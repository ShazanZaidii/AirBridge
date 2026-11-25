import AppKit
import ApplicationServices
import Combine
import CoreBluetooth
import Intents
import SwiftUI
import UserNotifications

/// First-run setup (issue #15), like Android's "Get AirBridge ready": a few skippable slides about what AirBridge does,
/// then one full step per permission, each saying what it is for and what stops working without it, with a button to the
/// exact Settings page and a live tick once it is granted. Ends with pairing a phone. Opens by itself on the first
/// launch and again from Settings › "Set up AirBridge…".
@MainActor final class SetupGuideWindow: NSObject, NSWindowDelegate {
    static let shared = SetupGuideWindow()
    static let shownKey = "AirBridge.setupGuideShown"
    private var window: NSWindow?

    /// First launch only.
    func showIfFirstRun() {
        guard !UserDefaults.standard.bool(forKey: Self.shownKey) else { return }
        UserDefaults.standard.set(true, forKey: Self.shownKey)
        show()
    }

    func show() {
        AppWindows.closeMenuPanel()
        if NSApp.activationPolicy() != .regular { NSApp.setActivationPolicy(.regular) }
        if window == nil {
            let host = NSHostingView(rootView: SetupGuideView(model: SetupModel(), close: { [weak self] in self?.window?.performClose(nil) }))
            host.sizingOptions = []
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 760, height: 560),
                             styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
            w.title = "Set Up AirBridge"
            w.titleVisibility = .hidden
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.backgroundColor = NSColor(Ink.page)
            w.contentView = host
            w.delegate = self
            w.center()
            window = w
        }
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        window = nil
    }
}

/// One permission or setting, with its live state.
struct SetupStep: Identifiable {
    enum State: Equatable { case done, todo, unknown }
    let id: String
    let symbol: String
    let title: String
    let why: String
    let without: String
    let action: String
    let state: () -> State
    let run: () -> Void
}

@MainActor final class SetupModel: ObservableObject {
    @Published var page = 0
    @Published var tick = 0
    private var timer: Timer?
    private var notifAllowed = false
    private var shareOn = false
    private var bluetooth: CBCentralManager?

    static let slides: [(String, String, String)] = [
        ("rectangle.on.rectangle", "Your Mac and phone, one desk", "Copy on one, paste on the other. Drop files across at full Wi-Fi speed. No cables, no accounts."),
        ("cursorarrow.motionlines", "One keyboard and mouse", "Push the cursor off the edge of the screen and it carries on on your phone, typing included."),
        ("phone.and.waveform", "Calls and notifications", "Your phone's notifications and calls show up here, to answer, reply or clear."),
        ("iphone.gen3", "Your phone in a window", "Mirror the phone, use AirWeb for any browser, and see everything at a glance in the widgets."),
    ]

    var steps: [SetupStep] {
        [
            SetupStep(id: "ax", symbol: "keyboard", title: "Accessibility",
                      why: "Lets AirBridge move the cursor and type on your phone, and take the keyboard and mouse back.",
                      without: "The cursor stops at the screen edge; keyboard and mouse sharing don't work.",
                      action: "Open Accessibility Settings",
                      state: { AXIsProcessTrusted() ? .done : .todo },
                      run: {
                          _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)
                          Self.open("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
                      }),
            SetupStep(id: "screen", symbol: "rectangle.dashed.badge.record", title: "Screen Recording",
                      why: "Lets this Mac show its screen on your phone.",
                      without: "Your phone can't show or control this Mac's screen. Mirroring the phone here still works.",
                      action: "Allow Screen Recording",
                      state: { CGPreflightScreenCaptureAccess() ? .done : .todo },
                      run: {
                          if !CGRequestScreenCaptureAccess() { Self.open("x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") }
                      }),
            SetupStep(id: "notif", symbol: "bell.badge", title: "Notifications",
                      why: "Shows your phone's notifications and calls, and finished transfers, as banners on this Mac.",
                      without: "Nothing pops up; phone notifications are only in the Notifications list.",
                      action: "Allow Notifications",
                      state: { [weak self] in self?.notifAllowed == true ? .done : .todo },
                      run: { [weak self] in
                          UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { ok, _ in
                              if !ok { DispatchQueue.main.async { Self.open("x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=com.shazan.AirBridge") } }
                              DispatchQueue.main.async { self?.refreshAsync() }
                          }
                      }),
            SetupStep(id: "bt", symbol: "dot.radiowaves.left.and.right", title: "Bluetooth",
                      why: "Tells whether your phone is next to this Mac, so a call rings where you are.",
                      without: "Calls ring on both, or by the setting you choose, instead of where you are.",
                      action: "Allow Bluetooth",
                      state: { CBManager.authorization == .allowedAlways ? .done : .todo },
                      run: { [weak self] in
                          if CBManager.authorization == .notDetermined { self?.bluetooth = CBCentralManager(delegate: nil, queue: .main) }
                          else { Self.open("x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth") }
                      }),
            SetupStep(id: "focus", symbol: "moon", title: "Focus",
                      why: "During a Focus or DND, phone calls still show on this Mac but don't ring.",
                      without: "Calls ring on this Mac even during a Focus or DND.",
                      action: "Allow Focus Status",
                      state: {
                          switch INFocusStatusCenter.default.authorizationStatus {
                          case .authorized: return .done
                          default: return .todo
                          }
                      },
                      run: {
                          if INFocusStatusCenter.default.authorizationStatus == .notDetermined { INFocusStatusCenter.default.requestAuthorization { _ in } }
                          else { Self.open("x-apple.systempreferences:com.apple.preference.security?Privacy_Focus") }
                      }),
            SetupStep(id: "fda", symbol: "externaldrive", title: "Full Disk Access",
                      why: "Reads this Mac's own app usage history, so the Screen Time widget has the whole day, not just since AirBridge started.",
                      without: "The Screen Time widget only counts from when AirBridge was opened.",
                      action: "Open Full Disk Access Settings",
                      state: { MacUsageHistory.canRead() ? .done : .todo },
                      run: { Self.open("x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles") }),
            SetupStep(id: "net", symbol: "network", title: "Local Network",
                      why: "Finds your phone and other computers on this Wi-Fi and connects to them.",
                      without: "No device can connect. If macOS asked and you chose Don't Allow, turn AirBridge on here.",
                      action: "Open Local Network Settings",
                      state: { AppModel.shared.readyPeers.isEmpty ? .unknown : .done },
                      run: { Self.open("x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") }),
            SetupStep(id: "share", symbol: "square.and.arrow.up", title: "Share menu",
                      why: "Puts AirBridge in the Share menu, so anything you can share goes straight to your phone.",
                      without: "Files go across by drag and drop or the menu bar only.",
                      action: "Open Extensions Settings",
                      state: { [weak self] in self?.shareOn == true ? .done : .todo },
                      run: { Self.open("x-apple.systempreferences:com.apple.ExtensionsPreferences?extensionPointIdentifier=com.apple.share-services") }),
            SetupStep(id: "login", symbol: "power", title: "Open at login",
                      why: "AirBridge starts with your Mac, so devices reconnect by themselves and screen time counts from the start of the day.",
                      without: "Nothing connects until you open AirBridge.",
                      action: "Turn On",
                      state: { LoginItem.isOn ? .done : .todo },
                      run: { [weak self] in LoginItem.set(true); self?.refreshAsync() }),
            SetupStep(id: "widgets", symbol: "square.grid.2x2", title: "Widgets",
                      why: "The Batteries widget shows your phone's battery next to your Mac's; the AirBridge widget shows screen time for all your devices.",
                      without: "Nothing breaks; you just don't see them at a glance.",
                      action: "",
                      state: { .unknown },
                      run: {}),
        ]
    }

    init() {
        refreshAsync()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshAsync() }
        }
    }

    deinit { timer?.invalidate() }

    func refreshAsync() {
        DispatchQueue.global(qos: .utility).async {
            let on = Self.shareExtensionOn()
            DispatchQueue.main.async { MainActor.assumeIsolated { self.shareOn = on } }
        }
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let ok = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
            DispatchQueue.main.async { MainActor.assumeIsolated { self.notifAllowed = ok; self.tick += 1 } }
        }
    }

    static func open(_ url: String) { if let u = URL(string: url) { NSWorkspace.shared.open(u) } }

    /// The Share extension is switched on (pluginkit lists it with a "+").
    nonisolated static func shareExtensionOn() -> Bool {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pluginkit")
        p.arguments = ["-m", "-i", "com.shazan.AirBridge.AirBridgeShare"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = Pipe()
        guard (try? p.run()) != nil else { return false }
        p.waitUntilExit()
        let text = String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        return text.trimmingCharacters(in: .whitespaces).hasPrefix("+")
    }
}

struct SetupGuideView: View {
    @ObservedObject var model: SetupModel
    @ObservedObject var app = AppModel.shared
    let close: () -> Void

    private var slideCount: Int { SetupModel.slides.count }
    private var steps: [SetupStep] { model.steps }
    /// Pages: slides, then steps, then pairing.
    private var pageCount: Int { slideCount + steps.count + 1 }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                if model.page < slideCount { slide(model.page) }
                else if model.page < slideCount + steps.count { stepPage(steps[model.page - slideCount]) }
                else { pairPage }
            }
            .id(model.page)
            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            footer
        }
        .padding(.horizontal, 48)
        .padding(.top, 44)
        .padding(.bottom, 28)
        .frame(width: 760, height: 560)
        .background(Ink.page)
        .foregroundStyle(Ink.text)
        .animation(.easeOut(duration: 0.25), value: model.page)
    }

    // MARK: Pages

    private func slide(_ i: Int) -> some View {
        let s = SetupModel.slides[i]
        return VStack(spacing: 18) {
            Spacer()
            Image(systemName: s.0).font(.system(size: 54, weight: .light)).foregroundStyle(Ink.secondary)
            Text(s.1).font(.system(size: 28, weight: .semibold)).tracking(-0.3)
            Text(s.2).font(.system(size: 15)).foregroundStyle(Ink.secondary).multilineTextAlignment(.center).frame(maxWidth: 460)
            Spacer()
        }
    }

    private func stepPage(_ s: SetupStep) -> some View {
        let state = s.state()
        let n = model.page - slideCount + 1
        return VStack(alignment: .leading, spacing: 16) {
            Eyebrow(text: "Step \(n) of \(steps.count)")
            HStack(spacing: 14) {
                Image(systemName: s.symbol).font(.system(size: 26, weight: .light)).foregroundStyle(Ink.secondary).frame(width: 36)
                Text(s.title).font(.system(size: 26, weight: .semibold)).tracking(-0.3)
                Spacer()
                StatusChip(state: state)
            }
            VStack(alignment: .leading, spacing: 10) {
                Text(s.why).font(.system(size: 15))
                Text("Without it: \(s.without)").font(.system(size: 13.5)).foregroundStyle(Ink.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
            if s.id == "widgets" {
                VStack(alignment: .leading, spacing: 6) {
                    Text("1. Click the date and time in the menu bar, then Edit Widgets at the bottom.")
                    Text("2. Search for AirBridge and drag its widget onto the desktop or into Notification Center.")
                    Text("3. Do the same with Batteries to see your phone's battery.")
                }
                .font(.system(size: 13.5)).foregroundStyle(Ink.secondary)
            }
            if state != .done && !s.action.isEmpty {
                Button(s.action) { s.run(); model.refreshAsync() }.buttonStyle(SetupPrimaryStyle())
                    .padding(.top, 6)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .id("\(s.id)-\(model.tick)")
    }

    private var pairPage: some View {
        let phones = app.readyPeers.filter { $0.platform == "android" }
        return VStack(spacing: 14) {
            Eyebrow(text: "Last step")
            Text(phones.isEmpty ? "Pair your phone" : "Paired with \(phones.map(\.name).joined(separator: ", "))")
                .font(.system(size: 26, weight: .semibold)).tracking(-0.3)
            if phones.isEmpty {
                if let qr = app.qrImage {
                    Image(nsImage: qr).interpolation(.none).resizable().frame(width: 180, height: 180)
                        .padding(10).background(RoundedRectangle(cornerRadius: Ink.radius).fill(.white))
                        .accessibilityLabel("Pairing QR code")
                }
                Text("Open AirBridge on your phone, tap Add device and scan this code. Both screens then show the same 6 digits to confirm.")
                    .font(.system(size: 13.5)).foregroundStyle(Ink.secondary).multilineTextAlignment(.center).frame(maxWidth: 440)
            } else {
                Image(systemName: "checkmark.circle").font(.system(size: 48, weight: .light)).foregroundStyle(Ink.secondary)
                Text("You're all set. Everything here is also in AirBridge's menu bar and Settings.")
                    .font(.system(size: 14)).foregroundStyle(Ink.secondary)
            }
            Spacer()
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack {
            if model.page > 0 {
                Button("Back") { model.page -= 1 }.buttonStyle(SetupQuietStyle())
            }
            Spacer()
            // Page dots for the slides; a count for the steps.
            if model.page < slideCount {
                HStack(spacing: 6) {
                    ForEach(0..<slideCount, id: \.self) { i in
                        Circle().fill(i == model.page ? Ink.text : Ink.hairline).frame(width: 6, height: 6)
                    }
                }
            }
            Spacer()
            if model.page < slideCount {
                Button("Skip intro") { model.page = slideCount }.buttonStyle(SetupQuietStyle())
                Button(model.page == slideCount - 1 ? "Set up AirBridge" : "Next") { model.page += 1 }.buttonStyle(SetupPrimaryStyle())
            } else if model.page < pageCount - 1 {
                let s = steps[model.page - slideCount]
                Button(s.state() == .done ? "Next" : "Skip") { model.page += 1 }
                    .buttonStyle(s.state() == .done ? AnyButtonStyle(SetupPrimaryStyle()) : AnyButtonStyle(SetupQuietStyle()))
            } else {
                Button("Done") { close() }.buttonStyle(SetupPrimaryStyle())
            }
        }
        .padding(.top, 12)
    }
}

private struct StatusChip: View {
    let state: SetupStep.State
    var body: some View {
        let (text, symbol): (String, String) = switch state {
        case .done: ("Done", "checkmark")
        case .todo: ("Not yet", "circle")
        case .unknown: ("Optional", "minus")
        }
        HStack(spacing: 5) {
            Image(systemName: symbol).font(.system(size: 10, weight: .bold))
            Text(text).font(.system(size: 12, weight: .medium))
        }
        .foregroundStyle(state == .done ? Ink.onPill : Ink.secondary)
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(Capsule().fill(state == .done ? Ink.pill : Ink.raised))
    }
}

struct SetupPrimaryStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13.5, weight: .semibold))
            .foregroundStyle(Ink.onPill)
            .padding(.horizontal, 18).padding(.vertical, 9)
            .background(Capsule().fill(Ink.pill))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

struct SetupQuietStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13.5, weight: .medium))
            .foregroundStyle(Ink.secondary)
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(Capsule().fill(Ink.raised))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

struct AnyButtonStyle: ButtonStyle {
    private let make: (Configuration) -> AnyView
    init<S: ButtonStyle>(_ s: S) { make = { AnyView(s.makeBody(configuration: $0)) } }
    func makeBody(configuration: Configuration) -> some View { make(configuration) }
}
