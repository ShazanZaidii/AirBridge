import AppKit
import CoreGraphics

/// Is the user at this Mac (§13.4)? Last keyboard / mouse / trackpad input, and whether the screen is awake and
/// unlocked. Nothing is recorded: only "how long ago", read at the moment a call rings.
enum MacPresence {
    /// Seconds since the last input event of any kind in this login session.
    static func idleSeconds() -> TimeInterval? {
        guard let any = CGEventType(rawValue: ~0) else { return nil }
        let s = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: any)
        return s.isFinite && s >= 0 ? s : nil
    }

    /// The screen is on and not locked (a locked or sleeping Mac is not "in use").
    static func available() -> Bool {
        if CGDisplayIsAsleep(CGMainDisplayID()) != 0 { return false }
        if let d = CGSessionCopyCurrentDictionary() as? [String: Any] {
            if (d["CGSSessionScreenIsLocked"] as? Bool) == true { return false }
            if (d[kCGSessionOnConsoleKey as String] as? Bool) == false { return false }
        }
        return true
    }
}
