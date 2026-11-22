import SwiftUI

/// One setting: an icon tile, a title (and optional hint), and a soft switch whose knob springs across and
/// shows a tick when on. Calm colours, playful motion; the whole row is the click target.
struct SettingToggleRow: View {
    let icon: String
    let title: String
    var hint: String? = nil
    @Binding var isOn: Bool
    var enabled: Bool = true
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button {
            withAnimation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.62)) { isOn.toggle() }
        } label: {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(Color.primary.opacity(isOn ? 0.14 : 0.07))
                    Image(systemName: icon)
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(isOn ? .primary : .secondary)
                        .symbolEffect(.bounce, value: isOn)
                }
                .frame(width: 26, height: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.callout).foregroundStyle(.primary).lineLimit(2)
                    if let hint { Text(hint).font(.caption2).foregroundStyle(.secondary).lineLimit(2) }
                }
                Spacer(minLength: 8)
                SoftSwitch(isOn: isOn)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.primary.opacity(hovering ? 0.06 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hovering = h } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(isOn ? "On" : "Off")
        .accessibilityAddTraits(.isButton)
    }
}

/// The switch itself: graphite track when off, near-white when on, knob with a small tick.
struct SoftSwitch: View {
    let isOn: Bool
    var body: some View {
        ZStack(alignment: isOn ? .trailing : .leading) {
            Capsule().fill(isOn ? Color.primary.opacity(0.85) : Color.primary.opacity(0.14))
            Circle()
                .fill(isOn ? Color(nsColor: .windowBackgroundColor) : Color.white)
                .shadow(color: .black.opacity(0.25), radius: 1.5, y: 0.5)
                .overlay(
                    Image(systemName: "checkmark")
                        .font(.system(size: 7.5, weight: .heavy))
                        .foregroundStyle(Color.primary.opacity(0.85))
                        .opacity(isOn ? 1 : 0)
                        .scaleEffect(isOn ? 1 : 0.4)
                )
                .padding(2)
        }
        .frame(width: 34, height: 20)
    }
}

/// A small pill button for secondary tools (Displays, Clipboard filters…).
struct ToolPill: View {
    let title: String
    let icon: String
    @State private var hovering = false
    var body: some View {
        Label(title, systemImage: icon)
            .font(.caption)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity)
            .background(Capsule().fill(Color.primary.opacity(hovering ? 0.10 : 0.05)))
            .overlay(Capsule().strokeBorder(Color.primary.opacity(0.08)))
            .contentShape(Capsule())
            .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hovering = h } }
    }
}

/// A setting that opens something: icon tile, title and hint, and a small labelled button on the right.
struct SettingActionRow: View {
    let icon: String
    let title: String
    var hint: String? = nil
    let action: String
    let perform: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: perform) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Color.primary.opacity(0.07))
                    Image(systemName: icon).font(.system(size: 12.5, weight: .medium)).foregroundStyle(.secondary)
                }
                .frame(width: 26, height: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.callout).foregroundStyle(.primary).lineLimit(2)
                    if let hint { Text(hint).font(.caption2).foregroundStyle(.secondary).lineLimit(2) }
                }
                Spacer(minLength: 8)
                Text(action).font(.caption.weight(.medium))
                    .padding(.horizontal, 9).padding(.vertical, 3)
                    .background(Capsule().fill(Color.primary.opacity(0.1)))
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.primary.opacity(hovering ? 0.06 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hovering = h } }
        .accessibilityLabel(title)
    }
}
