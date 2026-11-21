import Foundation

/// Why Pro mirroring can't go on, each with one plain sentence and the next step.
nonisolated enum ProMirrorError: Error, Equatable, Sendable {
    case notSetUp
    case wirelessDebuggingOff
    case notOnNetwork
    case wrongCode
    case pairingFailed(String)
    case notPaired
    case unauthorized
    case offline
    case serverMismatch
    case serverFailed(String)
    case adbMissing
    case timeout
    case streamEnded
    case other(String)

    func message(phone: String) -> String {
        switch self {
        case .notSetUp: return "Pro mirroring isn't set up for \(phone) yet. Choose Set Up Pro Mirroring."
        case .wirelessDebuggingOff: return "Wireless debugging is off on \(phone). Turn it on in Developer options, then try again."
        case .notOnNetwork: return "\(phone) isn't on this Wi-Fi. Join the same network as this Mac, then try again."
        case .wrongCode: return "That code didn't work. Check the 6 digits on the phone and enter them again."
        case .pairingFailed(let d): return d.isEmpty ? "Pairing didn't finish. Open the pairing code again on the phone and retry." : "Pairing didn't finish (\(d)). Open the pairing code again on the phone and retry."
        case .notPaired: return "\(phone) no longer trusts this Mac. Set up Pro mirroring again to pair."
        case .unauthorized: return "\(phone) hasn't allowed this Mac yet. Check the phone for a prompt, or pair again."
        case .offline: return "\(phone) stopped answering. Toggle Wireless debugging off and on, then try again."
        case .serverMismatch: return "The mirroring helper on \(phone) is out of date. Try again to replace it."
        case .serverFailed(let d): return d.isEmpty ? "\(phone) couldn't start screen capture. Try again." : "\(phone) couldn't start screen capture (\(d)). Try again."
        case .adbMissing: return "This copy of AirBridge is missing its Pro mirroring tools. Reinstall AirBridge."
        case .timeout: return "\(phone) took too long to answer. Check Wi-Fi, then try again."
        case .streamEnded: return "The connection to \(phone) ended. Try again."
        case .other(let d): return d
        }
    }

    /// Worth falling back to normal mirroring silently (the phone just isn't reachable over ADB right now).
    var canFallBack: Bool {
        switch self {
        case .wirelessDebuggingOff, .notOnNetwork, .timeout, .offline, .notPaired, .unauthorized, .notSetUp: return true
        default: return false
        }
    }
}

/// The bundled `adb` (Android SDK platform-tools, see ProMirrorAcknowledgements.txt): commands, environment
/// and output parsing. Pure parts are static and unit-tested; `run` spawns the binary.
///
/// Our adb never fights another adb on this Mac: its own server port, its own key in Application Support
/// (via HOME, which adb uses for ~/.android), no USB, no emulator scan, no mDNS auto-connect (we connect
/// explicitly), its own log file.
nonisolated enum ADB {
    static let serverPort = 27150

    static var supportDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support")
        return base.appendingPathComponent("AirBridge/ProMirror", isDirectory: true)
    }

    /// HOME for the adb subprocess; its key pair lives in `<home>/.android/adbkey{,.pub}`.
    static var homeDir: URL { supportDir.appendingPathComponent("adb-home", isDirectory: true) }

    static var binary: URL? {
        guard let u = Bundle.main.url(forResource: "adb", withExtension: nil),
              FileManager.default.isExecutableFile(atPath: u.path) else { return nil }
        return u
    }

    static func environment(home: String, logPath: String) -> [String: String] {
        [
            "HOME": home,
            "ANDROID_ADB_SERVER_PORT": String(serverPort),
            "ANDROID_ADB_LOG_PATH": logPath,
            "ADB_USB": "0",
            "ADB_EMU": "0",
            "ADB_MDNS_AUTO_CONNECT": "0",
            "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
            "LANG": "en_US.UTF-8",
        ]
    }

    static func prepareHome() {
        try? FileManager.default.createDirectory(at: homeDir.appendingPathComponent(".android"), withIntermediateDirectories: true)
    }

    // MARK: Commands (pure)

    static func hostPort(_ host: String, _ port: Int) -> String { host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)" }

    static func pair(host: String, port: Int, code: String) -> [String] { ["pair", hostPort(host, port), code] }
    static func connect(host: String, port: Int) -> [String] { ["connect", hostPort(host, port)] }
    static func disconnect(_ serial: String) -> [String] { ["disconnect", serial] }
    static func getState(_ serial: String) -> [String] { ["-s", serial, "get-state"] }
    static func push(_ serial: String, local: String, remote: String) -> [String] { ["-s", serial, "push", local, remote] }
    static func remoteSize(_ serial: String, path: String) -> [String] { ["-s", serial, "shell", "stat -c %s \(path) 2>/dev/null"] }
    static func forward(_ serial: String, scid: UInt32) -> [String] {
        ["-s", serial, "forward", "tcp:0", "localabstract:\(Scrcpy.socketName(scid: scid))"]
    }
    static func removeForward(_ serial: String, port: Int) -> [String] { ["-s", serial, "forward", "--remove", "tcp:\(port)"] }
    static func startServer(_ serial: String, _ o: Scrcpy.Options) -> [String] { ["-s", serial, "shell", Scrcpy.serverCommand(o)] }
    static func screenInfo(_ serial: String) -> [String] { ["-s", serial, "shell", "wm size; wm density"] }
    static func killServer() -> [String] { ["kill-server"] }

    // MARK: Output parsing (pure)

    enum PairResult: Equatable { case paired(guid: String?), failed(ProMirrorError) }

    static func parsePair(_ out: String, status: Int32) -> PairResult {
        let s = out.lowercased()
        if s.contains("successfully paired") {
            var guid: String?
            if let r = out.range(of: "[guid="), let end = out[r.upperBound...].firstIndex(of: "]") {
                guid = String(out[r.upperBound..<end])
            }
            return .paired(guid: guid)
        }
        if s.contains("wrong password") { return .failed(.wrongCode) }
        if s.contains("connection refused") || s.contains("no route") || s.contains("timed out") {
            return .failed(.pairingFailed("the phone closed the pairing screen"))
        }
        return .failed(.pairingFailed(firstLine(out)))
    }

    enum ConnectResult: Equatable { case connected(serial: String), failed(ProMirrorError) }

    static func parseConnect(_ out: String, host: String, port: Int) -> ConnectResult {
        let s = out.lowercased()
        let serial = hostPort(host, port)
        if s.contains("connected to") && !s.contains("failed") && !s.contains("cannot") { return .connected(serial: serial) }
        if s.contains("failed to authenticate") || s.contains("unauthorized") { return .failed(.notPaired) }
        if s.contains("connection refused") { return .failed(.wirelessDebuggingOff) }
        if s.contains("no route") || s.contains("host is down") || s.contains("network is unreachable") { return .failed(.notOnNetwork) }
        if s.contains("timed out") { return .failed(.timeout) }
        return .failed(.other(firstLine(out)))
    }

    static func parseState(_ out: String) -> ProMirrorError? {
        let s = out.lowercased()
        if s.trimmingCharacters(in: .whitespacesAndNewlines) == "device" { return nil }
        if s.contains("unauthorized") { return .unauthorized }
        if s.contains("offline") { return .offline }
        if s.contains("not found") { return .offline }
        return .other(firstLine(out))
    }

    /// `adb forward tcp:0 …` prints the port it picked.
    static func parseForwardPort(_ out: String) -> Int? {
        let t = out.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let p = Int(t), (1...65535).contains(p) else { return nil }
        return p
    }

    /// A line from the server's stdout/stderr that means it won't stream.
    static func serverFailure(_ line: String) -> ProMirrorError? {
        if line.contains("does not match the client") { return .serverMismatch }
        if line.contains("ClassNotFoundException") || line.contains("Unable to open zip") || line.contains("No such file") {
            return .serverMismatch
        }
        if line.contains("ERROR:") {
            let detail = line.components(separatedBy: "ERROR:").last?.trimmingCharacters(in: .whitespaces) ?? ""
            return .serverFailed(String(detail.prefix(120)))
        }
        return nil
    }

    static func firstLine(_ s: String) -> String {
        s.split(whereSeparator: \.isNewline).first.map { String($0).trimmingCharacters(in: .whitespaces) } ?? ""
    }

    // MARK: Running

    struct Output: Sendable {
        var status: Int32
        var out: String
        var err: String
        var timedOut: Bool
        var text: String { out + (err.isEmpty ? "" : "\n" + err) }
    }

    /// Runs `adb <args>` and waits (off the caller's thread) up to `timeout` seconds.
    static func run(_ args: [String], timeout: TimeInterval = 15) async -> Output {
        guard let bin = binary else { return Output(status: -1, out: "", err: "adb missing", timedOut: false) }
        prepareHome()
        return await withCheckedContinuation { (cont: CheckedContinuation<Output, Never>) in
            let p = Process()
            p.executableURL = bin
            p.arguments = args
            p.environment = environment(home: homeDir.path, logPath: supportDir.appendingPathComponent("adb-server.log").path)
            p.currentDirectoryURL = supportDir
            let o = Pipe(), e = Pipe()
            p.standardOutput = o
            p.standardError = e
            p.standardInput = FileHandle.nullDevice
            let box = RunBox()
            p.terminationHandler = { proc in
                let out = String(decoding: o.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
                let err = String(decoding: e.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
                box.finish(Output(status: proc.terminationStatus, out: out, err: err, timedOut: box.timedOut), cont)
            }
            do { try p.run() } catch {
                box.finish(Output(status: -1, out: "", err: error.localizedDescription, timedOut: false), cont)
                return
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                guard p.isRunning else { return }
                box.timedOut = true
                p.terminate()
            }
        }
    }

    private final class RunBox: @unchecked Sendable {
        private let lock = NSLock()
        private var done = false
        var timedOut = false
        func finish(_ o: Output, _ c: CheckedContinuation<Output, Never>) {
            lock.lock(); defer { lock.unlock() }
            guard !done else { return }
            done = true
            c.resume(returning: o)
        }
    }

    /// Long-running `adb` child (the scrcpy server's shell). Lines from stdout and stderr go to `onLine`.
    static func spawn(_ args: [String], onLine: @escaping @Sendable (String) -> Void,
                      onExit: @escaping @Sendable (Int32) -> Void) -> Process? {
        guard let bin = binary else { return nil }
        prepareHome()
        let p = Process()
        p.executableURL = bin
        p.arguments = args
        p.environment = environment(home: homeDir.path, logPath: supportDir.appendingPathComponent("adb-server.log").path)
        p.currentDirectoryURL = supportDir
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        p.standardInput = FileHandle.nullDevice
        let acc = LineSplitter(onLine)
        pipe.fileHandleForReading.readabilityHandler = { h in
            let d = h.availableData
            if d.isEmpty { h.readabilityHandler = nil; return }
            acc.feed(d)
        }
        p.terminationHandler = { proc in onExit(proc.terminationStatus) }
        do { try p.run() } catch { return nil }
        return p
    }

    private final class LineSplitter: @unchecked Sendable {
        private var buf = Data()
        private let lock = NSLock()
        private let onLine: @Sendable (String) -> Void
        init(_ f: @escaping @Sendable (String) -> Void) { onLine = f }
        func feed(_ d: Data) {
            lock.lock()
            buf.append(d)
            var lines: [String] = []
            while let i = buf.firstIndex(of: 0x0A) {
                lines.append(String(decoding: buf[buf.startIndex..<i], as: UTF8.self))
                buf.removeSubrange(buf.startIndex...i)
            }
            lock.unlock()
            lines.forEach(onLine)
        }
    }

    /// Stop our adb server (app quitting). Fire and forget, never blocks the caller.
    static func shutdown() {
        guard let bin = binary else { return }
        let p = Process()
        p.executableURL = bin
        p.arguments = killServer()
        p.environment = environment(home: homeDir.path, logPath: supportDir.appendingPathComponent("adb-server.log").path)
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try? p.run()
    }
}
