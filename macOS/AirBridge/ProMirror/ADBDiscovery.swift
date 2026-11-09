import Foundation
import Network
import dnssd

/// A phone's Wireless debugging service seen over mDNS. adbd advertises `_adb-tls-connect._tcp` while
/// Wireless debugging is on and `_adb-tls-pairing._tcp` only while "Pair device with pairing code" is open.
/// Both use the instance name `adb-<serial>-<random>`.
nonisolated struct ADBService: Equatable, Sendable {
    enum Kind: String, Sendable { case pairing = "_adb-tls-pairing._tcp", connect = "_adb-tls-connect._tcp" }
    let kind: Kind
    let name: String
    var host: String?
    var port: Int?

    var serial: String? { Self.serial(fromInstance: name) }
    var resolved: Bool { host != nil && port != nil }

    /// "adb-R5CR10ABCDE-qqLBvB" → "R5CR10ABCDE" (the random tail never contains a dash).
    static func serial(fromInstance name: String) -> String? {
        guard name.hasPrefix("adb-") else { return nil }
        let rest = name.dropFirst(4)
        guard let dash = rest.lastIndex(of: "-") else { return rest.isEmpty ? nil : String(rest) }
        let s = rest[..<dash]
        return s.isEmpty ? nil : String(s)
    }

    /// Which advertised service is the phone we want: the saved serial first, then the phone's IP address as
    /// AirBridge knows it, then the only one around (setup only, `allowSingle`).
    static func match(_ services: [ADBService], serial: String?, ip: String?, allowSingle: Bool) -> ADBService? {
        if let serial, let s = services.first(where: { $0.serial == serial }) { return s }
        if let ip, let s = services.first(where: { $0.host == ip }) { return s }
        if allowSingle, services.count == 1 { return services[0] }
        return nil
    }
}

/// Browses both adb service types (Network.framework) and resolves them to IPv4 + port (dns_sd).
/// `onChange` runs on the main queue. Thread-safe.
nonisolated final class ADBDiscovery: @unchecked Sendable {
    static let shared = ADBDiscovery()

    private let queue = DispatchQueue(label: "com.airbridge.promirror.mdns")
    private var browsers: [NWBrowser] = []
    private var found: [String: ADBService] = [:]          // key: kind|name
    private var resolving: Set<String> = []
    private var resolvers: [String: Resolver] = [:]
    private var generations: [String: Int] = [:]
    private var generation = 0
    private var listeners: [UUID: @Sendable ([ADBService]) -> Void] = [:]
    private var running = false

    private var startedAt: Date?

    /// Browsing long enough that "not seen" means "not there" (mDNS answers arrive within a second or two).
    var warm: Bool { queue.sync { startedAt.map { Date().timeIntervalSince($0) > 3 } ?? false } }

    func start() {
        queue.async { [self] in
            guard !running else { return }
            running = true
            startedAt = Date()
            for kind in [ADBService.Kind.connect, .pairing] {
                let b = NWBrowser(for: .bonjour(type: kind.rawValue, domain: "local."), using: .tcp)
                b.browseResultsChangedHandler = { [weak self] results, _ in self?.update(kind, results) }
                b.stateUpdateHandler = { state in
                    if case .failed(let e) = state { NSLog("[AirBridge] pro mirror: mDNS browse failed: %@", "\(e)") }
                }
                b.start(queue: queue)
                browsers.append(b)
            }
        }
    }

    /// Snapshot of what is around right now.
    func services() -> [ADBService] { queue.sync { Array(found.values) } }

    @discardableResult
    func observe(_ f: @escaping @Sendable ([ADBService]) -> Void) -> UUID {
        let id = UUID()
        queue.async { [self] in
            listeners[id] = f
            let snap = Array(found.values)
            DispatchQueue.main.async { f(snap) }
        }
        return id
    }

    func stopObserving(_ id: UUID) { queue.async { self.listeners[id] = nil } }

    /// Waits up to `timeout` for a resolved service matching `pick`.
    func wait(timeout: TimeInterval, _ pick: @escaping @Sendable ([ADBService]) -> ADBService?) async -> ADBService? {
        start()
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let s = pick(services().filter(\.resolved)) { return s }
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        return pick(services().filter(\.resolved))
    }

    /// Resolve again (the port changes every time Wireless debugging is switched on).
    func refresh(_ s: ADBService) {
        queue.async { [self] in
            let key = "\(s.kind.rawValue)|\(s.name)"
            resolving.remove(key)
            resolvers[key]?.cancel(); resolvers[key] = nil
            resolve(key, s)
        }
    }

    // MARK: queue

    private func update(_ kind: ADBService.Kind, _ results: Set<NWBrowser.Result>) {
        var names: Set<String> = []
        for r in results {
            guard case let .service(name, _, _, _) = r.endpoint else { continue }
            names.insert(name)
            let key = "\(kind.rawValue)|\(name)"
            if found[key] == nil {
                let s = ADBService(kind: kind, name: name)
                found[key] = s
                resolve(key, s)
            }
        }
        for (key, s) in found where s.kind == kind && !names.contains(s.name) {
            found[key] = nil
            resolvers[key]?.cancel(); resolvers[key] = nil
            resolving.remove(key)
        }
        notify()
    }

    private func resolve(_ key: String, _ s: ADBService) {
        guard !resolving.contains(key) else { return }
        resolving.insert(key)
        generation += 1
        let gen = generation
        let r = Resolver(name: s.name, type: s.kind.rawValue, queue: queue) { [weak self] host, port in
            guard let self, self.generations[key] == gen else { return }   // a newer resolve replaced this one
            self.resolving.remove(key)
            self.resolvers[key] = nil
            guard var cur = self.found[key] else { return }
            cur.host = host; cur.port = port
            self.found[key] = cur
            self.notify()
        }
        resolvers[key] = r
        generations[key] = gen
        r.start()
    }

    private func notify() {
        let snap = Array(found.values)
        let ls = Array(listeners.values)
        DispatchQueue.main.async { ls.forEach { $0(snap) } }
    }
}

/// One-shot dns_sd resolve: service instance → host target + port → first IPv4 address.
nonisolated private final class Resolver: @unchecked Sendable {
    private let name: String, type: String
    private let queue: DispatchQueue
    private let done: (String?, Int?) -> Void
    private var ref: DNSServiceRef?
    private var addrRef: DNSServiceRef?
    private var port: Int?
    private var finished = false

    init(name: String, type: String, queue: DispatchQueue, done: @escaping (String?, Int?) -> Void) {
        self.name = name; self.type = type; self.queue = queue; self.done = done
    }

    func start() {
        let ctx = Unmanaged.passRetained(self).toOpaque()
        var r: DNSServiceRef?
        let err = DNSServiceResolve(&r, 0, 0, name, type, "local.", { _, _, _, code, _, host, port, _, _, ctx in
            guard let ctx else { return }
            let me = Unmanaged<Resolver>.fromOpaque(ctx).takeUnretainedValue()
            guard code == kDNSServiceErr_NoError, let host else { me.finish(nil, nil); return }
            me.port = Int(UInt16(bigEndian: port))
            me.lookup(String(cString: host))
        }, ctx)
        guard err == kDNSServiceErr_NoError, let r else {
            Unmanaged<Resolver>.fromOpaque(ctx).release()
            done(nil, nil)
            return
        }
        ref = r
        DNSServiceSetDispatchQueue(r, queue)
        queue.asyncAfter(deadline: .now() + 5) { [weak self] in self?.finish(nil, nil) }
    }

    private func lookup(_ host: String) {
        let ctx = Unmanaged.passUnretained(self).toOpaque()
        var r: DNSServiceRef?
        let err = DNSServiceGetAddrInfo(&r, 0, 0, DNSServiceProtocol(kDNSServiceProtocol_IPv4), host, { _, _, _, code, _, addr, _, ctx in
            guard let ctx else { return }
            let me = Unmanaged<Resolver>.fromOpaque(ctx).takeUnretainedValue()
            guard code == kDNSServiceErr_NoError, let addr, addr.pointee.sa_family == sa_family_t(AF_INET) else { return }
            var sin = sockaddr_in()
            memcpy(&sin, addr, MemoryLayout<sockaddr_in>.size)
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &sin.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN))
            me.finish(String(cString: buf), me.port)
        }, ctx)
        guard err == kDNSServiceErr_NoError, let r else { finish(nil, nil); return }
        addrRef = r
        DNSServiceSetDispatchQueue(r, queue)
    }

    private func finish(_ host: String?, _ port: Int?) {
        guard !finished else { return }
        finished = true
        if let r = addrRef { DNSServiceRefDeallocate(r); addrRef = nil }
        if let r = ref { DNSServiceRefDeallocate(r); ref = nil }
        done(host, port)
        Unmanaged.passUnretained(self).release()   // balances passRetained in start()
    }

    func cancel() { queue.async { self.finish(nil, nil) } }
}
