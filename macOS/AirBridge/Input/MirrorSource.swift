import Foundation
import CoreGraphics
import CoreMedia
import CoreVideo
@preconcurrency import ScreenCaptureKit

/// Screen-mirror source (protocol.md §8.7): streams the display under the cursor to one `mirror_sink`
/// peer as low-latency H.264/HEVC. Input coming back from the sink (`ptr_abs` & co.) goes through the
/// Injector like any other input and obeys the §8.6 lease.
///
/// Threading: all state lives on `queue`; ScreenCaptureKit delivers samples on the same queue.
nonisolated final class MirrorSource: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    static let shared = MirrorSource()

    private let queue = DispatchQueue(label: "com.airbridge.mirror", qos: .userInteractive)

    private struct Active {
        let sink: String
        let generation: Int
        var stream: SCStream?
        var encoder: MirrorEncoder?
        let fps: Int
        let maxBitrate: Int
        var bitrate: Int
        let displayBounds: CGRect           // global CG points, for ptr_abs
        var sent: [(pts: UInt64, at: Double)] = []   // unacknowledged frames, oldest first
        var lastAck: Double                 // host seconds
        var lastAckPts: UInt64 = 0
        var lastFrameAt: Double = 0         // last frame submitted to the encoder
        var lastPts: UInt64 = 0
        var lastBuffer: CVPixelBuffer?      // re-encoded while the screen is idle so acks keep flowing
        var lastRaise: Double
        var lagMs: Double = 0
        // Stats (logged every 5 s).
        var statFrames = 0, statDropped = 0, statBytes = 0
        var statEncMs = 0.0, statEncMax = 0.0, statPipeMs = 0.0
        var statSince: Double
    }
    private var active: Active?
    private var generation = 0
    private var timer: DispatchSourceTimer?

    /// Frames the sink may leave unacknowledged: one ack window (10 frames) + 2 (§8.7).
    private static let ackWindow = 10
    private static let maxUnacked = ackWindow + 2
    private static let minBitrate = 600_000

    private let boundsLock = NSLock()
    private var _mirrorPeer: String?
    private var _bounds: CGRect?

    /// Bounds of the mirrored display (global CG points) if `peer` is the current sink.
    func mirroredBounds(for peer: String) -> CGRect? {
        boundsLock.lock(); defer { boundsLock.unlock() }
        return _mirrorPeer == peer ? _bounds : nil
    }

    var currentSink: String? {
        boundsLock.lock(); defer { boundsLock.unlock() }
        return _mirrorPeer
    }

    // MARK: entry points (any thread)

    func handle(_ peer: String, _ msg: [String: Any]) {
        guard let t = msg["t"] as? String else { return }
        queue.async {
            switch t {
            case "mirror_start": self.start(peer, msg)
            case "mirror_stop": if self.active?.sink == peer { self.stop(notify: false, reason: "sink stopped") }
            case "mirror_key": if self.active?.sink == peer { self.active?.encoder?.requestKeyframe() }
            case "mirror_ack": if self.active?.sink == peer, let pts = Msg.num(msg["pts"]) { self.ack(UInt64(max(0, pts))) }
            default: break // mirror_info is sink-bound; the Mac is never a sink
            }
        }
    }

    func peersChanged(_ ready: Set<String>) {
        queue.async {
            if let a = self.active, !ready.contains(a.sink) { self.stop(notify: false, reason: "sink disconnected") }
        }
    }

    // MARK: start / stop (queue)

    private func start(_ peer: String, _ m: [String: Any]) {
        let info = PeerBus.shared.peers().first { $0.id == peer }
        guard let info, info.caps.contains("mirror_sink") else {
            NSLog("[AirBridge] mirror: ignoring mirror_start from non-sink %@", peer)
            return
        }
        guard CGPreflightScreenCaptureAccess() else {
            PeerBus.shared.send(to: peer, ["t": "mirror_stop"])
            PeerBus.shared.send(to: peer, ["t": "error", "code": "mirror_permission",
                                           "msg": "Screen Recording permission is off for AirBridge on this Mac."])
            DispatchQueue.main.async { _ = CGRequestScreenCaptureAccess() } // shows the system prompt once
            return
        }
        if let a = active {
            // One sink at a time: a new request replaces the old stream.
            if a.sink != peer { PeerBus.shared.send(to: a.sink, ["t": "mirror_stop"]) }
            stop(notify: false, reason: "replaced")
        }

        let codecs = (m["codecs"] as? [String] ?? ["h264"]).compactMap(MirrorCodec.init(rawValue:))
        let fps = max(10, min(Msg.int(m["fps"]) ?? 60, 120))
        let maxW = max(160, Msg.int(m["maxW"]) ?? 1920)
        let maxH = max(160, Msg.int(m["maxH"]) ?? 1080)
        let bitrate = max(Self.minBitrate, min(Msg.int(m["bitrate"]) ?? 8_000_000, 80_000_000))

        // Display under the cursor, else the main display.
        let cursor = CGEvent(source: nil)?.location ?? .zero
        var displayID = CGMainDisplayID()
        var n: UInt32 = 0
        var ids = [CGDirectDisplayID](repeating: 0, count: 4)
        if CGGetDisplaysWithPoint(cursor, 4, &ids, &n) == .success, n > 0 { displayID = ids[0] }
        let bounds = CGDisplayBounds(displayID)
        let pixW = CGDisplayPixelsWide(displayID), pixH = CGDisplayPixelsHigh(displayID)
        let mode = CGDisplayCopyDisplayMode(displayID)
        let nativeW = max(pixW, mode?.pixelWidth ?? 0), nativeH = max(pixH, mode?.pixelHeight ?? 0)

        // Fit maxW×maxH, keep aspect, never upscale, even dimensions.
        let scale = min(1, min(Double(maxW) / Double(nativeW), Double(maxH) / Double(nativeH)))
        let w = max(2, Int(Double(nativeW) * scale) & ~1)
        let h = max(2, Int(Double(nativeH) * scale) & ~1)

        generation += 1
        let gen = generation
        let now = CACurrentMediaTimeCompat()
        let order = codecs.isEmpty ? [MirrorCodec.h264] : codecs
        guard let encoder = MirrorEncoder.make(preferring: order, width: w, height: h, fps: fps, bitrate: bitrate,
                                               onOutput: { [weak self] out in self?.queue.async { self?.emit(out, gen) } }) else {
            PeerBus.shared.send(to: peer, ["t": "mirror_stop"])
            PeerBus.shared.send(to: peer, ["t": "error", "code": "mirror_codec", "msg": "No hardware encoder for the requested codecs."])
            return
        }
        active = Active(sink: peer, generation: gen, stream: nil, encoder: encoder, fps: fps, maxBitrate: bitrate,
                        bitrate: bitrate, displayBounds: bounds, lastAck: now, lastRaise: now, statSince: now)
        setBounds(peer, bounds)
        ControlStatus.shared.setMirroring(info.name)
        NSLog("[AirBridge] mirror: start → %@ %@ %dx%d@%d %d bps (display %dx%d)",
              info.name, encoder.codec.rawValue, w, h, fps, bitrate, nativeW, nativeH)

        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { [weak self] content, error in
            guard let self else { return }
            self.queue.async {
                guard self.active?.generation == gen else { return }
                guard let content, let display = content.displays.first(where: { $0.displayID == displayID }) ?? content.displays.first else {
                    NSLog("[AirBridge] mirror: no shareable display (%@)", error?.localizedDescription ?? "nil")
                    self.stop(notify: true, reason: "no display")
                    return
                }
                self.startStream(display, gen: gen, w: w, h: h, fps: fps, codec: encoder.codec, nativeW: nativeW, nativeH: nativeH)
            }
        }
    }

    private func startStream(_ display: SCDisplay, gen: Int, w: Int, h: Int, fps: Int, codec: MirrorCodec, nativeW: Int, nativeH: Int) {
        let cfg = SCStreamConfiguration()
        cfg.width = w
        cfg.height = h
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        cfg.queueDepth = 3
        cfg.showsCursor = true
        cfg.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        cfg.colorMatrix = CGDisplayStream.yCbCrMatrix_ITU_R_709_2
        cfg.colorSpaceName = CGColorSpace.sRGB
        cfg.scalesToFit = true
        cfg.capturesAudio = false
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let stream = SCStream(filter: filter, configuration: cfg, delegate: self)
        do {
            try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        } catch {
            NSLog("[AirBridge] mirror: addStreamOutput failed: %@", error.localizedDescription)
            stop(notify: true, reason: "capture setup")
            return
        }
        active?.stream = stream
        stream.startCapture { [weak self] error in
            guard let self, let error else { return }
            self.queue.async {
                guard self.active?.generation == gen else { return }
                NSLog("[AirBridge] mirror: startCapture failed: %@", error.localizedDescription)
                self.stop(notify: true, reason: "capture start")
            }
        }
        guard let sink = active?.sink else { return }
        PeerBus.shared.send(to: sink, ["t": "mirror_info", "codec": codec.rawValue, "w": w, "h": h, "fps": fps,
                                       "display": ["w": nativeW, "h": nativeH]])
        startTimer()
    }

    private func stop(notify: Bool, reason: String) {
        guard let a = active else { return }
        active = nil
        timer?.cancel()
        timer = nil
        a.encoder?.invalidate()
        if let s = a.stream { s.stopCapture { _ in } }
        if notify { PeerBus.shared.send(to: a.sink, ["t": "mirror_stop"]) }
        setBounds(nil, nil)
        ControlStatus.shared.setMirroring(nil)
        NSLog("[AirBridge] mirror: stopped (%@)", reason)
    }

    private func setBounds(_ peer: String?, _ r: CGRect?) {
        boundsLock.lock(); _mirrorPeer = peer; _bounds = r; boundsLock.unlock()
    }

    // MARK: capture → encode (queue)

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, var a = active, a.stream === stream else { return }
        // Only complete frames carry a new image; idle/blank frames have no pixel buffer.
        if let atts = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
           let raw = atts.first?[.status] as? Int, let status = SCFrameStatus(rawValue: raw), status != .complete {
            return
        }
        guard let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        let ptsSec = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sb))
        a.lastBuffer = pb
        active = a
        submit(pb, ptsSec.isFinite ? ptsSec : CACurrentMediaTimeCompat())
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        queue.async {
            guard self.active?.stream === stream else { return }
            NSLog("[AirBridge] mirror: stream stopped: %@", error.localizedDescription)
            self.stop(notify: true, reason: "capture error")
        }
    }

    private func submit(_ pb: CVPixelBuffer, _ ptsSec: Double) {
        guard var a = active, let enc = a.encoder else { return }
        let now = CACurrentMediaTimeCompat()
        var pts = UInt64(max(0, ptsSec) * 1_000_000)
        if pts <= a.lastPts { pts = a.lastPts + 1 }
        if a.sent.count >= Self.maxUnacked {
            // Sink is behind: skip this frame entirely (never encoded, so the reference chain stays intact).
            a.statDropped += 1
            active = a
            return
        }
        a.lastPts = pts
        a.lastFrameAt = now
        a.sent.append((pts, now))
        active = a
        enc.encode(pb, ptsUs: pts)
    }

    private func emit(_ out: MirrorEncoder.Output, _ gen: Int) {
        guard var a = active, a.generation == gen else { return }
        if out.annexB.isEmpty {
            a.sent.removeAll { $0.pts == out.ptsUs }
            a.statDropped += 1
            active = a
            return
        }
        var frame = Data(capacity: 10 + out.annexB.count)
        frame.append(PeerBus.binaryKindVideo)
        withUnsafeBytes(of: out.ptsUs.bigEndian) { frame.append(contentsOf: $0) }
        frame.append((out.keyframe ? 0x01 : 0) | (out.hasParameterSets ? 0x02 : 0))
        frame.append(out.annexB)
        guard frame.count <= 1_048_576 + 17 else {
            NSLog("[AirBridge] mirror: frame too large (%d bytes), dropping", frame.count)
            a.sent.removeAll { $0.pts == out.ptsUs }
            active = a
            a.encoder?.requestKeyframe()
            lowerBitrate(by: 0.5)
            return
        }
        PeerBus.shared.sendBinary(to: a.sink, frame)
        let now = CACurrentMediaTimeCompat()
        a.statFrames += 1
        a.statBytes += frame.count
        a.statEncMs += out.encodeMs
        a.statEncMax = max(a.statEncMax, out.encodeMs)
        a.statPipeMs += max(0, now - Double(out.ptsUs) / 1_000_000) * 1000
        active = a
    }

    // MARK: flow control (queue)

    private func ack(_ pts: UInt64) {
        guard var a = active else { return }
        let now = CACurrentMediaTimeCompat()
        a.lastAck = now
        a.lastAckPts = max(a.lastAckPts, pts)
        if let hit = a.sent.last(where: { $0.pts <= pts }) {
            a.lagMs = (now - hit.at) * 1000
        }
        a.sent.removeAll { $0.pts <= pts }
        active = a
        if a.lagMs > 150 { lowerBitrate(by: 0.25) }
    }

    private func lowerBitrate(by fraction: Double) {
        guard var a = active else { return }
        let now = CACurrentMediaTimeCompat()
        // At most one cut per 500 ms so a single slow burst doesn't collapse the bitrate.
        guard now - a.lastRaise > 0.5 else { return }
        a.bitrate = max(Self.minBitrate, Int(Double(a.bitrate) * (1 - fraction)))
        a.lastRaise = now
        a.encoder?.setBitrate(a.bitrate)
        active = a
    }

    private func startTimer() {
        timer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 0.1, repeating: 0.1, leeway: .milliseconds(10))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    private func tick() {
        guard var a = active else { return }
        let now = CACurrentMediaTimeCompat()
        // §8.7: stop when the sink stops acking for 3 s.
        if now - a.lastAck > 3 {
            stop(notify: true, reason: "no ack for 3 s")
            return
        }
        // Healthy (acks keep up, low lag, nothing dropped recently): +10 % every 5 s up to the request.
        if a.lagMs < 100, a.sent.count < Self.ackWindow, now - a.lastRaise >= 5, a.bitrate < a.maxBitrate {
            a.bitrate = min(a.maxBitrate, Int(Double(a.bitrate) * 1.1))
            a.lastRaise = now
            a.encoder?.setBitrate(a.bitrate)
            active = a
        }
        // ScreenCaptureKit only delivers frames when the screen changes. Re-encode the last frame at
        // 10 fps while idle so the picture sharpens and the sink keeps acking.
        if now - a.lastFrameAt >= 0.1, let pb = a.lastBuffer {
            submit(pb, now)
        }
        if now - a.statSince >= 5, let enc = active?.encoder {
            let s = active!
            let n = max(1, s.statFrames)
            NSLog("[AirBridge] mirror: %@ %.1f fps, enc avg %.1f ms max %.1f ms, capture→send %.1f ms, lag %.0f ms, %d kbps (target %d), dropped %d",
                  enc.codec.rawValue, Double(s.statFrames) / (now - s.statSince), s.statEncMs / Double(n), s.statEncMax,
                  s.statPipeMs / Double(n), s.lagMs, s.statBytes * 8 / 1000 / max(1, Int(now - s.statSince)),
                  s.bitrate / 1000, s.statDropped)
            active?.statFrames = 0; active?.statDropped = 0; active?.statBytes = 0
            active?.statEncMs = 0; active?.statEncMax = 0; active?.statPipeMs = 0; active?.statSince = now
        }
    }
}
