import Foundation
import CryptoKit

/// Per-connection key exchange (§3.1–§3.3, §3.6): an ephemeral P-256 key, the ECDH secret `Z`,
/// the transcript `T`, and every MAC/derivation that hangs off them.
/// Owned by one `Session` on the manager queue; destroyed once the session keys are derived.
nonisolated final class KeyExchange {
    private var privateKey: P256.KeyAgreement.PrivateKey?
    /// Our X9.63 uncompressed public key (65 bytes), sent in `hello.pk`.
    let publicKey: Data
    private var z: Data?
    private(set) var transcript = Data()
    private var cNonce = Data()
    private var hNonce = Data()
    private var clientId = ""
    private var hostId = ""

    init() {
        let k = P256.KeyAgreement.PrivateKey()
        privateKey = k
        publicKey = k.publicKey.x963Representation
    }

    /// Parses a peer `pk`: base64 of a 65-byte uncompressed point that lies on the curve.
    static func parsePublicKey(_ b64: String?) -> P256.KeyAgreement.PublicKey? {
        guard let b64, let raw = Data(base64Encoded: b64), raw.count == 65, raw.first == 0x04 else { return nil }
        return try? P256.KeyAgreement.PublicKey(x963Representation: raw)
    }

    /// Computes `Z` and `T` once both hellos are known. Returns false if the agreement fails.
    func complete(peer: P256.KeyAgreement.PublicKey, isClient: Bool, localNonce: Data, remoteNonce: Data,
                  localId: String, remoteId: String) -> Bool {
        guard let privateKey, let secret = try? privateKey.sharedSecretFromKeyAgreement(with: peer) else { return false }
        z = secret.withUnsafeBytes { Data($0) }
        let peerPk = peer.x963Representation
        cNonce = isClient ? localNonce : remoteNonce
        hNonce = isClient ? remoteNonce : localNonce
        clientId = isClient ? localId : remoteId
        hostId = isClient ? remoteId : localId
        let cPk = isClient ? publicKey : peerPk
        let hPk = isClient ? peerPk : publicKey
        transcript = Proto.transcript(cNonce: cNonce, hNonce: hNonce, clientId: clientId, hostId: hostId,
                                      cPk: cPk, hPk: hPk)
        return z?.count == 32
    }

    /// The 6 confirmation digits (§3.3, mode `confirm`).
    var confirmDigits: String? {
        guard let z else { return nil }
        let mac = Proto.hmac(key: z, "airbridge/v2/confirm|", transcript)
        let n = mac.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return String(format: "%06u", n % 1_000_000)
    }

    /// Long-term key for mode `confirm`: HKDF(Z, cNonce|hNonce, "airbridge/v2/pairkey|" | cid | "|" | hid).
    var confirmKey: Data? {
        guard let z else { return nil }
        return Proto.hkdf(ikm: z, salt: cNonce + hNonce,
                          info: Data("airbridge/v2/pairkey|\(clientId)|\(hostId)".utf8), length: 32)
    }

    /// Derives the two direction keys, then destroys the ephemeral private key and `Z`.
    func deriveChannel(isClient: Bool) -> SecureChannel? {
        guard let z else { return nil }
        let keys = Proto.hkdf(ikm: z, salt: cNonce + hNonce,
                              info: Data("airbridge/v2/session|".utf8) + transcript, length: 64)
        destroy()
        let c2h = SymmetricKey(data: keys.prefix(32))
        let h2c = SymmetricKey(data: keys.suffix(32))
        return SecureChannel(sendKey: isClient ? c2h : h2c, receiveKey: isClient ? h2c : c2h)
    }

    /// Drops the private key and zeroes `Z`.
    func destroy() {
        privateKey = nil
        if var zz = z { zz.resetBytes(in: 0..<zz.count); z = nil }
    }

    deinit { destroy() }
}

/// The encrypted envelope (§3.6): binary kind 0x02, u64 seq, AES-256-GCM with nonce 0^4‖seq and the
/// 9 header bytes as AAD. One instance per session; used only on the manager queue.
nonisolated final class SecureChannel {
    static let envelopeKind: UInt8 = 0x02
    static let headerSize = 9
    static let tagSize = 16
    /// Envelope bytes added around an inner payload: header + innerKind + tag.
    static let overhead = headerSize + 1 + tagSize

    enum InnerKind: UInt8 { case text = 0, binary = 1 }

    enum OpenError: Error { case malformed, sequence, authentication }

    private let sendKey: SymmetricKey
    private let receiveKey: SymmetricKey
    private(set) var sendSeq: UInt64 = 0
    private(set) var receiveSeq: UInt64 = 0

    init(sendKey: SymmetricKey, receiveKey: SymmetricKey) {
        self.sendKey = sendKey
        self.receiveKey = receiveKey
    }

    private static func header(_ seq: UInt64) -> Data {
        var h = Data(capacity: headerSize)
        h.append(envelopeKind)
        withUnsafeBytes(of: seq.bigEndian) { h.append(contentsOf: $0) }
        return h
    }

    private static func nonce(_ seq: UInt64) -> AES.GCM.Nonce {
        var n = Data(repeating: 0, count: 4)
        withUnsafeBytes(of: seq.bigEndian) { n.append(contentsOf: $0) }
        return try! AES.GCM.Nonce(data: n)
    }

    /// Wraps one message. Consumes a sequence number.
    func seal(_ kind: InnerKind, _ payload: Data) -> Data? {
        let seq = sendSeq
        let h = SecureChannel.header(seq)
        var plain = Data(capacity: payload.count + 1)
        plain.append(kind.rawValue)
        plain.append(payload)
        guard let box = try? AES.GCM.seal(plain, using: sendKey, nonce: SecureChannel.nonce(seq), authenticating: h) else {
            return nil
        }
        sendSeq &+= 1
        var out = h
        out.reserveCapacity(h.count + box.ciphertext.count + SecureChannel.tagSize)
        out.append(box.ciphertext)
        out.append(box.tag)
        return out
    }

    /// Unwraps one envelope; the sequence number must be exactly the next expected one.
    func open(_ frame: Data) throws -> (kind: UInt8, payload: Data) {
        let f = frame.startIndex
        guard frame.count >= SecureChannel.overhead, frame[f] == SecureChannel.envelopeKind else { throw OpenError.malformed }
        let seq = frame[(f + 1)..<(f + 9)].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        guard seq == receiveSeq else { throw OpenError.sequence }
        let h = frame[f..<(f + SecureChannel.headerSize)]
        let ct = frame[(f + SecureChannel.headerSize)..<(frame.endIndex - SecureChannel.tagSize)]
        let tag = frame[(frame.endIndex - SecureChannel.tagSize)...]
        guard let box = try? AES.GCM.SealedBox(nonce: SecureChannel.nonce(seq), ciphertext: ct, tag: tag),
              let plain = try? AES.GCM.open(box, using: receiveKey, authenticating: h),
              let kind = plain.first else { throw OpenError.authentication }
        receiveSeq &+= 1
        return (kind, Data(plain[(plain.startIndex + 1)...]))
    }
}
