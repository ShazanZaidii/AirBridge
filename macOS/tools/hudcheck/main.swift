// Self-check for the transfer cards' state (AirBridge/DropHUD/HUDState.swift): sending cards to the background, bringing
// them back, how long they stay, and the "Sent · size · time" text. Run: tools/hudcheck/run.sh (exit code 0 = all passed).
import Foundation

var passed = 0, failed = 0
func check(_ ok: Bool, _ what: String) {
    if ok { passed += 1 } else { failed += 1; print("FAIL: \(what)") }
}

let t0 = Date(timeIntervalSince1970: 1_800_000_000)
func status(_ id: String, _ phase: TransferStatus.Phase, done: Int64 = 0, elapsed: TimeInterval = 0,
            dir: TransferStatus.Direction = .incoming) -> TransferStatus {
    TransferStatus(id: id, direction: dir, peerName: "V2404", fileCount: 1, firstName: "movie.mov", total: 1_000_000_000,
                   done: done, phase: phase, elapsed: elapsed, peerId: "phone")
}

// A running transfer swiped away: off screen, still listed, and its later reports keep updating it.
var s = HUDState()
s.ingest(status("a", .active, done: 10), now: t0)
s.ingest(status("b", .active, done: 10), now: t0)
let a = s.cards.first { $0.transferKey == "a" }!.id
s.sendToBackground(a)
check(s.visible.map(\.transferKey) == ["b"], "a card in the background is not on screen")
check(s.backgrounded.map(\.transferKey) == ["a"], "a card in the background is still listed")
s.ingest(status("a", .active, done: 500_000_000), now: t0.addingTimeInterval(5))
check(s.backgrounded.first?.status?.done == 500_000_000, "progress still reaches a card in the background")
check(s.visible.count == 1, "progress does not bring a background card back on screen")

// It finishes in the background: listed for half an hour, then gone, even with auto-hide on.
s.ingest(status("a", .done, done: 1_000_000_000, elapsed: 22), now: t0.addingTimeInterval(22))
s.expire(now: t0.addingTimeInterval(22 + 60), autoHide: true)
check(s.backgrounded.count == 1, "a finished background card is still listed a minute later (auto-hide on)")
check(s.nextEvent(autoHide: false) == t0.addingTimeInterval(22 + HUDState.backgroundKeep), "the next event is when it stops being listed")
s.expire(now: t0.addingTimeInterval(22 + HUDState.backgroundKeep + 1), autoHide: false)
check(s.backgrounded.isEmpty, "after half an hour a finished background card is gone")
s.ingest(status("a", .done, done: 1_000_000_000), now: t0.addingTimeInterval(4000))
check(!s.cards.contains { $0.transferKey == "a" }, "a card that went away does not come back with a late report")

// Bringing a card back: on top, on screen, with fresh time before auto-hide.
var u = HUDState()
u.ingest(status("x", .done, done: 1_000_000_000, elapsed: 3), now: t0)
u.ingest(status("y", .active, done: 1), now: t0)
let x = u.cards.first { $0.transferKey == "x" }!.id
u.sendToBackground(x)
u.bringBack(x, now: t0.addingTimeInterval(100))
check(u.visible.first?.transferKey == "x", "a card brought back is on top")
check(u.backgrounded.isEmpty, "a card brought back is no longer in the background")
u.expire(now: t0.addingTimeInterval(102), autoHide: true)
check(u.visible.contains { $0.transferKey == "x" }, "a finished card brought back gets its seconds on screen again")
u.expire(now: t0.addingTimeInterval(100 + HUDState.autoHideAfter + 0.1), autoHide: true)
check(!u.visible.contains { $0.transferKey == "x" }, "and then auto-hides as usual")

// The finished text.
check(TransferText.took(0) == nil, "no time when unknown")
check(TransferText.took(4.24) == "4.2 s", "under 10 s: one decimal")
check(TransferText.took(22.4) == "22 s", "seconds")
check(TransferText.took(65) == "1 min 5 s", "minutes and seconds")
check(TransferText.took(120) == "2 min", "whole minutes")
check(TransferText.took(3725) == "1 h 2 min", "hours and minutes")
let fin = TransferText.finished(status("z", .done, done: 1_000_000_000, elapsed: 22, dir: .outgoing))
check(fin.hasPrefix("Sent · ") && fin.hasSuffix(" · 22 s"), "Sent · size · time (\(fin))")
check(!TransferText.finished(status("z", .done)).contains(" s"), "no time part when the time is unknown")

print("\(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
