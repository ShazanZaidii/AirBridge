import Cocoa
import CoreGraphics
import ApplicationServices

/// Entry point of the input half. Wires PeerBus to the controller (event tap, Input/Controller.swift),
/// the target-side injector with its control lease (Input/Injector.swift) and the screen-mirror source
/// (Input/MirrorSource.swift). See docs/protocol.md §8 and §12.
///
/// Status for the menu: `ControlStatus` posts `Notification.Name("AirBridgeControlStatus")` with
/// `userInfo["text"]` (String, or NSNull when idle) on the main thread on every change.
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
        // Video from a phone that shows its screen in a mirror window (§8.7, Mac as sink).
        bus.binaryHandler = { peer, frame in PhoneMirrorHub.shared.frame(peer, frame) }
        peersChanged(bus.peers())
        // Pro mirroring: browse for set-up phones' Wireless debugging early so Mirror picks it at once.
        if !ProMirrorStore.all().isEmpty { ProMirrorHub.shared.startIfNeeded() }

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
            let reason = msg["reason"] as? String
            Controller.shared.perform { Controller.shared.remoteLeave(peer: peer, rel: rel, reason: reason) }
            Injector.shared.submit(peer, msg)
        case "ctl_ack":
            Controller.shared.perform { Controller.shared.remoteAck(peer: peer) }
        case "ctl_enter":
            Controller.shared.perform { Controller.shared.peerTookOver(peer) }
            Injector.shared.submit(peer, msg)
        case "ctl_busy":
            let holder = msg["holder"] as? String ?? ""
            Controller.shared.perform { Controller.shared.busy(peer: peer, holder: holder) }
        case "mirror_start", "mirror_key", "mirror_ack":
            MirrorSource.shared.handle(peer, msg)
        case "mirror_stop":
            // Either side may stop: the phone stopping its stream (mirror window) or a phone sink leaving ours.
            if PhoneMirrorHub.shared.isMirroring(peer) { PhoneMirrorHub.shared.handle(peer, msg) }
            MirrorSource.shared.handle(peer, msg)
        case "mirror_info", "mirror_wait", "mirror_state", "error":
            PhoneMirrorHub.shared.handle(peer, msg)
        case "adb_wifi_state":
            ProMirrorHub.shared.handle(peer, msg)   // Pro mirroring setup helper (§8.9)
        case "mirror_trust":
            MirrorTrust.shared.handle(peer, msg)    // "Mirror without asking" (§8.10)
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
        Injector.shared.peersChanged(peers)
        MirrorSource.shared.peersChanged(Set(peers.map(\.id)))
        PhoneMirrorHub.shared.peersChanged(Set(peers.map(\.id)))
        DispatchQueue.main.async { PeerDirectory.shared.peers = peers }

        // §8.3: tell new trackpad sessions the current caps-lock state.
        let caps = CGEventSource.flagsState(.hidSystemState).contains(.maskAlphaShift)
        for p in fresh where p.caps.contains("trackpad") {
            PeerBus.shared.send(to: p.id, ["t": "key_state", "caps": caps])
        }
    }
}
