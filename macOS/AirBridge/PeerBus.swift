import Foundation

/// A peer with a ready (authenticated) session.
nonisolated struct PeerInfo: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let platform: String
    let caps: Set<String>
    var screenW: Int = 0
    var screenH: Int = 0
}

/// Meeting point between the network half (discovery, sessions, pairing, encryption, clipboard, file
/// transfer) and the input half (event tap, cursor routing, input injection, display arrangement,
/// screen-mirror source). See docs/protocol.md §12.
///
/// Network half: assigns `transport`; calls `dispatchControl` for control-type messages, `dispatchBinary`
/// for inner binary frames of kind 0x03 (video), and `notifyPeers` whenever the set of ready peers changes.
/// Everything the input half sees is already decrypted.
///
/// Input half: sets `controlHandler` / `binaryHandler` / `peersHandler`; calls `send` / `broadcast` /
/// `sendBinary` / `peers`.
///
/// Threading: handlers are invoked on a network queue. The input half hops to the main thread itself
/// where needed. Sends are safe from any thread and never block.
nonisolated protocol PeerTransport: AnyObject {
    /// Send to one peer. Returns false if that peer has no ready session.
    @discardableResult func send(to peerId: String, _ msg: [String: Any]) -> Bool
    /// Send to every ready peer.
    func broadcast(_ msg: [String: Any])
    /// Send an inner binary frame (already starting with its kind byte, e.g. 0x03) to one peer.
    @discardableResult func sendBinary(to peerId: String, _ frame: Data) -> Bool
    /// Snapshot of ready peers.
    func peers() -> [PeerInfo]
}

nonisolated final class PeerBus: @unchecked Sendable {
    static let shared = PeerBus()

    /// Message types that belong to the input half (routed through `controlHandler`).
    static let controlTypes: Set<String> = [
        "ctl_enter", "ctl_leave", "ctl_ack", "ctl_busy",
        "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click", "ptr_abs",
        "key", "key_state", "action",
        "mirror_start", "mirror_info", "mirror_key", "mirror_ack", "mirror_stop",
        "drag_carry", "drag_drop", "drag_cancel",
        "mirror_wait", "mirror_state",
        "adb_wifi_state",
    ]

    /// Inner binary frame kinds routed to the input half (first byte of the inner frame).
    static let binaryKindVideo: UInt8 = 0x03

    private let lock = NSLock()
    private weak var _transport: PeerTransport?
    private var _controlHandler: ((_ peerId: String, _ msg: [String: Any]) -> Void)?
    private var _binaryHandler: ((_ peerId: String, _ frame: Data) -> Void)?
    private var _peersHandler: ((_ peers: [PeerInfo]) -> Void)?

    var transport: PeerTransport? {
        get { lock.lock(); defer { lock.unlock() }; return _transport }
        set { lock.lock(); _transport = newValue; lock.unlock() }
    }
    var controlHandler: ((_ peerId: String, _ msg: [String: Any]) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _controlHandler }
        set { lock.lock(); _controlHandler = newValue; lock.unlock() }
    }
    var binaryHandler: ((_ peerId: String, _ frame: Data) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _binaryHandler }
        set { lock.lock(); _binaryHandler = newValue; lock.unlock() }
    }
    var peersHandler: ((_ peers: [PeerInfo]) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _peersHandler }
        set { lock.lock(); _peersHandler = newValue; lock.unlock() }
    }

    @discardableResult
    func send(to peerId: String, _ msg: [String: Any]) -> Bool { transport?.send(to: peerId, msg) ?? false }
    func broadcast(_ msg: [String: Any]) { transport?.broadcast(msg) }
    @discardableResult
    func sendBinary(to peerId: String, _ frame: Data) -> Bool { transport?.sendBinary(to: peerId, frame) ?? false }
    func peers() -> [PeerInfo] { transport?.peers() ?? [] }

    func dispatchControl(from peerId: String, _ msg: [String: Any]) { controlHandler?(peerId, msg) }
    func dispatchBinary(from peerId: String, _ frame: Data) { binaryHandler?(peerId, frame) }
    func notifyPeers(_ peers: [PeerInfo]) { peersHandler?(peers) }
}
