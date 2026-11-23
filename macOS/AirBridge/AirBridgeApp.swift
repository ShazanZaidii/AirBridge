import CoreImage
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
        AppWindows.shared.start()
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let show = UNNotificationAction(identifier: "SHOW_IN_FINDER", title: "Show in Finder", options: [])
        let open = UNNotificationAction(identifier: "OPEN_FILE", title: "Open", options: [])
        center.setNotificationCategories(Set([
            UNNotificationCategory(identifier: "FILE_TRANSFER", actions: [show, open], intentIdentifiers: [], options: []),
        ] + PhoneNotifCenter.categories))
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }

        AppModel.shared.startObservingControlStatus()
        PeerManager.shared.start()
        ClipboardSync.shared.start()
        ShareInbox.purgeStale()
        DropItems.purgeStale()
        // The device pictures in the Batteries widget come from types AirBridge declares: make sure the system knows them.
        LSRegisterURL(Bundle.main.bundleURL as CFURL, false)
        // Finder's right-click menu: "Send with AirBridge" (Services). The file goes through the same dock as a drop.
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        InputInterceptor.shared.start()
        DropDock.shared.start()
        PhoneNotifCenter.shared.start()
        LocalScreenTime.shared.start() // this Mac's screen time for the AirBridge widget
        ScreenTimeHub.shared.reloadAfterUpdate()
        NotificationCenterWatch.shared.start() // the widget refreshes when Notification Center opens
        DesktopWidgetWatch.shared.start() // and when widgets on the desktop come into view
        LoginItem.applyDefault()
    }

    func applicationWillTerminate(_ notification: Notification) {
        AirWebWindow.shared.shutdown()
        DevicePowerSources.shared.removeAll()
        PeerManager.shared.shutdown()
    }

    /// The "Send with AirBridge" service: the chosen files, handled like a drop on the menu bar icon.
    @objc func sendWithAirBridge(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        guard !DropItems.fileURLs(pboard).isEmpty else {
            error.pointee = "Choose files or folders to send." as NSString
            return
        }
        _ = DropDock.shared.dropOnIcon(pboard)
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
        case "screentime":
            // A click on the AirBridge Screen Time widget.
            ScreenTimeWindow.shared.show(device: STLink.device(in: url))
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
            // airbridge://debug/mirror?to=ID — open the phone mirror window for that phone (test rigs).
            // airbridge://debug/mirror_tap?to=ID&x=0.5&y=0.5 — a click at that spot of the mirrored phone.
            // airbridge://debug/drop_send?to=ID&file=/path — what a drop on the dock does with one device
            //   (undo card, then the transfer card).
            let to = q.first(where: { $0.name == "to" })?.value
            let peer = AppModel.shared.readyPeers.first { $0.id == to }
            if url.path == "/mirror", let peer { PhoneMirrorWindows.shared.show(peer) }
            if url.path == "/mirror_tap", let peer,
               let x = Double(q.first(where: { $0.name == "x" })?.value ?? ""), let y = Double(q.first(where: { $0.name == "y" })?.value ?? "") {
                PeerBus.shared.send(to: peer.id, ["t": "ptr_abs", "x": x, "y": y])
                PeerBus.shared.send(to: peer.id, ["t": "ptr_btn", "b": 0, "down": true])
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                    PeerBus.shared.send(to: peer.id, ["t": "ptr_btn", "b": 0, "down": false])
                }
            }
            // airbridge://debug/mirror_snapshot?out=/path.png — save the frame the mirror window shows last.
            if url.path == "/mirror_snapshot", let out = q.first(where: { $0.name == "out" })?.value,
               let pb = MirrorRenderer.lastFrame {
                let shownAt = MirrorRenderer.lastFrameAt
                try? String(format: "%.0f", shownAt * 1000).write(toFile: out + ".txt", atomically: true, encoding: .utf8)
                let ci = CIImage(cvPixelBuffer: pb)
                if let cg = CIContext().createCGImage(ci, from: ci.extent),
                   let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) {
                    try? png.write(to: URL(fileURLWithPath: out))
                }
            }
            // airbridge://debug/render_menu?out=/path.png — the menu bar window as an image (layout checks).
            if url.path == "/render_menu", let out = q.first(where: { $0.name == "out" })?.value {
                let open = q.first(where: { $0.name == "settings" })?.value == "1"
                let host = NSHostingView(rootView: MenuView(model: AppModel.shared, startWithSettingsOpen: open).background(Color(nsColor: .windowBackgroundColor)))
                host.appearance = NSAppearance(named: .darkAqua)
                let w = NSWindow(contentRect: NSRect(x: -4000, y: 0, width: 320, height: 900), styleMask: [.borderless], backing: .buffered, defer: false)
                w.contentView = host
                host.frame.size = host.fittingSize
                w.setContentSize(host.fittingSize)
                w.orderFrontRegardless()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    host.layoutSubtreeIfNeeded()
                    if let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) {
                        host.cacheDisplay(in: host.bounds, to: rep)
                        if let png = rep.representation(using: .png, properties: [:]) { try? png.write(to: URL(fileURLWithPath: out)) }
                    }
                    w.orderOut(nil)
                }
            }
            // airbridge://debug/notif_dump?out=/path.txt — phone notifications the Mac holds now (test rigs).
            if url.path == "/notif_dump", let out = q.first(where: { $0.name == "out" })?.value {
                let lines = PhoneNotifCenter.shared.store.items.values.sorted { $0.ts < $1.ts }
                    .map { "\($0.app) | \($0.title) | \($0.text) | reply=\($0.canReply) | msgs=\($0.msgs.count)" }
                let calls = PhoneNotifCenter.shared.calls.values.map { "CALL \($0.state) | \($0.name ?? "-") | \($0.number ?? "-") | muted=\($0.muted) speaker=\($0.speaker) | can=\($0.can.sorted()) | route=\($0.route ?? "-") routes=\($0.routes.map { "\($0.id)\($0.computer ? "*" : "")" })" }
                try? (lines + calls).joined(separator: "\n").write(toFile: out, atomically: true, encoding: .utf8)
            }
            // airbridge://debug/call?op=answer|decline|end — the call panel's buttons for the current call.
            if url.path == "/call", let op = q.first(where: { $0.name == "op" })?.value,
               let c = PhoneNotifCenter.shared.calls.values.first {
                PhoneNotifCenter.shared.callAction(c, op)
            }
            // airbridge://debug/close_windows — ⌘W (performClose) on every open app window.
            if url.path == "/close_windows" {
                for w in NSApp.windows where AppWindows.isAppWindow(w) { w.performClose(nil) }
            }
            // airbridge://debug/fake_call?state=ringing|active|ended&name=Asha&label=Mobile — the call UI without a phone.
            if url.path == "/fake_call" { CallPanels.debugFakeCall(q) }
            // airbridge://debug/airweb?out=/path — opens AirWeb and writes its local link (127.0.0.1) to that file.
            if url.path == "/airweb", let out = q.first(where: { $0.name == "out" })?.value {
                AirWebWindow.shared.show()
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    guard let sess = AirWebWindow.shared.session, sess.port != 0 else { return }
                    try? "http://127.0.0.1:\(sess.port)/?t=\(sess.token)".write(toFile: out, atomically: true, encoding: .utf8)
                }
            }
            // airbridge://debug/airweb_newlink?out=/path — a new link on purpose; writes the new local link to that file.
            if url.path == "/airweb_newlink", let sess = AirWebWindow.shared.session {
                sess.newLink()
                if let out = q.first(where: { $0.name == "out" })?.value {
                    try? "http://127.0.0.1:\(sess.port)/?t=\(sess.token)".write(toFile: out, atomically: true, encoding: .utf8)
                }
            }
            // airbridge://debug/airweb_close — closes only the window (AirWeb keeps running).
            if url.path == "/airweb_close" { AirWebWindow.shared.debugCloseWindow() }
            // airbridge://debug/menu_snapshot?tab=phone — the menu panel with Settings open, drawn into ScreenTime/debug-menu.png.
            if url.path == "/menu_snapshot" {
                if let tab = q.first(where: { $0.name == "tab" })?.value { UserDefaults.standard.set(tab, forKey: "MenuSettingsTab") }
                let view = MenuView(model: AppModel.shared, startWithSettingsOpen: true, flat: true)
                    .background(Color(nsColor: .windowBackgroundColor)).environment(\.colorScheme, .dark)
                let r = ImageRenderer(content: view)
                r.scale = 1
                if let cg = r.cgImage, let folder = ScreenTimeShared.folder() {
                    try? NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:])?.write(to: folder.appendingPathComponent("debug-menu.png"))
                }
            }
            if url.path == "/screentime_close" { ScreenTimeWindow.shared.debugClose() }
            // airbridge://debug/notif_sample — sample notifications in the list; …/notif_snapshot[?light=1] — the list drawn into
            // ScreenTime/debug-notifs.png in the shared folder.
            if url.path == "/notif_sample" { PhoneNotifCenter.shared.debugInjectSamples() }
            if url.path == "/notif_open" { PhoneNotifsWindow.shared.show() }
            if url.path == "/notif_close" { PhoneNotifsWindow.shared.debugClose() }
            // …/notif_block_sample blocks the sample Instagram one; …/notif_unblock_samples removes sample blocks again.
            if url.path == "/notif_block_sample", let n = PhoneNotifCenter.shared.store.items.values.first(where: { $0.key == "s3" }) {
                PhoneNotifCenter.shared.block(n)
            }
            if url.path == "/notif_unblock_samples" {
                for b in PhoneNotifCenter.shared.blocks where b.pkg == "com.instagram.android" && b.title == "zoya.k" { PhoneNotifCenter.shared.unblock(b) }
            }
            if url.path == "/notif_snapshot" {
                let view = PhoneNotifsView(center: PhoneNotifCenter.shared, model: AppModel.shared, flat: true)
                    .frame(width: 420).environment(\.colorScheme, q.contains { $0.name == "light" } ? .light : .dark)
                let r = ImageRenderer(content: view)
                r.scale = 1
                if let cg = r.cgImage, let folder = ScreenTimeShared.folder() {
                    try? NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:])?.write(to: folder.appendingPathComponent("debug-notifs.png"))
                }
            }
            if url.path == "/screentime_snapshot" {
                ScreenTimeWindow.shared.debugSnapshot(range: q.first(where: { $0.name == "range" })?.value,
                                                      filter: q.first(where: { $0.name == "filter" })?.value,
                                                      expand: q.contains { $0.name == "expand" },
                                                      dark: q.first(where: { $0.name == "light" }) == nil,
                                                      back: Int(q.first(where: { $0.name == "back" })?.value ?? "") ?? 0)
            }
            // airbridge://debug/airweb_stop — closes AirWeb.
            if url.path == "/airweb_stop" { AirWebWindow.shared.stop() }
            // airbridge://debug/pro_setup?to=ID — the Pro Mirroring setup window.
            if url.path == "/pro_setup", let peer { ProMirrorSetupWindow.shared.show(peerId: peer.id, name: peer.name) }
            // airbridge://debug/phone_route?dev=speaker — the Audio menu's pick of one of the phone's routes.
            if url.path == "/phone_route", let dev = q.first(where: { $0.name == "dev" })?.value {
                CallAudio.shared.selectPhoneRoute(dev)
            }
            if url.path == "/drop_send", let peer, let f = q.first(where: { $0.name == "file" })?.value {
                TransferHUD.shared.queueSend([URL(fileURLWithPath: f)], to: peer)
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
        // Phone notifications (§13): reply, open on phone, clear (synced to the phone), click = the list.
        if let id = response.notification.request.content.userInfo[PhoneNotifCenter.userInfoKey] as? String {
            let action = response.actionIdentifier
            let text = (response as? UNTextInputNotificationResponse)?.userText
            DispatchQueue.main.async {
                MainActor.assumeIsolated { PhoneNotifCenter.shared.handleResponse(id: id, action: action, text: text) }
            }
            completionHandler()
            return
        }
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
        // Phone notifications stay in Notification Center after the banner, so clearing them there syncs back.
        if notification.request.content.userInfo[PhoneNotifCenter.userInfoKey] != nil {
            completionHandler([.banner, .list, .sound])
            return
        }
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

/// A row that opens and closes what is under it: the whole row is the button, not just the chevron
/// (SwiftUI's `DisclosureGroup` in a menu bar window only reacts to a tap on its small chevron).
struct ExpandRow<Label: View, Content: View>: View {
    @Binding var isExpanded: Bool
    var highlight = true
    /// The highlight reaches the menu's edges (a row inside padded content).
    var bleed = false
    @ViewBuilder var label: () -> Label
    @ViewBuilder var content: () -> Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                if reduceMotion { isExpanded.toggle() } else { withAnimation(Theme.motion) { isExpanded.toggle() } }
            } label: {
                HStack(spacing: 6) {
                    label()
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                }
            }
            .buttonStyle(ExpandRowStyle(highlight: highlight))
            .padding(.horizontal, bleed ? -Theme.sm : 0)
            .accessibilityAddTraits(.isHeader)
            .accessibilityValue(isExpanded ? "Expanded" : "Collapsed")
            if isExpanded {
                content()
                    .transition(.opacity)
            }
        }
    }
}

/// Full-width hover highlight for [ExpandRow] (the plain-row look of [MenuRowButtonStyle], without its padding
/// when the row sits in a card that already has some).
struct ExpandRowStyle: ButtonStyle {
    var highlight = true
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Theme.sm)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
            .background(RoundedRectangle(cornerRadius: 6)
                .fill(Color.primary.opacity(highlight ? (configuration.isPressed ? 0.14 : (hovering ? 0.08 : 0)) : 0)))
            .onHover { h in
                if reduceMotion { hovering = h } else { withAnimation(Theme.motion) { hovering = h } }
            }
    }
}

/// A square, quiet action button for the menu's action bar.
struct ActionTile: View {
    let title: String
    let icon: String
    @State private var hovering = false
    var body: some View {
        VStack(spacing: 5) {
            Image(systemName: icon).font(.system(size: 17, weight: .regular)).frame(height: 20)
            Text(title).font(.caption).lineLimit(1).minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 9).fill(Color.primary.opacity(hovering ? 0.10 : 0.06)))
        .contentShape(RoundedRectangle(cornerRadius: 9))
        .onHover { hovering = $0 }
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
    /// Test rigs only (airbridge://debug/render_menu?settings=1): start with Settings open.
    var startWithSettingsOpen = false
    /// Debug snapshots only: draw the panel without its scroll view, Settings open.
    var flat = false
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showPairing = false
    @State private var showMirrorPick = false
    @State private var showHosts = false
    @State private var showSettings = false
    @AppStorage(DropSettings.dockEnabledKey) private var dockEnabled = true
    @AppStorage(DropSettings.autoHideKey) private var autoHideCards = false
    @ObservedObject private var phoneNotifs = PhoneNotifCenter.shared
    @ObservedObject private var airWeb = AirWebWindow.shared
    @State private var renaming = false
    @State private var draftName = ""
    @AppStorage(PhoneNotifSettings.showNotifsKey) private var showPhoneNotifs = true
    @AppStorage(PhoneNotifSettings.showCallsKey) private var showPhoneCalls = true
    @AppStorage(PhoneNotifSettings.soundsKey) private var phoneSounds = true
    @AppStorage(DevicePowerSources.showKey) private var showPhoneBattery = true
    @AppStorage(ScreenTimeHub.trackKey) private var trackScreenTime = true
    /// Which Settings tab is showing (remembered).
    @AppStorage("MenuSettingsTab") private var settingsTab = SettingsTab.general.rawValue
    /// The panel's natural height, so it can scroll instead of running off a short screen.
    @State private var contentHeight: CGFloat = 0

    var body: some View {
        // The panel sizes to its content, but never taller than the screen: past that it scrolls.
        let limit = max(320, (NSScreen.main?.visibleFrame.height ?? 900) - 24)
        if flat {
            panel
        } else {
        ScrollView(.vertical) {
            panel
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollIndicators(contentHeight > limit ? .automatic : .hidden)
        .frame(width: 320, height: contentHeight > 0 ? min(contentHeight, limit) : nil)
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            renameRow

            if let session = airWeb.session { AirWebRunningRow(session: session) }

            ActiveCallsMenuSection()

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

            // Pairing opens in place (from "Add device" or when nothing is paired yet).
            if showPairing {
                VStack(alignment: .leading, spacing: Theme.sm) {
                    HStack {
                        SectionHeader(title: "Add a device")
                        Spacer()
                        Button { showPairing = false } label: { Image(systemName: "xmark").font(.caption) }
                            .buttonStyle(.borderless).accessibilityLabel("Close")
                    }
                    pairingSection
                    ExpandRow(isExpanded: $showHosts, bleed: true) {
                        Text("Connect by address").font(.callout)
                    } content: {
                        hostsSection
                    }
                }
            }

            actionBar

            HStack(spacing: 6) {
                Button { openAndFront("arrangement", title: "AirBridge Display Arrangement") } label: {
                    ToolPill(title: "Displays", icon: "rectangle.3.group")
                }
                .help("Arrange where your devices sit around this screen")
                Button { openAndFront("filters", title: "Clipboard Filters") } label: {
                    ToolPill(title: "Clipboard", icon: "line.3.horizontal.decrease.circle")
                }
                .help("Clipboard filters: choose what clipboard content is shared")
                Button { ScreenTimeWindow.shared.show() } label: {
                    ToolPill(title: "Screen Time", icon: "hourglass")
                }
                .help("Screen time on every device, day by day")
            }
            .buttonStyle(.plain)

            if showMirrorPick && connectedPhones.count > 1 {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(connectedPhones) { p in
                        Button { showMirrorPick = false; mirror(p) } label: { Label(p.name, systemImage: "iphone.gen3") }
                    }
                }
                .buttonStyle(MenuRowButtonStyle())
                .padding(.horizontal, -Theme.sm)
            }

            Divider()

            VStack(alignment: .leading, spacing: 0) {
                ExpandRow(isExpanded: Binding(get: { showSettings || flat }, set: { showSettings = $0 })) {
                    Label("Settings", systemImage: "gearshape")
                } content: {
                    settingsList.padding(.horizontal, Theme.sm)
                }
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
            ScreenTimeHub.shared.pullNow() // someone opened the menu: fresh screen time for the widget
            if model.devices.isEmpty { showPairing = true }
            if startWithSettingsOpen { showSettings = true }
            model.refreshScreenCapture()
        }
    }

    /// Connected Android phones (whether or not their AirBridge can show its screen yet).
    private var connectedPhones: [PeerInfo] { model.readyPeers.filter { $0.platform == "android" } }

    /// Mirror [p], or explain why it can't be mirrored yet (an older AirBridge on the phone).
    private func mirror(_ p: PeerInfo) {
        if PhoneMirrorHub.canMirror(p) || ProMirrorHub.isSetUp(p.id) { PhoneMirrorWindows.shared.show(p) }
        else { model.notice("Update AirBridge on \(p.name) to mirror its screen") }
    }

    /// The three things people do most, one tap each.
    private var actionBar: some View {
        HStack(spacing: Theme.sm) {
            let phones = connectedPhones
            Button {
                if phones.count == 1, let p = phones.first { mirror(p) } else { showMirrorPick.toggle() }
            } label: { ActionTile(title: "Mirror phone", icon: "iphone.gen3") }
                .buttonStyle(.plain)
                .disabled(phones.isEmpty)
                .opacity(phones.isEmpty ? 0.45 : 1)
                .help(phones.isEmpty ? "Connect a phone to mirror it" : "Show your phone's screen in a window")
            Button { AirWebWindow.shared.show() } label: { ActionTile(title: "AirWeb", icon: "qrcode.viewfinder") }
                .buttonStyle(.plain)
                .help("Send files to a phone or computer without the app")
            // Phone notifications (§13): only once a phone that can send them is connected or some are listed.
            if !phoneNotifs.sourcePhones.isEmpty || phoneNotifs.store.count > 0 {
                Button { PhoneNotifsWindow.shared.show() } label: {
                    ActionTile(title: "Notifications", icon: phoneNotifs.unseen > 0 ? "bell.badge" : "bell")
                        .overlay(alignment: .topTrailing) {
                            if phoneNotifs.unseen > 0 {
                                Text(phoneNotifs.unseen > 99 ? "99+" : "\(phoneNotifs.unseen)")
                                    .font(.system(size: 9, weight: .semibold).monospacedDigit())
                                    .padding(.horizontal, 5).padding(.vertical, 1)
                                    .background(Capsule().fill(Color.primary.opacity(0.75)))
                                    .foregroundStyle(Color(nsColor: .windowBackgroundColor))
                                    .padding(5)
                                    .transition(.scale.combined(with: .opacity))
                                    .accessibilityLabel("\(phoneNotifs.unseen) new")
                            }
                        }
                }
                .buttonStyle(.plain)
                .help("Your phone's notifications")
                .transition(.opacity)
            }
            Button { showPairing.toggle() } label: { ActionTile(title: "Add device", icon: "plus") }
                .buttonStyle(.plain)
        }
        .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.8), value: phoneNotifs.unseen)
    }

    /// Everything that's set once and rarely touched again, in three short tabs so the panel stays on screen.
    private var settingsList: some View {
        let tab = SettingsTab(rawValue: settingsTab) ?? .general
        return VStack(alignment: .leading, spacing: 0) {
            SettingsTabs(selection: $settingsTab)
                .padding(.top, Theme.sm)
                .padding(.bottom, Theme.xs)
            switch tab {
            case .general:
                dropSettingsSection
                if !model.screenCaptureAllowed {
                    Button { model.requestScreenCapture() } label: {
                        Label("Let phones show this Mac's screen…", systemImage: "rectangle.on.rectangle")
                    }
                }
            case .phone: phoneSettingsSection
            case .screenTime: screenTimeSettingsSection
            }
        }
        .buttonStyle(MenuRowButtonStyle())
    }

    /// Connected phones that can show their screen here (§8.7, phone as source).
    private var mirrorablePhones: [PeerInfo] { model.readyPeers.filter(PhoneMirrorHub.canMirror) }

    // MARK: Header & status

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("AirBridge").font(.headline)
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

    /// This Mac's name as other devices see it: the whole row is the button, a pencil says so.
    @ViewBuilder private var renameRow: some View {
        if renaming {
            HStack(spacing: Theme.sm) {
                TextField("Name", text: $draftName)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(commitRename)
                Button("Save", action: commitRename)
                    .controlSize(.small)
                    .disabled(draftName.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("Cancel") { renaming = false }
                    .controlSize(.small)
            }
        } else {
            Button {
                draftName = model.deviceName
                renaming = true
            } label: {
                HStack(spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Color.primary.opacity(0.07))
                        Image(systemName: "laptopcomputer").font(.system(size: 12.5, weight: .medium)).foregroundStyle(.secondary)
                    }
                    .frame(width: 26, height: 26)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(model.deviceName).font(.callout).lineLimit(1)
                        Text("How your other devices see this Mac").font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "pencil").font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: Theme.radius).fill(Color.primary.opacity(0.05)))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Rename this Mac")
            .accessibilityLabel("This Mac is called \(model.deviceName). Rename")
        }
    }

    private func commitRename() {
        let n = draftName.trimmingCharacters(in: .whitespaces)
        if !n.isEmpty, n != model.deviceName { PeerManager.shared.rename(to: n) }
        renaming = false
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
                Text("No devices yet. Use Add device below.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ForEach(model.devices.sorted { $0.connected && !$1.connected }) { d in deviceRow(d) }
            if let offer = model.staleOffer { staleBanner(offer) }
        }
    }

    /// Entries left behind by earlier installs of a device that is connected now: the user decides, nothing is guessed.
    private func staleBanner(_ offer: StaleOffer) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(offer.ids.count == 1
                 ? "1 older entry named “\(offer.name)” may be an earlier install of this device."
                 : "\(offer.ids.count) older entries named “\(offer.name)” may be earlier installs of this device.")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Button("Remove old") { PeerManager.shared.resolveStale(offer, remove: true) }
                Button("Keep") { PeerManager.shared.resolveStale(offer, remove: false) }
            }
            .controlSize(.small)
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Theme.radius).fill(Color.primary.opacity(0.05)))
        .accessibilityElement(children: .contain)
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
                if let phone = connectedPhones.first(where: { $0.id == d.id }) {
                    Button("Mirror Screen") { mirror(phone) }
                    Button(ProMirrorHub.isSetUp(phone.id) ? "Pro Mirroring…" : "Set Up Pro Mirroring…") {
                        ProMirrorSetupWindow.shared.show(peerId: phone.id, name: phone.name)
                    }
                    Divider()
                }
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

    private var dropSettingsSection: some View {
        VStack(alignment: .leading, spacing: Theme.xs) {
            SettingToggleRow(icon: "tray.and.arrow.down", title: "Drop dock",
                             hint: "Drag files to the menu bar icon to send them", isOn: $dockEnabled)
            SettingToggleRow(icon: "clock.arrow.circlepath", title: "Tidy up finished transfers",
                             hint: "Hide their cards after 5 seconds", isOn: $autoHideCards)
                .onChange(of: autoHideCards) { TransferHUD.shared.settingsChanged() }
            SettingToggleRow(icon: "power", title: "Open at login", hint: "Phones reconnect and screen time counts from the start",
                             isOn: Binding(get: { LoginItem.isOn }, set: { LoginItem.set($0) }))
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.top, Theme.xs)
    }

    /// Phone notifications and calls (§13). The phone has its own switches; these decide what this Mac shows.
    private var phoneSettingsSection: some View {
        VStack(alignment: .leading, spacing: Theme.xs) {
            SettingToggleRow(icon: "bell.badge", title: "Phone notifications", hint: "Show them on this Mac", isOn: $showPhoneNotifs)
            SettingToggleRow(icon: "phone", title: "Phone calls", hint: "See and answer calls here", isOn: $showPhoneCalls)
            RingModeSetting().disabled(!showPhoneCalls).opacity(showPhoneCalls ? 1 : 0.45)
            SettingToggleRow(icon: "speaker.wave.2", title: "Sounds", hint: "Play a sound for phone notifications",
                             isOn: $phoneSounds, enabled: showPhoneNotifs || showPhoneCalls)
            SettingToggleRow(icon: "battery.75percent", title: "Phone battery", hint: "List it in the Batteries widget",
                             isOn: $showPhoneBattery)
                .onChange(of: showPhoneBattery) { _, _ in DevicePowerSources.shared.refresh() }
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.top, Theme.xs)
    }

    /// This Mac's screen time for the AirBridge widget and page.
    private var screenTimeSettingsSection: some View {
        VStack(alignment: .leading, spacing: Theme.xs) {
            SettingToggleRow(icon: "hourglass", title: "This Mac's screen time", hint: "Count it for the AirBridge widget",
                             isOn: $trackScreenTime)
                .onChange(of: trackScreenTime) { _, _ in LocalScreenTime.shared.settingChanged() }
            SettingActionRow(icon: "clock.arrow.circlepath", title: "This Mac's past screen time",
                             hint: LocalScreenTime.shared.historyAvailable ? "Read from macOS's own records" : "Needs Full Disk Access to fill in the whole day",
                             action: LocalScreenTime.shared.historyAvailable ? "On" : "Turn on") {
                if !LocalScreenTime.shared.historyAvailable,
                   let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles") {
                    NSWorkspace.shared.open(url)
                }
                LocalScreenTime.shared.refreshHistory(force: true)
            }
            SettingActionRow(icon: "chart.bar.xaxis", title: "Screen Time", hint: "Every device, day by day", action: "Open") {
                ScreenTimeWindow.shared.show()
            }
        }
        .fixedSize(horizontal: false, vertical: true)
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
            (panel.contentViewController as? NSHostingController<AnyView>)?.rootView =
                FitWindow.wrap(PairPromptPanelView(prompt: p, model: AppModel.shared), width: 340) { [weak self] in self?.panels[p.id] }
            return
        }
        let host = FitWindow.host(PairPromptPanelView(prompt: p, model: AppModel.shared), width: 340) { [weak self] in self?.panels[p.id] }
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
                if status.phase == .active || status.phase == .waiting {
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
                Text("No devices connected.").foregroundStyle(.secondary)
            }
            List {
                ForEach(targets) { peer in
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
                airWebRow
            }
            .frame(height: targets.isEmpty ? 68 : 200)
            .cornerRadius(8)

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

    /// Offers the files on a web page for a device without the app (QR code + link).
    private var airWebRow: some View {
        HStack {
            Image(systemName: "qrcode.viewfinder")
            VStack(alignment: .leading, spacing: 1) {
                Text("AirWeb")
                Text("Send to a phone or computer without the app")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(.secondary)
        }
        .padding(8)
        .contentShape(Rectangle())
        .onTapGesture { openAirWeb() }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { openAirWeb() }
    }

    private func openAirWeb() {
        chosen = []
        let (files, batches) = model.takePendingShare()
        AppDelegate.shared?.closeTargetSelection()
        AirWebWindow.shared.show(offering: files, batches: batches)
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


/// AirWeb keeps running when its window is closed: say so on the main menu, and let it be stopped from there too.
struct AirWebRunningRow: View {
    @ObservedObject var session: AirWebSession

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "qrcode.viewfinder").font(.system(size: 14)).foregroundStyle(.secondary).frame(width: 26)
            VStack(alignment: .leading, spacing: 1) {
                Text("AirWeb is running").font(.callout)
                Text(session.peers.isEmpty ? "No one connected yet" : "\(session.peers.count) connected")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Button("Open") { AirWebWindow.shared.show() }.controlSize(.small)
            Button("Stop") { AirWebWindow.shared.stop() }.controlSize(.small)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: Theme.radius).fill(Color.primary.opacity(0.05)))
        .accessibilityElement(children: .contain)
    }
}
