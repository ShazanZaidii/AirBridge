import AppKit
import AVFoundation

/// The phone picture. Clicks become taps, drags become real-time swipes, two-finger scrolls become scrolls,
/// right-click is Back, keys go to the focused field on the phone. The Mac cursor turns into a soft round
/// pointer over the phone (the phone's own cursor is hidden while mirrored).
final class MirrorScreenView: NSView {
    let videoLayer = AVSampleBufferDisplayLayer()
    var renderer: MirrorRenderer?
    var peer = ""
    weak var model: PhoneMirrorModel?
    var onWindowCommand: ((String) -> Void)?
    /// Pro mirroring takes the input here (same messages, sent as scrcpy control instead of to the AirBridge peer).
    var outlet: (([String: Any]) -> Void)?

    private var buttonDown = false
    private var lastAbsSent = 0.0
    private var pendingAbs: (x: Double, y: Double)?
    private var flushScheduled = false
    private var heldKeys: Set<UInt16> = []
    private var scrollPending = (dx: 0.0, dy: 0.0, px: true)
    private var scrollScheduled = false
    private var lastScrollAt = 0.0
    private var lastTypingHint = 0.0
    private var lastViewOnlyHint = 0.0

    private static let pointer: NSCursor = {
        let d: CGFloat = 26
        let img = NSImage(size: NSSize(width: d, height: d), flipped: false) { r in
            let ring = NSBezierPath(ovalIn: r.insetBy(dx: 4, dy: 4))
            NSColor(white: 0.55, alpha: 0.42).setFill()
            ring.fill()
            NSColor(white: 1, alpha: 0.85).setStroke()
            ring.lineWidth = 1.5
            ring.stroke()
            return true
        }
        return NSCursor(image: img, hotSpot: NSPoint(x: d / 2, y: d / 2))
    }()

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor(white: 0.08, alpha: 1).cgColor
        layer?.masksToBounds = true
        layer?.cornerCurve = .continuous
        layer?.borderWidth = 1
        layer?.borderColor = NSColor.white.withAlphaComponent(0.09).cgColor
        videoLayer.videoGravity = .resizeAspect
        videoLayer.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(videoLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { false } // first click only focuses the window

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.frame = bounds
        CATransaction.commit()
    }

    func setCorner(_ r: CGFloat) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer?.cornerRadius = r
        CATransaction.commit()
    }

    func stateChanged() {
        window?.invalidateCursorRects(for: self)
        if !canControl { releaseAll() }
    }

    override func resetCursorRects() { addCursorRect(bounds, cursor: canControl ? Self.pointer : .arrow) }

    private var canControl: Bool {
        guard let s = model?.state, s.phase == .streaming, s.hasPicture else { return false }
        return s.control
    }

    private var streaming: Bool { model?.state.phase == .streaming && model?.state.hasPicture == true }

    private func viewOnlyHint() {
        guard streaming, model?.state.control == false else { return }
        let now = CACurrentMediaTime()
        guard now - lastViewOnlyHint > 3 else { return }
        lastViewOnlyHint = now
        model?.flash("Turn on AirBridge Universal Control on the phone to control it")
    }

    private func send(_ msg: [String: Any]) {
        if let outlet { outlet(msg) } else { PeerBus.shared.send(to: peer, msg) }
    }

    func sendAction(_ name: String) {
        guard canControl else { viewOnlyHint(); return }
        send(["t": "action", "name": name])
    }

    // MARK: pointer

    private func point(_ e: NSEvent) -> (x: Double, y: Double) {
        MirrorGeometry.normalized(convert(e.locationInWindow, from: nil), in: bounds.size)
    }

    private func sendAbs(_ p: (x: Double, y: Double)) {
        pendingAbs = nil
        lastAbsSent = CACurrentMediaTime()
        send(["t": "ptr_abs", "x": p.x, "y": p.y])
    }

    /// Drag moves at most every 8 ms (≤ 120 messages/s, §8.2); the newest position always goes out.
    private func queueAbs(_ p: (x: Double, y: Double)) {
        let now = CACurrentMediaTime()
        if now - lastAbsSent >= 0.008 { sendAbs(p); return }
        pendingAbs = p
        guard !flushScheduled else { return }
        flushScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.008) { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.flushScheduled = false
                if let p = self.pendingAbs { self.sendAbs(p) }
            }
        }
    }

    override func mouseDown(with e: NSEvent) {
        window?.makeFirstResponder(self)
        guard canControl else { viewOnlyHint(); return }
        let p = point(e)
        sendAbs(p)
        send(["t": "ptr_btn", "b": 0, "down": true])
        buttonDown = true
    }

    override func mouseDragged(with e: NSEvent) {
        guard buttonDown else { return }
        queueAbs(point(e))
    }

    override func mouseUp(with e: NSEvent) {
        guard buttonDown else { return }
        sendAbs(point(e))
        send(["t": "ptr_btn", "b": 0, "down": false])
        buttonDown = false
    }

    override func rightMouseDown(with e: NSEvent) {
        guard canControl else { viewOnlyHint(); return }
        send(["t": "action", "name": "back"])
    }

    override func otherMouseDown(with e: NSEvent) {
        guard canControl, e.buttonNumber == 2 else { return }
        send(["t": "action", "name": "home"])
    }

    override func scrollWheel(with e: NSEvent) {
        guard canControl, !buttonDown else { return }
        let precise = e.hasPreciseScrollingDeltas
        let phoneW = Double(model?.state.displayW ?? 0)
        let d = precise
            ? MirrorGeometry.scroll(dx: e.scrollingDeltaX, dy: e.scrollingDeltaY, viewWidth: Double(bounds.width), phoneWidth: phoneW)
            : (dx: -e.scrollingDeltaX, dy: -e.scrollingDeltaY)
        guard d.dx != 0 || d.dy != 0 else { return }
        // Scroll where the pointer is: move the phone's pointer there first when a new gesture starts.
        let now = CACurrentMediaTime()
        if now - lastScrollAt > 0.3 || e.phase == .began { sendAbs(point(e)) }
        lastScrollAt = now
        if scrollPending.px != precise { flushScroll() }
        scrollPending.dx += d.dx
        scrollPending.dy += d.dy
        scrollPending.px = precise
        guard !scrollScheduled else { return }
        scrollScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.008) { [weak self] in
            MainActor.assumeIsolated { self?.flushScroll() }
        }
    }

    private func flushScroll() {
        scrollScheduled = false
        let s = scrollPending
        scrollPending = (0, 0, s.px)
        guard s.dx != 0 || s.dy != 0 else { return }
        send(["t": "ptr_scroll", "dx": (s.dx * 100).rounded() / 100, "dy": (s.dy * 100).rounded() / 100, "px": s.px])
    }

    // MARK: keyboard

    override func performKeyEquivalent(with e: NSEvent) -> Bool {
        guard e.type == .keyDown, window?.firstResponder === self else { return super.performKeyEquivalent(with: e) }
        let f = e.modifierFlags.intersection(.deviceIndependentFlagsMask)
        guard f.contains(.command) else { return super.performKeyEquivalent(with: e) }
        switch e.charactersIgnoringModifiers?.lowercased() {
        case "w": onWindowCommand?("close"); return true
        case "m": onWindowCommand?("minimize"); return true
        case "1": sendAction("home"); return true
        case "2": sendAction("recents"); return true
        case "=", "+": onWindowCommand?("bigger"); return true
        case "-": onWindowCommand?("smaller"); return true
        case "0": onWindowCommand?("default"); return true
        case "q": return super.performKeyEquivalent(with: e)
        default:
            // Other shortcuts (⌘C, ⌘V, ⌘A, ⌘Z…) go to the phone. macOS sends no key-up for a ⌘ combo, so send both.
            guard canControl, let hid = KeyMap.hid(forMac: e.keyCode) else { return true }
            let mods = MirrorGeometry.mods(f)
            send(["t": "key", "mirror": true, "hid": hid, "down": true, "mods": mods])
            send(["t": "key", "mirror": true, "hid": hid, "down": false, "mods": mods])
            return true
        }
    }

    override func keyDown(with e: NSEvent) {
        guard canControl else { viewOnlyHint(); return }
        if let name = KeyMap.action(forMac: e.keyCode) {
            if !e.isARepeat { send(["t": "action", "name": name]) }
            return
        }
        guard let hid = KeyMap.hid(forMac: e.keyCode) else { return }
        let mods = MirrorGeometry.mods(e.modifierFlags)
        var msg: [String: Any] = ["t": "key", "mirror": true, "hid": hid, "down": true, "mods": mods]
        if e.isARepeat { msg["rep"] = true }
        if let t = MirrorGeometry.text(e.characters, mods: mods) {
            msg["text"] = t
            if model?.state.typing == false {
                let now = CACurrentMediaTime()
                if now - lastTypingHint > 10 {
                    lastTypingHint = now
                    model?.flash("Turn on AirBridge Typing & Clipboard on the phone to type here")
                }
            }
        }
        heldKeys.insert(e.keyCode)
        send(msg)
    }

    override func keyUp(with e: NSEvent) {
        guard heldKeys.remove(e.keyCode) != nil, let hid = KeyMap.hid(forMac: e.keyCode) else { return }
        send(["t": "key", "mirror": true, "hid": hid, "down": false, "mods": MirrorGeometry.mods(e.modifierFlags)])
    }

    override func flagsChanged(with e: NSEvent) {
        guard canControl else { return }
        let kc = e.keyCode
        if kc == 0x39 { // Caps Lock: one event per toggle, sent as a full press
            send(["t": "key", "mirror": true, "hid": 0x39, "down": true, "mods": MirrorGeometry.mods(e.modifierFlags)])
            send(["t": "key", "mirror": true, "hid": 0x39, "down": false, "mods": MirrorGeometry.mods(e.modifierFlags)])
            return
        }
        guard let f = KeyMap.modifierFlags(forMac: kc), let hid = KeyMap.hid(forMac: kc) else { return }
        let down = UInt64(e.modifierFlags.rawValue) & f.device != 0
        if down { guard heldKeys.insert(kc).inserted else { return } }
        else { guard heldKeys.remove(kc) != nil else { return } }
        send(["t": "key", "mirror": true, "hid": hid, "down": down, "mods": MirrorGeometry.mods(e.modifierFlags)])
    }

    /// Focus lost, window closing, control gone: every key and the button come back up on the phone.
    func releaseAll() {
        if buttonDown {
            buttonDown = false
            if let p = pendingAbs { sendAbs(p) }
            send(["t": "ptr_btn", "b": 0, "down": false])
        }
        for kc in heldKeys {
            if let hid = KeyMap.hid(forMac: kc) { send(["t": "key", "mirror": true, "hid": hid, "down": false, "mods": 0]) }
        }
        heldKeys.removeAll()
    }
}
