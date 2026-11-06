import Foundation
import Network

/// TCP listener on 47632 (falling back through 47641), advertising `_airbridge._tcp` (§2.2, §2.3).
/// The WebSocket upgrade and `/v2` path check happen in `WebSocket`. Runs on `queue`.
nonisolated final class Server: @unchecked Sendable {
    private let queue: DispatchQueue
    private var listener: NWListener?
    private var restartWork: DispatchWorkItem?
    private(set) var port: UInt16?
    private var listenerReady = false

    var onConnection: ((NWConnection) -> Void)?
    var onPortChange: ((UInt16?) -> Void)?

    init(queue: DispatchQueue) { self.queue = queue }

    func start() { bind(index: 0) }

    func stop() {
        restartWork?.cancel()
        listener?.cancel()
        listener = nil
        setPort(nil)
    }

    /// Re-applies the Bonjour service (after a rename).
    func refreshService() { listener?.service = Discovery.advertisedService() }

    /// Restarts if the listener died (e.g. after a network change).
    func ensureRunning() {
        if listener == nil, restartWork == nil { bind(index: 0) }
    }

    private func bind(index: Int) {
        restartWork = nil
        guard index < Proto.ports.count else {
            NSLog("AirBridge: no free port in \(Proto.ports.first!)–\(Proto.ports.last!); retrying")
            scheduleRestart(after: 10)
            return
        }
        let candidate = Proto.ports[index]
        let l: NWListener
        do {
            l = try NWListener(using: WebSocket.tcpParameters(), on: NWEndpoint.Port(rawValue: candidate)!)
        } catch {
            bind(index: index + 1)
            return
        }
        l.service = Discovery.advertisedService()
        l.newConnectionHandler = { [weak self] conn in self?.onConnection?(conn) }
        listenerReady = false
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard let self, let l, self.listener === l else { return }
            switch state {
            case .ready:
                self.listenerReady = true
                self.setPort(candidate)
            case .failed(let error):
                l.cancel()
                self.listener = nil
                if self.listenerReady {
                    NSLog("AirBridge: listener failed (\(error)); restarting")
                    self.setPort(nil)
                    self.scheduleRestart(after: 2)
                } else {
                    self.bind(index: index + 1)
                }
            default:
                break
            }
        }
        listener = l
        l.start(queue: queue)
    }

    private func scheduleRestart(after seconds: TimeInterval) {
        let work = DispatchWorkItem { [weak self] in self?.bind(index: 0) }
        restartWork = work
        queue.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func setPort(_ p: UInt16?) {
        guard port != p else { return }
        port = p
        onPortChange?(p)
    }
}
