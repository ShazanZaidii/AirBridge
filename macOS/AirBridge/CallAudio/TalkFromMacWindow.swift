import AppKit
import AVFoundation
import Combine
import CoreBluetooth
import IOBluetooth
import SwiftUI

/// "Talk from your Mac": the one-time setup for call audio on this Mac (Bluetooth, pairing with the phone,
/// microphone). Opened with `CallAudio.shared.openSetup()`.
@MainActor final class TalkFromMacWindow: NSObject, NSWindowDelegate {
    static let shared = TalkFromMacWindow()
    private var window: NSWindow?
    private var model: TalkSetupModel?

    func show() {
        if let window {
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            return
        }
        let model = TalkSetupModel()
        let host = FitWindow.host(TalkSetupView(model: model), width: 440) { [weak self] in self?.window }
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 440, height: 460),
                         styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
        w.title = "Talk from your Mac"
        w.titlebarAppearsTransparent = true
        w.isReleasedWhenClosed = false
        w.contentViewController = host
        w.delegate = self
        w.center()
        model.close = { [weak w] in w?.performClose(nil) }
        window = w
        self.model = model
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        model?.stop()
        model = nil
        window = nil
        CallAudioController.shared.refresh()
    }
}

// MARK: - Model

@MainActor final class TalkSetupModel: NSObject, ObservableObject {
    struct Phone: Identifiable, Equatable {
        let id: String          // Bluetooth address
        let name: String
        let connected: Bool
    }

    @Published var access: CBManagerAuthorization = CBManager.authorization
    @Published var bluetoothOn = false
    @Published var phones: [Phone] = []
    @Published var chosen: String?
    @Published var mic: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .audio)
    @Published var airBridgePhoneNames: [String] = []
    var close: (() -> Void)?

    private var poll: Timer?
    private var central: CBCentralManager?

    override init() {
        super.init()
        reload()
        poll = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.reload() }
        }
    }

    func stop() { poll?.invalidate(); poll = nil; central = nil }

    var bluetoothReady: Bool { access == .allowedAlways && bluetoothOn }
    var phoneReady: Bool { chosen != nil }
    var micReady: Bool { mic == .authorized }
    var allReady: Bool { bluetoothReady && phoneReady && micReady }

    func reload() {
        access = CBManager.authorization
        mic = AVCaptureDevice.authorizationStatus(for: .audio)
        bluetoothOn = CallAudioController.bluetoothOn
        let list = CallAudioController.pairedPhones().compactMap { d -> Phone? in
            guard let a = d.addressString else { return nil }
            return Phone(id: a, name: d.name ?? a, connected: d.isConnected())
        }
        if list != phones { phones = list }
        let ctl = CallAudioController.shared
        chosen = ctl.chosenPhone()?.addressString
        airBridgePhoneNames = ctl.phoneBluetoothNames
    }

    /// Shows macOS's Bluetooth permission prompt (creating a Bluetooth manager is what asks).
    func allowBluetooth() {
        if access == .notDetermined {
            central = CBCentralManager(delegate: self, queue: nil)
        } else {
            open("x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth")
        }
    }

    func openBluetoothSettings() { open("x-apple.systempreferences:com.apple.BluetoothSettings") }

    func choose(_ p: Phone) {
        CallAudioController.shared.choosePhone(address: p.id)
        reload()
    }

    func allowMic() {
        if mic == .notDetermined {
            AVCaptureDevice.requestAccess(for: .audio) { _ in
                DispatchQueue.main.async { [weak self] in self?.reload() }
            }
        } else {
            open("x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone")
        }
    }

    private func open(_ s: String) { if let u = URL(string: s) { NSWorkspace.shared.open(u) } }
}

extension TalkSetupModel: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        callAudioOnMain { [weak self] in
            self?.reload()
            CallAudioController.shared.refresh()
        }
    }
}

// MARK: - View

struct TalkSetupView: View {
    @ObservedObject var model: TalkSetupModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Talk from your Mac").font(.title2.weight(.semibold))
                Text("Answer a call here and talk through this Mac's microphone and speakers, or the headphones it uses. During calls your phone connects to this Mac like a Bluetooth headset, so its call screen can offer this Mac too.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(alignment: .leading, spacing: 14) {
                step(1, "Bluetooth", done: model.bluetoothReady) { bluetoothStep }
                step(2, "Pair your phone", done: model.phoneReady) { pairStep }
                step(3, "Microphone", done: model.micReady) { micStep }
            }

            if model.allReady {
                Label("Ready. Answer here, pick Mac in the call window, or pick this Mac in your phone's call audio options.", systemImage: "checkmark.circle")
                    .font(.callout)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }

            Text("When you answer on your phone the call stays on the phone. When your phone uses its own Bluetooth headset, this Mac doesn't connect at all.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack {
                Spacer()
                Button("Done") { model.close?() }
                    .buttonStyle(ProPrimaryButtonStyle())
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .padding(.top, 8)
        .frame(width: 440)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: model.allReady)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: model.phones)
    }

    @ViewBuilder private var bluetoothStep: some View {
        switch model.access {
        case .allowedAlways:
            if model.bluetoothOn {
                Text("On. Keep Bluetooth on on your phone too.").stepText()
            } else {
                Text("Turn on Bluetooth on this Mac and on your phone.").stepText()
                Button("Open Bluetooth Settings") { model.openBluetoothSettings() }.buttonStyle(ProSecondaryButtonStyle())
            }
        case .notDetermined:
            Text("AirBridge needs to use Bluetooth to connect to your phone for calls.").stepText()
            Button("Allow Bluetooth") { model.allowBluetooth() }.buttonStyle(ProSecondaryButtonStyle())
        default:
            Text("Bluetooth is turned off for AirBridge in Privacy & Security.").stepText()
            Button("Open Privacy Settings") { model.allowBluetooth() }.buttonStyle(ProSecondaryButtonStyle())
        }
    }

    @ViewBuilder private var pairStep: some View {
        if model.access != .allowedAlways {
            Text("After Bluetooth is allowed.").stepText()
        } else if model.phones.isEmpty {
            Text("On your phone, open Bluetooth settings and keep the screen on. On this Mac, choose the phone in Bluetooth Settings and confirm the same code on both.").stepText()
            Button("Open Bluetooth Settings") { model.openBluetoothSettings() }.buttonStyle(ProSecondaryButtonStyle())
        } else {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(model.phones) { p in
                    Button { model.choose(p) } label: {
                        HStack(spacing: 8) {
                            Image(systemName: model.chosen == p.id ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(model.chosen == p.id ? Color.primary : Color.secondary)
                            Text(p.name).lineLimit(1)
                            if model.airBridgePhoneNames.contains(where: { $0.caseInsensitiveCompare(p.name) == .orderedSame }) {
                                Text("AirBridge").font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .padding(.vertical, 5).padding(.horizontal, 8)
                        .background(RoundedRectangle(cornerRadius: 7).fill(Color.primary.opacity(model.chosen == p.id ? 0.07 : 0)))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            if model.chosen == nil {
                Text("Choose your phone.").stepText()
            }
            Button("Pair another phone") { model.openBluetoothSettings() }
                .buttonStyle(.borderless)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var micStep: some View {
        switch model.mic {
        case .authorized:
            Text("Allowed. The call uses this Mac's current microphone.").stepText()
        case .notDetermined:
            Text("So the other person can hear you.").stepText()
            Button("Allow Microphone") { model.allowMic() }.buttonStyle(ProSecondaryButtonStyle())
        default:
            Text("The microphone is turned off for AirBridge in Privacy & Security.").stepText()
            Button("Open Privacy Settings") { model.allowMic() }.buttonStyle(ProSecondaryButtonStyle())
        }
    }

    private func step<Content: View>(_ n: Int, _ title: String, done: Bool, @ViewBuilder content: () -> Content) -> some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle().strokeBorder(Color.primary.opacity(done ? 0 : 0.25), lineWidth: 1)
                    .background(Circle().fill(Color.primary.opacity(done ? 0.85 : 0)))
                if done {
                    Image(systemName: "checkmark").font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Color(nsColor: .windowBackgroundColor))
                        .transition(.scale.combined(with: .opacity))
                } else {
                    Text("\(n)").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                }
            }
            .frame(width: 22, height: 22)
            .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.7), value: done)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.headline)
                content()
            }
            Spacer(minLength: 0)
        }
    }
}

private extension Text {
    func stepText() -> some View {
        self.font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
    }
}
