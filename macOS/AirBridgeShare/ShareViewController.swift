import Cocoa
import UniformTypeIdentifiers

class ShareViewController: NSViewController {
    override func loadView() { self.view = NSView() }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }
        
        let docsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let sharedDir = docsURL.appendingPathComponent("AirBridgePending")
        
        try? FileManager.default.removeItem(at: sharedDir)
        try? FileManager.default.createDirectory(at: sharedDir, withIntermediateDirectories: true, attributes: nil)
        
        let group = DispatchGroup()
        var filesCopied = 0
        let lock = NSLock()
        
        for item in items {
            guard let attachments = item.attachments else { continue }
            
            for attachment in attachments {
                group.enter()
                
                let fileURLType = UTType.fileURL.identifier
                
                // 1. PRIMARY STRATEGY: Intercept the raw Finder URL before macOS sandboxes and renames it
                if attachment.hasItemConformingToTypeIdentifier(fileURLType) {
                    attachment.loadItem(forTypeIdentifier: fileURLType, options: nil) { (item, error) in
                        defer { group.leave() }
                        
                        // macOS can return the URL directly, or as Data
                        guard let originalURL = item as? URL ?? (item as? Data).flatMap({ URL(dataRepresentation: $0, relativeTo: nil) }) else { return }
                        
                        // We now have the TRUE original filename!
                        let realName = originalURL.lastPathComponent
                        let uniqueName = "\(UUID().uuidString.prefix(6))_\(realName)"
                        let dest = sharedDir.appendingPathComponent(uniqueName)
                        
                        // Because this is a raw Finder URL outside our sandbox, we must use Security Scoping to read it
                        let access = originalURL.startAccessingSecurityScopedResource()
                        
                        var coordinateError: NSError?
                        NSFileCoordinator().coordinate(readingItemAt: originalURL, options: .withoutChanges, error: &coordinateError) { readURL in
                            do {
                                try FileManager.default.copyItem(at: readURL, to: dest)
                                lock.lock()
                                filesCopied += 1
                                lock.unlock()
                            } catch {
                                print("AirBridge Copy Failed: \(error)")
                            }
                        }
                        
                        if access { originalURL.stopAccessingSecurityScopedResource() }
                    }
                }
                // 2. FALLBACK STRATEGY: For "files" that don't exist on disk yet (e.g., sharing a web image or unsaved screenshot)
                else {
                    let type = attachment.registeredTypeIdentifiers.first ?? "public.item"
                    attachment.loadFileRepresentation(forTypeIdentifier: type) { (url, error) in
                        defer { group.leave() }
                        guard let secureURL = url else { return }
                        
                        let originalName = secureURL.lastPathComponent
                        let uniqueName = "\(UUID().uuidString.prefix(6))_\(originalName)"
                        let dest = sharedDir.appendingPathComponent(uniqueName)
                        
                        do {
                            try FileManager.default.copyItem(at: secureURL, to: dest)
                            lock.lock()
                            filesCopied += 1
                            lock.unlock()
                        } catch {
                            print("AirBridge Fallback Copy Failed: \(error)")
                        }
                    }
                }
            }
        }
        
        group.notify(queue: .main) {
            if filesCopied > 0 {
                let notificationName = CFNotificationName("com.shazan.airbridge.filesReady" as CFString)
                CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), notificationName, nil, nil, true)
                
                if let url = URL(string: "airbridge://share") {
                    let config = NSWorkspace.OpenConfiguration()
                    NSWorkspace.shared.open(url, configuration: config) { (runningApp, error) in
                        DispatchQueue.main.async {
                            self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
                        }
                    }
                } else {
                    self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
                }
            } else {
                self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            }
        }
    }
}
