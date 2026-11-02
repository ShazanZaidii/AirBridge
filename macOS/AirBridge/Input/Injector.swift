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
/// All state lives on `queue`. Every posted event carries `tag` so our own event tap ignores it.
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
    private var entered: (peer: String, edge: ScreenEdge)?
    private var cursor: CGPoint?
    private var lastPost: CFAbsoluteTime = 0
    private var geo = DisplayGeometry.current()
    private var geoAt: CFAbsoluteTime = 0
    private var lastDown: (button: Int, time: CFAbsoluteTime, pos: CGPoint, count: Int64) = (-1, 0, .zero, 0)
    private var scrollRemainder = (x: 0.0, y: 0.0)

    // MARK: entry points (any thread)

    func submit(_ peer: String, _ msg: [String: Any]) {
        queue.async { self.handle(peer, msg) }
    }

    func peersChanged(_ ready: Set<String>) {
        queue.async {
            for peer in self.states.keys where !ready.contains(peer) { self.release(peer) }
            if let e = self.entered, !ready.contains(e.peer) { self.entered = nil }
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
            let edge = (m["edge"] as? String).flatMap(ScreenEdge.init(rawValue:)) ?? .left
            enter(peer, edge, Msg.num(m["rel"]) ?? 0.5)
        case "ctl_leave":
            if entered?.peer == peer { entered = nil }
            release(peer)
        case "ptr_move":
            move(peer, Msg.num(m["dx"]) ?? 0, Msg.num(m["dy"]) ?? 0)
        case "ptr_scroll":
            scroll(peer, Msg.num(m["dx"]) ?? 0, Msg.num(m["dy"]) ?? 0, px: Msg.bool(m["px"]) ?? true)
        case "ptr_btn":
            guard let b = Msg.int(m["b"]), let down = Msg.bool(m["down"]) else { return }
            button(peer, b, down: down)
        case "ptr_click":
            click(peer, Msg.int(m["b"]) ?? 0, count: Msg.int(m["n"]) ?? 1)
        case "key":
            let down = Msg.bool(m["down"]) ?? true
            key(peer, hid: Msg.int(m["hid"]) ?? 0, down: down, mods: Msg.int(m["mods"]),
                rep: Msg.bool(m["rep"]) ?? false, text: m["text"] as? String)
        case "action":
            if let name = m["name"] as? String { action(name) }
        default:
            break
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
        if let old = entered, old.peer != peer {
            // §8.1: a new controller takes over; hand the old one back its cursor.
            let c = currentCursor()
            let g = geometry()
            PeerBus.shared.send(to: old.peer, ["t": "ctl_leave", "edge": old.edge.rawValue,
                                               "rel": Msg.round(g.fraction(old.edge, old.edge.isVertical ? c.y : c.x), 10000)])
            release(old.peer)
        }
        release(peer)
        entered = (peer, edge)
        let g = geometry()
        let along = g.span(edge, max(0, min(1, rel)), 0).0
        let p = g.edgePoint(edge, along: along, inset: 1)
        Self.post(CGEvent(mouseEventSource: source, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: .left))
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
                entered = nil
                release(peer)
                return
            }
            p = c
        }
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
        cursor = p
        lastPost = CFAbsoluteTimeGetCurrent()
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
            e.flags = modifierFlags(states[peer])
            Self.post(e)
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

    private func click(_ peer: String, _ b: Int, count: Int) {
        guard (0...2).contains(b) else { return }
        let p = currentCursor()
        let flags = modifierFlags(states[peer])
        var n = nextClickCount(b, p)
        for i in 0..<max(1, min(count, 3)) {
            if i > 0 { n = nextClickCount(b, p); usleep(10_000) }
            mouseEvent(b, down: true, at: p, clicks: n, flags: flags)
            usleep(8_000)
            mouseEvent(b, down: false, at: p, clicks: n, flags: flags)
        }
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

    private func release(_ peer: String) {
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
        let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching(kIOHIDSystemClass))
        guard service != 0 else { return }
        defer { IOObjectRelease(service) }
        var conn: io_connect_t = 0
        guard IOServiceOpen(service, mach_task_self_, UInt32(kIOHIDParamConnectType), &conn) == KERN_SUCCESS else { return }
        defer { IOServiceClose(conn) }
        var on = false
        IOHIDGetModifierLockState(conn, Int32(kIOHIDCapsLockState), &on)
        IOHIDSetModifierLockState(conn, Int32(kIOHIDCapsLockState), !on)
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

    private func action(_ name: String) {
        if let nx = KeyMap.nxKeyType(forAction: name) { pressMediaKey(nx); return }
        switch name {
        case "mission_control": runMissionControl([], fallback: 0x7E)
        case "app_expose": runMissionControl(["2"], fallback: 0x7D)
        case "desktop_left": chord([0x3B], 0x7B)   // Ctrl+←
        case "desktop_right": chord([0x3B], 0x7C)  // Ctrl+→
        case "spotlight": chord([0x37], 0x31)      // Cmd+Space
        case "copy": chord([0x37], 0x08)           // Cmd+C
        case "paste": chord([0x37], 0x09)          // Cmd+V
        case "lock": lockScreen()
        case "keyboard_toggle": toggleAccessibilityKeyboard()
        default: break // Android-only and gesture actions have no Mac meaning
        }
    }

    /// Mission Control binary: no argument = Mission Control, "2" = App Exposé (current app's windows).
    /// Independent of the user's keyboard-shortcut settings; falls back to Ctrl+arrow.
    private func runMissionControl(_ args: [String], fallback: UInt16) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/System/Applications/Mission Control.app/Contents/MacOS/Mission Control")
        p.arguments = args
        do { try p.run() } catch { chord([0x3B], fallback) }
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
