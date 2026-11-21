import AppKit

// Pure helpers for the drop dock: what is being dragged, and where the dock's targets sit.
// Nothing here touches windows, so it can be checked on its own.

/// User settings for the drop dock and the transfer cards.
nonisolated enum DropSettings {
    static let dockEnabledKey = "DropDockEnabled"
    static let autoHideKey = "TransferCardsAutoHide"

    /// Show the dock when files are dragged to the menu bar icon (on unless turned off).
    static var dockEnabled: Bool {
        UserDefaults.standard.object(forKey: dockEnabledKey) as? Bool ?? true
    }
    /// Hide finished transfer cards after a few seconds (off: cards stay until dismissed).
    static var autoHideFinished: Bool { UserDefaults.standard.bool(forKey: autoHideKey) }
}

/// Reads dragged files off a pasteboard and turns folders into the files inside them.
nonisolated enum DropItems {
    /// Most files one transfer offers (the protocol allows 10 000).
    static let maxFiles = 10_000

    /// True if the pasteboard holds files (as URLs or as file promises, e.g. from Photos or Mail).
    static func holdsFiles(_ pb: NSPasteboard) -> Bool {
        let types = Set(pb.types ?? [])
        if types.contains(.fileURL) { return true }
        return !types.isDisjoint(with: NSFilePromiseReceiver.readableDraggedTypes.map { NSPasteboard.PasteboardType($0) })
    }

    /// File and folder URLs on the pasteboard, in order, without duplicates.
    static func fileURLs(_ pb: NSPasteboard) -> [URL] {
        let objs = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) ?? []
        var seen = Set<String>()
        return objs.compactMap { $0 as? URL }.filter { seen.insert($0.standardizedFileURL.path).inserted }
    }

    /// Promised files waiting on the pasteboard (only when there are no plain file URLs).
    static func promises(_ pb: NSPasteboard) -> [NSFilePromiseReceiver] {
        pb.readObjects(forClasses: [NSFilePromiseReceiver.self], options: nil) as? [NSFilePromiseReceiver] ?? []
    }

    /// How many items are being dragged, for the dock's title (a folder counts once).
    static func count(_ pb: NSPasteboard) -> Int {
        let urls = fileURLs(pb).count
        return urls > 0 ? urls : (pb.pasteboardItems?.count ?? 0)
    }

    // MARK: Files that only exist while the drag lasts

    /// Where copies of short-lived dropped files are kept until they have been sent.
    static var stagingRoot: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("AirBridge Drops", isDirectory: true)
    }

    /// True for a file that lives in one of the system's temporary areas. Such files are often deleted or moved the
    /// moment the drop ends: the macOS screenshot thumbnail keeps its picture in `TemporaryItems` and saves it to the
    /// Desktop once it is dropped, and a screen-recording preview does the same. AirBridge starts sending a few seconds
    /// later (the Undo window), by which time the path is gone.
    static func isVolatile(_ url: URL) -> Bool {
        let p = url.standardizedFileURL.path
        let resolved = url.resolvingSymlinksInPath().path
        for path in [p, resolved] {
            if path.hasPrefix(stagingRoot.path + "/") || path.hasPrefix(stagingRoot.resolvingSymlinksInPath().path + "/") { return false }
        }
        if p.contains("/TemporaryItems/") || resolved.contains("/TemporaryItems/") { return true }
        let tmp = FileManager.default.temporaryDirectory
        let roots = [tmp.path, tmp.resolvingSymlinksInPath().path, "/var/folders", "/private/var/folders", "/tmp", "/private/tmp"]
        return roots.contains { p.hasPrefix($0 + "/") || resolved.hasPrefix($0 + "/") }
    }

    /// What [stageVolatile] did.
    struct Staged {
        /// The list to send, in the same order. A short-lived file that could not be copied stays as it was.
        var files: [URL]
        /// Folders holding the copies, to delete later.
        var temp: [URL]
        /// Short-lived files that could not be copied (already gone, or no permission): see [recover].
        var failed: [URL]
        /// Why, for the log.
        var errors: [String]
    }

    /// Copies the short-lived files of `urls` into a staging folder right away (an APFS clone, so even a big video
    /// costs no time or space). Everything else is sent from where it is.
    static func stageVolatile(_ urls: [URL]) -> Staged {
        let fm = FileManager.default
        var dir: URL?
        var out = Staged(files: [], temp: [], failed: [], errors: [])
        for u in urls {
            guard isVolatile(u) else { out.files.append(u); continue }
            guard fm.fileExists(atPath: u.path) else {
                out.files.append(u); out.failed.append(u); out.errors.append("\(u.lastPathComponent): already gone"); continue
            }
            if dir == nil {
                let d = stagingRoot.appendingPathComponent(UUID().uuidString, isDirectory: true)
                do { try fm.createDirectory(at: d, withIntermediateDirectories: true) } catch {
                    out.files.append(u); out.failed.append(u); out.errors.append("staging folder: \(error.localizedDescription)"); continue
                }
                dir = d
            }
            let target = uniqueName(u.lastPathComponent, in: dir!)
            do { try fm.copyItem(at: u, to: target); out.files.append(target) } catch {
                out.files.append(u); out.failed.append(u); out.errors.append("\(u.lastPathComponent): \(error.localizedDescription)")
            }
        }
        if let dir { out.temp = [dir] }
        return out
    }

    private static func uniqueName(_ name: String, in dir: URL) -> URL {
        let fm = FileManager.default
        let name = name.isEmpty ? "File" : name
        let ext = (name as NSString).pathExtension
        let base = ext.isEmpty ? name : (name as NSString).deletingPathExtension
        var target = dir.appendingPathComponent(name)
        var n = 2
        while fm.fileExists(atPath: target.path) {
            target = dir.appendingPathComponent("\(base) (\(n))" + (ext.isEmpty ? "" : ".\(ext)"))
            n += 1
        }
        return target
    }

    /// The picture on the drag pasteboard as PNG data (a screenshot thumbnail carries one next to its file), or nil.
    static func imageData(_ pb: NSPasteboard) -> Data? {
        if let png = pb.data(forType: .png), !png.isEmpty { return png }
        if let tiff = pb.data(forType: .tiff), !tiff.isEmpty, let rep = NSBitmapImageRep(data: tiff),
           let png = rep.representation(using: .png, properties: [:]) { return png }
        return nil
    }

    /// Where macOS saves screenshots and screen recordings (System Settings, or the Screenshot app's options).
    static var screenshotFolder: URL {
        let loc = UserDefaults(suiteName: "com.apple.screencapture")?.string(forKey: "location")
        let path = (loc.map { ($0 as NSString).expandingTildeInPath }) ?? ""
        return path.isEmpty ? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Desktop") : URL(fileURLWithPath: path)
    }

    /// A short-lived file that could not be copied: what is still reachable. First the picture on the pasteboard, if any;
    /// otherwise the saved file of the same name, which macOS writes a moment after a thumbnail is dropped or
    /// dismissed (waited for up to `wait` seconds, on a background thread). Returns nil if neither turns up.
    static func recover(_ bad: URL, imageData: Data?, stagingDir: inout URL?, wait: TimeInterval = 8) -> URL? {
        let fm = FileManager.default
        if let data = imageData {
            if stagingDir == nil {
                let d = stagingRoot.appendingPathComponent(UUID().uuidString, isDirectory: true)
                if (try? fm.createDirectory(at: d, withIntermediateDirectories: true)) != nil { stagingDir = d }
            }
            if let dir = stagingDir {
                var name = bad.lastPathComponent.isEmpty ? "Image.png" : bad.lastPathComponent
                if (name as NSString).pathExtension.lowercased() != "png" { name = (name as NSString).deletingPathExtension + ".png" }
                let target = uniqueName(name, in: dir)
                if (try? data.write(to: target)) != nil { return target }
            }
        }
        let saved = screenshotFolder.appendingPathComponent(bad.lastPathComponent)
        let deadline = Date().addingTimeInterval(wait)
        var lastSize: Int64 = -1
        while Date() < deadline {
            let size = (try? saved.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init) ?? -1
            if size > 0, size == lastSize { return saved }   // there, and finished growing
            lastSize = size
            Thread.sleep(forTimeInterval: 0.25)
        }
        return nil
    }

    /// Deletes staged folders a while from now (not at once: a failed card can still be retried from them).
    static func removeTempLater(_ dirs: [URL], after seconds: TimeInterval = 600) {
        guard !dirs.isEmpty else { return }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + seconds) {
            for d in dirs { try? FileManager.default.removeItem(at: d) }
        }
    }

    /// Deletes staged folders left over from earlier runs (older than a day).
    static func purgeStale() {
        DispatchQueue.global(qos: .utility).async {
            let fm = FileManager.default
            let items = (try? fm.contentsOfDirectory(at: stagingRoot, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
            let cutoff = Date().addingTimeInterval(-86_400)
            for item in items {
                let date = (try? item.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
                if (date ?? .distantPast) < cutoff { try? fm.removeItem(at: item) }
            }
        }
    }

    /// Regular files in `urls`, with folders walked recursively (hidden files and packages' insides
    /// skipped; a package such as an .app is not a single file, so it is skipped too). Symlinks are not followed.
    static func expand(_ urls: [URL], limit: Int = maxFiles) -> [URL] {
        let fm = FileManager.default
        var out: [URL] = []
        var seen = Set<String>()
        func add(_ u: URL) {
            if out.count < limit, seen.insert(u.standardizedFileURL.path).inserted { out.append(u) }
        }
        for url in urls {
            guard out.count < limit else { break }
            let v = try? url.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey, .isPackageKey])
            if v?.isRegularFile == true { add(url); continue }
            guard v?.isDirectory == true, v?.isPackage != true else { continue }
            let keys: [URLResourceKey] = [.isRegularFileKey]
            guard let e = fm.enumerator(at: url, includingPropertiesForKeys: keys,
                                        options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { continue }
            var items: [URL] = []
            for case let f as URL in e {
                if (try? f.resourceValues(forKeys: Set(keys)))?.isRegularFile == true { items.append(f) }
                if items.count >= limit { break }
            }
            // Stable order: by path inside the folder.
            for f in items.sorted(by: { $0.path.localizedStandardCompare($1.path) == .orderedAscending }) { add(f) }
        }
        return out
    }

    /// "Report.pdf", "3 items".
    static func title(names: [String]) -> String {
        names.count == 1 ? names[0] : "\(names.count) items"
    }
}

/// One drop target in the dock.
nonisolated struct DockSlot: Equatable, Sendable {
    enum Kind: Equatable, Sendable { case device(String), airWeb }
    let kind: Kind
    /// Bubble centre in dock coordinates (top-left origin, y down).
    let center: CGPoint
    let diameter: CGFloat
    var radius: CGFloat { diameter / 2 }
}

/// Deterministic geometry of the dock, shared by the view (to place bubbles) and the drop code (to hit-test),
/// so what you see is exactly what you hit.
nonisolated struct DockLayout: Equatable, Sendable {
    enum Mode: Equatable, Sendable { case none, single, several }

    static let padding: CGFloat = 14
    static let header: CGFloat = 20
    static let nameHeight: CGFloat = 16
    static let gap: CGFloat = 6

    let mode: Mode
    let size: CGSize
    let slots: [DockSlot]

    /// `devices`: ids of connected devices that take files, in display order.
    init(devices: [String], airWeb: Bool = true) {
        let p = Self.padding, top = Self.padding + Self.header + Self.gap
        var slots: [DockSlot] = []
        switch devices.count {
        case 0:
            mode = .none
            // A short hint on the left, the AirWeb bubble on the right.
            let d: CGFloat = 46, hint: CGFloat = 170
            let width = p * 2 + hint + (airWeb ? 76 : 0)
            if airWeb { slots.append(DockSlot(kind: .airWeb, center: CGPoint(x: p + hint + 38, y: top + d / 2), diameter: d)) }
            size = CGSize(width: width, height: top + d + Self.gap + Self.nameHeight + p)
        case 1:
            mode = .single
            let big: CGFloat = 76, small: CGFloat = 44
            let bigSlot: CGFloat = 132, smallSlot: CGFloat = 76
            slots.append(DockSlot(kind: .device(devices[0]), center: CGPoint(x: p + bigSlot / 2, y: top + big / 2), diameter: big))
            if airWeb {
                slots.append(DockSlot(kind: .airWeb, center: CGPoint(x: p + bigSlot + smallSlot / 2, y: top + big / 2), diameter: small))
            }
            size = CGSize(width: p * 2 + bigSlot + (airWeb ? smallSlot : 0), height: top + big + Self.gap + Self.nameHeight + p)
        default:
            mode = .several
            let d: CGFloat = 54, slot: CGFloat = 88
            let all: [DockSlot.Kind] = devices.map { .device($0) } + (airWeb ? [.airWeb] : [])
            let row = CGFloat(all.count) * slot
            let width = max(240, p * 2 + row)
            let x0 = (width - row) / 2
            for (i, k) in all.enumerated() {
                slots.append(DockSlot(kind: k, center: CGPoint(x: x0 + slot * (CGFloat(i) + 0.5), y: top + d / 2), diameter: d))
            }
            size = CGSize(width: width, height: top + d + Self.gap + Self.nameHeight + p)
        }
        self.slots = slots
    }

    /// The target under `point` (dock coordinates). A drop a little outside a bubble or on its name still
    /// counts, and between two bubbles the nearer one wins.
    func hit(_ point: CGPoint, slack: CGFloat = 18) -> DockSlot? {
        var best: (DockSlot, CGFloat)?
        for s in slots {
            var dy = point.y - s.center.y
            if dy > 0 { dy = max(0, dy - (Self.gap + Self.nameHeight)) }   // the name under the bubble
            let d = hypot(point.x - s.center.x, dy)
            guard d <= s.radius + slack else { continue }
            if best == nil || d - s.radius < best!.1 { best = (s, d - s.radius) }
        }
        return best?.0
    }

    /// How strongly the cursor pulls a bubble: 1 when over it, falling to 0 at `reach` points from its edge.
    static func pull(of slot: DockSlot, cursor: CGPoint?, reach: CGFloat = 90) -> CGFloat {
        guard let c = cursor else { return 0 }
        let edge = max(0, hypot(c.x - slot.center.x, c.y - slot.center.y) - slot.radius)
        return max(0, min(1, 1 - edge / reach))
    }

    /// Small shift of a bubble towards the cursor (the "magnet"), at most `maxShift` points.
    static func lean(of slot: DockSlot, cursor: CGPoint?, maxShift: CGFloat = 7) -> CGSize {
        guard let c = cursor else { return .zero }
        let dx = c.x - slot.center.x, dy = c.y - slot.center.y
        let dist = hypot(dx, dy)
        guard dist > 0.5 else { return .zero }
        let k = pull(of: slot, cursor: cursor) * min(1, dist / 40) * maxShift
        return CGSize(width: dx / dist * k, height: dy / dist * k)
    }
}

/// Decides when the dock should be open while a file drag is in progress.
nonisolated struct DockProximity: Sendable {
    /// How far (points) from the menu bar icon the dock opens.
    var openReach: CGFloat = 80
    /// How far outside the open dock the cursor may wander before it closes.
    var keepReach: CGFloat = 56

    /// `icon` and `dock` are screen rects (AppKit coordinates). `dock` is nil while closed.
    func shouldBeOpen(cursor: CGPoint, icon: CGRect?, dock: CGRect?) -> Bool {
        if let icon, icon.insetBy(dx: -openReach, dy: -openReach).contains(cursor) { return true }
        if let dock, dock.insetBy(dx: -keepReach, dy: -keepReach).contains(cursor) { return true }
        // The strip between the icon and the dock, so moving down into the dock never closes it.
        if let icon, let dock {
            let bridge = CGRect(x: min(icon.minX, dock.minX), y: dock.maxY - 4,
                                width: max(icon.maxX, dock.maxX) - min(icon.minX, dock.minX),
                                height: max(0, icon.minY - dock.maxY) + 8)
            if bridge.insetBy(dx: -keepReach / 2, dy: 0).contains(cursor) { return true }
        }
        return false
    }
}

/// Byte/speed/time text for transfer cards.
nonisolated enum TransferText {
    static func bytes(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }

    static func speed(_ bps: Double) -> String { bytes(Int64(bps)) + "/s" }

    /// "4 s left", "2 min left", "1 h 5 min left"; nil when unknown or under a second.
    static func timeLeft(total: Int64, done: Int64, bytesPerSec: Double) -> String? {
        guard bytesPerSec > 0, total > done else { return nil }
        let s = Double(total - done) / bytesPerSec
        guard s.isFinite, s >= 1 else { return nil }
        let secs = Int(s.rounded(.up))
        if secs < 60 { return "\(secs) s left" }
        let mins = Int((Double(secs) / 60).rounded())
        if mins < 60 { return "\(mins) min left" }
        return "\(mins / 60) h \(mins % 60) min left"
    }

    /// "Report.pdf" or "3 files".
    static func what(_ s: TransferStatus) -> String {
        s.fileCount == 1 && !s.firstName.isEmpty ? s.firstName : "\(s.fileCount) files"
    }
}
