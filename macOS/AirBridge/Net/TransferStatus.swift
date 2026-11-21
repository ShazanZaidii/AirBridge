import Foundation

/// UI-facing snapshot of one transfer.
nonisolated struct TransferStatus: Identifiable, Equatable, Sendable {
    enum Direction: Sendable { case incoming, outgoing }
    enum Phase: Equatable, Sendable { case waiting, active, done, failed(String) }
    let id: String
    let direction: Direction
    let peerName: String
    let fileCount: Int
    let firstName: String
    var total: Int64
    var done: Int64
    var phase: Phase
    var bytesPerSec: Double = 0
    /// The other device's id (for Retry and matching a just-started send to its card).
    var peerId: String = ""
    /// Received files as saved on disk (incoming, once done).
    var savedPaths: [String] = []
    /// The files being sent (outgoing), so a failed send can be retried.
    var sourcePaths: [String] = []

    /// `failed` text when this side's user stopped the transfer.
    static let cancelledMessage = "Cancelled"
}
