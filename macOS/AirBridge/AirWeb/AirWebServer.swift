import Foundation
import Darwin
import Security

/// One file in the shared pool. `added` is epoch milliseconds, for "1 min ago" on the page. `by`: who put it there
/// (a device label; nil = this Mac). `owner`: the client id of the page that uploaded it, so that page can mark it
/// "yours".
nonisolated struct AirWebFile: Sendable, Equatable {
    let id: Int
    let url: URL
    let name: String
    let size: Int64
    let mime: String?
    let added: Int64
    var by: String? = nil
    var owner: String? = nil
}

/// A browser that is connected (heard from within the TTL). `label` is unique among the connected ones.
nonisolated struct AirWebPeer: Sendable, Equatable {
    let id: String
    let label: String
    let joined: Int64
    let lastSeen: Int64
}

/// Every limit in one place (the same numbers as the Android app's, except the transfer slots: a Mac can take more).
nonisolated struct AirWebLimits: Sendable {
    /// Connections open at once (each holds a thread); more get 503 + Retry-After.
    var maxConnections = 128
    /// Connections from one address (a browser opens about six): one device can't starve the rest.
    var perIp = 24
    /// Files moving at once; the others wait their turn in the waiting room.
    var maxTransfers = 12
    /// Requests allowed to wait for a transfer slot; beyond that: 503 + Retry-After.
    var maxWaiting = 32
    var transferWaitMs = 120_000
    /// The whole request head must arrive within this (slow-client / half-open connection guard).
    var headDeadlineMs = 10_000
    /// No byte moving for this long (reading a body or writing a file): the connection is dropped.
    var idleSeconds = 30
    /// A browser not heard from for this long is no longer "connected".
    var peerTtlMs: Int64 = 20_000
    var maxPeers = 256
    /// Files in the pool; uploads beyond this are refused.
    var maxPool = 1000
}

/// What the server needs from its owner. Called from connection threads.
nonisolated protocol AirWebHost: AnyObject, Sendable {
    func deviceName() -> String
    /// The page, with `{{NAME}}` where the device name goes.
    func page() -> String
    /// The whole pool: what this Mac added and what browsers uploaded. Ids are unique across both.
    func offered() -> [AirWebFile]
    /// Bytes free for uploads, or -1 when unknown.
    func freeSpace() -> Int64
    /// Folder uploads are saved in. Throws if it can't be created.
    func uploadDirectory() throws -> URL
    /// A safe file name for an upload (no path parts, no control characters).
    func cleanName(_ raw: String) -> String
    /// The QR code of `text` as rows of modules (true = dark, no quiet zone), or nil if it can't be drawn.
    func qr(_ text: String) -> [[Bool]]?
    /// `by` is the uploading browser (nil when it didn't identify itself).
    func uploadStarted(id: Int, name: String, size: Int64, by: AirWebPeer?)
    func uploadProgress(id: Int, done: Int64)
    /// `saved` is nil when the upload failed.
    func uploadFinished(id: Int, saved: URL?)
    func downloaded(_ file: AirWebFile)
    /// The set of connected browsers changed. Called on a server thread: return quickly.
    func peersChanged(_ peers: [AirWebPeer])
}

/// AirWeb: a small HTTP/1.1 server so phones and computers without AirBridge can swap files through this Mac from
/// their browsers. Same contract as the Android app's AirWebServer, so the same page works. Everyone who opens the
/// link joins one **shared pool**: a file added here or uploaded by any browser is listed for, and downloadable by,
/// everyone else who is connected.
///
///  - `GET /` the page
///  - `GET /api/state?c=&w=&v=` JSON: device name, free space, poll interval, who is connected, the pool (omitted,
///    with `"same":true`, when `v` is the pool's current version)
///  - `GET|HEAD /d/<id>[/<name>]` download one pool file
///  - `POST /u?n=<file name>&c=&w=` upload one file into the pool, raw body, Content-Length required
///  - `GET /qr.svg` the QR code of this page's own link, so any connected browser can show it to invite more people
///
/// Every request needs the session token (`?t=…`, 128 random bits) or the answer is 403. Built to stay up with 50+
/// browsers (see `AirWebLimits`): one thread per connection up to a cap, a per-address cap, a head-read deadline
/// against slow clients, a fair waiting room for the transfer slots, space reserved for uploads in flight, socket
/// timeouts for stalled clients, and a state poll that answers "nothing changed" in a couple of hundred bytes.
/// `Connection: close` on every response, files streamed both ways (never held in memory).
nonisolated final class AirWebServer: @unchecked Sendable {
    static let ports: ClosedRange<UInt16> = 47650...47659
    /// Pool ids of uploaded files start here (the host's own files count up from 1).
    static let uploadIdBase = 1_000_000
    private static let bufSize = 64 * 1024
    private static let progressEvery: Int64 = 256 * 1024
    private static let maxPeersListed = 24

    /// 16 random bytes as 22 base64url characters.
    static func newToken() -> String {
        var b = [UInt8](repeating: 0, count: 16)
        if SecRandomCopyBytes(kSecRandomDefault, b.count, &b) != errSecSuccess {
            for i in b.indices { b[i] = UInt8.random(in: 0...255) }
        }
        return Data(b).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func url(address: String, port: UInt16, token: String) -> String {
        "http://\(address):\(port)/?t=\(token)"
    }

    private struct PeerRec {
        let id: String
        var label: String
        let joined: Int64
        var lastSeen: Int64
    }

    let token: String
    let limits: AirWebLimits
    private weak var host: AirWebHost?

    private let lock = NSLock()
    private var listenFd: Int32 = -1
    private var wakeFd: Int32 = -1
    private var open = Set<Int32>()
    private var connections = 0
    private var perIpCount: [String: Int] = [:]
    private var transfers = 0
    private var waiting = 0
    private var rejecting = 0
    private var uploadIds = 0
    private var boundPort: UInt16 = 0
    private var stopped = false
    private var reservedBytes: Int64 = 0
    private var peerMap: [String: PeerRec] = [:]
    private var lastPeerKey = ""
    private var sweeper: DispatchSourceTimer?
    private let slots: DispatchSemaphore

    init(token: String, host: AirWebHost, limits: AirWebLimits = AirWebLimits()) {
        self.token = token
        self.host = host
        self.limits = limits
        slots = DispatchSemaphore(value: limits.maxTransfers)
    }

    var port: UInt16 { lock.withLock { boundPort } }
    var isRunning: Bool { lock.withLock { listenFd >= 0 } }
    /// Downloads and uploads in progress right now.
    var activeTransfers: Int { lock.withLock { transfers } }
    /// Requests waiting for a transfer slot right now.
    var waitingTransfers: Int { lock.withLock { waiting } }
    var openConnections: Int { lock.withLock { connections } }
    private var isStopped: Bool { lock.withLock { stopped } }

    /// The connected browsers, oldest first.
    func peers() -> [AirWebPeer] { lock.withLock { snapshotLocked() } }

    /// Listens on `preferred` if it is free, else the first free port of `ports`. Returns the port.
    @discardableResult
    func start(preferred: UInt16 = 0, ports: ClosedRange<UInt16> = AirWebServer.ports) throws -> UInt16 {
        lock.lock()
        defer { lock.unlock() }
        if listenFd >= 0 { return boundPort }
        var candidates: [UInt16] = preferred > 0 ? [preferred] : []
        candidates += ports.filter { $0 != preferred }
        for p in candidates {
            guard let fd = Self.listen(on: p) else { continue }
            var pipeFds: [Int32] = [-1, -1]
            guard pipe(&pipeFds) == 0 else { close(fd); throw POSIXError(.EMFILE) }
            listenFd = fd
            wakeFd = pipeFds[1]
            boundPort = p
            stopped = false
            let wakeRead = pipeFds[0]
            let t = Thread { [self] in acceptLoop(fd, wakeRead) }
            t.name = "airweb-accept"
            t.start()
            let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
            timer.schedule(deadline: .now() + 2, repeating: 2)
            timer.setEventHandler { [weak self] in self?.sweep() }
            timer.resume()
            sweeper = timer
            return p
        }
        throw POSIXError(.EADDRINUSE)
    }

    /// Stops listening and cuts every open connection (unfinished uploads are discarded).
    func stop() {
        lock.lock()
        defer { lock.unlock() }
        guard listenFd >= 0 else { return }
        stopped = true
        var one: UInt8 = 1
        _ = write(wakeFd, &one, 1)
        close(wakeFd)
        wakeFd = -1
        listenFd = -1 // closed by the accept thread once it wakes
        boundPort = 0
        sweeper?.cancel()
        sweeper = nil
        peerMap.removeAll()
        lastPeerKey = ""
        for fd in open { shutdown(fd, SHUT_RDWR) }
    }

    private static func listen(on port: UInt16) -> Int32? {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        _ = fcntl(fd, F_SETFD, FD_CLOEXEC)
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0
            }
        }
        guard ok, Darwin.listen(fd, 128) == 0 else { close(fd); return nil }
        return fd
    }

    private func acceptLoop(_ fd: Int32, _ wakeRead: Int32) {
        defer { close(fd); close(wakeRead) }
        while true {
            var fds = [pollfd(fd: fd, events: Int16(POLLIN), revents: 0),
                       pollfd(fd: wakeRead, events: Int16(POLLIN), revents: 0)]
            let r = poll(&fds, 2, -1)
            if r < 0 { if errno == EINTR { continue } else { return } }
            if fds[1].revents != 0 { return }
            guard fds[0].revents & Int16(POLLIN) != 0 else { if fds[0].revents != 0 { return }; continue }
            var peer = sockaddr_in()
            var peerLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let conn = withUnsafeMutablePointer(to: &peer) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { accept(fd, $0, &peerLen) }
            }
            if conn < 0 { if errno == EINTR || errno == ECONNABORTED || errno == EAGAIN { continue } else { return } }
            let ip = Self.text(of: peer)
            Self.configure(conn, seconds: limits.idleSeconds)
            let admitted: Bool = lock.withLock {
                guard listenFd == fd, connections < limits.maxConnections, perIpCount[ip, default: 0] < limits.perIp else { return false }
                connections += 1
                perIpCount[ip, default: 0] += 1
                open.insert(conn)
                return true
            }
            guard admitted else { reject(conn); continue }
            let t = Thread { [self] in
                serve(conn)
                lock.withLock {
                    open.remove(conn)
                    connections -= 1
                    if let n = perIpCount[ip] { if n <= 1 { perIpCount[ip] = nil } else { perIpCount[ip] = n - 1 } }
                }
                close(conn)
            }
            t.name = "airweb-conn"
            t.start()
        }
    }

    private static func text(of addr: sockaddr_in) -> String {
        var a = addr.sin_addr
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        return inet_ntop(AF_INET, &a, &buf, socklen_t(buf.count)).map { String(cString: $0) } ?? "?"
    }

    /// Too busy: answer 503 (so the browser retries) without tying up a connection slot. A few at a time; past that
    /// the connection is just closed.
    private func reject(_ conn: Int32) {
        let go: Bool = lock.withLock { if rejecting >= 16 { return false }; rejecting += 1; return true }
        guard go else { close(conn); return }
        let t = Thread { [self] in
            let body = Array("503 Service Unavailable\n".utf8)
            try? Self.sendAll(conn, Array(AirWebHTTP.head(503, "Service Unavailable", "text/plain; charset=utf-8", Int64(body.count),
                                                          extra: "Retry-After: 3\r\n").utf8) + body)
            shutdown(conn, SHUT_WR)
            // Read what it sent, so closing doesn't reset the connection before it has read the answer.
            var tv = timeval(tv_sec: 0, tv_usec: 500_000)
            setsockopt(conn, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
            var buf = [UInt8](repeating: 0, count: 2048)
            while recv(conn, &buf, buf.count, 0) > 0 {}
            close(conn)
            lock.withLock { rejecting -= 1 }
        }
        t.name = "airweb-reject"
        t.start()
    }

    private static func configure(_ fd: Int32, seconds: Int) {
        var yes: Int32 = 1
        let len = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, len)
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, len)
        _ = fcntl(fd, F_SETFD, FD_CLOEXEC)
        setTimeouts(fd, receive: seconds, send: seconds)
    }

    private static func setTimeouts(_ fd: Int32, receive: Int, send: Int) {
        var rv = timeval(tv_sec: receive, tv_usec: 0)
        var sv = timeval(tv_sec: send, tv_usec: 0)
        let l = socklen_t(MemoryLayout<timeval>.size)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rv, l)
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &sv, l)
    }

    /// Every couple of seconds: browsers that went quiet leave.
    private func sweep() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let gone: Bool = lock.withLock {
            let before = peerMap.count
            peerMap = peerMap.filter { now - $0.value.lastSeen <= limits.peerTtlMs }
            return peerMap.count != before
        }
        if gone { notifyPeers() }
    }

    // MARK: Who is connected

    @discardableResult
    private func touch(_ id: String?, _ rawLabel: String?) -> AirWebPeer? {
        guard let id else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let label = AirWebHTTP.cleanLabel(rawLabel)
        let changed: Bool = lock.withLock {
            if var p = peerMap[id] {
                p.lastSeen = now
                let c = p.label != label
                p.label = label
                peerMap[id] = p
                return c
            }
            if peerMap.count >= limits.maxPeers, let oldest = peerMap.values.min(by: { $0.lastSeen < $1.lastSeen }) {
                peerMap[oldest.id] = nil
            }
            peerMap[id] = PeerRec(id: id, label: label, joined: now, lastSeen: now)
            return true
        }
        if changed { notifyPeers() }
        return peers().first { $0.id == id }
    }

    private func snapshotLocked() -> [AirWebPeer] {
        let list = peerMap.values.sorted { $0.joined != $1.joined ? $0.joined < $1.joined : $0.id < $1.id }
        var total: [String: Int] = [:]
        for p in list { total[p.label, default: 0] += 1 }
        var seen: [String: Int] = [:]
        return list.map { p in
            seen[p.label, default: 0] += 1
            let label = (total[p.label] ?? 1) > 1 ? "\(p.label) \(seen[p.label]!)" : p.label
            return AirWebPeer(id: p.id, label: label, joined: p.joined, lastSeen: p.lastSeen)
        }
    }

    private func notifyPeers() {
        let snap: [AirWebPeer]? = lock.withLock {
            let s = snapshotLocked()
            let key = s.map { "\($0.id)=\($0.label)" }.joined(separator: "|")
            if key == lastPeerKey { return nil }
            lastPeerKey = key
            return s
        }
        if let snap { host?.peersChanged(snap) }
    }

    // MARK: Transfer slots (the waiting room)

    /// A transfer slot, waiting for one if need be; false when the room is full, time ran out or the server stopped.
    private func acquireSlot() -> Bool {
        if slots.wait(timeout: .now()) == .success { return true }
        let ok: Bool = lock.withLock {
            if waiting >= limits.maxWaiting { return false }
            waiting += 1
            return true
        }
        guard ok else { return false }
        defer { lock.withLock { waiting -= 1 } }
        let end = DispatchTime.now() + .milliseconds(limits.transferWaitMs)
        while !isStopped && DispatchTime.now() < end {
            if slots.wait(timeout: .now() + .milliseconds(500)) == .success { return true }
        }
        return false
    }

    private func releaseSlot() { slots.signal() }

    /// Space for an upload of `len` bytes on top of the uploads already running, or false when it won't fit.
    private func reserve(_ len: Int64, _ host: AirWebHost) -> Bool {
        lock.withLock {
            let free = host.freeSpace()
            if free >= 0 && reservedBytes + len > free { return false }
            reservedBytes += len
            return true
        }
    }

    private func unreserve(_ len: Int64) { lock.withLock { reservedBytes = max(0, reservedBytes - len) } }

    // MARK: One request per connection

    private func serve(_ fd: Int32) {
        // Any error (client gone, timeout, disk) ends the connection quietly.
        do {
            guard let host else { return }
            let input = SocketReader(fd: fd, size: Self.bufSize)
            // The head must arrive in full by the deadline; each read waits a few seconds at most.
            let headMs = limits.headDeadlineMs
            Self.setTimeouts(fd, receive: max(1, min(5, (headMs + 999) / 1000)), send: limits.idleSeconds)
            input.deadline = DispatchTime.now().uptimeNanoseconds + UInt64(headMs) * 1_000_000
            guard let req = AirWebHTTP.readRequest(input) else {
                if input.timedOut || input.received == 0 { return } // too slow or nothing sent: just hang up
                try simple(fd, 400, "Bad Request"); return
            }
            input.deadline = 0
            Self.setTimeouts(fd, receive: limits.idleSeconds, send: limits.idleSeconds)
            guard AirWebHTTP.tokenMatches(req.query["t"], token) else {
                if req.path == "/" && req.method == "GET" { try html(fd, 403, "Forbidden", AirWebHTTP.expiredPage) }
                else { try simple(fd, 403, "Forbidden") }
                return
            }
            switch req.path {
            case "/":
                guard req.method == "GET" || req.method == "HEAD" else { try simple(fd, 405, "Method Not Allowed"); return }
                let page = host.page().replacingOccurrences(of: "{{NAME}}", with: AirWebHTTP.escapeHtml(host.deviceName()))
                try html(fd, 200, "OK", page, headOnly: req.method == "HEAD")
            case "/api/state":
                guard req.method == "GET" else { try simple(fd, 405, "Method Not Allowed"); return }
                try state(fd, req, host)
            case "/qr.svg":
                guard req.method == "GET" else { try simple(fd, 405, "Method Not Allowed"); return }
                try qr(fd, req, host)
            case "/u":
                guard req.method == "POST" else { try simple(fd, 405, "Method Not Allowed"); return }
                try upload(fd, input, req, host)
            case let p where p.hasPrefix("/d/"):
                guard req.method == "GET" || req.method == "HEAD" else { try simple(fd, 405, "Method Not Allowed"); return }
                try download(fd, req, host)
            default:
                try simple(fd, 404, "Not Found")
            }
        } catch {}
    }

    private func simple(_ fd: Int32, _ code: Int, _ reason: String) throws {
        let body = Array("\(code) \(reason)\n".utf8)
        try Self.sendAll(fd, Array(AirWebHTTP.head(code, reason, "text/plain; charset=utf-8", Int64(body.count)).utf8) + body)
    }

    private func busy(_ fd: Int32) throws {
        let body = Array("503 Busy, try again in a moment\n".utf8)
        try Self.sendAll(fd, Array(AirWebHTTP.head(503, "Service Unavailable", "text/plain; charset=utf-8", Int64(body.count),
                                                   extra: "Retry-After: 5\r\n").utf8) + body)
    }

    private func html(_ fd: Int32, _ code: Int, _ reason: String, _ page: String, headOnly: Bool = false) throws {
        let body = Array(page.utf8)
        let head = AirWebHTTP.head(code, reason, "text/html; charset=utf-8", Int64(body.count), extra: AirWebHTTP.pageHeaders)
        try Self.sendAll(fd, Array(head.utf8) + (headOnly ? [] : body))
    }

    private func json(_ fd: Int32, _ code: Int, _ reason: String, _ body: String) throws {
        let b = Array(body.utf8)
        try Self.sendAll(fd, Array(AirWebHTTP.head(code, reason, "application/json; charset=utf-8", Int64(b.count)).utf8) + b)
    }

    private func state(_ fd: Int32, _ req: AirWebHTTP.Request, _ host: AirWebHost) throws {
        let c = AirWebHTTP.cleanClientId(req.query["c"])
        touch(c, req.query["w"])
        let peers = self.peers()
        let pool = host.offered()
        let sig = AirWebHTTP.signature(pool)
        let same = req.query["v"] == sig
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var s = "{\"name\":\(AirWebHTTP.jsonString(host.deviceName())),\"free\":\(host.freeSpace()),\"now\":\(now)"
        // The page polls this often: slower the more browsers there are, so 50 of them stay cheap.
        s += ",\"poll\":\(min(max(2_000 + 100 * peers.count, 2_000), 8_000)),\"v\":\"\(sig)\",\"count\":\(peers.count),\"peers\":["
        // With a big crowd only some names are listed; the asking page is always among them (it marks itself "you").
        var shown = Array(peers.prefix(Self.maxPeersListed))
        if let mine = peers.first(where: { $0.id == c }), !shown.contains(where: { $0.id == c }) {
            shown.removeLast(); shown.append(mine)
        }
        s += shown.map { "{\"id\":\(AirWebHTTP.jsonString($0.id)),\"name\":\(AirWebHTTP.jsonString($0.label))}" }
            .joined(separator: ",")
        s += "]"
        if same {
            s += ",\"same\":true"
        } else {
            s += ",\"files\":["
            s += pool.map { f in
                var o = "{\"id\":\(f.id),\"name\":\(AirWebHTTP.jsonString(f.name)),\"size\":\(f.size),\"added\":\(f.added)"
                if let by = f.by { o += ",\"by\":\(AirWebHTTP.jsonString(by))" }
                if let owner = f.owner, owner == c { o += ",\"own\":true" }
                return o + "}"
            }.joined(separator: ",")
            s += "]"
        }
        s += "}"
        try json(fd, 200, "OK", s)
    }

    private func qr(_ fd: Int32, _ req: AirWebHTTP.Request, _ host: AirWebHost) throws {
        // The link the browser itself used (hotspot and Wi-Fi addresses differ), so whoever scans it can reach us.
        guard let h = req.headers["host"], (3...80).contains(h.count),
              h.allSatisfy({ $0.isLetter || $0.isNumber || ".-:[]".contains($0) }),
              let matrix = host.qr("http://\(h)/?t=\(token)"), !matrix.isEmpty else { try simple(fd, 404, "Not Found"); return }
        let body = Array(AirWebHTTP.qrSvg(matrix).utf8)
        try Self.sendAll(fd, Array(AirWebHTTP.head(200, "OK", "image/svg+xml; charset=utf-8", Int64(body.count)).utf8) + body)
    }

    private func download(_ fd: Int32, _ req: AirWebHTTP.Request, _ host: AirWebHost) throws {
        let idPart = req.path.dropFirst(3).split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
        guard let id = Int(idPart), let f = host.offered().first(where: { $0.id == id }) else {
            try simple(fd, 404, "Not Found"); return
        }
        let type = f.mime.flatMap { $0.contains("/") ? $0 : nil } ?? "application/octet-stream"
        let extra = "Content-Disposition: \(AirWebHTTP.contentDisposition(f.name))\r\n"
        if req.method == "HEAD" {
            try Self.sendAll(fd, Array(AirWebHTTP.head(200, "OK", type, f.size >= 0 ? f.size : nil, extra: extra).utf8))
            return
        }
        guard acquireSlot() else { try busy(fd); return }
        lock.withLock { transfers += 1 }
        defer { lock.withLock { transfers -= 1 }; releaseSlot() }
        let file = Darwin.open(f.url.path, O_RDONLY | O_CLOEXEC)
        if file < 0 { try simple(fd, 410, "Gone"); return }
        defer { close(file) }
        try Self.sendAll(fd, Array(AirWebHTTP.head(200, "OK", type, f.size >= 0 ? f.size : nil, extra: extra).utf8))
        var buf = [UInt8](repeating: 0, count: Self.bufSize)
        var sent: Int64 = 0
        while true {
            let n = buf.withUnsafeMutableBytes { Darwin.read(file, $0.baseAddress, $0.count) }
            if n < 0 { if errno == EINTR { continue } else { throw POSIXError(.EIO) } }
            if n == 0 { break }
            // Never send more than announced (a file that grew would break the response).
            let k = f.size >= 0 ? Int(min(Int64(n), f.size - sent)) : n
            if k <= 0 { break }
            try buf.withUnsafeBytes { try Self.sendAll(fd, $0.baseAddress!, k) }
            sent += Int64(k)
        }
        if f.size < 0 || sent == f.size { host.downloaded(f) }
    }

    private func upload(_ fd: Int32, _ input: SocketReader, _ req: AirWebHTTP.Request, _ host: AirWebHost) throws {
        if req.headers["transfer-encoding"] != nil { try simple(fd, 411, "Length Required"); return }
        guard let len = req.contentLength else { try simple(fd, 411, "Length Required"); return }
        guard len >= 0 else { try simple(fd, 400, "Bad Request"); return }
        var name = host.cleanName(AirWebHTTP.baseName(req.query["n"] ?? ""))
        if name.isEmpty || name == "." || name == ".." || name.contains("/") { name = "file" }
        let who = touch(AirWebHTTP.cleanClientId(req.query["c"]), req.query["w"])
        if host.offered().count >= limits.maxPool {
            try json(fd, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"full\"}"); return
        }
        // Wait for a transfer slot first: the browser keeps sending into the connection's buffers meanwhile.
        guard acquireSlot() else { try busy(fd); return }
        lock.withLock { transfers += 1 }
        defer { lock.withLock { transfers -= 1 }; releaseSlot() }
        guard reserve(len, host) else {
            try json(fd, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"space\"}"); return
        }
        defer { unreserve(len) }
        let target: AirWebUpload
        do { target = try AirWebUpload(dir: host.uploadDirectory(), name: name) } catch {
            try json(fd, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"save\"}"); return
        }
        if req.headers["expect"]?.lowercased() == "100-continue" {
            do { try Self.sendAll(fd, Array("HTTP/1.1 100 Continue\r\n\r\n".utf8)) } catch { target.discard(); throw error }
        }
        let id: Int = lock.withLock { uploadIds += 1; return uploadIds }
        host.uploadStarted(id: id, name: name, size: len, by: who)
        var saved: URL?
        defer { host.uploadFinished(id: id, saved: saved) }
        do {
            var buf = [UInt8](repeating: 0, count: Self.bufSize)
            var got: Int64 = 0
            var lastReport: Int64 = 0
            while got < len {
                let want = Int(min(Int64(buf.count), len - got))
                let n = buf.withUnsafeMutableBytes { input.read(into: $0.baseAddress!, max: want) }
                if n <= 0 { throw POSIXError(.ECONNRESET) } // ended early, timed out or cut by stop()
                try buf.withUnsafeBytes { try target.write($0.baseAddress!, n) }
                got += Int64(n)
                if got - lastReport >= Self.progressEvery { lastReport = got; host.uploadProgress(id: id, done: got) }
            }
            saved = try target.commit()
        } catch {
            target.discard()
            throw error
        }
        try json(fd, 200, "OK", "{\"ok\":true,\"name\":\(AirWebHTTP.jsonString(saved?.lastPathComponent ?? ""))}")
    }

    // MARK: Socket writes

    static func sendAll(_ fd: Int32, _ bytes: [UInt8]) throws {
        try bytes.withUnsafeBytes { try sendAll(fd, $0.baseAddress!, $0.count) }
    }

    static func sendAll(_ fd: Int32, _ p: UnsafeRawPointer, _ count: Int) throws {
        var off = 0
        while off < count {
            let n = send(fd, p + off, count - off, 0)
            if n < 0 { if errno == EINTR { continue } else { throw POSIXError(.EPIPE) } }
            off += n
        }
    }
}

/// Buffered reads from a socket: byte by byte for the request head, in blocks for the body.
nonisolated final class SocketReader {
    private let fd: Int32
    private var buf: [UInt8]
    private var start = 0
    private var end = 0
    /// `DispatchTime` uptime nanoseconds after which reading stops (0 = none): a client dribbling bytes can't hold a
    /// connection open.
    var deadline: UInt64 = 0
    /// A read timed out or the deadline passed.
    private(set) var timedOut = false
    func markTimedOut() { timedOut = true }
    /// Bytes received so far.
    private(set) var received = 0

    init(fd: Int32, size: Int) {
        self.fd = fd
        buf = [UInt8](repeating: 0, count: size)
    }

    private func recvInto(_ p: UnsafeMutableRawPointer, _ max: Int) -> Int {
        while true {
            if deadline != 0 && DispatchTime.now().uptimeNanoseconds > deadline { timedOut = true; return -1 }
            let n = recv(fd, p, max, 0)
            if n < 0 && errno == EINTR { continue }
            if n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK) { timedOut = true }
            if n > 0 { received += n }
            return n
        }
    }

    /// Next byte, or -1 at the end of the stream (or on error / timeout).
    func readByte() -> Int {
        if start == end {
            let n = buf.withUnsafeMutableBytes { recvInto($0.baseAddress!, $0.count) }
            if n <= 0 { return -1 }
            start = 0
            end = n
        }
        let b = buf[start]
        start += 1
        return Int(b)
    }

    /// Up to `max` bytes; 0 at the end of the stream, -1 on error / timeout.
    func read(into p: UnsafeMutableRawPointer, max: Int) -> Int {
        if start < end {
            let k = min(max, end - start)
            buf.withUnsafeBytes { p.copyMemory(from: $0.baseAddress! + start, byteCount: k) }
            start += k
            return k
        }
        return recvInto(p, max)
    }
}

/// One upload: written to a hidden temporary file next to its destination, then renamed to a free
/// name (`name`, `name (2).ext`, …) so nothing is ever overwritten and half-written files never show.
nonisolated final class AirWebUpload {
    let dir: URL
    let name: String
    private let temp: URL
    private var fd: Int32

    init(dir: URL, name: String) throws {
        self.dir = dir
        self.name = name
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        temp = dir.appendingPathComponent(".airweb-\(UUID().uuidString).part", isDirectory: false)
        fd = Darwin.open(temp.path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0o644)
        if fd < 0 { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    }

    func write(_ p: UnsafeRawPointer, _ count: Int) throws {
        var off = 0
        while off < count {
            let n = Darwin.write(fd, p + off, count - off)
            if n < 0 { if errno == EINTR { continue } else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) } }
            off += n
        }
    }

    /// Makes the file visible under a free name and returns where it ended up.
    func commit() throws -> URL {
        guard fd >= 0 else { throw POSIXError(.EBADF) }
        let closed = close(fd)
        fd = -1
        if closed != 0 { throw POSIXError(.EIO) }
        let ext = (name as NSString).pathExtension
        let base = ext.isEmpty ? name : (name as NSString).deletingPathExtension
        for n in 1...9999 {
            let candidate = n == 1 ? name : "\(base) (\(n))" + (ext.isEmpty ? "" : ".\(ext)")
            let dest = dir.appendingPathComponent(candidate, isDirectory: false)
            if renamex_np(temp.path, dest.path, UInt32(RENAME_EXCL)) == 0 { return dest }
            if errno != EEXIST { break }
        }
        throw POSIXError(.EEXIST)
    }

    func discard() {
        if fd >= 0 { close(fd); fd = -1 }
        unlink(temp.path)
    }

    /// Removes temporary files left behind by a crash.
    static func purgeLeftovers(in dir: URL) {
        let items = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for item in items where item.hasPrefix(".airweb-") && item.hasSuffix(".part") {
            unlink(dir.appendingPathComponent(item).path)
        }
    }
}

/// HTTP pieces of `AirWebServer`: request parsing and response headers.
nonisolated enum AirWebHTTP {
    private static let maxHead = 16 * 1024
    private static let maxHeaders = 100

    struct Request {
        let method: String
        /// Decoded path without the query.
        let path: String
        let query: [String: String]
        /// Lower-case names.
        let headers: [String: String]

        /// nil when absent; -1 when malformed.
        var contentLength: Int64? {
            guard let v = headers["content-length"] else { return nil }
            if v.isEmpty || v.count > 18 || !v.allSatisfy({ $0 >= "0" && $0 <= "9" }) { return -1 }
            return Int64(v) ?? -1
        }
    }

    /// Request line and headers, or nil if malformed, too large or not HTTP. Reads no body bytes.
    static func readRequest(_ input: SocketReader) -> Request? {
        var lines: [String] = []
        var line: [UInt8] = []
        var total = 0
        while true {
            let c = input.readByte()
            if c < 0 { return nil }
            total += 1
            if total > maxHead { return nil }
            if input.deadline != 0 && DispatchTime.now().uptimeNanoseconds > input.deadline { input.markTimedOut(); return nil }
            if c == 0x0A {
                if line.last == 0x0D { line.removeLast() }
                // ISO-8859-1: one byte, one character.
                let latin = String(String.UnicodeScalarView(line.map { Unicode.Scalar($0) }))
                line.removeAll(keepingCapacity: true)
                if latin.isEmpty { if lines.isEmpty { continue } else { break } } // tolerate a stray CRLF first
                lines.append(latin)
                if lines.count > maxHeaders + 1 { return nil }
            } else {
                line.append(UInt8(c))
            }
        }
        return parse(lines)
    }

    static func parse(_ lines: [String]) -> Request? {
        guard let first = lines.first else { return nil }
        let parts = first.split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[2].hasPrefix("HTTP/1.") else { return nil }
        let method = String(parts[0])
        guard !method.isEmpty, method.allSatisfy({ $0 >= "A" && $0 <= "Z" }) else { return nil }
        let target = String(parts[1])
        guard target.hasPrefix("/") else { return nil }
        let q = target.firstIndex(of: "?")
        let rawPath = q.map { String(target[..<$0]) } ?? target
        guard let path = decode(rawPath, plusIsSpace: false),
              let query = parseQuery(q.map { String(target[target.index(after: $0)...]) } ?? "") else { return nil }
        var headers: [String: String] = [:]
        for l in lines.dropFirst() {
            guard let i = l.firstIndex(of: ":"), i != l.startIndex else { return nil }
            let name = l[..<i].trimmingCharacters(in: .whitespaces).lowercased()
            let value = l[l.index(after: i)...].trimmingCharacters(in: .whitespaces)
            if name == "content-length", let prev = headers[name], prev != value { return nil } // smuggling guard
            headers[name] = value
        }
        return Request(method: method, path: path, query: query, headers: headers)
    }

    /// `a=1&b=x%20y` → map; nil when an escape is malformed. The first value of a key wins.
    static func parseQuery(_ q: String) -> [String: String]? {
        var m: [String: String] = [:]
        for pair in q.split(separator: "&") where !pair.isEmpty {
            let eq = pair.firstIndex(of: "=")
            let k = eq.map { String(pair[..<$0]) } ?? String(pair)
            let v = eq.map { String(pair[pair.index(after: $0)...]) } ?? ""
            guard let dk = decode(k, plusIsSpace: true), let dv = decode(v, plusIsSpace: true) else { return nil }
            if m[dk] == nil { m[dk] = dv }
        }
        return m
    }

    private static func decode(_ s: String, plusIsSpace: Bool) -> String? {
        (plusIsSpace ? s.replacingOccurrences(of: "+", with: " ") : s).removingPercentEncoding
    }

    /// The page's random client id, or nil when absent or not 8 to 40 of `A-Z a-z 0-9 _ -`.
    static func cleanClientId(_ raw: String?) -> String? {
        guard let raw, (8...40).contains(raw.utf8.count),
              raw.utf8.allSatisfy({ ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x5F || $0 == 0x2D })
        else { return nil }
        return raw
    }

    /// A device label for the page's "who is here": no control characters, collapsed spaces, 40 characters.
    static func cleanLabel(_ raw: String?) -> String {
        let kept = String(String.UnicodeScalarView((raw ?? "").unicodeScalars.filter {
            $0.value >= 0x20 && $0.value != 0x7F && $0.value != 0x2028 && $0.value != 0x2029
        }))
        let collapsed = kept.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        let cut = collapsed.count > 40 ? String(collapsed.prefix(40)).trimmingCharacters(in: .whitespaces) : collapsed
        return cut.isEmpty ? "Browser" : cut
    }

    /// A short fingerprint of the pool (ids, names, sizes, owners): the page sends it back to skip an unchanged list.
    /// (FNV-1a; the same value as the Android app's for the same pool, which doesn't matter but is nice.)
    static func signature(_ pool: [AirWebFile]) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        func mix(_ x: Int64) { h = (h ^ UInt64(bitPattern: x)) &* 0x100000001b3 }
        func mix(_ s: String?) {
            if let s { for u in s.utf16 { mix(Int64(u)) }; mix(0x1f) } else { mix(-1) }
        }
        for f in pool { mix(Int64(f.id)); mix(f.name); mix(f.size); mix(f.added); mix(f.by); mix(f.owner) }
        mix(Int64(pool.count))
        return String(h, radix: 16)
    }

    /// `matrix` (rows of modules, true = dark) as an SVG with the 4-module quiet zone scanners need, black on white.
    static func qrSvg(_ matrix: [[Bool]]) -> String {
        let n = matrix.count, q = 4
        var s = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 \(n + 2 * q) \(n + 2 * q)\" shape-rendering=\"crispEdges\">" +
            "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\""
        for y in 0..<n {
            var x = 0
            while x < n {
                if !matrix[y][x] { x += 1; continue }
                var e = x
                while e < n && matrix[y][e] { e += 1 }
                s += "M\(x + q) \(y + q)h\(e - x)v1h-\(e - x)z"
                x = e
            }
        }
        return s + "\"/></svg>"
    }

    /// Constant-time token check.
    static func tokenMatches(_ given: String?, _ expected: String) -> Bool {
        guard let given, !expected.isEmpty else { return false }
        let a = Array(given.utf8), b = Array(expected.utf8)
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[i] ^ b[i] }
        return diff == 0
    }

    /// The last path segment of an uploaded name (browsers send only the name; old IE sent the full path).
    static func baseName(_ raw: String) -> String {
        let s = raw.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? raw
        let t = s.split(separator: "\\", omittingEmptySubsequences: false).last.map(String.init) ?? s
        return t.trimmingCharacters(in: .whitespaces)
    }

    static func head(_ code: Int, _ reason: String, _ type: String, _ length: Int64?, extra: String = "") -> String {
        var s = "HTTP/1.1 \(code) \(reason)\r\nContent-Type: \(type)\r\n"
        if let length { s += "Content-Length: \(length)\r\n" }
        s += "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
        s += extra
        s += "Connection: close\r\n\r\n"
        return s
    }

    static let pageHeaders =
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
        "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n" +
        "X-Frame-Options: DENY\r\n"

    /// `attachment; filename="ascii"; filename*=UTF-8''percent-encoded` (RFC 6266 / 5987).
    static func contentDisposition(_ name: String) -> String {
        var clean = String(String.UnicodeScalarView(name.unicodeScalars.filter { $0.value >= 0x20 && $0.value != 0x7F }))
        if clean.isEmpty { clean = "file" }
        let ascii = String(String.UnicodeScalarView(clean.unicodeScalars.map {
            ($0.value >= 0x20 && $0.value <= 0x7E && $0 != "\"" && $0 != "\\") ? $0 : "_"
        }))
        return "attachment; filename=\"\(ascii)\"; filename*=UTF-8''\(rfc5987(clean))"
    }

    private static func rfc5987(_ s: String) -> String {
        let keep = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$&+-.^_`|~".utf8)
        let hex = Array("0123456789ABCDEF".utf8)
        var out: [UInt8] = []
        for b in s.utf8 {
            if keep.contains(b) { out.append(b) } else { out += [UInt8(ascii: "%"), hex[Int(b >> 4)], hex[Int(b & 15)]] }
        }
        return String(decoding: out, as: UTF8.self)
    }

    static func escapeHtml(_ s: String) -> String {
        var out = ""
        for c in s {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&#39;"
            default: out.append(c)
            }
        }
        return out
    }

    static func jsonString(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case _ where u.value < 0x20 || u.value == 0x2028 || u.value == 0x2029 || u == "<":
                out += String(format: "\\u%04x", u.value)
            default: out.unicodeScalars.append(u)
            }
        }
        return out + "\""
    }

    static let expiredPage = """
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>AirWeb</title>
<style>body{font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;background:#f4f4f3;color:#1b1b1b;margin:0;padding:24px;line-height:1.5}
@media (prefers-color-scheme:dark){body{background:#161616;color:#ececec}}main{max-width:32rem;margin:10vh auto}</style></head>
<body><main><h1 style="font-size:1.4rem">This link has stopped working</h1>
<p>Scan the code on the Mac again to get a new link.</p></main></body></html>
"""
}
