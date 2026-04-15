//
//  ShareViewController.swift
//  AirBridgeShare
//
//  Created by Shazan Zaidi on 10/04/26.
//


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
                
                // Grab whatever the first registered type is (e.g., "public.image", "public.data")
                let type = attachment.registeredTypeIdentifiers.first ?? "public.item"
                
                // Force macOS to resolve it into a file URL
                                attachment.loadFileRepresentation(forTypeIdentifier: type) { (url, error) in
                                    defer { group.leave() }
                                    
                                    guard let secureURL = url else {
                                        print("File representation failed: \(String(describing: error))")
                                        return
                                    }
                                    
                                    var originalName = secureURL.lastPathComponent
                                    if let suggested = attachment.suggestedName, !suggested.isEmpty {
                                        originalName = suggested
                                        
                                        // Safety check: If the suggested name somehow lacks an extension, append the correct one
                                        if URL(fileURLWithPath: originalName).pathExtension.isEmpty && !secureURL.pathExtension.isEmpty {
                                            originalName += ".\(secureURL.pathExtension)"
                                        }
                                    }
                                    
                                    // USE the originalName variable here!
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
                let notificationName = CFNotificationName("com.shazan.airbridge.filesReady" as CFString)
                CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), notificationName, nil, nil, true)
            }
            self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
    }
}
