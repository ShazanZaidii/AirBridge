import AppKit

/// What the phone app said about its debugging settings (`adb_wifi_state`, protocol.md §8.9).
nonisolated struct PhoneDebugStatus: Equatable, Sendable {
    var developerOptions: Bool?
    var wirelessDebugging: Bool?
    var sdk: Int?
    /// After `op:"open"`: "direct" (Settings opened on the phone) or "notification" (tap it on the phone).
    var opened: String?
}

/// Glue for Pro mirroring: which phones are set up and reachable, the phone-app helper messages
/// (`adb_wifi` / `adb_wifi_state`), and stopping our adb server when AirBridge quits.
nonisolated final class ProMirrorHub: @unchecked Sendable {
    static let shared = ProMirrorHub()
    /// The phone app can open Wireless debugging for us (AirBridge on the phone with §8.9).
    static let cap = "adb_wifi"

    private let lock = NSLock()
    private var statusObservers: [String: [UUID: @Sendable (PhoneDebugStatus) -> Void]] = [:]
    private var lastStatus: [String: PhoneDebugStatus] = [:]
    private var started = false

    /// Starts mDNS browsing for set-up phones (cheap; lets "Mirror" pick Pro instantly) and the quit hook.
    func startIfNeeded() {
        lock.lock()
        let first = !started
        started = true
        lock.unlock()
        guard first else { return }
        ADBDiscovery.shared.start()
        DispatchQueue.main.async {
            NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: nil) { _ in
                ADB.shutdown()
            }
        }
    }

    // MARK: Set up / reachable

    static func isSetUp(_ peerId: String) -> Bool { ProMirrorStore.record(peerId) != nil }

    /// Set up, enabled, and its Wireless debugging port is advertised right now.
    static func isReachable(_ peerId: String) -> Bool {
        guard let r = ProMirrorStore.record(peerId), r.enabled else { return false }
        let connect = ADBDiscovery.shared.services().filter { $0.kind == .connect && $0.resolved }
        return ADBService.match(connect, serial: r.serial, ip: phoneIP(peerId), allowSingle: false) != nil
    }

    /// The phone's IP as AirBridge last saw it (PeerManager keeps "host|port" per peer under this key).
    static func phoneIP(_ peerId: String) -> String? {
        let all = UserDefaults.standard.dictionary(forKey: "AirBridgeLastAddresses") as? [String: String] ?? [:]
        guard let host = all[peerId]?.split(separator: "|").first else { return nil }
        return normalizeIP(String(host))
    }

    /// "::ffff:192.168.1.5" → "192.168.1.5"; strips an interface suffix.
    static func normalizeIP(_ s: String) -> String {
        var h = s
        if let pct = h.firstIndex(of: "%") { h = String(h[..<pct]) }
        if h.lowercased().hasPrefix("::ffff:") { h = String(h.dropFirst(7)) }
        return h
    }

    // MARK: Phone app helper (§8.9)

    static func phoneHasHelper(_ peerId: String) -> Bool {
        PeerBus.shared.peers().first(where: { $0.id == peerId })?.caps.contains(cap) == true
    }

    func requestStatus(_ peerId: String) {
        guard Self.phoneHasHelper(peerId) else { return }
        PeerBus.shared.send(to: peerId, ["t": "adb_wifi", "op": "status"])
    }

    /// Asks the phone to open Developer options at Wireless debugging. False if the phone can't.
    @discardableResult
    func openWirelessDebugging(_ peerId: String) -> Bool {
        guard Self.phoneHasHelper(peerId) else { return false }
        return PeerBus.shared.send(to: peerId, ["t": "adb_wifi", "op": "open"])
    }

    func status(_ peerId: String) -> PhoneDebugStatus? { lock.lock(); defer { lock.unlock() }; return lastStatus[peerId] }

    func observeStatus(_ peerId: String, _ f: @escaping @Sendable (PhoneDebugStatus) -> Void) -> UUID {
        let id = UUID()
        lock.lock(); statusObservers[peerId, default: [:]][id] = f; lock.unlock()
        return id
    }

    func stopObserving(_ peerId: String, _ id: UUID) {
        lock.lock(); statusObservers[peerId]?[id] = nil; lock.unlock()
    }

    /// `adb_wifi_state` from a phone (network queue).
    func handle(_ peerId: String, _ msg: [String: Any]) {
        guard msg["t"] as? String == "adb_wifi_state" else { return }
        let s = PhoneDebugStatus(developerOptions: msg["dev"] as? Bool, wirelessDebugging: msg["wifi"] as? Bool,
                                 sdk: Msg.int(msg["sdk"]), opened: msg["opened"] as? String)
        lock.lock()
        lastStatus[peerId] = s
        let obs = Array((statusObservers[peerId] ?? [:]).values)
        lock.unlock()
        DispatchQueue.main.async { obs.forEach { $0(s) } }
    }
}
