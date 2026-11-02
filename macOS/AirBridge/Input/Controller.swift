import Cocoa
import CoreGraphics
import ApplicationServices

/// Controller side (protocol.md §8): the Mac's physical mouse/keyboard driving a remote target.
/// Owns the session event tap, which runs on its own thread; all state below is confined to it.
/// Other threads hop in with `perform`.
nonisolated final class Controller: @unchecked Sendable {
    static let shared = Controller()

    private var runLoop: CFRunLoop?
    private var tap: CFMachPort?
    private var flushTimer: CFRunLoopTimer?

    private struct Session {
        let peer: String
        let edge: ScreenEdge             // Mac edge the cursor left through
        let span: (CGFloat, CGFloat)     // global coordinate range of the portal along that edge
        let anchor: CGPoint              // frozen cursor position
    }
    private var session: Session?
    private var remoteKeys: Set<UInt16> = []      // Mac keycodes whose down was sent to the target
    private var remoteButtons: Set<Int> = []
    private var localKeys: Set<UInt16> = []       // keys the Mac currently sees held
    private var localButtons: Set<Int> = []
    private var swallowKeyUps: Set<UInt16> = []   // ups whose down went to a target
    private var swallowButtonUps: Set<Int> = []
    private var pendingDX = 0.0, pendingDY = 0.0
    private var lastMoveSent: CFAbsoluteTime = 0
    private var escDowns: [CFAbsoluteTime] = []
    private var lastReturn: CFAbsoluteTime = 0
    private var capsOn = false
    private var peers: [PeerInfo] = []
    private var geo = DisplayGeometry.current()
    private var geoAt: CFAbsoluteTime = 0
    private var natural = true
    private var naturalAt: CFAbsoluteTime = 0

    private static let moveInterval = 1.0 / 120.0

    // MARK: thread / tap lifecycle

    /// Starts the tap thread (once). Blocks briefly until its run loop exists.
    func start() {
        guard runLoop == nil else { return }
        let ready = DispatchSemaphore(value: 0)
        let t = Thread { [self] in
            let rl = CFRunLoopGetCurrent()!
            runLoop = rl
            capsOn = CGEventSource.flagsState(.hidSystemState).contains(.maskAlphaShift)
            // Polls until Accessibility is granted, then keeps the tap installed and enabled.
            let poll = CFRunLoopTimerCreateWithHandler(nil, CFAbsoluteTimeGetCurrent(), 2.0, 0, 0) { [weak self] _ in
                self?.ensureTap()
            }
            CFRunLoopAddTimer(rl, poll, .commonModes)
            let flush = CFRunLoopTimerCreateWithHandler(nil, CFAbsoluteTimeGetCurrent() + 1e9, 1e9, 0, 0) { [weak self] _ in
                self?.flushMoves()
            }
            CFRunLoopAddTimer(rl, flush, .commonModes)
            flushTimer = flush
            ready.signal()
            CFRunLoopRun()
        }
        t.name = "AirBridge.InputTap"
        t.qualityOfService = .userInteractive
        t.start()
        ready.wait()
    }

    func perform(_ block: @escaping () -> Void) {
        guard let rl = runLoop else { return }
        CFRunLoopPerformBlock(rl, CFRunLoopMode.commonModes.rawValue, block)
        CFRunLoopWakeUp(rl)
    }

    private func ensureTap() {
        if let tap {
            if !CGEvent.tapIsEnabled(tap: tap) { CGEvent.tapEnable(tap: tap, enable: true) }
            return
        }
        guard AXIsProcessTrusted() else { return }
        let types: [UInt32] = [1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 14, 22, 25, 26, 27]
        let mask = types.reduce(CGEventMask(0)) { $0 | (CGEventMask(1) << CGEventMask($1)) }
        let callback: CGEventTapCallBack = { _, type, event, refcon in
            let c = Unmanaged<Controller>.fromOpaque(refcon!).takeUnretainedValue()
            return c.handle(type, event)
        }
        guard let t = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                        eventsOfInterest: mask, callback: callback,
                                        userInfo: Unmanaged.passUnretained(self).toOpaque()) else { return }
        tap = t
        CFRunLoopAddSource(runLoop, CFMachPortCreateRunLoopSource(kCFAllocatorDefault, t, 0), .commonModes)
        CGEvent.tapEnable(tap: t, enable: true)
    }

    // MARK: external events (tap thread via perform)

    func invalidateGeometry() { geoAt = 0 }

    func peersChanged(_ list: [PeerInfo]) {
        peers = list
        if let s = session, !list.contains(where: { $0.id == s.peer }) {
            end(resumeAt: inward(s.anchor, s.edge), sendLeave: false)
        }
    }

    /// `ctl_leave` from the peer we control: resume at the matching point of our edge.
    func remoteLeave(peer: String, rel: Double?) {
        guard let s = session, s.peer == peer else { return }
        var p = inward(s.anchor, s.edge)
        if let rel {
            let along = s.span.0 + (s.span.1 - s.span.0) * CGFloat(max(0, min(1, rel)))
            p = geometry().edgePoint(s.edge, along: along, inset: 3)
        }
        end(resumeAt: p, sendLeave: false)
    }

    /// The peer we control started controlling us (`ctl_enter`): stop without echoing a leave.
    func peerTookOver(_ peer: String) {
        if let s = session, s.peer == peer { end(resumeAt: inward(s.anchor, s.edge), sendLeave: false) }
    }

    /// Local recall (hotkey / legacy call): send `ctl_leave` and return to where we left.
    func recall() {
        if let s = session { end(resumeAt: inward(s.anchor, s.edge), sendLeave: true) }
    }

    // MARK: tap callback

    private func handle(_ type: CGEventType, _ event: CGEvent) -> Unmanaged<CGEvent>? {
        let pass = Unmanaged.passUnretained(event)
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
            return pass
        }
        if event.getIntegerValueField(.eventSourceUserData) == Injector.tag { return pass }
        if type == .flagsChanged { noteCaps(event.flags) }
        if session == nil { return handleLocal(type, event) ? pass : nil }
        return handleRemote(type, event) ? pass : nil
    }

    private static func buttonNumber(_ type: CGEventType, _ e: CGEvent) -> Int {
        switch type {
        case .leftMouseDown, .leftMouseUp, .leftMouseDragged: return 0
        case .rightMouseDown, .rightMouseUp, .rightMouseDragged: return 1
        default: return Int(e.getIntegerValueField(.mouseEventButtonNumber))
        }
    }

    /// Returns false to swallow.
    private func handleLocal(_ type: CGEventType, _ e: CGEvent) -> Bool {
        switch type {
        case .keyDown:
            let kc = UInt16(e.getIntegerValueField(.keyboardEventKeycode))
            swallowKeyUps.remove(kc)
            localKeys.insert(kc)
        case .keyUp:
            let kc = UInt16(e.getIntegerValueField(.keyboardEventKeycode))
            localKeys.remove(kc)
            if swallowKeyUps.remove(kc) != nil { return false }
        case .leftMouseDown, .rightMouseDown, .otherMouseDown:
            let b = Self.buttonNumber(type, e)
            swallowButtonUps.remove(b)
            localButtons.insert(b)
        case .leftMouseUp, .rightMouseUp, .otherMouseUp:
            let b = Self.buttonNumber(type, e)
            localButtons.remove(b)
            if swallowButtonUps.remove(b) != nil { return false }
        case .mouseMoved:
            // A plain move means no button is down (drags arrive as *Dragged and never cross).
            localButtons.removeAll()
            if let hit = portalHit(e) {
                begin(hit.peer, hit.edge, hit.span, hit.rel, e)
                return false
            }
        default:
            break
        }
        return true
    }

    private func geometry() -> DisplayGeometry {
        let now = CFAbsoluteTimeGetCurrent()
        if now - geoAt > 2 { geo = DisplayGeometry.current(); geoAt = now }
        return geo
    }

    private func portalHit(_ e: CGEvent) -> (peer: String, edge: ScreenEdge, span: (CGFloat, CGFloat), rel: Double)? {
        guard CFAbsoluteTimeGetCurrent() - lastReturn > 0.25 else { return nil }
        let targets = peers.filter { $0.caps.contains("ctl_target") }
        guard !targets.isEmpty else { return nil }
        let p = e.location
        let dx = e.getDoubleValueField(.mouseEventDeltaX), dy = e.getDoubleValueField(.mouseEventDeltaY)
        let g = geometry()
        guard let r = g.display(at: p) else { return nil }
        for peer in targets {
            guard let pl = Layout.shared.placement(for: peer.id), let edge = pl.edge else { continue }
            let outward: Bool
            switch edge {
            case .left: outward = dx < 0
            case .right: outward = dx > 0
            case .top: outward = dy < 0
            case .bottom: outward = dy > 0
            }
            guard outward, g.isOuter(p, of: r, edge) else { continue }
            let span = g.span(edge, pl.start, pl.end)
            let along = edge.isVertical ? p.y : p.x
            guard along >= span.0 - 0.5, along <= span.1 + 0.5 else { continue }
            let rel = Double(max(0, min(1, (along - span.0) / max(1, span.1 - span.0))))
            return (peer.id, edge, span, rel)
        }
        return nil
    }

    private func begin(_ peer: String, _ edge: ScreenEdge, _ span: (CGFloat, CGFloat), _ rel: Double, _ e: CGEvent) {
        // Release everything the Mac thinks is held so nothing sticks while we're away.
        for kc in localKeys where KeyMap.modifierFlags(forMac: kc) == nil {
            Injector.post(CGEvent(keyboardEventSource: nil, virtualKey: kc, keyDown: false))
        }
        localKeys.removeAll()
        var raw = e.flags.rawValue
        for kc in KeyMap.modifierKeycodes {
            guard let f = KeyMap.modifierFlags(forMac: kc), raw & f.device != 0 else { continue }
            raw &= ~f.device
            let sameKindHeld = KeyMap.modifierKeycodes.contains {
                guard let o = KeyMap.modifierFlags(forMac: $0) else { return false }
                return o.generic == f.generic && raw & o.device != 0
            }
            if !sameKindHeld { raw &= ~f.generic.rawValue }
            let up = CGEvent(keyboardEventSource: nil, virtualKey: kc, keyDown: false)
            up?.type = .flagsChanged
            up?.flags = CGEventFlags(rawValue: raw)
            Injector.post(up)
        }

        let anchor = e.location
        session = Session(peer: peer, edge: edge, span: span, anchor: anchor)
        remoteKeys.removeAll()
        remoteButtons.removeAll()
        escDowns.removeAll()
        pendingDX = 0; pendingDY = 0
        CGAssociateMouseAndMouseCursorPosition(0)
        DispatchQueue.main.async { CursorHider.hide() }
        PeerBus.shared.send(to: peer, ["t": "ctl_enter", "edge": edge.opposite.rawValue, "rel": Msg.round(rel, 10000)])
        PeerBus.shared.send(to: peer, ["t": "key_state", "caps": capsOn])
    }

    private func end(resumeAt p: CGPoint, sendLeave: Bool) {
        guard let s = session else { return }
        flushMoves()
        if sendLeave { PeerBus.shared.send(to: s.peer, ["t": "ctl_leave"]) }
        swallowKeyUps.formUnion(remoteKeys.filter { KeyMap.modifierFlags(forMac: $0) == nil })
        swallowButtonUps.formUnion(remoteButtons)
        remoteKeys.removeAll()
        remoteButtons.removeAll()
        escDowns.removeAll()
        session = nil
        lastReturn = CFAbsoluteTimeGetCurrent()
        CGWarpMouseCursorPosition(p)
        CGAssociateMouseAndMouseCursorPosition(1)
        DispatchQueue.main.async { CursorHider.show() }
    }

    private func inward(_ p: CGPoint, _ edge: ScreenEdge) -> CGPoint {
        switch edge {
        case .left: return CGPoint(x: p.x + 3, y: p.y)
        case .right: return CGPoint(x: p.x - 3, y: p.y)
        case .top: return CGPoint(x: p.x, y: p.y + 3)
        case .bottom: return CGPoint(x: p.x, y: p.y - 3)
        }
    }

    // MARK: forwarding while controlling

    /// Returns false to swallow (almost everything is swallowed while controlling).
    private func handleRemote(_ type: CGEventType, _ e: CGEvent) -> Bool {
        guard let s = session else { return true }
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            let loc = e.location
            if abs(loc.x - s.anchor.x) > 5 || abs(loc.y - s.anchor.y) > 5 { CGWarpMouseCursorPosition(s.anchor) }
            queueMove(e.getDoubleValueField(.mouseEventDeltaX), e.getDoubleValueField(.mouseEventDeltaY))
        case .leftMouseDown, .rightMouseDown, .otherMouseDown:
            let b = Self.buttonNumber(type, e)
            guard b <= 2, remoteButtons.insert(b).inserted else { return false }
            flushMoves()
            send(["t": "ptr_btn", "b": b, "down": true])
        case .leftMouseUp, .rightMouseUp, .otherMouseUp:
            let b = Self.buttonNumber(type, e)
            guard remoteButtons.remove(b) != nil else { return false }
            flushMoves()
            send(["t": "ptr_btn", "b": b, "down": false])
        case .scrollWheel:
            forwardScroll(e)
        case .keyDown, .keyUp, .flagsChanged:
            forwardKey(type, e)
        default:
            if type.rawValue == 14 { return !forwardSystemDefined(e) }
            return true
        }
        return false
    }

    private func send(_ msg: [String: Any]) {
        if let s = session { PeerBus.shared.send(to: s.peer, msg) }
    }

    private func queueMove(_ dx: Double, _ dy: Double) {
        pendingDX += dx
        pendingDY += dy
        if CFAbsoluteTimeGetCurrent() - lastMoveSent >= Self.moveInterval {
            flushMoves()
        } else if let flushTimer {
            CFRunLoopTimerSetNextFireDate(flushTimer, lastMoveSent + Self.moveInterval)
        }
    }

    private func flushMoves() {
        guard session != nil else { pendingDX = 0; pendingDY = 0; return }
        let sx = Msg.round(pendingDX), sy = Msg.round(pendingDY)
        guard sx != 0 || sy != 0 else { return }
        send(["t": "ptr_move", "dx": sx, "dy": sy])
        pendingDX -= sx
        pendingDY -= sy
        lastMoveSent = CFAbsoluteTimeGetCurrent()
    }

    private func forwardScroll(_ e: CGEvent) {
        let continuous = e.getIntegerValueField(.scrollWheelEventIsContinuous) != 0
        let a1 = continuous ? e.getDoubleValueField(.scrollWheelEventPointDeltaAxis1)
                            : Double(e.getIntegerValueField(.scrollWheelEventDeltaAxis1))
        let a2 = continuous ? e.getDoubleValueField(.scrollWheelEventPointDeltaAxis2)
                            : Double(e.getIntegerValueField(.scrollWheelEventDeltaAxis2))
        guard a1 != 0 || a2 != 0 else { return }
        let now = CFAbsoluteTimeGetCurrent()
        if now - naturalAt > 5 {
            natural = UserDefaults.standard.object(forKey: "com.apple.swipescrolldirection") as? Bool ?? true
            naturalAt = now
        }
        // CG deltas already include the Mac's natural-scrolling inversion (negative axis1 = content up).
        // The protocol wants the gesture in natural terms (+dy = content up) and lets the target invert.
        let dy = natural ? -a1 : a1, dx = natural ? -a2 : a2
        flushMoves()
        send(["t": "ptr_scroll", "dx": Msg.round(dx), "dy": Msg.round(dy), "px": continuous])
    }

    private func mods() -> Int {
        var m = 0
        if remoteKeys.contains(0x38) || remoteKeys.contains(0x3C) { m |= 1 }
        if remoteKeys.contains(0x3B) || remoteKeys.contains(0x3E) { m |= 2 }
        if remoteKeys.contains(0x3A) || remoteKeys.contains(0x3D) { m |= 4 }
        if remoteKeys.contains(0x37) || remoteKeys.contains(0x36) { m |= 8 }
        if capsOn { m |= 16 }
        return m
    }

    private func sendKey(_ hid: Int, down: Bool, rep: Bool = false, text: String? = nil) {
        flushMoves()
        var msg: [String: Any] = ["t": "key", "hid": hid, "down": down, "mods": mods()]
        if rep { msg["rep"] = true }
        if down, let text { msg["text"] = text }
        send(msg)
    }

    private func forwardKey(_ type: CGEventType, _ e: CGEvent) {
        let kc = UInt16(e.getIntegerValueField(.keyboardEventKeycode))
        switch type {
        case .flagsChanged:
            if kc == 0x39 {
                // macOS reports one flagsChanged per caps-lock toggle; forward it as a full press.
                sendKey(0x39, down: true)
                sendKey(0x39, down: false)
                return
            }
            guard let f = KeyMap.modifierFlags(forMac: kc), let hid = KeyMap.hid(forMac: kc) else { return }
            let down = e.flags.rawValue & f.device != 0
            if down {
                guard remoteKeys.insert(kc).inserted else { return }
            } else {
                guard remoteKeys.remove(kc) != nil else { return }
            }
            sendKey(hid, down: down)
        case .keyDown:
            let rep = e.getIntegerValueField(.keyboardEventAutorepeat) != 0
            if kc == 0x35 && !rep && isRecallChord(e) { recall(); return }
            if let name = KeyMap.action(forMac: kc) {
                if !rep || name.hasPrefix("vol") || name.hasPrefix("brightness") {
                    flushMoves()
                    send(["t": "action", "name": name])
                }
                return
            }
            guard let hid = KeyMap.hid(forMac: kc) else { return }
            remoteKeys.insert(kc)
            sendKey(hid, down: true, rep: rep, text: printableText(e))
        case .keyUp:
            guard remoteKeys.remove(kc) != nil, let hid = KeyMap.hid(forMac: kc) else { return }
            sendKey(hid, down: false)
        default:
            break
        }
    }

    /// Recall hotkey: Ctrl+Option+Cmd+Esc, or Esc pressed three times within 0.8 s.
    private func isRecallChord(_ e: CGEvent) -> Bool {
        let f = e.flags
        if f.contains(.maskControl) && f.contains(.maskAlternate) && f.contains(.maskCommand) { return true }
        let now = CFAbsoluteTimeGetCurrent()
        escDowns = escDowns.filter { now - $0 < 0.8 } + [now]
        return escDowns.count >= 3
    }

    private func printableText(_ e: CGEvent) -> String? {
        guard mods() & (2 | 8) == 0 else { return nil } // Ctrl/Cmd combos are shortcuts, not text
        var len = 0
        var buf = [UniChar](repeating: 0, count: 8)
        e.keyboardGetUnicodeString(maxStringLength: buf.count, actualStringLength: &len, unicodeString: &buf)
        guard len > 0 else { return nil }
        let s = String(utf16CodeUnits: buf, count: len)
        let printable = s.unicodeScalars.allSatisfy { $0.value >= 0x20 && $0.value != 0x7F && !(0xF700...0xF8FF).contains($0.value) }
        return printable ? s : nil
    }

    /// Media/brightness keys (NX_SYSDEFINED subtype 8) → `action`. Returns true if consumed.
    private func forwardSystemDefined(_ e: CGEvent) -> Bool {
        guard let ns = NSEvent(cgEvent: e), ns.subtype.rawValue == 8 else { return false }
        let nxKey = (ns.data1 & 0xFFFF_0000) >> 16
        let down = ((ns.data1 & 0xFF00) >> 8) == 0xA
        if down, let name = KeyMap.action(forNXKeyType: nxKey) {
            flushMoves()
            send(["t": "action", "name": name])
        }
        return true
    }

    private func noteCaps(_ flags: CGEventFlags) {
        let on = flags.contains(.maskAlphaShift)
        guard on != capsOn else { return }
        capsOn = on
        var ids = Set(peers.filter { $0.caps.contains("trackpad") }.map(\.id))
        if let s = session { ids.insert(s.peer) }
        for id in ids { PeerBus.shared.send(to: id, ["t": "key_state", "caps": on]) }
    }
}

/// Hides the cursor while it lives on a remote screen. Main thread only; calls are balanced.
enum CursorHider {
    private static var hidden = false
    private static var backgroundEnabled = false

    static func hide() {
        guard !hidden else { return }
        hidden = true
        enableBackgroundCursorControl()
        CGDisplayHideCursor(CGMainDisplayID())
        NSCursor.hide()
    }

    static func show() {
        guard hidden else { return }
        hidden = false
        CGDisplayShowCursor(CGMainDisplayID())
        NSCursor.unhide()
    }

    /// Agent (menu-bar) apps can only hide the cursor with this connection property set.
    private static func enableBackgroundCursorControl() {
        guard !backgroundEnabled else { return }
        backgroundEnabled = true
        typealias DefaultConn = @convention(c) () -> Int32
        typealias SetProp = @convention(c) (Int32, Int32, CFString, CFTypeRef) -> Int32
        guard let h = dlopen(nil, RTLD_LAZY),
              let c = dlsym(h, "_CGSDefaultConnection"),
              let s = dlsym(h, "CGSSetConnectionProperty") else { return }
        let conn = unsafeBitCast(c, to: DefaultConn.self)()
        _ = unsafeBitCast(s, to: SetProp.self)(conn, conn, "SetsCursorInBackground" as CFString, kCFBooleanTrue)
    }
}
