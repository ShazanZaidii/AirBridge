import AppKit
import SwiftUI

/// The incoming-call banner: a borderless, non-activating glass panel at the top right of the screen with the
/// pointer (as notification banners), above other windows, on every Space and over full-screen apps. It never takes
/// focus from the app the user is typing in, and has no close button: it stays until the call is answered or over.
@MainActor final class CallBannerPanel: NSPanel {
    private let host: NSHostingView<IncomingCallBanner>

    init(ui: CallUI) {
        host = NSHostingView(rootView: IncomingCallBanner(ui: ui))
        super.init(contentRect: NSRect(x: 0, y: 0, width: IncomingCallBanner.width, height: 70),
                   styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        level = .statusBar
        isFloatingPanel = true
        hidesOnDeactivate = false
        becomesKeyOnlyIfNeeded = true
        isReleasedWhenClosed = false
        animationBehavior = .none
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle, .stationary]
        title = "Incoming Call"
        // A little room around the glass (its edge highlight and the pointer's hover state).
        let pad: CGFloat = 8
        let container = NSView()
        host.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(host)
        NSLayoutConstraint.activate([
            host.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: pad),
            host.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -pad),
            host.topAnchor.constraint(equalTo: container.topAnchor, constant: pad),
            host.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -pad),
        ])
        contentView = container
        let size = host.fittingSize
        setContentSize(NSSize(width: size.width + 2 * pad, height: size.height + 2 * pad))
    }

    override var canBecomeMain: Bool { false }

    /// Where it sits: below the menu bar, at the right edge of the screen the user is on.
    func targetFrame(index: Int = 0) -> NSRect {
        let screen = NSScreen.screens.first { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
        let v = screen.visibleFrame
        let s = frame.size
        // The glass is inset by 8 pt inside the panel: the visible banner ends up 10 pt from the edges, like notifications.
        return NSRect(x: v.maxX - s.width - 2, y: v.maxY - s.height - 2 - CGFloat(index) * (s.height - 6), width: s.width, height: s.height)
    }

    /// Slides in from the right edge with a soft spring-like curve.
    func present(index: Int, animated: Bool) {
        let target = targetFrame(index: index)
        guard animated else { setFrame(target, display: true); alphaValue = 1; orderFrontRegardless(); return }
        setFrame(target.offsetBy(dx: target.width * 0.6, dy: 0), display: false)
        alphaValue = 0
        orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.42
            ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.16, 1.0, 0.3, 1.0)
            animator().setFrame(target, display: true)
            animator().alphaValue = 1
        }
        NSAccessibility.post(element: self, notification: .windowCreated)
    }

    /// Slides back out to the right and fades.
    func dismiss(animated: Bool, completion: (() -> Void)? = nil) {
        guard animated, isVisible else { orderOut(nil); completion?(); return }
        let f = frame
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.28
            ctx.timingFunction = CAMediaTimingFunction(name: .easeIn)
            animator().setFrame(f.offsetBy(dx: f.width * 0.5, dy: 0), display: true)
            animator().alphaValue = 0
        }, completionHandler: {
            MainActor.assumeIsolated {
                self.orderOut(nil)
                completion?()
            }
        })
    }
}

/// The call in progress: a small, normal window (draggable, in ⌘Tab) that floats above other windows and on every
/// Space for the whole call. Closing it (⌘W, the close button, ⌘M) never ends or hides the call: it collapses into
/// the call pill (`onCollapse`), which brings it back.
@MainActor final class InCallWindow: NSWindow, NSWindowDelegate {
    var onCollapse: (() -> Void)?

    init(ui: CallUI) {
        let host = NSHostingView(rootView: InCallView(ui: ui))
        let size = host.fittingSize
        super.init(contentRect: NSRect(origin: .zero, size: size),
                   styleMask: [.titled, .closable, .fullSizeContentView],
                   backing: .buffered, defer: false)
        titlebarAppearsTransparent = true
        titleVisibility = .hidden
        title = "Call with \(ui.title)"
        isMovableByWindowBackground = true
        isReleasedWhenClosed = false
        level = .floating
        hidesOnDeactivate = false
        standardWindowButton(.zoomButton)?.isHidden = true
        standardWindowButton(.miniaturizeButton)?.isHidden = true
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        tabbingMode = .disallowed
        // The window's own glass: the standard material for small utility windows on macOS 26.
        let glass = NSGlassEffectView()
        glass.cornerRadius = 0
        glass.contentView = host
        contentView = glass
        setContentSize(size)
        delegate = self
    }

    /// Close = collapse into the pill; the call goes on.
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        collapse()
        return false
    }

    override func performMiniaturize(_ sender: Any?) { collapse() }
    override func miniaturize(_ sender: Any?) { collapse() }

    private func collapse() {
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.18
            animator().alphaValue = 0
        }, completionHandler: {
            MainActor.assumeIsolated {
                self.orderOut(nil)
                self.alphaValue = 1
                self.onCollapse?()
            }
        })
    }

    /// Top right, under the menu bar.
    func placeTopRight() {
        let screen = NSScreen.screens.first { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
        let v = screen.visibleFrame
        setFrameOrigin(NSPoint(x: v.maxX - frame.width - 16, y: v.maxY - frame.height - 16))
    }

    /// Shows the window without pulling focus from the app in front (clicking it activates AirBridge as usual).
    func showQuietly() {
        if !isVisible {
            alphaValue = 0
            orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.22
                animator().alphaValue = 1
            }
        } else {
            orderFrontRegardless()
        }
    }

    func showAndFocus() {
        NSApp.activate(ignoringOtherApps: true)
        makeKeyAndOrderFront(nil)
    }

    /// The call is over: really close (no pill).
    func fadeOutAndClose() {
        delegate = nil
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.3
            animator().alphaValue = 0
        }, completionHandler: {
            MainActor.assumeIsolated {
                self.close()
                self.alphaValue = 1
            }
        })
    }
}

/// The collapsed call: a small glass pill docked at the top right, above other windows and on every Space, for as
/// long as the call lasts. A panel (not in ⌘Tab), never takes focus.
@MainActor final class CallPillPanel: NSPanel {
    init(ui: CallUI) {
        let host = NSHostingView(rootView: CallPillView(ui: ui))
        let size = host.fittingSize
        super.init(contentRect: NSRect(origin: .zero, size: size), styleMask: [.borderless, .nonactivatingPanel],
                   backing: .buffered, defer: false)
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        level = .floating
        isFloatingPanel = true
        hidesOnDeactivate = false
        becomesKeyOnlyIfNeeded = true
        isReleasedWhenClosed = false
        isMovableByWindowBackground = true
        animationBehavior = .none
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle, .stationary]
        title = "Call with \(ui.title)"
        contentView = host
        setContentSize(size)
    }

    override var canBecomeMain: Bool { false }

    func present(near window: NSWindow?) {
        let screen = window?.screen ?? NSScreen.screens.first { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
        let v = screen.visibleFrame
        let target = NSRect(x: v.maxX - frame.width - 12, y: v.maxY - frame.height - 10, width: frame.width, height: frame.height)
        setFrame(target.offsetBy(dx: 0, dy: 10), display: false)
        alphaValue = 0
        orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.3
            ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.16, 1.0, 0.3, 1.0)
            animator().setFrame(target, display: true)
            animator().alphaValue = 1
        }
    }

    func dismiss() {
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.18
            animator().alphaValue = 0
        }, completionHandler: {
            MainActor.assumeIsolated { self.orderOut(nil); self.alphaValue = 1 }
        })
    }
}
