import AppKit

/// Pure mapping from the mirror view to the phone (§8.2 conventions), kept separate for tests.
nonisolated enum MirrorGeometry {
    /// Target's `ptr_scroll px:true` gain (TargetController.GAIN on Android).
    static let targetGain = 1.6

    /// View point (flipped, origin top-left) → `ptr_abs` x/y in 0…1, rounded to 4 decimals (§9).
    static func normalized(_ p: CGPoint, in size: CGSize) -> (x: Double, y: Double) {
        guard size.width > 0, size.height > 0 else { return (0.5, 0.5) }
        let x = min(1, max(0, Double(p.x / size.width)))
        let y = min(1, max(0, Double(p.y / size.height)))
        return ((x * 10000).rounded() / 10000, (y * 10000).rounded() / 10000)
    }

    /// Trackpad scroll in view points → wire `ptr_scroll` pixels, so the phone's content follows the fingers 1:1
    /// as seen in the window. `scrollingDelta` keeps the Mac's natural-scrolling sign (positive = content down);
    /// the wire describes content motion (+dy = content up), hence the minus.
    static func scroll(dx: Double, dy: Double, viewWidth: Double, phoneWidth: Double) -> (dx: Double, dy: Double) {
        let k = viewWidth > 0 && phoneWidth > 0 ? phoneWidth / viewWidth / targetGain : 1
        return ((-dx * k * 100).rounded() / 100, (-dy * k * 100).rounded() / 100)
    }

    /// Protocol `mods` bitmask from AppKit modifier flags (§8.3).
    static func mods(_ f: NSEvent.ModifierFlags) -> Int {
        var m = 0
        if f.contains(.shift) { m |= 1 }
        if f.contains(.control) { m |= 2 }
        if f.contains(.option) { m |= 4 }
        if f.contains(.command) { m |= 8 }
        if f.contains(.capsLock) { m |= 16 }
        return m
    }

    /// Printable text of a key press, or nil for shortcuts and function keys.
    static func text(_ chars: String?, mods: Int) -> String? {
        guard mods & (2 | 8) == 0, let s = chars, !s.isEmpty else { return nil }
        let ok = s.unicodeScalars.allSatisfy { $0.value >= 0x20 && $0.value != 0x7F && !(0xF700...0xF8FF).contains($0.value) }
        return ok ? s : nil
    }
}
