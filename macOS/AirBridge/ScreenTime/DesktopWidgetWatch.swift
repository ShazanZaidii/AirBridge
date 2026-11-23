import AppKit
import CoreGraphics

/// Whether widgets sit on the desktop, and whether they can be seen right now (no app window covering them). macOS has no
/// notification for "the desktop is in view", so this looks at the window list (owners and positions only, no permission
/// needed) when it can change (switching desktops or apps, an app hiding or quitting, waking) and, only while widgets sit on
/// the desktop, every 3 seconds (Show Desktop sends no event). Then:
/// - desktop comes into view: fresh numbers and a redraw at once;
/// - staying on the desktop: again every 5 minutes;
/// - desktop widgets covered: every 30 minutes (ScreenTimeHub's background pace).
/// Every redraw still happens only if the numbers changed. Mission Control's previews give apps no signal at all.
@MainActor final class DesktopWidgetWatch {
    static let shared = DesktopWidgetWatch()
    private var started = false
    private var visible = false
    private var timer: Timer?

    func start() {
        guard !started else { return }
        started = true
        let ws = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.activeSpaceDidChangeNotification, NSWorkspace.didActivateApplicationNotification,
                     NSWorkspace.didHideApplicationNotification, NSWorkspace.didUnhideApplicationNotification,
                     NSWorkspace.didTerminateApplicationNotification, NSWorkspace.screensDidWakeNotification,
                     NSWorkspace.sessionDidBecomeActiveNotification] {
            ws.addObserver(forName: name, object: nil, queue: .main) { _ in
                // Let window animations settle before looking.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { MainActor.assumeIsolated { DesktopWidgetWatch.shared.check() } }
            }
        }
        check()
    }

    private var present = false
    private var lastPull = Date.distantPast

    func check() {
        // Nobody sees the desktop while the screen is locked or asleep.
        let state = ScreenTimeHub.screenInUse() ? Self.look() : (present: present, visible: false)
        // Fresh numbers when the desktop comes into view, and every 5 minutes while it stays in view.
        let pull = state.visible && (!visible || Date().timeIntervalSince(lastPull) >= 300)
        #if DEBUG
        if state.present != present || state.visible != visible || pull, let u = ScreenTimeShared.folder()?.appendingPathComponent("desktop-watch.txt") {
            let line = "\(Date()) present=\(state.present) visible=\(state.visible)\(pull ? " -> pull" : "")\n"
            if let h = try? FileHandle(forWritingTo: u) { h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close() }
            else { try? Data(line.utf8).write(to: u) }
        }
        #endif
        if state.present != present || state.visible != visible { ScreenTimeHub.shared.setDesktop(present: state.present, visible: state.visible) }
        present = state.present
        visible = state.visible
        if pull { lastPull = Date(); ScreenTimeHub.shared.pullNow() }
        // Show Desktop (four-finger spread), hot corners and Stage Manager send apps no event, so while widgets sit on the
        // desktop the window list is read every 3 seconds (about a millisecond). Without desktop widgets: a look every 5
        // minutes, in case some are added.
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: present ? 3 : 300, repeats: false) { _ in
            MainActor.assumeIsolated { DesktopWidgetWatch.shared.check() }
        }
    }

    /// Desktop widgets present on this desktop, and whether any of them is not completely covered by an app window.
    nonisolated static func look() -> (present: Bool, visible: Bool) {
        guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] else { return (false, false) }
        var widgets: [CGRect] = [], covers: [CGRect] = []
        for w in list {
            let owner = w[kCGWindowOwnerName as String] as? String ?? ""
            let layer = w[kCGWindowLayer as String] as? Int ?? 0
            let alpha = w[kCGWindowAlpha as String] as? Double ?? 1
            guard alpha > 0.05, let b = w[kCGWindowBounds as String] as? [String: Any],
                  let r = CGRect(dictionaryRepresentation: b as CFDictionary), r.width > 40, r.height > 40 else { continue }
            if owner == "NotificationCenter" && layer < 0 { widgets.append(r) }       // widgets drawn on the desktop
            else if layer == 0 && r.height > 60 { covers.append(r) }                  // ordinary app windows
        }
        let visible = widgets.contains { w in !covers.contains { $0.contains(w) } && covered(w, by: covers) < 0.8 }
        return (!widgets.isEmpty, visible)
    }

    /// How much of `r` the windows cover, roughly (sum of overlaps, capped at 1).
    nonisolated static func covered(_ r: CGRect, by windows: [CGRect]) -> Double {
        let area = Double(r.width * r.height)
        guard area > 0 else { return 1 }
        let overlap = windows.reduce(0.0) { $0 + Double(r.intersection($1).isNull ? 0 : r.intersection($1).width * r.intersection($1).height) }
        return min(1, overlap / area)
    }
}
