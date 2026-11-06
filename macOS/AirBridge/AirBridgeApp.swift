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

        AppModel.shared.startObservingControlStatus()
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
        #if DEBUG
        case "debug":
            // airbridge://debug/action?name=mission_control&via=coredock — test one action path locally.
            let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            if url.path == "/action", let name = q.first(where: { $0.name == "name" })?.value {
                Injector.shared.debugAction(name, via: q.first(where: { $0.name == "via" })?.value)
            }
            // airbridge://debug/accept_pairing — answer every open pairing prompt with Accept (test rigs).
            if url.path == "/accept_pairing" {
                for p in AppModel.shared.pairPrompts { AppModel.shared.respond(to: p, accept: true) }
            }
            // airbridge://debug/share_send?to=id1,id2 — the share window's Send with exactly those devices
            // picked (always name them: a real phone may be connected too).
            if url.path == "/share_send", let to = q.first(where: { $0.name == "to" })?.value {
                let wanted = Set(to.split(separator: ",").map(String.init))
                let ids = AppModel.shared.readyPeers.filter { $0.caps.contains("xfer") && wanted.contains($0.id) }.map(\.id)
                InputLog.shared.log("debug share_send: \(AppModel.shared.pendingShare.count) file(s) to \(ids.count) device(s)")
                AppModel.shared.sendPendingShare(to: ids)
                closeTargetSelection()
            }
        #endif
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

func platformName(_ plat: String) -> String {
    switch plat {
    case "android": return "Android"
    case "ios": return "iOS"
    case "macos": return "Mac"
    case "windows": return "Windows"
    case "linux": return "Linux"
    default: return plat.isEmpty ? "unknown device" : plat
    }
}

func formatBytes(_ n: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
}

/// Design tokens: neutral graphite with one calm blue accent (same as the phone app), 4/8/16 spacing, 8–12 pt radii.
/// Surfaces use system materials so the menu follows light/dark mode with native contrast.
enum Theme {
    static let accent = Color(red: 0x5B / 255, green: 0x9D / 255, blue: 0xFF / 255)
    static let destructive = Color(red: 0xEF / 255, green: 0x44 / 255, blue: 0x44 / 255)
    static let radius: CGFloat = 10
    static let xs: CGFloat = 4, sm: CGFloat = 8, md: CGFloat = 16
    static let motion = Animation.easeOut(duration: 0.18)
}

/// "483920" → "483 920".
func spacedDigits(_ c: String) -> String { c.count == 6 ? "\(c.prefix(3)) \(c.suffix(3))" : c }

/// Full-width menu row with a hover highlight.
struct MenuRowButtonStyle: ButtonStyle {
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Theme.sm)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
            .background(RoundedRectangle(cornerRadius: 6)
                .fill(Color.primary.opacity(configuration.isPressed ? 0.14 : (hovering ? 0.08 : 0))))
            .onHover { h in
                if reduceMotion { hovering = h } else { withAnimation(Theme.motion) { hovering = h } }
            }
    }
}

struct SectionHeader: View {
    let title: String
    var body: some View {
        Text(title.uppercased())
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            .tracking(0.6)
            .accessibilityAddTraits(.isHeader)
    }
}

struct MenuView: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showPairing = false
    @State private var showHosts = false
    @State private var showSettings = false
    @State private var newName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            if let status = model.controlStatus { controlBanner(status) }

            ForEach(model.pairPrompts) { PairPromptCard(prompt: $0, model: model, compact: true) }

            devicesSection

            if !model.transfers.isEmpty {
                VStack(alignment: .leading, spacing: Theme.sm) {
                    SectionHeader(title: "Transfers")
                    ForEach(model.transfers) { TransferRowView(status: $0) }
                }
            }

            if let notice = model.noticeText {
                Label(notice, systemImage: "exclamationmark.circle")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Divider()

            VStack(alignment: .leading, spacing: 2) {
                DisclosureGroup(isExpanded: $showPairing) { pairingSection } label: {
                    Label("Pair a new device", systemImage: "qrcode")
                }
                DisclosureGroup(isExpanded: $showHosts) { hostsSection } label: {
                    Label("Connect to host…", systemImage: "antenna.radiowaves.left.and.right")
                }
            }

            Divider()

            VStack(alignment: .leading, spacing: 0) {
                Button { openAndFront("arrangement", title: "AirBridge Display Arrangement") } label: {
                    Label("Display Arrangement…", systemImage: "rectangle.3.group")
                }
                Button { openAndFront("filters", title: "Clipboard Filters") } label: {
                    Label("Clipboard Filters…", systemImage: "line.3.horizontal.decrease.circle")
                }
                if !model.screenCaptureAllowed {
                    Button { model.requestScreenCapture() } label: {
                        Label("Allow screen mirroring…", systemImage: "rectangle.on.rectangle")
                    }
                    .help("Lets a phone show and control this Mac's screen. Needs the Screen Recording permission.")
                }
                DisclosureGroup(isExpanded: $showSettings) { settingsSection } label: {
                    Label("Device name", systemImage: "pencil")
                }
                .padding(.horizontal, Theme.sm)
                .padding(.vertical, 5)
                Button { NSApplication.shared.terminate(nil) } label: {
                    Label("Quit AirBridge", systemImage: "power")
                }
                .keyboardShortcut("q")
            }
            .buttonStyle(MenuRowButtonStyle())
            .padding(.horizontal, -Theme.sm)
        }
        .padding(Theme.md)
        .frame(width: 320)
        .animation(reduceMotion ? nil : Theme.motion, value: model.controlStatus)
        .animation(reduceMotion ? nil : Theme.motion, value: model.pairPrompts)
        .onAppear {
            if model.devices.isEmpty { showPairing = true }
            model.refreshScreenCapture()
        }
    }

    // MARK: Header & status

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("AirBridge").font(.headline)
                Text(model.deviceName).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if let ip = model.localIP, let port = model.port {
                Label("\(ip):\(String(port))", systemImage: "wifi")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .accessibilityLabel("Listening on \(ip) port \(String(port))")
            } else {
                Label("Starting…", systemImage: "hourglass").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private func controlBanner(_ text: String) -> some View {
        let controlled = model.controlRole == "controlled"
        return HStack(spacing: Theme.sm) {
            Image(systemName: controlled ? "hand.raised.fill" : "cursorarrow.motionlines")
                .font(.title3)
                .foregroundStyle(Theme.accent)
                .frame(width: 24)
            Text(text)
                .font(.callout.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(Theme.sm + 2)
        .background(RoundedRectangle(cornerRadius: Theme.radius).fill(Theme.accent.opacity(0.14)))
        .overlay(RoundedRectangle(cornerRadius: Theme.radius).strokeBorder(Theme.accent.opacity(0.35)))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
        .transition(.opacity)
    }

    // MARK: Devices

    private var devicesSection: some View {
        VStack(alignment: .leading, spacing: Theme.sm) {
            SectionHeader(title: "Devices")
            if model.devices.isEmpty {
                Text("No paired devices yet — pair one below.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ForEach(model.devices) { d in deviceRow(d) }
        }
    }

    private func deviceRow(_ d: DeviceRow) -> some View {
        let lease: String? = model.controlPeer == d.id
            ? (model.controlRole == "controlled" ? "Controlling this Mac" : (model.controlRole == "controlling" ? "Under control" : nil))
            : nil
        return HStack(spacing: 10) {
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: platformIcon(d.plat))
                    .font(.system(size: 18))
                    .frame(width: 28, height: 28)
                    .foregroundStyle(d.connected ? .primary : .secondary)
                Circle()
                    .fill(d.connected ? Theme.accent : Color.secondary.opacity(0.5))
                    .frame(width: 8, height: 8)
                    .overlay(Circle().strokeBorder(.background, lineWidth: 1.5))
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(d.name).lineLimit(1)
                HStack(spacing: Theme.xs) {
                    Text(d.connected ? "Connected" : (d.status ?? "Not connected"))
                        .foregroundStyle(d.connected ? Theme.accent : .secondary)
                    if let lease {
                        Text("·").foregroundStyle(.secondary)
                        Text(lease).foregroundStyle(.primary)
                    }
                }
                .font(.caption2)
                .lineLimit(1)
            }
            Spacer()
            Menu {
                if d.connected {
                    Button("Disconnect") { PeerManager.shared.disconnect(peerId: d.id) }
                } else if d.outbound {
                    Button("Connect") { PeerManager.shared.connect(peerId: d.id) }
                }
                Divider()
                Button("Forget Device", role: .destructive) { PeerManager.shared.forget(peerId: d.id) }
            } label: {
                Image(systemName: "ellipsis.circle").font(.system(size: 15))
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .accessibilityLabel("Options for \(d.name)")
        }
        .accessibilityElement(children: .contain)
    }

    // MARK: Pairing

    private var pairingSection: some View {
        VStack(spacing: Theme.sm) {
            if !model.allowPairing {
                Text("New pairings are turned off").font(.caption).foregroundStyle(.secondary)
            } else {
                if let qr = model.qrImage {
                    Image(nsImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 168, height: 168)
                        .padding(Theme.sm)
                        .background(RoundedRectangle(cornerRadius: Theme.radius).fill(.white))
                        .accessibilityLabel("Pairing QR code")
                }
                Text("Pair from your phone or PC")
                    .font(.callout.weight(.semibold))
                Text("Scan this code in the AirBridge app, or pick this Mac from its device list — both screens then show the same 6 digits to confirm.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                if model.pairingLocked {
                    Label("Too many failed attempts — pairing paused for a minute", systemImage: "lock")
                        .font(.caption).foregroundStyle(.orange)
                }
            }
            Toggle("Allow new pairings", isOn: Binding(
                get: { model.allowPairing },
                set: { Pairing.shared.setAllowPairing($0) }))
                .toggleStyle(.switch).controlSize(.small)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Theme.sm)
    }

    private var hostsSection: some View {
        VStack(alignment: .leading, spacing: Theme.sm) {
            if model.hosts.isEmpty {
                HStack(spacing: Theme.sm) {
                    ProgressView().controlSize(.small)
                    Text("Looking for AirBridge computers…").font(.caption).foregroundStyle(.secondary)
                }
            }
            ForEach(model.hosts) { h in
                HStack(spacing: 10) {
                    Image(systemName: platformIcon(h.plat)).frame(width: 28)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(h.name).lineLimit(1)
                        Text(h.status ?? platformName(h.plat)).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                    }
                    Spacer()
                    Button("Pair") { PeerManager.shared.pairConfirm(hostId: h.id) }
                        .controlSize(.small)
                        .disabled(h.status != nil && model.pairPrompts.contains { !$0.incoming && $0.peerName == h.name })
                        .accessibilityLabel("Pair with \(h.name)")
                }
            }
        }
        .padding(.vertical, Theme.xs)
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
        .padding(.top, Theme.xs)
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

// MARK: - Confirm-mode pairing prompt

/// Accept/Reject (incoming) or Confirm/Cancel (outgoing) card. Used in the menu and in the floating panel.
struct PairPromptCard: View {
    let prompt: PairPrompt
    @ObservedObject var model: AppModel
    var compact = false

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? Theme.sm : 12) {
            HStack(spacing: Theme.sm) {
                Image(systemName: platformIcon(prompt.peerPlat))
                    .font(.system(size: compact ? 16 : 22))
                    .foregroundStyle(Theme.accent)
                    .frame(width: compact ? 22 : 30)
                Text(title)
                    .font(compact ? .callout.weight(.semibold) : .headline)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(spacedDigits(prompt.digits))
                .font(.system(size: compact ? 26 : 34, weight: .semibold, design: .monospaced))
                .frame(maxWidth: .infinity)
                .textSelection(.enabled)
                .accessibilityLabel("Code \(prompt.digits.map(String.init).joined(separator: " "))")
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    let left = max(0, Int(prompt.expires.timeIntervalSince(ctx.date).rounded()))
                    Text("\(left)s").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        .accessibilityLabel("\(left) seconds left")
                }
                Spacer()
                if prompt.waiting {
                    ProgressView().controlSize(.small)
                    Button("Cancel") { model.respond(to: prompt, accept: false) }
                } else {
                    Button(prompt.incoming ? "Reject" : "Cancel") { model.respond(to: prompt, accept: false) }
                        .keyboardShortcut(compact ? nil : .cancelAction)
                    Button(prompt.incoming ? "Accept" : "Confirm") { model.respond(to: prompt, accept: true) }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.accent)
                        .keyboardShortcut(compact ? nil : .defaultAction)
                }
            }
            .controlSize(compact ? .small : .regular)
        }
        .padding(compact ? Theme.sm + 2 : Theme.md)
        .background {
            if compact {
                RoundedRectangle(cornerRadius: Theme.radius).fill(.quaternary.opacity(0.6))
            }
        }
        .accessibilityElement(children: .contain)
    }

    private var title: String {
        if prompt.incoming { return "\(prompt.peerName) (\(platformName(prompt.peerPlat))) wants to pair" }
        return prompt.waiting ? "Waiting for \(prompt.peerName) to accept…" : "Pair with \(prompt.peerName)?"
    }

    private var subtitle: String {
        if prompt.incoming { return "Accept only if \(prompt.peerName) shows the same code." }
        return prompt.waiting ? "Accept the pairing on \(prompt.peerName)." : "Confirm only if \(prompt.peerName) shows the same code."
    }
}

/// Floating, non-activating panels for pairing prompts: they appear above other apps without stealing focus.
@MainActor final class PairPromptPanels {
    static let shared = PairPromptPanels()
    private var panels: [String: NSPanel] = [:]

    func show(_ p: PairPrompt) {
        if let panel = panels[p.id] {
            (panel.contentViewController as? NSHostingController<PairPromptPanelView>)?.rootView =
                PairPromptPanelView(prompt: p, model: AppModel.shared)
            return
        }
        let host = NSHostingController(rootView: PairPromptPanelView(prompt: p, model: AppModel.shared))
        host.sizingOptions = [.preferredContentSize]
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 340, height: 220),
                            styleMask: [.nonactivatingPanel, .titled, .fullSizeContentView, .utilityWindow],
                            backing: .buffered, defer: false)
        panel.contentViewController = host
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.level = .floating
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.standardWindowButton(.closeButton)?.isHidden = true
        panel.standardWindowButton(.miniaturizeButton)?.isHidden = true
        panel.standardWindowButton(.zoomButton)?.isHidden = true
        panel.layoutIfNeeded()
        if let screen = NSScreen.main {
            let v = screen.visibleFrame
            let size = panel.frame.size
            let offset = CGFloat(panels.count) * 24
            panel.setFrameOrigin(NSPoint(x: v.maxX - size.width - 16 - offset, y: v.maxY - size.height - 16 - offset))
        }
        panels[p.id] = panel
        panel.orderFrontRegardless()
        NSAccessibility.post(element: panel, notification: .windowCreated)
    }

    func dismiss(_ id: String) {
        panels.removeValue(forKey: id)?.close()
    }
}

struct PairPromptPanelView: View {
    let prompt: PairPrompt
    @ObservedObject var model: AppModel

    var body: some View {
        PairPromptCard(prompt: prompt, model: model)
            .frame(width: 340)
            .background(.regularMaterial)
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
    /// Empty = normal mode (a click sends). Press and hold (or ⌘-click) a device to pick several.
    @State private var chosen: Set<String> = []

    private var targets: [PeerInfo] { model.readyPeers.filter { $0.caps.contains("xfer") } }
    private var picking: Bool { !chosen.isEmpty }

    var body: some View {
        VStack(spacing: 16) {
            Text("Send to Device").font(.title3).fontWeight(.bold)
            Text(picking ? "\(chosen.count) selected" : "\(model.pendingShare.count) file(s) ready to send")
                .font(.subheadline).foregroundStyle(.secondary)

            if targets.isEmpty {
                Text("No devices connected.").foregroundStyle(.secondary).padding()
            } else {
                List(targets) { peer in
                    let on = chosen.contains(peer.id)
                    HStack {
                        Image(systemName: platformIcon(peer.platform))
                        Text(peer.name)
                        Spacer()
                        if picking {
                            Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(on ? Color.primary : Color.secondary)
                        } else {
                            Image(systemName: "paperplane.fill").foregroundStyle(.secondary)
                        }
                    }
                    .padding(8)
                    .contentShape(Rectangle())
                    .onLongPressGesture(minimumDuration: 0.45) { toggle(peer.id) }
                    .highPriorityGesture(TapGesture().modifiers(.command).onEnded { toggle(peer.id) })
                    .onTapGesture { if picking { toggle(peer.id) } else { send([peer.id]) } }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(picking && on ? [.isButton, .isSelected] : .isButton)
                    .accessibilityAction { if picking { toggle(peer.id) } else { send([peer.id]) } }
                    .accessibilityAction(named: "Choose several") { toggle(peer.id) }
                }
                .frame(height: 150)
                .cornerRadius(8)
            }

            HStack {
                if !picking, targets.count > 1 {
                    Text("Hold or ⌘-click to choose several").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Cancel") {
                    model.cancelPendingShare()
                    AppDelegate.shared?.closeTargetSelection()
                }
                .keyboardShortcut(.cancelAction)
                if picking {
                    Button(chosen.count > 1 ? "Send to \(chosen.count)" : "Send") { send(Array(chosen)) }
                        .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding()
        .frame(width: 350)
        .onChange(of: targets.map(\.id)) { chosen.formIntersection(targets.map(\.id)) }
    }

    private func toggle(_ id: String) {
        if chosen.contains(id) { chosen.remove(id) } else { chosen.insert(id) }
    }

    private func send(_ ids: [String]) {
        let live = targets.map(\.id).filter(ids.contains)
        guard !live.isEmpty else { return }
        model.sendPendingShare(to: live)
        chosen = []
        AppDelegate.shared?.closeTargetSelection()
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
