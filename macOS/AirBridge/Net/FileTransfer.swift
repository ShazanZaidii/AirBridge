import Foundation
import UniformTypeIdentifiers

/// Per-session file transfers (§6). State lives on the session queue; disk I/O runs on
/// one serial queue per transfer.
nonisolated final class TransferService: @unchecked Sendable {
    private weak var session: Session?
    private let queue: DispatchQueue
    private var incoming: [UInt32: IncomingTransfer] = [:]
    private var outgoing: [UInt32: OutgoingTransfer] = [:]
    // Ids are per sender, so both directions can use the same number; starting high (but below 2^31
    // for peers that parse ints as signed 32-bit) keeps our ids apart from a peer counting from 1.
    private var nextId = UInt32.random(in: 0x4000_0000..<0x7000_0000)

    static let maxConcurrentIncoming = 8

    init(session: Session, queue: DispatchQueue) {
        self.session = session
        self.queue = queue
    }

    static var destination: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AirBridge Transfers", isDirectory: true)
    }

    func handle(_ t: String, _ msg: [String: Any]) {
        guard let raw = msg.int64("id"), raw >= 0, raw <= Int64(UInt32.max) else { return }
        let id = UInt32(raw)
        switch t {
        case "xfer_offer": offer(id, msg)
        case "xfer_accept": outgoing[id]?.accepted(window: msg.int64("window"))
        case "xfer_reject": outgoing[id]?.finish(.failed("Declined (\(msg.str("reason") ?? "user"))"))
        case "xfer_ack": outgoing[id]?.acked(msg.int64("received") ?? 0)
        case "xfer_complete":
            outgoing[id]?.finish(msg.bool("ok") == false ? .failed("Receiver reported an error") : .done)
        case "xfer_done": incoming[id]?.senderDone()
        case "xfer_cancel":
            let why = "Cancelled by peer (\(msg.str("reason") ?? "user"))"
            // `dir` is from the peer's point of view: its "out" is our incoming transfer.
            switch msg.str("dir") {
            case "out": incoming[id]?.abort(notifyPeer: nil, message: why)
            case "in": outgoing[id]?.finish(.failed(why), notifyPeer: nil)
            default:
                if let out = outgoing[id] { out.finish(.failed(why), notifyPeer: nil) }
                else { incoming[id]?.abort(notifyPeer: nil, message: why) }
            }
        default: break
        }
    }

    func handleChunk(_ data: Data) {
        guard let c = Proto.parseChunk(data) else { return }
        incoming[c.transfer]?.chunk(c)
    }

    /// Starts sending `urls`. `cleanup` runs once the transfer ends (any outcome).
    func send(urls: [URL], clip: Bool = false, cleanup: (() -> Void)?) {
        guard let session else { cleanup?(); return }
        let id = nextId
        nextId &+= 1
        let t = OutgoingTransfer(id: id, urls: urls, clip: clip, session: session, queue: queue, cleanup: cleanup) { [weak self] in
            self?.outgoing[id] = nil
        }
        outgoing[id] = t
        t.start()
    }

    func cancelOutgoing(_ id: UInt32) {
        outgoing[id]?.finish(.failed(TransferStatus.cancelledMessage), notifyPeer: "user")
    }

    /// The user stopped a file coming in: tell the sender and delete what was written.
    func cancelIncoming(_ id: UInt32) {
        incoming[id]?.abort(notifyPeer: "user", message: TransferStatus.cancelledMessage)
    }

    /// Session ended: delete partial files, stop senders.
    func teardown() {
        for t in incoming.values { t.abort(notifyPeer: nil, message: "Disconnected") }
        for t in outgoing.values { t.finish(.failed("Disconnected"), notifyPeer: nil) }
        incoming.removeAll()
        outgoing.removeAll()
    }

    private func offer(_ id: UInt32, _ msg: [String: Any]) {
        guard let session else { return }
        let reject = { (reason: String) in session.send(["t": "xfer_reject", "id": Int(id), "reason": reason]) }
        guard incoming[id] == nil, incoming.count < TransferService.maxConcurrentIncoming else { reject("busy"); return }
        guard let list = msg["files"] as? [[String: Any]], list.count <= 10_000 else { reject("user"); return }
        var files: [(name: String, size: Int64)] = Array(repeating: ("", 0), count: list.count)
        var seen = Set<Int>()
        for f in list {
            guard let i = f.int("i"), i >= 0, i < list.count, !seen.contains(i),
                  let size = f.int64("size"), size >= 0 else { reject("user"); return }
            seen.insert(i)
            files[i] = (Proto.sanitizeFileName(f.str("name") ?? ""), size)
        }
        let total = files.reduce(Int64(0)) { $0 + $1.size }
        // A clipboard transfer (§5.1) is kept in a private cache folder, not in the downloads folder.
        let clip = msg.bool("clip") == true
        let dir = clip ? ClipboardInbox.directory : TransferService.destination
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let free = try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage, free < total {
            reject("disk"); return
        }
        let t = IncomingTransfer(id: id, files: files, total: total, dir: dir, clip: clip, session: session, queue: queue) { [weak self] in
            self?.incoming[id] = nil
        }
        incoming[id] = t
        session.send(["t": "xfer_accept", "id": Int(id), "window": Proto.defaultWindow])
        t.reportProgress(force: true)
    }
}

// MARK: - Receiving

nonisolated final class IncomingTransfer: @unchecked Sendable {
    let id: UInt32
    private let files: [(name: String, size: Int64)]
    private let total: Int64
    private let dir: URL
    private let clip: Bool
    private weak var session: Session?
    private let peerName: String
    private let peerId: String
    private let queue: DispatchQueue
    private let io = DispatchQueue(label: "com.shazan.airbridge.xfer.write", qos: .userInitiated)
    private let onEnd: () -> Void
    private let key: String

    // Session-queue state.
    private var queuedBytes: Int64 = 0
    private var writtenBytes: Int64 = 0
    private var lastAcked: Int64 = 0
    private var ended = false
    private var startedAt = Date()
    private var lastReport = Date.distantPast
    private var savedURLs: [URL] = []

    // io-queue state.
    private var fds: [Int: Int32] = [:]
    private var urls: [Int: URL] = [:]
    private var written: [Int: Int64] = [:]
    private var completeFiles = Set<Int>()
    private var ioCancelled = false

    init(id: UInt32, files: [(name: String, size: Int64)], total: Int64, dir: URL, clip: Bool, session: Session,
         queue: DispatchQueue, onEnd: @escaping () -> Void) {
        self.id = id
        self.files = files
        self.total = total
        self.dir = dir
        self.clip = clip
        self.session = session
        self.peerName = session.peerName
        self.peerId = session.peerId
        self.queue = queue
        self.onEnd = onEnd
        self.key = "in-\(session.peerId)-\(id)-\(UUID().uuidString.prefix(4))"
        TransferRegistry.register(key: key, peerId: session.peerId, id: id, incoming: true)
    }

    func chunk(_ c: Proto.Chunk) {
        guard !ended else { return }
        let f = Int(c.file)
        let len = Int64(c.payload.count)
        guard f < files.count, c.offset <= UInt64(files[f].size), UInt64(files[f].size) - c.offset >= UInt64(len) else {
            abort(notifyPeer: "error", message: "Invalid data from sender")
            return
        }
        queuedBytes += len
        // The sender may not exceed the window; acks follow disk writes, so this bounds memory.
        if queuedBytes - writtenBytes > Int64(Proto.defaultWindow + 2 * Proto.chunkSize) {
            abort(notifyPeer: "error", message: "Sender exceeded flow-control window")
            return
        }
        io.async { [self] in
            guard !ioCancelled else { return }
            let ok = write(file: f, offset: c.offset, payload: c.payload)
            let fileDone = completeFiles.contains(f)
            queue.async { [self] in
                guard !ended else { return }
                if !ok { abort(notifyPeer: "error", message: "Could not write to disk"); return }
                writtenBytes += len
                if writtenBytes - lastAcked >= Int64(Proto.ackEvery) || fileDone { sendAck() }
                reportProgress(force: false)
            }
        }
    }

    func senderDone() {
        guard !ended else { return }
        io.async { [self] in
            guard !ioCancelled else { return }
            var ok = true
            for (i, f) in files.enumerated() where f.size == 0 && !completeFiles.contains(i) {
                guard let (fd, url) = openUnique(files[i].name) else { ok = false; break }
                close(fd)
                urls[i] = url
                completeFiles.insert(i)
            }
            let allDone = ok && completeFiles.count == files.count
            let saved = (0..<files.count).compactMap { urls[$0] }
            queue.async { [self] in
                guard !ended else { return }
                guard allDone else { abort(notifyPeer: "error", message: "Transfer incomplete"); return }
                if writtenBytes != lastAcked { sendAck() }
                session?.send(["t": "xfer_complete", "id": Int(id), "ok": true,
                               "saved": saved.map { $0.lastPathComponent }])
                savedURLs = saved
                end(.done)
                let from = peerName
                if clip { onMain { TransferUI.receivedClipboard(saved, from: from) } }
                else { onMain { TransferUI.receivedFiles(saved, from: from) } }
            }
        }
    }

    /// Stops the transfer and deletes incomplete files. `notifyPeer` is the `xfer_cancel` reason, if any.
    func abort(notifyPeer reason: String?, message: String) {
        guard !ended else { return }
        if let reason { session?.send(["t": "xfer_cancel", "id": Int(id), "dir": "in", "reason": reason]) }
        end(.failed(message))
        io.async { [self] in
            ioCancelled = true
            for (i, fd) in fds { close(fd); fds[i] = nil }
            for (i, url) in urls where !completeFiles.contains(i) { try? FileManager.default.removeItem(at: url) }
        }
    }

    private func end(_ phase: TransferStatus.Phase) {
        ended = true
        onEnd()
        TransferRegistry.unregister(key: key)
        report(phase)
    }

    private func sendAck() {
        lastAcked = writtenBytes
        session?.send(["t": "xfer_ack", "id": Int(id), "received": writtenBytes])
    }

    func reportProgress(force: Bool) {
        let now = Date()
        guard force || now.timeIntervalSince(lastReport) > 0.25 else { return }
        lastReport = now
        report(.active)
    }

    private func report(_ phase: TransferStatus.Phase) {
        if clip { return } // a clipboard transfer is quiet: no progress card
        let elapsed = Date().timeIntervalSince(startedAt)
        let s = TransferStatus(id: key, direction: .incoming, peerName: peerName, fileCount: files.count,
                               firstName: files.first?.name ?? "", total: total, done: writtenBytes, phase: phase,
                               bytesPerSec: elapsed > 0.5 ? Double(writtenBytes) / elapsed : 0,
                               peerId: peerId, savedPaths: savedURLs.map(\.path))
        onMain { TransferUI.update(s) }
    }

    // io queue only.
    private func write(file f: Int, offset: UInt64, payload: Data) -> Bool {
        if fds[f] == nil {
            guard written[f] == nil, let (fd, url) = openUnique(files[f].name) else { return false }
            fds[f] = fd
            urls[f] = url
            written[f] = 0
        }
        guard let fd = fds[f] else { return false }
        let ok = payload.withUnsafeBytes { raw -> Bool in
            var done = 0
            while done < raw.count {
                let n = pwrite(fd, raw.baseAddress! + done, raw.count - done, off_t(offset) + off_t(done))
                if n <= 0 { if n < 0 && errno == EINTR { continue }; return false }
                done += n
            }
            return true
        }
        guard ok else { return false }
        written[f, default: 0] += Int64(payload.count)
        if written[f]! >= files[f].size {
            close(fd)
            fds[f] = nil
            completeFiles.insert(f)
        }
        return true
    }

    /// Creates a new file without ever overwriting: `name`, `name (2).ext`, `name (3).ext`, …
    private func openUnique(_ name: String) -> (Int32, URL)? {
        let ext = (name as NSString).pathExtension
        let base = ext.isEmpty ? name : (name as NSString).deletingPathExtension
        for n in 1...9999 {
            let candidate = n == 1 ? name : "\(base) (\(n))" + (ext.isEmpty ? "" : ".\(ext)")
            let url = dir.appendingPathComponent(candidate, isDirectory: false)
            let fd = open(url.path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0o644)
            if fd >= 0 { return (fd, url) }
            if errno != EEXIST { return nil }
        }
        return nil
    }
}

// MARK: - Sending

nonisolated final class OutgoingTransfer: @unchecked Sendable {
    let id: UInt32
    private struct File { let url: URL; let name: String; let size: Int64 }
    private let files: [File]
    private let total: Int64
    private let clip: Bool
    private weak var session: Session?
    private let peerName: String
    private let peerId: String
    private let queue: DispatchQueue
    private let io = DispatchQueue(label: "com.shazan.airbridge.xfer.read", qos: .userInitiated)
    private let cleanup: (() -> Void)?
    private let onEnd: () -> Void
    private let key: String

    private var window: Int64 = 0
    private var sent: Int64 = 0
    private var acked: Int64 = 0
    private var fileIndex = 0
    private var fileOffset: Int64 = 0
    private var reading = false
    private var inFlight = 0
    private var doneSent = false
    private var ended = false
    private var startedAt = Date()
    private var lastReport = Date.distantPast

    // io-queue state.
    private var handle: FileHandle?
    private var handleIndex = -1

    init(id: UInt32, urls: [URL], clip: Bool = false, session: Session, queue: DispatchQueue, cleanup: (() -> Void)?,
         onEnd: @escaping () -> Void) {
        self.id = id
        self.clip = clip
        self.files = urls.compactMap { url in
            guard let v = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  v.isRegularFile == true else { return nil }
            return File(url: url, name: url.lastPathComponent, size: Int64(v.fileSize ?? 0))
        }
        self.total = files.reduce(0) { $0 + $1.size }
        self.session = session
        self.peerName = session.peerName
        self.peerId = session.peerId
        self.queue = queue
        self.cleanup = cleanup
        self.onEnd = onEnd
        self.key = "out-\(session.peerId)-\(id)-\(UUID().uuidString.prefix(4))"
        TransferRegistry.register(key: key, peerId: session.peerId, id: id, incoming: false)
    }

    func start() {
        guard !files.isEmpty else { finish(.failed("Nothing to send"), notifyPeer: nil); return }
        let list: [[String: Any]] = files.enumerated().map { i, f in
            var d: [String: Any] = ["i": i, "name": f.name, "size": f.size]
            if let mime = f.url.mimeType { d["mime"] = mime }
            return d
        }
        var offer: [String: Any] = ["t": "xfer_offer", "id": Int(id), "files": list, "total": total]
        if clip { offer["clip"] = true }
        session?.send(offer)
        report(.waiting)
        queue.asyncAfter(deadline: .now() + 60) { [weak self] in
            guard let self, !self.ended, self.window == 0 else { return }
            self.finish(.failed("No answer from \(self.peerName)"), notifyPeer: "error")
        }
    }

    func accepted(window w: Int64?) {
        guard !ended, window == 0 else { return }
        window = min(max(w ?? Int64(Proto.defaultWindow), Int64(Proto.chunkSize)), 64 << 20)
        startedAt = Date()
        report(.active)
        pump()
    }

    func acked(_ received: Int64) {
        guard !ended else { return }
        acked = max(acked, min(received, sent))
        let now = Date()
        if now.timeIntervalSince(lastReport) > 0.25 { lastReport = now; report(.active) }
        pump()
    }

    private func pump() {
        guard !ended, window > 0, !reading, !doneSent, let session else { return }
        while fileIndex < files.count && fileOffset >= files[fileIndex].size {
            fileIndex += 1
            fileOffset = 0
        }
        if fileIndex >= files.count {
            doneSent = true
            session.send(["t": "xfer_done", "id": Int(id)])
            report(.active)
            return
        }
        let len = Int(min(Int64(Proto.chunkSize), files[fileIndex].size - fileOffset))
        // Window from §6.1, plus a small local queue so control frames are not stuck behind file data.
        guard sent - acked + Int64(len) <= window, inFlight < 2 else { return }
        reading = true
        let (fi, off, url) = (fileIndex, fileOffset, files[fileIndex].url)
        io.async { [self] in
            var data: Data?
            if handleIndex != fi {
                try? handle?.close()
                handle = try? FileHandle(forReadingFrom: url)
                handleIndex = fi
            }
            if let h = handle {
                try? h.seek(toOffset: UInt64(off))
                data = try? h.read(upToCount: len)
            }
            queue.async { [self] in
                reading = false
                guard !ended else { return }
                guard let data, data.count == len else {
                    finish(.failed("Could not read \(url.lastPathComponent)"), notifyPeer: "error")
                    return
                }
                var frame = Proto.chunkHeader(transfer: id, file: UInt32(fi), offset: UInt64(off))
                frame.append(data)
                inFlight += 1
                sent += Int64(len)
                fileOffset += Int64(len)
                self.session?.sendBinary(frame) { [weak self] ok in
                    guard let self else { return }
                    self.queue.async {
                        self.inFlight -= 1
                        if !ok { self.finish(.failed("Connection lost"), notifyPeer: nil) } else { self.pump() }
                    }
                }
                pump()
            }
        }
    }

    func finish(_ phase: TransferStatus.Phase, notifyPeer reason: String? = nil) {
        guard !ended else { return }
        ended = true
        if let reason { session?.send(["t": "xfer_cancel", "id": Int(id), "dir": "out", "reason": reason]) }
        if phase == .done { acked = total }
        report(phase)
        onEnd()
        TransferRegistry.unregister(key: key)
        io.async { [self] in try? handle?.close(); handle = nil }
        if let cleanup { DispatchQueue.global(qos: .utility).async { cleanup() } }
    }

    private func report(_ phase: TransferStatus.Phase) {
        if clip { return } // a clipboard transfer is quiet: no progress card
        let elapsed = Date().timeIntervalSince(startedAt)
        let s = TransferStatus(id: key, direction: .outgoing, peerName: peerName, fileCount: files.count,
                               firstName: files.first?.name ?? "", total: total, done: acked, phase: phase,
                               bytesPerSec: elapsed > 0.5 && phase == .active ? Double(acked) / elapsed : 0,
                               peerId: peerId, sourcePaths: files.map(\.url.path))
        onMain { TransferUI.update(s) }
    }
}

/// Maps UI transfer keys back to (peer, transfer id, direction) so the UI can cancel.
nonisolated enum TransferRegistry {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var map: [String: (peerId: String, id: UInt32, incoming: Bool)] = [:]

    static func register(key: String, peerId: String, id: UInt32, incoming: Bool) {
        lock.lock(); map[key] = (peerId, id, incoming); lock.unlock()
    }
    static func unregister(key: String) {
        lock.lock(); map[key] = nil; lock.unlock()
    }
    static func lookup(_ key: String) -> (peerId: String, id: UInt32, incoming: Bool)? {
        lock.lock(); defer { lock.unlock() }; return map[key]
    }
}

nonisolated private extension URL {
    var mimeType: String? {
        (try? resourceValues(forKeys: [.contentTypeKey]))?.contentType?.preferredMIMEType
    }
}
