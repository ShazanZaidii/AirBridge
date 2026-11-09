import AVFoundation
import Foundation

/// The Mac's ringtone for phone calls: the system's own "Opening" ringtone — what a Mac plays for iPhone calls —
/// read from macOS at run time (never copied into the app). If macOS doesn't have it, a soft self-made mallet
/// arpeggio instead. Loops until the call is answered, declined or ends; fades in so it never startles.
@MainActor final class Ringtone {
    /// macOS's ringtones (ToneLibrary). "Opening" is the default for iPhone calls on a Mac.
    nonisolated static let systemTone = URL(fileURLWithPath:
        "/System/Library/PrivateFrameworks/ToneLibrary.framework/Versions/A/Resources/Ringtones/Opening.m4r")

    private var player: AVAudioPlayer?
    private var stopWork: DispatchWorkItem?
    private(set) var isPlaying = false

    func start() {
        stopWork?.cancel(); stopWork = nil
        if isPlaying { return }
        if player == nil { player = Self.makePlayer() }
        guard let p = player else { return }
        p.numberOfLoops = -1
        p.currentTime = 0
        p.volume = 0.3
        p.prepareToPlay()
        p.play()
        p.setVolume(0.9, fadeDuration: 1.5)
        isPlaying = true
    }

    func stop() {
        guard isPlaying, let p = player else { return }
        isPlaying = false
        p.setVolume(0, fadeDuration: 0.25)
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.isPlaying else { return }
            self.player?.stop()
        }
        stopWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: work)
    }

    static func makePlayer() -> AVAudioPlayer? {
        if FileManager.default.isReadableFile(atPath: systemTone.path), let p = try? AVAudioPlayer(contentsOf: systemTone) { return p }
        return try? AVAudioPlayer(data: wav)
    }

    // MARK: Synthesis (fallback)

    /// One 3.4 s cycle: a rising E-major arpeggio played twice, then a pause. 44.1 kHz mono 16-bit WAV.
    nonisolated static let wav: Data = {
        let rate = 44_100.0
        let length = 3.4
        let n = Int(rate * length)
        var buf = [Double](repeating: 0, count: n)
        let notes: [(f: Double, t: Double, a: Double)] = [
            (659.25, 0.00, 0.55), (830.61, 0.17, 0.50), (987.77, 0.34, 0.50), (1318.51, 0.51, 0.45),
            (659.25, 1.10, 0.50), (830.61, 1.27, 0.46), (987.77, 1.44, 0.46), (1318.51, 1.61, 0.42),
        ]
        for note in notes {
            let start = Int(note.t * rate)
            let dur = Int(1.4 * rate)
            for i in 0..<dur where start + i < n {
                let t = Double(i) / rate
                let attack = min(1, t / 0.004)
                // Mallet on a wooden bar: the fundamental rings, the bar's 4th partial adds the "tock" and dies fast.
                let body = sin(2 * .pi * note.f * t) * exp(-t / 0.42)
                let tone = 0.35 * sin(2 * .pi * note.f * 3.93 * t) * exp(-t / 0.06)
                let air = 0.12 * sin(2 * .pi * note.f * 2 * t) * exp(-t / 0.2)
                buf[start + i] += note.a * attack * (body + tone + air)
            }
        }
        // A little room: two soft echoes.
        for (delay, gain) in [(0.085, 0.18), (0.19, 0.08)] {
            let d = Int(delay * rate)
            for i in stride(from: n - 1, through: d, by: -1) { buf[i] += gain * buf[i - d] }
        }
        // Gentle limiter, then 16-bit.
        let peak = buf.map(abs).max() ?? 1
        let scale = 0.7 / max(peak, 0.0001)
        var pcm = Data(capacity: n * 2)
        for v in buf {
            let s = tanh(v * scale * 1.2) / tanh(1.2)
            var i = Int16(max(-1, min(1, s)) * 32_000).littleEndian
            withUnsafeBytes(of: &i) { pcm.append(contentsOf: $0) }
        }
        var d = Data()
        func u32(_ v: UInt32) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8)); u32(UInt32(36 + pcm.count)); d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1); u32(44_100); u32(88_200); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(UInt32(pcm.count)); d.append(pcm)
        return d
    }()
}
