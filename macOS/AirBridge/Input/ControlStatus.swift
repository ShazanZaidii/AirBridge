import Foundation

/// Single source of the "who controls whom" line (protocol.md §8.6). Every change is posted on the main
/// thread as `AirBridgeControlStatus` with `userInfo["text"]` = the line, or `NSNull()` when idle.
/// The network half renders it in the menu; DisplayArrangementView shows it too. Thread-safe.
nonisolated final class ControlStatus: @unchecked Sendable {
    static let shared = ControlStatus()
    static let notification = Notification.Name("AirBridgeControlStatus")

    struct Snapshot: Equatable, Sendable {
        var controlling: String?      // Mac is the controller of this peer
        var controlledBy: String?     // this peer holds the Mac's lease
        var leaseImplicit = false     // trackpad/mirror lease (expires) vs ctl_enter lease
        var mirroring: String?        // screen is streamed to this peer
        var notice: String?           // transient, e.g. "Pixel 9 is being used by Shazan's PC"

        var text: String? {
            if let c = controlling { return "Controlling \(c) · press Ctrl+Opt+Cmd+Esc to return" }
            if let n = notice { return n }
            if let b = controlledBy { return "Controlled by \(b)" }
            if let m = mirroring { return "Sharing screen with \(m)" }
            return nil
        }
    }

    private let lock = NSLock()
    private var state = Snapshot()
    private var noticeSerial = 0

    var snapshot: Snapshot {
        lock.lock(); defer { lock.unlock() }
        return state
    }

    func setControlling(_ name: String?) { mutate { $0.controlling = name } }

    func setControlledBy(_ name: String?, implicit: Bool = false) {
        mutate { $0.controlledBy = name; $0.leaseImplicit = name != nil && implicit }
    }

    func setMirroring(_ name: String?) { mutate { $0.mirroring = name } }

    /// Shows `text` for `seconds`, then falls back to the persistent state.
    func showNotice(_ text: String, seconds: Double = 4) {
        lock.lock()
        noticeSerial += 1
        let serial = noticeSerial
        lock.unlock()
        mutate { $0.notice = text }
        DispatchQueue.global().asyncAfter(deadline: .now() + seconds) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let current = self.noticeSerial == serial
            self.lock.unlock()
            if current { self.mutate { $0.notice = nil } }
        }
    }

    private func mutate(_ f: (inout Snapshot) -> Void) {
        lock.lock()
        let before = state
        f(&state)
        let after = state
        lock.unlock()
        guard before != after else { return }
        DispatchQueue.main.async { [weak self] in self?.post(after) }
    }

    /// Main thread.
    private func post(_ s: Snapshot) {
        let text = s.text
        var info: [String: Any] = ["text": text.map { $0 as Any } ?? NSNull()]
        info["controlling"] = s.controlling ?? NSNull()
        info["controlledBy"] = s.controlledBy ?? NSNull()
        info["mirroring"] = s.mirroring ?? NSNull()
        NotificationCenter.default.post(name: Self.notification, object: nil, userInfo: info)
    }
}
