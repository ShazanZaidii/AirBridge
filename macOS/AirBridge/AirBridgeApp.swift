import SwiftUI
import CoreImage.CIFilterBuiltins
import UserNotifications
import AppKit
import Network

enum FilterType: String, Codable, CaseIterable {
    case startsWith = "Starts With"
    case endsWith = "Ends With"
    case contains = "Contains"
}

struct ClipboardFilter: Codable, Identifiable, Hashable {
    var id = UUID()
    var type: FilterType
    var keyword: String
}

class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    static var shared: AppDelegate? // ADD THIS
    var targetWindowController: NSWindowController?
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        AppDelegate.shared = self // ADD THIS
        
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
        
        // Listen for the URL Scheme from the Share Extension
        NSAppleEventManager.shared().setEventHandler(
            self,
            andSelector: #selector(handleURLEvent(_:withReplyEvent:)),
            forEventClass: AEEventClass(kInternetEventClass),
            andEventID: AEEventID(kAEGetURL)
        )
    }
    
    @objc func handleURLEvent(_ event: NSAppleEventDescriptor, withReplyEvent replyEvent: NSAppleEventDescriptor) {
        if let urlString = event.paramDescriptor(forKeyword: AEKeyword(keyDirectObject))?.stringValue,
           urlString.hasPrefix("airbridge://") {
            
            // Give the extension 0.5s to finish writing to the disk before scanning
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                NotificationCenter.default.post(name: Notification.Name("AirBridgeFilesReady"), object: nil)
            }
        }
    }
    
    func showTargetSelection(server: SyncServer) {
            if targetWindowController == nil {
                let view = TargetSelectionView(server: server)
                let host = NSHostingController(rootView: view)
                let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 350, height: 300),
                                      styleMask: [.titled, .closable],
                                      backing: .buffered, defer: false)
                window.center()
                window.title = "Select Device to Send"
                window.isReleasedWhenClosed = false
                
                // FORCE the window to float above all other apps
                window.level = .floating
                
                // ADD THIS LINE to inject the SwiftUI view into the native window
                window.contentViewController = host
                
                targetWindowController = NSWindowController(window: window)
            }
            
            // Aggressively steal focus from the OS
            NSApp.activate(ignoringOtherApps: true)
            targetWindowController?.showWindow(nil)
            targetWindowController?.window?.makeKeyAndOrderFront(nil)
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

    private func bringWindowToFront(_ title: String) {
        NSApp.activate(ignoringOtherApps: true)
        if let window = NSApplication.shared.windows.first(where: { $0.title == title }) {
            window.collectionBehavior = [.moveToActiveSpace]
            window.makeKeyAndOrderFront(nil)
            window.orderFrontRegardless()
        }
    }

    var body: some Scene {
        MenuBarExtra("AirBridge", image: "MenuBarIcon") {
            VStack(spacing: 16) {
                Text("AirBridge Server").font(.headline)
                
                if !server.activeConnectionsInfo.isEmpty {
                    Menu {
                        ForEach(server.activeConnectionsInfo, id: \.id) { conn in
                            Button("Disconnect \(conn.name)") {
                                server.disconnect(id: conn.id)
                            }
                        }
                    } label: {
                        Text("\(server.activeConnectionsInfo.count) Device(s) Connected")
                            .foregroundColor(.green)
                    }
                } else {
                    Text("Waiting for connection...")
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
                            Text("Sending to Device").font(.caption).bold()
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
                
                Divider()
                
                Menu("Connect to Host...") {
                    if server.discoveredHosts.isEmpty {
                        Text("Searching for devices...")
                            .foregroundColor(.gray)
                    } else {
                        ForEach(server.discoveredHosts, id: \.endpoint) { host in
                            if case let .service(name, _, _, _) = host.endpoint {
                                Button(name) {
                                    server.connectToOutbound(endpoint: host.endpoint, name: name)
                                }
                            }
                        }
                    }
                }
                
                Button("Display Arrangement...") {
                    openWindow(id: "arrangement")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        bringWindowToFront("AirBridge Display Arrangement")
                    }
                }
                
                Button("Clipboard Filters...") {
                    openWindow(id: "filters")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        bringWindowToFront("Clipboard Filters")
                    }
                }
                
                Divider()
                Button("Quit") {
                    server.stopServer()
                    NSApplication.shared.terminate(nil)
                }.keyboardShortcut("q")
            }
            .padding()
            .frame(width: 250)
//            .onAppear {
//                InputInterceptor.shared.setup(server: server)
//                InputInterceptor.shared.start()
//            }
        }
        .menuBarExtraStyle(.window)
        
        Window("AirBridge Display Arrangement", id: "arrangement") {
            DisplayArrangementView()
                .fixedSize()
        }
        .windowResizability(.contentSize)
        
        Window("Clipboard Filters", id: "filters") {
            ClipboardFiltersView()
                .frame(minWidth: 400, minHeight: 300)
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

struct TargetSelectionView: View {
    @ObservedObject var server: SyncServer

    var body: some View {
        VStack(spacing: 16) {
            Text("AirDrop to Device")
                .font(.title3)
                .fontWeight(.bold)
            
            if let urls = server.pendingTransferURLs {
                Text("\(urls.count) file(s) ready to send")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            if server.activeConnectionsInfo.isEmpty {
                Text("No devices connected.")
                    .foregroundColor(.red)
                    .padding()
            } else {
                List(server.activeConnectionsInfo, id: \.id) { conn in
                    Button(action: {
                        if let urls = server.pendingTransferURLs {
                            server.sendFilesToTarget(urls: urls, to: conn.id)
                            server.pendingTransferURLs = nil
                        }
                        NSApp.keyWindow?.close()
                    }) {
                        HStack {
                            Image(systemName: "laptopcomputer.and.iphone")
                            Text(conn.name)
                                .font(.body)
                            Spacer()
                            Image(systemName: "paperplane.fill")
                                .foregroundColor(.blue)
                        }
                        .padding(8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PlainButtonStyle())
                }
                .frame(height: 150)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)
            }
            
            HStack {
                Spacer()
                Button("Cancel") {
                    if let urls = server.pendingTransferURLs {
                        for url in urls { try? FileManager.default.removeItem(at: url) }
                    }
                    server.pendingTransferURLs = nil
                    NSApp.keyWindow?.close()
                }
                .keyboardShortcut(.cancelAction)
            }
        }
        .padding()
        .frame(width: 350)
    }
}

struct ClipboardFiltersView: View {
    @AppStorage("ClipboardFilters") private var filtersData: Data = Data()
    @State private var filters: [ClipboardFilter] = []
    
    @State private var newKeyword: String = ""
    @State private var selectedType: FilterType = .startsWith
    
    var body: some View {
        VStack(spacing: 16) {
            Text("Ignore copied text matching these rules:")
                .font(.headline)
            
            List {
                ForEach(filters) { filter in
                    HStack {
                        Text(filter.type.rawValue)
                            .fontWeight(.semibold)
                            .frame(width: 100, alignment: .leading)
                        Text("\"\(filter.keyword)\"")
                        Spacer()
                        Button("Delete") {
                            filters.removeAll { $0.id == filter.id }
                            saveFilters()
                        }
                    }
                }
            }
            .border(Color.gray.opacity(0.2))
            
            HStack {
                Picker("", selection: $selectedType) {
                    ForEach(FilterType.allCases, id: \.self) { type in
                        Text(type.rawValue).tag(type)
                    }
                }
                .frame(width: 120)
                
                TextField("Keyword", text: $newKeyword)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                
                Button("Add") {
                    guard !newKeyword.isEmpty else { return }
                    filters.append(ClipboardFilter(type: selectedType, keyword: newKeyword))
                    newKeyword = ""
                    saveFilters()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding()
        .onAppear {
            if let decoded = try? JSONDecoder().decode([ClipboardFilter].self, from: filtersData) {
                self.filters = decoded
            }
        }
    }
    
    private func saveFilters() {
        if let encoded = try? JSONEncoder().encode(filters) {
            filtersData = encoded
        }
    }
}
