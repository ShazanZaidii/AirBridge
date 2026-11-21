import Foundation

/// Thread-safe view of who controls whom, shared by the controller (event-tap thread) and the injector (its own
/// queue) so they can refuse control loops (protocol.md §8.6): this Mac never controls a peer that is controlling
/// it, and never captures onward while it is itself being controlled.
nonisolated final class ControlGate: @unchecked Sendable {
    static let shared = ControlGate()

    private let lock = NSLock()
    private var _controlling: String?
    private var _controlledBy: String?
    private var _mirrorFocus: String?

    /// Peer whose cursor/keyboard the Mac's own mouse and keyboard currently drive (set by `Controller`).
    var controlling: String? {
        get { lock.lock(); defer { lock.unlock() }; return _controlling }
        set { lock.lock(); _controlling = newValue; lock.unlock() }
    }

    /// Phone whose mirror window has keyboard focus (set by the mirror window). While it is set, that window is
    /// the phone's controller and pushing through a screen edge never captures the cursor for the same phone.
    var mirrorFocus: String? {
        get { lock.lock(); defer { lock.unlock() }; return _mirrorFocus }
        set { lock.lock(); _mirrorFocus = newValue; lock.unlock() }
    }

    /// Peer that currently holds the Mac's control lease (set by `Injector`).
    var controlledBy: String? {
        get { lock.lock(); defer { lock.unlock() }; return _controlledBy }
        set { lock.lock(); _controlledBy = newValue; lock.unlock() }
    }
}
