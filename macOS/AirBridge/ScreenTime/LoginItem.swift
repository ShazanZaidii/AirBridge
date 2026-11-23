import ServiceManagement

/// "Open at login": AirBridge starts with the Mac, so this Mac's screen time counts from the start of the day and phones
/// reconnect without opening the app. Switched on once by default; the user can turn it off (here or in System Settings,
/// General, Login Items), and that choice is kept.
@MainActor enum LoginItem {
    private static let defaultAppliedKey = "LoginItemDefaultApplied"

    static var isOn: Bool { SMAppService.mainApp.status == .enabled }

    static func set(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch { NSLog("[AirBridge] login item: %@", error.localizedDescription) }
    }

    /// The first time this build runs: turn it on once. Never again after that, so turning it off sticks.
    static func applyDefault() {
        let d = UserDefaults.standard
        guard !d.bool(forKey: defaultAppliedKey) else { return }
        d.set(true, forKey: defaultAppliedKey)
        if SMAppService.mainApp.status != .enabled { set(true) }
    }
}
