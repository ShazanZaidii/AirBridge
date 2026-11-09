import Foundation
import Combine

/// Where the audio of the phone call currently is, and switching it (shared by the call window and the
/// Bluetooth hands-free code). This file is the contract; the hands-free implementation fills it in.
@MainActor final class CallAudio: ObservableObject {
    static let shared = CallAudio()

    enum Route: Equatable {
        /// The Mac can't carry call audio (no Bluetooth link to the phone yet); `reason` is one plain sentence.
        case unavailable(reason: String)
        /// Audio is on the phone (earpiece, speaker or a headset).
        case phone
        /// Audio is on this Mac (microphone and speakers).
        case mac
    }

    @Published private(set) var route: Route = .unavailable(reason: "Pair your phone with this Mac over Bluetooth to talk from here.")
    /// The Mac's own microphone is muted (only meaningful while `route == .mac`).
    @Published private(set) var macMicMuted = false
    /// One short line to show with an available route, e.g. "Your phone is using Galaxy Buds2." (audio stays
    /// on the phone because another headset is connected to it). Nil when there is nothing to say.
    @Published private(set) var note: String?
    /// The audio is being moved to this Mac right now (a second or two); the window may show a quiet hint.
    @Published private(set) var switching = false

    /// Implementation hooks, set by the hands-free module at launch.
    var onUseMac: (() -> Void)?
    var onUsePhone: (() -> Void)?
    var onSetMacMicMuted: ((Bool) -> Void)?
    var onOpenSetup: (() -> Void)?
    var onCallEnded: (() -> Void)?

    // MARK: Device choice (shared contract: filled by the hands-free module, shown by the call window)

    /// One audio device a call can use.
    struct Device: Identifiable, Equatable, Hashable {
        /// Mac devices: the Core Audio UID. Phone devices: "phone:<type>[:<address>]" as the phone reports them.
        let id: String
        let name: String
        /// SF Symbol for the picker (e.g. "laptopcomputer", "airpods", "headphones", "iphone", "speaker.wave.2").
        let symbol: String
    }

    /// The Mac's microphones and speakers usable for the call (current defaults first).
    @Published private(set) var macInputs: [Device] = []
    @Published private(set) var macOutputs: [Device] = []
    @Published private(set) var selectedMacInput: String?
    @Published private(set) var selectedMacOutput: String?
    /// Where the phone itself can play the call (earpiece, speaker, wired, Bluetooth headsets…), from the phone.
    @Published private(set) var phoneRoutes: [Device] = []
    @Published private(set) var selectedPhoneRoute: String?

    var onSelectMacInput: ((String) -> Void)?
    var onSelectMacOutput: ((String) -> Void)?
    var onSelectPhoneRoute: ((String) -> Void)?

    func selectMacInput(_ id: String) { onSelectMacInput?(id) }
    func selectMacOutput(_ id: String) { onSelectMacOutput?(id) }
    /// Moves the call audio to that phone route (and away from the Mac if it was here).
    func selectPhoneRoute(_ id: String) { onSelectPhoneRoute?(id) }

    func report(macInputs: [Device], selected: String?) { self.macInputs = macInputs; selectedMacInput = selected }
    func report(macOutputs: [Device], selected: String?) { self.macOutputs = macOutputs; selectedMacOutput = selected }
    func report(phoneRoutes: [Device], selected: String?) { self.phoneRoutes = phoneRoutes; selectedPhoneRoute = selected }

    func useMac() { onUseMac?() }
    func usePhone() { onUsePhone?() }
    func setMacMicMuted(_ on: Bool) { onSetMacMicMuted?(on) }
    /// Shows the "Talk from your Mac" setup (Bluetooth pairing, microphone).
    func openSetup() { onOpenSetup?() }
    /// Tells the hands-free side the phone call is over (it also notices by itself while the link is up).
    func callEnded() { onCallEnded?() }

    /// Called by the implementation when the route or mute state really changed.
    func report(route: Route) { if self.route != route { self.route = route } }
    func report(macMicMuted: Bool) { if self.macMicMuted != macMicMuted { self.macMicMuted = macMicMuted } }
    func report(note: String?) { if self.note != note { self.note = note } }
    func report(switching: Bool) { if self.switching != switching { self.switching = switching } }
}
