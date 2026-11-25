import Foundation
import CoreMedia
import CoreVideo

/// What the mirror window shows (main thread).
nonisolated enum PhoneMirrorPhase: Equatable, Sendable {
    case connecting
    case waitingForConsent
    case streaming
    case stopped(String)
}

/// Snapshot published to the window on every change.
nonisolated struct PhoneMirrorState: Equatable, Sendable {
    var phase: PhoneMirrorPhase = .connecting
    /// Stream size in pixels (`mirror_info.w/h`), 0 until known.
    var streamW = 0, streamH = 0
    /// Phone screen in pixels (`mirror_info.display`), and its corner radius in those pixels (0 = unknown).
    var displayW = 0, displayH = 0, corner = 0
    /// `mirror_state`: the phone takes input (Universal Control on), can type, is locked.
    var control = false
    var typing = false
    var locked = false
    var hasPicture = false
}

/// Phone-as-source mirroring (protocol.md §8.7, Mac as `mirror_sink`): asks a phone for its screen, decodes the
/// `0x03` frames in hardware and acks them for flow control. One session per phone; the window owns it.
/// Network callbacks arrive on any thread; all state lives on `queue`; `onState`/`onFrame` are called on `queue`.
nonisolated final class PhoneMirrorSession: @unchecked Sendable {
    /// Ack every frame (tiny message): the phone then keeps only ~100 ms of frames in flight, so a slow link makes it
    /// skip frames instead of building a backlog — low latency (we tell it with `ackEvery` in `mirror_start`).
    static let ackEvery = 1
    static let ackGap = 0.5
    static let keyThrottle = 0.5

    let peer: String
    private let queue: DispatchQueue
    private var decoder: MirrorDecoder?
    private var state = PhoneMirrorState()
    private var active = false
    private var received = 0
    private var lastAck = 0.0
    private var lastKeyReq = 0.0
    private var watchdog: DispatchSourceTimer?
    private var lastFrameAt = 0.0
    private var startedAt = 0.0
    /// No `mirror_wait`/`mirror_info` this long after `mirror_start`: the phone isn't answering.
    static let answerTimeout = 8.0

    /// Observers set by the window; called on the session queue (the window hops to main itself).
    var onState: (@Sendable (PhoneMirrorState) -> Void)?
    var onFrame: (@Sendable (CVPixelBuffer) -> Void)?

    init(peer: String) {
        self.peer = peer
        queue = DispatchQueue(label: "com.airbridge.phonemirror.\(peer.prefix(8))", qos: .userInteractive)
    }

    // MARK: control (any thread)

    /// Ask for the stream. `maxSide` caps both sides (the phone fits its screen inside, never upscales).
    /// 1920 keeps text sharp in the window while encoding/decoding faster than full 2400-px phone panels.
    func start(maxSide: Int = 1920) {
        queue.async { [self] in
            guard PeerBus.shared.peers().contains(where: { $0.id == peer }) else {
                active = false
                update { $0 = PhoneMirrorState(); $0.phase = .stopped("The phone isn't connected") }
                return
            }
            active = true
            decoder?.reset()
            received = 0
            startedAt = CACurrentMediaTimeCompat()
            lastFrameAt = 0
            update { s in
                s = PhoneMirrorState()
                if let p = PeerBus.shared.peers().first(where: { $0.id == peer }) { s.control = p.caps.contains("ctl_target"); s.typing = p.caps.contains("type_target") }
            }
            // H.264 first: every Android encoder does it with low latency; HEVC is only taken when listed first.
            PeerBus.shared.send(to: peer, ["t": "mirror_start", "codecs": ["h264", "hevc"], "maxW": maxSide, "maxH": maxSide,
                                           "fps": 60, "bitrate": 12_000_000, "ackEvery": Self.ackEvery])
            startWatchdog()
        }
    }

    func stop() {
        queue.async { [self] in
            guard active else { return }
            active = false
            PeerBus.shared.send(to: peer, ["t": "mirror_stop"])
            watchdog?.cancel(); watchdog = nil
            decoder?.invalidate()
            decoder = nil
        }
    }

    func requestKeyframe() { queue.async { self.askKey(force: true) } }

    func handle(_ msg: [String: Any]) {
        guard let t = msg["t"] as? String else { return }
        queue.async { [self] in
            switch t {
            case "mirror_wait":
                guard active else { return }
                update { $0.phase = .waitingForConsent }
            case "mirror_info":
                guard active else { return }
                let codec = MirrorCodec(rawValue: msg["codec"] as? String ?? "") ?? .h264
                let d = msg["display"] as? [String: Any]
                let w = Msg.int(msg["w"]) ?? 0, h = Msg.int(msg["h"]) ?? 0
                guard w > 0, h > 0 else { return }
                if decoder?.codec != codec { decoder?.invalidate(); decoder = makeDecoder(codec) }
                update { s in
                    if s.phase != .streaming { s.phase = .streaming }
                    s.streamW = w; s.streamH = h
                    s.displayW = Msg.int(d?["w"]) ?? w
                    s.displayH = Msg.int(d?["h"]) ?? h
                    s.corner = Msg.int(d?["corner"]) ?? 0
                }
                askKey(force: true)
            case "mirror_state":
                guard active else { return }
                update { s in
                    if let c = Msg.bool(msg["control"]) { s.control = c }
                    if let c = Msg.bool(msg["typing"]) { s.typing = c }
                    if let c = Msg.bool(msg["locked"]) { s.locked = c }
                }
            case "mirror_stop":
                guard active else { return }
                active = false
                watchdog?.cancel(); watchdog = nil
                decoder?.invalidate(); decoder = nil
                let reason = msg["reason"] as? String
                update { s in
                    // A more specific `error` may already have explained it.
                    if case .stopped = s.phase { return }
                    s.phase = .stopped(Self.text(forStop: reason))
                }
            case "error":
                guard let code = msg["code"] as? String, code.hasPrefix("mirror_") else { return }
                let reason = msg["reason"] as? String
                let text: String
                switch code {
                case "mirror_permission": text = reason == "timeout" ? "Nobody allowed it on the phone" : "Not allowed on the phone"
                case "mirror_codec": text = "The phone can't encode video for this Mac"
                default: text = "The phone stopped sharing its screen"
                }
                update { $0.phase = .stopped(text) }
            default:
                break
            }
        }
    }

    static func text(forStop reason: String?) -> String {
        switch reason {
        case "user": return "Stopped on the phone"
        case "locked": return "Phone is locked"
        case "replaced": return "Another computer is showing this phone"
        case "no ack": return "The connection was too slow"
        default: return "Stopped"
        }
    }

    /// Inner binary frame `0x03 | u64 pts | flags | Annex-B` (any thread).
    func frame(_ data: Data) {
        guard data.count > 10 else { return }
        queue.async { [self] in
            guard active, let dec = decoder else { return }
            let b = data.startIndex
            var pts: UInt64 = 0
            for i in 1...8 { pts = (pts << 8) | UInt64(data[b + i]) }
            let flags = data[b + 9]
            received += 1
            let now = CACurrentMediaTimeCompat()
            lastFrameAt = now
            // §8.7: ack every `ackEvery` received frames or 500 ms, independent of decoding.
            if received % Self.ackEvery == 0 || now - lastAck >= Self.ackGap {
                lastAck = now
                PeerBus.shared.send(to: peer, ["t": "mirror_ack", "pts": Int64(bitPattern: pts)])
            }
            switch dec.decode(data.subdata(in: (b + 10)..<data.endIndex), ptsUs: pts, keyframe: flags & 1 != 0) {
            case .needKeyframe: askKey(force: false)
            case .decoded, .dropped: break
            }
        }
    }

    // MARK: queue

    private func makeDecoder(_ codec: MirrorCodec) -> MirrorDecoder {
        MirrorDecoder(codec: codec) { [weak self] image, _ in
            guard let self else { return }
            if !self.state.hasPicture { self.update { $0.hasPicture = true } }
            self.onFrame?(image)
        }
    }

    private func askKey(force: Bool) {
        guard active else { return }
        let now = CACurrentMediaTimeCompat()
        if !force && now - lastKeyReq < Self.keyThrottle { return }
        lastKeyReq = now
        PeerBus.shared.send(to: peer, ["t": "mirror_key"])
    }

    private func update(_ f: (inout PhoneMirrorState) -> Void) {
        let before = state
        f(&state)
        if state != before { onState?(state) }
    }

    /// Nothing from the phone for a long time while streaming: say so instead of showing a frozen picture.
    private func startWatchdog() {
        watchdog?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in
            guard let self, self.active else { return }
            let now = CACurrentMediaTimeCompat()
            if self.state.phase == .connecting, now - self.startedAt > Self.answerTimeout {
                self.active = false
                PeerBus.shared.send(to: self.peer, ["t": "mirror_stop"])
                self.watchdog?.cancel(); self.watchdog = nil
                self.update { $0.phase = .stopped("The phone didn't answer") }
                return
            }
            if self.state.phase == .streaming, self.lastFrameAt > 0, now - self.lastFrameAt > 6 {
                self.askKey(force: true)
            }
        }
        t.resume()
        watchdog = t
    }

    /// The phone's session ended.
    func peerGone() {
        queue.async { [self] in
            guard active else { return }
            active = false
            watchdog?.cancel(); watchdog = nil
            decoder?.invalidate(); decoder = nil
            update { $0.phase = .stopped("The phone disconnected") }
        }
    }
}

/// Routes phone-mirror traffic to the open sessions and keeps track of which phones can be mirrored.
nonisolated final class PhoneMirrorHub: @unchecked Sendable {
    static let shared = PhoneMirrorHub()

    private let lock = NSLock()
    private var sessions: [String: PhoneMirrorSession] = [:]

    func register(_ s: PhoneMirrorSession) { lock.lock(); sessions[s.peer] = s; lock.unlock() }
    func unregister(_ s: PhoneMirrorSession) {
        lock.lock(); if sessions[s.peer] === s { sessions[s.peer] = nil }; lock.unlock()
    }
    private func session(_ peer: String) -> PhoneMirrorSession? { lock.lock(); defer { lock.unlock() }; return sessions[peer] }

    /// True when `peer` has an open mirror window (its `mirror_stop`/`error` are for us, not the Mac source).
    func isMirroring(_ peer: String) -> Bool { session(peer) != nil }

    func handle(_ peer: String, _ msg: [String: Any]) { session(peer)?.handle(msg) }
    func frame(_ peer: String, _ data: Data) { session(peer)?.frame(data) }

    func peersChanged(_ ready: Set<String>) {
        lock.lock(); let gone = sessions.filter { !ready.contains($0.key) }.map(\.value); lock.unlock()
        gone.forEach { $0.peerGone() }
    }

    /// Phones that can show their screen here.
    static func canMirror(_ p: PeerInfo) -> Bool { p.platform == "android" && p.caps.contains("mirror_source") }

    /// Another Mac or a PC that shares its screen (#18): shown and controlled in the same window, without the phone's
    /// Back / Home / Recents.
    static func isComputer(_ p: PeerInfo) -> Bool { ["macos", "windows", "linux"].contains(p.platform) }
    static func canShowScreen(_ p: PeerInfo) -> Bool { isComputer(p) && p.caps.contains("mirror_source") }
}
