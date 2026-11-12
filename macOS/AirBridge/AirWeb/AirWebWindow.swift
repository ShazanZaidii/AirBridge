import AppKit
import SwiftUI

/// The AirWeb window. Opening it starts the one AirWeb session. Closing the window leaves AirWeb running (same link,
/// same server): sharing more files later joins it. Stop ends it, and so does two hours with nobody connected.
@MainActor final class AirWebWindow: NSObject, NSWindowDelegate {
    static let shared = AirWebWindow()

    private var window: NSWindow?
    private(set) var session: AirWebSession?

    /// Shows AirWeb offering `urls` (may be empty: receive only). If it is already open the files are added.
    /// `batches`: share-extension staging folders to delete when the session ends.
    func show(offering urls: [URL] = [], batches: [URL] = []) {
        let s: AirWebSession
        if let session { s = session } else {
            s = AirWebSession()
            session = s
            s.start()
        }
        s.add(urls, batches: batches)
        if window == nil {
            let host = NSHostingController(rootView: AirWebView(session: s))
            host.sizingOptions = [.minSize]
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 760, height: 540),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable],
                             backing: .buffered, defer: false)
            w.title = "AirWeb"
            w.isReleasedWhenClosed = false
            w.contentViewController = host
            w.setContentSize(NSSize(width: 760, height: 540))
            w.delegate = self
            w.center()
            window = w
        }
        idleTimer?.invalidate()
        idleTimer = nil
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    /// The Stop button: ends the session, asking first if something is still moving.
    func stop() {
        func end() { endSession(); window?.close() }
        guard session?.isBusy == true, let w = window else { end(); return }
        let alert = NSAlert()
        alert.messageText = "Stop AirWeb?"
        alert.informativeText = "A file is still being sent. Stopping now cuts it off."
        alert.addButton(withTitle: "Stop")
        alert.addButton(withTitle: "Keep Sharing")
        alert.beginSheetModal(for: w) { response in if response == .alertFirstButtonReturn { end() } }
    }

    #if DEBUG
    func debugCloseWindow() { window?.close() }
    #endif

    /// Whether AirWeb is running (with or without its window).
    var isOn: Bool { session != nil }

    /// The app is quitting.
    func shutdown() {
        idleTimer?.invalidate()
        session?.end(now: true)
        session = nil
    }

    private func endSession() {
        idleTimer?.invalidate()
        idleTimer = nil
        session?.end()
        session = nil
    }

    // MARK: Idle

    private var idleTimer: Timer?
    private var lastBusy = Date()
    private static let idleStop: TimeInterval = 2 * 3600

    /// With the window closed, nobody connected and nothing moving for two hours, AirWeb turns itself off.
    private func watchIdle() {
        lastBusy = Date()
        idleTimer?.invalidate()
        idleTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let s = self.session else { return }
                if s.isBusy || !s.peers.isEmpty { self.lastBusy = Date() }
                else if Date().timeIntervalSince(self.lastBusy) > Self.idleStop { self.endSession() }
            }
        }
    }

    func windowWillClose(_ notification: Notification) {
        // Only the window goes: the session keeps running (see above), so the link keeps working.
        window?.contentViewController = nil
        window = nil
        if session != nil { watchIdle() }
    }
}
