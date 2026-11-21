import Foundation
import CoreGraphics
import SystemConfiguration
import CryptoKit
import IOKit

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

    /// A fingerprint of this Mac that survives reinstalling the app (a hash of the hardware UUID, never the UUID itself).
    /// `id` is new whenever the app's data is cleared, so a device that remembers only `id` keeps a stale entry per reset;
    /// with this it can tell "same Mac, new install" from "a different Mac with the same name". Nil if unreadable.
    static let hw: String? = {
        let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPlatformExpertDevice"))
        guard service != 0 else { return nil }
        defer { IOObjectRelease(service) }
        guard let uuid = IORegistryEntryCreateCFProperty(service, "IOPlatformUUID" as CFString, kCFAllocatorDefault, 0)?
            .takeRetainedValue() as? String, !uuid.isEmpty else { return nil }
        return SHA256.hash(data: Data("airbridge-hw|\(uuid)".utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
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

    static func hello(nonce: Data, publicKey: Data, paired: Bool?) -> [String: Any] {
        let screen = screenPixels
        var msg: [String: Any] = [
            "t": "hello", "v": Proto.version, "id": id, "name": name, "plat": Proto.platform,
            "app": Proto.appVersion, "caps": Proto.caps, "nonce": nonce.base64EncodedString(),
            "pk": publicKey.base64EncodedString(), "screen": ["w": screen.w, "h": screen.h],
        ]
        if let paired { msg["paired"] = paired }
        if let hw { msg["hw"] = hw }
        return msg
    }
}
