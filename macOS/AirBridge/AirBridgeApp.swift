//
//  AirBridgeApp.swift
//  AirBridge
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
    
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        
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
    @Environment(\.openWindow) private var openWindow

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
                }
                
                if server.isReceivingBatch {
                    VStack(spacing: 4) {
                        ProgressView(value: server.batchProgress)
                            .progressViewStyle(LinearProgressViewStyle(tint: .blue))
                        Text("\(server.batchStatus) • \(server.batchSpeed)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(server.batchETA)
                            .font(.caption2)
                            .foregroundColor(.gray)
                    }
                    .padding(.top, 8)
                }
                
                if server.isSendingBatch || !server.sendStatus.isEmpty {
                                    VStack(spacing: 4) {
                                        HStack {
                                            Text("Sending to Phone").font(.caption).bold()
                                            Spacer()
                                        }
                                        if server.isSendingBatch {
                                            ProgressView(value: server.sendProgress)
                                                .progressViewStyle(LinearProgressViewStyle(tint: .green))
                                        }
                                        Text(server.sendStatus)
                                            .font(.caption2)
                                            .foregroundColor(.gray)
                                    }
                                    .padding(.top, 8)
                                }
                
//                                Button("Force Check Pending Shares") {
//                                    server.processPendingTransfers()
//                                }
//                                .disabled(server.connectedClients == 0)

                                
                
                if server.port > 0, let qrImage = generateQRCode(from: "ws://\(server.localIP):\(server.port)?name=\(server.macName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")") {
                    Image(nsImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 160, height: 160)
                        .cornerRadius(8)
                    if(server.connectedClients >= 1){
                        Text("Scan to add more devices").font(.caption).foregroundColor(.secondary)
                    }
                    else{
                        Text("Scan to add device").font(.caption).foregroundColor(.secondary)
                    }
                }
                
                Button("Send File to Phone...") {
                    let panel = NSOpenPanel()
                    panel.allowsMultipleSelection = true
                    panel.canChooseDirectories = false
                    panel.canChooseFiles = true
                    NSApp.activate(ignoringOtherApps: true)
                    
                    if panel.runModal() == .OK {
                        server.sendFiles(urls: panel.urls)
                    }
                }
                .disabled(server.connectedClients == 0)
                
                Button("Display Arrangement...") {
                    openWindow(id: "arrangement")
                }
                
                Divider()
                Button("Quit") { NSApplication.shared.terminate(nil) }.keyboardShortcut("q")
            }
            .padding()
            .frame(width: 250)
            .onAppear {
                InputInterceptor.shared.setup(server: server)
                InputInterceptor.shared.start()
            }
        }
        .menuBarExtraStyle(.window)
        
        Window("AirBridge Display Arrangement", id: "arrangement") {
            DisplayArrangementView()
                .fixedSize()
        }
        .windowResizability(.contentSize)
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
