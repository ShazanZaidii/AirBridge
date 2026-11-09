import AppKit
import Combine
import SwiftUI

/// One "Pro Mirroring" window per phone: the three-step setup, or the settings once it is set up.
@MainActor final class ProMirrorSetupWindow: NSObject, NSWindowDelegate {
    static let shared = ProMirrorSetupWindow()
    private var windows: [String: (NSWindow, ProSetupModel)] = [:]

    func show(peerId: String, name: String, forceSetup: Bool = false) {
        ProMirrorHub.shared.startIfNeeded()
        if let (w, m) = windows[peerId] {
            if forceSetup { m.restart() }
            NSApp.activate(ignoringOtherApps: true)
            w.makeKeyAndOrderFront(nil)
            return
        }
        let model = ProSetupModel(peerId: peerId, name: name, forceSetup: forceSetup)
        let host = FitWindow.host(ProSetupView(model: model), width: 440) { [weak self] in self?.windows[peerId]?.0 }
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 440, height: 480),
                         styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
        w.title = "Pro Mirroring"
        w.titlebarAppearsTransparent = true
        w.isReleasedWhenClosed = false
        w.contentViewController = host
        w.delegate = self
        w.center()
        model.close = { [weak w] in w?.performClose(nil) }
        windows[peerId] = (w, model)
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        guard let w = notification.object as? NSWindow,
              let (id, entry) = windows.first(where: { $0.value.0 === w }) else { return }
        entry.1.stop()
        windows[id] = nil
    }
}

// MARK: - Model

@MainActor final class ProSetupModel: ObservableObject {
    let peerId: String
    let name: String
    @Published var step: ProSetup.Step = .intro
    @Published var code = ""
    @Published var record: ProMirrorRecord?
    @Published var reachable = false
    @Published var phoneStatus: PhoneDebugStatus?
    @Published var note: String?
    /// Showing the setup steps even though a record exists ("Pair again").
    @Published var settingUp: Bool
    var close: (() -> Void)?

    private var ctx = ProSetup.Context()
    private var discoveryToken: UUID?
    private var statusToken: UUID?
    private var poll: Timer?
    private var recordsObserver: NSObjectProtocol?

    init(peerId: String, name: String, forceSetup: Bool) {
        self.peerId = peerId
        self.name = name
        record = ProMirrorStore.record(peerId)
        settingUp = forceSetup || ProMirrorStore.record(peerId) == nil
        ctx.phoneIP = ProMirrorHub.phoneIP(peerId)
        discoveryToken = ADBDiscovery.shared.observe { [weak self] list in
            MainActor.assumeIsolated { self?.services(list) }
        }
        statusToken = ProMirrorHub.shared.observeStatus(peerId) { [weak self] s in
            MainActor.assumeIsolated { self?.status(s) }
        }
        recordsObserver = NotificationCenter.default.addObserver(forName: .proMirrorRecordsChanged, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.record = ProMirrorStore.record(peerId) }
        }
        // While the user works through steps 1–2 on the phone, ask the phone app now and then.
        poll = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                switch self.step {
                case .developerOptions, .wirelessDebugging: ProMirrorHub.shared.requestStatus(self.peerId)
                default: break
                }
                self.reachable = ProMirrorHub.isReachable(self.peerId)
            }
        }
        reachable = ProMirrorHub.isReachable(peerId)
        ProMirrorHub.shared.requestStatus(peerId)
    }

    func stop() {
        poll?.invalidate(); poll = nil
        if let t = discoveryToken { ADBDiscovery.shared.stopObserving(t) }
        if let t = statusToken { ProMirrorHub.shared.stopObserving(peerId, t) }
        if let o = recordsObserver { NotificationCenter.default.removeObserver(o) }
    }

    func restart() {
        settingUp = true
        code = ""
        step = .intro
    }

    var phoneHasHelper: Bool { ProMirrorHub.phoneHasHelper(peerId) }

    func send(_ e: ProSetup.Event) {
        let (next, effects) = ProSetup.reduce(step, e, &ctx)
        if next != step {
            withAnimation(.spring(response: 0.42, dampingFraction: 0.86)) { step = next }
            if case .enterCode = next {} else { code = "" }
        }
        effects.forEach(run)
    }

    func openOnPhone() {
        if ProMirrorHub.shared.openWirelessDebugging(peerId) {
            note = "Opening Developer options on \(name)…"
        } else {
            note = "Open Settings on \(name) yourself: AirBridge on the phone is not connected or too old."
        }
    }

    func submit() { send(.submitCode(code)) }

    func codeChanged() {
        let digits = code.filter(\.isNumber)
        if digits != code { code = String(digits.prefix(6)) }
        if code.count == 6, case .enterCode = step { submit() }
    }

    func setEnabled(_ on: Bool) { ProMirrorStore.update(peerId) { $0.enabled = on } }
    func setScreenOff(_ on: Bool) { ProMirrorStore.update(peerId) { $0.screenOff = on } }

    func forget() {
        if let r = record, let h = r.host, let p = r.port {
            Task.detached { _ = await ADB.run(ADB.disconnect(ADB.hostPort(h, p)), timeout: 5) }
        }
        ProMirrorStore.remove(peerId)
        close?()
    }

    func mirrorNow() {
        guard let p = PeerBus.shared.peers().first(where: { $0.id == peerId }) else {
            note = "\(name) isn't connected to AirBridge right now."
            return
        }
        close?()
        PhoneMirrorWindows.shared.show(p)
    }

    // MARK: inputs

    private func services(_ list: [ADBService]) {
        reachable = ProMirrorHub.isReachable(peerId)
        send(.pairingServices(list.filter { $0.kind == .pairing && $0.resolved }))
    }

    private func status(_ s: PhoneDebugStatus) {
        phoneStatus = s
        if s.opened == "notification" { note = "Tap the AirBridge notification on \(name) to open Wireless debugging." }
        else if s.opened == "direct" { note = nil }
        send(.phoneStatus(developerOptions: s.developerOptions, wirelessDebugging: s.wirelessDebugging))
    }

    // MARK: effects

    private func run(_ effect: ProSetup.Effect) {
        switch effect {
        case .askPhoneStatus:
            ProMirrorHub.shared.requestStatus(peerId)
        case let .pair(svc, code):
            guard let host = svc.host, let port = svc.port else { send(.pairFailed(.pairingFailed(""))); return }
            Task { [weak self] in
                let out = await ADB.run(ADB.pair(host: host, port: port, code: code), timeout: 25)
                let result: ProSetup.Event
                if ADB.binary == nil { result = .pairFailed(.adbMissing) }
                else if out.timedOut { result = .pairFailed(.pairingFailed("no answer from the phone")) }
                else {
                    switch ADB.parsePair(out.text, status: out.status) {
                    case .paired(let guid): result = .paired(guid: guid)
                    case .failed(let e): result = .pairFailed(e)
                    }
                }
                self?.send(result)
            }
        case let .connect(serial, pairingHost):
            Task { [weak self] in
                guard let self else { return }
                let ip = self.ctx.phoneIP
                let svc = await ADBDiscovery.shared.wait(timeout: 10) { list in
                    ADBService.match(list.filter { $0.kind == .connect }, serial: serial, ip: pairingHost ?? ip, allowSingle: true)
                }
                guard let svc, let h = svc.host, let p = svc.port else { self.send(.connectFailed(.wirelessDebuggingOff)); return }
                _ = await ADB.run(["start-server"], timeout: 10)
                let out = await ADB.run(ADB.connect(host: h, port: p), timeout: 10)
                switch ADB.parseConnect(out.text, host: h, port: p) {
                case .connected:
                    var stateError: ProMirrorError? = .offline
                    for _ in 0..<8 where stateError == .offline {
                        let state = await ADB.run(ADB.getState(ADB.hostPort(h, p)), timeout: 8)
                        stateError = ADB.parseState(state.text)
                        if stateError == .offline { try? await Task.sleep(nanoseconds: 400_000_000) }
                    }
                    if let e = stateError { self.send(.connectFailed(e)); return }
                    self.send(.connected(serial: svc.serial ?? serial ?? ADB.hostPort(h, p), host: h, port: p))
                case .failed(let e):
                    self.send(.connectFailed(e))
                }
            }
        case let .save(serial, host, port):
            var r = ProMirrorStore.record(peerId) ?? ProMirrorRecord(peerId: peerId, name: name, pairedAt: Date())
            r.name = name
            r.serial = serial
            r.host = host
            r.port = port
            r.pairedAt = Date()
            r.enabled = true
            ProMirrorStore.save(r)
            record = r
            reachable = true
        }
    }
}

// MARK: - Views

struct ProSetupView: View {
    @ObservedObject var model: ProSetupModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if model.settingUp { setupContent } else { manageContent }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let note = model.note {
                Text(note)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .padding(.top, 14)
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 28)
        .padding(.top, 38)
        .padding(.bottom, 24)
        .frame(width: 440)
        .animation(.spring(response: 0.42, dampingFraction: 0.86), value: model.note)
    }

    // MARK: Setup

    @ViewBuilder private var setupContent: some View {
        switch model.step {
        case .intro:
            page(icon: "bolt.horizontal", step: nil, title: "Pro Mirroring",
                 text: "Mirror and control \(model.name) over Android's Wireless debugging. No screen-sharing prompt, no recording chip, quicker response, and control works without AirBridge's accessibility service.") {
                bullet("Android 11 or later, on the same Wi-Fi as this Mac")
                bullet("A few one-time steps on the phone")
                bullet("Normal mirroring stays available as a fallback")
            } actions: {
                primary("Set Up") { model.send(.begin) }
            }
        case .developerOptions:
            page(icon: "hammer", step: 1, title: "Turn on Developer options",
                 text: "On \(model.name), open Settings › About phone, then tap Build number seven times. On some phones it sits under Software information.") {
                if model.phoneStatus?.developerOptions == true { done("Developer options are on") }
            } actions: {
                secondary("Back") { model.send(.back) }
                Spacer()
                primary("Next") { model.send(.next) }
            }
        case .wirelessDebugging:
            page(icon: "wifi", step: 2, title: "Turn on Wireless debugging",
                 text: "In Settings › System › Developer options, turn on Wireless debugging and allow it on this network.") {
                if model.phoneHasHelper {
                    linkButton("Open Developer options on the phone") { model.openOnPhone() }
                }
                if model.phoneStatus?.wirelessDebugging == true { done("Wireless debugging is on") }
            } actions: {
                secondary("Back") { model.send(.back) }
                Spacer()
                primary("Next") { model.send(.next) }
            }
        case .waitingForPairing:
            page(icon: "qrcode", step: 3, title: "Pair with a code",
                 text: "Tap Wireless debugging, then Pair device with pairing code. This Mac finds the phone on its own.") {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Looking for \(model.name)…").font(.system(size: 12)).foregroundStyle(.secondary)
                }
                .padding(.top, 2)
                if model.phoneHasHelper {
                    linkButton("Open Developer options on the phone") { model.openOnPhone() }
                }
            } actions: {
                secondary("Back") { model.send(.back) }
                Spacer()
            }
        case let .enterCode(svc, error):
            page(icon: "number", step: 3, title: "Enter the pairing code",
                 text: "Type the six digits shown on \(model.name) under Wi-Fi pairing code.") {
                CodeField(code: $model.code, onChange: model.codeChanged, onSubmit: model.submit)
                    .padding(.top, 4)
                if let error { Text(error.message(phone: model.name)).font(.system(size: 12)).foregroundStyle(.secondary) }
                if let h = svc.host { Text("Found at \(h)").font(.system(size: 11)).foregroundStyle(.tertiary) }
            } actions: {
                secondary("Back") { model.send(.back) }
                Spacer()
                primary("Pair") { model.submit() }
                    .disabled(ProSetup.normalizeCode(model.code) == nil)
            }
        case .pairing:
            page(icon: "link", step: 3, title: "Pairing…", text: "Keep the pairing code open on the phone for a moment.") {
                ProgressView().controlSize(.small)
            } actions: { EmptyView() }
        case .connecting:
            page(icon: "link", step: 3, title: "Connecting…", text: "Paired. Connecting to \(model.name).") {
                ProgressView().controlSize(.small)
            } actions: { EmptyView() }
        case .done:
            page(icon: "checkmark", step: nil, title: "Pro mirroring is ready",
                 text: "Mirror phone now uses Pro mirroring for \(model.name) whenever Wireless debugging is on.") {
                screenOffToggle
                restartNote
            } actions: {
                secondary("Close") { model.close?() }
                Spacer()
                primary("Mirror Now") { model.mirrorNow() }
            }
        case let .failed(error, _):
            page(icon: "exclamationmark", step: nil, title: "That didn't work",
                 text: error.message(phone: model.name)) {
                EmptyView()
            } actions: {
                secondary("Close") { model.close?() }
                Spacer()
                primary("Try Again") { model.send(.retry) }
            }
        }
    }

    // MARK: Manage (already set up)

    @ViewBuilder private var manageContent: some View {
        page(icon: "bolt.horizontal", step: nil, title: "Pro Mirroring",
             text: model.reachable
                ? "\(model.name) is ready. Mirror phone uses Pro mirroring."
                : "Wireless debugging is off on \(model.name), so Mirror phone uses normal mirroring for now.") {
            Toggle("Use Pro mirroring when available", isOn: Binding(
                get: { model.record?.enabled ?? true }, set: { model.setEnabled($0) }))
                .toggleStyle(.switch).controlSize(.small)
            screenOffToggle
            if !model.reachable && model.phoneHasHelper {
                linkButton("Open Wireless debugging on the phone") { model.openOnPhone() }
            }
            restartNote
        } actions: {
            Button("Forget", role: .destructive) { model.forget() }
                .buttonStyle(.plain).foregroundStyle(.secondary).font(.system(size: 12.5))
            Button("Pair Again") { model.restart() }
                .buttonStyle(.plain).foregroundStyle(.secondary).font(.system(size: 12.5))
                .padding(.leading, 10)
            Spacer()
            primary("Mirror Now") { model.mirrorNow() }
        }
    }

    private var screenOffToggle: some View {
        Toggle("Turn off the phone's screen while mirroring", isOn: Binding(
            get: { model.record?.screenOff ?? false }, set: { model.setScreenOff($0) }))
            .toggleStyle(.switch).controlSize(.small)
    }

    private var restartNote: some View {
        Text("Many phones turn Wireless debugging off after a restart or on another Wi-Fi network. Turn it back on when that happens; pairing is kept.")
            .font(.system(size: 11.5))
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 4)
    }

    // MARK: Pieces

    private func page<C: View, A: View>(icon: String, step: Int?, title: String, text: String,
                                        @ViewBuilder content: () -> C, @ViewBuilder actions: () -> A) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.primary.opacity(0.8))
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(Color.primary.opacity(0.07)))
                if let step {
                    Text("STEP \(step) OF 3")
                        .font(.system(size: 10.5, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
            }
            Text(title).font(.system(size: 19, weight: .semibold))
            Text(text)
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 10) { content() }
            HStack(spacing: 10) { actions() }.padding(.top, 10)
        }
        .transition(.asymmetric(insertion: .opacity.combined(with: .offset(x: 18)), removal: .opacity))
        .id(title)
    }

    private func bullet(_ s: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Circle().fill(Color.secondary.opacity(0.6)).frame(width: 4, height: 4).offset(y: -2)
            Text(s).font(.system(size: 12.5)).foregroundStyle(.primary.opacity(0.85))
        }
    }

    private func done(_ s: String) -> some View {
        Label(s, systemImage: "checkmark.circle")
            .font(.system(size: 12.5))
            .foregroundStyle(.secondary)
    }

    private func linkButton(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: "iphone.gen3.radiowaves.left.and.right")
                .font(.system(size: 12.5, weight: .medium))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary.opacity(0.85))
    }

    private func primary(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title) }.buttonStyle(ProPrimaryButtonStyle()).keyboardShortcut(.defaultAction)
    }

    private func secondary(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title) }.buttonStyle(ProSecondaryButtonStyle())
    }
}

/// Six big digits; submits itself when complete.
private struct CodeField: View {
    @Binding var code: String
    var onChange: () -> Void
    var onSubmit: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        TextField("000000", text: $code)
            .textFieldStyle(.plain)
            .font(.system(size: 30, weight: .medium, design: .monospaced))
            .tracking(8)
            .frame(width: 210)
            .padding(.vertical, 8).padding(.horizontal, 14)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.primary.opacity(0.06)))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Color.primary.opacity(focused ? 0.25 : 0.1)))
            .focused($focused)
            .onChange(of: code) { onChange() }
            .onSubmit(onSubmit)
            .onAppear { focused = true }
    }
}

/// White capsule in dark mode, graphite in light mode.
struct ProPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Color(nsColor: .windowBackgroundColor))
            .padding(.horizontal, 18).padding(.vertical, 7)
            .background(Capsule().fill(Color.primary.opacity(configuration.isPressed ? 0.7 : (enabled ? 0.9 : 0.3))))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.8), value: configuration.isPressed)
    }
}

struct ProSecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(.primary.opacity(0.8))
            .padding(.horizontal, 16).padding(.vertical, 7)
            .background(Capsule().fill(Color.primary.opacity(configuration.isPressed ? 0.14 : 0.07)))
    }
}
