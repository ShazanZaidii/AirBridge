import Cocoa
import CoreGraphics
import ApplicationServices

/// Entry point of the input half. Wires PeerBus to the controller (event tap, Input/Controller.swift)
/// and the target-side injector (Input/Injector.swift). See docs/protocol.md §8 and §12.
nonisolated final class InputInterceptor: @unchecked Sendable {
    static let shared = InputInterceptor()

    private let lock = NSLock()
    private var started = false
    private var known: Set<String> = []

    /// Idempotent; call once at launch from any thread. If Accessibility isn't granted yet the
    /// tap installs itself as soon as it is (polled every 2 s).
    func start() {
        lock.lock()
        if started { lock.unlock(); return }
        started = true
        lock.unlock()

        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
        Controller.shared.start()

        let bus = PeerBus.shared
        bus.controlHandler = { [unowned self] peer, msg in self.route(peer, msg) }
        bus.peersHandler = { [unowned self] peers in self.peersChanged(peers) }
        peersChanged(bus.peers())

        DispatchQueue.main.async {
            NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification,
                                                   object: nil, queue: nil) { _ in
                Controller.shared.perform { Controller.shared.invalidateGeometry() }
            }
        }
    }

    /// Brings the cursor back to the Mac if it is on a remote screen.
    func forceReturnToMac() {
        Controller.shared.perform { Controller.shared.recall() }
    }

    private func route(_ peer: String, _ msg: [String: Any]) {
        guard let t = msg["t"] as? String else { return }
        switch t {
        case "ctl_leave":
            let rel = msg["edge"] == nil ? nil : Msg.num(msg["rel"])
            Controller.shared.perform { Controller.shared.remoteLeave(peer: peer, rel: rel) }
            Injector.shared.submit(peer, msg)
        case "ctl_enter":
            Controller.shared.perform { Controller.shared.peerTookOver(peer) }
            Injector.shared.submit(peer, msg)
        case "key_state":
            break // no caps-lock indicator on the Mac
        default:
            Injector.shared.submit(peer, msg)
        }
    }

    private func peersChanged(_ peers: [PeerInfo]) {
        lock.lock()
        let fresh = peers.filter { !known.contains($0.id) }
        known = Set(peers.map(\.id))
        lock.unlock()

        Layout.shared.autoAssign(peers.filter { $0.caps.contains("ctl_target") })
        Controller.shared.perform { Controller.shared.peersChanged(peers) }
        Injector.shared.peersChanged(Set(peers.map(\.id)))
        DispatchQueue.main.async { PeerDirectory.shared.peers = peers }

        // §8.3: tell new trackpad sessions the current caps-lock state.
        let caps = CGEventSource.flagsState(.hidSystemState).contains(.maskAlphaShift)
        for p in fresh where p.caps.contains("trackpad") {
            PeerBus.shared.send(to: p.id, ["t": "key_state", "caps": caps])
        }
    }
}
