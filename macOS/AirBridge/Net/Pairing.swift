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

/// Host-side pairing secrets (§2.4, §3.3): rotating QR secret and 6-digit code, with attempt limiting.
nonisolated final class Pairing: @unchecked Sendable {
    static let shared = Pairing()

    struct Snapshot: Equatable, Sendable {
        var code: String
        var secret: Data
        var allowPairing: Bool
        var lockedUntil: Date?
    }

    enum Verdict { case ok(Data), fail(String) }

    private static let codeLifetime: TimeInterval = 5 * 60
    private static let secretLifetime: TimeInterval = 10 * 60
    private static let maxFailures = 5
    private static let lockout: TimeInterval = 60

    private let lock = NSLock()
    private var code = ""
    private var codeCreated = Date.distantPast
    private var secret = Data()
    private var secretCreated = Date.distantPast
    private var failures = 0
    private var lockedUntil: Date?
    private var allow = UserDefaults.standard.object(forKey: "AirBridgeAllowPairing") as? Bool ?? true
    private var timer: DispatchSourceTimer?

    /// Called (on an arbitrary queue) whenever the code, secret or lock state changes.
    var onChange: ((Snapshot) -> Void)?

    func start() {
        lock.lock()
        rotateCode(); rotateSecret()
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
        return Snapshot(code: code, secret: secret, allowPairing: allow, lockedUntil: lockedUntil)
    }

    func setAllowPairing(_ on: Bool) {
        lock.lock()
        allow = on
        UserDefaults.standard.set(on, forKey: "AirBridgeAllowPairing")
        lock.unlock()
        publish()
    }

    /// Verifies a `pair` proof against both current secrets.
    func verify(clientId: String, cNonce: Data, hNonce: Data, proof: Data) -> Verdict {
        lock.lock()
        let verdict: Verdict
        if !allow {
            verdict = .fail("not_pairing")
        } else if let until = lockedUntil, until > Date() {
            verdict = .fail("locked")
        } else {
            lockedUntil = nil
            var match: Data?
            for s in [secret, Data(code.utf8)] {
                let k = Proto.deriveKey(secret: s, clientId: clientId, hostId: Identity.id)
                if Proto.constantTimeEqual(Proto.pairProof(k, cNonce: cNonce, hNonce: hNonce, clientId: clientId), proof) {
                    match = k
                }
            }
            if let k = match {
                failures = 0
                rotateCode(); rotateSecret()
                verdict = .ok(k)
            } else {
                failures += 1
                if failures >= Pairing.maxFailures {
                    failures = 0
                    lockedUntil = Date().addingTimeInterval(Pairing.lockout)
                    rotateCode()
                }
                verdict = .fail("bad_code")
            }
        }
        lock.unlock()
        publish()
        return verdict
    }

    private func tick() {
        lock.lock()
        var changed = false
        let now = Date()
        if now.timeIntervalSince(codeCreated) >= Pairing.codeLifetime { rotateCode(); changed = true }
        if now.timeIntervalSince(secretCreated) >= Pairing.secretLifetime { rotateSecret(); changed = true }
        if let until = lockedUntil, until <= now { lockedUntil = nil; changed = true }
        lock.unlock()
        if changed { publish() }
    }

    private func rotateCode() {
        var n: UInt32 = 0
        repeat {
            withUnsafeMutableBytes(of: &n) { _ = SecRandomCopyBytes(kSecRandomDefault, 4, $0.baseAddress!) }
        } while n >= 4_294_000_000 // rejection sampling: uniform over 0..<1_000_000
        code = String(format: "%06u", n % 1_000_000)
        codeCreated = Date()
    }

    private func rotateSecret() {
        secret = Proto.randomBytes(32)
        secretCreated = Date()
    }

    private func publish() { onChange?(snapshot) }
}
