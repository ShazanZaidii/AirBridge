import Foundation

// Checks EdgePush (issue #6): deliberate pushes always cross, accidental contact never does.
var passed = 0, failed = 0
func check(_ ok: Bool, _ what: String) { if ok { passed += 1 } else { failed += 1; print("FAIL: \(what)") } }

/// Feeds `events` (seconds, outward points) and returns the time it crossed, or nil.
func run(_ events: [(Double, Double)], threshold: Double = 100) -> Double? {
    var p = EdgePush()
    for (t, v) in events {
        if p.feed(t: t, push: v, threshold: threshold) == .cross { return t }
    }
    return nil
}
/// A push of `speed` points per second for `seconds`, as 120 Hz trackpad events.
func steady(_ speed: Double, _ seconds: Double, from t0: Double = 0) -> [(Double, Double)] {
    stride(from: 0.0, to: seconds, by: 1.0 / 120).map { (t0 + $0, speed / 120) }
}

// Fast flick: 100 points in 0.15 s.
check(run(steady(700, 0.15)) != nil, "a flick into the edge crosses")
// Slow, deliberate: 80 pt/s never adds up to 100 in 0.5 s; it used to be ignored forever.
if let t = run(steady(80, 2)) { check(t <= 0.4, "a slow steady push crosses in about a third of a second (took \(t))") }
else { check(false, "a slow steady push crosses") }
// Very slow but steady: 45 pt/s.
check(run(steady(45, 2)) != nil, "a very slow steady push still crosses")
// Trackpad jitter: mostly outward with a point back now and then.
var jittery: [(Double, Double)] = []
for (i, e) in steady(90, 1.5).enumerated() { jittery.append(i % 10 == 9 ? (e.0, -1) : e) }
check(run(jittery) != nil, "small jitter backwards doesn't start over")
// Pulling back for real starts over.
var back = steady(90, 0.2) + [(0.21, -8)] + steady(90, 0.1, from: 0.22)
check(run(back) == nil, "pulling back 8 points starts over")
// Accidental: overshooting the edge while aiming at something there (a short, small push).
check(run(steady(120, 0.12)) == nil, "overshooting the edge stays on the Mac")
// Resting a hand: a point of drift now and then, with pauses.
let drift: [(Double, Double)] = (0..<10).map { (Double($0) * 0.3, 1) }
check(run(drift) == nil, "drift with pauses stays on the Mac")
// A pause longer than the gap ends a steady push: two short pushes don't add up.
check(run(steady(60, 0.2) + steady(60, 0.15, from: 0.5)) == nil, "two short pushes with a pause don't add up")
// Firm needs longer, Easy shorter.
let firm = run(steady(80, 2), threshold: 160) ?? 9, easy = run(steady(80, 2), threshold: 60) ?? 9
check(easy < firm, "Easy crosses sooner than Firm (easy \(easy), firm \(firm))")
// tick(): after a pause everything is gone.
var p = EdgePush()
_ = p.feed(t: 0, push: 30, threshold: 100)
check(!p.tick(0.1), "still charging right after a push")
check(p.tick(0.7), "empty after a pause")

print("edgecheck: \(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
