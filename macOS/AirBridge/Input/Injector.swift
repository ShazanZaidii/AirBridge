import Cocoa
import CoreGraphics
import IOKit
import IOKit.hidsystem

/// Loose JSON field readers (values may arrive as NSNumber, Int, Double or Bool).
nonisolated enum Msg {
    static func num(_ v: Any?) -> Double? {
        switch v {
        case let d as Double: return d
        case let i as Int: return Double(i)
        case let n as NSNumber: return n.doubleValue
        case let f as Float: return Double(f)
        case let c as CGFloat: return Double(c)
        default: return nil
        }
    }
    static func int(_ v: Any?) -> Int? { num(v).map { Int($0) } }
    static func bool(_ v: Any?) -> Bool? {
        switch v {
        case let b as Bool: return b
        case let n as NSNumber: return n.boolValue
        default: return nil
        }
    }
    static func round(_ v: Double, _ places: Double = 100) -> Double { (v * places).rounded() / places }
}

/// Target side (protocol.md §8): injects pointer/keyboard/action messages from peers into macOS.
/// Holds the single control lease (§8.6): `ctl_enter` takes it explicitly (and pre-empts), the first
/// trackpad/mirror input takes it implicitly; input from anyone else is dropped and answered with
/// `ctl_busy`. All state lives on `queue`. Every posted event carries `tag` so our own event tap ignores it.
nonisolated final class Injector: @unchecked Sendable {
    static let shared = Injector()
    static let tag: Int64 = 0x4149_5242 // "AIRB"

    private let queue = DispatchQueue(label: "com.airbridge.inject", qos: .userInteractive)
    private let source: CGEventSource? = {
        let s = CGEventSource(stateID: .hidSystemState)
        s?.userData = Injector.tag
        s?.localEventsSuppressionInterval = 0
        return s
    }()

    private struct PeerState {
        var keys: Set<UInt16> = []   // Mac keycodes held (modifiers included)
        var buttons: Set<Int> = []   // protocol button ids held
    }
    private var states: [String: PeerState] = [:]

    private struct Lease {
        let peer: String
        let edge: ScreenEdge?          // entry edge of an explicit (ctl_enter) lease; nil = implicit
        var last: CFAbsoluteTime       // last input from the holder
    }
    private var lease: Lease?
    private var busySent: [String: CFAbsoluteTime] = [:]
    private var names: [String: String] = [:]
    private var watchdog: DispatchSourceTimer?
    private var scrollPhaseOpen = false
    private var scrollEnd: DispatchWorkItem?

    private static let implicitLeaseTTL = 1.5      // §8.6
    private static let busyInterval = 2.0          // §8.6: ctl_busy at most once per 2 s per peer
    private static let buttonWatchdog = 5.0        // stuck-button guard for trackpad/mirror peers

    /// The explicit (`ctl_enter`) controller, if any.
    private var entered: (peer: String, edge: ScreenEdge)? {
        guard let l = lease, let e = l.edge else { return nil }
        return (l.peer, e)
    }
    private var cursor: CGPoint?
    private var lastPost: CFAbsoluteTime = 0
    private var geo = DisplayGeometry.current()
    private var geoAt: CFAbsoluteTime = 0
    private var lastDown: (button: Int, time: CFAbsoluteTime, pos: CGPoint, count: Int64) = (-1, 0, .zero, 0)
    /// §8.8: the ghost of a drag carried over by the explicit controller.
    private var ghost = DragGhostState()
    private var scrollRemainder = (x: 0.0, y: 0.0)

    // MARK: entry points (any thread)

    func submit(_ peer: String, _ msg: [String: Any]) {
        queue.async { self.handle(peer, msg) }
    }

    func peersChanged(_ peers: [PeerInfo]) {
        let ready = Set(peers.map(\.id))
        queue.async {
            for p in peers { self.names[p.id] = p.name }
            for peer in self.states.keys where !ready.contains(peer) { self.release(peer) }
            if let g = self.ghost.shown, !ready.contains(g.from) { self.apply(self.ghost.peerGone(g.from)) }
            if let l = self.lease, !ready.contains(l.peer) { self.setLease(nil) }
            self.busySent = self.busySent.filter { ready.contains($0.key) }
            self.startWatchdog()
        }
    }

    /// Posts an event tagged as ours.
    static func post(_ e: CGEvent?) {
        guard let e else { return }
        e.setIntegerValueField(.eventSourceUserData, value: tag)
        e.post(tap: .cghidEventTap)
    }

    // MARK: dispatch (queue)

    private func handle(_ peer: String, _ m: [String: Any]) {
        guard let t = m["t"] as? String else { return }
        switch t {
        case "ctl_enter":
            // Loop guard (§8.6): this Mac is controlling `peer`, so `peer` may not control it back.
            if ControlGate.shared.controlling == peer {
                PeerBus.shared.send(to: peer, ["t": "ctl_leave", "reason": "loop"])
                return
            }
            let edge = (m["edge"] as? String).flatMap(ScreenEdge.init(rawValue:)) ?? .left
            enter(peer, edge, Msg.num(m["rel"]) ?? 0.5)
            return
        case "ctl_leave":
            apply(ghost.leave(peer))
            if lease?.peer == peer { setLease(nil) }
            release(peer)
            return
        case "drag_carry", "drag_drop", "drag_cancel":
            dragMessage(peer, t, m)
            return
        case "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click", "ptr_abs", "key", "action":
            guard admit(peer) else {
                if t != "ptr_move" && t != "ptr_abs" && t != "ptr_scroll" {
                    InputLog.shared.log("DROPPED \(t)\((m["name"] as? String).map { " " + $0 } ?? "") from \(self.name(peer)): " +
                        (ControlGate.shared.controlling == peer ? "this Mac is controlling it (loop guard)"
                            : "lease held by \(lease.map { self.name($0.peer) } ?? "?")"))
                }
                return
            }
        default:
            return
        }
        switch t {
        case "ptr_move":
            move(peer, Msg.num(m["dx"]) ?? 0, Msg.num(m["dy"]) ?? 0)
        case "ptr_scroll":
            scroll(peer, Msg.num(m["dx"]) ?? 0, Msg.num(m["dy"]) ?? 0, px: Msg.bool(m["px"]) ?? true)
        case "ptr_btn":
            guard let b = Msg.int(m["b"]), let down = Msg.bool(m["down"]) else { return }
            InputLog.shared.log("button b=\(b) \(down ? "down" : "up") from \(name(peer))")
            button(peer, b, down: down)
        case "ptr_click":
            InputLog.shared.log("click b=\(Msg.int(m["b"]) ?? 0) n=\(Msg.int(m["n"]) ?? 1) from \(name(peer))")
            click(peer, Msg.int(m["b"]) ?? 0, count: Msg.int(m["n"]) ?? 1)
        case "ptr_abs":
            guard let x = Msg.num(m["x"]), let y = Msg.num(m["y"]) else { return }
            absolute(peer, x, y)
        case "key":
            let down = Msg.bool(m["down"]) ?? true
            key(peer, hid: Msg.int(m["hid"]) ?? 0, down: down, mods: Msg.int(m["mods"]),
                rep: Msg.bool(m["rep"]) ?? false, text: m["text"] as? String)
        case "action":
            if let name = m["name"] as? String { action(name, from: self.name(peer)) }
        default:
            break
        }
    }

    // MARK: carried drag (§8.8)

    /// Only the explicit (ctl_enter) holder may carry; the files themselves come as a normal transfer (§6) and
    /// are saved to ~/Downloads/AirBridge Transfers like any other.
    private func dragMessage(_ peer: String, _ t: String, _ m: [String: Any]) {
        let id = Msg.num(m["id"]).flatMap { $0.isFinite && $0 >= 0 && $0 < 9e15 ? Int($0) : nil } ?? -1
        switch t {
        case "drag_carry":
            guard let info = DragGhostState.info(from: peer, m) else { return }
            apply(ghost.carry(from: peer, holder: entered?.peer, info))
        case "drag_drop":
            apply(ghost.drop(from: peer, id: id))
        default:
            apply(ghost.cancel(from: peer, id: id))
        }
    }

    private func apply(_ e: DragGhostState.Effect) {
        let at = currentCursor()
        switch e {
        case .none:
            return
        case .show(let info):
            InputLog.shared.log("drag: \(name(info.from)) carries \(info.count) item(s)")
            DispatchQueue.main.async { DragGhost.shared.show(info, at: at) }
        case .hide:
            DispatchQueue.main.async { DragGhost.shared.hide(dropped: false) }
        case .dropped(let info, _):
            let from = name(info.from)
            let text = info.count == 1 && !info.firstName.isEmpty
                ? "Receiving \(info.firstName) from \(from)" : "Receiving \(info.count) items from \(from)"
            InputLog.shared.log("drag: \(from) dropped \(info.count) item(s) here")
            DispatchQueue.main.async {
                DragGhost.shared.hide(dropped: true)
                DragGhost.shared.note(text, at: at)
            }
        }
    }

    // MARK: lease (§8.6)

    private func name(_ peer: String) -> String { names[peer] ?? "another device" }

    private func setLease(_ l: Lease?) {
        let before = lease?.peer
        if let b = before, b != l?.peer || l?.edge == nil { apply(ghost.leave(b)) }
        let wasImplicit = lease?.edge == nil
        lease = l
        ControlGate.shared.controlledBy = l?.peer
        if before != l?.peer || wasImplicit != (l?.edge == nil) {
            ControlStatus.shared.setControlledBy(l.map { name($0.peer) }, implicit: l?.edge == nil)
        }
    }

    /// Implicit lease is stale: no input for 1.5 s and no button held (a held button keeps it until the
    /// 5 s watchdog, so a paused drag isn't broken by the lease timer).
    private func implicitExpired(_ l: Lease, _ now: CFAbsoluteTime) -> Bool {
        guard l.edge == nil else { return false }
        let holding = !(states[l.peer]?.buttons.isEmpty ?? true)
        return now - l.last > (holding ? Self.buttonWatchdog : Self.implicitLeaseTTL)
    }

    /// Input gate. Returns true if `peer` holds (or just took) the lease.
    private func admit(_ peer: String) -> Bool {
        let now = CFAbsoluteTimeGetCurrent()
        if ControlGate.shared.controlling == peer {
            // Loop guard (§8.6): we are driving this peer; its trackpad/keyboard must not drive us back.
            if now - (busySent[peer] ?? 0) >= Self.busyInterval {
                busySent[peer] = now
                PeerBus.shared.send(to: peer, ["t": "ctl_busy", "holder": Identity.name])
            }
            return false
        }
        if var l = lease {
            if l.peer == peer {
                l.last = now
                lease = l
                return true
            }
            if implicitExpired(l, now) {
                release(l.peer)
                setLease(nil)
            } else {
                if now - (busySent[peer] ?? 0) >= Self.busyInterval {
                    busySent[peer] = now
                    PeerBus.shared.send(to: peer, ["t": "ctl_busy", "holder": name(l.peer)])
                }
                return false
            }
        }
        setLease(Lease(peer: peer, edge: nil, last: now))
        startWatchdog()
        return true
    }

    private func startWatchdog() {
        guard watchdog == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 0.25, repeating: 0.25, leeway: .milliseconds(50))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        watchdog = t
    }

    /// Releases expired implicit leases and anything a silent trackpad/mirror peer left held.
    private func tick() {
        let now = CFAbsoluteTimeGetCurrent()
        if let l = lease, implicitExpired(l, now) {
            release(l.peer)
            setLease(nil)
        }
        // Belt and braces: buttons held by any non-explicit peer with no input for 5 s.
        for (peer, st) in states where !st.buttons.isEmpty && entered?.peer != peer {
            let last = lease?.peer == peer ? lease!.last : 0
            if now - last > Self.buttonWatchdog {
                NSLog("[AirBridge] inject: releasing buttons held by silent peer %@", name(peer))
                releaseButtons(peer)
            }
        }
    }

    // MARK: pointer

    private func geometry() -> DisplayGeometry {
        let now = CFAbsoluteTimeGetCurrent()
        if now - geoAt > 2 { geo = DisplayGeometry.current(); geoAt = now }
        return geo
    }

    private func currentCursor() -> CGPoint {
        if let c = cursor, CFAbsoluteTimeGetCurrent() - lastPost < 0.5 { return c }
        return CGEvent(source: nil)?.location ?? .zero
    }

    private var heldButtons: Set<Int> { states.values.reduce(into: Set<Int>()) { $0.formUnion($1.buttons) } }

    private func modifierFlags(_ st: PeerState?) -> CGEventFlags {
        var raw: UInt64 = 0
        for kc in st?.keys ?? [] {
            if let f = KeyMap.modifierFlags(forMac: kc) { raw |= f.device | f.generic.rawValue }
        }
        return CGEventFlags(rawValue: raw)
    }

    private func enter(_ peer: String, _ edge: ScreenEdge, _ rel: Double) {
        if let old = lease, old.peer != peer {
            // §8.1/§8.6: a person pushing a cursor in always wins.
            if let oldEdge = old.edge {
                // Hand the old controller its cursor back at its entry edge.
                let c = currentCursor()
                let g = geometry()
                PeerBus.shared.send(to: old.peer, ["t": "ctl_leave", "edge": oldEdge.rawValue,
                                                   "rel": Msg.round(g.fraction(oldEdge, oldEdge.isVertical ? c.y : c.x), 10000)])
            } else {
                // Implicit (trackpad/mirror) holder has no edge to return to: tell it who has the Mac now.
                busySent[old.peer] = CFAbsoluteTimeGetCurrent()
                PeerBus.shared.send(to: old.peer, ["t": "ctl_busy", "holder": name(peer)])
            }
            release(old.peer)
        }
        release(peer)
        setLease(Lease(peer: peer, edge: edge, last: CFAbsoluteTimeGetCurrent()))
        let g = geometry()
        let along = g.span(edge, max(0, min(1, rel)), 0).0
        let p = g.edgePoint(edge, along: along, inset: 1)
        Self.post(CGEvent(mouseEventSource: source, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: .left))
        syncCursor(p)
        cursor = p
        lastPost = CFAbsoluteTimeGetCurrent()
    }

    private func move(_ peer: String, _ dx: Double, _ dy: Double) {
        let isEntered = entered?.peer == peer
        let gain = isEntered ? 1.0 : Self.pref("AirBridge.trackpadGain", 1.8)
        let from = currentCursor()
        var p = CGPoint(x: from.x + dx * gain, y: from.y + dy * gain)
        let g = geometry()
        if !g.contains(p) {
            let c = g.clamp(p)
            if isEntered, let e = entered, pushedPast(e.edge, p, c, g) {
                let rel = g.fraction(e.edge, e.edge.isVertical ? c.y : c.x)
                PeerBus.shared.send(to: peer, ["t": "ctl_leave", "edge": e.edge.rawValue, "rel": Msg.round(rel, 10000)])
                setLease(nil)
                release(peer)
                return
            }
            p = c
        }
        postPointer(peer, to: p, from: from)
    }

    /// `ptr_abs`: normalised to the mirrored display when `peer` is our mirror sink, else the main display.
    private func absolute(_ peer: String, _ x: Double, _ y: Double) {
        let r = MirrorSource.shared.mirroredBounds(for: peer) ?? CGDisplayBounds(CGMainDisplayID())
        let fx = CGFloat(max(0, min(1, x))), fy = CGFloat(max(0, min(1, y)))
        let p = CGPoint(x: r.minX + fx * max(0, r.width - 1), y: r.minY + fy * max(0, r.height - 1))
        postPointer(peer, to: p, from: currentCursor())
    }

    /// Moves the cursor to `p`; a held button turns the move into a drag.
    private func postPointer(_ peer: String, to p: CGPoint, from: CGPoint) {
        let held = heldButtons
        let (type, btn): (CGEventType, CGMouseButton) =
            held.contains(0) ? (.leftMouseDragged, .left) :
            held.contains(1) ? (.rightMouseDragged, .right) :
            held.contains(2) ? (.otherMouseDragged, .center) : (.mouseMoved, .left)
        guard let e = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: p, mouseButton: btn) else { return }
        e.setIntegerValueField(.mouseEventDeltaX, value: Int64((p.x - from.x).rounded()))
        e.setIntegerValueField(.mouseEventDeltaY, value: Int64((p.y - from.y).rounded()))
        e.flags = modifierFlags(states[peer])
        Self.post(e)
        syncCursor(p)
        cursor = p
        lastPost = CFAbsoluteTimeGetCurrent()
        if ghost.shown != nil { DispatchQueue.main.async { DragGhost.shared.move(to: p) } }
    }

    /// Posting a mouse-moved event shows the cursor at `p`, but the HID layer keeps its own position. Without
    /// this the physical trackpad's next move starts from the OLD position and the cursor snaps back.
    private func syncCursor(_ p: CGPoint) {
        CGWarpMouseCursorPosition(p)
        // A warp briefly suppresses the physical trackpad; clear that for the source our own events use.
        source?.localEventsSuppressionInterval = 0
    }

    private func pushedPast(_ edge: ScreenEdge, _ p: CGPoint, _ c: CGPoint, _ g: DisplayGeometry) -> Bool {
        guard let r = g.display(at: c) else { return false }
        switch edge {
        case .left: return p.x < c.x - 0.25 && g.isOuter(c, of: r, .left)
        case .right: return p.x > c.x + 0.25 && g.isOuter(c, of: r, .right)
        case .top: return p.y < c.y - 0.25 && g.isOuter(c, of: r, .top)
        case .bottom: return p.y > c.y + 0.25 && g.isOuter(c, of: r, .bottom)
        }
    }

    private func scroll(_ peer: String, _ dx: Double, _ dy: Double, px: Bool) {
        // Wire values describe content motion (+dy = content up, +dx = content left); the controller
        // already applied its own natural-scrolling preference. CG: negative axis1 = content moves up.
        let gain = entered?.peer == peer ? 1.0 : Self.pref("AirBridge.trackpadScrollGain", 1.5)
        let a1 = -dy * gain
        let a2 = -dx * gain
        scrollRemainder.y += a1
        scrollRemainder.x += a2
        let i1 = scrollRemainder.y.rounded(.towardZero), i2 = scrollRemainder.x.rounded(.towardZero)
        if px {
            scrollRemainder.y -= i1
            scrollRemainder.x -= i2
            guard let e = CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
                                  wheel1: Int32(clamping: Int(i1)), wheel2: Int32(clamping: Int(i2)), wheel3: 0) else { return }
            e.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
            e.setDoubleValueField(.scrollWheelEventPointDeltaAxis1, value: a1)
            e.setDoubleValueField(.scrollWheelEventPointDeltaAxis2, value: a2)
            // Gesture phases (began → changed … → ended) give AppKit smooth, responsive scrolling.
            e.setIntegerValueField(.scrollWheelEventScrollPhase, value: scrollPhaseOpen ? 2 : 1)
            scrollPhaseOpen = true
            e.flags = modifierFlags(states[peer])
            Self.post(e)
            scheduleScrollEnd()
        } else {
            guard i1 != 0 || i2 != 0 else { return }
            scrollRemainder.y -= i1
            scrollRemainder.x -= i2
            guard let e = CGEvent(scrollWheelEvent2Source: source, units: .line, wheelCount: 2,
                                  wheel1: Int32(clamping: Int(i1)), wheel2: Int32(clamping: Int(i2)), wheel3: 0) else { return }
            e.flags = modifierFlags(states[peer])
            Self.post(e)
        }
    }

    /// Closes the scroll gesture 120 ms after the last pixel delta.
    private func scheduleScrollEnd() {
        scrollEnd?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.endScrollPhase() }
        scrollEnd = w
        queue.asyncAfter(deadline: .now() + 0.12, execute: w)
    }

    private func endScrollPhase() {
        guard scrollPhaseOpen else { return }
        scrollPhaseOpen = false
        scrollRemainder = (0, 0)
        guard let e = CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2, wheel1: 0, wheel2: 0, wheel3: 0) else { return }
        e.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        e.setIntegerValueField(.scrollWheelEventScrollPhase, value: 4) // ended
        Self.post(e)
    }

    private func mouseEvent(_ b: Int, down: Bool, at p: CGPoint, clicks: Int64, flags: CGEventFlags) {
        let type: CGEventType
        let btn: CGMouseButton
        switch b {
        case 0: type = down ? .leftMouseDown : .leftMouseUp; btn = .left
        case 1: type = down ? .rightMouseDown : .rightMouseUp; btn = .right
        default: type = down ? .otherMouseDown : .otherMouseUp; btn = .center
        }
        guard let e = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: p, mouseButton: btn) else { return }
        e.setIntegerValueField(.mouseEventClickState, value: clicks)
        e.flags = flags
        Self.post(e)
    }

    private func nextClickCount(_ b: Int, _ p: CGPoint) -> Int64 {
        let now = CFAbsoluteTimeGetCurrent()
        let interval = UserDefaults.standard.object(forKey: "com.apple.mouse.doubleClickThreshold") as? Double ?? 0.5
        let same = lastDown.button == b && now - lastDown.time <= interval && hypot(p.x - lastDown.pos.x, p.y - lastDown.pos.y) < 6
        let n: Int64 = same ? min(lastDown.count + 1, 3) : 1
        lastDown = (b, now, p, n)
        return n
    }

    private func button(_ peer: String, _ b: Int, down: Bool) {
        guard (0...2).contains(b) else { return }
        var st = states[peer] ?? PeerState()
        if down {
            guard st.buttons.insert(b).inserted else { return }
        } else {
            guard st.buttons.remove(b) != nil else { return }
        }
        states[peer] = st
        let p = currentCursor()
        let clicks = down ? nextClickCount(b, p) : (lastDown.button == b ? lastDown.count : 1)
        mouseEvent(b, down: down, at: p, clicks: clicks, flags: modifierFlags(st))
    }

    /// §8.2: one click per message; `n` is this click's count (1, then 2 for a double tap). Never
    /// synthesise extra clicks.
    private func click(_ peer: String, _ b: Int, count: Int) {
        guard (0...2).contains(b) else { return }
        let p = currentCursor()
        let flags = modifierFlags(states[peer])
        let n = Int64(max(1, min(count, 3)))
        lastDown = (b, CFAbsoluteTimeGetCurrent(), p, n)
        mouseEvent(b, down: true, at: p, clicks: n, flags: flags)
        usleep(6_000)
        mouseEvent(b, down: false, at: p, clicks: n, flags: flags)
    }

    // MARK: keyboard

    private func keyEvent(_ kc: UInt16, down: Bool, flags: CGEventFlags, rep: Bool = false) {
        guard let e = CGEvent(keyboardEventSource: source, virtualKey: kc, keyDown: down) else { return }
        if KeyMap.modifierFlags(forMac: kc) != nil { e.type = .flagsChanged }
        e.flags = flags
        if rep { e.setIntegerValueField(.keyboardEventAutorepeat, value: 1) }
        Self.post(e)
    }

    private func key(_ peer: String, hid: Int, down: Bool, mods: Int?, rep: Bool, text: String?) {
        switch hid {
        case 0x7F: if down { pressMediaKey(7) }; return
        case 0x80: if down { pressMediaKey(0) }; return
        case 0x81: if down { pressMediaKey(1) }; return
        case 0x39: if down { toggleCapsLock() }; return  // posted caps-lock keys don't change the lock state
        default: break
        }
        guard let kc = KeyMap.mac(forHid: hid) else {
            if down, let text, !text.isEmpty { typeText(text) }
            return
        }
        var st = states[peer] ?? PeerState()
        if KeyMap.modifierFlags(forMac: kc) != nil {
            if down { guard st.keys.insert(kc).inserted else { return } } else { guard st.keys.remove(kc) != nil else { return } }
            states[peer] = st
            keyEvent(kc, down: down, flags: modifierFlags(st))
            return
        }
        if down { st.keys.insert(kc) } else { guard st.keys.remove(kc) != nil else { return } }
        states[peer] = st
        var flags = mods.map(KeyMap.flags(fromMods:)) ?? modifierFlags(st)
        flags.formUnion(KeyMap.extraFlags(forMac: kc))
        keyEvent(kc, down: down, flags: flags, rep: rep)
    }

    /// Types arbitrary text independent of the keyboard layout.
    func typeText(_ text: String) {
        let units = Array(text.utf16)
        var i = 0
        while i < units.count {
            var chunk = Array(units[i..<min(i + 20, units.count)])
            // Don't split a surrogate pair across events.
            if chunk.count == 20, UTF16.isLeadSurrogate(chunk[19]) { chunk.removeLast() }
            for down in [true, false] {
                guard let e = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down) else { continue }
                e.flags = []
                e.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
                Self.post(e)
            }
            i += chunk.count
        }
    }

    private func releaseButtons(_ peer: String) {
        guard var st = states[peer], !st.buttons.isEmpty else { return }
        let p = currentCursor()
        for b in st.buttons { mouseEvent(b, down: false, at: p, clicks: 1, flags: []) }
        st.buttons.removeAll()
        states[peer] = st
    }

    private func release(_ peer: String) {
        endScrollPhase()
        guard let st = states.removeValue(forKey: peer) else { return }
        let p = currentCursor()
        for kc in st.keys where KeyMap.modifierFlags(forMac: kc) == nil { keyEvent(kc, down: false, flags: []) }
        var remaining = st
        for kc in st.keys where KeyMap.modifierFlags(forMac: kc) != nil {
            remaining.keys.remove(kc)
            keyEvent(kc, down: false, flags: modifierFlags(remaining))
        }
        for b in st.buttons { mouseEvent(b, down: false, at: p, clicks: 1, flags: []) }
    }

    private func toggleCapsLock() {
        guard let current = Self.readCapsLock() else { return }
        let target = !current
        Self.writeCapsLock(target)
        // Report the state we just set, not a read-back: the system reports the OLD value for a moment after the
        // write, which made the phone drop its lit Caps key again while the Mac's Caps Lock stayed on.
        announceCaps(target)
        // Re-check shortly after and correct only if the system really ended up somewhere else.
        queue.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self, let actual = Self.readCapsLock(), actual != target else { return }
            self.announceCaps(actual)
        }
    }

    private func announceCaps(_ on: Bool) {
        // Our own toggle raises no event the controller's tap would report: tell everyone (the phone's Caps key
        // follows this) and keep the controller's tracker in step so physical toggles are still noticed.
        Controller.shared.perform { Controller.shared.syncCaps(on) }
        for p in PeerBus.shared.peers() where p.caps.contains("trackpad") || p.caps.contains("ctl_target") {
            PeerBus.shared.send(to: p.id, ["t": "key_state", "caps": on])
        }
    }

    private static func withHIDSystem<T>(_ body: (io_connect_t) -> T) -> T? {
        let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching(kIOHIDSystemClass))
        guard service != 0 else { return nil }
        defer { IOObjectRelease(service) }
        var conn: io_connect_t = 0
        guard IOServiceOpen(service, mach_task_self_, UInt32(kIOHIDParamConnectType), &conn) == KERN_SUCCESS else { return nil }
        defer { IOServiceClose(conn) }
        return body(conn)
    }

    private static func readCapsLock() -> Bool? {
        withHIDSystem { conn in
            var on = false
            IOHIDGetModifierLockState(conn, Int32(kIOHIDCapsLockState), &on)
            return on
        }
    }

    private static func writeCapsLock(_ on: Bool) {
        _ = withHIDSystem { conn in IOHIDSetModifierLockState(conn, Int32(kIOHIDCapsLockState), on) }
    }

    /// Modifier chord, e.g. chord([kVK_Control], kVK_UpArrow), as real flagsChanged + key events.
    private func chord(_ mods: [UInt16], _ key: UInt16) {
        var flags: CGEventFlags = []
        for m in mods {
            guard let f = KeyMap.modifierFlags(forMac: m) else { continue }
            flags.formUnion(CGEventFlags(rawValue: f.device | f.generic.rawValue))
            keyEvent(m, down: true, flags: flags)
        }
        let kf = flags.union(KeyMap.extraFlags(forMac: key))
        keyEvent(key, down: true, flags: kf)
        usleep(10_000)
        keyEvent(key, down: false, flags: kf)
        for m in mods.reversed() {
            guard let f = KeyMap.modifierFlags(forMac: m) else { continue }
            flags.subtract(CGEventFlags(rawValue: f.device | f.generic.rawValue))
            keyEvent(m, down: false, flags: flags)
        }
    }

    // MARK: actions

    /// Launchpad (macOS ≤ 15) or the Apps view (macOS 26+).
    private func openAppsGrid() {
        for path in ["/System/Applications/Apps.app", "/System/Applications/Launchpad.app"]
        where FileManager.default.fileExists(atPath: path) {
            NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: path), configuration: NSWorkspace.OpenConfiguration())
            return
        }
    }

    /// How an action is carried out. Logged with every action (InputLog), and forceable from the
    /// DEBUG-only link airbridge://debug/action?name=…&via=… so each path can be tested on its own.
    enum Via: String { case coredock, sysevents, cgevent, dockpref, applescript }

    /// DEBUG builds: run `name` through `via` (or its normal path) as if a peer had sent it.
    func debugAction(_ name: String, via: String?) {
        queue.async { self.action(name, from: "debug link", forced: via.flatMap(Via.init(rawValue:))) }
    }

    private func action(_ name: String, from peer: String = "", forced: Via? = nil) {
        let who = peer.isEmpty ? "" : " from \(peer)"
        if let nx = KeyMap.nxKeyType(forAction: name) {
            pressMediaKey(nx)
            InputLog.shared.log("action \(name)\(who) -> media key \(nx)")
            return
        }
        InputLog.shared.timed("action \(name)\(who)") { () -> String in
            switch name {
            // Mission Control / App Exposé / Show Desktop: the Dock's own notifications (verified on macOS 26.5 for
            // Mission Control and App Exposé). Launching the Mission Control binary does nothing on macOS 26
            // (verified), which is what killed swipe up / down. Dock hiding: ⌥⌘D through System Events, like v1.
            case "mission_control": return run(name, forced ?? .coredock, hotkeyId: 32, def: (0x40000, 0x7E), dockNote: "com.apple.expose.awake")
            case "app_expose": return run(name, forced ?? .coredock, hotkeyId: 33, def: (0x40000, 0x7D), dockNote: "com.apple.expose.front.awake")
            case "show_desktop": return run(name, forced ?? .coredock, hotkeyId: 36, def: (0x800000, 0x67), dockNote: "com.apple.showdesktop.awake")
            case "desktop_left": return run(name, forced ?? .cgevent, hotkeyId: 79, def: (0x40000, 0x7B))
            case "desktop_right": return run(name, forced ?? .cgevent, hotkeyId: 81, def: (0x40000, 0x7C))
            case "spotlight": return run(name, forced ?? .cgevent, hotkeyId: 64, def: (0x100000, 0x31))
            case "dock_toggle": return run(name, forced ?? .sysevents, hotkeyId: 52, def: (0x180000, 0x02))
            case "screenshot": return run(name, forced ?? .cgevent, hotkeyId: 184, def: (0x120000, 0x17))
            case "menu_bar_toggle": return toggleMenuBarHiding()
            case "notifications": showNotificationCenter(); return "fn+N (or the user's shortcut)"
            case "app_drawer": openAppsGrid(); return "opened the Apps view / Launchpad"
            case "zoom_in": chord([0x37], 0x18); return "Cmd+="
            case "zoom_out": chord([0x37], 0x1B); return "Cmd+-"
            case "look_up": chord([0x3B, 0x37], 0x02); return "Ctrl+Cmd+D (force click)"
            case "copy": chord([0x37], 0x08); return "Cmd+C"
            case "paste": chord([0x37], 0x09); return "Cmd+V"
            case "lock": lockScreen(); return "locked"
            case "keyboard_toggle": toggleAccessibilityKeyboard(); return "Accessibility Keyboard toggled"
            default: return "ignored (no Mac meaning)"
            }
        }
    }

    /// One system shortcut through the chosen path. `def` = (modifier mask as in symbolichotkeys, keycode).
    private func run(_ name: String, _ via: Via, hotkeyId: Int, def: (Int, Int), dockNote: String? = nil) -> String {
        switch via {
        case .coredock:
            guard let note = dockNote, let send = Self.coreDockSend else {
                return run(name, .sysevents, hotkeyId: hotkeyId, def: def) + " (no Dock call; fell back)"
            }
            send(note as CFString, 0)
            return "Dock notification \(note)"
        case .dockpref:
            guard name == "dock_toggle", let get = Self.coreDockGetAutoHide, let set = Self.coreDockSetAutoHide else {
                return run(name, .sysevents, hotkeyId: hotkeyId, def: def)
            }
            let was = get()
            set(!was)
            return "Dock auto-hide \(was) -> \(!was) (CoreDock)"
        case .sysevents:
            let (kc, mask, from) = resolveHotkey(hotkeyId, def)
            systemEventsKey(kc, mask, label: "\(name) [\(from)]")
            return "System Events key code \(kc) mods \(Self.modNames(mask)) [\(from)] (queued; result logged next)"
        case .cgevent, .applescript:
            let (kc, mask, from) = resolveHotkey(hotkeyId, def)
            var mods: [UInt16] = []
            if mask & 0x40000 != 0 { mods.append(0x3B) }   // control
            if mask & 0x80000 != 0 { mods.append(0x3A) }   // option
            if mask & 0x20000 != 0 { mods.append(0x38) }   // shift
            if mask & 0x100000 != 0 { mods.append(0x37) }  // command
            chord(mods, UInt16(kc)) // F-keys and arrows carry fn (+ keypad) automatically (KeyMap.extraFlags)
            return "posted keycode \(kc) mods \(Self.modNames(mask)) [\(from)]"
        }
    }

    /// The user's shortcut for a symbolic hotkey (com.apple.symbolichotkeys), else the macOS default.
    private func resolveHotkey(_ id: Int, _ def: (Int, Int)) -> (kc: Int, mask: Int, from: String) {
        let all = UserDefaults(suiteName: "com.apple.symbolichotkeys")?.dictionary(forKey: "AppleSymbolicHotKeys")
        guard let entry = all?[String(id)] as? [String: Any] else { return (def.1, def.0, "default") }
        if let on = Msg.bool(entry["enabled"]), !on { return (def.1, def.0, "default; user's shortcut \(id) is OFF") }
        guard let value = entry["value"] as? [String: Any], let params = value["parameters"] as? [Any], params.count >= 3,
              let kc = Msg.int(params[1]), kc >= 0, kc < 0xFFFF, let m = Msg.int(params[2]) else { return (def.1, def.0, "default") }
        return (kc, m, "user's shortcut")
    }

    private static func modNames(_ m: Int) -> String {
        var n: [String] = []
        if m & 0x40000 != 0 { n.append("ctrl") }
        if m & 0x80000 != 0 { n.append("opt") }
        if m & 0x20000 != 0 { n.append("shift") }
        if m & 0x100000 != 0 { n.append("cmd") }
        if m & 0x800000 != 0 { n.append("fn") }
        return n.isEmpty ? "none" : n.joined(separator: "+")
    }

    /// The path v1 used: System Events types the shortcut (asks once for Automation permission).
    /// Its own serial queue, so a slow first run never stalls pointer input.
    private static let sysEventsQueue = DispatchQueue(label: "com.airbridge.sysevents", qos: .userInitiated)
    private func systemEventsKey(_ kc: Int, _ mask: Int, label: String) {
        var parts: [String] = []
        if mask & 0x40000 != 0 { parts.append("control down") }
        if mask & 0x80000 != 0 { parts.append("option down") }
        if mask & 0x20000 != 0 { parts.append("shift down") }
        if mask & 0x100000 != 0 { parts.append("command down") }
        let using = parts.isEmpty ? "" : " using {\(parts.joined(separator: ", "))}"
        let src = "tell application \"System Events\" to key code \(kc)\(using)"
        Self.sysEventsQueue.async {
            let t0 = CFAbsoluteTimeGetCurrent()
            var err: NSDictionary?
            NSAppleScript(source: src)?.executeAndReturnError(&err)
            let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
            if let err {
                InputLog.shared.log("  \(label): System Events FAILED \(err[NSAppleScript.errorNumber] ?? "?") \(err[NSAppleScript.errorMessage] ?? "") (\(ms) ms)")
                if (err[NSAppleScript.errorNumber] as? Int) == -1743 { Self.tellAutomationDenied() }
            } else {
                InputLog.shared.log("  \(label): System Events ok (\(ms) ms)")
            }
        }
    }

    private static func tellAutomationDenied() {
        DispatchQueue.main.async {
            let n = NSUserNotification()
            n.title = "AirBridge can't use System Events"
            n.informativeText = "Allow it in System Settings › Privacy & Security › Automation so phone shortcuts can reach the Dock."
            NSUserNotificationCenter.default.deliver(n)
        }
    }

    /// Dock private calls (HIServices). Mission Control through CoreDockSendNotification is verified on macOS 26.
    private static let hiServices = dlopen("/System/Library/Frameworks/ApplicationServices.framework/Frameworks/HIServices.framework/HIServices", RTLD_LAZY)
    private typealias CoreDockSendFn = @convention(c) (CFString, Int32) -> Void
    private typealias CoreDockGetFn = @convention(c) () -> Bool
    private typealias CoreDockSetFn = @convention(c) (Bool) -> Void
    private static let coreDockSend: CoreDockSendFn? = dlsym(hiServices, "CoreDockSendNotification").map { unsafeBitCast($0, to: CoreDockSendFn.self) }
    private static let coreDockGetAutoHide: CoreDockGetFn? = dlsym(hiServices, "CoreDockGetAutoHideEnabled").map { unsafeBitCast($0, to: CoreDockGetFn.self) }
    private static let coreDockSetAutoHide: CoreDockSetFn? = dlsym(hiServices, "CoreDockSetAutoHideEnabled").map { unsafeBitCast($0, to: CoreDockSetFn.self) }

    /// Flips "Automatically hide and show the menu bar" through System Events (asks once for Automation
    /// permission). Off the injector queue: the first run waits for the user's answer.
    private func toggleMenuBarHiding() -> String {
        Self.sysEventsQueue.async {
            let src = """
            tell application "System Events" to tell dock preferences
                set autohide menu bar to not autohide menu bar
                return autohide menu bar
            end tell
            """
            var err: NSDictionary?
            let r = NSAppleScript(source: src)?.executeAndReturnError(&err)
            if let err {
                InputLog.shared.log("  menu_bar_toggle: System Events FAILED \(err[NSAppleScript.errorNumber] ?? "?") \(err[NSAppleScript.errorMessage] ?? "")")
                if (err[NSAppleScript.errorNumber] as? Int) == -1743 { Self.tellAutomationDenied() }
            } else {
                InputLog.shared.log("  menu_bar_toggle: autohide menu bar is now \(r?.booleanValue ?? false)")
            }
        }
        return "System Events dock preferences (queued; result logged next)"
    }

    /// Notification Center: the user's shortcut if they set one ("Show Notification Center"), else
    /// fn/Globe+N, the system default since macOS 12.
    private func showNotificationCenter() {
        let all = UserDefaults(suiteName: "com.apple.symbolichotkeys")?.dictionary(forKey: "AppleSymbolicHotKeys")
        if let entry = all?["163"] as? [String: Any], Msg.bool(entry["enabled"]) == true {
            _ = run("notifications", .cgevent, hotkeyId: 163, def: (0x800000, 0x2D))
            return
        }
        let fn = CGEventFlags.maskSecondaryFn
        func fnKey(_ down: Bool) { // kVK_Function as a real modifier change
            guard let e = CGEvent(keyboardEventSource: source, virtualKey: 0x3F, keyDown: down) else { return }
            e.type = .flagsChanged
            e.flags = down ? fn : []
            Self.post(e)
        }
        fnKey(true)
        keyEvent(0x2D, down: true, flags: fn)   // N
        usleep(10_000)
        keyEvent(0x2D, down: false, flags: fn)
        fnKey(false)
    }

    private func lockScreen() {
        typealias LockFn = @convention(c) () -> Int32
        if let h = dlopen("/System/Library/PrivateFrameworks/login.framework/Versions/Current/login", RTLD_LAZY),
           let sym = dlsym(h, "SACLockScreenImmediate") {
            _ = unsafeBitCast(sym, to: LockFn.self)()
            return
        }
        chord([0x3B, 0x37], 0x0C) // Ctrl+Cmd+Q
    }

    private func toggleAccessibilityKeyboard() {
        DispatchQueue.main.async {
            let id = "com.apple.inputmethod.AssistiveControl"
            if let app = NSRunningApplication.runningApplications(withBundleIdentifier: id).first {
                app.terminate()
            } else {
                let url = URL(fileURLWithPath: "/System/Library/Input Methods/Assistive Control.app")
                NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration())
            }
        }
    }

    /// NX_SYSDEFINED media key (volume, brightness, play/pause…).
    private func pressMediaKey(_ nx: Int) {
        for state in [0xA, 0xB] {
            let ev = NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(state << 8)),
                                        timestamp: 0, windowNumber: 0, context: nil, subtype: 8,
                                        data1: (nx << 16) | (state << 8), data2: -1)
            Self.post(ev?.cgEvent)
            if state == 0xA { usleep(10_000) }
        }
    }

    private static func pref(_ key: String, _ fallback: Double) -> Double {
        let v = UserDefaults.standard.double(forKey: key)
        return v > 0 ? v : fallback
    }
}
