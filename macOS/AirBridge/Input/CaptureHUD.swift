import AppKit

/// Small pill shown at the top of the screen while the Mac's cursor is on another device, so there is
/// always a visible way back. Non-activating, click-through, on all Spaces. Main thread only.
@MainActor
final class CaptureHUD {
    static let shared = CaptureHUD()

    private var panel: NSPanel?
    private let label = NSTextField(labelWithString: "")

    /// `anchor` is in global CoreGraphics coordinates (top-left origin); the pill goes on that display.
    func show(deviceName: String, anchor: CGPoint) {
        let p = ensurePanel()
        label.stringValue = "Controlling \(deviceName)  ·  move back through the edge, or press Esc 3×"
        label.sizeToFit()
        let size = NSSize(width: label.frame.width + 32, height: label.frame.height + 16)
        let screen = NSScreen.screens.first { s in
            let f = s.frame, h = NSScreen.screens.first?.frame.height ?? f.height
            let cg = CGRect(x: f.minX, y: h - f.maxY, width: f.width, height: f.height)
            return cg.contains(anchor)
        } ?? NSScreen.main ?? NSScreen.screens[0]
        let f = screen.frame
        let origin = NSPoint(x: f.midX - size.width / 2, y: f.maxY - size.height - 48)
        p.setFrame(NSRect(origin: origin, size: size), display: false)
        label.frame = NSRect(x: 16, y: 8, width: label.frame.width, height: label.frame.height)
        p.alphaValue = 1
        p.orderFrontRegardless()
    }

    func hide() {
        panel?.orderOut(nil)
    }

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let p = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = true
        p.ignoresMouseEvents = true
        p.level = .statusBar
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        p.isReleasedWhenClosed = false
        let bg = NSVisualEffectView()
        bg.material = .hudWindow
        bg.blendingMode = .behindWindow
        bg.state = .active
        bg.wantsLayer = true
        bg.layer?.cornerRadius = 14
        bg.layer?.masksToBounds = true
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.textColor = .white
        bg.addSubview(label)
        p.contentView = bg
        panel = p
        return p
    }
}
