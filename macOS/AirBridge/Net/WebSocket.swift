import Foundation
import Network
import CryptoKit

/// Minimal RFC 6455 WebSocket over a raw TCP `NWConnection`, used for both roles.
/// Hand-rolled because Network.framework's server-side WebSocket does not expose the request path,
/// and the spec requires a 404 for anything other than `/v2`. No extensions (permessage-deflate off).
/// All callbacks run on `queue`.
nonisolated final class WebSocket: @unchecked Sendable {
    enum Role { case server, client }

    let connection: NWConnection
    private let role: Role
    private let queue: DispatchQueue
    private var buffer = Data()
    private var open = false
    private var finished = false
    private var closeSent = false
    private var clientKey = ""
    private var fragOpcode: UInt8?
    private var fragData = Data()

    private static let maxMessage = 2 << 20
    private static let maxHeader = 16 * 1024
    private static let guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    var onOpen: (() -> Void)?
    var onText: ((Data) -> Void)?
    var onBinary: ((Data) -> Void)?
    /// Any complete frame (including ping/pong) — used for liveness.
    var onActivity: (() -> Void)?
    var onClose: ((Error?) -> Void)?

    init(connection: NWConnection, role: Role, queue: DispatchQueue) {
        self.connection = connection
        self.role = role
        self.queue = queue
    }

    static func tcpParameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        tcp.connectionTimeout = 8
        return NWParameters(tls: nil, tcp: tcp)
    }

    var remoteHost: String? {
        if case .hostPort(let host, _)? = connection.currentPath?.remoteEndpoint {
            return WebSocket.hostString(host)
        }
        return nil
    }

    var remotePort: UInt16? {
        if case .hostPort(_, let port)? = connection.currentPath?.remoteEndpoint { return port.rawValue }
        return nil
    }

    static func hostString(_ host: NWEndpoint.Host) -> String {
        switch host {
        case .ipv4(let a): return "\(a)".components(separatedBy: "%").first ?? "\(a)"
        case .ipv6(let a): return "\(a)"
        case .name(let n, _): return n
        @unknown default: return "\(host)"
        }
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: self.didConnect()
            case .failed(let e): self.finish(e)
            case .waiting(let e): if self.role == .client { self.finish(e) }
            case .cancelled: self.finish(nil)
            default: break
            }
        }
        connection.start(queue: queue)
    }

    // MARK: Sending

    func sendText(_ data: Data, completion: ((Bool) -> Void)? = nil) {
        sendFrame(opcode: 0x1, payload: data, completion: completion)
    }

    func sendBinary(_ data: Data, completion: ((Bool) -> Void)? = nil) {
        sendFrame(opcode: 0x2, payload: data, completion: completion)
    }

    /// Closing handshake (RFC 6455 §5.5.1): sends a close frame and waits for the peer's close frame (which
    /// cancels the connection in `handleFrame`), or cancels after 2 s if none arrives. No data frames are sent
    /// after the close frame. Calling it again while a close is in progress does nothing.
    func close(code: UInt16 = 1000) {
        guard !finished else { return }
        guard open else { connection.cancel(); return }
        guard !closeSent else { return }
        closeSent = true
        var payload = Data()
        withUnsafeBytes(of: code.bigEndian) { payload.append(contentsOf: $0) }
        sendFrame(opcode: 0x8, payload: payload, completion: nil)
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in self?.connection.cancel() }
    }

    func cancel() { connection.cancel() }

    private func sendFrame(opcode: UInt8, payload: Data, completion: ((Bool) -> Void)?) {
        guard open, !finished, !(closeSent && opcode < 0x8) else { completion?(false); return }
        var header = Data(capacity: 14)
        header.append(0x80 | opcode)
        let maskBit: UInt8 = role == .client ? 0x80 : 0
        let n = payload.count
        if n < 126 {
            header.append(maskBit | UInt8(n))
        } else if n <= 0xFFFF {
            header.append(maskBit | 126)
            withUnsafeBytes(of: UInt16(n).bigEndian) { header.append(contentsOf: $0) }
        } else {
            header.append(maskBit | 127)
            withUnsafeBytes(of: UInt64(n).bigEndian) { header.append(contentsOf: $0) }
        }
        var body = payload
        if role == .client {
            let key = [UInt8](Proto.randomBytes(4))
            header.append(contentsOf: key)
            WebSocket.applyMask(&body, key: key)
        }
        connection.send(content: header, completion: .idempotent)
        connection.send(content: body, completion: .contentProcessed { error in completion?(error == nil) })
    }

    private func sendRaw(_ s: String, then: (() -> Void)? = nil) {
        connection.send(content: Data(s.utf8), completion: .contentProcessed { _ in then?() })
    }

    static func applyMask(_ data: inout Data, key: [UInt8]) {
        data.withUnsafeMutableBytes { raw in
            guard let p = raw.baseAddress else { return }
            let n = raw.count
            var m64: UInt64 = 0
            for i in 0..<8 { m64 |= UInt64(key[i % 4]) << (8 * UInt64(i)) }
            var i = 0
            while i + 8 <= n {
                let v = p.loadUnaligned(fromByteOffset: i, as: UInt64.self)
                p.storeBytes(of: v ^ m64.littleEndian, toByteOffset: i, as: UInt64.self)
                i += 8
            }
            let bytes = p.assumingMemoryBound(to: UInt8.self)
            while i < n { bytes[i] ^= key[i % 4]; i += 1 }
        }
    }

    // MARK: Receiving

    private func didConnect() {
        if role == .client {
            let keyBytes = Proto.randomBytes(16)
            clientKey = keyBytes.base64EncodedString()
            var hostHeader = "airbridge.local"
            if let h = remoteHost, let p = remotePort {
                hostHeader = h.contains(":") ? "[\(h)]:\(p)" : "\(h):\(p)"
            }
            sendRaw("GET \(Proto.path) HTTP/1.1\r\nHost: \(hostHeader)\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: \(clientKey)\r\nSec-WebSocket-Version: 13\r\n\r\n")
        }
        receiveLoop()
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, isComplete, error in
            guard let self, !self.finished else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                self.process()
            }
            if self.finished { return }
            if error != nil || isComplete { self.finish(error); return }
            self.receiveLoop()
        }
    }

    private func process() {
        if !open {
            guard let r = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                if buffer.count > WebSocket.maxHeader { fail() }
                return
            }
            let head = String(decoding: buffer[buffer.startIndex..<r.lowerBound], as: UTF8.self)
            buffer = Data(buffer[r.upperBound...])
            let ok = role == .server ? handleRequest(head) : handleResponse(head)
            guard ok else { return }
            open = true
            onOpen?()
            if finished { return }
        }
        parseFrames()
    }

    private func headers(_ lines: ArraySlice<String>) -> [String: String] {
        var h: [String: String] = [:]
        for line in lines {
            guard let c = line.firstIndex(of: ":") else { continue }
            h[line[..<c].trimmingCharacters(in: .whitespaces).lowercased()] =
                line[line.index(after: c)...].trimmingCharacters(in: .whitespaces)
        }
        return h
    }

    private func acceptValue(_ key: String) -> String {
        Data(Insecure.SHA1.hash(data: Data((key + WebSocket.guid).utf8))).base64EncodedString()
    }

    private func handleRequest(_ head: String) -> Bool {
        let lines = head.components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        guard parts.count >= 3, parts[0] == "GET" else { return reject("400 Bad Request") }
        let path = parts[1].split(separator: "?", maxSplits: 1).first.map(String.init) ?? ""
        guard path == Proto.path else { return reject("404 Not Found") }
        let h = headers(lines.dropFirst())
        guard h["upgrade"]?.lowercased().contains("websocket") == true,
              let key = h["sec-websocket-key"], !key.isEmpty else { return reject("400 Bad Request") }
        guard h["sec-websocket-version"] == "13" else {
            return reject("426 Upgrade Required", extra: "Sec-WebSocket-Version: 13\r\n")
        }
        sendRaw("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: \(acceptValue(key))\r\n\r\n")
        return true
    }

    private func reject(_ status: String, extra: String = "") -> Bool {
        finishedEarly()
        sendRaw("HTTP/1.1 \(status)\r\n\(extra)Content-Length: 0\r\nConnection: close\r\n\r\n") { [weak self] in
            self?.connection.cancel()
        }
        return false
    }

    private func handleResponse(_ head: String) -> Bool {
        let lines = head.components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        let h = headers(lines.dropFirst())
        guard parts.count >= 2, parts[1] == "101", h["sec-websocket-accept"] == acceptValue(clientKey) else {
            fail(); return false
        }
        return true
    }

    private func parseFrames() {
        var offset = 0
        let total = buffer.count
        while !finished {
            let avail = total - offset
            guard avail >= 2 else { break }
            let (b0, b1) = (byte(offset), byte(offset + 1))
            let fin = b0 & 0x80 != 0
            let opcode = b0 & 0x0F
            let masked = b1 & 0x80 != 0
            if b0 & 0x70 != 0 { fail(code: 1002); return }
            var hdr = 2
            var len = UInt64(b1 & 0x7F)
            if len == 126 {
                guard avail >= 4 else { break }
                len = UInt64(byte(offset + 2)) << 8 | UInt64(byte(offset + 3))
                hdr = 4
            } else if len == 127 {
                guard avail >= 10 else { break }
                len = (0..<8).reduce(UInt64(0)) { $0 << 8 | UInt64(byte(offset + 2 + $1)) }
                hdr = 10
            }
            if len > UInt64(WebSocket.maxMessage) { fail(code: 1009); return }
            var key: [UInt8] = []
            if masked {
                guard avail >= hdr + 4 else { break }
                key = (0..<4).map { byte(offset + hdr + $0) }
                hdr += 4
            }
            let frameLen = hdr + Int(len)
            guard avail >= frameLen else { break }
            var payload = buffer.subdata(in: (offset + hdr)..<(offset + frameLen))
            if masked { WebSocket.applyMask(&payload, key: key) }
            offset += frameLen
            handleFrame(fin: fin, opcode: opcode, payload: payload)
        }
        if finished { return }
        if offset > 0 { buffer = offset >= total ? Data() : buffer.subdata(in: offset..<total) }
    }

    private func byte(_ i: Int) -> UInt8 { buffer[buffer.startIndex + i] }

    private func handleFrame(fin: Bool, opcode: UInt8, payload: Data) {
        onActivity?()
        switch opcode {
        case 0x8:
            if !closeSent {
                // Peer-initiated: echo the status code, then close the TCP connection once it is flushed.
                closeSent = true
                let echo = payload.count >= 2 ? payload.prefix(2) : Data()
                sendFrame(opcode: 0x8, payload: Data(echo)) { [weak self] _ in self?.connection.cancel() }
                queue.asyncAfter(deadline: .now() + 2) { [weak self] in self?.connection.cancel() }
            } else {
                // The echo to our own close frame: the handshake is complete.
                connection.cancel()
            }
        case 0x9:
            sendFrame(opcode: 0xA, payload: payload, completion: nil)
        case 0xA:
            break
        case 0x1, 0x2:
            guard !closeSent else { return }
            guard fragOpcode == nil else { fail(code: 1002); return }
            if fin { deliver(opcode, payload) } else { fragOpcode = opcode; fragData = payload }
        case 0x0:
            guard let op = fragOpcode else { fail(code: 1002); return }
            fragData.append(payload)
            if fragData.count > WebSocket.maxMessage { fail(code: 1009); return }
            if fin { fragOpcode = nil; let d = fragData; fragData = Data(); deliver(op, d) }
        default:
            fail(code: 1002)
        }
    }

    private func deliver(_ opcode: UInt8, _ data: Data) {
        if opcode == 0x1 { onText?(data) } else { onBinary?(data) }
    }

    /// Protocol violation: best-effort close frame, then the socket is dead for callers immediately.
    private func fail(code: UInt16 = 1002) {
        guard !finished else { return }
        if open, !closeSent {
            closeSent = true
            var payload = Data()
            withUnsafeBytes(of: code.bigEndian) { payload.append(contentsOf: $0) }
            sendFrame(opcode: 0x8, payload: payload) { [weak self] _ in self?.connection.cancel() }
            queue.asyncAfter(deadline: .now() + 2) { [weak self] in self?.connection.cancel() }
            finishedEarly()
        } else {
            finish(nil)
        }
    }

    /// Marks the socket dead for callers without cancelling yet (lets an HTTP error response flush).
    private func finishedEarly() {
        guard !finished else { return }
        finished = true
        let cb = onClose
        clearCallbacks()
        cb?(nil)
    }

    private func finish(_ error: Error?) {
        guard !finished else { return }
        finished = true
        connection.cancel()
        let cb = onClose
        clearCallbacks()
        cb?(error)
    }

    private func clearCallbacks() {
        onOpen = nil; onText = nil; onBinary = nil; onActivity = nil; onClose = nil
    }
}
