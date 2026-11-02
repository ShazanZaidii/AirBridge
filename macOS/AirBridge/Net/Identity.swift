import Foundation
import CoreGraphics
import SystemConfiguration

/// This device's protocol identity (§2.1).
nonisolated enum Identity {
    private static let idKey = "AirBridgeDeviceId"
    private static let nameKey = "AirBridgeDeviceName"

    static let id: String = {
        if let s = UserDefaults.standard.string(forKey: idKey), UUID(uuidString: s) != nil { return s }
        let s = UUID().uuidString.lowercased()
        UserDefaults.standard.set(s, forKey: idKey)
        return s
    }()

    /// User override, otherwise the computer name.
    static var name: String {
        if let s = UserDefaults.standard.string(forKey: nameKey), !s.isEmpty { return s }
        return (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? "Mac"
    }

    static func setName(_ new: String) {
        let trimmed = new.trimmingCharacters(in: .whitespacesAndNewlines)
        UserDefaults.standard.set(trimmed.isEmpty ? nil : String(trimmed.prefix(63)), forKey: nameKey)
    }

    /// Pixel size of the primary (menu-bar) display, for `hello.screen`.
    static var screenPixels: (w: Int, h: Int) {
        let display = CGMainDisplayID()
        if let mode = CGDisplayCopyDisplayMode(display) {
            return (mode.pixelWidth, mode.pixelHeight)
        }
        return (CGDisplayPixelsWide(display), CGDisplayPixelsHigh(display))
    }

    static func hello(nonce: Data, paired: Bool?) -> [String: Any] {
        let screen = screenPixels
        var msg: [String: Any] = [
            "t": "hello", "v": Proto.version, "id": id, "name": name, "plat": Proto.platform,
            "app": Proto.appVersion, "caps": Proto.caps, "nonce": nonce.base64EncodedString(),
            "screen": ["w": screen.w, "h": screen.h],
        ]
        if let paired { msg["paired"] = paired }
        return msg
    }
}
