import AppKit
import AVFoundation
import Combine
import CoreMedia
import SwiftUI

// MARK: - Rendering

/// Shows decoded frames on an `AVSampleBufferDisplayLayer` the moment they arrive (no clock, no queueing).
/// Called from the mirror session's queue.
nonisolated final class MirrorRenderer: @unchecked Sendable {
    let layer: AVSampleBufferDisplayLayer
    private var format: CMVideoFormatDescription?

    init(layer: AVSampleBufferDisplayLayer) { self.layer = layer }

    #if DEBUG
    /// Last frame shown, for airbridge://debug/mirror_snapshot (test rigs only).
    nonisolated(unsafe) static var lastFrame: CVPixelBuffer?
    /// Wall-clock time (s since 1970) the last frame was handed to the display layer.
    nonisolated(unsafe) static var lastFrameAt: Double = 0
    #endif

    func enqueue(_ pb: CVPixelBuffer) {
        #if DEBUG
        MirrorRenderer.lastFrame = pb
        MirrorRenderer.lastFrameAt = Date().timeIntervalSince1970
        #endif
        if format == nil || !CMVideoFormatDescriptionMatchesImageBuffer(format!, imageBuffer: pb) {
            var fd: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pb, formatDescriptionOut: &fd)
            format = fd
        }
        guard let fd = format else { return }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
                                        decodeTimeStamp: .invalid)
        var sb: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: pb, formatDescription: fd,
                                                       sampleTiming: &timing, sampleBufferOut: &sb) == noErr, let sb else { return }
        if let atts = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true), CFArrayGetCount(atts) > 0 {
            let d = unsafeBitCast(CFArrayGetValueAtIndex(atts, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(d, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        let r = layer.sampleBufferRenderer
        if r.status == .failed { r.flush() }
        r.enqueue(sb)
    }

    func clear() { layer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil) }
}

// MARK: - Window model

@MainActor final class PhoneMirrorModel: ObservableObject {
    @Published var state = PhoneMirrorState()
    @Published var name: String
    /// Short-lived hint at the bottom of the phone ("Turn on … to type here").
    @Published var hint: String?
    private var hintToken = 0

    init(name: String) { self.name = name }

    func flash(_ text: String, seconds: Double = 4) {
        hint = text
        hintToken += 1
        let token = hintToken
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.hintToken == token else { return }
                self.hint = nil
            }
        }
    }

    var interactive: Bool { if case .stopped = state.phase { return true } else { return false } }
}

// MARK: - Windows

/// Opens one mirror window per phone; a second "Mirror" for the same phone brings its window forward.
@MainActor final class PhoneMirrorWindows {
    static let shared = PhoneMirrorWindows()
    private var open: [String: PhoneMirrorWindowController] = [:]

    func show(_ peer: PeerInfo) {
        if let c = open[peer.id] {
            c.bringForward(restart: true)
            return
        }
        let c = PhoneMirrorWindowController(peer: peer)
        open[peer.id] = c
        c.onClose = { [weak self] in self?.open[peer.id] = nil }
        c.present()
    }

    func isOpen(_ peerId: String) -> Bool { open[peerId] != nil }
}

/// Borderless window that can still become key (keyboard goes to the phone).
final class MirrorWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
    // Borderless windows have no close button, so the standard performClose (⌘W) would just beep.
    override func performClose(_ sender: Any?) {
        if let d = delegate, d.responds(to: #selector(NSWindowDelegate.windowShouldClose(_:))), d.windowShouldClose?(self) == false { return }
        close()
    }
    override func validateMenuItem(_ item: NSMenuItem) -> Bool {
        item.action == #selector(performClose(_:)) || item.action == #selector(performMiniaturize(_:)) ? true : super.validateMenuItem(item)
    }
}

@MainActor final class PhoneMirrorWindowController: NSObject, NSWindowDelegate {
    static let barHeight: CGFloat = 40
    static let gap: CGFloat = 6

    let peer: PeerInfo
    let model: PhoneMirrorModel
    let session: PhoneMirrorSession
    /// Pro mirroring (Wireless debugging + scrcpy), when this phone is set up for it; see ProMirror/.
    private var pro: ProMirrorSession?
    private var usingPro = false
    private let window: MirrorWindow
    private let root: MirrorRootView
    private var aspect: CGFloat = 9.0 / 19.5      // width / height of the phone picture
    var onClose: (() -> Void)?
    private var closing = false

    init(peer: PeerInfo) {
        self.peer = peer
        model = PhoneMirrorModel(name: peer.name)
        session = PhoneMirrorSession(peer: peer.id)
        let size = Self.initialPhoneSize(aspect: aspect)
        let frame = NSRect(x: 0, y: 0, width: size.width, height: size.height + Self.barHeight + Self.gap)
        window = MirrorWindow(contentRect: frame, styleMask: [.borderless, .resizable, .miniaturizable],
                              backing: .buffered, defer: false)
        root = MirrorRootView(frame: frame, model: model)
        super.init()

        window.isReleasedWhenClosed = false
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = true
        window.title = "\(peer.name)"
        window.collectionBehavior = [.fullScreenNone, .managed]
        window.contentView = root
        window.delegate = self
        window.minSize = NSSize(width: 180, height: 260)
        window.center()

        root.screen.peer = peer.id
        root.screen.model = model
        root.bar.onClose = { [weak self] in self?.window.performClose(nil) }
        root.bar.onMinimize = { [weak self] in self?.window.miniaturize(nil) }
        root.bar.onAction = { [weak self] name in self?.root.screen.sendAction(name) }
        root.bar.menuProvider = { [weak self] in self?.optionsMenu() }
        root.screen.onWindowCommand = { [weak self] cmd in self?.command(cmd) }
        root.onRetry = { [weak self] in self?.restart() }

        let renderer = MirrorRenderer(layer: root.screen.videoLayer)
        root.screen.renderer = renderer
        session.onFrame = { @Sendable pb in renderer.enqueue(pb) }
        session.onState = { @Sendable [weak self] s in
            DispatchQueue.main.async { MainActor.assumeIsolated { if self?.usingPro == false { self?.apply(s) } } }
        }
        PhoneMirrorHub.shared.register(session)
    }

    private static func initialPhoneSize(aspect: CGFloat) -> NSSize {
        let vis = NSScreen.main?.visibleFrame.size ?? NSSize(width: 1440, height: 900)
        let h = min(vis.height * 0.72, 820)
        return NSSize(width: (h * aspect).rounded(), height: h.rounded())
    }

    func present() {
        startPreferred()
        NSApp.activate(ignoringOtherApps: true)
        // Open: fade in while growing from 94 %, like a sheet settling.
        let target = window.frame
        let small = target.insetBy(dx: target.width * 0.03, dy: target.height * 0.03)
        window.alphaValue = 0
        window.setFrame(small, display: false)
        window.makeKeyAndOrderFront(nil)
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.32
            ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.9, 0.25, 1)
            window.animator().alphaValue = 1
            window.animator().setFrame(target, display: true)
        }
        window.makeFirstResponder(root.screen)
    }

    func bringForward(restart: Bool) {
        NSApp.activate(ignoringOtherApps: true)
        if window.isMiniaturized { window.deminiaturize(nil) }
        window.makeKeyAndOrderFront(nil)
        if restart, case .stopped = model.state.phase { self.restart() }
    }

    private func restart() {
        root.screen.renderer?.clear()
        startPreferred()
    }

    // MARK: Pro mirroring

    /// Pro mirroring when this phone is set up for it (and it isn't known to be unreachable), else normal.
    private func startPreferred() {
        if let r = ProMirrorStore.record(peer.id), r.enabled {
            let known = ADBDiscovery.shared.warm && !ProMirrorHub.isReachable(peer.id)
            if known && PhoneMirrorHub.canMirror(peer) {
                startNormal(hint: ProMirrorError.wirelessDebuggingOff.message(phone: peer.name) + " Using normal mirroring.")
            } else {
                startPro()
            }
        } else {
            startNormal(hint: nil)
        }
    }

    private func startPro() {
        let p: ProMirrorSession
        if let pro { p = pro } else {
            p = ProMirrorSession(peerId: peer.id, name: peer.name)
            let renderer = root.screen.renderer
            p.onFrame = { @Sendable pb in renderer?.enqueue(pb) }
            p.onState = { @Sendable [weak self] s in
                DispatchQueue.main.async { MainActor.assumeIsolated { if self?.usingPro == true { self?.apply(s) } } }
            }
            p.onFailure = { @Sendable [weak self] e, streamed in
                DispatchQueue.main.async { MainActor.assumeIsolated { self?.proFailed(e, streamed: streamed) } }
            }
            pro = p
        }
        if !usingPro { session.stop() }
        usingPro = true
        root.screen.outlet = { [weak p] msg in p?.input(msg) }
        root.bar.setName("\(peer.name) · Pro")
        p.begin()
    }

    private func startNormal(hint: String?) {
        if usingPro { pro?.end() }
        usingPro = false
        root.screen.outlet = nil
        root.bar.setName(peer.name)
        session.start()
        if let hint { model.flash(hint, seconds: 6) }
    }

    /// Pro couldn't connect: fall back to normal mirroring when the phone simply isn't reachable over ADB.
    private func proFailed(_ e: ProMirrorError, streamed: Bool) {
        guard usingPro, !closing else { return }
        if !streamed, e.canFallBack, PhoneMirrorHub.canMirror(peer) {
            root.screen.renderer?.clear()
            startNormal(hint: e.message(phone: peer.name) + " Using normal mirroring.")
        }
    }

    private func optionsMenu() -> NSMenu {
        let m = NSMenu()
        let setUp = ProMirrorHub.isSetUp(peer.id)
        if usingPro {
            let off = ProMirrorStore.record(peer.id)?.screenOff ?? false
            let screen = MenuAction("Keep Phone Screen Off") { [weak self] in self?.pro?.setScreenOff(!off) }
            screen.state = off ? .on : .off
            m.addItem(screen)
            m.addItem(MenuAction("Rotate Phone") { [weak self] in self?.pro?.rotate() })
            if PhoneMirrorHub.canMirror(peer) {
                m.addItem(MenuAction("Use Normal Mirroring") { [weak self] in
                    self?.root.screen.renderer?.clear()
                    self?.startNormal(hint: nil)
                })
            }
            m.addItem(.separator())
        } else if setUp {
            let item = MenuAction("Use Pro Mirroring") { [weak self] in
                self?.root.screen.renderer?.clear()
                self?.startPro()
            }
            m.addItem(item)
            if ProMirrorHub.phoneHasHelper(peer.id) {
                m.addItem(MenuAction("Open Wireless Debugging on Phone") { [weak self] in
                    guard let self else { return }
                    ProMirrorHub.shared.openWirelessDebugging(self.peer.id)
                    self.model.flash("Turn on Wireless debugging on \(self.peer.name), then choose Use Pro Mirroring.", seconds: 6)
                })
            }
            m.addItem(.separator())
        }
        let peerId = peer.id, name = peer.name
        m.addItem(MenuAction(setUp ? "Pro Mirroring Settings…" : "Set Up Pro Mirroring…") {
            ProMirrorSetupWindow.shared.show(peerId: peerId, name: name)
        })
        return m
    }

    private func apply(_ s: PhoneMirrorState) {
        let old = model.state
        model.state = s
        root.stateChanged()
        if s.displayW > 0, s.displayH > 0 {
            let a = CGFloat(s.displayW) / CGFloat(s.displayH)
            if abs(a - aspect) > 0.002 { reshape(to: a) }
        }
        if s.corner != old.corner || s.displayW != old.displayW { root.layoutScreen() }
        if s.locked && !old.locked { root.screen.releaseAll() }
    }

    /// New aspect (first `mirror_info`, rotation): keep the picture about as large, keep the centre, stay on screen.
    private func reshape(to a: CGFloat) {
        let oldPhone = NSSize(width: window.frame.width, height: window.frame.height - Self.barHeight - Self.gap)
        let rotated = (a > 1) != (aspect > 1)
        aspect = a
        var w: CGFloat, h: CGFloat
        if rotated { w = oldPhone.height; h = w / a } else { h = oldPhone.height; w = h * a }
        let vis = window.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .zero
        let maxH = vis.height - Self.barHeight - Self.gap - 20, maxW = vis.width - 40
        if h > maxH { h = maxH; w = h * a }
        if w > maxW { w = maxW; h = w / a }
        let size = NSSize(width: w.rounded(), height: (h + Self.barHeight + Self.gap).rounded())
        var f = NSRect(x: window.frame.midX - size.width / 2, y: window.frame.midY - size.height / 2, width: size.width, height: size.height)
        if !vis.isEmpty {
            f.origin.x = min(max(f.origin.x, vis.minX), vis.maxX - f.width)
            f.origin.y = min(max(f.origin.y, vis.minY), vis.maxY - f.height)
        }
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.28
            ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.9, 0.25, 1)
            window.animator().setFrame(f, display: true)
        }
    }

    private func command(_ cmd: String) {
        switch cmd {
        case "close": window.performClose(nil)
        case "minimize": window.miniaturize(nil)
        case "bigger": scale(1.12)
        case "smaller": scale(1 / 1.12)
        case "default":
            let s = Self.initialPhoneSize(aspect: aspect)
            setPhoneSize(aspect > 1 ? NSSize(width: s.height, height: s.height / aspect) : s)
        default: break
        }
    }

    private func scale(_ k: CGFloat) {
        let phone = NSSize(width: window.frame.width * k, height: (window.frame.height - Self.barHeight - Self.gap) * k)
        setPhoneSize(phone)
    }

    private func setPhoneSize(_ phone: NSSize) {
        let vis = window.screen?.visibleFrame ?? .zero
        var w = max(160, phone.width), h = w / aspect
        if !vis.isEmpty, h + Self.barHeight + Self.gap > vis.height { h = vis.height - Self.barHeight - Self.gap; w = h * aspect }
        let size = NSSize(width: w.rounded(), height: (h + Self.barHeight + Self.gap).rounded())
        let f = NSRect(x: window.frame.midX - size.width / 2, y: window.frame.maxY - size.height, width: size.width, height: size.height)
        window.animator().setFrame(f, display: true)
    }

    // MARK: NSWindowDelegate

    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        // Keep the phone's aspect: the bar strip above it has a fixed height.
        let extra = Self.barHeight + Self.gap
        let w = max(sender.minSize.width, frameSize.width)
        return NSSize(width: w.rounded(), height: (w / aspect + extra).rounded())
    }

    func windowDidResize(_ notification: Notification) { window.invalidateShadow() }

    func windowDidBecomeKey(_ notification: Notification) {
        // While this window has focus it is the phone's controller: the edge crossing must not grab the cursor.
        ControlGate.shared.mirrorFocus = peer.id
        root.screen.window?.makeFirstResponder(root.screen)
    }

    func windowDidResignKey(_ notification: Notification) {
        if ControlGate.shared.mirrorFocus == peer.id { ControlGate.shared.mirrorFocus = nil }
        root.screen.releaseAll()
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        guard !closing else { return true }
        closing = true
        root.screen.releaseAll()
        session.stop()
        pro?.end()
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.18
            window.animator().alphaValue = 0
        }, completionHandler: { [weak self] in
            MainActor.assumeIsolated { self?.window.close() }
        })
        return false
    }

    func windowWillClose(_ notification: Notification) {
        if ControlGate.shared.mirrorFocus == peer.id { ControlGate.shared.mirrorFocus = nil }
        session.stop()
        pro?.end()
        PhoneMirrorHub.shared.unregister(session)
        root.screen.renderer?.clear()
        onClose?()
    }
}

// MARK: - Root view: hover bar + phone

final class MirrorRootView: NSView {
    let bar = MirrorBarView()
    let screen = MirrorScreenView()
    private let overlay: PassthroughHostingView<MirrorOverlay>
    private let model: PhoneMirrorModel
    var onRetry: (() -> Void)?
    private var tracking: NSTrackingArea?
    private var hovering = false
    private var hideWork: DispatchWorkItem?

    init(frame: NSRect, model: PhoneMirrorModel) {
        self.model = model
        overlay = PassthroughHostingView(rootView: MirrorOverlay(model: model, retry: {}))
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = .clear
        addSubview(screen)
        screen.addSubview(overlay)
        addSubview(bar)
        overlay.rootView = MirrorOverlay(model: model, retry: { [weak self] in self?.onRetry?() })
        overlay.isInteractive = { [weak model] in model?.interactive ?? false }
        bar.setName(model.name)
        bar.alphaValue = 1
        layoutScreen()
    }

    required init?(coder: NSCoder) { fatalError() }

    override var isFlipped: Bool { true }

    override func layout() {
        super.layout()
        layoutScreen()
    }

    func layoutScreen() {
        let top = PhoneMirrorWindowController.barHeight + PhoneMirrorWindowController.gap
        bar.frame = NSRect(x: 0, y: 0, width: bounds.width, height: PhoneMirrorWindowController.barHeight)
        screen.frame = NSRect(x: 0, y: top, width: bounds.width, height: max(0, bounds.height - top))
        overlay.frame = screen.bounds
        let s = model.state
        let short = min(screen.bounds.width, screen.bounds.height)
        let dispShort = CGFloat(min(s.displayW, s.displayH))
        let r = s.corner > 0 && dispShort > 0 ? CGFloat(s.corner) * short / dispShort : short * 0.1
        screen.setCorner(min(r, short / 4))
        window?.invalidateShadow()
    }

    func stateChanged() {
        screen.stateChanged()
        updateBar(animated: true)
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let t = tracking { removeTrackingArea(t) }
        let t = NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(t)
        tracking = t
    }

    override func mouseEntered(with event: NSEvent) { hovering = true; updateBar(animated: true) }
    override func mouseExited(with event: NSEvent) { hovering = false; updateBar(animated: true) }

    /// The bar shows while the pointer is over the window, and always while there is no picture.
    private func updateBar(animated: Bool) {
        let show = hovering || model.state.phase != .streaming
        hideWork?.cancel()
        let apply = { [weak self] in
            guard let self else { return }
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = animated ? 0.2 : 0
                self.bar.animator().alphaValue = show ? 1 : 0
            }
        }
        if show { apply() } else {
            let w = DispatchWorkItem(block: apply)
            hideWork = w
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7, execute: w)
        }
    }
}

/// SwiftUI overlay that lets every click through to the phone unless it shows a button.
final class PassthroughHostingView<Content: View>: NSHostingView<Content> {
    var isInteractive: () -> Bool = { false }
    override func hitTest(_ point: NSPoint) -> NSView? { isInteractive() ? super.hitTest(point) : nil }
}

// MARK: - Bar

/// Capsule above the phone: close / minimise on the left, the phone's name, Back / Home / Recents on the right.
/// Dragging the capsule moves the window.
final class MirrorBarView: NSView {
    var onClose: (() -> Void)?
    var onMinimize: (() -> Void)?
    var onAction: ((String) -> Void)?
    /// Options menu (Pro mirroring), shown from the "…" button.
    var menuProvider: (() -> NSMenu?)?
    private let capsule = NSVisualEffectView()
    private let title = NSTextField(labelWithString: "")
    private var buttons: [NSButton] = []

    override init(frame: NSRect) {
        super.init(frame: frame)
        capsule.material = .hudWindow
        capsule.blendingMode = .behindWindow
        capsule.state = .active
        capsule.wantsLayer = true
        capsule.layer?.cornerRadius = 15
        capsule.layer?.cornerCurve = .continuous
        capsule.layer?.masksToBounds = true
        capsule.layer?.borderWidth = 0.5
        capsule.layer?.borderColor = NSColor.white.withAlphaComponent(0.12).cgColor
        addSubview(capsule)

        title.font = .systemFont(ofSize: 12, weight: .medium)
        title.textColor = .secondaryLabelColor
        title.alignment = .center
        title.lineBreakMode = .byTruncatingTail
        capsule.addSubview(title)

        let specs: [(String, String, Selector)] = [
            ("xmark", "Close", #selector(closeTapped)),
            ("minus", "Minimise", #selector(minimizeTapped)),
            ("chevron.backward", "Back", #selector(backTapped)),
            ("circle", "Home", #selector(homeTapped)),
            ("square.on.square", "Recent apps", #selector(recentsTapped)),
            ("ellipsis", "Options", #selector(optionsTapped)),
        ]
        for (symbol, label, sel) in specs {
            let b = NSButton(image: NSImage(systemSymbolName: symbol, accessibilityDescription: label) ?? NSImage(),
                             target: self, action: sel)
            b.isBordered = false
            b.bezelStyle = .regularSquare
            b.symbolConfiguration = .init(pointSize: 11, weight: .semibold)
            b.contentTintColor = .secondaryLabelColor
            b.toolTip = label
            b.setAccessibilityLabel(label)
            capsule.addSubview(b)
            buttons.append(b)
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    override var isFlipped: Bool { true }

    func setName(_ s: String) { title.stringValue = s }

    override func layout() {
        super.layout()
        let h: CGFloat = 30
        let w = min(bounds.width, 360)
        capsule.frame = NSRect(x: (bounds.width - w) / 2, y: (bounds.height - h) / 2, width: w, height: h)
        let side: CGFloat = 26
        let y = (h - side) / 2
        buttons[0].frame = NSRect(x: 6, y: y, width: side, height: side)
        buttons[1].frame = NSRect(x: 6 + side, y: y, width: side, height: side)
        buttons[5].frame = NSRect(x: 6 + 2 * side, y: y, width: side, height: side)
        for i in 2..<5 {
            buttons[i].frame = NSRect(x: w - 6 - CGFloat(5 - i) * side, y: y, width: side, height: side)
        }
        let left = 6 + 3 * side + 4, right = w - 6 - 3 * side - 4
        title.frame = NSRect(x: left, y: (h - 16) / 2, width: max(0, right - left), height: 16)
        title.isHidden = right - left < 40
    }

    override func mouseDown(with event: NSEvent) { window?.performDrag(with: event) }

    @objc private func closeTapped() { onClose?() }
    @objc private func minimizeTapped() { onMinimize?() }
    @objc private func backTapped() { onAction?("back") }
    @objc private func homeTapped() { onAction?("home") }
    @objc private func recentsTapped() { onAction?("recents") }
    @objc private func optionsTapped() {
        guard let menu = menuProvider?(), let b = buttons.last else { return }
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: b.isFlipped ? b.bounds.maxY + 4 : -4), in: b)
    }
}

/// NSMenuItem running a closure.
final class MenuAction: NSMenuItem {
    private let run: () -> Void
    init(_ title: String, _ run: @escaping () -> Void) {
        self.run = run
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
    }
    required init(coder: NSCoder) { fatalError() }
    @objc private func fire() { run() }
}

// MARK: - Overlay

struct MirrorOverlay: View {
    @ObservedObject var model: PhoneMirrorModel
    var retry: () -> Void

    var body: some View {
        ZStack {
            center
            VStack {
                Spacer()
                if let hint = currentHint {
                    Text(hint)
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(.white.opacity(0.92))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Capsule().fill(Color.black.opacity(0.62)))
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.1)))
                        .padding(.horizontal, 14).padding(.bottom, 18)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.86), value: model.state)
        .animation(.spring(response: 0.35, dampingFraction: 0.86), value: model.hint)
    }

    private var currentHint: String? {
        if let h = model.hint { return h }
        if model.state.phase == .streaming, model.state.hasPicture, !model.state.control, !model.state.locked {
            return "Turn on AirBridge Universal Control on the phone to control it"
        }
        return nil
    }

    @ViewBuilder private var center: some View {
        switch model.state.phase {
        case .connecting:
            card { ProgressView().controlSize(.small).tint(.white); line("Connecting to \(model.name)…", sub: nil) }
        case .waitingForConsent:
            card {
                Image(systemName: "iphone.gen3").font(.system(size: 30, weight: .light)).foregroundStyle(.white.opacity(0.85))
                line("Allow on your phone", sub: "Tap Allow on \(model.name) to show its screen here.")
            }
        case .streaming:
            if model.state.locked {
                card(dim: 0.55) {
                    Image(systemName: "lock.fill").font(.system(size: 24)).foregroundStyle(.white.opacity(0.9))
                    line("Phone is locked", sub: "Unlock \(model.name) to continue.")
                }
            } else if !model.state.hasPicture {
                card { ProgressView().controlSize(.small).tint(.white); line("Starting…", sub: nil) }
            }
        case .stopped(let why):
            card {
                Image(systemName: why == "Phone is locked" ? "lock.fill" : "rectangle.on.rectangle.slash")
                    .font(.system(size: 24)).foregroundStyle(.white.opacity(0.8))
                line(why, sub: nil)
                Button(action: retry) {
                    Text("Try Again")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color(white: 0.1))
                        .padding(.horizontal, 16).padding(.vertical, 7)
                        .background(Capsule().fill(Color.white.opacity(0.92)))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
    }

    private func line(_ title: String, sub: String?) -> some View {
        VStack(spacing: 4) {
            Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
            if let sub { Text(sub).font(.system(size: 12)).foregroundStyle(.white.opacity(0.7)) }
        }
        .multilineTextAlignment(.center)
    }

    private func card<C: View>(dim: Double = 1, @ViewBuilder _ content: () -> C) -> some View {
        ZStack {
            Rectangle().fill(Color(white: 0.1).opacity(dim))
            VStack(spacing: 12) { content() }.padding(24)
        }
        .transition(.opacity)
    }
}
