import AppKit
import SwiftUI

/// AirBridge lives in the menu bar, but its windows (phone mirror, AirWeb, notifications, calls, settings
/// windows) behave like any Mac app's: while one is open the app appears in ⌘Tab and the Dock and has a
/// real menu bar (⌘W closes the front window, ⌘M minimises, ⌘Q quits). When the last one closes it goes
/// back to being a menu bar app.
@MainActor final class AppWindows: NSObject {
    static let shared = AppWindows()
    private var observers: [NSObjectProtocol] = []
    private var pending = false

    func start() {
        NSApp.mainMenu = Self.makeMainMenu()
        let nc = NotificationCenter.default
        for name in [NSWindow.didBecomeKeyNotification, NSWindow.didBecomeMainNotification, NSWindow.willCloseNotification,
                     NSWindow.didMiniaturizeNotification, NSWindow.didDeminiaturizeNotification, NSWindow.didChangeOcclusionStateNotification,
                     NSApplication.didResignActiveNotification] {
            observers.append(nc.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.schedule() }
            })
        }
        schedule()
    }

    /// Re-evaluate shortly after window changes settle (a closing window is still "visible" in willClose).
    private func schedule() {
        guard !pending else { return }
        pending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            MainActor.assumeIsolated {
                self?.pending = false
                self?.update()
            }
        }
    }

    private func update() {
        let has = NSApp.windows.contains(where: Self.isAppWindow)
        let want: NSApplication.ActivationPolicy = has ? .regular : .accessory
        guard NSApp.activationPolicy() != want else { return }
        // The last window closed while AirBridge is the active app: hand the focus to an app on *this* desktop first. Left to
        // itself macOS activates the previously used app, and with "switch to a Space with open windows" that jumped the
        // user to the desktop they had left (closing a window on an empty desktop teleported them).
        if want == .accessory && NSApp.isActive { Self.activateAppOnThisDesktop() }
        NSApp.setActivationPolicy(want)
        if want == .regular {
            // Make the menu bar ours right away (otherwise the previous app's menu stays until a click).
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    /// Activates the app whose window is front-most on the current desktop, or Finder (it owns the desktop itself on every
    /// Space) when there is none, so no desktop switch happens.
    private static func activateAppOnThisDesktop() {
        let me = ProcessInfo.processInfo.processIdentifier
        let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
        for w in list {   // front to back
            guard (w[kCGWindowLayer as String] as? Int) == 0, let pid = w[kCGWindowOwnerPID as String] as? pid_t, pid != me,
                  let b = w[kCGWindowBounds as String] as? [String: Any], let r = CGRect(dictionaryRepresentation: b as CFDictionary),
                  r.width > 80, r.height > 80, let app = NSRunningApplication(processIdentifier: pid),
                  app.activationPolicy == .regular else { continue }
            app.activate()
            return
        }
        NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.finder").first?.activate()
    }

    /// Hides the menu bar panel (it floats above other windows): called when the panel opens one of AirBridge's windows.
    static func closeMenuPanel() {
        for w in NSApp.windows where w.isVisible && String(describing: type(of: w)).contains("MenuBarExtra") { w.orderOut(nil) }
    }

    /// A window the user opened and can switch to: titled windows and the phone mirror window — not the menu
    /// bar popover, HUD panels, banners or off-screen helpers. Minimised windows still count (they're in the Dock).
    static func isAppWindow(_ w: NSWindow) -> Bool {
        guard w.isVisible || w.isMiniaturized else { return false }
        if w is NSPanel { return false }
        if w is MirrorWindow { return true }
        let name = String(describing: type(of: w))
        if name.contains("MenuBarExtra") || name.contains("StatusBar") || name.contains("Popover") { return false }
        return w.styleMask.contains(.titled) && w.frame.width > 120 && w.frame.minX > -3000
    }

    private static func makeMainMenu() -> NSMenu {
        let main = NSMenu()

        let app = NSMenu(title: "AirBridge")
        app.addItem(withTitle: "About AirBridge", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        app.addItem(.separator())
        app.addItem(withTitle: "Hide AirBridge", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        let others = app.addItem(withTitle: "Hide Others", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
        others.keyEquivalentModifierMask = [.command, .option]
        app.addItem(.separator())
        app.addItem(withTitle: "Quit AirBridge", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        main.addItem(submenu: app, title: "AirBridge")

        let file = NSMenu(title: "File")
        file.addItem(withTitle: "Close Window", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        main.addItem(submenu: file, title: "File")

        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        let redo = edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        edit.addItem(.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        main.addItem(submenu: edit, title: "Edit")

        let window = NSMenu(title: "Window")
        window.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        window.addItem(withTitle: "Zoom", action: #selector(NSWindow.performZoom(_:)), keyEquivalent: "")
        window.addItem(.separator())
        window.addItem(withTitle: "Bring All to Front", action: #selector(NSApplication.arrangeInFront(_:)), keyEquivalent: "")
        main.addItem(submenu: window, title: "Window")
        NSApp.windowsMenu = window
        return main
    }
}

private extension NSMenu {
    func addItem(submenu: NSMenu, title: String) {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.submenu = submenu
        addItem(item)
    }
}

/// Places a window where it makes sense on screen: under the menu bar at the right edge of the screen that
/// holds the AirBridge menu bar icon, sized to the room that's there, and beside — not on top of — other
/// AirBridge windows already open (a phone mirror, a call window).
@MainActor enum SmartPlacement {
    static func frame(for size: NSSize, avoiding window: NSWindow? = nil) -> NSRect {
        let screen = statusItemScreen() ?? NSScreen.main ?? NSScreen.screens.first
        guard let vis = screen?.visibleFrame else { return NSRect(origin: .zero, size: size) }
        let margin: CGFloat = 12
        let h = min(size.height, vis.height - 2 * margin)
        let w = min(size.width, vis.width - 2 * margin)
        var rect = NSRect(x: vis.maxX - w - margin, y: vis.maxY - h - margin, width: w, height: h)
        // Step left past any of our windows it would cover.
        let others = NSApp.windows.filter { $0 !== window && AppWindows.isAppWindow($0) && !$0.isMiniaturized && $0.screen == screen }
        for _ in 0..<others.count {
            guard let hit = others.first(where: { $0.frame.insetBy(dx: 4, dy: 4).intersects(rect) }) else { break }
            let x = hit.frame.minX - w - margin
            if x < vis.minX + margin { break } // no room on the left: stay where it is
            rect.origin.x = x
        }
        return rect
    }

    /// The screen showing the menu bar icon (falls back to the main screen).
    static func statusItemScreen() -> NSScreen? {
        NSApp.windows.first { String(describing: type(of: $0)).contains("StatusBar") }?.screen
    }
}

/// A window that fits its SwiftUI content's height without AppKit's automatic sizing. Automatic sizing
/// (`sizingOptions = .preferredContentSize`) on a window whose content runs under the title bar can loop on
/// macOS 26: each resize changes the safe area, which changes the content, which resizes the window, until AppKit
/// aborts. Here the content is measured at its natural height and the window is resized afterwards, outside the
/// layout pass, so it settles in one step.
@MainActor enum FitWindow {
    static func host<V: View>(_ view: V, width: CGFloat, window: @escaping () -> NSWindow?) -> NSHostingController<AnyView> {
        let host = NSHostingController(rootView: wrap(view, width: width, window: window))
        host.sizingOptions = []
        return host
    }

    /// The content measured for [host] (also for replacing a host's root view later).
    static func wrap<V: View>(_ view: V, width: CGFloat, window: @escaping () -> NSWindow?) -> AnyView {
        AnyView(view
            .fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { h in
                DispatchQueue.main.async {
                    guard let w = window(), h > 0 else { return }
                    // Title bar and other chrome above the content (the content keeps clear of it).
                    let chrome = w.frame.height - w.contentLayoutRect.height
                    let target = NSSize(width: width, height: ceil(h) + chrome)
                    guard abs(w.frame.height - target.height) > 0.5 || abs(w.frame.width - target.width) > 0.5 else { return }
                    // Grow or shrink downwards, keeping the title bar where it is.
                    let f = NSRect(x: w.frame.minX, y: w.frame.maxY - target.height, width: target.width, height: target.height)
                    w.setFrame(f, display: true, animate: w.isVisible)
                }
            })
    }
}
