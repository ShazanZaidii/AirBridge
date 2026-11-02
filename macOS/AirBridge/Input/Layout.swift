import Foundation
import CoreGraphics

nonisolated enum ScreenEdge: String, CaseIterable, Sendable {
    case left, right, top, bottom

    var opposite: ScreenEdge {
        switch self {
        case .left: return .right
        case .right: return .left
        case .top: return .bottom
        case .bottom: return .top
        }
    }
    /// left/right edges run top→bottom (along = y); top/bottom run left→right (along = x).
    var isVertical: Bool { self == .left || self == .right }
}

/// Where a peer sits around the Mac. `start`/`end` are fractions [0,1] of the union of all
/// displays along that edge, so placements survive resolution and arrangement changes.
nonisolated struct Placement: Equatable, Sendable {
    var edge: ScreenEdge?          // nil = parked (never a portal)
    var start: Double = 0
    var end: Double = 1
    var name: String = ""
}

/// Per-peer edge assignment, persisted in UserDefaults keyed by peer id. Thread-safe.
nonisolated final class Layout: @unchecked Sendable {
    static let shared = Layout()
    static let changed = Notification.Name("AirBridgeLayoutChanged")
    private static let defaultsKey = "AirBridge.layout.v2"

    private let lock = NSLock()
    private var map: [String: Placement] = [:]

    private init() {
        let raw = UserDefaults.standard.dictionary(forKey: Self.defaultsKey) as? [String: [String: Any]] ?? [:]
        for (id, d) in raw {
            let edge = (d["edge"] as? String).flatMap(ScreenEdge.init(rawValue:))
            map[id] = Placement(edge: edge,
                                start: (d["start"] as? Double) ?? 0,
                                end: (d["end"] as? Double) ?? 1,
                                name: (d["name"] as? String) ?? "")
        }
        // §8.6: at most one peer per edge. Park duplicates left by older builds (deterministically).
        var seen: Set<ScreenEdge> = []
        for id in map.keys.sorted() {
            guard let e = map[id]?.edge else { continue }
            if !seen.insert(e).inserted { map[id]?.edge = nil }
        }
    }

    /// The single peer assigned to `edge`, if any.
    func assignment(for edge: ScreenEdge) -> (id: String, placement: Placement)? {
        lock.lock(); defer { lock.unlock() }
        guard let hit = map.first(where: { $0.value.edge == edge }) else { return nil }
        return (hit.key, hit.value)
    }

    func placement(for id: String) -> Placement? {
        lock.lock(); defer { lock.unlock() }
        return map[id]
    }

    func all() -> [String: Placement] {
        lock.lock(); defer { lock.unlock() }
        return map
    }

    /// Assigns `id` to `edge` (nil parks it). Any other peer on that edge is parked (§8.1: one per edge).
    func set(_ id: String, edge: ScreenEdge?, start: Double, end: Double, name: String) {
        lock.lock()
        let a = max(0, min(1, min(start, end))), b = max(0, min(1, max(start, end)))
        if let edge {
            for (other, p) in map where other != id && p.edge == edge { map[other]?.edge = nil }
        }
        map[id] = Placement(edge: edge, start: a, end: max(b, min(1, a + 0.05)), name: name)
        persistLocked()
        lock.unlock()
        notify()
    }

    /// First-seen controllable peers get the first free edge (right, left, bottom, top).
    func autoAssign(_ peers: [PeerInfo]) {
        lock.lock()
        var changed = false
        for peer in peers where map[peer.id] == nil {
            let used = Set(map.values.compactMap(\.edge))
            let edge = [ScreenEdge.right, .left, .bottom, .top].first { !used.contains($0) }
            map[peer.id] = Placement(edge: edge, start: 0, end: 1, name: peer.name)
            changed = true
        }
        for peer in peers where map[peer.id] != nil && map[peer.id]!.name != peer.name {
            map[peer.id]!.name = peer.name
            changed = true
        }
        if changed { persistLocked() }
        lock.unlock()
        if changed { notify() }
    }

    private func persistLocked() {
        var raw: [String: [String: Any]] = [:]
        for (id, p) in map {
            var d: [String: Any] = ["start": p.start, "end": p.end, "name": p.name]
            if let e = p.edge { d["edge"] = e.rawValue }
            raw[id] = d
        }
        UserDefaults.standard.set(raw, forKey: Self.defaultsKey)
    }

    private func notify() {
        DispatchQueue.main.async { NotificationCenter.default.post(name: Layout.changed, object: nil) }
    }
}

/// Snapshot of all active displays in global CG coordinates (points, top-left origin, y down).
nonisolated struct DisplayGeometry: Sendable {
    let rects: [CGRect]
    let mainRect: CGRect
    let union: CGRect

    static func current() -> DisplayGeometry {
        var count: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &count)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(max(count, 1)))
        CGGetActiveDisplayList(count, &ids, &count)
        var rects: [CGRect] = []
        for id in ids.prefix(Int(count)) {
            // Mirrored secondaries share a rect with their master; skip them.
            if CGDisplayIsInMirrorSet(id) != 0 && CGDisplayMirrorsDisplay(id) != kCGNullDirectDisplay { continue }
            rects.append(CGDisplayBounds(id))
        }
        let main = CGDisplayBounds(CGMainDisplayID())
        if rects.isEmpty { rects = [main] }
        return DisplayGeometry(rects: rects, mainRect: main,
                               union: rects.dropFirst().reduce(rects[0]) { $0.union($1) })
    }

    /// Display that contains `p`, with half a point of tolerance on the max edges.
    func display(at p: CGPoint) -> CGRect? {
        rects.first { $0.contains(p) } ??
        rects.first { p.x >= $0.minX - 0.5 && p.x <= $0.maxX + 0.5 && p.y >= $0.minY - 0.5 && p.y <= $0.maxY + 0.5 }
    }

    func contains(_ p: CGPoint) -> Bool { rects.contains { $0.contains(p) } }

    /// Nearest point that lies on some display.
    func clamp(_ p: CGPoint) -> CGPoint {
        var best = p, bestD = CGFloat.infinity
        for r in rects {
            let c = CGPoint(x: min(max(p.x, r.minX), r.maxX - 1), y: min(max(p.y, r.minY), r.maxY - 1))
            let d = hypot(c.x - p.x, c.y - p.y)
            if d < bestD { bestD = d; best = c }
        }
        return best
    }

    /// Global coordinate range along `edge` for a fractional span of the union.
    func span(_ edge: ScreenEdge, _ a: Double, _ b: Double) -> (CGFloat, CGFloat) {
        let lo = edge.isVertical ? union.minY : union.minX
        let len = edge.isVertical ? union.height : union.width
        return (lo + len * a, lo + len * b)
    }

    /// Fraction of the union along `edge` for a global coordinate.
    func fraction(_ edge: ScreenEdge, _ along: CGFloat) -> Double {
        let lo = edge.isVertical ? union.minY : union.minX
        let len = edge.isVertical ? union.height : union.width
        return Double(max(0, min(1, (along - lo) / max(1, len))))
    }

    /// True if `p` (on display `r`) sits on the outer `edge` of the desktop — no display beyond it.
    func isOuter(_ p: CGPoint, of r: CGRect, _ edge: ScreenEdge) -> Bool {
        switch edge {
        case .left: return p.x <= r.minX + 0.5 && !contains(CGPoint(x: r.minX - 2, y: p.y))
        case .right: return p.x >= r.maxX - 1.5 && !contains(CGPoint(x: r.maxX + 1, y: p.y))
        case .top: return p.y <= r.minY + 0.5 && !contains(CGPoint(x: p.x, y: r.minY - 2))
        case .bottom: return p.y >= r.maxY - 1.5 && !contains(CGPoint(x: p.x, y: r.maxY + 1))
        }
    }

    /// Point on the outermost display boundary of `edge` at global coordinate `along`,
    /// moved `inset` points inward.
    func edgePoint(_ edge: ScreenEdge, along: CGFloat, inset: CGFloat) -> CGPoint {
        let covering = rects.filter {
            edge.isVertical ? (along >= $0.minY && along < $0.maxY) : (along >= $0.minX && along < $0.maxX)
        }
        var a = along
        var pool = covering
        if pool.isEmpty {
            // Gap in an irregular arrangement: use the nearest display and clamp onto it.
            let near = rects.min { l, r in
                let dl = edge.isVertical ? max(l.minY - along, along - l.maxY) : max(l.minX - along, along - l.maxX)
                let dr = edge.isVertical ? max(r.minY - along, along - r.maxY) : max(r.minX - along, along - r.maxX)
                return dl < dr
            } ?? mainRect
            pool = [near]
            a = edge.isVertical ? min(max(along, near.minY), near.maxY - 1) : min(max(along, near.minX), near.maxX - 1)
        }
        switch edge {
        case .left:   return CGPoint(x: pool.map(\.minX).min()! + inset, y: a)
        case .right:  return CGPoint(x: pool.map(\.maxX).max()! - 1 - inset, y: a)
        case .top:    return CGPoint(x: a, y: pool.map(\.minY).min()! + inset)
        case .bottom: return CGPoint(x: a, y: pool.map(\.maxY).max()! - 1 - inset)
        }
    }
}
