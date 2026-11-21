import AppKit

/// Pictures on the clipboard. A received image file used to be put there as a file location only: WhatsApp, Mail and
/// Finder take that, but anything that wants the picture itself (a terminal, Claude Code, Preview, Slack, Notes) finds no
/// image and pastes nothing. So the picture goes on next to the location, as PNG.
enum ClipboardImages {
    /// Larger source files are left as a file location only: turning a huge photo into PNG would be slow and heavy.
    static let maxSourceBytes = 30_000_000

    static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "heic", "heif", "gif", "tif", "tiff", "bmp", "webp"]

    static func isImage(_ url: URL) -> Bool { imageExtensions.contains(url.pathExtension.lowercased()) }

    /// The picture in an image file as PNG, or nil if it is not an image, too big, or cannot be read. Runs off the main thread.
    static func pngData(for url: URL) -> Data? {
        guard isImage(url),
              let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size > 0, size <= maxSourceBytes,
              let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        if url.pathExtension.lowercased() == "png" { return data }
        guard let image = NSImage(data: data), let tiff = image.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .png, properties: [:])
    }

    /// Adds the PNG to what is already on `pb`, if nobody has copied anything since `expectedChange`.
    /// Returns whether it was added.
    @discardableResult
    static func addPNG(_ png: Data, to pb: NSPasteboard, expectedChange: Int) -> Bool {
        guard pb.changeCount == expectedChange else { return false }
        pb.addTypes([.png], owner: nil)
        return pb.setData(png, forType: .png)
    }

    /// Puts a picture on the clipboard the way Apple's own screenshot-to-clipboard does: PNG and TIFF, nothing else.
    /// Returns the pasteboard's change count after the write.
    @discardableResult
    static func setImage(png: Data, on pb: NSPasteboard) -> Int {
        let tiff = NSBitmapImageRep(data: png)?.tiffRepresentation
        pb.clearContents()
        pb.declareTypes(tiff == nil ? [.png] : [.png, .tiff], owner: nil)
        pb.setData(png, forType: .png)
        if let tiff { pb.setData(tiff, forType: .tiff) }
        return pb.changeCount
    }

    /// The picture on the clipboard as PNG (converted from TIFF when that is all there is), or nil.
    static func pngFromPasteboard(_ pb: NSPasteboard) -> Data? {
        if let png = pb.data(forType: .png), !png.isEmpty { return png }
        if let tiff = pb.data(forType: .tiff), !tiff.isEmpty, let rep = NSBitmapImageRep(data: tiff) {
            return rep.representation(using: .png, properties: [:])
        }
        return nil
    }
}

/// Where clipboard transfers (§5.1) live: a private cache folder, never the downloads folder.
enum ClipboardInbox {
    static var root: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AirBridge", isDirectory: true).appendingPathComponent("Clipboard", isDirectory: true)
    }
    /// Clipboard data that came in from another device.
    static var directory: URL { root.appendingPathComponent("in", isDirectory: true) }
    /// Clipboard data waiting to be sent (an image is written here first).
    static var outbox: URL { root.appendingPathComponent("out", isDirectory: true) }

    /// Writes `data` to a fresh file called `name` in the outbox and returns it.
    static func outFile(named name: String, data: Data) -> URL? {
        let dir = outbox.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
        let url = dir.appendingPathComponent(name)
        return (try? data.write(to: url)) == nil ? nil : url
    }

    /// Removes an outbox file and the folder made for it.
    static func discard(_ url: URL) { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }

    /// Deletes cached clipboard data older than `age` seconds (a day by default). `root` is a parameter for testing.
    static func purgeOld(age: TimeInterval = 86_400, in root: URL = ClipboardInbox.root) {
        let fm = FileManager.default
        let cutoff = Date().addingTimeInterval(-age)
        for sub in [root.appendingPathComponent("in"), root.appendingPathComponent("out")] {
            for item in (try? fm.contentsOfDirectory(at: sub, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
                let when = (try? item.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantFuture
                if when < cutoff { try? fm.removeItem(at: item) }
            }
        }
    }
}
