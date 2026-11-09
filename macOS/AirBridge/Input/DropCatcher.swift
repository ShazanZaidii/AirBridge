import AppKit

/// An invisible drop target at the screen edge while a file drag is carried to another device (protocol.md §8.8).
///
/// While the user pushes a file drag against an edge that leads to a `drag_files` peer, this thin panel sits under
/// the pinned cursor. The system drag session then sees it as the destination. When the user lets go over the
/// remote device, the controller lets that release through at the frozen cursor position, so the drop lands here:
/// we accept it as a copy and do nothing. The source keeps its files, the drag ends cleanly, and nothing is dropped
/// on the desktop or a window that happens to be at the edge. A drop here while nothing is carried (the user let go
/// at the very edge without crossing) is refused, which looks like dropping on nothing.
///
/// `show`/`hide` are main-thread only; `hasDrag`/`carrying` are thread-safe (read on the event-tap thread).
@MainActor
final class DropCatcher {
    static let shared = DropCatcher()

    nonisolated static let thickness: CGFloat = 6

    private var panel: NSPanel?
    private var serial = 0

    private nonisolated static let lock = NSLock()
    private nonisolated(unsafe) static var _hasDrag = false
    private nonisolated(unsafe) static var _carrying = false

    /// A drag session currently has us as its destination (entered and not exited).
    nonisolated static var hasDrag: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _hasDrag }
        set { lock.lock(); _hasDrag = newValue; lock.unlock() }
    }

    /// The controller is carrying (accept the drop as a no-op copy); false = refuse drops.
    nonisolated static var carrying: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _carrying }
        set { lock.lock(); _carrying = newValue; lock.unlock() }
    }

    /// `rect` in global CoreGraphics coordinates (top-left origin).
    func show(rect: CGRect) {
        let p = ensurePanel()
        serial += 1
        let frame = Self.cocoaRect(rect)
        if p.frame != frame { p.setFrame(frame, display: false) }
        if !p.isVisible { p.orderFrontRegardless() }
    }

    /// Hides now, or after `delay` unless shown again in the meantime.
    func hide(after delay: Double = 0) {
        serial += 1
        let s = serial
        let work = { [weak self] in
            guard let self, self.serial == s else { return }
            self.panel?.orderOut(nil)
            Self.hasDrag = false
            if delay > 0 { Self.carrying = false } // a delayed hide follows a drop, which has been performed by now
        }
        if delay <= 0 { work() } else { DispatchQueue.main.asyncAfter(deadline: .now() + delay) { work() } }
    }

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: Self.thickness, height: Self.thickness),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        p.isFloatingPanel = true
        p.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.overlayWindow)))
        p.backgroundColor = .clear
        p.isOpaque = false
        p.hasShadow = false
        // Set explicitly: a transparent window then still takes events (and drags) over its whole frame.
        p.ignoresMouseEvents = false
        p.hidesOnDeactivate = false
        p.isReleasedWhenClosed = false
        p.becomesKeyOnlyIfNeeded = true
        p.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        p.animationBehavior = .none
        let v = CatcherView(frame: p.contentRect(forFrameRect: p.frame))
        v.autoresizingMask = [.width, .height]
        p.contentView = v
        panel = p
        return p
    }

    private static func cocoaRect(_ r: CGRect) -> NSRect {
        let primaryHeight = NSScreen.screens.first?.frame.height ?? CGDisplayBounds(CGMainDisplayID()).height
        return NSRect(x: r.minX, y: primaryHeight - r.maxY, width: r.width, height: r.height)
    }

    private final class CatcherView: NSView {
        override init(frame: NSRect) {
            super.init(frame: frame)
            wantsLayer = true
            // Nearly invisible, never fully transparent (some hit tests skip alpha-0 pixels).
            layer?.backgroundColor = NSColor(white: 1, alpha: 0.012).cgColor
            registerForDraggedTypes([.fileURL, .URL, NSPasteboard.PasteboardType("NSFilenamesPboardType")])
        }

        required init?(coder: NSCoder) { fatalError("not used") }

        private func operation(_ info: NSDraggingInfo) -> NSDragOperation {
            let mask = info.draggingSourceOperationMask
            if mask.contains(.copy) { return .copy }
            if mask.contains(.generic) { return .generic }
            return []
        }

        override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
            DropCatcher.hasDrag = true
            return operation(sender)
        }

        override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
            DropCatcher.hasDrag = true
            return operation(sender)
        }

        override func draggingExited(_ sender: NSDraggingInfo?) { DropCatcher.hasDrag = false }

        override func draggingEnded(_ sender: NSDraggingInfo) { DropCatcher.hasDrag = false }

        override func prepareForDragOperation(_ sender: NSDraggingInfo) -> Bool { DropCatcher.carrying }

        /// The files are already on their way to the other device; the local drop itself does nothing.
        override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
            DropCatcher.hasDrag = false
            return DropCatcher.carrying
        }
    }
}
