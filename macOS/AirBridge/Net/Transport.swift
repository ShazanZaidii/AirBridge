import Foundation
import Network
import Darwin
import dnssd

/// The byte stream under a [WebSocket]: a plain BSD socket ([SocketTransport]) or a Network.framework connection
/// ([NWTransport]). Every callback runs on the queue passed to `start`; nothing is called after `onEnd`.
///
/// Why plain sockets: a Network.framework TCP connection keeps its send buffer near the 128 KiB default and never grows it,
/// so on Wi-Fi (a few ms of round trip under load) sending tops out around 10 MiB/s. A kernel socket grows its buffer up to
/// 4 MiB by itself (`net.inet.tcp.autosndbufmax`) and reaches the link's speed: 65-74 MiB/s on the same 5 GHz link.
nonisolated protocol ByteTransport: AnyObject {
    var onReady: (() -> Void)? { get set }
    var onData: ((Data) -> Void)? { get set }
    /// The stream ended: nil = closed by the peer or cancelled.
    var onEnd: ((Error?) -> Void)? { get set }
    var remoteHost: String? { get }
    var remotePort: UInt16? { get }
    func start(queue: DispatchQueue)
    /// [completion] runs once the bytes are with the kernel (false = they never will be).
    func send(_ data: Data, completion: ((Bool) -> Void)?)
    func cancel()
}

nonisolated enum Transports {
    /// The transport for dialing [endpoint]: a plain socket for addresses and Bonjour services.
    static func dial(_ endpoint: NWEndpoint) -> ByteTransport {
        switch endpoint {
        case .hostPort(let host, let port):
            return SocketTransport(host: WebSocket.hostString(host), port: port.rawValue)
        case .service(let name, let type, let domain, _):
            return SocketTransport(service: name, type: type, domain: domain)
        default:
            let params = WebSocket.tcpParameters()
            (params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
            return NWTransport(NWConnection(to: endpoint, using: params))
        }
    }

    static func posixError(_ code: Int32 = errno) -> Error { NSError(domain: NSPOSIXErrorDomain, code: Int(code)) }
}

// MARK: - Plain socket

nonisolated final class SocketTransport: ByteTransport, @unchecked Sendable {
    private enum Target { case accepted(Int32), host(String, UInt16), service(String, String, String) }

    var onReady: (() -> Void)?
    var onData: ((Data) -> Void)?
    var onEnd: ((Error?) -> Void)?
    private(set) var remoteHost: String?
    private(set) var remotePort: UInt16?

    private let target: Target
    private var queue: DispatchQueue!
    private var fd: Int32 = -1
    private var connected = false
    private var ended = false
    private var readSource: DispatchSourceRead?
    private var writeSource: DispatchSourceWrite?
    private var writeArmed = false
    private var pending: [(data: Data, sent: Int, done: ((Bool) -> Void)?)] = []
    private var timeout: DispatchWorkItem?
    private var resolver: DNSServiceRef?
    private var candidates: [Data] = []   // sockaddrs still to try
    private var lastError: Error?
    private var readBuffer = [UInt8](repeating: 0, count: 1 << 20)

    private static let connectTimeout: TimeInterval = 8

    /// A connection [Server] accepted.
    init(accepted fd: Int32) { target = .accepted(fd) }
    init(host: String, port: UInt16) { target = .host(host, port) }
    init(service name: String, type: String, domain: String) { target = .service(name, type, domain) }

    func start(queue: DispatchQueue) {
        self.queue = queue
        switch target {
        case .accepted(let s):
            fd = s
            configure(s)
            (remoteHost, remotePort) = Self.peerName(s)
            queue.async { [self] in didConnect() }
        case .host(let h, let p):
            armTimeout()
            resolve(host: h, port: p)
        case .service(let name, let type, let domain):
            armTimeout()
            resolveService(name: name, type: type, domain: domain)
        }
    }

    func send(_ data: Data, completion: ((Bool) -> Void)?) {
        guard !ended else { completion?(false); return }
        pending.append((data, 0, completion))
        if connected { flush() }
    }

    func cancel() { end(nil) }

    // MARK: Connecting

    private func armTimeout() {
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.connected else { return }
            self.end(self.lastError ?? Transports.posixError(ETIMEDOUT))
        }
        timeout = work
        queue.asyncAfter(deadline: .now() + Self.connectTimeout, execute: work)
    }

    /// Looks [host] up off the queue (a `.local` name goes through mDNS), IPv4 first like the old dialer.
    private func resolve(host: String, port: UInt16) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var found: [Data] = []
            for family in [AF_INET, AF_UNSPEC] where found.isEmpty {
                var hints = addrinfo()
                hints.ai_family = family
                hints.ai_socktype = SOCK_STREAM
                var res: UnsafeMutablePointer<addrinfo>?
                guard getaddrinfo(host, String(port), &hints, &res) == 0 else { continue }
                var p = res
                while let ai = p {
                    if let a = ai.pointee.ai_addr { found.append(Data(bytes: a, count: Int(ai.pointee.ai_addrlen))) }
                    p = ai.pointee.ai_next
                }
                freeaddrinfo(res)
            }
            guard let self else { return }
            self.queue.async {
                guard !self.ended else { return }
                if found.isEmpty { self.end(Transports.posixError(EHOSTUNREACH)); return }
                self.candidates = found
                self.connectNext()
            }
        }
    }

    private func resolveService(name: String, type: String, domain: String) {
        var ref: DNSServiceRef?
        let ctx = Unmanaged.passUnretained(self).toOpaque()
        let err = DNSServiceResolve(&ref, 0, 0, name, type, domain, { _, _, _, error, _, hostTarget, port, _, _, ctx in
            guard let ctx else { return }
            let me = Unmanaged<SocketTransport>.fromOpaque(ctx).takeUnretainedValue()
            me.stopResolver()
            guard error == kDNSServiceErr_NoError, let hostTarget, !me.ended else {
                me.end(Transports.posixError(EHOSTUNREACH))
                return
            }
            me.resolve(host: String(cString: hostTarget), port: UInt16(bigEndian: port))
        }, ctx)
        guard err == kDNSServiceErr_NoError, let ref else { end(Transports.posixError(EHOSTUNREACH)); return }
        resolver = ref
        DNSServiceSetDispatchQueue(ref, queue)
    }

    private func stopResolver() {
        if let r = resolver { DNSServiceRefDeallocate(r); resolver = nil }
    }

    /// Tries the next resolved address; a failed attempt moves on to the one after it.
    private func connectNext() {
        guard !ended else { return }
        guard !candidates.isEmpty else { end(lastError ?? Transports.posixError(ECONNREFUSED)); return }
        let addr = candidates.removeFirst()
        let family = addr.withUnsafeBytes { $0.load(as: sockaddr.self).sa_family }
        let s = socket(Int32(family), SOCK_STREAM, IPPROTO_TCP)
        guard s >= 0 else { lastError = Transports.posixError(); connectNext(); return }
        configure(s)
        let r = addr.withUnsafeBytes { Darwin.connect(s, $0.baseAddress!.assumingMemoryBound(to: sockaddr.self), socklen_t(addr.count)) }
        if r == 0 { fd = s; finishConnect(); return }
        guard errno == EINPROGRESS else { lastError = Transports.posixError(); close(s); connectNext(); return }
        // Wait for the socket to become writable: then the connect has finished, one way or the other.
        let w = DispatchSource.makeWriteSource(fileDescriptor: s, queue: queue)
        w.setEventHandler { [weak self, weak w] in
            w?.cancel()
            guard let self else { close(s); return }
            var err: Int32 = 0
            var len = socklen_t(MemoryLayout<Int32>.size)
            getsockopt(s, SOL_SOCKET, SO_ERROR, &err, &len)
            if self.ended { close(s); return }
            if err == 0 { self.fd = s; self.finishConnect() } else { self.lastError = Transports.posixError(err); close(s); self.connectNext() }
        }
        w.resume()
    }

    private func finishConnect() {
        (remoteHost, remotePort) = Self.peerName(fd)
        didConnect()
    }

    private func didConnect() {
        guard !ended else { return }
        connected = true
        timeout?.cancel()
        timeout = nil
        let r = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        r.setEventHandler { [weak self] in self?.readAvailable() }
        let w = DispatchSource.makeWriteSource(fileDescriptor: fd, queue: queue)
        w.setEventHandler { [weak self] in self?.flush() }
        // Close the descriptor only when both sources are done with it.
        let fdToClose = fd
        let group = DispatchGroup()
        group.enter(); group.enter()
        r.setCancelHandler { group.leave() }
        w.setCancelHandler { group.leave() }
        group.notify(queue: queue) { close(fdToClose) }
        readSource = r
        writeSource = w
        r.resume()
        onReady?()
        if !pending.isEmpty { flush() }
    }

    // MARK: Reading and writing

    private func readAvailable() {
        guard !ended else { return }
        let n = readBuffer.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress, $0.count) }
        if n > 0 {
            onData?(Data(readBuffer[0..<n]))
        } else if n == 0 {
            end(nil)
        } else if errno != EAGAIN && errno != EINTR {
            end(Transports.posixError())
        }
    }

    private func flush() {
        guard connected, !ended else { return }
        while !pending.isEmpty {
            let item = pending[0]
            let left = item.data.count - item.sent
            if left > 0 {
                let n = item.data.withUnsafeBytes { Darwin.write(fd, $0.baseAddress! + item.sent, left) }
                if n < 0 {
                    if errno == EAGAIN || errno == EINTR { setWriteArmed(true); return }
                    end(Transports.posixError())
                    return
                }
                pending[0].sent += n
                if n < left { setWriteArmed(true); return }
            }
            pending.removeFirst()
            item.done?(true)
            if ended { return }
        }
        setWriteArmed(false)
    }

    private func setWriteArmed(_ on: Bool) {
        guard let w = writeSource, on != writeArmed else { return }
        writeArmed = on
        if on { w.resume() } else { w.suspend() }
    }

    // MARK: Ending

    private func end(_ error: Error?) {
        guard !ended else { return }
        ended = true
        timeout?.cancel()
        timeout = nil
        stopResolver()
        if let w = writeSource {
            if !writeArmed { w.resume() }   // a suspended source never runs its cancel handler
            w.cancel()
        }
        readSource?.cancel()
        if readSource == nil, fd >= 0 { close(fd) }   // never got as far as the sources (an accepted socket ended early)
        let waiting = pending
        pending = []
        for p in waiting { p.done?(false) }
        let cb = onEnd
        onReady = nil; onData = nil; onEnd = nil
        cb?(error)
    }

    // MARK: Helpers

    private func configure(_ s: Int32) {
        _ = fcntl(s, F_SETFL, fcntl(s, F_GETFL) | O_NONBLOCK)
        var one: Int32 = 1
        let size = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, size)
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, size)
        setsockopt(s, SOL_SOCKET, SO_KEEPALIVE, &one, size)
        var idle: Int32 = 10, interval: Int32 = 5, count: Int32 = 4
        setsockopt(s, IPPROTO_TCP, TCP_KEEPALIVE, &idle, size)
        setsockopt(s, IPPROTO_TCP, TCP_KEEPINTVL, &interval, size)
        setsockopt(s, IPPROTO_TCP, TCP_KEEPCNT, &count, size)
    }

    /// The peer's address; an IPv4 peer on the dual-stack listener shows as `::ffff:a.b.c.d`, which is reported as `a.b.c.d`.
    static func peerName(_ s: Int32) -> (String?, UInt16?) {
        var ss = sockaddr_storage()
        var len = socklen_t(MemoryLayout<sockaddr_storage>.size)
        let ok = withUnsafeMutablePointer(to: &ss) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getpeername(s, $0, &len) } } == 0
        guard ok else { return (nil, nil) }
        let family = Int32(ss.ss_family)
        return withUnsafePointer(to: &ss) { p -> (String?, UInt16?) in
            switch family {
            case AF_INET:
                return p.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { a in
                    var addr = a.pointee.sin_addr
                    var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                    inet_ntop(AF_INET, &addr, &buf, socklen_t(buf.count))
                    return (String(cString: buf), UInt16(bigEndian: a.pointee.sin_port))
                }
            case AF_INET6:
                return p.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { a in
                    var addr = a.pointee.sin6_addr
                    let port = UInt16(bigEndian: a.pointee.sin6_port)
                    let b = withUnsafeBytes(of: &addr) { Array($0) }
                    if b[0..<10].allSatisfy({ $0 == 0 }) && b[10] == 0xFF && b[11] == 0xFF {
                        return ("\(b[12]).\(b[13]).\(b[14]).\(b[15])", port)
                    }
                    var buf = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
                    inet_ntop(AF_INET6, &addr, &buf, socklen_t(buf.count))
                    return (String(cString: buf), port)
                }
            default:
                return (nil, nil)
            }
        }
    }
}

// MARK: - Network.framework (fallback for endpoint kinds a plain socket cannot dial)

nonisolated final class NWTransport: ByteTransport, @unchecked Sendable {
    var onReady: (() -> Void)?
    var onData: ((Data) -> Void)?
    var onEnd: ((Error?) -> Void)?
    private let connection: NWConnection
    private var ended = false

    init(_ connection: NWConnection) { self.connection = connection }

    var remoteHost: String? {
        if case .hostPort(let host, _)? = connection.currentPath?.remoteEndpoint { return WebSocket.hostString(host) }
        return nil
    }

    var remotePort: UInt16? {
        if case .hostPort(_, let port)? = connection.currentPath?.remoteEndpoint { return port.rawValue }
        return nil
    }

    func start(queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: self.onReady?(); self.receive()
            case .failed(let e), .waiting(let e): self.end(e)
            case .cancelled: self.end(nil)
            default: break
            }
        }
        connection.start(queue: queue)
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, isComplete, error in
            guard let self, !self.ended else { return }
            if let data, !data.isEmpty { self.onData?(data) }
            if self.ended { return }
            if error != nil || isComplete { self.end(error); return }
            self.receive()
        }
    }

    func send(_ data: Data, completion: ((Bool) -> Void)?) {
        connection.send(content: data, completion: .contentProcessed { error in completion?(error == nil) })
    }

    func cancel() { end(nil) }

    private func end(_ error: Error?) {
        guard !ended else { return }
        ended = true
        connection.cancel()
        let cb = onEnd
        onReady = nil; onData = nil; onEnd = nil
        cb?(error)
    }
}
