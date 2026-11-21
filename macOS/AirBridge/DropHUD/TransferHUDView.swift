import SwiftUI

/// Shared motion for the dock and the cards: soft springs, nothing bouncy enough to distract.
enum DropMotion {
    static let card = Animation.spring(response: 0.42, dampingFraction: 0.8)
    static let pop = Animation.spring(response: 0.32, dampingFraction: 0.62)
    static let dock = Animation.spring(response: 0.36, dampingFraction: 0.74)
}

/// Stack of transfer cards, newest on top.
struct TransferHUDView: View {
    @ObservedObject var hud: TransferHUD
    /// Reports the stack's natural height so the panel can fit it.
    var onHeight: (CGFloat) -> Void = { _ in }
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .trailing, spacing: 8) {
            ForEach(hud.cards) { card in
                TransferCardView(card: card, hud: hud)
                    .transition(reduceMotion ? .opacity : .asymmetric(
                        insertion: .move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.94, anchor: .top)),
                        removal: .opacity.combined(with: .scale(scale: 0.9, anchor: .trailing))))
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 6)
        .padding(.bottom, 18)
        .animation(reduceMotion ? nil : DropMotion.card, value: hud.cards.map(\.id))
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { onHeight($0) }
        .frame(width: HUDPanelController.width)
        .frame(maxHeight: .infinity, alignment: .top)
    }
}

struct TransferCardView: View {
    let card: HUDCard
    @ObservedObject var hud: TransferHUD
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            TransferRing(card: card)
                .frame(width: 38, height: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.callout.weight(.semibold))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(peerLine)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                detailLine
                actions
                    .padding(.top, actionsVisible ? 6 : 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            closeButton
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 11)
        .frame(width: HUDPanelController.width - 24)
        .background(alignment: .bottom) { progressHairline }
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
        .shadow(color: .black.opacity(0.16), radius: 12, y: 5)
        .onHover { h in withAnimation(reduceMotion ? nil : .easeOut(duration: 0.15)) { hovering = h } }
        .animation(reduceMotion ? nil : DropMotion.card, value: stageKey)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(accessibilityText)
    }

    // MARK: Text

    private var outgoing: Bool { card.direction == .outgoing }

    private var title: String {
        if case .countdown = card.stage { return "Sending \(card.title)" }
        return card.title
    }

    private var peerLine: String { outgoing ? "To \(card.peerName)" : "From \(card.peerName)" }

    @ViewBuilder private var detailLine: some View {
        switch card.stage {
        case .countdown(_, let deadline):
            TimelineView(.periodic(from: .now, by: 0.25)) { ctx in
                let left = max(1, Int(ceil(deadline.timeIntervalSince(ctx.date))))
                Text("Starts in \(left) s").font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
        case .launching:
            Text("Starting…").font(.caption).foregroundStyle(.secondary)
        case .transfer(let s):
            switch s.phase {
            case .waiting:
                Text(outgoing ? "Waiting for \(s.peerName) to accept…" : "Getting ready…")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            case .active:
                Text(progressText(s)).font(.caption).foregroundStyle(.secondary).monospacedDigit().lineLimit(1)
            case .done:
                Text("\(outgoing ? "Sent" : "Received") · \(TransferText.bytes(s.total))")
                    .font(.caption).foregroundStyle(.secondary)
            case .failed(let why):
                Text(why).font(.caption).foregroundStyle(.secondary)
                    .lineLimit(2).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func progressText(_ s: TransferStatus) -> String {
        var parts = ["\(TransferText.bytes(s.done)) of \(TransferText.bytes(s.total))"]
        if s.bytesPerSec > 0 { parts.append(TransferText.speed(s.bytesPerSec)) }
        if let left = TransferText.timeLeft(total: s.total, done: s.done, bytesPerSec: s.bytesPerSec) { parts.append(left) }
        return parts.joined(separator: " · ")
    }

    // MARK: Buttons

    private var actionsVisible: Bool {
        switch card.stage {
        case .countdown, .launching: return true
        case .transfer(let s):
            switch s.phase {
            case .waiting, .active: return true
            case .done: return !outgoing && !s.savedPaths.isEmpty
            case .failed: return card.canRetry
            }
        }
    }

    @ViewBuilder private var actions: some View {
        if actionsVisible {
            HStack(spacing: 6) {
                switch card.stage {
                case .countdown:
                    Button("Undo") { hud.undo(card.id) }.buttonStyle(HUDButtonStyle(prominent: true))
                case .launching:
                    EmptyView()
                case .transfer(let s):
                    switch s.phase {
                    case .waiting, .active:
                        Button("Cancel") { hud.cancel(card) }.buttonStyle(HUDButtonStyle())
                    case .done:
                        Button("Show in Finder") { hud.showInFinder(card) }.buttonStyle(HUDButtonStyle())
                        Button("Open") { hud.open(card) }.buttonStyle(HUDButtonStyle())
                    case .failed:
                        Button("Retry") { hud.retry(card) }.buttonStyle(HUDButtonStyle())
                    }
                }
            }
            .transition(.opacity)
        }
    }

    private var closeButton: some View {
        Button { hud.dismiss(card.id) } label: {
            Image(systemName: "xmark")
                .font(.system(size: 9, weight: .bold))
                .frame(width: 18, height: 18)
                .background(Circle().fill(Color.primary.opacity(hovering ? 0.1 : 0.05)))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.secondary)
        .opacity(hovering || card.isFinished ? 1 : 0.0)
        .help(card.isFinished ? "Close" : "Hide this card (the transfer keeps going)")
        .accessibilityLabel("Close")
    }

    // MARK: Progress

    private var fraction: Double? {
        guard case .transfer(let s) = card.stage, s.phase == .active, s.total > 0 else { return nil }
        return min(1, Double(s.done) / Double(s.total))
    }

    @ViewBuilder private var progressHairline: some View {
        if let f = fraction {
            GeometryReader { g in
                Rectangle()
                    .fill(Color.primary.opacity(0.22))
                    .frame(width: g.size.width * f, height: 2)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.3), value: f)
        }
    }

    /// Changes whenever the card moves to a different stage, to animate the layout change.
    private var stageKey: String {
        switch card.stage {
        case .countdown: return "countdown"
        case .launching: return "launching"
        case .transfer(let s):
            switch s.phase {
            case .waiting: return "waiting"
            case .active: return "active"
            case .done: return "done"
            case .failed: return "failed" + (card.canRetry ? "r" : "")
            }
        }
    }

    private var accessibilityText: String {
        var t = "\(outgoing ? "Sending" : "Receiving") \(card.title), \(peerLine)"
        if let f = fraction { t += ", \(Int(f * 100)) percent" }
        if card.isDone { t = "\(outgoing ? "Sent" : "Received") \(card.title), \(peerLine)" }
        if let why = card.failure { t += ", \(why)" }
        return t
    }
}

/// Ring around a small glyph: fills with progress, drains during the undo countdown, spins while waiting,
/// and turns into a tick or an exclamation mark at the end.
struct TransferRing: View {
    let card: HUDCard
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle().stroke(Color.primary.opacity(0.12), lineWidth: 3)
            arc
            glyph
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder private var arc: some View {
        switch card.stage {
        case .countdown(let started, let deadline):
            TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion)) { ctx in
                let total = max(0.1, deadline.timeIntervalSince(started))
                let left = max(0, min(1, deadline.timeIntervalSince(ctx.date) / total))
                ringShape(left)
            }
        case .launching:
            spinner
        case .transfer(let s):
            switch s.phase {
            case .waiting: spinner
            case .active: ringShape(s.total > 0 ? Double(s.done) / Double(s.total) : 0)
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.3), value: s.done)
            case .done: ringShape(1)
            case .failed: EmptyView()
            }
        }
    }

    private func ringShape(_ f: Double) -> some View {
        Circle()
            .trim(from: 0, to: max(0.001, f))
            .stroke(Color.primary.opacity(0.8), style: StrokeStyle(lineWidth: 3, lineCap: .round))
            .rotationEffect(.degrees(-90))
    }

    private var spinner: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            Circle()
                .trim(from: 0, to: 0.28)
                .stroke(Color.primary.opacity(0.6), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(t.truncatingRemainder(dividingBy: 1.1) / 1.1 * 360))
        }
    }

    @ViewBuilder private var glyph: some View {
        switch card.stage {
        case .countdown(_, let deadline):
            TimelineView(.periodic(from: .now, by: 0.25)) { ctx in
                Text("\(max(1, Int(ceil(deadline.timeIntervalSince(ctx.date)))))")
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .contentTransition(.numericText(countsDown: true))
            }
        default:
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .semibold))
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.bounce, value: card.isDone)
        }
    }

    private var symbol: String {
        if card.isDone { return "checkmark" }
        if card.failure != nil { return card.wasCancelledHere ? "xmark" : "exclamationmark" }
        return card.direction == .outgoing ? "arrow.up" : "arrow.down"
    }
}

/// Small capsule button for cards and the dock.
struct HUDButtonStyle: ButtonStyle {
    var prominent = false
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        let base: Double = prominent ? 0.85 : 0.08
        return configuration.label
            .font(.caption.weight(.medium))
            .foregroundStyle(prominent ? AnyShapeStyle(.background) : AnyShapeStyle(.primary))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(Color.primary.opacity(configuration.isPressed ? base + (prominent ? -0.15 : 0.1)
                                                             : (hovering ? base + (prominent ? 0.1 : 0.05) : base))))
            .contentShape(Capsule())
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
            .onHover { hovering = $0 }
    }
}
