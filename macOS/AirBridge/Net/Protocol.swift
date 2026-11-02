import Foundation
import CryptoKit

/// Protocol v2 constants and wire helpers. See docs/protocol.md.
nonisolated enum Proto {
    static let version = 2
    static let appVersion = "2.0.0"
    static let platform = "macos"
    static let ports: [UInt16] = Array(47632...47641)
    static let path = "/v2"
    static let serviceType = "_airbridge._tcp"
    static let serviceDomain = "local."
    static let caps = ["clip", "xfer", "ctl_source", "ctl_target"]

    static let handshakeTimeout: TimeInterval = 10
    static let pingAfterIdle: TimeInterval = 5
    static let deadAfterSilence: TimeInterval = 15

    static let maxTextFrame = 1 << 20
    static let maxBinaryFrame = (1 << 20) + 17
    static let clipMaxBytes = 512 * 1024

    static let chunkSize = 512 * 1024
    static let defaultWindow = 8 * 1024 * 1024
    static let ackEvery = 1 << 20

    static let chunkKind: UInt8 = 0x01
    static let chunkHeaderSize = 17

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: JSON

    static func encode(_ msg: [String: Any]) -> Data? {
        guard JSONSerialization.isValidJSONObject(msg) else { return nil }
        return try? JSONSerialization.data(withJSONObject: msg, options: [.withoutEscapingSlashes])
    }

    static func decode(_ data: Data) -> [String: Any]? {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              obj["t"] is String else { return nil }
        return obj
    }

    // MARK: Binary chunk frame (§6.2)

    static func chunkHeader(transfer: UInt32, file: UInt32, offset: UInt64) -> Data {
        var d = Data(capacity: chunkHeaderSize)
        d.append(chunkKind)
        withUnsafeBytes(of: transfer.bigEndian) { d.append(contentsOf: $0) }
        withUnsafeBytes(of: file.bigEndian) { d.append(contentsOf: $0) }
        withUnsafeBytes(of: offset.bigEndian) { d.append(contentsOf: $0) }
        return d
    }

    struct Chunk {
        let transfer: UInt32
        let file: UInt32
        let offset: UInt64
        let payload: Data
    }

    static func parseChunk(_ data: Data) -> Chunk? {
        guard data.count > chunkHeaderSize else { return nil }
        return data.withUnsafeBytes { raw -> Chunk? in
            guard raw[0] == chunkKind else { return nil }
            func u32(_ at: Int) -> UInt32 { (0..<4).reduce(0) { $0 << 8 | UInt32(raw[at + $1]) } }
            func u64(_ at: Int) -> UInt64 { (0..<8).reduce(0) { $0 << 8 | UInt64(raw[at + $1]) } }
            let payload = Data(raw[chunkHeaderSize...])
            return Chunk(transfer: u32(1), file: u32(5), offset: u64(9), payload: payload)
        }
    }

    // MARK: Crypto (§3)

    static func randomBytes(_ n: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: n)
        _ = SecRandomCopyBytes(kSecRandomDefault, n, &bytes)
        return Data(bytes)
    }

    /// HMAC-SHA256 over the concatenation of `parts` (strings are UTF-8 encoded).
    static func hmac(key: Data, _ parts: Any...) -> Data {
        var h = HMAC<SHA256>(key: SymmetricKey(data: key))
        for p in parts {
            if let s = p as? String { h.update(data: Data(s.utf8)) }
            else if let d = p as? Data { h.update(data: d) }
        }
        return Data(h.finalize())
    }

    static func deriveKey(secret: Data, clientId: String, hostId: String) -> Data {
        hmac(key: secret, "airbridge/v2/pair|", clientId, "|", hostId)
    }

    static func authMac(_ k: Data, cNonce: Data, hNonce: Data, clientId: String) -> Data {
        hmac(key: k, "auth|", cNonce, "|", hNonce, "|", clientId)
    }

    static func authAckMac(_ k: Data, cNonce: Data, hNonce: Data, hostId: String) -> Data {
        hmac(key: k, "auth_ack|", hNonce, "|", cNonce, "|", hostId)
    }

    static func pairProof(_ k: Data, cNonce: Data, hNonce: Data, clientId: String) -> Data {
        hmac(key: k, "pair|", cNonce, "|", hNonce, "|", clientId)
    }

    static func pairAckProof(_ k: Data, cNonce: Data, hNonce: Data, hostId: String) -> Data {
        hmac(key: k, "pair_ack|", hNonce, "|", cNonce, "|", hostId)
    }

    static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[a.startIndex + i] ^ b[b.startIndex + i] }
        return diff == 0
    }

    static func base64url(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func fromBase64url(_ s: String) -> Data? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b)
    }

    // MARK: File names (§6.3)

    private static let reservedNames: Set<String> = {
        var s: Set<String> = ["CON", "PRN", "AUX", "NUL"]
        for i in 1...9 { s.insert("COM\(i)"); s.insert("LPT\(i)") }
        return s
    }()

    static func sanitizeFileName(_ raw: String) -> String {
        var name = raw.replacingOccurrences(of: "\\", with: "/")
        name = name.split(separator: "/", omittingEmptySubsequences: true).last.map(String.init) ?? ""
        name = String(String.UnicodeScalarView(name.unicodeScalars.filter {
            $0.value >= 0x20 && $0.value != 0x7F && !(0x80...0x9F).contains($0.value)
        }))
        while let f = name.first, f == "." || f == " " { name.removeFirst() }
        let stem = name.split(separator: ".", maxSplits: 1).first.map { String($0).uppercased() } ?? ""
        if reservedNames.contains(stem.trimmingCharacters(in: .whitespaces)) { name = "_" + name }
        if name.utf8.count > 200 {
            let ext = (name as NSString).pathExtension
            let keepExt = !ext.isEmpty && ext.utf8.count <= 20
            var base = keepExt ? (name as NSString).deletingPathExtension : name
            let suffix = keepExt ? "." + ext : ""
            while (base + suffix).utf8.count > 200, !base.isEmpty { base.removeLast() }
            name = base + suffix
        }
        return name.isEmpty ? "file" : name
    }
}

nonisolated extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String? { self[k] as? String }
    func int(_ k: String) -> Int? {
        if let n = self[k] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { return n.intValue }
        return nil
    }
    func int64(_ k: String) -> Int64? {
        if let n = self[k] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { return n.int64Value }
        return nil
    }
    func bool(_ k: String) -> Bool? {
        if let n = self[k] as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
        return nil
    }
}
