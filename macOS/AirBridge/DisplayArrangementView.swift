import SwiftUI
import Combine
import CoreGraphics

/// Ready peers, mirrored on the main thread for SwiftUI.
final class PeerDirectory: ObservableObject {
    static let shared = PeerDirectory()
    @Published var peers: [PeerInfo] = []
}

/// Lets the user place each controllable peer on an outer edge of the real display layout.
struct DisplayArrangementView: View {
    @ObservedObject private var directory = PeerDirectory.shared
    @State private var geo = DisplayGeometry.current()
    @State private var placements = Layout.shared.all()
    @State private var dragOffsets: [String: CGSize] = [:]

    private let canvas = CGSize(width: 640, height: 400)
    private let margin: CGFloat = 70
    private let thickness: CGFloat = 34
    private let gap: CGFloat = 6
    private let trayHeight: CGFloat = 64

    private var targets: [PeerInfo] { directory.peers.filter { $0.caps.contains("ctl_target") } }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Arrange Devices").font(.headline)
            Text("Drag a device to the display edge where your cursor should cross over to it. Drop it in the tray to turn crossing off.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color(nsColor: .controlBackgroundColor))
                ForEach(Array(geo.rects.enumerated()), id: \.offset) { _, r in
                    displayTile(r)
                }
                RoundedRectangle(cornerRadius: 10)
                    .stroke(Color.secondary.opacity(0.4), style: StrokeStyle(lineWidth: 1, dash: [4]))
                    .frame(width: canvas.width - 24, height: trayHeight - 16)
                    .overlay(alignment: .trailing) {
                        Text("Off").font(.caption).foregroundStyle(.secondary).padding(.trailing, 10)
                    }
                    .position(x: canvas.width / 2, y: canvas.height + trayHeight / 2 - 4)
                ForEach(targets) { peer in
                    peerTile(peer)
                }
            }
            .frame(width: canvas.width, height: canvas.height + trayHeight)
            .coordinateSpace(name: "canvas")

            if targets.isEmpty {
                Text("No connected device can be controlled right now.")
                    .foregroundStyle(.secondary)
            }
            ForEach(targets) { peer in
                peerRow(peer)
            }

            Text("To bring the cursor back from a device: move it back across the edge, press Esc three times quickly, or press Control-Option-Command-Esc.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(24)
        .frame(width: canvas.width + 48)
        .onAppear {
            geo = DisplayGeometry.current()
            placements = Layout.shared.all()
            if directory.peers.isEmpty { directory.peers = PeerBus.shared.peers() }
        }
        .onReceive(NotificationCenter.default.publisher(for: Layout.changed)) { _ in
            placements = Layout.shared.all()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didChangeScreenParametersNotification)) { _ in
            geo = DisplayGeometry.current()
        }
    }

    // MARK: geometry mapping

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

    private func tileRect(_ peer: PeerInfo) -> CGRect {
        let bb = desktop
        guard let p = placements[peer.id], let edge = p.edge else {
            let parked = targets.filter { placements[$0.id]?.edge == nil }
            let i = CGFloat(parked.firstIndex(where: { $0.id == peer.id }) ?? 0)
            return CGRect(x: 24 + i * 118, y: canvas.height + 10, width: 110, height: 36)
        }
        let a = CGFloat(p.start), b = CGFloat(p.end)
        switch edge {
        case .left: return CGRect(x: bb.minX - gap - thickness, y: bb.minY + a * bb.height, width: thickness, height: (b - a) * bb.height)
        case .right: return CGRect(x: bb.maxX + gap, y: bb.minY + a * bb.height, width: thickness, height: (b - a) * bb.height)
        case .top: return CGRect(x: bb.minX + a * bb.width, y: bb.minY - gap - thickness, width: (b - a) * bb.width, height: thickness)
        case .bottom: return CGRect(x: bb.minX + a * bb.width, y: bb.maxY + gap, width: (b - a) * bb.width, height: thickness)
        }
    }

    // MARK: tiles

    private func displayTile(_ r: CGRect) -> some View {
        let v = toView(r)
        let isMain = r == geo.mainRect
        return RoundedRectangle(cornerRadius: 6)
            .fill(Color.accentColor.opacity(isMain ? 0.85 : 0.6))
            .overlay(alignment: .top) {
                if isMain { Rectangle().fill(Color.white.opacity(0.6)).frame(height: 4) }
            }
            .overlay {
                VStack(spacing: 2) {
                    Text(isMain ? "Mac (main)" : "Display").font(.caption).bold()
                    Text("\(Int(r.width))×\(Int(r.height))").font(.caption2)
                }
                .foregroundStyle(.white)
            }
            .frame(width: max(1, v.width - 2), height: max(1, v.height - 2))
            .position(x: v.midX, y: v.midY)
    }

    private func peerTile(_ peer: PeerInfo) -> some View {
        let r = tileRect(peer)
        let off = dragOffsets[peer.id] ?? .zero
        let vertical = placements[peer.id]?.edge?.isVertical ?? false
        return RoundedRectangle(cornerRadius: 8)
            .fill(Color.green.opacity(0.85))
            .overlay {
                Text(peer.name)
                    .font(.caption).bold()
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .rotationEffect(vertical ? .degrees(-90) : .zero)
                    .fixedSize()
            }
            .clipped()
            .shadow(color: .black.opacity(off == .zero ? 0 : 0.3), radius: 5, y: 4)
            .frame(width: r.width, height: r.height)
            .position(x: r.midX + off.width, y: r.midY + off.height)
            .gesture(
                DragGesture(coordinateSpace: .named("canvas"))
                    .onChanged { dragOffsets[peer.id] = $0.translation }
                    .onEnded { v in
                        dragOffsets[peer.id] = nil
                        drop(peer, at: CGPoint(x: r.midX + v.translation.width, y: r.midY + v.translation.height))
                    }
            )
            .help(peer.name)
    }

    private func drop(_ peer: PeerInfo, at c: CGPoint) {
        let current = placements[peer.id] ?? Placement()
        let length = max(0.1, current.end - current.start)
        if c.y > canvas.height {
            Layout.shared.set(peer.id, edge: nil, start: current.start, end: current.end, name: peer.name)
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
        Layout.shared.set(peer.id, edge: edge, start: start, end: start + length, name: peer.name)
    }

    // MARK: list controls

    private func peerRow(_ peer: PeerInfo) -> some View {
        let p = placements[peer.id] ?? Placement()
        let edgeBinding = Binding<String>(
            get: { p.edge?.rawValue ?? "off" },
            set: { Layout.shared.set(peer.id, edge: ScreenEdge(rawValue: $0), start: p.start, end: p.end, name: peer.name) }
        )
        let spanBinding = Binding<Int>(
            get: { Int(((p.end - p.start) * 4).rounded()) },
            set: { quarters in
                let len = Double(quarters) / 4
                let mid = (p.start + p.end) / 2
                let start = min(max(0, mid - len / 2), 1 - len)
                Layout.shared.set(peer.id, edge: p.edge, start: start, end: start + len, name: peer.name)
            }
        )
        return HStack {
            Text(peer.name).frame(width: 180, alignment: .leading).lineLimit(1)
            Picker("Edge", selection: edgeBinding) {
                Text("Off").tag("off")
                ForEach(ScreenEdge.allCases, id: \.self) { Text($0.rawValue.capitalized).tag($0.rawValue) }
            }
            .frame(width: 160)
            Picker("Span", selection: spanBinding) {
                Text("25%").tag(1); Text("50%").tag(2); Text("75%").tag(3); Text("100%").tag(4)
            }
            .frame(width: 150)
            .disabled(p.edge == nil)
        }
    }
}
