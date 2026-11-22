import Foundation
import Network

/// Result of an outbound (client-role) handshake, reported to the connector/UI.
nonisolated enum ClientOutcome: Equatable {
    case ready
    case needsPairing          // host does not know us / our key was rejected
    case pairFailed(String)    // pair_fail reason, or "cancelled" when our user cancelled
    case badHost(String)       // wrong id, bad MAC/proof, version mismatch
    case busy
    case network               // closed before the handshake finished
}

/// How an outbound connection pairs (§3.3).
nonisolated enum PairMode: Equatable, Sendable {
    case qr(Data)    // the 32-byte secret `S` from the host's QR `k`
    case confirm     // numeric comparison
}

/// A mode-`confirm` pairing waiting for the local user (§3.3).
nonisolated struct PairPrompt: Identifiable, Equatable, Sendable {
    let id: String
    /// true: a client wants to pair with this Mac (Accept / Reject).
    /// false: this Mac is pairing with a host (Confirm / Cancel).
    let incoming: Bool
    let peerName: String
    let peerPlat: String
    let digits: String
    let expires: Date
    /// Outgoing only: our user confirmed; waiting for the host's user.
    var waiting = false
}

/// One WebSocket connection: handshake (§3), secure channel (§3.6), liveness (§4), and routing of
/// ready-state messages. All methods run on `PeerManager.queue`.
nonisolated final class Session: @unchecked Sendable {
    enum Role { case host, client }
    enum State {
        case connecting, awaitingHello
        case awaitingAuth          // host: hello exchanged, waiting for auth / pair / pair_start
        case confirming            // host: pair_start received, waiting for the user and/or the client's proof
        case awaitingAuthResult    // client
        case awaitingUserConfirm   // client, mode confirm: digits shown, waiting for our user
        case awaitingPairResult    // client
        case ready, closed
    }

    let ws: WebSocket
    let role: Role
    private let queue: DispatchQueue
    private weak var manager: PeerManager?
    private(set) var state: State = .connecting

    private let localNonce = Proto.randomBytes(16)
    private var remoteNonce = Data()
    private var kx: KeyExchange? = KeyExchange()
    private var channel: SecureChannel?
    private var transcript = Data()
    private(set) var peerId = ""
    private(set) var peerName = "Unknown"
    private(set) var peerPlat = ""
    /// The peer's hardware fingerprint (`hello.hw`), if it sent one: the same physical device across reinstalls.
    private(set) var peerHw: String?
    private(set) var peerCaps: Set<String> = []
    private(set) var screenW = 0
    private(set) var screenH = 0

    // Client role.
    let expectedPeerId: String?
    private let pairing: PairMode?
    private var pairKey: Data?
    private(set) var clientOutcome: ClientOutcome?

    // Mode confirm (both roles).
    private var confirmStarted: TimeInterval?
    private(set) var prompt: PairPrompt?
    private var userAccepted = false
    private var verifiedKey: Data?

    /// `bye` reason received from the peer, or the reason we closed with.
    private(set) var closeReason: String?
    private var closing = false
    private let startedAt = ProcessInfo.processInfo.systemUptime
    private var lastSent = ProcessInfo.processInfo.systemUptime
    private var lastReceived = ProcessInfo.processInfo.systemUptime
    private var timer: DispatchSourceTimer?
    private(set) var transfers: TransferService?

    init(ws: WebSocket, role: Role, queue: DispatchQueue, manager: PeerManager,
         expectedPeerId: String? = nil, pairing: PairMode? = nil) {
        self.ws = ws
        self.role = role
        self.queue = queue
        self.manager = manager
        self.expectedPeerId = expectedPeerId
        self.pairing = pairing
    }

    var isReady: Bool { state == .ready }
    var hasIncomingPrompt: Bool { prompt?.incoming == true }

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

    /// Sends a JSON message: in the clear during the handshake, inside the envelope once ready.
    func sendText(_ data: Data) {
        guard state != .closed, !closing else { return }
        lastSent = ProcessInfo.processInfo.systemUptime
        if state == .ready {
            guard let channel, let env = channel.seal(.text, data) else { return }
            ws.sendBinary(env)
        } else {
            ws.sendText(data)
        }
    }

    /// Sends an inner binary frame (starting with its own kind byte) inside the envelope.
    func sendBinary(_ frame: Data, completion: @escaping (Bool) -> Void) {
        guard state == .ready, !closing, let channel, frame.count <= Proto.maxBinaryFrame,
              let env = channel.seal(.binary, frame) else { completion(false); return }
        lastSent = ProcessInfo.processInfo.systemUptime
        ws.sendBinary(env, completion: completion)
    }

    /// Best-effort `bye` (encrypted, ready sessions only), then a WebSocket close.
    func close(bye reason: String?) {
        guard state != .closed, !closing else { return }
        if closeReason == nil { closeReason = reason }
        if let reason, state == .ready { send(["t": "bye", "reason": reason]) }
        closing = true
        ws.close()
    }

    /// Our user accepted/confirmed (`true`) or rejected/cancelled (`false`) a pending confirm-mode pairing.
    func userResponded(accept: Bool) {
        guard prompt != nil else { return }
        switch (role, state) {
        case (.host, .confirming):
            if accept {
                userAccepted = true
                finishConfirmIfPossible()
            } else {
                fail("pair_fail", "rejected")
            }
        case (.client, .awaitingUserConfirm):
            if accept { sendConfirmProof() } else { finishClient(.pairFailed("cancelled")) }
        case (.client, .awaitingPairResult):
            if !accept { finishClient(.pairFailed("cancelled")) }
        default:
            break
        }
    }

    private func fail(_ type: String, _ reason: String) {
        send(["t": type, "reason": reason])
        closeReason = closeReason ?? reason
        closing = true
        ws.close()
    }

    private func finishClient(_ outcome: ClientOutcome) {
        if clientOutcome == nil { clientOutcome = outcome }
        if outcome != .ready { closing = true; ws.close() }
    }

    /// Any plaintext or undecryptable frame after ready (§3.6): encrypted `bye error`, then close.
    private func protocolError(_ why: String) {
        NSLog("AirBridge: protocol error from \(peerName): \(why)")
        closeReason = closeReason ?? "error"
        close(bye: "error")
    }

    // MARK: Lifecycle

    private func didOpen() {
        state = .awaitingHello
        lastReceived = ProcessInfo.processInfo.systemUptime
        if role == .client, let kx { send(Identity.hello(nonce: localNonce, publicKey: kx.publicKey, paired: nil)) }
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
            if let started = confirmStarted {
                // Mode confirm: 60 s from pair_start (the host enforces it; the client allows a little slack).
                let limit = Proto.confirmTimeout + (role == .client ? 5 : 0)
                guard now - started > limit, !closing else { return }
                if role == .host { fail("pair_fail", "timeout") } else { finishClient(.pairFailed("timeout")) }
            } else if now - startedAt > Proto.handshakeTimeout {
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
        kx?.destroy()
        kx = nil
        endPrompt()
        if role == .client, clientOutcome == nil { clientOutcome = .network }
        transfers?.teardown()
        transfers = nil
        manager?.sessionClosed(self, wasReady: wasReady)
    }

    /// Derives the session keys (destroying the ephemeral key) and switches to the encrypted channel.
    /// The caller has already sent `auth_ok`/`pair_ok` in the clear (host) or verified it (client).
    private func becomeReady() {
        guard let ch = kx?.deriveChannel(isClient: role == .client) else {
            closing = true
            ws.close()
            return
        }
        kx = nil
        channel = ch
        endPrompt()
        state = .ready
        // Liveness starts now: during a confirm pairing nothing may arrive while a person reads the code.
        lastReceived = ProcessInfo.processInfo.systemUptime
        transfers = TransferService(session: self, queue: queue)
        if role == .client { clientOutcome = .ready }
        manager?.sessionReady(self)
    }

    // MARK: Receiving

    private func didReceiveText(_ data: Data) {
        guard !closing else { return }
        if state == .ready { protocolError("plaintext text frame"); return }
        guard data.count <= Proto.maxTextFrame, let msg = Proto.decode(data), let t = msg.str("t") else { return }
        handshake(t, msg)
    }

    private func didReceiveBinary(_ data: Data) {
        guard !closing, state == .ready, let channel else { return } // Rule 5: nothing binary before ready.
        guard let kind = data.first else { return }
        switch kind {
        case SecureChannel.envelopeKind:
            break
        case Proto.chunkKind, PeerBus.binaryKindVideo:
            protocolError("unencrypted binary frame of kind \(kind)")
            return
        default:
            return // Rule 2: unknown kind → ignore.
        }
        guard data.count <= Proto.maxEnvelope else { protocolError("oversized envelope"); return }
        let inner: (kind: UInt8, payload: Data)
        do {
            inner = try channel.open(data)
        } catch {
            protocolError((error as? SecureChannel.OpenError) == .sequence ? "bad sequence number" : "envelope failed to decrypt")
            return
        }
        switch SecureChannel.InnerKind(rawValue: inner.kind) {
        case .text:
            guard inner.payload.count <= Proto.maxTextFrame, let msg = Proto.decode(inner.payload),
                  let t = msg.str("t") else { return }
            route(t, msg)
        case .binary:
            switch inner.payload.first {
            case Proto.chunkKind: transfers?.handleChunk(inner.payload)
            case PeerBus.binaryKindVideo: PeerBus.shared.dispatchBinary(from: peerId, inner.payload)
            default: break
            }
        case nil:
            break
        }
    }

    private func route(_ t: String, _ msg: [String: Any]) {
        switch t {
        case "ping":
            send(["t": "pong", "ts": msg["ts"] ?? Proto.nowMs()])
        case "error":
            // Mirror errors (a phone that did not allow its screen to be shown) belong to the mirror window.
            if msg.str("code")?.hasPrefix("mirror_") == true { PeerBus.shared.dispatchControl(from: peerId, msg) }
        case "pong", "hello", "auth", "pair", "pair_start":
            break
        case "bye":
            // Reply with a close frame (WebSocket.close waits for the peer's echo, max 2 s).
            closeReason = msg.str("reason") ?? "error"
            closing = true
            ws.close()
        case "unpair":
            closeReason = "unpair"
            manager?.peerUnpairedUs(self)
            closing = true
            ws.close()
        case "name":
            if let n = msg.str("name")?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty {
                peerName = String(n.prefix(100))
                manager?.peerRenamed(self)
            }
        case "clip":
            ClipboardSync.receive(msg, from: peerId)
        case "screen_time", "screen_time_icon":
            // §15: another device's screen time, shown in the AirBridge widget (ScreenTime/ScreenTimeHub.swift).
            ScreenTimeHub.shared.receive(from: peerInfo, type: t, msg)
        case "dev_battery":
            // The phone's battery level, listed in macOS's own battery list (Widgets/Integration/DevicePowerSources.swift).
            DevicePowerSources.shared.receive(from: peerId, msg)
        case _ where t.hasPrefix("xfer_"):
            transfers?.handle(t, msg)
        case _ where t.hasPrefix("notif_") || t.hasPrefix("call_"):
            // §13 phone notifications and calls: shown by PhoneNotifCenter, never the input half.
            PhoneNotifCenter.receive(from: peerId, msg)
        case _ where PeerBus.controlTypes.contains(t):
            PeerBus.shared.dispatchControl(from: peerId, msg)
        default:
            break
        }
    }

    // MARK: Handshake (§3)

    /// Parses a peer `hello` and completes the key agreement. Returns false (after replying/closing) if it
    /// is unacceptable.
    private func acceptHello(_ msg: [String: Any]) -> Bool {
        guard msg.int("v") == Proto.version else {
            if role == .host { fail("auth_fail", "version") } else { finishClient(.badHost("version")) }
            return false
        }
        guard let id = msg.str("id"), !id.isEmpty, id.count <= 100, !id.contains("|"), id != Identity.id,
              let nonceStr = msg.str("nonce"), let nonce = Data(base64Encoded: nonceStr),
              nonce.count >= 8, nonce.count <= 64,
              let peerKey = KeyExchange.parsePublicKey(msg.str("pk")),
              let kx, kx.complete(peer: peerKey, isClient: role == .client, localNonce: localNonce,
                                  remoteNonce: nonce, localId: Identity.id, remoteId: id) else {
            if role == .client { finishClient(.badHost("Malformed hello")) } else { closing = true; ws.close() }
            return false
        }
        transcript = kx.transcript
        peerId = id
        remoteNonce = nonce
        peerName = String((msg.str("name") ?? "Unknown").prefix(100))
        peerPlat = String((msg.str("plat") ?? "").prefix(20))
        peerHw = msg.str("hw").map { String($0.prefix(64)) }.flatMap { $0.isEmpty ? nil : $0 }
        peerCaps = Set((msg["caps"] as? [Any])?.compactMap { $0 as? String } ?? [])
        if let screen = msg["screen"] as? [String: Any] {
            screenW = screen.int("w") ?? 0
            screenH = screen.int("h") ?? 0
        }
        return true
    }

    private func handshake(_ t: String, _ msg: [String: Any]) {
        switch (role, state, t) {
        // ---- Host ----
        case (.host, .awaitingHello, "hello"):
            guard acceptHello(msg), let kx else { return }
            send(Identity.hello(nonce: localNonce, publicKey: kx.publicKey, paired: PeerStore.shared.hasKey(peerId)))
            state = .awaitingAuth

        case (.host, .awaitingAuth, "auth"):
            guard let k = PeerStore.shared.key(peerId) else { fail("auth_fail", "unpaired"); return }
            guard let mac = msg.str("mac").flatMap({ Data(base64Encoded: $0) }),
                  Proto.constantTimeEqual(mac, Proto.authMac(k, transcript)) else { fail("auth_fail", "bad_mac"); return }
            send(["t": "auth_ok", "mac": Proto.authAckMac(k, transcript).base64EncodedString()])
            PeerStore.shared.rename(id: peerId, name: peerName)
            becomeReady()

        case (.host, .awaitingAuth, "pair_start"):
            guard (msg.str("mode") ?? "confirm") == "confirm" else { return }
            startConfirm()

        case (.host, .awaitingAuth, "pair"), (.host, .confirming, "pair"):
            guard let proof = msg.str("proof").flatMap({ Data(base64Encoded: $0) }) else {
                fail("pair_fail", "bad_proof"); return
            }
            switch msg.str("mode") ?? "qr" {
            case "qr":
                guard state == .awaitingAuth else { return }
                switch Pairing.shared.verifyQR(clientId: peerId, transcript: transcript, proof: proof) {
                case .ok(let k): completePairing(k)
                case .fail(let reason): fail("pair_fail", reason)
                }
            case "confirm":
                if state == .awaitingAuth {
                    // Lenient: `pair` without `pair_start` starts the prompt now.
                    startConfirm()
                    guard state == .confirming else { return }
                }
                guard verifiedKey == nil, let k = kx?.confirmKey else { return }
                switch Pairing.shared.verify(k: k, transcript: transcript, proof: proof) {
                case .ok(let k):
                    verifiedKey = k
                    finishConfirmIfPossible()
                case .fail(let reason):
                    fail("pair_fail", reason)
                }
            default:
                fail("pair_fail", "bad_proof")
            }

        // ---- Client ----
        case (.client, .awaitingHello, "hello"):
            guard acceptHello(msg) else { return }
            if let expected = expectedPeerId, expected != peerId {
                finishClient(.badHost("A different device answered at that address"))
                return
            }
            let hostKnowsUs = msg.bool("paired") ?? false
            switch pairing {
            case .qr(let secret):
                let k = Proto.qrKey(secret: secret, clientId: Identity.id, hostId: peerId)
                pairKey = k
                send(["t": "pair", "mode": "qr", "proof": Proto.pairProof(k, transcript).base64EncodedString()])
                state = .awaitingPairResult
            case .confirm:
                send(["t": "pair_start", "mode": "confirm"])
                state = .awaitingUserConfirm
                confirmStarted = ProcessInfo.processInfo.systemUptime
                if Pairing.debugAutoAccept {
                    sendConfirmProof()
                } else if let digits = kx?.confirmDigits {
                    showPrompt(PairPrompt(id: UUID().uuidString, incoming: false, peerName: peerName,
                                          peerPlat: peerPlat, digits: digits,
                                          expires: Date().addingTimeInterval(Proto.confirmTimeout)))
                }
            case nil:
                if let k = PeerStore.shared.key(peerId), hostKnowsUs {
                    pairKey = k
                    send(["t": "auth", "mac": Proto.authMac(k, transcript).base64EncodedString()])
                    state = .awaitingAuthResult
                } else {
                    // The host forgot us (or we never paired): our key is useless now.
                    PeerStore.shared.delete(peerId)
                    finishClient(.needsPairing)
                }
            }

        case (.client, .awaitingAuthResult, "auth_ok"):
            guard let k = pairKey, let mac = msg.str("mac").flatMap({ Data(base64Encoded: $0) }),
                  Proto.constantTimeEqual(mac, Proto.authAckMac(k, transcript)) else {
                finishClient(.badHost("Host failed authentication"))
                return
            }
            PeerStore.shared.rename(id: peerId, name: peerName)
            becomeReady()

        case (.client, .awaitingPairResult, "pair_ok"):
            guard let k = pairKey, let proof = msg.str("proof").flatMap({ Data(base64Encoded: $0) }),
                  Proto.constantTimeEqual(proof, Proto.pairAckProof(k, transcript)) else {
                finishClient(.badHost("Host failed pairing verification"))
                return
            }
            PeerStore.shared.save(id: peerId, key: k, name: peerName, plat: peerPlat, hw: peerHw)
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
            finishClient(.pairFailed(msg.str("reason") ?? "bad_proof"))

        default:
            break // Rule 5: nothing else before ready.
        }
    }

    // MARK: Mode confirm

    /// Host: `pair_start` — check policy, then show the digits (or auto-accept under the test hook).
    private func startConfirm() {
        if let reason = Pairing.shared.refusal() { fail("pair_fail", reason); return }
        guard let manager, manager.incomingPromptCount < PeerManager.maxIncomingPrompts else {
            fail("pair_fail", "locked"); return
        }
        guard let digits = kx?.confirmDigits else { closing = true; ws.close(); return }
        state = .confirming
        confirmStarted = ProcessInfo.processInfo.systemUptime
        if Pairing.debugAutoAccept {
            NSLog("AirBridge: AIRBRIDGE_DEBUG_AUTO_ACCEPT — accepting \(peerName) (\(digits))")
            userAccepted = true
        } else {
            showPrompt(PairPrompt(id: UUID().uuidString, incoming: true, peerName: peerName, peerPlat: peerPlat,
                                  digits: digits, expires: Date().addingTimeInterval(Proto.confirmTimeout)))
        }
    }

    /// Host: `pair_ok` once both the user accepted and the client's proof verified.
    private func finishConfirmIfPossible() {
        guard state == .confirming, userAccepted, let k = verifiedKey else { return }
        completePairing(k)
    }

    private func completePairing(_ k: Data) {
        PeerStore.shared.save(id: peerId, key: k, name: peerName, plat: peerPlat, hw: peerHw)
        Pairing.shared.succeeded()
        send(["t": "pair_ok", "proof": Proto.pairAckProof(k, transcript).base64EncodedString()])
        becomeReady()
    }

    /// Client: our user confirmed the digits.
    private func sendConfirmProof() {
        guard state == .awaitingUserConfirm, let k = kx?.confirmKey else { return }
        pairKey = k
        send(["t": "pair", "mode": "confirm", "proof": Proto.pairProof(k, transcript).base64EncodedString()])
        state = .awaitingPairResult
        if var p = prompt {
            p.waiting = true
            prompt = p
            manager?.pairPromptChanged(p)
        }
    }

    private func showPrompt(_ p: PairPrompt) {
        prompt = p
        manager?.pairPromptChanged(p)
    }

    private func endPrompt() {
        guard let p = prompt else { return }
        prompt = nil
        manager?.pairPromptEnded(p.id)
    }
}
