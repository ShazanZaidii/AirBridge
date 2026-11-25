import AppKit
import Combine
import SwiftUI

/// The calls shown on this Mac, for the menu bar ("return to the call").
@MainActor final class CallBoardUI: ObservableObject {
    @Published fileprivate(set) var calls: [CallUI] = []
}

/// Phone calls on this Mac (PROTOCOL §13.3–13.5): the incoming banner, the in-call window and its pill, the
/// ringtone, the decision where the call rings, and the phone's audio routes for the Audio menu (`CallAudio`).
/// `PhoneNotifCenter` feeds it `call_*` messages through `show` / `end` / `flash`.
///
/// Like a call on a Mac, it never just disappears: the banner stays until the call is answered or over, and an
/// active call is always on screen — the call window, or the pill it collapses into.
@MainActor final class CallPanels {
    static let shared = CallPanels()
    let board = CallBoardUI()

    private final class Live {
        let ui: CallUI
        var banner: CallBannerPanel? { didSet { if banner !== oldValue { bannerSwipe?.stop(); bannerSwipe = nil } } }
        var window: InCallWindow?
        var pill: CallPillPanel? { didSet { if pill !== oldValue { pillSwipe?.stop(); pillSwipe = nil } } }
        /// Two-finger swipe and the options menu on the banner / pill.
        var bannerSwipe: PanelSwipe?
        var pillSwipe: PanelSwipe?
        var ring: RingSession?
        var silenceSent = false
        /// The banner was on screen when the call was answered (here or on the phone).
        var sawRinging = false
        var answeredHere = false
        var flashWork: DispatchWorkItem?
        var closeWork: DispatchWorkItem?
        init(ui: CallUI) { self.ui = ui }
    }

    private var live: [String: Live] = [:]
    private var photos: [String: (id: String, image: NSImage)] = [:]
    private var presence: [String: PhonePresenceReport] = [:]
    private let ringtone = Ringtone()
    /// A volume key press while ringing silences the Mac for that call (it still shows; answer or decline as usual).
    private let volumeKeys = VolumeKeys()
    private var silenced: Set<String> = []
    private var ticker: Timer?
    private var cancellables: Set<AnyCancellable> = []

    private init() {
        CallAudio.shared.$route
            .sink { [weak self] r in self?.live.values.forEach { $0.ui.route = r } }
            .store(in: &cancellables)
        CallAudio.shared.$macMicMuted
            .sink { [weak self] m in self?.live.values.forEach { $0.ui.macMicMuted = m } }
            .store(in: &cancellables)
        CallAudio.shared.$switching
            .sink { [weak self] v in self?.live.values.forEach { $0.ui.switching = v } }
            .store(in: &cancellables)
        CallAudio.shared.$selectedMacOutput
            .sink { [weak self] id in
                let sym = CallAudio.shared.macOutputs.first { $0.id == id }?.symbol
                self?.live.values.forEach { $0.ui.macOutputSymbol = sym }
            }
            .store(in: &cancellables)
        // §13.5: the Audio menu's phone rows ask the phone to move the call there.
        CallAudio.shared.onSelectPhoneRoute = { [weak self] dev in self?.selectPhoneRoute(dev) }
        NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { _ in BeaconScanner.shared.update() }
            .store(in: &cancellables)
    }

    // MARK: From PhoneNotifCenter

    func show(_ c: PhoneCall) {
        let name = PhoneNotifCenter.shared.phoneName(c.phoneId)
        let l: Live
        if let cur = live[c.phoneId], cur.ui.call.id == c.id {
            l = cur
            l.closeWork?.cancel(); l.closeWork = nil
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                l.ui.call = c
                l.ui.phoneName = name
                l.ui.ended = false
            }
        } else {
            if let old = live[c.phoneId] { tearDown(old, phone: c.phoneId) }
            let ui = CallUI(call: c, phoneName: name)
            ui.route = CallAudio.shared.route
            ui.macMicMuted = CallAudio.shared.macMicMuted
            ui.switching = CallAudio.shared.switching
            ui.macOutputSymbol = CallAudio.shared.macOutputs.first { $0.id == CallAudio.shared.selectedMacOutput }?.symbol
            if let p = photos[c.phoneId], p.id == c.id { ui.photo = p.image }
            let phone = c.phoneId
            ui.perform = { [weak self] a in self?.perform(a, phone: phone) }
            l = Live(ui: ui)
            live[c.phoneId] = l
        }

        switch c.state {
        case .ringing:
            if l.banner == nil && !l.ui.inBackground { showBanner(l) }
            if l.ring == nil { l.ring = RingSession(inputs(c.phoneId)); applyRing(l, phone: c.phoneId) }
        case .active, .held:
            l.ring = nil
            l.ui.ringingHere = false
            if let b = l.banner {
                l.sawRinging = true
                l.banner = nil
                b.dismiss(animated: true)
            }
            // On screen while it lasts (the window when the call was seen or answered here, else the pill), unless the
            // user sent it to the background.
            if l.window == nil && l.pill == nil && !l.ui.inBackground {
                if l.answeredHere || l.sawRinging { openWindow(l, focus: false) } else { showPill(l) }
            }
            reportRoutes(c)
        case .ended:
            break
        }
        updateRinger()
        publishBoard()
    }

    func end(_ c: PhoneCall) {
        guard let l = live[c.phoneId], l.ui.call.id == c.id else { return }
        CallAudio.shared.report(phoneRoutes: [], selected: nil)
        if !live.contains(where: { $0.key != c.phoneId && !$0.value.ui.ended }) { CallAudio.shared.callEnded() }
        if let p = l.pill { l.pill = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { p.dismiss() } }
        l.ring = nil
        l.ui.ringingHere = false
        updateRinger()
        withAnimation(.easeOut(duration: 0.2)) { l.ui.ended = true }
        if let b = l.banner {
            l.banner = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { b.dismiss(animated: true) }
        }
        let work = DispatchWorkItem { [weak self] in
            guard let self, let cur = self.live[c.phoneId], cur === l else { return }
            self.tearDown(l, phone: c.phoneId)
            self.live[c.phoneId] = nil
            self.publishBoard()
        }
        l.closeWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + (l.window?.isVisible == true ? 1.6 : 1.0), execute: work)
        publishBoard()
    }

    /// A problem worth telling the user while the call UI is up ("couldn't do that").
    func flash(phone: String, _ text: String) {
        guard let l = live[phone] else { return }
        l.flashWork?.cancel()
        withAnimation { l.ui.flash = text }
        resizeWindow(l)
        let work = DispatchWorkItem { [weak self] in
            withAnimation { l.ui.flash = nil }
            self?.resizeWindow(l)
        }
        l.flashWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
    }

    /// `call_photo`: may arrive just before the call it belongs to.
    func setPhoto(phone: String, id: String, jpg: Data) {
        guard let img = NSImage(data: jpg) else { return }
        photos[phone] = (id, img)
        if let l = live[phone], l.ui.call.id == id { withAnimation(.easeOut(duration: 0.2)) { l.ui.photo = img } }
    }

    /// `call_presence`: what the phone says about its user. A ringing call is re-checked at once.
    func setPresence(phone: String, _ r: PhonePresenceReport) {
        presence[phone] = r
        if let l = live[phone], l.ring != nil { reevaluate(l, phone: phone) }
    }

    func phoneGone(_ phone: String) {
        presence[phone] = nil
        photos[phone] = nil
        BeaconScanner.shared.phoneGone(phone)
    }

    /// From the menu bar or the Notifications window: bring the call back (the window, or the banner while it rings).
    func showCall(phone: String) {
        guard let l = live[phone] else { return }
        l.ui.inBackground = false
        publishBoard()
        if l.ui.call.state == .ringing {
            if l.banner == nil { showBanner(l) } else { l.banner?.orderFrontRegardless() }
        } else {
            openWindow(l, focus: true)
        }
    }

    // MARK: Buttons

    private func perform(_ a: CallUI.Action, phone: String) {
        guard let l = live[phone] else { return }
        let c = l.ui.call
        switch a {
        case .answer:
            l.answeredHere = true
            l.ring = nil
            l.ui.ringingHere = false
            updateRinger()
            send(c, "answer")
        case .answerOnPhone:
            l.answeredHere = true
            l.ring = nil
            l.ui.ringingHere = false
            updateRinger()
            send(c, "answer")
        case .decline:
            l.ring = nil
            l.ui.ringingHere = false
            updateRinger()
            send(c, "decline")
            if let b = l.banner { l.banner = nil; b.dismiss(animated: true) }
        case .end:
            send(c, "end")
        case .toggleMute:
            if CallAudio.shared.route == .mac { CallAudio.shared.setMacMicMuted(!CallAudio.shared.macMicMuted) }
            else { send(c, c.muted ? "unmute" : "mute") }
        case .audioPhone:
            if CallAudio.shared.route == .mac { CallAudio.shared.usePhone() }
            if c.speaker { send(c, "speaker_off") }
        case .audioSpeaker:
            if CallAudio.shared.route == .mac { CallAudio.shared.usePhone(); send(c, "speaker_on") }
            else { send(c, c.speaker ? "speaker_off" : "speaker_on") }
        case .expand:
            openWindow(l, focus: true)
        case .options:
            showMenu(phone: phone)
        case .hide:
            toBackground(phone: phone)
        }
    }

    // MARK: Background

    /// Swiped away (or "Hide" in its menu): nothing on screen, and the Mac stops ringing for it. The call stays in the menu
    /// bar and the Notifications window; a click there brings it back.
    func toBackground(phone: String) {
        guard let l = live[phone], !l.ui.ended else { return }
        l.ui.inBackground = true
        if l.ui.call.state == .ringing {
            silenced.insert(l.ui.call.id)
            updateRinger()
        }
        if let b = l.banner { l.banner = nil; b.dismiss(animated: b.isVisible) }
        if let p = l.pill { l.pill = nil; if p.isVisible { p.dismiss() } }
        if let w = l.window, w.isVisible { w.orderOut(nil) }
        InputLog.shared.log("call: sent to the background")
        publishBoard()
    }

    /// The call's options: press and hold, right-click or Control-click on the banner or the pill.
    private func showMenu(phone: String) {
        callMenu(phone: phone)?.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
    }

    private func callMenu(phone: String) -> NSMenu? {
        guard let l = live[phone], !l.ui.ended else { return nil }
        let c = l.ui.call
        let m = NSMenu()
        m.autoenablesItems = false
        if c.state == .ringing {
            m.addItem(ClosureMenuItem("Answer", enabled: c.can.contains("answer")) { [weak self] in self?.perform(.answer, phone: phone) })
            m.addItem(ClosureMenuItem("Decline", enabled: c.can.contains("decline")) { [weak self] in self?.perform(.decline, phone: phone) })
        } else {
            m.addItem(ClosureMenuItem("Show Call Window") { [weak self] in self?.perform(.expand, phone: phone) })
            m.addItem(ClosureMenuItem(l.ui.isMuted ? "Unmute" : "Mute", enabled: l.ui.canMute) { [weak self] in self?.perform(.toggleMute, phone: phone) })
        }
        m.addItem(.separator())
        m.addItem(ClosureMenuItem(c.state == .ringing ? "Hide and Silence" : "Hide (Keep in Menu Bar)") { [weak self] in
            self?.toBackground(phone: phone)
        })
        if c.state != .ringing {
            m.addItem(.separator())
            m.addItem(ClosureMenuItem("End Call", enabled: c.can.contains("end")) { [weak self] in self?.perform(.end, phone: phone) })
        }
        return m
    }

    private func swipeable(_ p: NSWindow, phone: String) -> PanelSwipe {
        let sw = PanelSwipe(panel: p)
        sw.onAway = { [weak self] in self?.toBackground(phone: phone) }
        sw.menu = { [weak self] in self?.callMenu(phone: phone) }
        return sw
    }

    /// §13.5: the Audio menu picked one of the phone's routes.
    private func selectPhoneRoute(_ dev: String) {
        guard let l = live.values.first(where: { $0.ui.call.state == .active || $0.ui.call.state == .held }) else { return }
        let c = l.ui.call
        if CallAudio.shared.route == .mac { CallAudio.shared.usePhone() }
        if c.can.contains("route") {
            PeerBus.shared.send(to: c.phoneId, PhoneNotifWire.callRoute(c.id, dev: dev))
        } else {
            // An older AirBridge on the phone: speaker on / off is all it can do.
            send(c, dev == "phone:speaker" ? "speaker_on" : "speaker_off")
        }
    }

    /// Hands the phone's routes to `CallAudio` (the Audio menu and the hands-free side read them there).
    private func reportRoutes(_ c: PhoneCall) {
        let routes = RouteText.menuRoutes(c.routes)
        let name = PhoneNotifCenter.shared.phoneName(c.phoneId)
        let devices = routes.map { CallAudio.Device(id: $0.id, name: RouteText.name($0, phoneName: name), symbol: RouteText.symbol($0)) }
        let selected = routes.contains { $0.id == c.route } ? c.route : nil
        if devices != CallAudio.shared.phoneRoutes || selected != CallAudio.shared.selectedPhoneRoute {
            CallAudio.shared.report(phoneRoutes: devices, selected: selected)
        }
    }

    private func send(_ c: PhoneCall, _ op: String) { PhoneNotifCenter.shared.callAction(c, op) }

    // MARK: Ringing

    private func inputs(_ phone: String) -> RingInputs {
        RingInputs(mode: RingMode.current, macIdle: MacPresence.idleSeconds(), macAvailable: MacPresence.available(),
                   phone: presence[phone], proximity: BeaconScanner.shared.proximity(phone), now: Date())
    }

    private func reevaluate(_ l: Live, phone: String) {
        guard l.ring != nil else { return }
        _ = l.ring?.update(inputs(phone))
        applyRing(l, phone: phone)
        updateRinger()
    }

    private func applyRing(_ l: Live, phone: String) {
        guard let d = l.ring?.decision else { return }
        // A Focus or DND on the Mac: the call still shows here but doesn't ring, and the phone isn't silenced either, so it
        // rings or not by its own settings (#11).
        let focus = MacFocus.isOn
        l.ui.ringingHere = d.macRings && !focus
        if d.silencePhone && !focus && !l.silenceSent,
           PeerBus.shared.peers().contains(where: { $0.id == phone && $0.caps.contains(PhoneNotifCaps.callRing) }) {
            l.silenceSent = true
            PeerBus.shared.send(to: phone, PhoneNotifWire.ring(l.ui.call.id, silencePhone: true))
        }
        let i = inputs(phone)
        InputLog.shared.log("call ring: \(d.reason.rawValue) mac=\(d.macRings) silencePhone=\(d.silencePhone) focus=\(focus) " +
            "proximity=\(i.proximity) macIdle=\(i.macIdle.map { String(format: "%.0fs", $0) } ?? "?")")
    }

    /// One ringtone for all phones; a 1 s tick re-checks presence while a call rings.
    private func updateRinger() {
        let ringing = live.values.contains { $0.ring != nil && $0.ui.call.state == .ringing }
        let ringingHere = live.values.filter { $0.ui.ringingHere && $0.ui.call.state == .ringing }
        if ringingHere.contains(where: { !silenced.contains($0.ui.call.id) }) {
            ringtone.start()
            if !volumeKeys.isWatching {
                volumeKeys.start { [weak self] in
                    guard let self else { return }
                    for l in self.live.values where l.ui.call.state == .ringing { self.silenced.insert(l.ui.call.id) }
                    InputLog.shared.log("call ring: silenced with a volume key")
                    self.updateRinger()
                }
            }
        } else {
            ringtone.stop()
            volumeKeys.stop()
        }
        // Forget silenced calls once they stop ringing.
        silenced = silenced.filter { id in live.values.contains { $0.ui.call.id == id && $0.ui.call.state == .ringing } }
        if ringing && ticker == nil {
            ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    for (phone, l) in self.live where l.ring != nil {
                        let before = l.ring?.decision
                        _ = l.ring?.update(self.inputs(phone))
                        // A Focus switched on or off while it rings counts as a change too.
                        let wanted = (l.ring?.decision.macRings ?? false) && !MacFocus.isOn
                        if l.ring?.decision != before || l.ui.ringingHere != wanted { self.applyRing(l, phone: phone) }
                    }
                    self.updateRinger()
                }
            }
        } else if !ringing {
            ticker?.invalidate()
            ticker = nil
        }
    }

    // MARK: Windows

    private func showBanner(_ l: Live) {
        let b = CallBannerPanel(ui: l.ui)
        l.banner = b
        l.bannerSwipe = swipeable(b, phone: l.ui.phoneId)
        let index = live.values.filter { $0.banner != nil && $0 !== l }.count
        b.present(index: index, animated: !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion)
    }

    private func openWindow(_ l: Live, focus: Bool) {
        if let p = l.pill { l.pill = nil; p.dismiss() }
        if l.window == nil {
            let w = InCallWindow(ui: l.ui)
            w.setFrame(SmartPlacement.frame(for: w.frame.size, avoiding: w), display: false)
            w.onCollapse = { [weak self, weak l] in
                guard let self, let l, !l.ui.ended, !l.ui.inBackground else { return }
                self.showPill(l)
            }
            l.window = w
        }
        if focus { l.window?.showAndFocus() } else { l.window?.showQuietly() }
    }

    /// The call without its window: the pill at the top right, until the call ends or the window comes back.
    private func showPill(_ l: Live) {
        guard l.pill == nil else { l.pill?.orderFrontRegardless(); return }
        let p = CallPillPanel(ui: l.ui)
        l.pill = p
        l.pillSwipe = swipeable(p, phone: l.ui.phoneId)
        p.present(near: l.window)
    }

    private func resizeWindow(_ l: Live) {
        guard let w = l.window, let host = (w.contentView as? NSGlassEffectView)?.contentView else { return }
        DispatchQueue.main.async {
            let size = host.fittingSize
            var f = w.frame
            let top = f.maxY
            f.size = w.frameRect(forContentRect: NSRect(origin: .zero, size: size)).size
            f.origin.y = top - f.height
            w.setFrame(f, display: true, animate: true)
        }
    }

    private func tearDown(_ l: Live, phone: String) {
        l.flashWork?.cancel()
        l.closeWork?.cancel()
        l.ring = nil
        if let b = l.banner { l.banner = nil; b.dismiss(animated: true) }
        if let p = l.pill { l.pill = nil; p.dismiss() }
        if let w = l.window {
            l.window = nil
            if w.isVisible { w.fadeOutAndClose() } else { w.close() }
        }
        if photos[phone]?.id == l.ui.call.id { photos[phone] = nil }
        updateRinger()
    }

    private func publishBoard() {
        board.calls = live.values.filter { !$0.ui.ended }.map(\.ui).sorted { $0.phoneId < $1.phoneId }
    }
}

#if DEBUG
extension CallPanels {
    /// `airbridge://debug/call_swipe?dx=-200`: a two-finger swipe on the fake call's pill (or banner while it rings).
    static func debugSwipe(dx: Int32) {
        guard let l = shared.live["debug-phone"] else { return }
        (l.pillSwipe ?? l.bannerSwipe)?.debugSwipe(dx: dx)
    }

    /// `airbridge://debug/fake_call?state=ringing|active|ended&name=…&number=…&label=…&muted=1&speaker=1`: drives the
    /// banner, window, ring decision and ringtone with a made-up call (phone id "debug-phone"; its buttons go nowhere).
    static func debugFakeCall(_ q: [URLQueryItem]) {
        func v(_ k: String) -> String? { q.first { $0.name == k }?.value }
        let state = v("state").flatMap(PhoneCallState.init(rawValue:)) ?? .ringing
        let c = PhoneCall(phoneId: "debug-phone", id: v("id") ?? "debug-call", state: state, name: v("name"), number: v("number"),
                          since: state == .active ? Date().addingTimeInterval(-5) : nil, muted: v("muted") == "1",
                          speaker: v("speaker") == "1",
                          can: state == .ringing ? ["answer", "decline"] : ["end", "mute", "speaker"], label: v("label"))
        if state == .ended { shared.end(c) } else { shared.show(c) }
    }
}
#endif
