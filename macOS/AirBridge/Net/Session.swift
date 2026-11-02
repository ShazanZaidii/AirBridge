import Foundation
import Network

/// Result of an outbound (client-role) handshake, reported to the connector/UI.
nonisolated enum ClientOutcome: Equatable {
    case ready
    case needsPairing          // host does not know us / our key was rejected
    case pairFailed(String)    // pair_fail reason
    case badHost(String)       // wrong id, bad MAC/proof, version mismatch
    case busy
    case network               // closed before the handshake finished
}

/// One WebSocket connection: handshake (§3), liveness (§4), and routing of ready-state messages.
/// All methods run on `PeerManager.queue`.
nonisolated final class Session: @unchecked Sendable {
    enum Role { case host, client }
    enum State { case connecting, awaitingHello, awaitingAuth, awaitingPairResult, awaitingAuthResult, ready, closed }

    let ws: WebSocket
    let role: Role
    private let queue: DispatchQueue
    private weak var manager: PeerManager?
    private(set) var state: State = .connecting

    private let localNonce = Proto.randomBytes(16)
    private var remoteNonce = Data()
    private(set) var peerId = ""
    private(set) var peerName = "Unknown"
    private(set) var peerPlat = ""
    private(set) var peerCaps: Set<String> = []
    private(set) var screenW = 0
    private(set) var screenH = 0

    // Client role.
    let expectedPeerId: String?
    private let pairSecret: Data?
    private var sessionKey: Data?
    private(set) var clientOutcome: ClientOutcome?

    /// `bye` reason received from the peer, or the reason we closed with.
    private(set) var closeReason: String?
    private let startedAt = ProcessInfo.processInfo.systemUptime
    private var lastSent = ProcessInfo.processInfo.systemUptime
    private var lastReceived = ProcessInfo.processInfo.systemUptime
    private var timer: DispatchSourceTimer?
    private(set) var transfers: TransferService?

    init(ws: WebSocket, role: Role, queue: DispatchQueue, manager: PeerManager,
         expectedPeerId: String? = nil, pairSecret: Data? = nil) {
        self.ws = ws
        self.role = role
        self.queue = queue
        self.manager = manager
        self.expectedPeerId = expectedPeerId
        self.pairSecret = pairSecret
    }

    var isReady: Bool { state == .ready }

    var peerInfo: PeerInfo {
        PeerInfo(id: peerId, name: peerName, platform: peerPlat, caps: peerCaps, screenW: screenW, screenH: screenH)
    }

    var remoteAddress: (host: String, port: UInt16)? {
        guard let h = ws.remoteHost, let p = ws.remotePort else { return nil }
        return (h, p)
    }

    func start() {
        ws.onOpen = { [weak self] in self?.didOpen() }
        ws.onText = { [weak self] d in self?.didReceiveText(d) }
        ws.onBinary = { [weak self] d in self?.didReceiveBinary(d) }
        ws.onActivity = { [weak self] in self?.lastReceived = ProcessInfo.processInfo.systemUptime }
        ws.onClose = { [weak self] _ in self?.didClose() }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
        ws.start()
    }

    // MARK: Sending

    func send(_ msg: [String: Any]) {
        guard let data = Proto.encode(msg) else { return }
        sendText(data)
    }

    func sendText(_ data: Data) {
        guard state != .closed else { return }
        lastSent = ProcessInfo.processInfo.systemUptime
        ws.sendText(data)
    }

    func sendBinary(_ data: Data, completion: @escaping (Bool) -> Void) {
        guard state == .ready else { completion(false); return }
        lastSent = ProcessInfo.processInfo.systemUptime
        ws.sendBinary(data, completion: completion)
    }

    /// Best-effort `bye`, then close.
    func close(bye reason: String?) {
        guard state != .closed else { return }
        if closeReason == nil { closeReason = reason }
        if let reason, state == .ready { send(["t": "bye", "reason": reason]) }
        ws.close()
    }

    private func fail(_ type: String, _ reason: String) {
        send(["t": type, "reason": reason])
        closeReason = closeReason ?? reason
        ws.close()
    }

    private func finishClient(_ outcome: ClientOutcome) {
        if clientOutcome == nil { clientOutcome = outcome }
        if outcome != .ready { ws.close() }
    }

    // MARK: Lifecycle

    private func didOpen() {
        state = .awaitingHello
        lastReceived = ProcessInfo.processInfo.systemUptime
        if role == .client { send(Identity.hello(nonce: localNonce, paired: nil)) }
    }

    private func tick() {
        let now = ProcessInfo.processInfo.systemUptime
        switch state {
        case .closed:
            return
        case .ready:
            if now - lastReceived > Proto.deadAfterSilence {
                closeReason = closeReason ?? "timeout"
                ws.cancel()
            } else if now - lastSent >= Proto.pingAfterIdle {
                send(["t": "ping", "ts": Proto.nowMs()])
            }
        default:
            if now - startedAt > Proto.handshakeTimeout {
                if role == .client { clientOutcome = clientOutcome ?? .network }
                ws.cancel()
            }
        }
    }

    private func didClose() {
        guard state != .closed else { return }
        let wasReady = state == .ready
        state = .closed
        timer?.cancel()
        timer = nil
        if role == .client, clientOutcome == nil { clientOutcome = .network }
        transfers?.teardown()
        transfers = nil
        manager?.sessionClosed(self, wasReady: wasReady)
    }

    private func becomeReady() {
        state = .ready
        transfers = TransferService(session: self, queue: queue)
        if role == .client { clientOutcome = .ready }
        manager?.sessionReady(self)
    }

    // MARK: Receiving

    private func didReceiveText(_ data: Data) {
        guard data.count <= Proto.maxTextFrame, let msg = Proto.decode(data), let t = msg.str("t") else { return }
        if state == .ready { route(t, msg) } else { handshake(t, msg) }
    }

    private func didReceiveBinary(_ data: Data) {
        guard state == .ready, data.first == Proto.chunkKind else { return }
        transfers?.handleChunk(data)
    }

    private func route(_ t: String, _ msg: [String: Any]) {
        switch t {
        case "ping":
            send(["t": "pong", "ts": msg["ts"] ?? Proto.nowMs()])
        case "pong", "error", "hello", "auth", "pair":
            break
        case "bye":
            closeReason = msg.str("reason") ?? "error"
            ws.close()
        case "unpair":
            closeReason = "unpair"
            manager?.peerUnpairedUs(self)
            ws.close()
        case "name":
            if let n = msg.str("name")?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty {
                peerName = String(n.prefix(100))
                manager?.peerRenamed(self)
            }
        case "clip":
            ClipboardSync.receive(msg, from: peerId)
        case _ where t.hasPrefix("xfer_"):
            transfers?.handle(t, msg)
        case _ where PeerBus.controlTypes.contains(t):
            PeerBus.shared.dispatchControl(from: peerId, msg)
        default:
            break
        }
    }

    // MARK: Handshake (§3)

    /// Parses a peer `hello`. Returns false (after replying/closing) if it is unacceptable.
    private func acceptHello(_ msg: [String: Any]) -> Bool {
        guard msg.int("v") == Proto.version else {
            if role == .host { fail("auth_fail", "version") } else { finishClient(.badHost("version")) }
            return false
        }
        guard let id = msg.str("id"), !id.isEmpty, id.count <= 100, id != Identity.id,
              let nonceStr = msg.str("nonce"), let nonce = Data(base64Encoded: nonceStr), nonce.count >= 8 else {
            ws.close()
            if role == .client { clientOutcome = .badHost("Malformed hello") }
            return false
        }
        peerId = id
        remoteNonce = nonce
        peerName = String((msg.str("name") ?? "Unknown").prefix(100))
        peerPlat = String((msg.str("plat") ?? "").prefix(20))
        peerCaps = Set((msg["caps"] as? [Any])?.compactMap { $0 as? String } ?? [])
        if let screen = msg["screen"] as? [String: Any] {
            screenW = screen.int("w") ?? 0
            screenH = screen.int("h") ?? 0
        }
        return true
    }

    private func handshake(_ t: String, _ msg: [String: Any]) {
        switch (role, state, t) {
        case (.host, .awaitingHello, "hello"):
            guard acceptHello(msg) else { return }
            send(Identity.hello(nonce: localNonce, paired: PeerStore.shared.hasKey(peerId)))
            state = .awaitingAuth

        case (.host, .awaitingAuth, "auth"):
            guard let k = PeerStore.shared.key(peerId) else { fail("auth_fail", "unpaired"); return }
            let expected = Proto.authMac(k, cNonce: remoteNonce, hNonce: localNonce, clientId: peerId)
            guard let mac = msg.str("mac").flatMap({ Data(base64Encoded: $0) }),
                  Proto.constantTimeEqual(mac, expected) else { fail("auth_fail", "bad_mac"); return }
            let ack = Proto.authAckMac(k, cNonce: remoteNonce, hNonce: localNonce, hostId: Identity.id)
            send(["t": "auth_ok", "mac": ack.base64EncodedString()])
            PeerStore.shared.rename(id: peerId, name: peerName)
            becomeReady()

        case (.host, .awaitingAuth, "pair"):
            guard let proof = msg.str("proof").flatMap({ Data(base64Encoded: $0) }) else {
                fail("pair_fail", "bad_code"); return
            }
            switch Pairing.shared.verify(clientId: peerId, cNonce: remoteNonce, hNonce: localNonce, proof: proof) {
            case .ok(let k):
                PeerStore.shared.save(id: peerId, key: k, name: peerName, plat: peerPlat)
                let ack = Proto.pairAckProof(k, cNonce: remoteNonce, hNonce: localNonce, hostId: Identity.id)
                send(["t": "pair_ok", "proof": ack.base64EncodedString()])
                becomeReady()
            case .fail(let reason):
                fail("pair_fail", reason)
            }

        case (.client, .awaitingHello, "hello"):
            guard acceptHello(msg) else { return }
            if let expected = expectedPeerId, expected != peerId {
                finishClient(.badHost("A different device answered at that address"))
                return
            }
            let hostKnowsUs = msg.bool("paired") ?? false
            if let secret = pairSecret {
                let k = Proto.deriveKey(secret: secret, clientId: Identity.id, hostId: peerId)
                sessionKey = k
                let proof = Proto.pairProof(k, cNonce: localNonce, hNonce: remoteNonce, clientId: Identity.id)
                send(["t": "pair", "proof": proof.base64EncodedString()])
                state = .awaitingPairResult
            } else if let k = PeerStore.shared.key(peerId), hostKnowsUs {
                sessionKey = k
                let mac = Proto.authMac(k, cNonce: localNonce, hNonce: remoteNonce, clientId: Identity.id)
                send(["t": "auth", "mac": mac.base64EncodedString()])
                state = .awaitingAuthResult
            } else {
                // The host forgot us (or we never paired): our key is useless now.
                PeerStore.shared.delete(peerId)
                finishClient(.needsPairing)
            }

        case (.client, .awaitingAuthResult, "auth_ok"):
            guard let k = sessionKey, let mac = msg.str("mac").flatMap({ Data(base64Encoded: $0) }),
                  Proto.constantTimeEqual(mac, Proto.authAckMac(k, cNonce: localNonce, hNonce: remoteNonce, hostId: peerId)) else {
                finishClient(.badHost("Host failed authentication"))
                return
            }
            PeerStore.shared.rename(id: peerId, name: peerName)
            becomeReady()

        case (.client, .awaitingPairResult, "pair_ok"):
            guard let k = sessionKey, let proof = msg.str("proof").flatMap({ Data(base64Encoded: $0) }),
                  Proto.constantTimeEqual(proof, Proto.pairAckProof(k, cNonce: localNonce, hNonce: remoteNonce, hostId: peerId)) else {
                finishClient(.badHost("Host failed pairing verification"))
                return
            }
            PeerStore.shared.save(id: peerId, key: k, name: peerName, plat: peerPlat)
            becomeReady()

        case (.client, _, "auth_fail"):
            switch msg.str("reason") {
            case "unpaired", "bad_mac":
                PeerStore.shared.delete(peerId)
                finishClient(.needsPairing)
            case "busy":
                finishClient(.busy)
            default:
                finishClient(.badHost(msg.str("reason") ?? "auth_fail"))
            }

        case (.client, _, "pair_fail"):
            finishClient(.pairFailed(msg.str("reason") ?? "bad_code"))

        default:
            break // Rule 5: nothing else before ready.
        }
    }
}
