import CoreAudio
import Foundation
import IOKit

/// The Mac's microphones and speakers a call can use, for the call window's device picker, and which ones the
/// call bridge should use. Choices are remembered for the next call (when that device is there) and only ever
/// apply to the call: the system's default devices are never changed.
///
/// Rule for remembered choices: the remembered device is used when present, except when the system default is
/// a headset (AirPods, headphones, USB headset) and the remembered device is not, because putting headphones on
/// should keep the call in them.
@MainActor final class MacAudioDevices {
    static let shared = MacAudioDevices()

    static let inputKey = "callAudio.macInputUID"
    static let outputKey = "callAudio.macOutputUID"

    /// Called on the main thread whenever devices or defaults change.
    var onChange: (() -> Void)?
    private var listening = false
    private var pending = false

    /// Starts listening for device and default-device changes (Core Audio property listeners).
    func start() {
        guard !listening else { return }
        listening = true
        let selectors = [kAudioHardwarePropertyDevices, kAudioHardwarePropertyDefaultInputDevice,
                         kAudioHardwarePropertyDefaultOutputDevice]
        for sel in selectors {
            var addr = AudioObjectPropertyAddress(mSelector: sel, mScope: kAudioObjectPropertyScopeGlobal,
                                                  mElement: kAudioObjectPropertyElementMain)
            AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &addr, DispatchQueue.main) { _, _ in
                MainActor.assumeIsolated { MacAudioDevices.shared.changed() }
            }
        }
    }

    /// Several notifications arrive together when a device comes or goes; report once.
    private func changed() {
        guard !pending else { return }
        pending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            MainActor.assumeIsolated {
                self?.pending = false
                self?.onChange?()
            }
        }
    }

    func remember(uid: String, input: Bool) {
        UserDefaults.standard.set(uid, forKey: input ? Self.inputKey : Self.outputKey)
    }

    // MARK: Lists (nonisolated: Core Audio reads are thread-safe)

    /// Devices for the picker: the system default first, then built-in, then the rest by name. Leaves out the
    /// phone's own Bluetooth audio device and virtual/aggregate devices (unless one is the default).
    nonisolated static func list(input: Bool, excluding phone: Set<AudioDeviceID>, phoneName: String?) -> [CallAudio.Device] {
        let sys = CoreAudioDevices.defaultDevice(input: input)
        var rows: [(id: AudioDeviceID, builtIn: Bool, device: CallAudio.Device)] = []
        for id in CoreAudioDevices.all() where usable(id, input: input, excluding: phone, phoneName: phoneName, systemDefault: sys) {
            guard let uid = CoreAudioDevices.uid(id) else { continue }
            let name = CoreAudioDevices.name(id) ?? (input ? "Microphone" : "Speakers")
            rows.append((id, CoreAudioDevices.transport(id) == kAudioDeviceTransportTypeBuiltIn,
                         CallAudio.Device(id: uid, name: name, symbol: symbol(id, input: input, name: name))))
        }
        rows.sort { a, b in
            if (a.id == sys) != (b.id == sys) { return a.id == sys }
            if a.builtIn != b.builtIn { return a.builtIn }
            return a.device.name.localizedCaseInsensitiveCompare(b.device.name) == .orderedAscending
        }
        return rows.map(\.device)
    }

    /// Names of virtual drivers that are never a real microphone or speaker for a call.
    private nonisolated static let junk = ["zoomaudiodevice", "zoom audio", "blackhole", "soundflower", "loopback",
                                           "microsoft teams audio", "krisp", "cadefaultdeviceaggregate", "ms teams",
                                           "webexmediaaudio", "obs ", "screen recording", "rogue amoeba"]

    nonisolated static func usable(_ id: AudioDeviceID, input: Bool, excluding phone: Set<AudioDeviceID>,
                                   phoneName: String?, systemDefault: AudioDeviceID) -> Bool {
        guard CoreAudioDevices.hasStreams(id, input: input), !phone.contains(id) else { return false }
        let t = CoreAudioDevices.transport(id)
        let name = (CoreAudioDevices.name(id) ?? "").lowercased()
        if t == kAudioDeviceTransportTypeBluetooth || t == kAudioDeviceTransportTypeBluetoothLE,
           let pn = phoneName?.lowercased(), !pn.isEmpty, name == pn { return false }
        if id == systemDefault { return true }
        if CoreAudioDevices.isHidden(id) { return false }
        if !CoreAudioDevices.canBeDefault(id, input: input) { return false }
        if t == kAudioDeviceTransportTypeAggregate || t == kAudioDeviceTransportTypeVirtual || t == kAudioDeviceTransportTypeUnknown {
            return false
        }
        return !junk.contains { name.contains($0) }
    }

    // MARK: Which device the call uses

    /// The device the call uses in each direction (for the picker's selection), as Core Audio IDs.
    nonisolated static func effective(input: Bool, excluding phone: Set<AudioDeviceID>, phoneName: String?,
                                      fallback: AudioDeviceID?) -> AudioDeviceID? {
        let sys = CoreAudioDevices.defaultDevice(input: input)
        let ok = { (id: AudioDeviceID) in
            id != 0 && usable(id, input: input, excluding: phone, phoneName: phoneName, systemDefault: sys)
        }
        let key = input ? inputKey : outputKey
        let remembered = UserDefaults.standard.string(forKey: key).flatMap { uid in
            CoreAudioDevices.all().first { CoreAudioDevices.uid($0) == uid }
        }.flatMap { ok($0) ? $0 : nil }
        if let r = remembered {
            if r != sys, ok(sys), isHeadset(sys), !isHeadset(r) { return sys }
            return r
        }
        if ok(sys) { return sys }
        // The default is the phone itself (macOS switched to it): use what was there before, or the built-in.
        if let f = fallback, ok(f) { return f }
        return CoreAudioDevices.all().first { ok($0) && CoreAudioDevices.transport($0) == kAudioDeviceTransportTypeBuiltIn }
            ?? CoreAudioDevices.all().first(where: ok)
    }

    /// For the bridge: an explicit device per direction, or nil where the call should simply follow the system
    /// default (then the bridge can use voice processing, which runs on the defaults).
    nonisolated static func callDevices(excluding phone: Set<AudioDeviceID>, phoneName: String?,
                                        fallback: (input: AudioDeviceID, output: AudioDeviceID)?) -> (input: AudioDeviceID?, output: AudioDeviceID?) {
        let i = effective(input: true, excluding: phone, phoneName: phoneName, fallback: fallback?.input)
        let o = effective(input: false, excluding: phone, phoneName: phoneName, fallback: fallback?.output)
        return (i == CoreAudioDevices.defaultDevice(input: true) ? nil : i,
                o == CoreAudioDevices.defaultDevice(input: false) ? nil : o)
    }

    nonisolated static func isHeadset(_ id: AudioDeviceID) -> Bool {
        let t = CoreAudioDevices.transport(id)
        let name = (CoreAudioDevices.name(id) ?? "").lowercased()
        if t == kAudioDeviceTransportTypeBluetooth || t == kAudioDeviceTransportTypeBluetoothLE { return true }
        if t == kAudioDeviceTransportTypeUSB && CoreAudioDevices.hasStreams(id, input: true) && CoreAudioDevices.hasStreams(id, input: false) { return true }
        return ["headphone", "headset", "airpods", "buds", "earphone"].contains { name.contains($0) }
    }

    // MARK: Symbols

    nonisolated static func symbol(_ id: AudioDeviceID, input: Bool, name: String) -> String {
        let n = name.lowercased()
        switch CoreAudioDevices.transport(id) {
        case kAudioDeviceTransportTypeBuiltIn:
            if n.contains("headphone") || n.contains("external") { return "headphones" }
            return macSymbol
        case kAudioDeviceTransportTypeBluetooth, kAudioDeviceTransportTypeBluetoothLE:
            return airPodsSymbol(id, name: n) ?? (n.contains("beats") ? "beats.headphones" : "headphones")
        case kAudioDeviceTransportTypeUSB:
            if CoreAudioDevices.hasStreams(id, input: true) && CoreAudioDevices.hasStreams(id, input: false) && isHeadset(id) {
                return "headphones"
            }
            return input ? "mic" : "hifispeaker"
        case kAudioDeviceTransportTypeHDMI, kAudioDeviceTransportTypeDisplayPort:
            return "display"
        case kAudioDeviceTransportTypeAirPlay:
            return "airplayaudio"
        case kAudioDeviceTransportTypeContinuityCaptureWired, kAudioDeviceTransportTypeContinuityCaptureWireless:
            return "iphone"
        case kAudioDeviceTransportTypeThunderbolt, kAudioDeviceTransportTypeFireWire, kAudioDeviceTransportTypePCI:
            return input ? "mic" : "hifispeaker"
        default:
            return input ? "mic" : "speaker.wave.2"
        }
    }

    /// AirPods model from the name ("… AirPods Pro"), else from Apple's Bluetooth product ID in the model UID.
    nonisolated static func airPodsSymbol(_ id: AudioDeviceID, name: String) -> String? {
        if name.contains("airpods max") { return "airpodsmax" }
        if name.contains("airpods pro") { return "airpodspro" }
        if name.contains("airpods") { return "airpods" }
        guard let model = CoreAudioDevices.string(id, kAudioDevicePropertyModelUID)?.lowercased() else { return nil }
        let max: Set<String> = ["200a", "201f"]
        let pro: Set<String> = ["200e", "2014", "2024", "2027"]
        let plain: Set<String> = ["2002", "200f", "2013", "2019", "201b", "2026"]
        let tokens = model.split(whereSeparator: { !$0.isHexDigit }).map(String.init)
        guard tokens.contains(where: { $0 == "4c" || $0 == "004c" }) else { return nil }
        if tokens.contains(where: max.contains) { return "airpodsmax" }
        if tokens.contains(where: pro.contains) { return "airpodspro" }
        if tokens.contains(where: plain.contains) { return "airpods" }
        return nil
    }

    /// This Mac's own symbol, from its product name ("MacBook Pro (14-inch, 2021)", "Mac mini (2023)" …).
    nonisolated static let macSymbol: String = {
        let product = productName().lowercased()
        let model = hardwareModel().lowercased()
        if product.contains("macbook") || model.contains("macbook") { return "laptopcomputer" }
        if product.contains("mac mini") || model.contains("macmini") { return "macmini" }
        if product.contains("mac studio") { return "macstudio" }
        if product.contains("mac pro") || model.contains("macpro") { return "macpro.gen3" }
        if product.contains("imac") || model.contains("imac") { return "desktopcomputer" }
        return "desktopcomputer"
    }()

    private nonisolated static func productName() -> String {
        let entry = IORegistryEntryFromPath(kIOMainPortDefault, "IODeviceTree:/product")
        guard entry != 0 else { return "" }
        defer { IOObjectRelease(entry) }
        guard let data = IORegistryEntryCreateCFProperty(entry, "product-name" as CFString, kCFAllocatorDefault, 0)?
            .takeRetainedValue() as? Data else { return "" }
        return String(decoding: data.prefix { $0 != 0 }, as: UTF8.self)
    }

    private nonisolated static func hardwareModel() -> String {
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        guard size > 0 else { return "" }
        var buf = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.model", &buf, &size, nil, 0)
        return String(cString: buf)
    }
}
