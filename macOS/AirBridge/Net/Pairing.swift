import Foundation
import Security

/// A paired peer as persisted in the Keychain.
nonisolated struct StoredPeer: Equatable, Sendable {
    let id: String
    var name: String
    var plat: String
}

/// Long-term peer keys (§3.3). Keychain generic passwords:
/// service `com.shazan.AirBridge.peers`, account = peer id, data = raw 32-byte K,
/// generic attribute = JSON `{"name":..,"plat":..}`, label = "AirBridge – <name>".
nonisolated final class PeerStore: @unchecked Sendable {
    static let shared = PeerStore()
    private static let service = "com.shazan.AirBridge.peers"

    private let lock = NSLock()
    private var peers: [String: StoredPeer] = [:]
    private var keys: [String: Data] = [:]
    private var loaded = false

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: PeerStore.service,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let items = out as? [[String: Any]] else { return }
        for item in items {
            guard let id = item[kSecAttrAccount as String] as? String else { continue }
            var name = "Unknown", plat = ""
            if let g = item[kSecAttrGeneric as String] as? Data,
               let meta = try? JSONSerialization.jsonObject(with: g) as? [String: String] {
                name = meta["name"] ?? name
                plat = meta["plat"] ?? plat
            }
            peers[id] = StoredPeer(id: id, name: name, plat: plat)
        }
    }

    func all() -> [StoredPeer] {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        return peers.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    func peer(_ id: String) -> StoredPeer? {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        return peers[id]
    }

    func hasKey(_ id: String) -> Bool { peer(id) != nil }

    func key(_ id: String) -> Data? {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        guard peers[id] != nil else { return nil }
        if let k = keys[id] { return k }
        var q = baseQuery(id)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let k = out as? Data else { return nil }
        keys[id] = k
        return k
    }

    func save(id: String, key: Data, name: String, plat: String) {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        SecItemDelete(baseQuery(id) as CFDictionary)
        var add = baseQuery(id)
        add[kSecValueData as String] = key
        add[kSecAttrGeneric as String] = meta(name: name, plat: plat)
        add[kSecAttrLabel as String] = "AirBridge – \(name)"
        let status = SecItemAdd(add as CFDictionary, nil)
        if status != errSecSuccess { NSLog("AirBridge: Keychain save failed (\(status))") }
        peers[id] = StoredPeer(id: id, name: name, plat: plat)
        keys[id] = key
    }

    func rename(id: String, name: String) {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        guard var p = peers[id], p.name != name else { return }
        p.name = name
        peers[id] = p
        let attrs: [String: Any] = [
            kSecAttrGeneric as String: meta(name: name, plat: p.plat),
            kSecAttrLabel as String: "AirBridge – \(name)",
        ]
        SecItemUpdate(baseQuery(id) as CFDictionary, attrs as CFDictionary)
    }

    func delete(_ id: String) {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        SecItemDelete(baseQuery(id) as CFDictionary)
        peers[id] = nil
        keys[id] = nil
    }

    private func baseQuery(_ id: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: PeerStore.service,
         kSecAttrAccount as String: id]
    }

    private func meta(name: String, plat: String) -> Data {
        (try? JSONSerialization.data(withJSONObject: ["name": name, "plat": plat])) ?? Data()
    }
}

/// Host-side pairing policy (§2.4, §3.3): the rotating QR secret, the "allow new pairings" switch, and
/// the failed-proof lockout shared by both pairing modes.
nonisolated final class Pairing: @unchecked Sendable {
    static let shared = Pairing()

    struct Snapshot: Equatable, Sendable {
        var secret: Data
        var allowPairing: Bool
        var lockedUntil: Date?
    }

    enum Verdict { case ok(Data), fail(String) }

    /// Test hook: `AIRBRIDGE_DEBUG_AUTO_ACCEPT=1` auto-accepts mode-`confirm` pairings on both sides
    /// (host Accept and client Confirm) without showing a prompt. Never set in normal use.
    static let debugAutoAccept = ProcessInfo.processInfo.environment["AIRBRIDGE_DEBUG_AUTO_ACCEPT"] == "1"

    private static let secretLifetime: TimeInterval = 10 * 60
    private static let maxFailures = 5
    private static let lockout: TimeInterval = 60

    private let lock = NSLock()
    private var secret = Data()
    private var secretCreated = Date.distantPast
    private var failures = 0
    private var lockedUntil: Date?
    private var allow = UserDefaults.standard.object(forKey: "AirBridgeAllowPairing") as? Bool ?? true
    private var timer: DispatchSourceTimer?

    /// Called (on an arbitrary queue) whenever the secret or lock state changes.
    var onChange: ((Snapshot) -> Void)?

    func start() {
        lock.lock()
        rotateSecret()
        lock.unlock()
        let t = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        t.schedule(deadline: .now() + 5, repeating: 5)
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
        publish()
    }

    var snapshot: Snapshot {
        lock.lock(); defer { lock.unlock() }
        return Snapshot(secret: secret, allowPairing: allow, lockedUntil: lockedUntil)
    }

    func setAllowPairing(_ on: Bool) {
        lock.lock()
        allow = on
        UserDefaults.standard.set(on, forKey: "AirBridgeAllowPairing")
        lock.unlock()
        publish()
    }

    /// `pair_fail` reason if a new pairing attempt must be refused right now (`not_pairing` / `locked`).
    func refusal() -> String? {
        lock.lock(); defer { lock.unlock() }
        if !allow { return "not_pairing" }
        if let until = lockedUntil, until > Date() { return "locked" }
        return nil
    }

    /// Mode `qr`: derives K from the current QR secret and checks `proof = HMAC(K, "pair|" | T)`.
    func verifyQR(clientId: String, transcript: Data, proof: Data) -> Verdict {
        lock.lock()
        let k = Proto.qrKey(secret: secret, clientId: clientId, hostId: Identity.id)
        lock.unlock()
        return verify(k: k, transcript: transcript, proof: proof)
    }

    /// Checks `proof = HMAC(K, "pair|" | T)` for an already derived K (mode `confirm`). Failures count
    /// towards the lockout in both modes.
    func verify(k: Data, transcript: Data, proof: Data) -> Verdict {
        if let reason = refusal() { return .fail(reason) }
        lock.lock()
        let verdict: Verdict
        if Proto.constantTimeEqual(Proto.pairProof(k, transcript), proof) {
            verdict = .ok(k)
        } else {
            failures += 1
            if failures >= Pairing.maxFailures {
                failures = 0
                lockedUntil = Date().addingTimeInterval(Pairing.lockout)
                rotateSecret()
            }
            verdict = .fail("bad_proof")
        }
        lock.unlock()
        publish()
        return verdict
    }

    /// A pairing completed: reset the failure count and rotate the QR secret (§2.4).
    func succeeded() {
        lock.lock()
        failures = 0
        rotateSecret()
        lock.unlock()
        publish()
    }

    private func tick() {
        lock.lock()
        var changed = false
        let now = Date()
        if now.timeIntervalSince(secretCreated) >= Pairing.secretLifetime { rotateSecret(); changed = true }
        if let until = lockedUntil, until <= now { lockedUntil = nil; changed = true }
        lock.unlock()
        if changed { publish() }
    }

    private func rotateSecret() {
        secret = Proto.randomBytes(32)
        secretCreated = Date()
    }

    private func publish() { onChange?(snapshot) }
}
