import SwiftUI
import Combine
import CoreGraphics

/// Ready peers, mirrored on the main thread for SwiftUI.
final class PeerDirectory: ObservableObject {
    static let shared = PeerDirectory()
    @Published var peers: [PeerInfo] = []
}

/// Design tokens: neutral graphite + one blue accent, matching the phone app.
private enum Tok {
    static func hex(_ v: UInt32) -> Color {
        Color(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255,
              blue: Double(v & 0xFF) / 255, opacity: 1)
    }
    static let background = hex(0x0F0F11)
    static let card = hex(0x1B1B1E)
    static let muted = hex(0x27272B)
    static let mutedFg = hex(0xA1A1A8)
    static let fg = hex(0xF4F4F6)
    static let primary = hex(0x242428)
    static let secondary = hex(0x34343A)
    static let border = hex(0x4A4A50)
    static let accent = hex(0x5B9DFF)
    static let onAccent = hex(0x0F0F11)
    static let destructive = hex(0xE5484D)

    static let xs: CGFloat = 4, sm: CGFloat = 8, md: CGFloat = 16, lg: CGFloat = 24
    static let radius: CGFloat = 8, cardRadius: CGFloat = 12
    static let transition = Animation.easeOut(duration: 0.2)
}

/// Lets the user place each controllable peer on an outer edge of the real display layout, shows which
/// peer each edge leads to (§8.6), the lease state of this Mac, and the push-through settings (§8.2).
struct DisplayArrangementView: View {
    @ObservedObject private var directory = PeerDirectory.shared
    @State private var geo = DisplayGeometry.current()
    @State private var placements = Layout.shared.all()
    @State private var dragOffsets: [String: CGSize] = [:]
    @State private var hovered: String?
    @State private var status = ControlStatus.shared.snapshot
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @AppStorage(CaptureSettings.thresholdKey) private var threshold: Double = 100
    @AppStorage(CaptureSettings.requireOptionKey) private var requireOption = false
    @AppStorage(CaptureSettings.edgeColorKey) private var edgeColorHex = CaptureSettings.defaultEdgeColorHex
    @AppStorage(CaptureSettings.edgeKey(.left)) private var leftOn = true
    @AppStorage(CaptureSettings.edgeKey(.right)) private var rightOn = true
    @AppStorage(CaptureSettings.edgeKey(.top)) private var topOn = true
    @AppStorage(CaptureSettings.edgeKey(.bottom)) private var bottomOn = true

    private let canvas = CGSize(width: 640, height: 360)
    private let margin: CGFloat = 64
    private let thickness: CGFloat = 34
    private let gap: CGFloat = 6
    private let trayHeight: CGFloat = 60

    /// A device that can sit on an edge: online controllable peers plus offline ones that still hold an edge.
    private struct Item: Identifiable, Equatable {
        let id: String
        let name: String
        let online: Bool
        var platform = ""

        var symbol: String {
            guard online else { return "wifi.slash" }
            switch platform {
            case "android", "ios": return "iphone"
            case "macos": return "laptopcomputer"
            case "windows", "linux": return "desktopcomputer"
            default: return "display"
            }
        }
    }

    private var items: [Item] {
        let online = directory.peers.filter { $0.caps.contains("ctl_target") }
        var list = online.map { Item(id: $0.id, name: $0.name, online: true, platform: $0.platform) }
        let ids = Set(list.map(\.id))
        for (id, p) in placements where p.edge != nil && !ids.contains(id) && PeerStore.shared.hasKey(id) {
            list.append(Item(id: id, name: p.name.isEmpty ? "Device" : p.name, online: false))
        }
        return list.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Tok.md) {
            header
            canvasCard
            HStack(alignment: .top, spacing: Tok.md) {
                edgesCard
                VStack(spacing: Tok.md) {
                    crossingCard
                    thisMacCard
                }
                .frame(width: 270)
            }
        }
        .padding(Tok.lg)
        .frame(width: canvas.width + 2 * Tok.lg)
        .background(Tok.background)
        .foregroundStyle(Tok.fg)
        .tint(Tok.accent)
        .preferredColorScheme(.dark)
        .onAppear {
            geo = DisplayGeometry.current()
            placements = Layout.shared.all()
            status = ControlStatus.shared.snapshot
            if directory.peers.isEmpty { directory.peers = PeerBus.shared.peers() }
        }
        .onReceive(NotificationCenter.default.publisher(for: Layout.changed)) { _ in
            placements = Layout.shared.all()
        }
        .onReceive(NotificationCenter.default.publisher(for: ControlStatus.notification)) { _ in
            status = ControlStatus.shared.snapshot
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didChangeScreenParametersNotification)) { _ in
            geo = DisplayGeometry.current()
        }
    }

    // MARK: header

    private var header: some View {
        HStack(alignment: .center, spacing: Tok.md) {
            Image(systemName: "rectangle.on.rectangle")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Tok.accent)
                .frame(width: 40, height: 40)
                .background(RoundedRectangle(cornerRadius: Tok.radius).fill(Tok.primary))
            VStack(alignment: .leading, spacing: 2) {
                Text("Arrange Devices").font(.title3.weight(.semibold))
                Text("Drag a device to the edge that leads to it. Push the cursor firmly through that edge to cross.")
                    .font(.callout)
                    .foregroundStyle(Tok.mutedFg)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
    }

    private func card<Content: View>(_ title: String, _ symbol: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Tok.sm + 4) {
            Label(title, systemImage: symbol)
                .font(.headline)
            content()
        }
        .padding(Tok.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: Tok.cardRadius)
                .fill(Tok.card)
                .overlay(RoundedRectangle(cornerRadius: Tok.cardRadius).stroke(Color.white.opacity(0.08), lineWidth: 1))
                .shadow(color: .black.opacity(0.1), radius: 3, y: 2)
        )
    }

    // MARK: canvas

    private var canvasCard: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: Tok.cardRadius)
                .fill(Tok.card)
                .overlay(RoundedRectangle(cornerRadius: Tok.cardRadius).stroke(Color.white.opacity(0.08), lineWidth: 1))
            ForEach(Array(geo.rects.enumerated()), id: \.offset) { _, r in
                displayTile(r)
            }
            ForEach(ScreenEdge.allCases, id: \.self) { edge in
                edgeGlow(edge)
            }
            RoundedRectangle(cornerRadius: Tok.radius)
                .stroke(Tok.border, style: StrokeStyle(lineWidth: 1, dash: [4]))
                .frame(width: canvas.width - 2 * Tok.md, height: trayHeight - Tok.md)
                .overlay(alignment: .trailing) {
                    Label("Not used", systemImage: "tray")
                        .font(.caption)
                        .foregroundStyle(Tok.mutedFg)
                        .padding(.trailing, Tok.sm + 2)
                }
                .position(x: canvas.width / 2, y: canvas.height + trayHeight / 2 - Tok.xs)
            ForEach(items) { item in
                peerTile(item)
            }
            if items.isEmpty {
                Label("No connected device can be controlled right now.", systemImage: "antenna.radiowaves.left.and.right.slash")
                    .font(.callout)
                    .foregroundStyle(Tok.mutedFg)
                    .position(x: canvas.width / 2, y: canvas.height + trayHeight / 2 - Tok.xs)
            }
        }
        .frame(width: canvas.width, height: canvas.height + trayHeight)
        .coordinateSpace(name: "canvas")
    }

    private var scale: CGFloat {
        min((canvas.width - 2 * margin) / max(1, geo.union.width),
            (canvas.height - 2 * margin) / max(1, geo.union.height))
    }

    private func toView(_ r: CGRect) -> CGRect {
        let s = scale
        let ox = (canvas.width - geo.union.width * s) / 2 - geo.union.minX * s
        let oy = (canvas.height - geo.union.height * s) / 2 - geo.union.minY * s
        return CGRect(x: ox + r.minX * s, y: oy + r.minY * s, width: r.width * s, height: r.height * s)
    }

    private var desktop: CGRect { toView(geo.union) }

    private func tileRect(_ item: Item) -> CGRect {
        let bb = desktop
        guard let p = placements[item.id], let edge = p.edge else {
            let parked = items.filter { placements[$0.id]?.edge == nil }
            let i = CGFloat(parked.firstIndex(where: { $0.id == item.id }) ?? 0)
            return CGRect(x: Tok.lg + i * 122, y: canvas.height + 8, width: 114, height: 34)
        }
        let a = CGFloat(p.start), b = CGFloat(p.end)
        switch edge {
        case .left: return CGRect(x: bb.minX - gap - thickness, y: bb.minY + a * bb.height, width: thickness, height: (b - a) * bb.height)
        case .right: return CGRect(x: bb.maxX + gap, y: bb.minY + a * bb.height, width: thickness, height: (b - a) * bb.height)
        case .top: return CGRect(x: bb.minX + a * bb.width, y: bb.minY - gap - thickness, width: (b - a) * bb.width, height: thickness)
        case .bottom: return CGRect(x: bb.minX + a * bb.width, y: bb.maxY + gap, width: (b - a) * bb.width, height: thickness)
        }
    }

    private func displayTile(_ r: CGRect) -> some View {
        let v = toView(r)
        let isMain = r == geo.mainRect
        return RoundedRectangle(cornerRadius: 6)
            .fill(isMain ? Tok.secondary : Tok.primary)
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Tok.border, lineWidth: 1))
            .overlay(alignment: .top) {
                if isMain {
                    UnevenRoundedRectangle(topLeadingRadius: 6, topTrailingRadius: 6)
                        .fill(Color.white.opacity(0.18))
                        .frame(height: 5)
                }
            }
            .overlay {
                VStack(spacing: 2) {
                    Image(systemName: isMain ? "laptopcomputer" : "display").font(.system(size: 14))
                    Text(isMain ? "This Mac" : "Display").font(.caption.weight(.semibold))
                    Text("\(Int(r.width)) × \(Int(r.height))").font(.caption2).foregroundStyle(Tok.mutedFg)
                }
            }
            .frame(width: max(1, v.width - 2), height: max(1, v.height - 2))
            .position(x: v.midX, y: v.midY)
    }

    /// Thin accent line on each edge that leads somewhere (dim when the edge is off or the peer is offline).
    @ViewBuilder
    private func edgeGlow(_ edge: ScreenEdge) -> some View {
        if let (id, p) = assignment(edge) {
            let bb = desktop
            let a = CGFloat(p.start), b = CGFloat(p.end)
            let lit = isOnline(id) && edgeEnabled(edge)
            let r: CGRect = {
                switch edge {
                case .left: return CGRect(x: bb.minX - 1, y: bb.minY + a * bb.height, width: 3, height: (b - a) * bb.height)
                case .right: return CGRect(x: bb.maxX - 2, y: bb.minY + a * bb.height, width: 3, height: (b - a) * bb.height)
                case .top: return CGRect(x: bb.minX + a * bb.width, y: bb.minY - 1, width: (b - a) * bb.width, height: 3)
                case .bottom: return CGRect(x: bb.minX + a * bb.width, y: bb.maxY - 2, width: (b - a) * bb.width, height: 3)
                }
            }()
            Capsule()
                .fill(Tok.accent.opacity(lit ? 0.9 : 0.25))
                .frame(width: r.width, height: r.height)
                .position(x: r.midX, y: r.midY)
                .allowsHitTesting(false)
        }
    }

    private func peerTile(_ item: Item) -> some View {
        let r = tileRect(item)
        let off = dragOffsets[item.id] ?? .zero
        let edge = placements[item.id]?.edge
        let vertical = edge?.isVertical ?? false
        let dragging = off != .zero
        let isHover = hovered == item.id
        let leased = status.controlledBy == item.name || status.controlling == item.name
        return RoundedRectangle(cornerRadius: Tok.radius)
            .fill(item.online ? Tok.accent : Tok.muted)
            .overlay(
                RoundedRectangle(cornerRadius: Tok.radius)
                    .stroke(item.online ? Color.white.opacity(leased ? 0.9 : 0) : Tok.border,
                            style: StrokeStyle(lineWidth: leased ? 2 : 1, dash: item.online ? [] : [3]))
            )
            .overlay {
                HStack(spacing: Tok.xs) {
                    Image(systemName: item.symbol).font(.caption2)
                    Text(item.name).font(.caption.weight(.semibold)).lineLimit(1)
                }
                .foregroundStyle(item.online ? Tok.onAccent : Tok.mutedFg)
                .rotationEffect(vertical ? .degrees(-90) : .zero)
                .fixedSize()
            }
            .clipped()
            .shadow(color: .black.opacity(dragging ? 0.35 : (isHover ? 0.2 : 0)), radius: dragging ? 8 : 4, y: dragging ? 6 : 2)
            .frame(width: r.width, height: r.height)
            .position(x: r.midX + off.width, y: r.midY + off.height)
            .animation(reduceMotion || dragging ? nil : Tok.transition, value: placements)
            .animation(reduceMotion ? nil : Tok.transition, value: isHover)
            .onHover { inside in
                if inside { hovered = item.id } else if hovered == item.id { hovered = nil }
            }
            .pointerStyle(dragging ? .grabActive : .grabIdle)
            .gesture(
                DragGesture(coordinateSpace: .named("canvas"))
                    .onChanged { dragOffsets[item.id] = $0.translation }
                    .onEnded { v in
                        dragOffsets[item.id] = nil
                        drop(item, at: CGPoint(x: r.midX + v.translation.width, y: r.midY + v.translation.height))
                    }
            )
            .help(item.online ? "\(item.name): drag to an edge, or to the tray to stop crossing"
                              : "\(item.name) is offline; its edge does nothing until it reconnects")
            .accessibilityLabel(item.name)
            .accessibilityValue(edge.map { "\($0.rawValue) edge" } ?? "not used")
    }

    private func drop(_ item: Item, at c: CGPoint) {
        let current = placements[item.id] ?? Placement()
        let length = max(0.1, current.end - current.start)
        if c.y > canvas.height {
            Layout.shared.set(item.id, edge: nil, start: current.start, end: current.end, name: item.name)
            return
        }
        let bb = desktop
        // Pick the side the drop point is furthest outside of (or nearest to, if inside).
        let outside: [(ScreenEdge, CGFloat)] = [
            (.left, bb.minX - c.x), (.right, c.x - bb.maxX), (.top, bb.minY - c.y), (.bottom, c.y - bb.maxY),
        ]
        let edge = outside.max { $0.1 < $1.1 }!.0
        let t = edge.isVertical ? (c.y - bb.minY) / max(1, bb.height) : (c.x - bb.minX) / max(1, bb.width)
        let start = min(max(0, Double(t) - length / 2), 1 - length)
        Layout.shared.set(item.id, edge: edge, start: start, end: start + length, name: item.name)
    }

    // MARK: edges

    private func assignment(_ edge: ScreenEdge) -> (String, Placement)? {
        placements.first { $0.value.edge == edge }.map { ($0.key, $0.value) }
    }

    private func isOnline(_ id: String) -> Bool {
        directory.peers.contains { $0.id == id && $0.caps.contains("ctl_target") }
    }

    private func edgeEnabled(_ e: ScreenEdge) -> Bool {
        switch e {
        case .left: return leftOn
        case .right: return rightOn
        case .top: return topOn
        case .bottom: return bottomOn
        }
    }

    private func edgeBinding(_ e: ScreenEdge) -> Binding<Bool> {
        switch e {
        case .left: return $leftOn
        case .right: return $rightOn
        case .top: return $topOn
        case .bottom: return $bottomOn
        }
    }

    private static func symbol(_ e: ScreenEdge) -> String {
        switch e {
        case .left: return "arrow.left.to.line"
        case .right: return "arrow.right.to.line"
        case .top: return "arrow.up.to.line"
        case .bottom: return "arrow.down.to.line"
        }
    }

    private var edgesCard: some View {
        card("Edges", "arrow.left.and.right.square") {
            VStack(spacing: Tok.sm) {
                ForEach(ScreenEdge.allCases, id: \.self) { edge in
                    edgeRow(edge)
                    if edge != ScreenEdge.allCases.last { Divider().overlay(Color.white.opacity(0.06)) }
                }
            }
            Text("Each edge leads to exactly one device. An edge whose device is offline never captures.")
                .font(.caption)
                .foregroundStyle(Tok.mutedFg)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func edgeRow(_ edge: ScreenEdge) -> some View {
        let a = assignment(edge)
        let online = a.map { isOnline($0.0) } ?? false
        let enabled = edgeEnabled(edge)
        let onlineItems = items.filter(\.online)
        return HStack(spacing: Tok.sm) {
            Image(systemName: Self.symbol(edge))
                .foregroundStyle(a != nil && online && enabled ? Tok.accent : Tok.mutedFg)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text("\(edge.rawValue.capitalized) edge").font(.callout.weight(.medium))
                if let (_, p) = a {
                    HStack(spacing: Tok.xs) {
                        Text("→ \(p.name.isEmpty ? "Device" : p.name)").lineLimit(1)
                        Text(online ? "· \(spanLabel(p))" : "· offline")
                            .foregroundStyle(online ? Tok.mutedFg : Tok.destructive)
                    }
                    .font(.caption)
                    .foregroundStyle(online ? Tok.fg : Tok.mutedFg)
                    .help(online ? "Crossing here controls \(p.name)" : "\(p.name) is offline, so this edge does nothing")
                } else {
                    Text("Not assigned").font(.caption).foregroundStyle(Tok.mutedFg)
                }
            }
            Spacer(minLength: Tok.sm)
            if let (id, p) = a {
                Menu {
                    ForEach([1, 2, 3, 4], id: \.self) { q in
                        Button("\(q * 25)% of the edge") { setSpan(id, p, quarters: q) }
                    }
                    Divider()
                    Button("Stop using this edge", role: .destructive) {
                        Layout.shared.set(id, edge: nil, start: p.start, end: p.end, name: p.name)
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .pointerStyle(.link)
                .help("Span and assignment")
            } else if !onlineItems.isEmpty {
                Menu {
                    ForEach(onlineItems) { item in
                        Button(item.name) { Layout.shared.set(item.id, edge: edge, start: 0, end: 1, name: item.name) }
                    }
                } label: {
                    Image(systemName: "plus.circle")
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .pointerStyle(.link)
                .help("Assign a device to this edge")
            }
            Toggle("", isOn: edgeBinding(edge))
                .toggleStyle(.switch)
                .controlSize(.mini)
                .labelsHidden()
                .help(enabled ? "Crossing on this edge is on" : "Crossing on this edge is off")
                .accessibilityLabel("\(edge.rawValue.capitalized) edge crossing")
        }
        .frame(minHeight: 34)
    }

    private func spanLabel(_ p: Placement) -> String {
        let pct = Int(((p.end - p.start) * 100).rounded())
        return pct >= 99 ? "full edge" : "\(pct)% of edge"
    }

    private func setSpan(_ id: String, _ p: Placement, quarters: Int) {
        let len = Double(quarters) / 4
        let mid = (p.start + p.end) / 2
        let start = min(max(0, mid - len / 2), 1 - len)
        Layout.shared.set(id, edge: p.edge, start: start, end: start + len, name: p.name)
    }

    // MARK: crossing settings

    private var edgeColorBinding: Binding<Color> {
        Binding(
            get: { Color(nsColor: CaptureSettings.color(fromHex: edgeColorHex)) },
            set: { edgeColorHex = CaptureSettings.hex(NSColor($0)); previewEdge() }
        )
    }

    /// A quick flash of the glow along the main screen's right edge, so the new colour can be judged.
    private func previewEdge() {
        let r = CGDisplayBounds(CGMainDisplayID())
        let t = EdgeIndicator.thickness
        EdgeIndicator.shared.flash(rect: CGRect(x: r.maxX - t, y: r.minY + r.height * 0.3, width: t, height: r.height * 0.4))
    }

    private var strengthBinding: Binding<Int> {
        Binding(
            get: {
                CaptureStrength.allCases.min { abs(Double($0.rawValue) - threshold) < abs(Double($1.rawValue) - threshold) }!.rawValue
            },
            set: { threshold = Double($0) }
        )
    }

    private var crossingCard: some View {
        card("Crossing", "cursorarrow.motionlines") {
            VStack(alignment: .leading, spacing: Tok.xs) {
                Text("Push-through").font(.caption).foregroundStyle(Tok.mutedFg)
                Picker("Push-through", selection: strengthBinding) {
                    ForEach(CaptureStrength.allCases) { Text($0.label).tag($0.rawValue) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            Toggle(isOn: $requireOption) {
                Label("Also hold Option to cross", systemImage: "option")
                    .font(.callout)
            }
            .toggleStyle(.checkbox)
            HStack(spacing: Tok.sm) {
                ColorPicker("Edge glow", selection: edgeColorBinding, supportsOpacity: false)
                    .font(.callout)
                if edgeColorHex.uppercased() != CaptureSettings.defaultEdgeColorHex {
                    Button("Reset") { edgeColorHex = CaptureSettings.defaultEdgeColorHex; previewEdge() }
                        .buttonStyle(.link)
                }
            }
            Text("Resting the cursor on an edge never crosses. Keep pushing outward for a moment: the bar on the edge glows and fills, then the cursor moves over. Come back the same way, or press Control-Option-Command-Esc.")
                .font(.caption)
                .foregroundStyle(Tok.mutedFg)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: lease state

    private var thisMacCard: some View {
        card("This Mac", "lock.shield") {
            VStack(alignment: .leading, spacing: Tok.sm) {
                statusRow(symbol: "arrow.up.forward.app", active: status.controlling != nil,
                          text: status.controlling.map { "Controlling \($0)" } ?? "Not controlling another device")
                statusRow(symbol: "hand.point.up.left", active: status.controlledBy != nil,
                          text: status.controlledBy.map { "Controlled by \($0)" + (status.leaseImplicit ? " (trackpad)" : "") }
                              ?? "Nobody is controlling this Mac")
                if let m = status.mirroring {
                    statusRow(symbol: "rectangle.on.rectangle.angled", active: true, text: "Screen shared with \(m)")
                }
                if let n = status.notice {
                    statusRow(symbol: "exclamationmark.triangle", active: false, text: n, warn: true)
                }
            }
            .animation(reduceMotion ? nil : Tok.transition, value: status)
        }
    }

    private func statusRow(symbol: String, active: Bool, text: String, warn: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Tok.sm) {
            Circle()
                .fill(warn ? Tok.destructive : (active ? Tok.accent : Tok.border))
                .frame(width: 7, height: 7)
            Image(systemName: symbol).font(.caption).foregroundStyle(active ? Tok.fg : Tok.mutedFg)
            Text(text)
                .font(.callout)
                .foregroundStyle(active || warn ? Tok.fg : Tok.mutedFg)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}
