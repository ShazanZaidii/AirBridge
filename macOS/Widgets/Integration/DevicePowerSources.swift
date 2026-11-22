import CryptoKit
import Foundation

/// Lists each connected phone in macOS's own battery list, so it shows in the Batteries widget, the menu-bar
/// battery menu and `pmset -g accps` next to the Mac's other devices.
///
/// The system keeps one "power source" per device. Bluetooth accessories get theirs from `bluetoothd`; we add one
/// per phone with the same two IOKit calls it uses. Those calls are private (Apple's own header calls them
/// "internal, publicly unsupported helper functions"), so everything that touches them is in this file: if a macOS
/// update changes them, this is the only place to fix, and the rest of AirBridge is unaffected.
///
/// The phone sends `dev_battery` (`pct`, `charging`) when its level changes. An entry exists only while that phone
/// is connected: it is removed on disconnect, when the setting is turned off, and when the app quits (the system
/// drops a process's power sources when it exits).
nonisolated final class DevicePowerSources: @unchecked Sendable {
    static let shared = DevicePowerSources()

    static let showKey = "ShowPhoneBattery"
    static var enabled: Bool { UserDefaults.standard.object(forKey: showKey) as? Bool ?? true }

    /// What the widget draws, per kind of device.
    ///
    /// Phones keep the picture the widget already has for them: Apple's phone-in-a-case shape, chosen by a rule the widget
    /// applies to USB sources whose vendor is Apple's USB id (1452) and whose product id is 5016 (the constant BatteryCenter
    /// calls "R5x"). It needs nothing from AirBridge.
    ///
    /// Computers have no picture of their own in the widget, but the widget asks the system's type database "what is
    /// Bluetooth product 76:N?" and draws the symbol that type declares. AirBridge declares one type per platform
    /// (Info.plist, `UTExportedTypeDeclarations`), each with a product id from `computerProduct` and a symbol that ships with
    /// macOS. The ids here and the declarations there must match: `tools/batterycheck` reads the Info.plist and fails if
    /// they don't.
    ///
    /// Everything stays a plain accessory entry ("Accessory Source"). Posing as an internal battery would draw this Mac's
    /// laptop icon, but the menu bar then averages that entry into its own battery percentage.
    /// `category` is one the widget has no picture of its own for: if a type is ever not found, the device falls back to the
    /// plain Bluetooth symbol instead of showing something wrong.
    static let category = "Battery Case"
    static let appleVendor = 76
    static let appleUSBVendor = 1452
    static let phoneGlyphProduct = 5016

    static func isPhone(_ platform: String) -> Bool { platform == "android" || platform == "ios" }

    /// The product id of AirBridge's own type for a computer. Windows and Linux are told apart by the `plat` the device
    /// announces in its hello. "Other" is only the safety net for a platform this build doesn't know yet.
    static func computerProduct(for platform: String) -> Int {
        switch platform {
        case "windows": return 64001   // pc
        case "linux": return 64002     // terminal.fill
        case "macos": return 64003     // macbook
        default: return 64005          // desktopcomputer
        }
    }

    private struct Entry {
        var pct: Int
        var charging: Bool
        var handle: OpaquePointer?
    }

    private let lock = NSLock()
    private var entries: [String: Entry] = [:]

    // MARK: From the network

    /// `dev_battery` from a phone (network queue).
    func receive(from peerId: String, _ msg: [String: Any]) {
        guard let pct = (msg["pct"] as? NSNumber)?.intValue, (0...100).contains(pct) else { return }
        let charging = (msg["charging"] as? Bool) ?? false
        lock.lock(); defer { lock.unlock() }
        var e = entries[peerId] ?? Entry(pct: pct, charging: charging, handle: nil)
        e.pct = pct; e.charging = charging
        entries[peerId] = e
        publish(peerId)
    }

    /// The ready peers changed: forget the phones that are gone, refresh names.
    func peersChanged(_ peers: [PeerInfo]) {
        lock.lock(); defer { lock.unlock() }
        let live = Set(peers.map(\.id))
        for id in Array(entries.keys) where !live.contains(id) { remove(id) }
        for id in entries.keys { publish(id) }
    }

    /// The setting changed (or the app is quitting): put back or take away every entry.
    func refresh() {
        lock.lock(); defer { lock.unlock() }
        for id in entries.keys { publish(id) }
    }

    func removeAll() {
        lock.lock(); defer { lock.unlock() }
        for id in Array(entries.keys) { remove(id) }
    }

    // MARK: The system's list (private calls; lock held)

    /// What the system's power-source list gets for one device.
    static func details(name: String, platform: String, pct: Int, charging: Bool, identifier: String) -> [String: Any] {
        var d: [String: Any] = [
            "Type": "Accessory Source",
            "Name": name,
            "Accessory Category": category,
            "Accessory Identifier": identifier,
            "Current Capacity": pct,
            "Max Capacity": 100,
            "Is Present": true,
            "Is Charging": charging,
            "Is Charged": pct >= 100,
            "Power Source State": charging ? "AC Power" : "Battery Power",
            "Part Identifier": "Single",
            "Low Warn Level": 20,
            "Time to Full Charge": -1,
        ]
        if isPhone(platform) {
            d["Transport Type"] = "USB"
            d["Vendor ID"] = appleUSBVendor
            d["Product ID"] = phoneGlyphProduct
        } else {
            d["Transport Type"] = "Bluetooth"
            d["Vendor ID"] = appleVendor
            d["Product ID"] = computerProduct(for: platform)
        }
        return d
    }

    private func publish(_ peerId: String) {
        guard var e = entries[peerId] else { return }
        guard Self.enabled, let peer = PeerBus.shared.peers().first(where: { $0.id == peerId }) else {
            release(&e); entries[peerId] = e; return
        }
        if e.handle == nil {
            var h: OpaquePointer?
            guard IOPSCreate(&h) == 0, h != nil else { return }
            e.handle = h
        }
        let d = Self.details(name: peer.name, platform: peer.platform, pct: e.pct, charging: e.charging, identifier: Self.identifier(peerId))
        _ = IOPSSet(e.handle, d as CFDictionary)
        entries[peerId] = e
    }

    private func remove(_ peerId: String) {
        guard var e = entries[peerId] else { return }
        release(&e)
        entries[peerId] = nil
    }

    private func release(_ e: inout Entry) {
        if let h = e.handle { _ = IOPSRelease(h); e.handle = nil }
    }

    /// The same id for the same phone every time, so a reconnect is the same device to the widget.
    private static func identifier(_ peerId: String) -> String {
        let b = Array(SHA256.hash(data: Data("AirBridge|\(peerId)".utf8)).prefix(16))
        return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15])).uuidString
    }
}

// The three private IOKit functions (declared in IOPowerSourcesPrivate.h; exported by IOKit, not in the SDK).
@_silgen_name("IOPSCreatePowerSource") private func IOPSCreate(_ out: UnsafeMutablePointer<OpaquePointer?>) -> Int32
@_silgen_name("IOPSSetPowerSourceDetails") private func IOPSSet(_ ps: OpaquePointer?, _ details: CFDictionary) -> Int32
@_silgen_name("IOPSReleasePowerSource") private func IOPSRelease(_ ps: OpaquePointer?) -> Int32
