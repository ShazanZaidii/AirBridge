import AppKit
import SwiftUI

// AirBridge's charcoal design language, shared by its windows (Screen Time, Notifications, …): neutral zinc greys only, a
// page and a slightly lifted card surface, hairline borders, white text with two quieter levels, white selected pills
// (charcoal in light mode). Colour comes only from content (app icons).

enum Ink {
    private static func dyn(_ dark: UInt32, _ light: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { a in
            let v = a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        })
    }
    static let page = dyn(0x111113, 0xF4F4F5)
    static let surface = dyn(0x18181B, 0xFFFFFF)
    static let raised = dyn(0x202024, 0xF4F4F5)
    static let hairline = dyn(0x27272B, 0xE4E4E7)
    static let text = dyn(0xF4F4F5, 0x18181B)
    static let secondary = dyn(0xA1A1AA, 0x52525B)
    static let tertiary = dyn(0x71717A, 0xA1A1AA)
    static let track = dyn(0x232327, 0xEDEDEF)
    /// Device groups, by lightness.
    static let mac = dyn(0xE4E4E7, 0x27272A)
    /// The picked segment: a white pill in dark mode, a charcoal one in light mode.
    static let pill = dyn(0xFFFFFF, 0x27272A)
    static let onPill = dyn(0x000000, 0xFFFFFF)
    static let mobile = dyn(0x8E8E96, 0x71717A)
    static let other = dyn(0x4E4E56, 0xB4B4BC)

    static func series(_ g: STFilter) -> Color {
        switch g {
        case .mobile: mobile
        case .mac: mac
        default: other
        }
    }

    /// Categories in five steps of grey, most used lightest.
    static func step(_ i: Int) -> Color {
        [text, dyn(0xC4C4CC, 0x3F3F46), mobile, dyn(0x63636B, 0x9A9AA2), other][min(max(0, i), 4)]
    }

    static let radius: CGFloat = 14
}

/// Small spaced capitals.
struct Eyebrow: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.system(size: 10.5, weight: .semibold)).tracking(1.0).foregroundStyle(Ink.tertiary)
    }
}

/// A flat card: lifted surface, one hairline.
struct Panel<Content: View>: View {
    var padding: CGFloat = 24
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).fill(Ink.surface))
            .overlay(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
    }
}

struct PanelTitle: View {
    let title: String
    var detail: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(Ink.text)
            if let detail { Text(detail).font(.system(size: 12)).foregroundStyle(Ink.tertiary) }
        }
    }
}

/// Segmented choice: the picked one is a white pill (dark text) that slides; the rest are quiet text.
struct Segmented<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let title: (T) -> String
    var small = false
    @Namespace private var ns
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.self) { item in
                let on = item == selection
                Button {
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { selection = item }
                } label: {
                    Text(title(item))
                        .font(.system(size: small ? 11.5 : 12.5, weight: on ? .semibold : .medium))
                        .foregroundStyle(on ? Ink.onPill : Ink.secondary)
                        .padding(.horizontal, small ? 11 : 14)
                        .padding(.vertical, small ? 5 : 6)
                        .background {
                            if on { Capsule().fill(Ink.pill).matchedGeometryEffect(id: "seg", in: ns) }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(3)
        .background(Capsule().fill(Ink.raised))
        .overlay(Capsule().strokeBorder(Ink.hairline, lineWidth: 1))
    }
}

