import Foundation
import Network
import Darwin
import dnssd

/// TCP listener on 47632 (falling back through 47641), advertising `_airbridge._tcp` (§2.2, §2.3).
/// Plain sockets, not NWListener: see [ByteTransport]. The WebSocket upgrade and `/v2` path check happen in `WebSocket`. Runs on `queue`.
nonisolated final class Server: @unchecked Sendable {
    private let queue: DispatchQueue
    private var listenFd: Int32 = -1
    private var acceptSource: DispatchSourceRead?
    private var service: DNSServiceRef?
    private var restartWork: DispatchWorkItem?
    private(set) var port: UInt16?

    /// A new connection, as a plain socket (see [ByteTransport] for why not Network.framework).
    var onConnection: ((ByteTransport) -> Void)?
    var onPortChange: ((UInt16?) -> Void)?

    init(queue: DispatchQueue) { self.queue = queue }

    func start() { bind(index: 0) }

    func stop() {
        restartWork?.cancel()
        closeListener()
        setPort(nil)
    }

    /// Re-applies the Bonjour service (after a rename).
    func refreshService() {
        guard let p = port else { return }
        advertise(port: p)
    }

    /// Restarts if the listener is gone.
    func ensureRunning() {
        if listenFd < 0, restartWork == nil { bind(index: 0) }
    }

    /// Listens on [Proto.ports][index] (IPv4 and IPv6 on one socket), moving on to the next port if it is taken.
    private func bind(index: Int) {
        restartWork = nil
        guard index < Proto.ports.count else {
            NSLog("AirBridge: no free port in \(Proto.ports.first!)–\(Proto.ports.last!); retrying")
            scheduleRestart(after: 10)
            return
        }
        let candidate = Proto.ports[index]
        let fd = socket(AF_INET6, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { scheduleRestart(after: 2); return }
        var off: Int32 = 0, on: Int32 = 1
        let size = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &off, size)
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, size)
        var addr = sockaddr_in6()
        addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        addr.sin6_family = sa_family_t(AF_INET6)
        addr.sin6_port = candidate.bigEndian
        addr.sin6_addr = in6addr_any
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) }
        } == 0
        guard bound, listen(fd, 16) == 0 else {
            close(fd)
            bind(index: index + 1)
            return
        }
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
        let src = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        src.setEventHandler { [weak self] in self?.acceptAll(fd) }
        src.setCancelHandler { close(fd) }
        listenFd = fd
        acceptSource = src
        src.resume()
        advertise(port: candidate)
        setPort(candidate)
    }

    private func acceptAll(_ fd: Int32) {
        while true {
            let c = accept(fd, nil, nil)
            if c < 0 {
                if errno == EINTR { continue }
                if errno != EAGAIN && errno != ECONNABORTED {
                    NSLog("AirBridge: accept failed (errno \(errno)); restarting the listener")
                    closeListener()
                    setPort(nil)
                    scheduleRestart(after: 2)
                }
                return
            }
            onConnection?(SocketTransport(accepted: c))
        }
    }

    /// `_airbridge._tcp` with the TXT record of §2.3. mDNSResponder renames on a clash, like the old listener did.
    private func advertise(port: UInt16) {
        if let old = service { DNSServiceRefDeallocate(old); service = nil }
        let txt = Discovery.advertisedTXT()
        var ref: DNSServiceRef?
        let err = txt.withUnsafeBytes { raw in
            DNSServiceRegister(&ref, 0, 0, Identity.name, Proto.serviceType, Proto.serviceDomain, nil, port.bigEndian,
                               UInt16(raw.count), raw.baseAddress, nil, nil)
        }
        guard err == kDNSServiceErr_NoError, let ref else {
            NSLog("AirBridge: Bonjour registration failed (\(err))")
            return
        }
        service = ref
        DNSServiceSetDispatchQueue(ref, queue)
    }

    private func closeListener() {
        if let s = service { DNSServiceRefDeallocate(s); service = nil }
        acceptSource?.cancel()
        acceptSource = nil
        listenFd = -1
    }

    private func scheduleRestart(after seconds: TimeInterval) {
        let work = DispatchWorkItem { [weak self] in self?.bind(index: 0) }
        restartWork = work
        queue.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func setPort(_ p: UInt16?) {
        guard port != p else { return }
        port = p
        onPortChange?(p)
    }
}
