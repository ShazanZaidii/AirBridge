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
        let startedAt: CFAbsoluteTime
        var acked = false                // target answered ctl_ack (§8.1); no ack within ackTimeout aborts the capture
        var depth = 0.0                  // how far into the target the cursor is, in Mac points (our own estimate)
        var lateral = 0.0                // sideways travel since entry, in Mac points
        let maxDepth: Double             // target extent along the travel axis, in Mac points
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
    private var capsOn = false
    private var peers: [PeerInfo] = []
    private var geo = DisplayGeometry.current()
    private var geoAt: CFAbsoluteTime = 0

    // Capture intent (§8.2): outward push while pinned at an edge, summed over a sliding window.
    private struct Charge {
        let peer: String
        let edge: ScreenEdge
        let span: (CGFloat, CGFloat)
        let bar: CGRect                   // indicator rect, global CG coordinates
        var samples: [(t: CFAbsoluteTime, v: Double)] = []
    }
    private struct Candidate {
        let peer: String
        let edge: ScreenEdge
        let span: (CGFloat, CGFloat)
        let rel: Double
        let bar: CGRect
    }
    private var charge: Charge?
    private var lastLocal: CGPoint?
    private var cooldownUntil: [ScreenEdge: CFAbsoluteTime] = [:]
    private var dragging = false
    private var lastDragEnd: CFAbsoluteTime = 0
    private var settings = CaptureSettings.load()
    private var settingsAt: CFAbsoluteTime = 0
    private var shownLevel: Double = -1
    private var lastEnded: (peer: String, at: CFAbsoluteTime)?
    private var refusedUntil: [String: CFAbsoluteTime] = [:]   // peers that did not answer/refused a capture

    // Carrying a file drag onto a target (§8.8).
    private var carry = DragCarryController(firstId: Int.random(in: 1..<0x1000_0000))
    private var carryURLs: [URL] = []
    private var dragPbAtDown = -1                 // drag pasteboard changeCount when the left button went down
    private var dragFiles: [URL]?                 // files of the current local drag, once known
    private var dragFilesCheckedAt: CFAbsoluteTime = 0
    private var catcherShown = false
    private var buttonUpSince: CFAbsoluteTime?    // carrying, but the left button is physically up

    private static let moveInterval = 1.0 / 120.0
    /// §8.1: the target must answer a ctl_enter with ctl_ack within this long, or we give the cursor back.
    private static let ackTimeout = 0.6
    /// Moving back this far (Mac points) through the entry edge returns control even if the target is silent.
    private static let returnSlack = 40.0

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
            // Lets a charging edge indicator decay when the user stops pushing (no events arrive then).
            let decay = CFRunLoopTimerCreateWithHandler(nil, CFAbsoluteTimeGetCurrent(), 0.05, 0, 0) { [weak self] _ in
                self?.decayCharge()
                self?.watchSession()
            }
            CFRunLoopAddTimer(rl, decay, .commonModes)
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
            end(resumeAt: returnPoint(s), sendLeave: false)
        }
        if let c = charge, !list.contains(where: { $0.id == c.peer }) { resetCharge() }
    }

    /// `ctl_busy` from a peer (§8.6): it is held by someone else. Stop forwarding, give the cursor back.
    func busy(peer: String, holder: String) {
        let name = peerName(peer)
        let who = holder.isEmpty ? "another device" : holder
        if let s = session, s.peer == peer {
            end(resumeAt: returnPoint(s), sendLeave: true)
            ControlStatus.shared.showNotice("\(name) is being used by \(who)")
        } else if let l = lastEnded, l.peer == peer, CFAbsoluteTimeGetCurrent() - l.at < 2 {
            ControlStatus.shared.showNotice("\(name) is being used by \(who)")
        }
    }

    private func peerName(_ id: String) -> String {
        peers.first { $0.id == id }?.name ?? Layout.shared.placement(for: id)?.name ?? "The device"
    }

    /// `ctl_leave` from the peer we control: resume at the matching point of our edge.
    func remoteLeave(peer: String, rel: Double?, reason: String? = nil) {
        guard let s = session, s.peer == peer else { return }
        var p = returnPoint(s)
        if reason == "unavailable" {
            refusedUntil[peer] = CFAbsoluteTimeGetCurrent() + 5
            ControlStatus.shared.showNotice("\(peerName(peer)) can't be controlled right now. Turn on the AirBridge accessibility service on it.", seconds: 8)
        }
        if let rel {
            let along = s.span.0 + (s.span.1 - s.span.0) * CGFloat(max(0, min(1, rel)))
            p = geometry().edgePoint(s.edge, along: along, inset: 3)
        }
        end(resumeAt: p, sendLeave: false)
    }

    /// The peer we control started controlling us (`ctl_enter`): stop without echoing a leave.
    func peerTookOver(_ peer: String) {
        if let s = session, s.peer == peer { end(resumeAt: returnPoint(s), sendLeave: false) }
    }

    /// `ctl_ack` from the target: it showed its cursor and can inject input.
    func remoteAck(peer: String) {
        guard let s = session, s.peer == peer, !s.acked else { return }
        session!.acked = true
        let name = peerName(peer), anchor = s.anchor
        // Hand-off: the target's cursor is up, now the Mac's disappears.
        DispatchQueue.main.async { CursorHider.hide(); CaptureHUD.shared.show(deviceName: name, anchor: anchor) }
    }

    /// No ack in time: the target can't or won't take control. Never leave the user's cursor hostage.
    private func watchSession() {
        watchCarriedButton()
        guard let s = session, !s.acked else { return }
        let now = CFAbsoluteTimeGetCurrent()
        guard now - s.startedAt > Self.ackTimeout else { return }
        refusedUntil[s.peer] = now + 5
        let name = peerName(s.peer)
        end(resumeAt: returnPoint(s), sendLeave: true)
        ControlStatus.shared.showNotice("\(name) isn't ready to be controlled. Turn on the AirBridge accessibility service on it.", seconds: 8)
    }

    /// §8.8 safety net: carrying, but the left button is physically up and we never saw the release (the tap was
    /// disabled for a moment, secure input...). Cancel the carry; a release we missed went to the catcher anyway.
    private func watchCarriedButton() {
        guard carry.isCarrying, session != nil else { buttonUpSince = nil; return }
        let now = CFAbsoluteTimeGetCurrent()
        if CGEventSource.buttonState(.hidSystemState, button: .left) { buttonUpSince = nil; return }
        guard let since = buttonUpSince else { buttonUpSince = now; return }
        guard now - since > 0.3, case let .cancel(peer, id) = carry.end() else { return }
        PeerBus.shared.send(to: peer, ["t": "drag_cancel", "id": id])
        InputLog.shared.log("drag: button found up without a release; carry cancelled")
        carryURLs = []
        buttonUpSince = nil
        localButtons.remove(0)
        hideCatcher(after: 1.0)
    }

    /// Where the cursor reappears: on the entry edge, at the sideways position the user had reached.
    private func returnPoint(_ s: Session) -> CGPoint {
        let anchorAlong = s.edge.isVertical ? s.anchor.y : s.anchor.x
        let along = max(s.span.0, min(s.span.1, anchorAlong + CGFloat(s.lateral)))
        return geometry().edgePoint(s.edge, along: along, inset: 8)
    }

    /// Local recall (hotkey / legacy call): send `ctl_leave` and return to where we left.
    func recall() {
        if let s = session { end(resumeAt: returnPoint(s), sendLeave: true) }
    }

    // MARK: tap callback

    private func handle(_ type: CGEventType, _ event: CGEvent) -> Unmanaged<CGEvent>? {
        let pass = Unmanaged.passUnretained(event)
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
            // We missed events while disabled; don't trust button/drag tracking.
            localButtons.removeAll()
            dragging = false
            dragPbAtDown = -1
            dragFiles = nil
            resetCharge()
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
            if b == 0 {
                // §8.8: a file drag writes the drag pasteboard after this point; remember where it stood.
                dragPbAtDown = NSPasteboard(name: .drag).changeCount
                dragFiles = nil
                dragFilesCheckedAt = 0
            }
            resetCharge()
        case .leftMouseUp, .rightMouseUp, .otherMouseUp:
            let b = Self.buttonNumber(type, e)
            localButtons.remove(b)
            if dragging && localButtons.isEmpty {
                dragging = false
                lastDragEnd = CFAbsoluteTimeGetCurrent()
            }
            lastLocal = e.location
            if swallowButtonUps.remove(b) != nil { return false }
        case .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            dragging = true
            // Drags never cross (§8.2: a held button resets the accumulator), except a file drag pushed into a
            // peer that can take it (§8.8).
            if type == .leftMouseDragged, localButtons == [0] {
                if let hit = pushThroughDrag(e) {
                    begin(hit.peer, hit.edge, hit.span, hit.rel, e, carrying: dragFiles ?? [])
                    return false
                }
                return true
            }
            lastLocal = e.location
            resetCharge()
        case .mouseMoved:
            // A plain move means no button is down.
            localButtons.removeAll()
            if dragging { dragging = false; lastDragEnd = CFAbsoluteTimeGetCurrent() }
            if let hit = pushThrough(e) {
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

    private func currentSettings() -> CaptureSettings {
        let now = CFAbsoluteTimeGetCurrent()
        if now - settingsAt > 1 { settings = CaptureSettings.load(); settingsAt = now }
        return settings
    }

    /// Outward component of a delta for `edge` (positive = pushing out of the screen).
    private static func outward(_ edge: ScreenEdge, _ dx: Double, _ dy: Double) -> Double {
        switch edge {
        case .left: return -dx
        case .right: return dx
        case .top: return -dy
        case .bottom: return dy
        }
    }

    /// The edge the cursor is pinned against that leads to an online, assigned, enabled peer.
    /// Only the peer assigned to that edge is ever considered (§8.6); offline peers leave the edge inert.
    private func candidate(at p: CGPoint, dx: Double, dy: Double, _ cfg: CaptureSettings, drag: Bool = false) -> Candidate? {
        let g = geometry()
        guard let r = g.display(at: p) else { return nil }
        var best: (Candidate, Double)?
        for edge in ScreenEdge.allCases where cfg.enabled(edge) && g.isOuter(p, of: r, edge) {
            guard let (id, pl) = Layout.shared.assignment(for: edge),
                  peers.contains(where: { $0.id == id && $0.caps.contains("ctl_target")
                                          && (!drag || DragCarryController.eligible($0.caps)) }) else { continue }
            if let until = refusedUntil[id], CFAbsoluteTimeGetCurrent() < until { continue }
            let span = g.span(edge, pl.start, pl.end)
            let along = edge.isVertical ? p.y : p.x
            guard along >= span.0 - 0.5, along <= span.1 + 0.5 else { continue }
            let rel = Double(max(0, min(1, (along - span.0) / max(1, span.1 - span.0))))
            // Indicator: the part of this display's edge that belongs to the peer's span.
            let lo = max(span.0, edge.isVertical ? r.minY : r.minX)
            let hi = min(span.1, edge.isVertical ? r.maxY : r.maxX)
            let t = EdgeIndicator.thickness
            let bar: CGRect
            switch edge {
            case .left: bar = CGRect(x: r.minX, y: lo, width: t, height: max(t, hi - lo))
            case .right: bar = CGRect(x: r.maxX - t, y: lo, width: t, height: max(t, hi - lo))
            case .top: bar = CGRect(x: lo, y: r.minY, width: max(t, hi - lo), height: t)
            case .bottom: bar = CGRect(x: lo, y: r.maxY - t, width: max(t, hi - lo), height: t)
            }
            let push = Self.outward(edge, dx, dy)
            if best == nil || push > best!.1 { best = (Candidate(peer: id, edge: edge, span: span, rel: rel, bar: bar), push) }
        }
        return best?.0
    }

    /// §8.2 Capture intent: returns the portal to enter once the user has deliberately pushed through.
    private func pushThrough(_ e: CGEvent) -> Candidate? {
        let now = CFAbsoluteTimeGetCurrent()
        let p = e.location
        let prev = lastLocal
        lastLocal = p
        let dx = e.getDoubleValueField(.mouseEventDeltaX), dy = e.getDoubleValueField(.mouseEventDeltaY)
        let cfg = currentSettings()
        guard !dragging, localButtons.isEmpty, now - lastDragEnd >= CaptureSettings.dragCooldown,
              ControlGate.shared.controlledBy == nil,          // no chaining while this Mac is itself controlled (§8.6)
              !peers.isEmpty, let c = candidate(at: p, dx: dx, dy: dy, cfg) else {
            resetCharge()
            return nil
        }
        return accumulate(c, e, prev: prev, now: now, cfg)
    }

    /// §8.8: the same push-through, during a left-button drag that carries files, into a `drag_files` peer.
    /// While it charges, the drop catcher sits under the cursor so the release can never land on the Mac.
    private func pushThroughDrag(_ e: CGEvent) -> Candidate? {
        let now = CFAbsoluteTimeGetCurrent()
        let p = e.location
        let prev = lastLocal
        lastLocal = p
        let dx = e.getDoubleValueField(.mouseEventDeltaX), dy = e.getDoubleValueField(.mouseEventDeltaY)
        let cfg = currentSettings()
        guard ControlGate.shared.controlledBy == nil, !peers.isEmpty,
              let c = candidate(at: p, dx: dx, dy: dy, cfg, drag: true),
              currentDragFiles(now)?.isEmpty == false else {
            resetCharge()
            return nil
        }
        if !catcherShown {
            catcherShown = true
            DropCatcher.carrying = false
            let r = Self.catcherRect(c.bar, c.edge)
            DispatchQueue.main.async { DropCatcher.shared.show(rect: r) }
        }
        return accumulate(c, e, prev: prev, now: now, cfg)
    }

    /// The files in the drag going on now, read from the drag pasteboard (only if it changed since the button
    /// went down). A miss is re-checked at most every 250 ms: the pasteboard is written a moment after the drag
    /// starts.
    private func currentDragFiles(_ now: CFAbsoluteTime) -> [URL]? {
        if let dragFiles { return dragFiles }
        guard dragPbAtDown >= 0, now - dragFilesCheckedAt >= 0.25 else { return nil }
        dragFilesCheckedAt = now
        let pb = NSPasteboard(name: .drag)
        let changed = pb.changeCount
        guard changed != dragPbAtDown else { return nil }
        let urls = (pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        guard DragCarryController.dragHasFiles(changeCountAtDown: dragPbAtDown, changeCountNow: changed,
                                               fileCount: urls.count) else { return nil }
        dragFiles = urls
        return urls
    }

    /// The strip under a cursor pinned at `edge`: the indicator bar, a little thicker, flush with the outer edge.
    private static func catcherRect(_ bar: CGRect, _ edge: ScreenEdge) -> CGRect {
        let t = DropCatcher.thickness
        switch edge {
        case .left: return CGRect(x: bar.minX, y: bar.minY, width: t, height: bar.height)
        case .right: return CGRect(x: bar.maxX - t, y: bar.minY, width: t, height: bar.height)
        case .top: return CGRect(x: bar.minX, y: bar.minY, width: bar.width, height: t)
        case .bottom: return CGRect(x: bar.minX, y: bar.maxY - t, width: bar.width, height: t)
        }
    }

    /// Shared accumulator of both push-through paths.
    private func accumulate(_ c: Candidate, _ e: CGEvent, prev: CGPoint?, now: CFAbsoluteTime, _ cfg: CaptureSettings) -> Candidate? {
        let p = e.location
        let dx = e.getDoubleValueField(.mouseEventDeltaX), dy = e.getDoubleValueField(.mouseEventDeltaY)
        if let until = cooldownUntil[c.edge], now < until { resetCharge(); return nil }
        if ControlGate.shared.mirrorFocus == c.peer { resetCharge(); return nil } // its mirror window drives it
        if cfg.requireOption && !e.flags.contains(.maskAlternate) { resetCharge(); return nil }

        var push = Self.outward(c.edge, dx, dy)
        if push < 0 { resetCharge(); return nil } // any inward motion
        // Only motion while pinned counts: subtract the distance travelled to reach the edge.
        if let prev {
            let travelled = c.edge.isVertical ? abs(p.x - prev.x) : abs(p.y - prev.y)
            push = max(0, push - Double(travelled))
        }
        if charge == nil || charge!.peer != c.peer || charge!.edge != c.edge {
            charge = Charge(peer: c.peer, edge: c.edge, span: c.span, bar: c.bar)
        }
        charge!.samples.removeAll { now - $0.t > CaptureSettings.window }
        if push > 0 { charge!.samples.append((now, push)) }
        let sum = charge!.samples.reduce(0) { $0 + $1.v }
        if sum >= cfg.threshold {
            let bar = c.bar
            charge = nil
            shownLevel = -1
            DispatchQueue.main.async { EdgeIndicator.shared.flash(rect: bar) }
            return c
        }
        showLevel(sum / cfg.threshold, c.bar)
        return nil
    }

    private func showLevel(_ level: Double, _ bar: CGRect) {
        if level <= 0.02 {
            if shownLevel > 0 { DispatchQueue.main.async { EdgeIndicator.shared.hide() } }
            shownLevel = 0
            return
        }
        guard abs(level - shownLevel) >= 0.03 else { return }
        shownLevel = level
        DispatchQueue.main.async { EdgeIndicator.shared.update(rect: bar, level: level) }
    }

    private func resetCharge() {
        charge = nil
        if shownLevel > 0 { DispatchQueue.main.async { EdgeIndicator.shared.hide() } }
        shownLevel = -1
        if catcherShown && !carry.isCarrying { hideCatcher() }
    }

    private func hideCatcher(after delay: Double = 0) {
        catcherShown = false
        DispatchQueue.main.async { DropCatcher.shared.hide(after: delay) }
    }

    /// Timer: samples age out of the window even when no events arrive (pause > 500 ms empties it).
    private func decayCharge() {
        guard var c = charge else { return }
        let now = CFAbsoluteTimeGetCurrent()
        c.samples.removeAll { now - $0.t > CaptureSettings.window }
        charge = c
        let sum = c.samples.reduce(0) { $0 + $1.v }
        if sum <= 0 { resetCharge(); return }
        showLevel(sum / currentSettings().threshold, c.bar)
    }

    private func begin(_ peer: String, _ edge: ScreenEdge, _ span: (CGFloat, CGFloat), _ rel: Double, _ e: CGEvent,
                       carrying files: [URL] = []) {
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
        if !files.isEmpty { _ = carry.begin(peer: peer) } // before resetCharge so the drop catcher stays up
        resetCharge()
        let info = peers.first { $0.id == peer }
        let gain = info?.platform == "android" ? 1.6 : 1.0       // target pixels per Mac point (see Android TargetController)
        let extentPx = Double(edge.isVertical ? (info?.screenW ?? 0) : (info?.screenH ?? 0))
        let maxDepth = extentPx > 0 ? extentPx / gain : 1200
        session = Session(peer: peer, edge: edge, span: span, anchor: anchor,
                          startedAt: CFAbsoluteTimeGetCurrent(), maxDepth: max(200, maxDepth))
        remoteKeys.removeAll()
        remoteButtons.removeAll()
        escDowns.removeAll()
        pendingDX = 0; pendingDY = 0
        CGAssociateMouseAndMouseCursorPosition(0)
        // A carried file drag is still held on the Mac: park the frozen cursor a little inside the screen, not on the
        // very edge, or macOS keeps switching Spaces (see CarryPark).
        var park: CarryPark?
        if !files.isEmpty, let r = geometry().display(at: anchor) {
            park = CarryPark.make(display: r, vertical: edge.isVertical, atMin: edge == .left || edge == .top,
                                  along: edge.isVertical ? anchor.y : anchor.x, span: span)
            CGWarpMouseCursorPosition(park!.point)
        }
        ControlGate.shared.controlling = peer
        // The cursor stays visible (frozen at the edge) until the target confirms its own cursor is up, so there is
        // never a moment with no cursor anywhere. It is hidden in remoteAck.
        PeerBus.shared.send(to: peer, ["t": "ctl_enter", "edge": edge.opposite.rawValue, "rel": Msg.round(rel, 10000)])
        PeerBus.shared.send(to: peer, ["t": "key_state", "caps": capsOn])
        if case let .carrying(id, _) = carry.phase {
            // §8.8: the held button stays local (never forwarded); the target shows a ghost instead.
            carryURLs = files
            buttonUpSince = nil
            DropCatcher.carrying = true
            PeerBus.shared.send(to: peer, DragPayload.describe(files).message(id: id))
            // Let the drag session see the cursor resting on the catcher (it may have arrived there in one jump).
            // Queued on main behind the catcher's show, so the panel is up when the event arrives.
            let rest = park?.point ?? anchor
            let wide = park?.rect
            DispatchQueue.main.async {
                if let wide { DropCatcher.shared.show(rect: wide) }
                Injector.post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged, mouseCursorPosition: rest, mouseButton: .left))
            }
            InputLog.shared.log("drag: carrying \(files.count) item(s) to \(peerName(peer))")
        }
        ControlStatus.shared.setControlling(peerName(peer))
    }

    private func end(resumeAt p: CGPoint, sendLeave: Bool) {
        guard let s = session else { return }
        flushMoves()
        let gone = !peers.contains { $0.id == s.peer }
        if case let .cancel(peer, id) = carry.end(peerGone: gone) {
            // §8.8: the button is still held here; the local drag simply continues on the Mac.
            PeerBus.shared.send(to: peer, ["t": "drag_cancel", "id": id])
            InputLog.shared.log("drag: carry cancelled")
        }
        if catcherShown { DropCatcher.carrying = false; hideCatcher() }
        carryURLs = []
        buttonUpSince = nil
        if sendLeave { PeerBus.shared.send(to: s.peer, ["t": "ctl_leave"]) }
        swallowKeyUps.formUnion(remoteKeys.filter { KeyMap.modifierFlags(forMac: $0) == nil })
        swallowButtonUps.formUnion(remoteButtons)
        remoteKeys.removeAll()
        remoteButtons.removeAll()
        escDowns.removeAll()
        session = nil
        ControlGate.shared.controlling = nil
        let now = CFAbsoluteTimeGetCurrent()
        cooldownUntil[s.edge] = now + CaptureSettings.returnCooldown // no bounce back out
        lastEnded = (s.peer, now)
        lastLocal = p
        ControlStatus.shared.setControlling(nil)
        CGWarpMouseCursorPosition(p)
        CGAssociateMouseAndMouseCursorPosition(1)
        CGWarpMouseCursorPosition(p)   // some macOS versions re-place the cursor when association comes back
        DispatchQueue.main.async { CursorHider.show(); CaptureHUD.shared.hide() }
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
            let dx = e.getDoubleValueField(.mouseEventDeltaX), dy = e.getDoubleValueField(.mouseEventDeltaY)
            queueMove(dx, dy)
            // Our own estimate of where the target's cursor is, so the way back never depends on the target.
            let deeper = Self.outward(s.edge, dx, dy)
            let side = s.edge.isVertical ? dx : dy
            session!.depth = min(s.maxDepth, s.depth + deeper)
            session!.lateral += side
            if session!.depth <= -Self.returnSlack, remoteButtons.isEmpty {
                end(resumeAt: returnPoint(session!), sendLeave: true)
            }
        case .leftMouseDown, .rightMouseDown, .otherMouseDown:
            let b = Self.buttonNumber(type, e)
            guard b <= 2, remoteButtons.insert(b).inserted else { return false }
            flushMoves()
            send(["t": "ptr_btn", "b": b, "down": true])
        case .leftMouseUp, .rightMouseUp, .otherMouseUp:
            let b = Self.buttonNumber(type, e)
            if b == 0, carry.isCarrying, !remoteButtons.contains(0) { return dropCarried(e, s) }
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

    /// §8.8: the carried drag was let go over the target. Tell it, send the files, and end the Mac's own drag
    /// session on the drop catcher under the frozen cursor (the release is let through there). Returns true = pass.
    private func dropCarried(_ e: CGEvent, _ s: Session) -> Bool {
        flushMoves()
        guard case let .drop(peer, id) = carry.release() else { return false }
        PeerBus.shared.send(to: peer, ["t": "drag_drop", "id": id])
        DragSend.send(carryURLs, to: peer)
        InputLog.shared.log("drag: dropped \(carryURLs.count) item(s) on \(peerName(peer))")
        carryURLs = []
        buttonUpSince = nil
        localButtons.remove(0)
        dragging = false
        dragFiles = nil
        dragPbAtDown = -1
        e.location = s.anchor
        if !DropCatcher.hasDrag {
            // The catcher never became the destination (should not happen): cancel the drag with Esc instead, so
            // the release can't drop the files on whatever is under the edge.
            InputLog.shared.log("drag: drop catcher not engaged; cancelling the local drag with Esc")
            for down in [true, false] {
                Injector.post(CGEvent(keyboardEventSource: nil, virtualKey: 0x35, keyDown: down))
            }
        }
        hideCatcher(after: 1.5) // keeps `carrying` until the drop has been performed on it
        return true
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
        // CG deltas already include the Mac's natural-scrolling setting (negative axis1 = content up).
        // Wire values describe content motion (+dy = content up, +dx = content left); targets never invert.
        let dy = -a1, dx = -a2
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

    /// The injector flipped Caps Lock (no tap event): keep our tracker in step so physical toggles are still seen.
    func syncCaps(_ on: Bool) { capsOn = on }

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
