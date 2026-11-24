import AppKit

/// A two-finger horizontal swipe on the trackpad, the way a notification banner is flicked away. Feed it scroll events;
/// it says how far the content should follow the fingers and, when the fingers lift, whether that was far enough.
/// Vertical swipes, mouse wheels and the momentum after the fingers lift are left alone.
@MainActor final class SwipeAway {
    enum Result: Equatable {
        case ignored
        /// The fingers are on the trackpad and the content follows them by this much (points, + = right).
        case moved(CGFloat)
        /// Let go far enough: send it away in this direction.
        case away(CGFloat)
        /// Let go too early: spring back.
        case back
    }

    /// How far (points) a swipe has to go to send something away.
    static let threshold: CGFloat = 70

    private var tracking = false
    private var horizontal: Bool?
    private var travel = CGSize.zero
    private(set) var dx: CGFloat = 0

    func handle(_ e: NSEvent) -> Result {
        guard e.type == .scrollWheel, e.momentumPhase.isEmpty, !e.phase.isEmpty else { return .ignored }
        if e.phase.contains(.began) {
            tracking = true
            horizontal = nil
            travel = .zero
            dx = 0
            return .ignored
        }
        guard tracking else { return .ignored }
        if e.phase.contains(.ended) || e.phase.contains(.cancelled) {
            tracking = false
            guard horizontal == true else { return .ignored }
            return abs(dx) >= Self.threshold && !e.phase.contains(.cancelled) ? .away(dx) : .back
        }
        // Which way the fingers moved: with natural scrolling the content follows them already, otherwise it is reversed.
        let fx = e.isDirectionInvertedFromDevice ? e.scrollingDeltaX : -e.scrollingDeltaX
        if horizontal == nil {
            travel.width += abs(e.scrollingDeltaX)
            travel.height += abs(e.scrollingDeltaY)
            guard travel.width + travel.height >= 6 else { return .ignored }
            horizontal = travel.width > travel.height * 1.2
        }
        guard horizontal == true else { return .ignored }
        dx += fx
        return .moved(dx)
    }
}

/// Makes a small borderless panel (the call pill, the incoming-call banner) follow a two-finger swipe and fly off when it
/// goes far enough, and gives it a menu on right-click, Control-click or press-and-hold (`showMenu`).
@MainActor final class PanelSwipe {
    private weak var panel: NSWindow?
    private var monitor: Any?
    private let tracker = SwipeAway()
    private var home: NSRect?
    /// Swiped away (the panel has already flown off and is ordered out).
    var onAway: (() -> Void)?
    /// The menu for right-click / Control-click / press-and-hold; nil = none.
    var menu: (() -> NSMenu?)?

    init(panel: NSWindow) {
        self.panel = panel
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.scrollWheel, .rightMouseDown, .leftMouseDown]) { [weak self] e in
            self?.handle(e) ?? e
        }
    }

    /// Stops listening (call when the panel goes away for good).
    func stop() {
        if let m = monitor { NSEvent.removeMonitor(m); monitor = nil }
    }

    func showMenu() {
        guard let m = menu?() else { return }
        m.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
    }

    #if DEBUG
    /// The scroll events of a two-finger swipe of [dx] points, through the same handling (window routing skipped).
    func debugSwipe(dx: Int32) {
        func ev(_ d: Int32, _ phase: Int64) -> NSEvent? {
            guard let c = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: 0, wheel2: d, wheel3: 0) else { return nil }
            c.setIntegerValueField(.scrollWheelEventScrollPhase, value: phase)
            c.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
            return NSEvent(cgEvent: c)
        }
        var steps = [ev(0, 1)].compactMap { $0 } + (0..<12).compactMap { _ in ev(dx / 12, 2) }
        if let end = ev(0, 4) { steps.append(end) }
        for (i, e) in steps.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.012) { [weak self] in
                MainActor.assumeIsolated { _ = self?.handle(e, force: true) }
            }
        }
    }
    #endif

    private func handle(_ e: NSEvent, force: Bool = false) -> NSEvent? {
        guard let p = panel, force || e.window === p, p.isVisible else { return e }
        switch e.type {
        case .rightMouseDown:
            guard menu != nil else { return e }
            showMenu()
            return nil
        case .leftMouseDown where e.modifierFlags.contains(.control):
            guard menu != nil else { return e }
            showMenu()
            return nil
        case .scrollWheel:
            switch tracker.handle(e) {
            case .ignored:
                return e
            case .moved(let dx):
                let base = home ?? p.frame
                home = base
                p.setFrameOrigin(NSPoint(x: base.minX + dx, y: base.minY))
                p.alphaValue = max(0.25, 1 - abs(dx) / (base.width * 1.4))
                return nil
            case .away(let dx):
                let base = home ?? p.frame
                home = nil
                NSAnimationContext.runAnimationGroup({ ctx in
                    ctx.duration = 0.2
                    ctx.timingFunction = CAMediaTimingFunction(name: .easeIn)
                    p.animator().setFrame(base.offsetBy(dx: (dx > 0 ? 1 : -1) * (base.width + 60), dy: 0), display: true)
                    p.animator().alphaValue = 0
                }, completionHandler: { [weak self] in
                    MainActor.assumeIsolated {
                        p.orderOut(nil)
                        p.setFrame(base, display: false)
                        p.alphaValue = 1
                        self?.onAway?()
                    }
                })
                return nil
            case .back:
                guard let base = home else { return nil }
                home = nil
                NSAnimationContext.runAnimationGroup { ctx in
                    ctx.duration = 0.25
                    ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 1.2, 0.4, 1)
                    p.animator().setFrame(base, display: true)
                    p.animator().alphaValue = 1
                }
                return nil
            }
        default:
            return e
        }
    }
}

/// A menu item that runs a closure.
@MainActor final class ClosureMenuItem: NSMenuItem {
    private let handler: () -> Void

    init(_ title: String, checked: Bool = false, enabled: Bool = true, _ handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
        state = checked ? .on : .off
        isEnabled = enabled
    }

    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("not used") }

    @objc private func fire() { handler() }
}
