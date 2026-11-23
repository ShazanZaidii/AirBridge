import AppKit
import ApplicationServices

/// Notices when macOS's Notification Center (where the AirBridge widget lives) opens, so the widget can be redrawn with fresh
/// numbers while the user is looking at it. Event-driven through Accessibility (which AirBridge already has for keyboard and
/// mouse sharing): no polling, no cost while nothing happens. Without Accessibility it does nothing.
@MainActor final class NotificationCenterWatch {
    static let shared = NotificationCenterWatch()
    private static let bundle = "com.apple.notificationcenterui"
    private var observer: AXObserver?
    private var pid: pid_t = 0
    private var lastPull = Date.distantPast
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        attach()
        // Notification Center can restart, and Accessibility can be granted later: attach again then.
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didLaunchApplicationNotification, object: nil, queue: .main) { n in
            guard (n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication)?.bundleIdentifier == NotificationCenterWatch.bundle else { return }
            MainActor.assumeIsolated { NotificationCenterWatch.shared.attach() }
        }
        Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { _ in MainActor.assumeIsolated { NotificationCenterWatch.shared.attach() } }
    }

    private func attach() {
        guard AXIsProcessTrusted(), let app = NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundle).first else { return }
        if observer != nil, app.processIdentifier == pid { return }
        if let old = observer { CFRunLoopRemoveSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(old), .defaultMode) }
        observer = nil
        pid = app.processIdentifier
        var obs: AXObserver?
        let callback: AXObserverCallback = { _, _, notification, _ in
            let name = notification as String
            DispatchQueue.main.async { MainActor.assumeIsolated { NotificationCenterWatch.shared.changed(name) } }
        }
        guard AXObserverCreate(pid, callback, &obs) == .success, let obs else { return }
        let element = AXUIElementCreateApplication(pid)
        for n in [kAXWindowCreatedNotification, kAXFocusedWindowChangedNotification, kAXMainWindowChangedNotification,
                  kAXApplicationActivatedNotification, kAXApplicationShownNotification, kAXFocusedUIElementChangedNotification] {
            AXObserverAddNotification(obs, element, n as CFString, nil)
        }
        CFRunLoopAddSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(obs), .defaultMode)
        observer = obs
    }

    private func changed(_ what: String) {
        #if DEBUG
        if let u = ScreenTimeShared.folder()?.appendingPathComponent("nc-watch.txt") {
            let line = "\(Date()) \(what)\n"
            if let h = try? FileHandle(forWritingTo: u) { h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close() }
            else { try? Data(line.utf8).write(to: u) }
        }
        #endif
        // One open fires several of these; one pull per opening is enough.
        guard Date().timeIntervalSince(lastPull) > 8 else { return }
        lastPull = Date()
        ScreenTimeHub.shared.pullNow()
    }
}
