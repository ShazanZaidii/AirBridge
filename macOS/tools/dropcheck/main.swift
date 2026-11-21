// Self-check for how the drop dock handles short-lived files (AirBridge/DropHUD/DropLogic.swift).
// Run: tools/dropcheck/run.sh   (exit code 0 = all passed). Never launches the app, never touches your files.
import Foundation

var passed = 0, failed = 0
func check(_ ok: Bool, _ what: String) {
    if ok { passed += 1 } else { failed += 1; print("FAIL: \(what)") }
}

let fm = FileManager.default
let tmp = fm.temporaryDirectory

// What macOS does with a screenshot thumbnail: the picture sits in TemporaryItems until the drop ends.
let thumbDir = tmp.appendingPathComponent("TemporaryItems/NSIRD_screencaptureui_check\(UUID().uuidString.prefix(6))", isDirectory: true)
try! fm.createDirectory(at: thumbDir, withIntermediateDirectories: true)
let shot = thumbDir.appendingPathComponent("Screenshot 2026-11-21 at 18.04.47.png")
let bytes = Data((0..<4096).map { UInt8($0 % 251) })
try! bytes.write(to: shot)

// A normal file the user keeps somewhere safe (outside the temporary areas).
let home = fm.homeDirectoryForCurrentUser.appendingPathComponent("Library/Caches/AirBridge-dropcheck-\(UUID().uuidString.prefix(6))", isDirectory: true)
try! fm.createDirectory(at: home, withIntermediateDirectories: true)
let keep = home.appendingPathComponent("Holiday.mov")
try! bytes.write(to: keep)
defer { try? fm.removeItem(at: home); try? fm.removeItem(at: thumbDir); try? fm.removeItem(at: DropItems.stagingRoot) }

check(DropItems.isVolatile(shot), "a file in TemporaryItems is short-lived")
check(DropItems.isVolatile(URL(fileURLWithPath: "/var/folders/ab/cdef/T/whatever.png")), "/var/folders is short-lived")
check(DropItems.isVolatile(URL(fileURLWithPath: "/tmp/x.png")), "/tmp is short-lived")
check(!DropItems.isVolatile(keep), "a file in the user's own folders is not")
check(!DropItems.isVolatile(URL(fileURLWithPath: "/Users/someone/Desktop/Screenshot.png")), "the saved screenshot on the Desktop is not")

let r0 = DropItems.stageVolatile([shot, keep]); let r = (files: r0.files, temp: r0.temp)
check(r.files.count == 2, "both files stay in the list, in order")
check(r.files[1] == keep, "the normal file is sent from where it is")
check(r.files[0] != shot && r.files[0].lastPathComponent == shot.lastPathComponent, "the thumbnail is copied under the same name")
check(r.temp.count == 1 && r.files[0].path.hasPrefix(r.temp[0].path + "/"), "the copy lives in one staging folder")
check(!DropItems.isVolatile(r.files[0]), "a staged copy is not copied again")

// The failure from the screenshot: macOS removes the thumbnail's file when the drop ends. The copy must survive.
try! fm.removeItem(at: thumbDir)
check(!fm.fileExists(atPath: shot.path), "(the original is gone, as macOS does)")
check((try? Data(contentsOf: r.files[0])) == bytes, "the staged copy is still there and byte-identical")

// Staging twice (or a second drop of the same name) never overwrites or fails.
let thumb2 = tmp.appendingPathComponent("TemporaryItems/NSIRD_screencaptureui_check2", isDirectory: true)
try! fm.createDirectory(at: thumb2, withIntermediateDirectories: true)
let a = thumb2.appendingPathComponent("Screenshot.png"), b = thumb2.appendingPathComponent("Other/Screenshot.png")
try! bytes.write(to: a)
try! fm.createDirectory(at: b.deletingLastPathComponent(), withIntermediateDirectories: true)
try! Data([1, 2, 3]).write(to: b)
let two0 = DropItems.stageVolatile([a, b]); let two = (files: two0.files, temp: two0.temp)
check(two.files.count == 2 && two.files[0] != two.files[1], "same-named short-lived files get distinct names")
check((try? Data(contentsOf: two.files[1])) == Data([1, 2, 3]), "…and each keeps its own content")
try? fm.removeItem(at: thumb2)

// A short-lived folder is copied whole.
let thumb3 = tmp.appendingPathComponent("TemporaryItems/NSIRD_check3/Trip", isDirectory: true)
try! fm.createDirectory(at: thumb3, withIntermediateDirectories: true)
try! bytes.write(to: thumb3.appendingPathComponent("a.jpg"))
let folder0 = DropItems.stageVolatile([thumb3]); let folder = (files: folder0.files, temp: folder0.temp)
check(folder.files.count == 1 && fm.fileExists(atPath: folder.files[0].appendingPathComponent("a.jpg").path), "a short-lived folder is copied with its contents")
try? fm.removeItem(at: tmp.appendingPathComponent("TemporaryItems/NSIRD_check3"))

// Something already gone stays in the list (the send then reports it by name) and does not crash.
let ghost = tmp.appendingPathComponent("TemporaryItems/NSIRD_gone/Gone.png")
let g = DropItems.stageVolatile([ghost])
check(g.files == [ghost] && g.temp.isEmpty, "a file that is already gone is left as it was")
check(g.failed == [ghost] && !g.errors.isEmpty, "…and is reported as failed, with a reason")

// Recovery: the picture that travels on the pasteboard...
var dir: URL?
let png = Data([0x89, 0x50, 0x4E, 0x47, 1, 2, 3])
let viaImage = DropItems.recover(ghost.deletingLastPathComponent().appendingPathComponent("Screenshot 2026-11-21 at 18.04.47.png"), imageData: png, stagingDir: &dir, wait: 0.2)
check(viaImage != nil && (try? Data(contentsOf: viaImage!)) == png && viaImage!.pathExtension == "png", "a failed screenshot is rebuilt from the pasteboard's picture")
// ...else the saved file of the same name, once it exists and has stopped growing.
let saveDir = DropItems.screenshotFolder
let probeName = "AirBridge-dropcheck-\(UUID().uuidString.prefix(6)).png"
let savedFile = saveDir.appendingPathComponent(probeName)
if fm.isWritableFile(atPath: saveDir.path) {
    DispatchQueue.global().asyncAfter(deadline: .now() + 0.6) { try? bytes.write(to: savedFile) }  // macOS saves it a moment later
    var d2: URL?
    let found = DropItems.recover(ghost.deletingLastPathComponent().appendingPathComponent(probeName), imageData: nil, stagingDir: &d2, wait: 5)
    check(found == savedFile, "a file saved a moment later is found by name in the screenshot folder")
    try? fm.removeItem(at: savedFile)
}
var d3: URL?
check(DropItems.recover(tmp.appendingPathComponent("TemporaryItems/never-\(UUID().uuidString.prefix(4)).png"), imageData: nil, stagingDir: &d3, wait: 0.6) == nil, "nothing to recover returns nil (and does not hang)")

print("\(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
