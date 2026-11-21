import Cocoa
import UniformTypeIdentifiers

/// Copies shared items into the App Group container (`Pending/<batch>/`) and wakes the main app once
/// with `airbridge://share?batch=<batch>`. Files are staged in a hidden directory and renamed into
/// place when complete, so the app never sees a half-written batch.
class ShareViewController: NSViewController {
    private static let groupId = "group.com.shazan.AirBridge"

    override func loadView() { view = NSView() }

    override func viewDidLoad() {
        super.viewDidLoad()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        guard !providers.isEmpty,
              let root = FileManager.default
                .containerURL(forSecurityApplicationGroupIdentifier: Self.groupId)?
                .appendingPathComponent("Pending", isDirectory: true) else {
            finish()
            return
        }
        let batch = UUID().uuidString
        let staging = root.appendingPathComponent(".\(batch)", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        } catch {
            finish()
            return
        }

        let group = DispatchGroup()
        let counter = Counter()
        for provider in providers {
            group.enter()
            Self.copy(provider, into: staging) { ok in
                if ok { counter.increment() }
                group.leave()
            }
        }

        group.notify(queue: .main) {
            let final = root.appendingPathComponent(batch, isDirectory: true)
            guard counter.value > 0, (try? FileManager.default.moveItem(at: staging, to: final)) != nil,
                  let url = URL(string: "airbridge://share?batch=\(batch)") else {
                try? FileManager.default.removeItem(at: staging)
                self.finish()
                return
            }
            NSWorkspace.shared.open(url, configuration: NSWorkspace.OpenConfiguration()) { _, _ in
                DispatchQueue.main.async { self.finish() }
            }
        }
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    private static func copy(_ provider: NSItemProvider, into dir: URL, done: @escaping (Bool) -> Void) {
        let fileURLType = UTType.fileURL.identifier
        if provider.hasItemConformingToTypeIdentifier(fileURLType) {
            // Prefer the original Finder URL so the real file name is kept.
            provider.loadItem(forTypeIdentifier: fileURLType, options: nil) { item, _ in
                guard let source = item as? URL ?? (item as? Data).flatMap({ URL(dataRepresentation: $0, relativeTo: nil) }) else {
                    done(false)
                    return
                }
                let scoped = source.startAccessingSecurityScopedResource()
                defer { if scoped { source.stopAccessingSecurityScopedResource() } }
                var ok = false
                var coordError: NSError?
                NSFileCoordinator().coordinate(readingItemAt: source, options: .withoutChanges, error: &coordError) { readURL in
                    ok = copyUnique(readURL, name: source.lastPathComponent, into: dir)
                }
                done(ok)
            }
        } else {
            // Items that are not files yet (web images, unsaved screenshots…).
            let type = provider.registeredTypeIdentifiers.first ?? UTType.item.identifier
            let suggested = provider.suggestedName
            provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                guard let url else { done(false); return }
                var name = url.lastPathComponent
                if let suggested, !suggested.isEmpty {
                    name = url.pathExtension.isEmpty ? suggested : "\(suggested).\(url.pathExtension)"
                }
                done(copyUnique(url, name: name, into: dir))
            }
        }
    }

    /// Copies keeping the original name, adding " (2)", " (3)"… on collisions within the batch.
    private static func copyUnique(_ source: URL, name: String, into dir: URL) -> Bool {
        let ext = (name as NSString).pathExtension
        let base = ext.isEmpty ? name : (name as NSString).deletingPathExtension
        for n in 1...999 {
            let candidate = n == 1 ? name : "\(base) (\(n))" + (ext.isEmpty ? "" : ".\(ext)")
            do {
                try FileManager.default.copyItem(at: source, to: dir.appendingPathComponent(candidate))
                return true
            } catch CocoaError.fileWriteFileExists {
                continue
            } catch {
                return false
            }
        }
        return false
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.lock(); count += 1; lock.unlock() }
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
}
