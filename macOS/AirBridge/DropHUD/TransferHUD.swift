import AppKit
import Combine
import SwiftUI

/// Live transfer cards in a corner of the screen (top right unless moved), one per transfer (sending and receiving), plus
/// the undo-send card. Cards stay until closed unless "hide finished cards" is on. A two-finger swipe (or a drag) sends a
/// card to the background: off screen, still listed in the menu bar and the Notifications window, back with a click.
/// Press and hold, right-click or Control-click a card for its options (hide, cancel, open, move the cards).
@MainActor final class TransferHUD: ObservableObject {
    static let shared = TransferHUD()

    @Published private(set) var state = HUDState()
    private var cleanups: [UUID: () -> Void] = [:]
    private var timer: DispatchWorkItem?
    private lazy var panel = HUDPanelController(hud: self)

    var cards: [HUDCard] { state.cards }
    /// The cards on screen.
    var visibleCards: [HUDCard] { state.visible }
    /// Cards in the background.
    var backgroundCards: [HUDCard] { state.backgrounded }

    /// A card following the fingers (or the mouse) sideways.
    struct Swipe: Equatable { let id: UUID; var dx: CGFloat }
    @Published var swipe: Swipe?

    // MARK: Inputs

    /// Every transfer report goes through here (from `AppModel.updateTransfer`).
    func ingest(_ s: TransferStatus) {
        state.ingest(s, now: Date())
        changed()
    }

    /// Sends `files` to one device after a short undo window. `cleanup` runs once the files are no longer
    /// needed (sent, failed or undone).
    func queueSend(_ files: [URL], to peer: PeerInfo, cleanup: (() -> Void)? = nil) {
        guard !files.isEmpty else { cleanup?(); return }
        let title = files.count == 1 ? files[0].lastPathComponent : "\(files.count) files"
        let id = state.queueSend(files: files, peerId: peer.id, peerName: peer.name, title: title, now: Date())
        if let cleanup { cleanups[id] = cleanup }
        changed()
    }

    /// Sends right away, with a card that is waiting for the transfer's first report.
    func sendNow(_ files: [URL], to peer: PeerInfo, cleanup: (() -> Void)? = nil) {
        guard !files.isEmpty else { cleanup?(); return }
        let title = files.count == 1 ? files[0].lastPathComponent : "\(files.count) files"
        let id = state.queueSend(files: files, peerId: peer.id, peerName: peer.name, title: title, now: Date(), delay: 0)
        if let cleanup { cleanups[id] = cleanup }
        changed()
    }

    // MARK: Card actions

    func undo(_ id: UUID) {
        guard state.undo(id) != nil else { return }
        runCleanup(id)
        changed()
    }

    /// Cancel on a running transfer (or Undo while counting down).
    func cancel(_ card: HUDCard) {
        if case .countdown = card.stage { undo(card.id); return }
        if let key = card.transferKey { PeerManager.shared.cancelTransfer(key: key) }
    }

    /// Off screen; the transfer keeps going and stays in the menu bar and the Notifications window.
    func sendToBackground(_ id: UUID) {
        state.sendToBackground(id)
        changed()
    }

    /// From the menu bar or the Notifications window: the card comes back on screen, on top.
    func bringBack(_ id: UUID) {
        state.bringBack(id, now: Date())
        changed()
    }

    /// The close button: a finished card goes away, a running one goes to the background (the transfer keeps going).
    func close(_ card: HUDCard) {
        if card.isFinished { dismiss(card.id) } else { sendToBackground(card.id) }
    }

    /// A swipe went far enough: the card flies off that way, then goes to the background.
    func swipedAway(_ id: UUID, dx: CGFloat) {
        withAnimation(.easeIn(duration: 0.16)) { swipe = Swipe(id: id, dx: dx > 0 ? 420 : -420) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.sendToBackground(id)
                // Leave the offset while the card's removal plays, so it doesn't jump back first.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    MainActor.assumeIsolated { if self.swipe?.id == id { self.swipe = nil } }
                }
            }
        }
    }

    /// A swipe that didn't go far enough springs back.
    func swipeBack() {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { swipe = nil }
    }

    #if DEBUG
    func debugSwipe(at pt: CGPoint, dx: Int32) { panel.debugSwipe(at: pt, dx: dx) }
    #endif

    /// Moves the cards to another corner of the screen.
    func move(to corner: HUDCorner) {
        DropSettings.corner = corner
        objectWillChange.send()
        panel.reposition()
    }

    // MARK: Options menu

    /// Press and hold, right-click or Control-click on a card.
    func showMenu(_ id: UUID) {
        guard let card = state.cards.first(where: { $0.id == id }) else { return }
        menu(for: card).popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
    }

    private func menu(for card: HUDCard) -> NSMenu {
        let m = NSMenu()
        m.autoenablesItems = false
        switch card.stage {
        case .countdown:
            m.addItem(ClosureMenuItem("Undo Send") { [weak self] in self?.undo(card.id) })
        case .launching:
            break
        case .transfer(let s):
            switch s.phase {
            case .waiting, .active:
                m.addItem(ClosureMenuItem("Cancel Transfer") { [weak self] in self?.cancel(card) })
            case .done:
                if card.direction == .incoming && !(card.status?.savedPaths.isEmpty ?? true) {
                    m.addItem(ClosureMenuItem("Open") { [weak self] in self?.open(card) })
                    m.addItem(ClosureMenuItem("Show in Finder") { [weak self] in self?.showInFinder(card) })
                }
            case .failed:
                if card.canRetry { m.addItem(ClosureMenuItem("Retry") { [weak self] in self?.retry(card) }) }
            }
        }
        if !m.items.isEmpty { m.addItem(.separator()) }
        m.addItem(ClosureMenuItem("Hide (Keep in Menu Bar)") { [weak self] in self?.sendToBackground(card.id) })
        let move = NSMenuItem(title: "Move Cards To", action: nil, keyEquivalent: "")
        let corners = NSMenu()
        for c in HUDCorner.allCases {
            corners.addItem(ClosureMenuItem(c.title, checked: DropSettings.corner == c) { [weak self] in self?.move(to: c) })
        }
        move.submenu = corners
        m.addItem(move)
        if card.isFinished {
            m.addItem(.separator())
            m.addItem(ClosureMenuItem("Close") { [weak self] in self?.dismiss(card.id) })
        }
        return m
    }

    func dismiss(_ id: UUID) {
        guard let card = state.dismiss(id) else { return }
        if case .countdown = card.stage { runCleanup(id) }
        changed()
    }

    func retry(_ card: HUDCard) {
        guard card.canRetry else { return }
        let files = card.files.filter { FileManager.default.fileExists(atPath: $0.path) }
        guard !files.isEmpty else {
            state.fail(card.id, reason: "The files are no longer there", now: Date(), filesGone: true)
            changed()
            return
        }
        guard AppModel.shared.readyPeers.contains(where: { $0.id == card.peerId }) else {
            state.fail(card.id, reason: "\(card.peerName) is not connected", now: Date())
            changed()
            return
        }
        state.relaunch(card.id, now: Date())
        PeerManager.shared.sendFiles(files, to: card.peerId, cleanup: nil)
        changed()
    }

    func showInFinder(_ card: HUDCard) {
        let urls = existing(card.status?.savedPaths ?? [])
        if !urls.isEmpty { NSWorkspace.shared.activateFileViewerSelecting(urls) }
    }

    func open(_ card: HUDCard) {
        let urls = existing(card.status?.savedPaths ?? [])
        if urls.count == 1 { NSWorkspace.shared.open(urls[0]) } else if !urls.isEmpty { NSWorkspace.shared.activateFileViewerSelecting(urls) }
    }

    private func existing(_ paths: [String]) -> [URL] {
        paths.map { URL(fileURLWithPath: $0) }.filter { FileManager.default.fileExists(atPath: $0.path) }
    }

    // MARK: Clock

    private func changed() {
        let now = Date()
        for card in state.due(now: now) { launch(card) }
        state.expire(now: now, autoHide: DropSettings.autoHideFinished)
        schedule()
        panel.update(visible: !state.visible.isEmpty)
    }

    private func launch(_ card: HUDCard) {
        let cleanup = cleanups.removeValue(forKey: card.id)
        guard AppModel.shared.readyPeers.contains(where: { $0.id == card.peerId }) else {
            state.fail(card.id, reason: "\(card.peerName) is not connected", now: Date())
            if let cleanup { DispatchQueue.global(qos: .utility).async { cleanup() } }
            return
        }
        PeerManager.shared.sendFiles(card.files, to: card.peerId, cleanup: cleanup)
    }

    private func runCleanup(_ id: UUID) {
        if let c = cleanups.removeValue(forKey: id) { DispatchQueue.global(qos: .utility).async { c() } }
    }

    private func schedule() {
        timer?.cancel()
        guard let next = state.nextEvent(autoHide: DropSettings.autoHideFinished) else { return }
        let item = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.changed() } }
        timer = item
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0.02, next.timeIntervalSinceNow + 0.01), execute: item)
    }

    /// The auto-hide setting changed.
    func settingsChanged() { changed() }
}

// MARK: - Panel

/// Borderless, non-activating panel under the menu bar on the right. It is sized to its cards (so it never
/// blocks clicks around them) and keeps its top edge fixed while cards come and go.
@MainActor final class HUDPanelController {
    private let hud: TransferHUD
    private var panel: NSPanel?
    private var host: NSView?
    private var height: CGFloat = 0
    private var shrinkToken = 0
    /// Separate from [shrinkToken]: a shrink after the last card left must not cancel hiding the panel.
    private var hideToken = 0
    private var monitor: Any?
    private let tracker = SwipeAway()
    private var swipeCard: UUID?
    /// Where each card is, in the panel's content (top-left origin), to find the card under the pointer.
    var cardFrames: [UUID: CGRect] = [:]
    static let width: CGFloat = 340

    init(hud: TransferHUD) { self.hud = hud }

    func update(visible: Bool) {
        hideToken &+= 1
        if visible {
            let p = panel ?? make()
            if !p.isVisible { place(p, height: max(height, 1)); p.orderFrontRegardless() }
        } else if let p = panel, p.isVisible {
            // Let the last card's exit animation play first.
            let token = hideToken
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, self.hideToken == token, self.hud.visibleCards.isEmpty else { return }
                    p.orderOut(nil)
                }
            }
        }
    }

    private func make() -> NSPanel {
        let root = TransferHUDView(hud: hud, onHeight: { [weak self] h in self?.contentHeight(h) },
                                   onCardFrames: { [weak self] f in self?.cardFrames = f })
        let host = NSHostingView(rootView: root)
        self.host = host
        host.sizingOptions = []
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: Self.width, height: 100),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.contentView = host
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = false
        p.level = .statusBar
        p.isFloatingPanel = true
        p.hidesOnDeactivate = false
        p.becomesKeyOnlyIfNeeded = true
        p.isReleasedWhenClosed = false
        p.isMovable = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        p.setAccessibilityLabel("AirBridge transfers")
        panel = p
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.scrollWheel, .rightMouseDown, .leftMouseDown]) { [weak self] e in
            self?.handle(e) ?? e
        }
        return p
    }

    /// The card under the pointer for an event in the panel.
    private func card(at e: NSEvent) -> UUID? {
        guard let host else { return nil }
        var pt = host.convert(e.locationInWindow, from: nil)
        if !host.isFlipped { pt.y = host.bounds.height - pt.y }
        return card(atContent: pt)
    }

    private func card(atContent pt: CGPoint) -> UUID? { cardFrames.first { $0.value.contains(pt) }?.key }

    /// Two-finger swipes move a card; right-click and Control-click open its options.
    private func handle(_ e: NSEvent) -> NSEvent? {
        guard let p = panel, e.window === p else { return e }
        switch e.type {
        case .rightMouseDown:
            guard let id = card(at: e) else { return e }
            hud.showMenu(id)
            return nil
        case .leftMouseDown:
            guard e.modifierFlags.contains(.control), let id = card(at: e) else { return e }
            hud.showMenu(id)
            return nil
        case .scrollWheel:
            if e.phase.contains(.began) { swipeCard = card(at: e) }
            return scroll(e)
        default:
            return e
        }
    }

    private func scroll(_ e: NSEvent) -> NSEvent? {
            guard let id = swipeCard else { return e }
            switch tracker.handle(e) {
            case .ignored:
                return e
            case .moved(let dx):
                hud.swipe = .init(id: id, dx: dx)
                return nil
            case .away(let dx):
                swipeCard = nil
                hud.swipedAway(id, dx: dx)
                return nil
            case .back:
                swipeCard = nil
                hud.swipeBack()
                return nil
            }
    }

    #if DEBUG
    /// A two-finger swipe of [dx] points at [pt] (panel content, top-left origin), built from the same scroll events a
    /// trackpad sends, through the same handling (only the window-server routing is skipped).
    func debugSwipe(at pt: CGPoint, dx: Int32) {
        func ev(_ d: Int32, _ phase: Int64) -> NSEvent? {
            guard let c = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: 0, wheel2: d, wheel3: 0) else { return nil }
            c.setIntegerValueField(.scrollWheelEventScrollPhase, value: phase)
            c.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
            return NSEvent(cgEvent: c)
        }
        swipeCard = card(atContent: pt)
        var steps: [NSEvent] = [ev(0, 1)].compactMap { $0 }
        steps += (0..<12).compactMap { _ in ev(dx / 12, 2) }
        if let end = ev(0, 4) { steps.append(end) }
        for (i, e) in steps.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.012) { [weak self] in
                MainActor.assumeIsolated { _ = self?.scroll(e) }
            }
        }
    }
    #endif

    /// The corner changed: jump there.
    func reposition() {
        guard let p = panel, p.isVisible else { return }
        place(p, height: max(height, 1))
    }

    private func contentHeight(_ h: CGFloat) {
        let h = ceil(h)
        guard h != height else { return }
        if h > height {
            height = h
            if let p = panel { place(p, height: h) }
        } else {
            // Shrink after the removal animation so cards don't get clipped mid-flight.
            let token = shrinkToken &+ 1
            shrinkToken = token
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, self.shrinkToken == token else { return }
                    self.height = h
                    if let p = self.panel, h > 0 { self.place(p, height: h) }
                }
            }
        }
    }

    private func place(_ p: NSPanel, height h: CGFloat) {
        guard let screen = DropDock.shared.statusScreen ?? NSScreen.main else { return }
        let v = screen.visibleFrame
        let c = DropSettings.corner
        let x = c.isLeft ? v.minX + 6 : v.maxX - Self.width - 6
        let y = c.isTop ? v.maxY - h - 4 : v.minY + 4
        p.setFrame(NSRect(x: x, y: y, width: Self.width, height: h), display: true)
    }
}
