import AppKit
import Combine
import SwiftUI

/// What the dock view shows.
@MainActor final class DockModel: ObservableObject {
    @Published var targets: [PeerInfo] = []
    @Published var layout = DockLayout(devices: [])
    /// Cursor in dock coordinates while a drag is over or near the dock.
    @Published var cursor: CGPoint?
    @Published var shown = false
    /// "Report.pdf" or "3 items".
    @Published var itemTitle = ""
    /// Files were dropped without a target: they wait here for a click.
    @Published var held = false
    /// The target that just took a drop (plays a little "gulp").
    @Published var took: DockSlot.Kind?

    var hot: DockSlot.Kind? { cursor.flatMap { layout.hit($0)?.kind } }

    func peer(_ id: String) -> PeerInfo? { targets.first { $0.id == id } }
}

/// The drop dock under the menu bar icon: drag files near the icon and a small shelf of connected devices
/// slides down; drop on a device to send.
///
/// How the drag is noticed (both paths, since neither is guaranteed on every macOS version):
/// - the menu bar icon itself is registered for file drags (`StatusDropCatcher`);
/// - a global left-mouse-dragged monitor checks whether the drag pasteboard was just filled with files, then
///   polls the cursor while the button is held and opens the dock when it comes near the icon.
@MainActor final class DropDock: NSObject {
    static let shared = DropDock()

    let model = DockModel()
    private var panel: NSPanel?
    private var host: DockDropHostView?
    static let margin: CGFloat = 26

    private var monitors: [Any] = []
    private var dragPBCount = NSPasteboard(name: .drag).changeCount
    private var gestureStart = Date.distantPast
    private var gestureDecided = true
    private var lastCheck = Date.distantPast
    private var tracking = false
    private var poll: Timer?
    private var closeWork: DispatchWorkItem?
    private var holdTimeout: DispatchWorkItem?
    private var heldFiles: [URL] = []
    private var heldTemp: [URL] = []
    private var peersSub: AnyCancellable?
    private let proximity = DockProximity()
    private let catcher = StatusDropCatcher()

    var isOpen: Bool { panel?.isVisible == true && model.shown }

    /// Screen the menu bar icon is on (the HUD uses it too).
    var statusScreen: NSScreen? { statusWindow()?.screen }

    // MARK: Start

    func start() {
        guard monitors.isEmpty else { return }
        if let m = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown], handler: { [weak self] _ in
            MainActor.assumeIsolated { self?.mouseDown() }
        }) { monitors.append(m) }
        if let m = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDragged], handler: { [weak self] _ in
            MainActor.assumeIsolated { self?.mouseDragged() }
        }) { monitors.append(m) }
        peersSub = AppModel.shared.$readyPeers.receive(on: DispatchQueue.main).sink { [weak self] peers in
            MainActor.assumeIsolated { self?.peersChanged(peers) }
        }
        // The status item's window exists once SwiftUI has built the menu bar extra.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.installCatcher() }
    }

    private func installCatcher() {
        guard let w = statusWindow() else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.installCatcher() }
            return
        }
        catcher.install(on: w)
    }

    /// The menu bar icon's window (SwiftUI's MenuBarExtra keeps it private).
    private func statusWindow() -> NSWindow? {
        NSApp.windows.first { $0.className.contains("StatusBarWindow") && $0.frame.width < 200 }
    }

    private func iconFrame() -> CGRect? {
        guard let w = statusWindow(), w.isVisible || w.occlusionState.contains(.visible) else { return nil }
        return w.frame
    }

    // MARK: Spotting a file drag

    private func mouseDown() {
        dragPBCount = NSPasteboard(name: .drag).changeCount
        gestureStart = Date()
        gestureDecided = false
        if model.held { closeHeld() }
    }

    private func mouseDragged() {
        guard !gestureDecided, !tracking, DropSettings.dockEnabled else { return }
        let now = Date()
        guard now.timeIntervalSince(lastCheck) > 0.08 else { return }
        lastCheck = now
        // A drag session writes the drag pasteboard as it starts; until then this is just a mouse drag.
        if now.timeIntervalSince(gestureStart) > 2 { gestureDecided = true; return }
        let pb = NSPasteboard(name: .drag)
        guard pb.changeCount != dragPBCount else { return }
        gestureDecided = true
        guard DropItems.holdsFiles(pb) else { return }
        beginTracking(pb)
    }

    private func beginTracking(_ pb: NSPasteboard) {
        tracking = true
        installCatcher()
        model.itemTitle = itemTitle(pb)
        poll?.invalidate()
        let t = Timer(timeInterval: 1.0 / 60, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        poll = t
    }

    private func tick() {
        guard NSEvent.pressedMouseButtons & 1 != 0 else { endTracking(); return }
        let loc = NSEvent.mouseLocation
        let dockRect = isOpen ? contentScreenRect() : nil
        if proximity.shouldBeOpen(cursor: loc, icon: iconFrame(), dock: dockRect) {
            cancelClose()
            if !isOpen { open() }
        } else if isOpen, !model.held {
            scheduleClose(after: 0.22)
        }
        if isOpen, let p = panel {
            let c = toDock(window: NSPoint(x: loc.x - p.frame.minX, y: loc.y - p.frame.minY))
            if model.cursor != c { model.cursor = c }
        }
    }

    private func endTracking() {
        tracking = false
        poll?.invalidate()
        poll = nil
        model.cursor = nil
        // A drop on the dock is handled by the drop view (and closes the dock itself); give it a moment.
        if isOpen, !model.held { scheduleClose(after: 0.45) }
    }

    private func itemTitle(_ pb: NSPasteboard) -> String {
        let urls = DropItems.fileURLs(pb)
        if !urls.isEmpty { return DropItems.title(names: urls.map(\.lastPathComponent)) }
        let n = DropItems.count(pb)
        return n == 1 ? "1 item" : "\(n) items"
    }

    // MARK: Open / close

    private func targetsNow() -> [PeerInfo] {
        AppModel.shared.readyPeers.filter { $0.caps.contains("xfer") }
    }

    private func peersChanged(_ peers: [PeerInfo]) {
        guard panel?.isVisible == true else { return }
        refreshTargets()
        place()
    }

    private func refreshTargets() {
        let t = targetsNow()
        if model.targets != t { model.targets = t }
        let l = DockLayout(devices: t.map(\.id))
        if model.layout != l { model.layout = l }
    }

    /// Opens the dock (from a drag near the icon, or from the icon's drop catcher).
    func open() {
        guard DropSettings.dockEnabled else { return }
        cancelClose()
        refreshTargets()
        let p = panel ?? makePanel()
        place()
        if !p.isVisible {
            model.shown = false
            p.orderFrontRegardless()
        }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            withAnimation(DropMotion.dock) { self.model.shown = true }
        }
    }

    /// Called by the icon's catcher when a drag enters it.
    func dragEnteredIcon(_ pb: NSPasteboard) {
        guard DropSettings.dockEnabled else { return }
        if !tracking, DropItems.holdsFiles(pb) {
            gestureDecided = true
            beginTracking(pb)
        }
        open()
    }

    private func scheduleClose(after delay: TimeInterval) {
        guard closeWork == nil else { return }
        let w = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.closeWork = nil
                if !self.model.held { self.close() }
            }
        }
        closeWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: w)
    }

    private func cancelClose() {
        closeWork?.cancel()
        closeWork = nil
    }

    func close() {
        cancelClose()
        holdTimeout?.cancel()
        guard let p = panel, p.isVisible else { return }
        withAnimation(DropMotion.dock) { model.shown = false }
        model.held = false
        model.cursor = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.28) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, !self.model.shown else { return }
                p.orderOut(nil)
                self.model.took = nil
            }
        }
    }

    private func closeHeld() {
        let temp = heldTemp
        heldFiles = []
        heldTemp = []
        Self.removeTemp(temp)
        close()
    }

    // MARK: Panel

    private func makePanel() -> NSPanel {
        let host = DockDropHostView(rootView: DropDockView(model: model, dock: self))
        host.sizingOptions = []
        host.registerForDraggedTypes([.fileURL] + NSFilePromiseReceiver.readableDraggedTypes.map { NSPasteboard.PasteboardType($0) })
        host.dock = self
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 300, height: 200),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.contentView = host
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = false
        p.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)
        p.isFloatingPanel = true
        p.hidesOnDeactivate = false
        p.becomesKeyOnlyIfNeeded = true
        p.isReleasedWhenClosed = false
        p.isMovable = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle, .transient]
        p.setAccessibilityLabel("AirBridge drop dock")
        panel = p
        self.host = host
        return p
    }

    /// Centres the dock under the menu bar icon, inside the icon's screen.
    private func place() {
        guard let p = panel else { return }
        let m = Self.margin
        let size = NSSize(width: model.layout.size.width + 2 * m, height: model.layout.size.height + 2 * m)
        let screen = statusScreen ?? NSScreen.main
        let full = screen?.frame ?? .zero
        let visible = screen?.visibleFrame ?? .zero
        let icon = iconFrame() ?? CGRect(x: full.maxX - 160, y: visible.maxY, width: 24, height: full.maxY - visible.maxY)
        var x = icon.midX - size.width / 2
        x = min(max(x, full.minX + 4 - m), full.maxX - size.width - 4 + m)
        let top = min(icon.minY, visible.maxY) - 6 + m
        p.setFrame(NSRect(x: x, y: top - size.height, width: size.width, height: size.height), display: true)
    }

    private func contentScreenRect() -> CGRect? {
        guard let p = panel else { return nil }
        return p.frame.insetBy(dx: Self.margin, dy: Self.margin)
    }

    /// Window point (AppKit, bottom-left origin) → dock coordinates (top-left origin of the dock body).
    func toDock(window p: NSPoint) -> CGPoint {
        let h = panel?.frame.height ?? 0
        return CGPoint(x: p.x - Self.margin, y: (h - p.y) - Self.margin)
    }

    // MARK: Drops

    func dragUpdated(windowPoint: NSPoint) {
        cancelClose()
        let c = toDock(window: windowPoint)
        if model.cursor != c { model.cursor = c }
    }

    func dragExited() { model.cursor = nil }

    /// Files dropped on the dock at `windowPoint`.
    func drop(_ pb: NSPasteboard, windowPoint: NSPoint) -> Bool {
        let slot = model.layout.hit(toDock(window: windowPoint))?.kind
        return accept(pb, slot: slot)
    }

    /// Files dropped right on the menu bar icon.
    func dropOnIcon(_ pb: NSPasteboard) -> Bool {
        refreshTargets()
        return accept(pb, slot: nil)
    }

    private func accept(_ pb: NSPasteboard, slot: DockSlot.Kind?) -> Bool {
        let urls = DropItems.fileURLs(pb)
        let promises = urls.isEmpty ? DropItems.promises(pb) : []
        guard !urls.isEmpty || !promises.isEmpty else { return false }
        cancelClose()
        // With one device, a drop anywhere means that device; the bubble the drop landed on otherwise.
        let target: DockSlot.Kind? = slot ?? (model.targets.count == 1 ? .device(model.targets[0].id) : nil)
        if let target {
            model.took = target
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.32) { [weak self] in self?.close() }
        } else {
            // Keep the dock up while the files are read; they then wait in it for a click.
            model.held = true
            model.cursor = nil
        }
        Self.resolve(urls: urls, promises: promises) { [weak self] files, temp in
            guard let self else { Self.removeTemp(temp); return }
            guard !files.isEmpty else {
                Self.removeTemp(temp)
                AppModel.shared.notice("Only files and folders with files in them can be sent")
                self.close()
                return
            }
            if let target {
                self.route(files, temp: temp, to: target)
            } else {
                self.hold(files, temp: temp)
            }
        }
        return true
    }

    /// Keeps dropped files in the open dock until a target is clicked.
    private func hold(_ files: [URL], temp: [URL]) {
        Self.removeTemp(heldTemp)
        heldFiles = files
        heldTemp = temp
        model.itemTitle = files.count == 1 ? files[0].lastPathComponent : "\(files.count) files"
        model.held = true
        model.cursor = nil
        open()
        holdTimeout?.cancel()
        let w = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { if self?.model.held == true { self?.closeHeld() } } }
        holdTimeout = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: w)
    }

    /// A target was clicked while files are held.
    func pick(_ kind: DockSlot.Kind) {
        guard model.held, !heldFiles.isEmpty else { return }
        let (files, temp) = (heldFiles, heldTemp)
        heldFiles = []
        heldTemp = []
        model.took = kind
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.close() }
        route(files, temp: temp, to: kind)
    }

    func dismissHeld() { closeHeld() }

    private func route(_ files: [URL], temp: [URL], to kind: DockSlot.Kind) {
        let cleanup: (() -> Void)? = temp.isEmpty ? nil : { Self.removeTemp(temp) }
        switch kind {
        case .airWeb:
            AirWebWindow.shared.show(offering: files, batches: temp)
        case .device(let id):
            guard let peer = model.peer(id) ?? targetsNow().first(where: { $0.id == id }) else {
                cleanup?()
                AppModel.shared.notice("That device is no longer connected")
                return
            }
            if targetsNow().count == 1 {
                TransferHUD.shared.queueSend(files, to: peer, cleanup: cleanup)
            } else {
                TransferHUD.shared.sendNow(files, to: peer, cleanup: cleanup)
            }
        }
    }

    // MARK: Files

    /// Turns dropped URLs (folders walked) and file promises (received into a temporary folder) into files.
    /// `temp` lists folders to delete once the files are no longer needed.
    private static func resolve(urls: [URL], promises: [NSFilePromiseReceiver],
                                done: @escaping @MainActor (_ files: [URL], _ temp: [URL]) -> Void) {
        if !urls.isEmpty {
            DispatchQueue.global(qos: .userInitiated).async {
                let files = DropItems.expand(urls)
                onMain { done(files, []) }
            }
            return
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("AirBridge Drops", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        do { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) } catch {
            done([], []); return
        }
        let queue = OperationQueue()
        queue.qualityOfService = .userInitiated
        let group = DispatchGroup()
        let lock = NSLock()
        nonisolated(unsafe) var got: [URL] = []
        for r in promises {
            let n = max(1, r.fileTypes.count)
            for _ in 0..<n { group.enter() }
            r.receivePromisedFiles(atDestination: dir, options: [:], operationQueue: queue) { url, error in
                if error == nil { lock.lock(); got.append(url); lock.unlock() }
                group.leave()
            }
        }
        DispatchQueue.global(qos: .userInitiated).async {
            _ = group.wait(timeout: .now() + 60)
            lock.lock(); let urls = got; lock.unlock()
            let files = DropItems.expand(urls)
            onMain { done(files, [dir]) }
        }
    }

    nonisolated static func removeTemp(_ dirs: [URL]) {
        guard !dirs.isEmpty else { return }
        DispatchQueue.global(qos: .utility).async {
            for d in dirs { try? FileManager.default.removeItem(at: d) }
        }
    }
}

// MARK: - Drop destinations

/// The dock's content view: hosts the SwiftUI dock and takes the drops itself, so hit-testing uses the same
/// `DockLayout` the bubbles are drawn from.
final class DockDropHostView: NSHostingView<DropDockView> {
    weak var dock: DropDock?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        guard DropItems.holdsFiles(sender.draggingPasteboard) else { return [] }
        dock?.dragUpdated(windowPoint: sender.draggingLocation)
        return .copy
    }

    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        guard DropItems.holdsFiles(sender.draggingPasteboard) else { return [] }
        dock?.dragUpdated(windowPoint: sender.draggingLocation)
        return .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) { dock?.dragExited() }

    override func prepareForDragOperation(_ sender: NSDraggingInfo) -> Bool { true }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        dock?.drop(sender.draggingPasteboard, windowPoint: sender.draggingLocation) ?? false
    }
}

/// Makes the menu bar icon a drop target. If SwiftUI left the status window without a delegate the window
/// itself is registered (AppKit hands its drag messages to the delegate); otherwise a transparent view
/// that ignores clicks is laid over the icon.
final class StatusDropCatcher: NSObject, NSWindowDelegate, NSDraggingDestination {
    private weak var window: NSWindow?
    private var overlay: StatusDropOverlay?

    func install(on w: NSWindow) {
        guard window !== w else { return }
        window = w
        let types: [NSPasteboard.PasteboardType] = [.fileURL] + NSFilePromiseReceiver.readableDraggedTypes.map { NSPasteboard.PasteboardType($0) }
        if w.delegate == nil {
            w.delegate = self
            w.registerForDraggedTypes(types)
        } else if let content = w.contentView {
            let v = StatusDropOverlay(frame: content.bounds)
            v.autoresizingMask = [.width, .height]
            v.catcher = self
            v.registerForDraggedTypes(types)
            content.addSubview(v)
            overlay = v
        }
    }

    func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        let pb = sender.draggingPasteboard
        guard DropSettings.dockEnabled, DropItems.holdsFiles(pb) else { return [] }
        MainActor.assumeIsolated { DropDock.shared.dragEnteredIcon(pb) }
        return .copy
    }

    func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        DropSettings.dockEnabled && DropItems.holdsFiles(sender.draggingPasteboard) ? .copy : []
    }

    func prepareForDragOperation(_ sender: NSDraggingInfo) -> Bool { true }

    func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let pb = sender.draggingPasteboard
        return MainActor.assumeIsolated { DropDock.shared.dropOnIcon(pb) }
    }
}

final class StatusDropOverlay: NSView {
    weak var catcher: StatusDropCatcher?

    // Clicks go to the icon underneath.
    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { catcher?.draggingEntered(sender) ?? [] }
    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation { catcher?.draggingUpdated(sender) ?? [] }
    override func prepareForDragOperation(_ sender: NSDraggingInfo) -> Bool { true }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool { catcher?.performDragOperation(sender) ?? false }
}
