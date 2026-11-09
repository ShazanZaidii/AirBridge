// Self-check for the pure drag-carry logic in AirBridge/Input/DragCarry.swift (protocol.md §8.8).
// Run: tools/dragcheck/run.sh   (exit code 0 = all passed). Never launches the app.
import Foundation

var passed = 0, failed = 0
func check(_ ok: Bool, _ what: String) {
    if ok { passed += 1 } else { failed += 1; print("FAIL: \(what)") }
}

// MARK: controller

do {
    var c = DragCarryController(firstId: 5)
    check(c.release() == .none && c.end() == .none, "idle: release/end do nothing")
    check(c.begin(peer: "phone") == .carry(peer: "phone", id: 5), "begin sends drag_carry with the first id")
    check(c.isCarrying && c.peer == "phone", "carrying phone")
    check(c.release() == .drop(peer: "phone", id: 5), "release over the target = drop")
    check(!c.isCarrying && c.release() == .none, "after drop: idle, a second release does nothing")
    check(c.end() == .none, "after drop: end of capture sends no cancel")

    check(c.begin(peer: "pc") == .carry(peer: "pc", id: 6), "next carry gets a new id")
    check(c.end() == .cancel(peer: "pc", id: 6), "cursor back before release = cancel")
    check(c.release() == .none, "release after a cancel is local again")

    _ = c.begin(peer: "pc")
    check(c.end(peerGone: true) == .none && !c.isCarrying, "disconnect mid-carry: nothing to send, idle")

    var w = DragCarryController(firstId: 0x3FFF_FFFF)
    _ = w.begin(peer: "a")
    check(w.begin(peer: "a") == .carry(peer: "a", id: 1), "ids wrap and stay positive")
}

check(DragCarryController.eligible(["ctl_target", "xfer", "drag_files"]), "eligible with all three caps")
check(!DragCarryController.eligible(["ctl_target", "xfer"]), "older peer (no drag_files) never gets a carry")
check(!DragCarryController.eligible(["ctl_target", "drag_files"]), "no xfer, no carry")
check(DragCarryController.dragHasFiles(changeCountAtDown: 10, changeCountNow: 11, fileCount: 2), "fresh drag pasteboard with files")
check(!DragCarryController.dragHasFiles(changeCountAtDown: 10, changeCountNow: 10, fileCount: 2), "stale pasteboard (text selection drag) is not a file drag")
check(!DragCarryController.dragHasFiles(changeCountAtDown: 10, changeCountNow: 12, fileCount: 0), "no files, no carry")
check(!DragCarryController.dragHasFiles(changeCountAtDown: -1, changeCountNow: 3, fileCount: 1), "unknown button-down state: no carry")

// MARK: payload

do {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("dragcheck-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: dir.appendingPathComponent("Photos"), withIntermediateDirectories: true)
    let a = dir.appendingPathComponent("IMG_0001.jpg"), b = dir.appendingPathComponent("notes.txt")
    let m = dir.appendingPathComponent("clip.mov"), s = dir.appendingPathComponent("song.mp3")
    try! Data(count: 1000).write(to: a); try! Data(count: 24).write(to: b)
    try! Data(count: 5).write(to: m); try! Data(count: 7).write(to: s)
    let folder = dir.appendingPathComponent("Photos", isDirectory: true)
    try! Data(count: 99).write(to: folder.appendingPathComponent("inside.jpg"))

    let one = DragPayload.describe([a])
    check(one == DragPayload(names: ["IMG_0001.jpg"], kinds: ["image"], count: 1, totalBytes: 1000), "one image")
    let mixed = DragPayload.describe([folder, b, m, s])
    check(mixed.names == ["Photos", "notes.txt", "clip.mov"], "names capped at 3 in drag order")
    check(mixed.kinds == ["folder", "file", "video"], "kinds aligned: folder, file, video")
    check(mixed.count == 4, "count = all top-level items")
    check(mixed.totalBytes == 24 + 5 + 7, "folders add no bytes")
    check(DragPayload.kind(for: s, isDirectory: false) == "audio", "mp3 is audio")
    let msg = one.message(id: 9)
    check(msg["t"] as? String == "drag_carry" && msg["id"] as? Int == 9 && msg["count"] as? Int == 1, "drag_carry message")
    check((msg["totalBytes"] as? Int64) == 1000 && (msg["names"] as? [String]) == ["IMG_0001.jpg"], "message fields")
    let data = try? JSONSerialization.data(withJSONObject: msg)
    check(data != nil, "message is valid JSON")
    try? FileManager.default.removeItem(at: dir)
}

// MARK: target ghost

func info(_ id: Int, _ from: String = "mac", n: Int = 1) -> DragGhostState.Info {
    DragGhostState.info(from: from, ["id": id, "names": (1...n).map { "f\($0).jpg" }, "kinds": Array(repeating: "image", count: n),
                                     "count": n, "totalBytes": 1000 * n])!
}

do {
    var g = DragGhostState()
    check(g.carry(from: "mac", holder: "mac", info(1)) == .show(info(1)), "carry from the holder shows the ghost")
    check(g.drop(from: "mac", id: 1) == .dropped(info(1), ghostWasShown: true), "drop settles it")
    check(g.shown == nil && g.drop(from: "mac", id: 1) == .none, "duplicate drop ignored")

    _ = g.carry(from: "mac", holder: "mac", info(2))
    check(g.cancel(from: "mac", id: 3) == .none, "cancel of another id ignored")
    check(g.cancel(from: "mac", id: 2) == .hide && g.drop(from: "mac", id: 2) == .none, "cancel hides; no confirmation later")

    check(g.carry(from: "pc", holder: "mac", info(1, "pc")) == .none, "only the explicit holder may carry")
    check(g.carry(from: "mac", holder: nil, info(1)) == .none, "no explicit lease, no ghost")

    _ = g.carry(from: "mac", holder: "mac", info(4))
    check(g.leave("pc") == .none, "another peer's leave keeps it")
    check(g.leave("mac") == .hide, "ctl_leave hides it")
    check(g.drop(from: "mac", id: 4) == .dropped(info(4), ghostWasShown: false), "a drop racing our ctl_leave still confirms")

    _ = g.carry(from: "mac", holder: "mac", info(5))
    check(g.peerGone("mac") == .hide && g.drop(from: "mac", id: 5) == .none, "disconnect mid-carry forgets it")

    _ = g.carry(from: "mac", holder: "mac", info(6))
    check(g.carry(from: "mac", holder: "mac", info(7, n: 3)) == .show(info(7, n: 3)), "a new carry replaces the old")
    check(g.drop(from: "mac", id: 6) == .none, "old id is gone")
}

do {
    check(DragGhostState.info(from: "m", ["names": ["a"]]) == nil, "no id: ignored")
    check(DragGhostState.info(from: "m", ["id": -1]) == nil, "negative id: ignored")
    check(DragGhostState.info(from: "m", ["id": Double.nan]) == nil, "NaN id: ignored")
    let i = DragGhostState.info(from: "m", ["id": 7, "names": [" a.txt ", "", "Photos", "b", "c"], "kinds": ["file", "folder", "weird"],
                                            "count": 2, "totalBytes": -5])!
    check(i.names == ["a.txt", "Photos", "b"], "names trimmed, blanks dropped, at most 3")
    check(i.kinds == ["file", "folder", "file"], "unknown kinds become file")
    check(i.count == 3 && i.totalBytes == 0, "count never below the names; negative bytes → 0")
    let huge = DragGhostState.info(from: "m", ["id": 1, "count": 1e30, "totalBytes": 1e30])!
    check(huge.count == 1_000_000_000 && huge.totalBytes > 0, "huge numbers are clamped, never trap")
}

print("\(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
