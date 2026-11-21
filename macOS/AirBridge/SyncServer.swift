import Foundation

/// Temporary compile shim: the v1 `InputInterceptor` on this branch still references this type.
/// All v1 networking is gone; the v2 input half talks to `PeerBus` instead.
/// Delete this file once the v2 input half is merged.
nonisolated final class SyncServer {
    var connectedClients: Int { 0 }
    func broadcastInput(command: String) {}
}
