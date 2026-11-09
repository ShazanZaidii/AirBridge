import Foundation
import Darwin
import Security

/// One file offered for download. `added` is epoch milliseconds, for "1 min ago" on the page.
nonisolated struct AirWebFile: Sendable, Equatable {
    let id: Int
    let url: URL
    let name: String
    let size: Int64
    let mime: String?
    let added: Int64
}

/// What the server needs from its owner. Called from connection threads.
nonisolated protocol AirWebHost: AnyObject, Sendable {
    func deviceName() -> String
    /// The page, with `{{NAME}}` where the device name goes.
    func page() -> String
    func offered() -> [AirWebFile]
    /// Bytes free for uploads, or -1 when unknown.
    func freeSpace() -> Int64
    /// Folder uploads are saved in. Throws if it can't be created.
    func uploadDirectory() throws -> URL
    /// A safe file name for an upload (no path parts, no control characters).
    func cleanName(_ raw: String) -> String
    func uploadStarted(id: Int, name: String, size: Int64)
    func uploadProgress(id: Int, done: Int64)
    /// `saved` is nil when the upload failed.
    func uploadFinished(id: Int, saved: URL?)
    func downloaded(_ file: AirWebFile)
}

/// AirWeb: a small HTTP/1.1 server so a phone or computer without AirBridge can swap files with this
/// Mac from its browser. Same contract as the Android app's AirWebServer, so the same page works:
///
///  - `GET /` the page
///  - `GET /api/state` JSON: device name, free space, server clock, offered files
///  - `GET|HEAD /d/<id>[/<name>]` download one offered file
///  - `POST /u?n=<file name>` upload one file, raw body, Content-Length required
///
/// Every request needs the session token (`?t=…`, 128 random bits) or the answer is 403. One thread per
/// connection, `Connection: close` on every response, files streamed both ways (never held in memory).
nonisolated final class AirWebServer: @unchecked Sendable {
    static let ports: ClosedRange<UInt16> = 47650...47659
    static let maxConnections = 48
    private static let ioTimeout: Int = 30
    private static let bufSize = 64 * 1024
    private static let progressEvery: Int64 = 256 * 1024

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

    let token: String
    private weak var host: AirWebHost?

    private let lock = NSLock()
    private var listenFd: Int32 = -1
    private var wakeFd: Int32 = -1
    private var open = Set<Int32>()
    private var connections = 0
    private var transfers = 0
    private var uploadIds = 0
    private var boundPort: UInt16 = 0

    init(token: String, host: AirWebHost) {
        self.token = token
        self.host = host
    }

    var port: UInt16 { lock.withLock { boundPort } }
    var isRunning: Bool { lock.withLock { listenFd >= 0 } }
    /// Downloads and uploads in progress right now.
    var activeTransfers: Int { lock.withLock { transfers } }

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
            let wakeRead = pipeFds[0]
            let t = Thread { [self] in acceptLoop(fd, wakeRead) }
            t.name = "airweb-accept"
            t.start()
            return p
        }
        throw POSIXError(.EADDRINUSE)
    }

    /// Stops listening and cuts every open connection (unfinished uploads are discarded).
    func stop() {
        lock.lock()
        defer { lock.unlock() }
        guard listenFd >= 0 else { return }
        var one: UInt8 = 1
        _ = write(wakeFd, &one, 1)
        close(wakeFd)
        wakeFd = -1
        listenFd = -1 // closed by the accept thread once it wakes
        boundPort = 0
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
        guard ok, Darwin.listen(fd, 64) == 0 else { close(fd); return nil }
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
            let conn = accept(fd, nil, nil)
            if conn < 0 { if errno == EINTR || errno == ECONNABORTED || errno == EAGAIN { continue } else { return } }
            Self.configure(conn)
            let admitted: Bool = lock.withLock {
                guard listenFd == fd, connections < Self.maxConnections else { return false }
                connections += 1
                open.insert(conn)
                return true
            }
            guard admitted else {
                try? Self.sendAll(conn, Array(AirWebHTTP.head(503, "Service Unavailable", "text/plain", 0).utf8))
                close(conn)
                continue
            }
            let t = Thread { [self] in
                serve(conn)
                lock.withLock {
                    open.remove(conn)
                    connections -= 1
                }
                close(conn)
            }
            t.name = "airweb-conn"
            t.start()
        }
    }

    private static func configure(_ fd: Int32) {
        var yes: Int32 = 1
        let len = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, len)
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, len)
        _ = fcntl(fd, F_SETFD, FD_CLOEXEC)
        var tv = timeval(tv_sec: ioTimeout, tv_usec: 0)
        let tvLen = socklen_t(MemoryLayout<timeval>.size)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, tvLen)
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, tvLen)
    }

    // MARK: One request per connection

    private func serve(_ fd: Int32) {
        // Any error (client gone, timeout, disk) ends the connection quietly.
        do {
            guard let host else { return }
            let input = SocketReader(fd: fd, size: Self.bufSize)
            guard let req = AirWebHTTP.readRequest(input) else { try simple(fd, 400, "Bad Request"); return }
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
                try state(fd, host)
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

    private func html(_ fd: Int32, _ code: Int, _ reason: String, _ page: String, headOnly: Bool = false) throws {
        let body = Array(page.utf8)
        let head = AirWebHTTP.head(code, reason, "text/html; charset=utf-8", Int64(body.count), extra: AirWebHTTP.pageHeaders)
        try Self.sendAll(fd, Array(head.utf8) + (headOnly ? [] : body))
    }

    private func json(_ fd: Int32, _ code: Int, _ reason: String, _ body: String) throws {
        let b = Array(body.utf8)
        try Self.sendAll(fd, Array(AirWebHTTP.head(code, reason, "application/json; charset=utf-8", Int64(b.count)).utf8) + b)
    }

    private func state(_ fd: Int32, _ host: AirWebHost) throws {
        let files = host.offered().map {
            "{\"id\":\($0.id),\"name\":\(AirWebHTTP.jsonString($0.name)),\"size\":\($0.size),\"added\":\($0.added)}"
        }.joined(separator: ",")
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // "now" lets the page turn "added" into "1 min ago" even when the two clocks disagree.
        try json(fd, 200, "OK", "{\"name\":\(AirWebHTTP.jsonString(host.deviceName())),\"free\":\(host.freeSpace())," +
                 "\"now\":\(now),\"files\":[\(files)]}")
    }

    private func download(_ fd: Int32, _ req: AirWebHTTP.Request, _ host: AirWebHost) throws {
        let idPart = req.path.dropFirst(3).split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
        guard let id = Int(idPart), let f = host.offered().first(where: { $0.id == id }) else {
            try simple(fd, 404, "Not Found"); return
        }
        let headOnly = req.method == "HEAD"
        var file: Int32 = -1
        if !headOnly {
            file = Darwin.open(f.url.path, O_RDONLY | O_CLOEXEC)
            if file < 0 { try simple(fd, 410, "Gone"); return }
        }
        defer { if file >= 0 { close(file) } }
        lock.withLock { transfers += 1 }
        defer { lock.withLock { transfers -= 1 } }
        let type = f.mime.flatMap { $0.contains("/") ? $0 : nil } ?? "application/octet-stream"
        let extra = "Content-Disposition: \(AirWebHTTP.contentDisposition(f.name))\r\n"
        try Self.sendAll(fd, Array(AirWebHTTP.head(200, "OK", type, f.size >= 0 ? f.size : nil, extra: extra).utf8))
        if headOnly { return }
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
        let free = host.freeSpace()
        if free >= 0 && len > free { try json(fd, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"space\"}"); return }
        let target: AirWebUpload
        do { target = try AirWebUpload(dir: host.uploadDirectory(), name: name) } catch {
            try json(fd, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"save\"}"); return
        }
        if req.headers["expect"]?.lowercased() == "100-continue" {
            do { try Self.sendAll(fd, Array("HTTP/1.1 100 Continue\r\n\r\n".utf8)) } catch { target.discard(); throw error }
        }
        let id: Int = lock.withLock { uploadIds += 1; transfers += 1; return uploadIds }
        host.uploadStarted(id: id, name: name, size: len)
        var saved: URL?
        defer {
            lock.withLock { transfers -= 1 }
            host.uploadFinished(id: id, saved: saved)
        }
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

    init(fd: Int32, size: Int) {
        self.fd = fd
        buf = [UInt8](repeating: 0, count: size)
    }

    private func recvInto(_ p: UnsafeMutableRawPointer, _ max: Int) -> Int {
        while true {
            let n = recv(fd, p, max, 0)
            if n < 0 && errno == EINTR { continue }
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
