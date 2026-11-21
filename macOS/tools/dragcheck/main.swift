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

// MARK: parking spot (the frozen cursor must not rest on the screen edge)

do {
    let screen = CGRect(x: 0, y: 0, width: 1512, height: 982)
    func inside(_ p: CGPoint, _ r: CGRect) -> Bool { r.contains(p) }
    // right edge (the usual: the phone sits to the right), cursor pinned at x = 1511, y = 400
    let r = CarryPark.make(display: screen, vertical: true, atMin: false, along: 400, span: (100, 700))
    check(r.point.x <= screen.maxX - 1 - CarryPark.inset + 0.001 && r.point.x > screen.maxX - CarryPark.depth, "right edge: parked inside, away from the edge")
    check(inside(r.point, r.rect), "right edge: the catcher covers the parked cursor")
    check(r.rect.maxX <= screen.maxX && r.rect.minY >= screen.minY && r.rect.maxY <= screen.maxY, "right edge: catcher stays on the display")
    check(r.point.y == 400, "right edge: same height as the cursor")
    let l = CarryPark.make(display: screen, vertical: true, atMin: true, along: 400, span: (100, 700))
    check(l.point.x >= CarryPark.inset - 0.001 && l.point.x < CarryPark.depth && inside(l.point, l.rect) && l.rect.minX == 0, "left edge")
    let t = CarryPark.make(display: screen, vertical: false, atMin: true, along: 900, span: (0, 1512))
    check(t.point.y >= CarryPark.inset - 0.001 && inside(t.point, t.rect) && t.rect.minY == 0, "top edge")
    let b = CarryPark.make(display: screen, vertical: false, atMin: false, along: 900, span: (0, 1512))
    check(b.point.y <= screen.maxY - 1 - CarryPark.inset + 0.001 && inside(b.point, b.rect) && b.rect.maxY == screen.maxY, "bottom edge")
    // a short span, and the cursor in a corner: still a catcher of a useful length that holds the cursor, on the display
    let c = CarryPark.make(display: screen, vertical: true, atMin: false, along: 981, span: (975, 982))
    check(inside(c.point, c.rect) && c.rect.height >= CarryPark.minAlong / 2 && c.rect.maxY <= screen.maxY && c.point.y <= screen.maxY - 2, "corner: clamped onto the display, catcher holds the cursor")
    // the cursor is never closer to the edge than the inset
    for (vert, min) in [(true, true), (true, false), (false, true), (false, false)] {
        let q = CarryPark.make(display: screen, vertical: vert, atMin: min, along: 300, span: (0, 2000))
        let d = vert ? (min ? q.point.x - screen.minX : screen.maxX - 1 - q.point.x) : (min ? q.point.y - screen.minY : screen.maxY - 1 - q.point.y)
        check(d >= CarryPark.inset - 0.001, "never rests nearer the edge than the inset (vertical=\(vert) min=\(min))")
    }
}

print("\(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
