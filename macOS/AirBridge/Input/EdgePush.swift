import Foundation

/// Whether a push against a screen edge means "go to the device over there" (protocol.md §8.2 Capture intent). Pure, so
/// tools/edgecheck can test it. Fed with the outward motion of each event while the cursor is pinned on the edge.
///
/// Two ways through, so a deliberate push always crosses (issue #6):
/// - fast: `threshold` points of outward motion within half a second (a flick into the edge);
/// - steady: pushing without a break for `dwell` (a slow, deliberate push that never adds up to the threshold in half a
///   second, which used to be ignored however long it went on).
/// Small backwards jitter from a trackpad doesn't start over (it used to: one point inward emptied everything).
/// Brushing the edge, overshooting it while aiming at something there, stays on the Mac.
nonisolated struct EdgePush: Sendable {
    static let window = 0.5
    /// A pause longer than this ends a steady push.
    static let gap = 0.18
    /// Inward motion tolerated before the push starts over.
    static let jitter = 6.0
    /// The steady push needs at least this much outward motion too (a resting hand drifts a point or two).
    static let steadyMin = 14.0

    /// How long a steady push takes for a threshold (Easy 60 → 0.25 s, Normal 100 → 0.35 s, Firm 160 → 0.5 s).
    static func dwell(_ threshold: Double) -> Double { min(0.6, max(0.2, 0.1 + threshold * 0.0025)) }

    enum Result: Equatable {
        /// 0…1, for the glow on the edge.
        case charging(Double)
        case cross
        case reset
    }

    private var samples: [(t: Double, v: Double)] = []
    private var since: Double?
    private var last: Double?
    private var total = 0.0
    private var inward = 0.0

    var isEmpty: Bool { samples.isEmpty && since == nil }

    mutating func feed(t: Double, push: Double, threshold: Double) -> Result {
        if push < 0 {
            inward += -push
            if inward > Self.jitter { self = EdgePush(); return .reset }
            return .charging(level(t, threshold))
        }
        guard push > 0 else { return .charging(level(t, threshold)) }
        if let l = last, t - l > Self.gap { since = nil }
        if since == nil { since = t; total = 0; inward = 0 }
        last = t
        total += push
        samples.append((t, push))
        samples.removeAll { t - $0.t > Self.window }
        let sum = samples.reduce(0) { $0 + $1.v }
        if sum >= threshold { self = EdgePush(); return .cross }
        if let s = since, t - s >= Self.dwell(threshold), total >= Self.steadyMin { self = EdgePush(); return .cross }
        return .charging(level(t, threshold))
    }

    /// No events for a while: the fast path's motion ages out, a steady push ends after a pause. True = nothing left.
    mutating func tick(_ t: Double) -> Bool {
        samples.removeAll { t - $0.t > Self.window }
        if let l = last, t - l > Self.gap { since = nil; total = 0 }
        if samples.isEmpty && since == nil { self = EdgePush(); return true }
        return false
    }

    func level(_ t: Double, _ threshold: Double) -> Double {
        let fast = samples.filter { t - $0.t <= Self.window }.reduce(0) { $0 + $1.v } / threshold
        var steady = 0.0
        if let s = since { steady = min((t - s) / Self.dwell(threshold), total / Self.steadyMin) }
        return min(1, max(fast, steady))
    }
}
