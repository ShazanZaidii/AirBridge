import AppKit
import Combine
import SwiftUI

/// Live transfer cards in the top-right corner, one per transfer (sending and receiving), plus the
/// undo-send card. Cards stay until closed unless "hide finished cards" is on.
@MainActor final class TransferHUD: ObservableObject {
    static let shared = TransferHUD()

    @Published private(set) var state = HUDState()
    private var cleanups: [UUID: () -> Void] = [:]
    private var timer: DispatchWorkItem?
    private lazy var panel = HUDPanelController(hud: self)

    var cards: [HUDCard] { state.cards }

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
        panel.update(visible: !state.cards.isEmpty)
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
    private var height: CGFloat = 0
    private var shrinkToken = 0
    static let width: CGFloat = 340

    init(hud: TransferHUD) { self.hud = hud }

    func update(visible: Bool) {
        if visible {
            let p = panel ?? make()
            if !p.isVisible { place(p, height: max(height, 1)); p.orderFrontRegardless() }
        } else if let p = panel, p.isVisible {
            // Let the last card's exit animation play first.
            let token = shrinkToken &+ 1
            shrinkToken = token
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, self.shrinkToken == token, self.hud.cards.isEmpty else { return }
                    p.orderOut(nil)
                }
            }
        }
    }

    private func make() -> NSPanel {
        let root = TransferHUDView(hud: hud) { [weak self] h in self?.contentHeight(h) }
        let host = NSHostingView(rootView: root)
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
        return p
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
        p.setFrame(NSRect(x: v.maxX - Self.width - 6, y: v.maxY - h - 4, width: Self.width, height: h), display: true)
    }
}
