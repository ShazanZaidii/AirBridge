import SwiftUI
import UserNotifications
import AppKit

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
    static var shared: AppDelegate?
    private var targetWindowController: NSWindowController?

    func applicationWillFinishLaunching(_ notification: Notification) {
        AppDelegate.shared = self
        // Registered before launch completes so the URL that launched us is not missed.
        NSAppleEventManager.shared().setEventHandler(
            self, andSelector: #selector(handleURLEvent(_:withReplyEvent:)),
            forEventClass: AEEventClass(kInternetEventClass), andEventID: AEEventID(kAEGetURL))
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let show = UNNotificationAction(identifier: "SHOW_IN_FINDER", title: "Show in Finder", options: [])
        let open = UNNotificationAction(identifier: "OPEN_FILE", title: "Open", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "FILE_TRANSFER", actions: [show, open], intentIdentifiers: [], options: []),
        ])
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }

        PeerManager.shared.start()
        ClipboardSync.shared.start()
        ShareInbox.purgeStale()
        InputInterceptor.shared.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        PeerManager.shared.shutdown()
    }

    @objc func handleURLEvent(_ event: NSAppleEventDescriptor, withReplyEvent replyEvent: NSAppleEventDescriptor) {
        guard let s = event.paramDescriptor(forKeyword: AEKeyword(keyDirectObject))?.stringValue,
              let url = URL(string: s), url.scheme == "airbridge" else { return }
        switch url.host {
        case "share":
            let batch = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "batch" }?.value
            ShareInbox.open(batch: batch)
        case "pair":
            PeerManager.shared.pair(link: url)
        default:
            break
        }
    }

    func showTargetSelection() {
        if targetWindowController == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 350, height: 300),
                                  styleMask: [.titled, .closable], backing: .buffered, defer: false)
            window.title = "Send with AirBridge"
            window.isReleasedWhenClosed = false
            window.level = .floating
            window.contentViewController = NSHostingController(rootView: TargetSelectionView(model: AppModel.shared))
            window.center()
            targetWindowController = NSWindowController(window: window)
        }
        NSApp.activate(ignoringOtherApps: true)
        targetWindowController?.showWindow(nil)
        targetWindowController?.window?.makeKeyAndOrderFront(nil)
    }

    func closeTargetSelection() { targetWindowController?.window?.close() }

    var isTargetSelectionVisible: Bool { targetWindowController?.window?.isVisible == true }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let paths = response.notification.request.content.userInfo["filePaths"] as? [String] ?? []
        let urls = paths.map { URL(fileURLWithPath: $0) }
        if response.actionIdentifier == "SHOW_IN_FINDER" {
            NSWorkspace.shared.activateFileViewerSelecting(urls)
        } else if response.actionIdentifier == "OPEN_FILE" || response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            if urls.count == 1 { NSWorkspace.shared.open(urls[0]) } else { NSWorkspace.shared.activateFileViewerSelecting(urls) }
        }
        completionHandler()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

@main
struct AirBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var model = AppModel.shared

    var body: some Scene {
        MenuBarExtra("AirBridge", image: "MenuBarIcon") {
            MenuView(model: model)
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
}

func platformIcon(_ plat: String) -> String {
    switch plat {
    case "android", "ios": return "iphone"
    case "macos": return "laptopcomputer"
    case "windows", "linux": return "desktopcomputer"
    default: return "questionmark.circle"
    }
}

func formatBytes(_ n: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
}

struct MenuView: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @State private var showPairing = false
    @State private var showHosts = false
    @State private var showSettings = false
    @State private var codeHost: String?
    @State private var code = ""
    @State private var newName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("AirBridge").font(.headline)
                Spacer()
                if let ip = model.localIP, let port = model.port {
                    Text("\(ip):\(String(port))").font(.caption2).foregroundStyle(.secondary)
                } else {
                    Text("Starting…").font(.caption2).foregroundStyle(.secondary)
                }
            }

            devicesSection

            if !model.transfers.isEmpty {
                Divider()
                ForEach(model.transfers) { TransferRowView(status: $0) }
            }

            Divider()

            DisclosureGroup("Pair a new device", isExpanded: $showPairing) { pairingSection }
            DisclosureGroup("Connect to host…", isExpanded: $showHosts) { hostsSection }
            DisclosureGroup("Settings", isExpanded: $showSettings) { settingsSection }

            if let notice = model.noticeText {
                Text(notice).font(.caption).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
            }

            Divider()

            Button("Display Arrangement…") { openAndFront("arrangement", title: "AirBridge Display Arrangement") }
                .buttonStyle(.plain)
            Button("Clipboard Filters…") { openAndFront("filters", title: "Clipboard Filters") }
                .buttonStyle(.plain)
            Button("Quit AirBridge") { NSApplication.shared.terminate(nil) }
                .buttonStyle(.plain)
                .keyboardShortcut("q")
        }
        .padding()
        .frame(width: 300)
        .onAppear { if model.devices.isEmpty { showPairing = true } }
    }

    private var devicesSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Devices").font(.subheadline).foregroundStyle(.secondary)
            if model.devices.isEmpty {
                Text("No paired devices yet").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(model.devices) { d in
                HStack(spacing: 8) {
                    Image(systemName: platformIcon(d.plat)).frame(width: 18)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(d.name).lineLimit(1)
                        Text(d.connected ? "Connected" : (d.status ?? "Not connected"))
                            .font(.caption2).foregroundStyle(d.connected ? .green : .secondary)
                    }
                    Spacer()
                    Circle().fill(d.connected ? Color.green : Color.gray.opacity(0.4)).frame(width: 8, height: 8)
                    Menu {
                        if d.connected {
                            Button("Disconnect") { PeerManager.shared.disconnect(peerId: d.id) }
                        } else if d.outbound {
                            Button("Connect") { PeerManager.shared.connect(peerId: d.id) }
                        }
                        Button("Forget Device", role: .destructive) { PeerManager.shared.forget(peerId: d.id) }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                }
            }
        }
    }

    private var pairingSection: some View {
        VStack(spacing: 8) {
            if !model.allowPairing {
                Text("New pairings are turned off").font(.caption).foregroundStyle(.secondary)
            } else {
                if let qr = model.qrImage {
                    Image(nsImage: qr).interpolation(.none).resizable().frame(width: 160, height: 160).cornerRadius(6)
                    Text("Scan with the AirBridge app").font(.caption).foregroundStyle(.secondary)
                }
                Text(spacedCode(model.pairingCode))
                    .font(.system(size: 24, weight: .semibold, design: .monospaced))
                    .textSelection(.enabled)
                Text("Or enter this code on the other device").font(.caption).foregroundStyle(.secondary)
                if model.pairingLocked {
                    Text("Too many wrong codes — pairing paused for a minute").font(.caption).foregroundStyle(.orange)
                }
            }
            Toggle("Allow new pairings", isOn: Binding(
                get: { model.allowPairing },
                set: { Pairing.shared.setAllowPairing($0) }))
                .toggleStyle(.switch).controlSize(.small)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 4)
    }

    private var hostsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            if model.hosts.isEmpty {
                Text("Searching for devices…").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(model.hosts) { h in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Image(systemName: platformIcon(h.plat)).frame(width: 18)
                        Text(h.name).lineLimit(1)
                        Spacer()
                        if codeHost != h.id {
                            Button("Pair…") { codeHost = h.id; code = "" }.controlSize(.small)
                        }
                    }
                    if let status = h.status {
                        Text(status).font(.caption2).foregroundStyle(.secondary)
                    }
                    if codeHost == h.id {
                        HStack {
                            TextField("6-digit code", text: $code)
                                .textFieldStyle(.roundedBorder)
                                .onSubmit { submitCode(h.id) }
                            Button("Pair") { submitCode(h.id) }
                                .disabled(code.filter(\.isNumber).count != 6)
                            Button("Cancel") { codeHost = nil }
                        }
                        .controlSize(.small)
                    }
                }
            }
        }
        .padding(.top, 4)
    }

    private var settingsSection: some View {
        HStack {
            TextField("Device name", text: $newName)
                .textFieldStyle(.roundedBorder)
                .onAppear { newName = model.deviceName }
                .onSubmit { PeerManager.shared.rename(to: newName) }
            Button("Rename") { PeerManager.shared.rename(to: newName) }
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty || newName == model.deviceName)
        }
        .controlSize(.small)
        .padding(.top, 4)
    }

    private func submitCode(_ hostId: String) {
        let digits = code.filter(\.isNumber)
        guard digits.count == 6 else { return }
        PeerManager.shared.pair(hostId: hostId, code: digits)
        codeHost = nil
        code = ""
    }

    private func spacedCode(_ c: String) -> String {
        c.count == 6 ? "\(c.prefix(3)) \(c.suffix(3))" : c
    }

    private func openAndFront(_ id: String, title: String) {
        openWindow(id: id)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            NSApp.activate(ignoringOtherApps: true)
            if let window = NSApplication.shared.windows.first(where: { $0.title == title }) {
                window.collectionBehavior = [.moveToActiveSpace]
                window.makeKeyAndOrderFront(nil)
                window.orderFrontRegardless()
            }
        }
    }
}

struct TransferRowView: View {
    let status: TransferStatus

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Image(systemName: status.direction == .incoming ? "arrow.down.circle" : "arrow.up.circle")
                Text(title).font(.caption).lineLimit(1)
                Spacer()
                if status.direction == .outgoing, status.phase == .active || status.phase == .waiting {
                    Button { PeerManager.shared.cancelTransfer(key: status.id) } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .buttonStyle(.plain)
                }
            }
            if status.phase == .active || status.phase == .waiting {
                ProgressView(value: status.total > 0 ? Double(status.done) / Double(status.total) : 0)
                    .progressViewStyle(.linear)
            }
            Text(detail).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private var title: String {
        let what = status.fileCount == 1 ? status.firstName : "\(status.fileCount) files"
        return status.direction == .incoming ? "\(what) from \(status.peerName)" : "\(what) to \(status.peerName)"
    }

    private var detail: String {
        switch status.phase {
        case .waiting: return "Waiting for \(status.peerName)…"
        case .done: return "Done — \(formatBytes(status.total))"
        case .failed(let why): return why
        case .active:
            var s = "\(formatBytes(status.done)) of \(formatBytes(status.total))"
            if status.bytesPerSec > 0 {
                s += String(format: " • %.1f MB/s", status.bytesPerSec / 1_048_576)
                let remaining = Double(status.total - status.done) / status.bytesPerSec
                if remaining.isFinite, remaining >= 1 { s += " • \(Int(remaining))s left" }
            }
            return s
        }
    }
}

struct TargetSelectionView: View {
    @ObservedObject var model: AppModel

    private var targets: [PeerInfo] { model.readyPeers.filter { $0.caps.contains("xfer") } }

    var body: some View {
        VStack(spacing: 16) {
            Text("Send to Device").font(.title3).fontWeight(.bold)
            Text("\(model.pendingShare.count) file(s) ready to send").font(.subheadline).foregroundStyle(.secondary)

            if targets.isEmpty {
                Text("No devices connected.").foregroundStyle(.red).padding()
            } else {
                List(targets) { peer in
                    Button {
                        model.sendPendingShare(to: peer.id)
                        AppDelegate.shared?.closeTargetSelection()
                    } label: {
                        HStack {
                            Image(systemName: platformIcon(peer.platform))
                            Text(peer.name)
                            Spacer()
                            Image(systemName: "paperplane.fill").foregroundStyle(.blue)
                        }
                        .padding(8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .frame(height: 150)
                .cornerRadius(8)
            }

            HStack {
                Spacer()
                Button("Cancel") {
                    model.cancelPendingShare()
                    AppDelegate.shared?.closeTargetSelection()
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
