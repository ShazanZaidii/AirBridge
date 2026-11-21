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
}
