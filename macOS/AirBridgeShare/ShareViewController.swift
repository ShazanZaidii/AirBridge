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
        try? FileManager.default.createDirectory(at: sharedDir, withIntermediateDirectories: true, attributes: nil)
        
        let group = DispatchGroup()
        var filesCopied = 0
        let lock = NSLock()
        
        for item in items {
            guard let attachments = item.attachments else { continue }
            
            for attachment in attachments {
                group.enter()
                let type = attachment.registeredTypeIdentifiers.first ?? "public.item"
                
                attachment.loadFileRepresentation(forTypeIdentifier: type) { (url, error) in
                    defer { group.leave() }
                    
                    guard let secureURL = url else { return }
                    
                    var originalName = secureURL.lastPathComponent
                    if let suggested = attachment.suggestedName, !suggested.isEmpty {
                        originalName = suggested
                        if URL(fileURLWithPath: originalName).pathExtension.isEmpty && !secureURL.pathExtension.isEmpty {
                            originalName += ".\(secureURL.pathExtension)"
                        }
                    }
                    
                    let uniqueName = "\(UUID().uuidString.prefix(6))_\(originalName)"
                    let dest = sharedDir.appendingPathComponent(uniqueName)
                    
                    do {
                        try FileManager.default.copyItem(at: secureURL, to: dest)
                        lock.lock()
                        filesCopied += 1
                        lock.unlock()
                    } catch {
                        print("Copy Failed: \(error)")
                    }
                }
            }
        }
        
        group.notify(queue: .main) {
                    if filesCopied > 0 {
                        // 1. Post local Darwin notification
                        let notificationName = CFNotificationName("com.shazan.airbridge.filesReady" as CFString)
                        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), notificationName, nil, nil, true)
                        
                        // 2. Wake the main app and WAIT for completion
                        if let url = URL(string: "airbridge://share") {
                            let config = NSWorkspace.OpenConfiguration()
                            NSWorkspace.shared.open(url, configuration: config) { (runningApp, error) in
                                if let error = error {
                                    print("AirBridge Workspace Open Error: \(error)")
                                }
                                // Close the share extension ONLY AFTER the app opens
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
