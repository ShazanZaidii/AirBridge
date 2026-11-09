import AppKit
import UniformTypeIdentifiers

/// Target side of a carried drag (protocol.md §8.8): a small tilted card with the file's icon, its name (or
/// "3 items") and size that follows the injected cursor, and after a drop a short "Receiving … from …" line.
/// Click-through, non-activating, on all Spaces. Main thread only.
@MainActor
final class DragGhost {
    static let shared = DragGhost()

    private static let cardSize = NSSize(width: 210, height: 58)
    private static let margin: CGFloat = 14
    private static let offset = CGPoint(x: 10, y: 14)        // card corner relative to the cursor tip

    private var panel: NSPanel?
    private var card: CardView?
    private var notePanel: NSPanel?
    private var noteLabel: NSTextField?
    private var noteSerial = 0
    private var shownSerial = 0
    private(set) var isShown = false

    private var reduceMotion: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

    func show(_ info: DragGhostState.Info, at cursor: CGPoint) {
        let p = ensurePanel()
        card?.bind(info)
        shownSerial += 1
        place(p, cursor)
        let wasShown = isShown && p.isVisible
        isShown = true
        guard let layer = p.contentView?.layer else { p.orderFrontRegardless(); return }
        if !p.isVisible { p.orderFrontRegardless() }
        if wasShown { return }
        layer.removeAllAnimations()
        p.alphaValue = 1
        if reduceMotion { layer.opacity = 1; return }
        let spring = CASpringAnimation(keyPath: "transform.scale")
        spring.fromValue = 0.82
        spring.toValue = 1
        spring.damping = 14
        spring.stiffness = 260
        spring.mass = 1
        spring.duration = spring.settlingDuration
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0
        fade.toValue = 1
        fade.duration = 0.16
        layer.opacity = 1
        layer.add(spring, forKey: "in.scale")
        layer.add(fade, forKey: "in.fade")
    }

    func move(to cursor: CGPoint) {
        guard isShown, let p = panel else { return }
        place(p, cursor)
    }

    /// `dropped`: shrink into the cursor; otherwise fade.
    func hide(dropped: Bool) {
        guard isShown, let p = panel else { return }
        isShown = false
        shownSerial += 1
        let s = shownSerial
        guard !reduceMotion, let layer = p.contentView?.layer else { p.orderOut(nil); return }
        CATransaction.begin()
        CATransaction.setCompletionBlock { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.shownSerial == s else { return }
                self.panel?.orderOut(nil)
                self.panel?.contentView?.layer?.removeAllAnimations()
                self.panel?.contentView?.layer?.opacity = 1
                self.panel?.contentView?.layer?.transform = CATransform3DIdentity
            }
        }
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = layer.presentation()?.opacity ?? 1
        fade.toValue = 0
        fade.duration = dropped ? 0.2 : 0.14
        fade.fillMode = .forwards
        fade.isRemovedOnCompletion = false
        layer.add(fade, forKey: "out.fade")
        if dropped {
            let shrink = CABasicAnimation(keyPath: "transform.scale")
            shrink.fromValue = 1
            shrink.toValue = 0.5
            shrink.duration = 0.2
            shrink.timingFunction = CAMediaTimingFunction(name: .easeIn)
            shrink.fillMode = .forwards
            shrink.isRemovedOnCompletion = false
            layer.add(shrink, forKey: "out.scale")
        }
        CATransaction.commit()
    }

    /// A short line under the cursor; fades after a moment.
    func note(_ text: String, at cursor: CGPoint) {
        let (p, label) = ensureNote()
        label.stringValue = text
        let size = label.fittingSize
        let w = min(max(size.width + 28, 120), 520), h: CGFloat = 32
        let screen = Self.screen(containing: cursor)
        var origin = CGPoint(x: cursor.x - w / 2, y: cursor.y + 28)       // CG coordinates
        if let s = screen {
            let b = Self.cgFrame(s)
            origin.x = max(b.minX + 12, min(b.maxX - w - 12, origin.x))
            if origin.y + h > b.maxY - 12 { origin.y = cursor.y - 28 - h }
        }
        p.setFrame(Self.cocoaRect(CGRect(origin: origin, size: CGSize(width: w, height: h))), display: true)
        noteSerial += 1
        let s = noteSerial
        p.alphaValue = reduceMotion ? 1 : 0
        p.orderFrontRegardless()
        if !reduceMotion {
            NSAnimationContext.runAnimationGroup { ctx in ctx.duration = 0.18; p.animator().alphaValue = 1 }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { [weak self] in
            guard let self, self.noteSerial == s else { return }
            if self.reduceMotion { p.orderOut(nil); return }
            NSAnimationContext.runAnimationGroup({ ctx in ctx.duration = 0.22; p.animator().alphaValue = 0 },
                                                 completionHandler: { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, self.noteSerial == s else { return }
                    self.notePanel?.orderOut(nil)
                }
            })
        }
    }

    // MARK: windows

    private func place(_ p: NSPanel, _ cursor: CGPoint) {
        let full = NSSize(width: Self.cardSize.width + 2 * Self.margin, height: Self.cardSize.height + 2 * Self.margin)
        let r = CGRect(x: cursor.x + Self.offset.x - Self.margin, y: cursor.y + Self.offset.y - Self.margin,
                       width: full.width, height: full.height)
        p.setFrame(Self.cocoaRect(r), display: false)
    }

    private static func basePanel() -> NSPanel {
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 10, height: 10),
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
        return p
    }

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let p = Self.basePanel()
        let full = NSRect(x: 0, y: 0, width: Self.cardSize.width + 2 * Self.margin, height: Self.cardSize.height + 2 * Self.margin)
        let root = NSView(frame: full)
        root.wantsLayer = true
        let c = CardView(frame: NSRect(x: Self.margin, y: Self.margin, width: Self.cardSize.width, height: Self.cardSize.height))
        root.addSubview(c)
        p.contentView = root
        // Scale/fade around the card corner nearest the cursor (top-left in Cocoa = minX, maxY).
        if let l = root.layer {
            l.anchorPoint = CGPoint(x: Self.margin / full.width, y: (full.height - Self.margin) / full.height)
            l.position = CGPoint(x: Self.margin, y: full.height - Self.margin)
        }
        panel = p
        card = c
        return p
    }

    private func ensureNote() -> (NSPanel, NSTextField) {
        if let notePanel, let noteLabel { return (notePanel, noteLabel) }
        let p = Self.basePanel()
        let v = NSView(frame: NSRect(x: 0, y: 0, width: 200, height: 32))
        v.wantsLayer = true
        v.layer?.backgroundColor = NSColor(srgbRed: 0.165, green: 0.173, blue: 0.188, alpha: 0.96).cgColor
        v.layer?.cornerRadius = 16
        v.layer?.borderWidth = 1
        v.layer?.borderColor = NSColor(white: 1, alpha: 0.12).cgColor
        let label = NSTextField(labelWithString: "")
        label.font = .systemFont(ofSize: 12.5, weight: .medium)
        label.textColor = .white
        label.lineBreakMode = .byTruncatingMiddle
        label.translatesAutoresizingMaskIntoConstraints = false
        v.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerYAnchor.constraint(equalTo: v.centerYAnchor),
            label.leadingAnchor.constraint(equalTo: v.leadingAnchor, constant: 14),
            label.trailingAnchor.constraint(equalTo: v.trailingAnchor, constant: -14),
        ])
        p.contentView = v
        notePanel = p
        noteLabel = label
        return (p, label)
    }

    private static func screen(containing p: CGPoint) -> NSScreen? {
        NSScreen.screens.first { cgFrame($0).contains(p) } ?? NSScreen.main
    }

    private static func cgFrame(_ s: NSScreen) -> CGRect {
        let primaryHeight = NSScreen.screens.first?.frame.height ?? 0
        let f = s.frame
        return CGRect(x: f.minX, y: primaryHeight - f.maxY, width: f.width, height: f.height)
    }

    private static func cocoaRect(_ r: CGRect) -> NSRect {
        let primaryHeight = NSScreen.screens.first?.frame.height ?? CGDisplayBounds(CGMainDisplayID()).height
        return NSRect(x: r.minX, y: primaryHeight - r.maxY, width: r.width, height: r.height)
    }

    // MARK: card

    private final class CardView: NSView {
        private let icon = NSImageView()
        private let title = NSTextField(labelWithString: "")
        private let subtitle = NSTextField(labelWithString: "")
        private let badge = NSTextField(labelWithString: "")
        private let back = CALayer()

        override init(frame: NSRect) {
            super.init(frame: frame)
            wantsLayer = true
            guard let l = layer else { return }
            l.backgroundColor = NSColor(srgbRed: 0.165, green: 0.173, blue: 0.188, alpha: 0.96).cgColor
            l.cornerRadius = 14
            l.borderWidth = 1
            l.borderColor = NSColor(white: 1, alpha: 0.12).cgColor
            l.shadowColor = NSColor.black.cgColor
            l.shadowOpacity = 0.32
            l.shadowRadius = 10
            l.shadowOffset = CGSize(width: 0, height: -4)
            l.masksToBounds = false

            back.backgroundColor = NSColor(srgbRed: 0.227, green: 0.239, blue: 0.259, alpha: 0.9).cgColor
            back.cornerRadius = 14
            back.frame = bounds.offsetBy(dx: 4, dy: -3)
            back.setAffineTransform(CGAffineTransform(rotationAngle: -5 * .pi / 180))
            back.isHidden = true
            l.insertSublayer(back, at: 0)

            icon.imageScaling = .scaleProportionallyUpOrDown
            icon.frame = NSRect(x: 10, y: (frame.height - 38) / 2, width: 38, height: 38)
            addSubview(icon)

            title.font = .systemFont(ofSize: 13, weight: .semibold)
            title.textColor = .white
            title.lineBreakMode = .byTruncatingMiddle
            title.frame = NSRect(x: 56, y: frame.height / 2 - 1, width: frame.width - 66, height: 18)
            addSubview(title)

            subtitle.font = .systemFont(ofSize: 11)
            subtitle.textColor = NSColor(white: 0.66, alpha: 1)
            subtitle.lineBreakMode = .byTruncatingTail
            subtitle.frame = NSRect(x: 56, y: frame.height / 2 - 17, width: frame.width - 66, height: 15)
            addSubview(subtitle)

            badge.font = .systemFont(ofSize: 10.5, weight: .bold)
            badge.textColor = NSColor(white: 0.12, alpha: 1)
            badge.alignment = .center
            badge.wantsLayer = true
            badge.drawsBackground = true
            badge.backgroundColor = .white
            badge.layer?.cornerRadius = 9
            badge.layer?.masksToBounds = true
            badge.isHidden = true
            addSubview(badge)
            // A slight tilt: playful, not loud.
            frameCenterRotation = 4
        }

        required init?(coder: NSCoder) { fatalError("not used") }

        func bind(_ info: DragGhostState.Info) {
            let many = info.count > 1
            title.stringValue = many ? "\(info.count) items" : info.firstName
            let size = info.totalBytes > 0 ? ByteCountFormatter.string(fromByteCount: info.totalBytes, countStyle: .file) : ""
            subtitle.stringValue = !size.isEmpty ? size : (many ? "\(info.firstName) and more" : "")
            if subtitle.stringValue.isEmpty {
                title.frame.origin.y = (frame.height - 18) / 2
            } else {
                title.frame.origin.y = frame.height / 2 - 1
            }
            icon.image = Self.icon(for: info)
            back.isHidden = !many
            badge.isHidden = !many
            if many {
                badge.stringValue = info.count > 99 ? "99+" : "\(info.count)"
                let w = max(18, badge.fittingSize.width + 10)
                badge.frame = NSRect(x: frame.width - w / 2 - 4, y: frame.height - 12, width: w, height: 18)
            }
        }

        private static func icon(for info: DragGhostState.Info) -> NSImage {
            if info.kind == "folder" { return NSWorkspace.shared.icon(for: .folder) }
            let ext = (info.firstName as NSString).pathExtension
            if !ext.isEmpty, let t = UTType(filenameExtension: ext) { return NSWorkspace.shared.icon(for: t) }
            switch info.kind {
            case "image": return NSWorkspace.shared.icon(for: .image)
            case "video": return NSWorkspace.shared.icon(for: .movie)
            case "audio": return NSWorkspace.shared.icon(for: .audio)
            default: return NSWorkspace.shared.icon(for: .data)
            }
        }
    }
}
