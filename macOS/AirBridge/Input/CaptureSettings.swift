import AppKit
import Foundation

/// How hard the user has to push through an edge before the cursor crosses (protocol.md §8.2 Capture intent).
nonisolated enum CaptureStrength: Int, CaseIterable, Identifiable, Sendable {
    case easy = 60, normal = 100, firm = 160

    var id: Int { rawValue }
    var label: String {
        switch self {
        case .easy: return "Easy"
        case .normal: return "Normal"
        case .firm: return "Firm"
        }
    }
}

/// User-tunable capture settings, stored in UserDefaults (edited in DisplayArrangementView).
nonisolated struct CaptureSettings: Sendable {
    static let thresholdKey = "AirBridge.capture.threshold"
    static let requireOptionKey = "AirBridge.capture.requireOption"
    static func edgeKey(_ e: ScreenEdge) -> String { "AirBridge.capture.edge.\(e.rawValue)" }
    /// The colour of the glow on an edge ("#RRGGBB"); blue like the phone's accent unless the user picks another.
    static let edgeColorKey = "AirBridge.capture.edgeColor"
    static let defaultEdgeColorHex = "#5B9DFF"

    static func hex(_ c: NSColor) -> String {
        let s = c.usingColorSpace(.sRGB) ?? c
        func b(_ v: CGFloat) -> Int { max(0, min(255, Int((v * 255).rounded()))) }
        return String(format: "#%02X%02X%02X", b(s.redComponent), b(s.greenComponent), b(s.blueComponent))
    }

    static func color(fromHex hex: String) -> NSColor {
        var h = hex.trimmingCharacters(in: .whitespaces)
        if h.hasPrefix("#") { h.removeFirst() }
        guard h.count == 6, let v = UInt32(h, radix: 16) else { return color(fromHex: defaultEdgeColorHex) }
        return NSColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }

    static func edgeColor() -> NSColor {
        color(fromHex: UserDefaults.standard.string(forKey: edgeColorKey) ?? defaultEdgeColorHex)
    }

    /// Window over which outward motion is summed; a pause this long empties the accumulator.
    static let window: Double = 0.5
    /// After the cursor comes back through an edge, that edge ignores pushes this long.
    static let returnCooldown: Double = 0.7
    /// No capture while a drag is in progress or this long after one ends.
    static let dragCooldown: Double = 1.0

    var threshold: Double = 160
    var requireOption = false
    var disabledEdges: Set<ScreenEdge> = []

    func enabled(_ e: ScreenEdge) -> Bool { !disabledEdges.contains(e) }

    static func load() -> CaptureSettings {
        let d = UserDefaults.standard
        var s = CaptureSettings()
        let t = d.double(forKey: thresholdKey)
        if t > 0 { s.threshold = t }
        s.requireOption = d.bool(forKey: requireOptionKey)
        for e in ScreenEdge.allCases where d.object(forKey: edgeKey(e)) != nil && !d.bool(forKey: edgeKey(e)) {
            s.disabledEdges.insert(e)
        }
        return s
    }
}
