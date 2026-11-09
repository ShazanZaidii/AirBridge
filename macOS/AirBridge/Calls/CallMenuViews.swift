import AppKit
import SwiftUI

/// The menu bar's "call in progress" rows: one click brings the call window (or the banner) back.
struct ActiveCallsMenuSection: View {
    @ObservedObject private var board = CallPanels.shared.board

    var body: some View {
        if !board.calls.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(board.calls) { CallMenuRow(ui: $0) }
            }
            .buttonStyle(MenuRowButtonStyle())
            .padding(.horizontal, -Theme.sm)
            .transition(.opacity)
        }
    }
}

private struct CallMenuRow: View {
    @ObservedObject var ui: CallUI

    var body: some View {
        Button { CallPanels.shared.showCall(phone: ui.phoneId) } label: {
            HStack(spacing: 10) {
                CallerAvatar(photo: ui.photo, name: ui.call.name, size: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(ui.title).font(.callout.weight(.medium)).lineLimit(1)
                    status.font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: ui.call.state == .ringing ? "phone.arrow.down.left" : "phone")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        }
        .help(ui.call.state == .ringing ? "Show the incoming call" : "Show the call")
        .accessibilityLabel(ui.call.state == .ringing ? "Incoming call from \(ui.title)" : "Call with \(ui.title)")
    }

    @ViewBuilder private var status: some View {
        switch ui.call.state {
        case .ringing: Text("Incoming call · \(ui.phoneName)")
        case .held: Text("On hold · \(ui.phoneName)")
        default:
            if let since = ui.call.since {
                TimelineView(.periodic(from: since, by: 1)) { ctx in
                    Text("\(CallerText.duration(ctx.date.timeIntervalSince(since))) · \(ui.phoneName)").monospacedDigit()
                }
            } else {
                Text("On a call · \(ui.phoneName)")
            }
        }
    }
}

/// Settings: "Ring on this Mac" (Smart / Always / Never).
struct RingModeSetting: View {
    @AppStorage(RingMode.key) private var mode = RingMode.smart.rawValue

    @Namespace private var pill
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Color.primary.opacity(0.07))
                Image(systemName: "bell.and.waves.left.and.right").font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
            }
            .frame(width: 26, height: 26)
            VStack(alignment: .leading, spacing: 5) {
                Text("Ring on this Mac").font(.callout)
                // Three-way switch: the highlight slides between choices.
                HStack(spacing: 2) {
                    ForEach([RingMode.smart, .always, .never], id: \.rawValue) { m in
                        let on = mode == m.rawValue
                        Button {
                            withAnimation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.72)) { mode = m.rawValue }
                        } label: {
                            Text(m == .smart ? "Smart" : (m == .always ? "Always" : "Never"))
                                .font(.caption.weight(on ? .semibold : .regular))
                                .foregroundStyle(on ? Color(nsColor: .windowBackgroundColor) : .primary)
                                .padding(.horizontal, 11).padding(.vertical, 4)
                                .background {
                                    if on { Capsule().fill(Color.primary.opacity(0.85)).matchedGeometryEffect(id: "pill", in: pill) }
                                }
                                .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                .padding(2)
                .background(Capsule().fill(Color.primary.opacity(0.07)))
                Text(explanation).font(.caption2).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }

    private var explanation: String {
        switch RingMode(rawValue: mode) ?? .smart {
        case .smart: "Rings here when you're using this Mac or your phone is next to it, and then your phone stays quiet. Otherwise calls show here without a sound."
        case .always: "Every call rings here, and your phone stays quiet."
        case .never: "Calls show here without a sound."
        }
    }
}
