//
//  AirBridgeApp.swift
//  AirBridge
//
//  Created by Shazan Zaidi on 29/03/26.
//

import SwiftUI
import CoreImage.CIFilterBuiltins
import UserNotifications
import AppKit

class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        
        let showAction = UNNotificationAction(identifier: "SHOW_IN_FINDER", title: "Show in Finder", options: [])
        let openAction = UNNotificationAction(identifier: "OPEN_FILE", title: "Open", options: [])
        let category = UNNotificationCategory(identifier: "FILE_TRANSFER", actions: [showAction, openAction], intentIdentifiers: [], options: [])
        
        center.setNotificationCategories([category])
        
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error = error {
                print("Notification permission error: \(error)")
            }
        }
    }
    
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        
        if let filePath = userInfo["filePath"] as? String {
            let fileURL = URL(fileURLWithPath: filePath)
            
            if response.actionIdentifier == "SHOW_IN_FINDER" {
                NSWorkspace.shared.activateFileViewerSelecting([fileURL])
            } else if response.actionIdentifier == "OPEN_FILE" || response.actionIdentifier == UNNotificationDefaultActionIdentifier {
                NSWorkspace.shared.open(fileURL)
            }
        }
        completionHandler()
    }
    
    // Forces the notification banner to show even if the app is active
        func userNotificationCenter(_ center: UNUserNotificationCenter,
                                    willPresent notification: UNNotification,
                                    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
            
            // Use .banner for macOS 11+, or .alert for older targets
            if #available(macOS 11.0, *) {
                completionHandler([.banner, .sound])
            } else {
                completionHandler([.alert, .sound])
            }
        }
}

@main
struct AirBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var server = SyncServer()

    var body: some Scene {
        MenuBarExtra("AirBridge", systemImage: "link.icloud") {
            VStack(spacing: 16) {
                Text("AirBridge Server").font(.headline)
                
                if server.connectedClients > 0 {
                    HStack {
                        Image(systemName: "bolt.badge.checkmark.fill").foregroundStyle(Color.green)
                        Text("\(server.connectedClients) Device(s) Connected")
                            .foregroundColor(.green)
                            .font(.subheadline)
                    }
                } else {
                    Text("⚠️ Waiting for connection...")
                        .foregroundColor(.orange)
                        .font(.subheadline)
                    
                    if server.port > 0, let qrImage = generateQRCode(from: "ws://\(server.localIP):\(server.port)?name=\(server.macName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")") {
                        Image(nsImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .frame(width: 160, height: 160)
                            .cornerRadius(8)
                    }
                    Text("Scan from Android App").font(.caption).foregroundColor(.secondary)
                }
                
                Divider()
                Button("Quit") { NSApplication.shared.terminate(nil) }.keyboardShortcut("q")
            }
            .padding()
            .frame(width: 250)
        }
        .menuBarExtraStyle(.window)
    }
    
    private func generateQRCode(from string: String) -> NSImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)

        if let outputImage = filter.outputImage {
            let transform = CGAffineTransform(scaleX: 10, y: 10)
            let scaledImage = outputImage.transformed(by: transform)
            if let cgimg = context.createCGImage(scaledImage, from: scaledImage.extent) {
                return NSImage(cgImage: cgimg, size: NSSize(width: 160, height: 160))
            }
        }
        return nil
    }
}
