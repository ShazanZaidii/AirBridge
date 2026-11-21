import AppKit

/// Thin translucent bar along the part of a screen edge that leads to a peer. It fades in while the
/// push-through accumulator charges and flashes once on capture (protocol.md §8.2 Capture intent).
/// Non-activating, click-through, on all Spaces. Main thread only.
@MainActor
final class EdgeIndicator {
    static let shared = EdgeIndicator()

    nonisolated static let thickness: CGFloat = 4

    private var panel: NSPanel?
    private var flashSerial = 0

    private var reduceMotion: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

    /// `rect` is in global CoreGraphics coordinates (top-left origin); `level` 0…1.
    func update(rect: CGRect, level: Double) {
        let p = ensurePanel()
        flashSerial += 1
        p.setFrame(Self.cocoaRect(rect), display: false)
        applyColor(p)
        setAlpha(p, CGFloat(0.12 + 0.68 * max(0, min(1, level))))
        p.orderFrontRegardless()
    }

    /// Sets the alpha now and cancels any fade still running: a fade-out that was started a moment ago would otherwise
    /// finish after this and leave the bar invisible while it is on screen (the glow "going to sleep").
    private func setAlpha(_ p: NSPanel, _ a: CGFloat) {
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0
            ctx.allowsImplicitAnimation = false
            p.animator().alphaValue = a
        })
        p.alphaValue = a
    }

    /// The glow colour the user picked (Displays window), read on every use so a change shows at once.
    private func applyColor(_ p: NSPanel) {
        p.contentView?.layer?.backgroundColor = CaptureSettings.edgeColor().cgColor
    }

    func flash(rect: CGRect) {
        let p = ensurePanel()
        flashSerial += 1
        let serial = flashSerial
        p.setFrame(Self.cocoaRect(rect), display: false)
        applyColor(p)
        setAlpha(p, 1)
        p.orderFrontRegardless()
        if reduceMotion {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                guard let self, self.flashSerial == serial else { return }
                self.panel?.orderOut(nil)
            }
            return
        }
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.35
            ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
            p.animator().alphaValue = 0
        }, completionHandler: { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.flashSerial == serial else { return }
                self.panel?.orderOut(nil)
            }
        })
    }

    func hide() {
        flashSerial += 1
        guard let p = panel, p.isVisible else { return }
        if reduceMotion {
            p.orderOut(nil)
            return
        }
        let serial = flashSerial
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.15
            p.animator().alphaValue = 0
        }, completionHandler: { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.flashSerial == serial else { return }
                self.panel?.orderOut(nil)
            }
        })
    }

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 4, height: 4),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        p.isFloatingPanel = true
        p.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.overlayWindow)))
        p.backgroundColor = .clear
        p.isOpaque = false
        p.hasShadow = false
        p.ignoresMouseEvents = true
        p.hidesOnDeactivate = false
        p.isReleasedWhenClosed = false
        p.becomesKeyOnlyIfNeeded = true
        p.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        p.animationBehavior = .none
        let v = NSView()
        v.wantsLayer = true
        v.layer?.backgroundColor = CaptureSettings.edgeColor().cgColor
        v.layer?.cornerRadius = Self.thickness / 2
        v.autoresizingMask = [.width, .height]
        p.contentView = v
        panel = p
        return p
    }

    /// CG global (top-left origin of the main display) → Cocoa global (bottom-left of the primary screen).
    private static func cocoaRect(_ r: CGRect) -> NSRect {
        let primaryHeight = NSScreen.screens.first?.frame.height ?? CGDisplayBounds(CGMainDisplayID()).height
        return NSRect(x: r.minX, y: primaryHeight - r.maxY, width: r.width, height: r.height)
    }
}
