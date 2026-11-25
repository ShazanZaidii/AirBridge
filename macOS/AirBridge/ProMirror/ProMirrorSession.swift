import AppKit
import CoreMedia
import CoreVideo

/// What the mirror window drives: normal mirroring (PhoneMirrorSession, AirBridge protocol §8.7) or Pro
/// mirroring (ProMirrorSession, Wireless debugging + scrcpy server). Callbacks may come on any queue.
nonisolated protocol PhoneMirrorBackend: AnyObject, Sendable {
    var onState: (@Sendable (PhoneMirrorState) -> Void)? { get set }
    var onFrame: (@Sendable (CVPixelBuffer) -> Void)? { get set }
    func begin()
    func end()
}

extension PhoneMirrorSession: PhoneMirrorBackend {
    nonisolated func begin() { start() }
    nonisolated func end() { stop() }
}

/// Pro mirroring for one phone: finds it over mDNS, `adb connect`s it with this Mac's paired key, pushes and
/// starts the bundled scrcpy server, then reads H.264 from the video socket straight into the hardware decoder
/// (no buffering) and writes input as scrcpy control messages. No screen-recording prompt or chip on the
/// phone, no accessibility service needed for input.
///
/// Threads: `queue` owns state and the input translator; the video socket is read and decoded on its own
/// thread; control messages are written on `writeQueue`; device messages (clipboard) read on another thread.
nonisolated final class ProMirrorSession: PhoneMirrorBackend, @unchecked Sendable {
    let peerId: String
    let name: String

    var onState: (@Sendable (PhoneMirrorState) -> Void)?
    var onFrame: (@Sendable (CVPixelBuffer) -> Void)?
    /// A failure (any queue). `streamed` = a picture had been shown before it ended.
    var onFailure: (@Sendable (ProMirrorError, _ streamed: Bool) -> Void)?

    private let queue: DispatchQueue
    private let writeQueue: DispatchQueue
    private var state = PhoneMirrorState()
    private var generation = 0
    private var active = false
    private var everStreamed = false
    private var serial: String?
    private var forwardPort: Int?
    private var server: Process?
    private var serverError: ProMirrorError?
    private var videoFD: Int32 = -1
    private var controlFD: Int32 = -1
    private var input = ProMirrorInput()
    private var screenOff = false
    private var lastClipFromPhone: String?

    init(peerId: String, name: String) {
        self.peerId = peerId
        self.name = name
        queue = DispatchQueue(label: "com.airbridge.promirror.\(peerId.prefix(8))", qos: .userInteractive)
        writeQueue = DispatchQueue(label: "com.airbridge.promirror.write.\(peerId.prefix(8))", qos: .userInteractive)
    }

    // MARK: PhoneMirrorBackend

    func begin() {
        queue.async { [self] in
            teardown()
            generation += 1
            active = true
            everStreamed = false
            serverError = nil
            input = ProMirrorInput()
            update { s in
                s = PhoneMirrorState()
                s.control = true      // scrcpy injects as the shell user: no accessibility service needed
                s.typing = true
            }
            let gen = generation
            Task.detached(priority: .userInitiated) { [self] in await connect(gen) }
        }
    }

    func end() {
        queue.async { [self] in
            guard active else { return }
            active = false
            generation += 1
            teardown()
        }
    }

    // MARK: Controls from the window (main thread)

    /// Input in the mirror window's JSON vocabulary (see ProMirrorInput).
    func input(_ msg: [String: Any]) {
        let clip = Self.wantsPaste(msg) ? NSPasteboard.general.string(forType: .string) : nil
        let box = MsgBox(msg)
        queue.async { [self] in
            guard active else { return }
            let out = input.translate(box.msg, pasteText: { clip })
            send(out)
        }
    }

    func setScreenOff(_ off: Bool) {
        ProMirrorStore.update(peerId) { $0.screenOff = off }
        queue.async { [self] in
            screenOff = off
            guard active, controlFD >= 0 else { return }
            send([.setDisplayPower(on: !off)])
        }
    }

    func rotate() { queue.async { [self] in if active { send([.rotateDevice]) } } }

    private static func wantsPaste(_ m: [String: Any]) -> Bool {
        switch m["t"] as? String {
        case "action": return m["name"] as? String == "paste"
        case "key":
            return (m["down"] as? Bool ?? false) && Int(ProMirrorInput.num(m["hid"]) ?? 0) == 0x19
                && Int(ProMirrorInput.num(m["mods"]) ?? 0) & 8 != 0
        default: return false
        }
    }

    // MARK: Connecting (detached task; checks `generation` between steps)

    private func current(_ gen: Int) -> Bool { queue.sync { active && generation == gen } }

    private func connect(_ gen: Int) async {
        guard let record = ProMirrorStore.record(peerId) else { return fail(.notSetUp, gen) }
        guard ADB.binary != nil, let jar = Bundle.main.url(forResource: Scrcpy.serverResource, withExtension: nil) else {
            return fail(.adbMissing, gen)
        }
        queue.sync { screenOff = record.screenOff }
        guard current(gen) else { return }
        let serial: String
        switch await ADBLink.connect(peerId: peerId, record: record) {
        case .success(let s): serial = s
        case .failure(let e): return fail(e, gen)
        }
        guard current(gen) else { return }
        queue.sync { self.serial = serial }

        // 3. The server jar (only when the phone's copy differs in size).
        let localSize = (try? FileManager.default.attributesOfItem(atPath: jar.path)[.size] as? NSNumber)?.intValue ?? -1
        let remote = await ADB.run(ADB.remoteSize(serial, path: Scrcpy.devicePath), timeout: 6)
        if Int(remote.out.trimmingCharacters(in: .whitespacesAndNewlines)) != localSize {
            let push = await ADB.run(ADB.push(serial, local: jar.path, remote: Scrcpy.devicePath), timeout: 40)
            if push.status != 0 { return fail(.serverFailed(ADB.firstLine(push.text)), gen) }
        }
        guard current(gen) else { return }

        // Panel size and density for scroll speed (best effort).
        let wm = await ADB.run(ADB.screenInfo(serial), timeout: 5)
        let info = WMInfo.parse(wm.out)
        queue.sync {
            input.physicalLong = max(info.width, info.height)
            input.density = info.density
        }

        // 4. Tunnel + server.
        let scid = Scrcpy.randomSCID()
        let fwd = await ADB.run(ADB.forward(serial, scid: scid), timeout: 6)
        guard let port = ADB.parseForwardPort(fwd.out) else { return fail(.serverFailed(ADB.firstLine(fwd.text)), gen) }
        let stillWanted: Bool = queue.sync {
            guard active, generation == gen else { return false }
            forwardPort = port
            return true
        }
        guard stillWanted else {
            _ = await ADB.run(ADB.removeForward(serial, port: port), timeout: 5)
            return
        }

        let opts = Scrcpy.Options(scid: scid)
        let proc = ADB.spawn(ADB.startServer(serial, opts), onLine: { [weak self] line in
            guard let self else { return }
            NSLog("[AirBridge] pro mirror server: %@", line)
            if let e = ADB.serverFailure(line) { self.queue.async { if self.serverError == nil { self.serverError = e } } }
        }, onExit: { [weak self] status in
            guard let self else { return }
            self.queue.async {
                guard self.active, self.generation == gen else { return }
                let e = self.serverError ?? (self.everStreamed ? .streamEnded : .serverFailed(""))
                self.failLocked(e)
            }
        })
        guard let proc else { return fail(.adbMissing, gen) }
        let keep: Bool = queue.sync {
            guard active, generation == gen else { return false }
            server = proc
            return true
        }
        guard keep else { proc.terminate(); return }   // the window closed meanwhile; teardown already ran

        // 5. Sockets: video first (the dummy byte proves the server is listening), then control.
        guard let vfd = await openVideoSocket(port: port, gen: gen) else {
            let e = queue.sync { serverError } ?? .timeout
            return fail(e, gen)
        }
        guard let cfd = Self.connectLocal(port: port) else { close(vfd); return fail(.streamEnded, gen) }
        guard Self.readFully(vfd, Scrcpy.deviceNameLength) != nil,
              let codecRaw = Self.readFully(vfd, 4) else {
            close(vfd); close(cfd)
            return fail(queue.sync { serverError } ?? .serverFailed(""), gen)
        }
        switch Scrcpy.streamStart(Scrcpy.be32(Array(codecRaw), 0)) {
        case .codec(Scrcpy.codecH264): break
        case .codec: close(vfd); close(cfd); return fail(.serverFailed("unexpected codec"), gen)
        case .disabled: close(vfd); close(cfd); return fail(.serverFailed("video unavailable"), gen)
        case .error: close(vfd); close(cfd); return fail(queue.sync { serverError } ?? .serverFailed("configuration"), gen)
        }

        let ok: Bool = queue.sync {
            guard active, generation == gen else { return false }
            videoFD = vfd
            controlFD = cfd
            if screenOff { send([.setDisplayPower(on: false)]) }
            return true
        }
        guard ok else { close(vfd); close(cfd); return }
        startVideoThread(fd: vfd, gen: gen)
        startControlReader(fd: cfd, gen: gen)
    }

    private func openVideoSocket(port: Int, gen: Int) async -> Int32? {
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline {
            guard current(gen), queue.sync(execute: { serverError }) == nil else { return nil }
            if let fd = Self.connectLocal(port: port) {
                Self.setReadTimeout(fd, seconds: 2)
                if let b = Self.readFully(fd, 1), b.count == 1 {
                    Self.setReadTimeout(fd, seconds: 0)
                    return fd
                }
                close(fd)   // adb accepted, but nothing listens on the phone yet
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return nil
    }

    // MARK: Video (own thread)

    private func startVideoThread(fd: Int32, gen: Int) {
        let t = Thread { [weak self] in self?.videoLoop(fd: fd, gen: gen) }
        t.name = "AirBridge Pro mirror video"
        t.qualityOfService = .userInteractive
        t.start()
    }

    private func videoLoop(fd: Int32, gen: Int) {
        let decoder = MirrorDecoder(codec: .h264) { [weak self] image, _ in
            guard let self else { return }
            self.onFrame?(image)
            self.queue.async {
                guard self.generation == gen, !self.state.hasPicture else { return }
                self.everStreamed = true
                self.update { $0.hasPicture = true }
            }
        }
        var assembler = Scrcpy.VideoAssembler()
        var lastReset = 0.0
        defer { decoder.invalidate() }
        while true {
            guard let h = Self.readFully(fd, Scrcpy.headerSize), let header = Scrcpy.parseHeader(Array(h)) else { break }
            var payload = Data()
            if case let .media(_, _, _, size) = header {
                guard size > 0, size < Scrcpy.maxPacket, let p = Self.readFully(fd, size) else { break }
                payload = p
            }
            switch assembler.packet(header, payload: payload) {
            case let .session(w, hgt):
                queue.async { [self] in
                    guard generation == gen else { return }
                    send(input.setFrame(width: w, height: hgt))
                    update { s in
                        s.phase = .streaming
                        s.streamW = w; s.streamH = hgt
                        s.displayW = w; s.displayH = hgt
                    }
                }
            case let .accessUnit(au, pts, key):
                switch decoder.decode(au, ptsUs: pts, keyframe: key) {
                case .needKeyframe:
                    let now = CACurrentMediaTimeCompat()
                    if now - lastReset > 1 {
                        lastReset = now
                        queue.async { [self] in if generation == gen { send([.resetVideo]) } }
                    }
                case .decoded, .dropped: break
                }
            case .none:
                break
            }
        }
        finishVideo(gen)
    }

    private func finishVideo(_ gen: Int) {
        queue.async { [self] in
            guard active, generation == gen else { return }
            failLocked(serverError ?? .streamEnded)
        }
    }

    // MARK: Control socket

    private func send(_ controls: [Scrcpy.Control]) {
        guard !controls.isEmpty, controlFD >= 0 else { return }
        let fd = controlFD
        var data = Data()
        for c in controls { data.append(c.bytes) }
        writeQueue.async { _ = Self.writeAll(fd, data) }
    }

    private func startControlReader(fd: Int32, gen: Int) {
        let t = Thread { [weak self] in
            var parser = Scrcpy.DeviceMessageParser()
            var buf = [UInt8](repeating: 0, count: 64 * 1024)
            while true {
                let n = recv(fd, &buf, buf.count, 0)
                if n <= 0 { break }
                for m in parser.feed(Data(buf[0..<n])) {
                    if case .clipboard(let text) = m { self?.phoneClipboard(text) }
                }
                if parser.failed { break }
            }
        }
        t.name = "AirBridge Pro mirror control"
        t.qualityOfService = .userInitiated
        t.start()
    }

    /// The phone's clipboard changed (or answered ⌘C/⌘X): put it on the Mac.
    private func phoneClipboard(_ text: String) {
        DispatchQueue.main.async {
            let pb = NSPasteboard.general
            guard !text.isEmpty, pb.string(forType: .string) != text else { return }
            pb.clearContents()
            pb.setString(text, forType: .string)
        }
    }

    // MARK: State

    private func fail(_ e: ProMirrorError, _ gen: Int) {
        queue.async { [self] in
            guard active, generation == gen else { return }
            failLocked(e)
        }
    }

    /// On `queue`.
    private func failLocked(_ e: ProMirrorError) {
        let streamed = everStreamed
        active = false
        generation += 1
        teardown()
        NSLog("[AirBridge] pro mirror: %@", e.message(phone: name))
        update { $0.phase = .stopped(e.message(phone: name)) }
        onFailure?(e, streamed)
    }

    /// On `queue`: close everything this attempt opened.
    private func teardown() {
        if controlFD >= 0 {
            let fd = controlFD
            send(input.releaseAll())
            if screenOff { send([.setDisplayPower(on: true)]) }
            writeQueue.async { shutdown(fd, SHUT_RDWR); close(fd) }
            controlFD = -1
        }
        if videoFD >= 0 { shutdown(videoFD, SHUT_RDWR); close(videoFD); videoFD = -1 }
        if let p = server, p.isRunning { p.terminate() }
        server = nil
        if let serial, let port = forwardPort {
            Task.detached { _ = await ADB.run(ADB.removeForward(serial, port: port), timeout: 5) }
        }
        forwardPort = nil
    }

    private func update(_ f: (inout PhoneMirrorState) -> Void) {
        let before = state
        f(&state)
        if state != before { onState?(state) }
    }

    // MARK: Sockets

    static func connectLocal(port: Int) -> Int32? {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(UInt16(port).bigEndian)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard r == 0 else { close(fd); return nil }
        var one: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        return fd
    }

    static func setReadTimeout(_ fd: Int32, seconds: Int) {
        var tv = timeval(tv_sec: seconds, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    }

    /// Exactly `n` bytes, or nil on EOF / error / timeout.
    static func readFully(_ fd: Int32, _ n: Int) -> Data? {
        var d = Data(count: n)
        var got = 0
        let ok: Bool = d.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return n == 0 }
            while got < n {
                let r = recv(fd, base + got, n - got, 0)
                if r <= 0 { return false }
                got += r
            }
            return true
        }
        return ok ? d : nil
    }

    static func writeAll(_ fd: Int32, _ data: Data) -> Bool {
        data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return true }
            var sent = 0
            while sent < raw.count {
                let r = Darwin.send(fd, base + sent, raw.count - sent, 0)
                if r <= 0 { return false }
                sent += r
            }
            return true
        }
    }
}

/// Carries a JSON dictionary across queues (the window builds it, the session's queue reads it).
nonisolated private struct MsgBox: @unchecked Sendable {
    let msg: [String: Any]
    init(_ m: [String: Any]) { msg = m }
}
