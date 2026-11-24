import Foundation
import Network

/// A host seen over mDNS (§2.3).
nonisolated struct DiscoveredHost: Equatable, Sendable {
    let id: String
    let name: String
    let plat: String
    let endpoint: NWEndpoint
}

/// Bonjour advertisement (applied to the listener) and continuous browsing for other hosts.
nonisolated final class Discovery: @unchecked Sendable {
    private let queue: DispatchQueue
    private var browser: NWBrowser?
    private(set) var hosts: [String: DiscoveredHost] = [:]

    /// Called with the ids that newly appeared (or changed endpoint) whenever results change.
    var onChange: ((_ appeared: Set<String>) -> Void)?

    init(queue: DispatchQueue) { self.queue = queue }

    /// The TXT record we advertise (§2.3), in DNS wire format: each `key=value` prefixed by its length.
    static func advertisedTXT() -> Data {
        let pairs = ["v": String(Proto.version), "id": Identity.id, "name": Identity.name, "plat": Proto.platform]
        var out = Data()
        for (k, v) in pairs {
            var entry = Array("\(k)=\(v)".utf8)
            if entry.count > 255 { entry = Array(entry.prefix(255)) }
            out.append(UInt8(entry.count))
            out.append(contentsOf: entry)
        }
        return out
    }

    func start() {
        browser?.cancel()
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: Proto.serviceType, domain: Proto.serviceDomain),
                          using: NWParameters())
        b.browseResultsChangedHandler = { [weak self] results, _ in self?.update(results) }
        b.stateUpdateHandler = { [weak self, weak b] state in
            guard let self, let b, self.browser === b else { return }
            if case .failed = state {
                b.cancel()
                self.browser = nil
                self.queue.asyncAfter(deadline: .now() + 3) { [weak self] in
                    if self?.browser == nil { self?.start() }
                }
            }
        }
        browser = b
        b.start(queue: queue)
    }

    func restart() { start() }

    private func update(_ results: Set<NWBrowser.Result>) {
        var next: [String: DiscoveredHost] = [:]
        for r in results {
            guard case .bonjour(let txt) = r.metadata,
                  txt["v"] == String(Proto.version),
                  let id = txt["id"], !id.isEmpty, id != Identity.id else { continue }
            if let existing = next[id], existing.endpoint.serviceInterface == nil { continue }
            next[id] = DiscoveredHost(id: id, name: txt["name"] ?? id, plat: txt["plat"] ?? "", endpoint: r.endpoint)
        }
        let appeared = Set(next.keys.filter { hosts[$0]?.endpoint != next[$0]?.endpoint })
        hosts = next
        onChange?(appeared)
    }
}

nonisolated private extension NWEndpoint {
    var serviceInterface: NWInterface? {
        if case .service(_, _, _, let i) = self { return i }
        return nil
    }
}
