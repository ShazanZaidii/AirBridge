import AppKit

var failed = 0, passed = 0
func check(_ name: String, _ ok: Bool) { if ok { passed += 1 } else { failed += 1; print("FAIL: \(name)") } }

let dir = FileManager.default.temporaryDirectory.appendingPathComponent("clipcheck-\(UUID().uuidString)")
try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: dir) }

/// A real picture, encoded in the given format.
func picture(_ type: NSBitmapImageRep.FileType, to name: String) -> URL {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 64, pixelsHigh: 48, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSColor.systemOrange.setFill(); NSRect(x: 0, y: 0, width: 64, height: 48).fill()
    NSGraphicsContext.restoreGraphicsState()
    let url = dir.appendingPathComponent(name)
    try! rep.representation(using: type, properties: [:])!.write(to: url)
    return url
}

func isPNG(_ d: Data?) -> Bool { d.map { $0.count > 8 && Array($0.prefix(8)) == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] } ?? false }
func size(_ d: Data?) -> NSSize? { d.flatMap { NSBitmapImageRep(data: $0) }.map { NSSize(width: $0.pixelsWide, height: $0.pixelsHigh) } }

let png = picture(.png, to: "a.png"), jpg = picture(.jpeg, to: "b.JPG"), gif = picture(.gif, to: "c.gif"), tiff = picture(.tiff, to: "d.tiff")
let text = dir.appendingPathComponent("notes.txt"); try! "hello".write(to: text, atomically: true, encoding: .utf8)
let fake = dir.appendingPathComponent("fake.png"); try! Data("not a picture".utf8).write(to: fake)
let empty = dir.appendingPathComponent("empty.jpg"); FileManager.default.createFile(atPath: empty.path, contents: Data())

check("png is recognised", ClipboardImages.isImage(png))
check("extension case does not matter (.JPG)", ClipboardImages.isImage(jpg))
check("a text file is not an image", !ClipboardImages.isImage(text))
check("png file gives png", isPNG(ClipboardImages.pngData(for: png)))
check("jpeg gives a real png of the same size", isPNG(ClipboardImages.pngData(for: jpg)) && size(ClipboardImages.pngData(for: jpg)) == NSSize(width: 64, height: 48))
check("gif gives png", isPNG(ClipboardImages.pngData(for: gif)))
check("tiff gives png", isPNG(ClipboardImages.pngData(for: tiff)))
check("text file gives nothing", ClipboardImages.pngData(for: text) == nil)
check("a file named .png that is not a picture still passes through unchanged for png", ClipboardImages.pngData(for: fake) != nil)
check("an empty file gives nothing", ClipboardImages.pngData(for: empty) == nil)
check("a missing file gives nothing", ClipboardImages.pngData(for: dir.appendingPathComponent("nope.png")) == nil)

// The real thing, on a private pasteboard: file locations first (as the app does), then the picture.
let pb = NSPasteboard(name: NSPasteboard.Name("airbridge.clipcheck.\(UUID().uuidString)"))
pb.clearContents(); pb.writeObjects([jpg as NSURL])
let beforeTypes = Set(pb.types ?? [])
check("before: a file location and no image", beforeTypes.contains(.fileURL) && !beforeTypes.contains(.png))
let change = pb.changeCount
check("png is added", ClipboardImages.addPNG(ClipboardImages.pngData(for: jpg)!, to: pb, expectedChange: change))
let types = Set(pb.types ?? [])
check("after: still a file location", types.contains(.fileURL))
check("after: the picture is there as png", types.contains(.png) && isPNG(pb.data(forType: .png)))
check("after: the file can still be read back as a url", (pb.readObjects(forClasses: [NSURL.self], options: nil) as? [URL])?.first?.lastPathComponent == "b.JPG")
check("after: an app that asks for an image gets one", NSImage(pasteboard: pb) != nil)

// Someone copying something else in between must not be overwritten.
let pb2 = NSPasteboard(name: NSPasteboard.Name("airbridge.clipcheck.\(UUID().uuidString)"))
pb2.clearContents(); pb2.writeObjects([jpg as NSURL]); let stale = pb2.changeCount
pb2.clearContents(); pb2.setString("something newer", forType: .string)
check("a newer copy is not overwritten", !ClipboardImages.addPNG(Data([1, 2, 3]), to: pb2, expectedChange: stale))
check("and the newer copy is intact", pb2.string(forType: .string) == "something newer" && !(pb2.types ?? []).contains(.png))


// setImage / pngFromPasteboard
let pb3 = NSPasteboard(name: NSPasteboard.Name("airbridge.clipcheck.\(UUID().uuidString)"))
let png3 = ClipboardImages.pngData(for: jpg)!
ClipboardImages.setImage(png: png3, on: pb3)
let t3 = Set(pb3.types ?? [])
check("setImage: png and tiff, no file location", t3.contains(.png) && t3.contains(.tiff) && !t3.contains(.fileURL))
check("setImage: png is the same bytes", pb3.data(forType: .png) == png3)
check("pngFromPasteboard returns the png", ClipboardImages.pngFromPasteboard(pb3) == png3)
let pb4 = NSPasteboard(name: NSPasteboard.Name("airbridge.clipcheck.\(UUID().uuidString)"))
pb4.clearContents(); pb4.declareTypes([.tiff], owner: nil); pb4.setData(NSImage(contentsOf: tiff)!.tiffRepresentation!, forType: .tiff)
check("pngFromPasteboard converts a tiff-only clipboard to png", isPNG(ClipboardImages.pngFromPasteboard(pb4)))
let pb5 = NSPasteboard(name: NSPasteboard.Name("airbridge.clipcheck.\(UUID().uuidString)"))
pb5.clearContents(); pb5.setString("just text", forType: .string)
check("pngFromPasteboard gives nothing for text", ClipboardImages.pngFromPasteboard(pb5) == nil)

// inbox / outbox
let out = ClipboardInbox.outFile(named: "clipboard.png", data: png3)
check("outFile writes the bytes", out != nil && (try? Data(contentsOf: out!)) == png3 && out!.lastPathComponent == "clipboard.png")
let out2 = ClipboardInbox.outFile(named: "clipboard.png", data: png3)
check("two outFiles never share a folder", out != nil && out2 != nil && out!.deletingLastPathComponent() != out2!.deletingLastPathComponent())
ClipboardInbox.discard(out!); ClipboardInbox.discard(out2!)
check("discard removes the file and its folder", !FileManager.default.fileExists(atPath: out!.deletingLastPathComponent().path))

let fakeRoot = dir.appendingPathComponent("cache")
let inDir = fakeRoot.appendingPathComponent("in"), outDir = fakeRoot.appendingPathComponent("out")
try! FileManager.default.createDirectory(at: inDir, withIntermediateDirectories: true)
try! FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
let oldFile = inDir.appendingPathComponent("old.png"), newFile = inDir.appendingPathComponent("new.png"), oldOut = outDir.appendingPathComponent("o.png")
for f in [oldFile, newFile, oldOut] { try! Data([1]).write(to: f) }
let twoDaysAgo = Date().addingTimeInterval(-2 * 86_400)
try! FileManager.default.setAttributes([.modificationDate: twoDaysAgo], ofItemAtPath: oldFile.path)
try! FileManager.default.setAttributes([.modificationDate: twoDaysAgo], ofItemAtPath: oldOut.path)
ClipboardInbox.purgeOld(in: fakeRoot)
check("purge removes data older than a day", !FileManager.default.fileExists(atPath: oldFile.path) && !FileManager.default.fileExists(atPath: oldOut.path))
check("purge keeps recent data", FileManager.default.fileExists(atPath: newFile.path))
check("the inbox is in the caches folder, not Downloads", ClipboardInbox.directory.path.contains("/Library/Caches/") && !ClipboardInbox.directory.path.contains("Downloads"))

print("clipcheck: \(passed) passed, \(failed) failed"); exit(failed == 0 ? 0 : 1)
