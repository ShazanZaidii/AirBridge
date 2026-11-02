import Foundation

/// A peer with a ready (authenticated) session.
struct PeerInfo: Identifiable, Equatable {
    let id: String
    let name: String
    let platform: String
    let caps: Set<String>
    var screenW: Int = 0
    var screenH: Int = 0
}

/// Meeting point between the network half (discovery, sessions, pairing, clipboard, file transfer)
/// and the input half (event tap, cursor routing, input injection, display arrangement).
/// See docs/protocol.md §12.
///
/// Network half: assigns `transport`; calls `dispatchControl` for control-type messages and
/// `notifyPeers` whenever the set of ready peers changes.
///
/// Input half: sets `controlHandler` and `peersHandler`; calls `send` / `broadcast` / `peers`.
///
/// Threading: `controlHandler` is invoked on a network queue. The input half hops to the main
/// thread itself where needed. `send` / `broadcast` are safe from any thread and never block.
protocol PeerTransport: AnyObject {
    /// Send to one peer. Returns false if that peer has no ready session.
    @discardableResult func send(to peerId: String, _ msg: [String: Any]) -> Bool
    /// Send to every ready peer.
    func broadcast(_ msg: [String: Any])
    /// Snapshot of ready peers.
    func peers() -> [PeerInfo]
}

nonisolated final class PeerBus: @unchecked Sendable {
    static let shared = PeerBus()

    /// Message types that belong to the input half (routed through `controlHandler`).
    static let controlTypes: Set<String> = [
        "ctl_enter", "ctl_leave",
        "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click",
        "key", "key_state", "action",
    ]

    private let lock = NSLock()
    private weak var _transport: PeerTransport?
    private var _controlHandler: ((_ peerId: String, _ msg: [String: Any]) -> Void)?
    private var _peersHandler: ((_ peers: [PeerInfo]) -> Void)?

    var transport: PeerTransport? {
        get { lock.lock(); defer { lock.unlock() }; return _transport }
        set { lock.lock(); _transport = newValue; lock.unlock() }
    }
    var controlHandler: ((_ peerId: String, _ msg: [String: Any]) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _controlHandler }
        set { lock.lock(); _controlHandler = newValue; lock.unlock() }
    }
    var peersHandler: ((_ peers: [PeerInfo]) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _peersHandler }
        set { lock.lock(); _peersHandler = newValue; lock.unlock() }
    }

    @discardableResult
    func send(to peerId: String, _ msg: [String: Any]) -> Bool { transport?.send(to: peerId, msg) ?? false }
    func broadcast(_ msg: [String: Any]) { transport?.broadcast(msg) }
    func peers() -> [PeerInfo] { transport?.peers() ?? [] }

    func dispatchControl(from peerId: String, _ msg: [String: Any]) { controlHandler?(peerId, msg) }
    func notifyPeers(_ peers: [PeerInfo]) { peersHandler?(peers) }
}
