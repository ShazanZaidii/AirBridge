import AppKit
import SwiftUI

/// The AirWeb window. Opening it starts a session; closing it (or Stop) ends the session.
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
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    /// The Stop button: asks first if something is still moving.
    func stop() { window?.performClose(nil) }

    /// The app is quitting.
    func shutdown() {
        session?.end(now: true)
        session = nil
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        guard session?.isBusy == true else { return true }
        let alert = NSAlert()
        alert.messageText = "Stop AirWeb?"
        alert.informativeText = "A file is still being sent. Stopping now cuts it off."
        alert.addButton(withTitle: "Stop")
        alert.addButton(withTitle: "Keep Sharing")
        alert.beginSheetModal(for: sender) { response in
            if response == .alertFirstButtonReturn { sender.close() }
        }
        return false
    }

    func windowWillClose(_ notification: Notification) {
        session?.end()
        session = nil
        window?.contentViewController = nil
        window = nil
    }
}
