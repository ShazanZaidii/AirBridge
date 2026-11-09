import SwiftUI

/// The dock body: a rounded shelf with one bubble per connected device plus AirWeb. Bubbles swell and lean
/// towards the dragged files; the one under the cursor lights up.
struct DropDockView: View {
    @ObservedObject var model: DockModel
    weak var dock: DropDock?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var layout: DockLayout { model.layout }

    var body: some View {
        ZStack(alignment: .topLeading) {
            header
            if layout.mode == .none { emptyHint }
            ForEach(Array(layout.slots.enumerated()), id: \.offset) { _, slot in
                DockBubble(slot: slot, peer: peer(slot), cursor: model.cursor, hot: model.hot == slot.kind,
                           took: model.took == slot.kind, clickable: model.held, single: layout.mode == .single,
                           reduceMotion: reduceMotion) { dock?.pick(slot.kind) }
            }
        }
        .frame(width: layout.size.width, height: layout.size.height, alignment: .topLeading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.primary.opacity(0.09)))
        .shadow(color: .black.opacity(0.2), radius: 16, y: 7)
        .scaleEffect(model.shown ? 1 : (reduceMotion ? 1 : 0.86), anchor: .top)
        .offset(y: model.shown || reduceMotion ? 0 : -16)
        .opacity(model.shown ? 1 : 0)
        .padding(DropDock.margin)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .animation(reduceMotion ? nil : DropMotion.dock, value: layout)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Send with AirBridge")
    }

    private func peer(_ slot: DockSlot) -> PeerInfo? {
        if case .device(let id) = slot.kind { return model.peer(id) }
        return nil
    }

    private var headerText: String {
        if model.held { return layout.mode == .none ? "Send \(model.itemTitle) with AirWeb" : "Choose where to send \(model.itemTitle)" }
        if let hot = model.hot {
            switch hot {
            case .airWeb: return "Share \(model.itemTitle) with AirWeb"
            case .device(let id): return "Send \(model.itemTitle) to \(model.peer(id)?.name ?? "device")"
            }
        }
        if layout.mode == .single, let p = model.targets.first { return "Drop to send to \(p.name)" }
        return model.itemTitle.isEmpty ? "Drop to send" : "Send \(model.itemTitle)"
    }

    private var header: some View {
        HStack(spacing: 6) {
            Text(headerText)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
                .contentTransition(.opacity)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: headerText)
            if model.held {
                Button { dock?.dismissHeld() } label: {
                    Image(systemName: "xmark").font(.system(size: 8, weight: .bold)).frame(width: 16, height: 16)
                        .background(Circle().fill(Color.primary.opacity(0.07)))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel("Cancel")
            }
        }
        .frame(width: layout.size.width - 2 * DockLayout.padding, height: DockLayout.header)
        .offset(x: DockLayout.padding, y: DockLayout.padding)
    }

    private var emptyHint: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("No devices connected").font(.callout.weight(.semibold))
            Text("Pair one from the AirBridge menu, or share with AirWeb.")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(width: 160, alignment: .leading)
        .offset(x: DockLayout.padding + 4, y: DockLayout.padding + DockLayout.header + DockLayout.gap + 2)
    }
}

/// One round target with its name underneath.
struct DockBubble: View {
    let slot: DockSlot
    let peer: PeerInfo?
    let cursor: CGPoint?
    let hot: Bool
    let took: Bool
    let clickable: Bool
    let single: Bool
    let reduceMotion: Bool
    let onPick: () -> Void
    @State private var hovering = false

    var body: some View {
        let pull = reduceMotion ? 0 : DockLayout.pull(of: slot, cursor: cursor)
        let lean = reduceMotion ? .zero : DockLayout.lean(of: slot, cursor: cursor)
        let scale = took ? 1.18 : 1 + 0.1 * pull + (hot || (clickable && hovering) ? 0.08 : 0)
        let d = slot.diameter
        VStack(spacing: DockLayout.gap) {
            ZStack {
                Circle()
                    .fill(Color.primary.opacity(hot || took ? 0.16 : 0.06 + 0.05 * pull))
                Circle()
                    .strokeBorder(Color.primary.opacity(hot || took ? 0.55 : 0.12 + 0.2 * pull), lineWidth: hot ? 1.5 : 1)
                Image(systemName: symbol)
                    .font(.system(size: d * 0.4, weight: .regular))
                    .foregroundStyle(.primary.opacity(0.85))
                    .symbolEffect(.bounce, value: took)
            }
            .frame(width: d, height: d)
            .scaleEffect(scale)
            .offset(lean)
            Text(name)
                .font(single && slot.kind != .airWeb ? .caption.weight(.semibold) : .caption2.weight(.medium))
                .foregroundStyle(hot ? .primary : .secondary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(width: max(d + 22, single && slot.kind != .airWeb ? 124 : 84), height: DockLayout.nameHeight)
        }
        .animation(reduceMotion ? nil : (took ? DropMotion.pop : DropMotion.dock), value: scale)
        .animation(reduceMotion ? nil : DropMotion.dock, value: lean)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture { if clickable { onPick() } }
        // Centre the bubble (not the whole column) on the slot's centre.
        .position(x: slot.center.x, y: slot.center.y + (DockLayout.gap + DockLayout.nameHeight) / 2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(slot.kind == .airWeb ? "AirWeb" : "Send to \(name)")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { if clickable { onPick() } }
    }

    private var symbol: String {
        if slot.kind == .airWeb { return "qrcode.viewfinder" }
        return platformIcon(peer?.platform ?? "")
    }

    private var name: String {
        if slot.kind == .airWeb { return "AirWeb" }
        return peer?.name ?? "Device"
    }
}
