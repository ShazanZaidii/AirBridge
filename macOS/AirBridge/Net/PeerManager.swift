import Foundation
import Network

/// Runs `block` on the main actor (FIFO with other main-queue work).
nonisolated func onMain(_ block: @escaping @MainActor () -> Void) {
    DispatchQueue.main.async { MainActor.assumeIsolated(block) }
}

/// UI row for a paired device.
nonisolated struct DeviceRow: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let plat: String
    let connected: Bool
    let outbound: Bool
    let status: String?
}

/// Older entries that look like earlier installs of a device that just connected, offered to the user for removal.
nonisolated struct StaleOffer: Equatable, Sendable {
    let name: String
    let ids: [String]
}

/// UI row for an unpaired host found over mDNS.
nonisolated struct HostRow: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let plat: String
    let status: String?
}

/// Owns the listener, discovery, all sessions and outbound connectors, and implements `PeerTransport`.
/// All mutable state is confined to `queue`, except the ready-peer snapshot (guarded by `snapLock`).
nonisolated final class PeerManager: PeerTransport, @unchecked Sendable {
    static let shared = PeerManager()

    let queue: DispatchQueue
    private let server: Server
    private let discovery: Discovery
    private var pending: [ObjectIdentifier: Session] = [:]
    private var ready: [String: Session] = [:]
    private var connectors: [String: OutboundConnector] = [:]
    private var pathMonitor: NWPathMonitor?
    private var pathSignature: String?
    private var localIP: String?
    private var started = false

    private let snapLock = NSLock()
    private var snapshot: [PeerInfo] = []

    private static let outboundKey = "AirBridgeOutboundPeers"
    private static let lastAddressKey = "AirBridgeLastAddresses"
    private static let maxPending = 32
    /// At most this many incoming confirm-mode prompts at once; more get `pair_fail locked`.
    static let maxIncomingPrompts = 3

    private init() {
        queue = DispatchQueue(label: "com.shazan.airbridge.net", qos: .userInitiated)
        server = Server(queue: queue)
        discovery = Discovery(queue: queue)
    }

    // MARK: Lifecycle

    func start() {
        PeerBus.shared.transport = self
        queue.async { [self] in
            guard !started else { return }
            started = true
            // Screen placements left behind by devices that are no longer paired (every reinstall used to leave one).
            let paired = Set(PeerStore.shared.all().map(\.id))
            if !paired.isEmpty { Layout.shared.prune(keeping: paired) }
            localIP = NetworkUtils.getLocalIPAddress()
            Pairing.shared.onChange = { [weak self] _ in self?.queue.async { self?.publishPairing() } }
            Pairing.shared.start()
            server.onConnection = { [weak self] conn in self?.accept(conn) }
            server.onPortChange = { [weak self] _ in self?.publishPairing(); self?.publishUI() }
            server.start()
            discovery.onChange = { [weak self] appeared in self?.discoveryChanged(appeared) }
            discovery.start()
            for id in outboundIds where PeerStore.shared.hasKey(id) { connector(for: id).kick() }
            startPathMonitor()
            publishUI()
        }
    }

    /// Best-effort `bye` to everyone before the app exits.
    func shutdown() {
        queue.sync {
            for s in ready.values { s.close(bye: "shutdown") }
            for s in pending.values { s.close(bye: nil) }
            server.stop()
        }
        Thread.sleep(forTimeInterval: 0.2)
    }

    // MARK: PeerTransport (any thread)

    @discardableResult
    func send(to peerId: String, _ msg: [String: Any]) -> Bool {
        snapLock.lock()
        let isReady = snapshot.contains { $0.id == peerId }
        snapLock.unlock()
        guard isReady, let data = Proto.encode(msg) else { return false }
        queue.async { [self] in ready[peerId]?.sendText(data) }
        return true
    }

    func broadcast(_ msg: [String: Any]) {
        guard let data = Proto.encode(msg) else { return }
        queue.async { [self] in for s in ready.values { s.sendText(data) } }
    }

    @discardableResult
    func sendBinary(to peerId: String, _ frame: Data) -> Bool {
        snapLock.lock()
        let isReady = snapshot.contains { $0.id == peerId }
        snapLock.unlock()
        guard isReady, frame.count <= Proto.maxBinaryFrame, let kind = frame.first, kind != SecureChannel.envelopeKind else {
            return false
        }
        queue.async { [self] in ready[peerId]?.sendBinary(frame) { _ in } }
        return true
    }

    func peers() -> [PeerInfo] {
        snapLock.lock(); defer { snapLock.unlock() }
        return snapshot
    }

    // MARK: Services (any thread)

    func broadcastClip(_ msg: [String: Any]) {
        guard let data = Proto.encode(msg) else { return }
        queue.async { [self] in
            for s in ready.values where s.peerCaps.contains("clip") { s.sendText(data) }
        }
    }

    /// True when at least one connected peer can take a clipboard image or files (§5.1).
    func anyClipMediaPeer() -> Bool { peers().contains { $0.caps.contains("clip_media") } }

    /// Sends `urls` as a clipboard transfer (§5.1) to every ready peer that advertised `clip_media`.
    /// `cleanup` runs once the last of those transfers has ended (or at once when there is none).
    func broadcastClipMedia(_ urls: [URL], cleanup: @escaping () -> Void) {
        queue.async { [self] in
            let targets = ready.values.filter { $0.peerCaps.contains("clip_media") }
            guard !targets.isEmpty else { cleanup(); return }
            let left = CountDown(targets.count, then: cleanup)
            for s in targets {
                guard let t = s.transfers else { left.one(); continue }
                t.send(urls: urls, clip: true) { left.one() }
            }
        }
    }

    func sendFiles(_ urls: [URL], to peerId: String, cleanup: (() -> Void)?) {
        queue.async { [self] in
            guard let t = ready[peerId]?.transfers else {
                cleanup?()
                onMain { AppModel.shared.notice("That device is no longer connected") }
                return
            }
            t.send(urls: urls, cleanup: cleanup)
        }
    }

    func cancelTransfer(key: String) {
        guard let (peerId, id, incoming) = TransferRegistry.lookup(key) else { return }
        queue.async { [self] in
            if incoming { ready[peerId]?.transfers?.cancelIncoming(id) } else { ready[peerId]?.transfers?.cancelOutgoing(id) }
        }
    }

    // MARK: User actions (any thread)

    /// Pair with a discovered host by numeric comparison (§3.3, mode `confirm`): both devices show the
    /// same 6 digits. `hint` is an address to use when mDNS has nothing (tests, manual entry).
    func pairConfirm(hostId: String, hint: (host: String, port: UInt16)? = nil) {
        queue.async { [self] in
            let c = connector(for: hostId)
            c.pairing = .confirm
            if let hint { c.hint = hint }
            c.paused = false
            c.kick(resetBackoff: true)
            publishUI()
        }
    }

    /// The user answered a confirm-mode prompt (Accept/Confirm = true, Reject/Cancel = false).
    func respondToPairing(promptId: String, accept: Bool) {
        queue.async { [self] in
            pending.values.first { $0.prompt?.id == promptId }?.userResponded(accept: accept)
        }
    }

    /// Pair from an `airbridge://pair?...` link (QR payload).
    func pair(link: URL) {
        guard let comps = URLComponents(url: link, resolvingAgainstBaseURL: false) else { return }
        var q: [String: String] = [:]
        for item in comps.queryItems ?? [] { q[item.name] = item.value }
        guard q["v"] == String(Proto.version), let id = q["id"], !id.isEmpty, id != Identity.id,
              let k = q["k"].flatMap(Proto.fromBase64url), k.count == 32 else {
            onMain { AppModel.shared.notice("Invalid pairing link") }
            return
        }
        let hint: (String, UInt16)? = {
            guard let a = q["addr"], let p = q["port"].flatMap(UInt16.init) else { return nil }
            return (a, p)
        }()
        queue.async { [self] in
            let c = connector(for: id)
            c.pairing = .qr(k)
            c.hint = hint
            c.paused = false
            c.kick(resetBackoff: true)
            publishUI()
        }
    }

    func connect(peerId: String) {
        queue.async { [self] in
            guard PeerStore.shared.hasKey(peerId) else { return }
            let c = connector(for: peerId)
            c.paused = false
            c.kick(resetBackoff: true)
            publishUI()
        }
    }

    func disconnect(peerId: String) {
        queue.async { [self] in
            connectors[peerId]?.pause()
            ready[peerId]?.close(bye: "user")
            publishUI()
        }
    }

    /// "Forget device": `unpair` if connected, then delete locally (§3.5).
    func forget(peerId: String) {
        queue.async { [self] in
            if let s = ready[peerId] {
                s.send(["t": "unpair"])
                s.close(bye: nil)
            }
            removePeer(peerId)
        }
    }

    func rename(to name: String) {
        Identity.setName(name)
        queue.async { [self] in
            server.refreshService()
            let msg: [String: Any] = ["t": "name", "name": Identity.name]
            for s in ready.values { s.send(msg) }
            publishPairing()
            publishUI()
        }
    }

    // MARK: Session callbacks (queue)

    private func accept(_ conn: NWConnection) {
        guard pending.count < PeerManager.maxPending else { conn.cancel(); return }
        let s = Session(ws: WebSocket(connection: conn, role: .server, queue: queue), role: .host,
                        queue: queue, manager: self)
        pending[ObjectIdentifier(s)] = s
        s.start()
    }

    fileprivate func track(_ s: Session) { pending[ObjectIdentifier(s)] = s }

    func sessionReady(_ s: Session) {
        pending[ObjectIdentifier(s)] = nil
        if let old = ready[s.peerId], old !== s { old.close(bye: "replaced") }
        ready[s.peerId] = s
        if s.role == .client {
            var ids = outboundIds
            if !ids.contains(s.peerId) { ids.append(s.peerId); outboundIds = ids }
            if let a = s.remoteAddress { setLastAddress(s.peerId, "\(a.host)|\(a.port)") }
        }
        connectors[s.peerId]?.sessionReady(s)
        tidyOlderEntries(for: s)
        publishPeers()
        publishUI()
    }

    // MARK: Entries left by reinstalls

    private static let dismissedKey = "AirBridge.staleDismissed"

    private func entries() -> [StaleDevices.Entry] {
        PeerStore.shared.all().map { StaleDevices.Entry(id: $0.id, name: $0.name, plat: $0.plat, hw: $0.hw) }
    }

    /// A device just connected. If it is the same physical device as older entries (same fingerprint), those entries are
    /// dead: replace them, and the screen placement you gave the device moves to its new id. Older entries from before
    /// fingerprints existed can't be proven, so the user is asked about them once instead.
    private func tidyOlderEntries(for s: Session) {
        guard let hw = s.peerHw, PeerStore.shared.hasKey(s.peerId) else { return }
        PeerStore.shared.setHw(id: s.peerId, hw: hw)
        let connected = Set(ready.keys)
        let gone = StaleDevices.superseded(current: s.peerId, hw: hw, among: entries(), connected: connected)
        for e in gone {
            Layout.shared.migrate(from: e.id, to: s.peerId)
            removePeer(e.id)
        }
        if !gone.isEmpty {
            let name = s.peerName
            onMain { AppModel.shared.notice("\(name) was reinstalled: its old entry was replaced") }
        }
        let me = StaleDevices.Entry(id: s.peerId, name: s.peerName, plat: s.peerPlat, hw: hw)
        let dismissed = Set(UserDefaults.standard.stringArray(forKey: Self.dismissedKey) ?? [])
        let maybe = StaleDevices.candidates(current: me, among: entries(), connected: connected, dismissed: dismissed)
        let offer = maybe.isEmpty ? nil : StaleOffer(name: s.peerName, ids: maybe.map(\.id))
        onMain { AppModel.shared.staleOffer = offer }
    }

    /// The user's answer to a [StaleOffer]: remove those entries (their screen placement moves to the device that is
    /// connected now) or keep them and don't ask again.
    func resolveStale(_ offer: StaleOffer, remove: Bool) {
        queue.async { [self] in
            if remove {
                let current = ready.values.first { $0.peerName == offer.name && $0.peerHw != nil }?.peerId
                for id in offer.ids {
                    if let current { Layout.shared.migrate(from: id, to: current) }
                    removePeer(id)
                }
            } else {
                var d = Set(UserDefaults.standard.stringArray(forKey: Self.dismissedKey) ?? [])
                d.formUnion(offer.ids)
                UserDefaults.standard.set(Array(d), forKey: Self.dismissedKey)
            }
            onMain { AppModel.shared.staleOffer = nil }
            publishUI()
        }
    }

    func sessionClosed(_ s: Session, wasReady: Bool) {
        pending[ObjectIdentifier(s)] = nil
        let id = s.expectedPeerId ?? s.peerId
        if !s.peerId.isEmpty, ready[s.peerId] === s {
            ready[s.peerId] = nil
            publishPeers()
        }
        if let c = connectors[id] {
            c.sessionEnded(s, wasReady: wasReady)
            if !c.isUseful { connectors[id] = nil }
        }
        publishUI()
    }

    func peerUnpairedUs(_ s: Session) {
        let name = s.peerName
        removePeer(s.peerId)
        onMain { AppModel.shared.notice("\(name) unpaired this Mac") }
    }

    func peerRenamed(_ s: Session) {
        PeerStore.shared.rename(id: s.peerId, name: s.peerName)
        publishPeers()
        publishUI()
    }

    func hasReady(_ peerId: String) -> Bool { ready[peerId] != nil }

    var incomingPromptCount: Int { pending.values.filter(\.hasIncomingPrompt).count }

    func pairPromptChanged(_ p: PairPrompt) {
        onMain { AppModel.shared.showPairPrompt(p) }
    }

    func pairPromptEnded(_ id: String) {
        onMain { AppModel.shared.endPairPrompt(id) }
    }

    func endpoint(for peerId: String, hint: (host: String, port: UInt16)?) -> NWEndpoint? {
        if let h = discovery.hosts[peerId] { return h.endpoint }
        let fallback = hint ?? lastAddress(peerId)
        guard let fb = fallback, let port = NWEndpoint.Port(rawValue: fb.port) else { return nil }
        return .hostPort(host: NWEndpoint.Host(fb.host), port: port)
    }

    private func removePeer(_ id: String) {
        PeerStore.shared.delete(id)
        ScreenTimeHub.shared.forget(deviceId: id)
        Layout.shared.remove(id)
        outboundIds = outboundIds.filter { $0 != id }
        setLastAddress(id, nil)
        connectors[id]?.pause()
        connectors[id] = nil
        publishUI()
    }

    private func connector(for id: String) -> OutboundConnector {
        if let c = connectors[id] { return c }
        let c = OutboundConnector(peerId: id, manager: self, queue: queue)
        connectors[id] = c
        return c
    }

    fileprivate func pairingFinished(_ id: String, error: String?) {
        if let error {
            if !PeerStore.shared.hasKey(id) { outboundIds = outboundIds.filter { $0 != id } }
            onMain { AppModel.shared.notice(error) }
        }
    }

    private func discoveryChanged(_ appeared: Set<String>) {
        for id in appeared { connectors[id]?.kick(resetBackoff: true) }
        publishUI()
    }

    private func startPathMonitor() {
        let m = NWPathMonitor()
        m.pathUpdateHandler = { [weak self] path in self?.pathChanged(path) }
        m.start(queue: queue)
        pathMonitor = m
    }

    private func pathChanged(_ path: NWPath) {
        let ip = NetworkUtils.getLocalIPAddress()
        let sig = "\(path.status)|\(path.availableInterfaces.map(\.name).joined(separator: ","))|\(ip ?? "")"
        guard sig != pathSignature else { return }
        let first = pathSignature == nil
        pathSignature = sig
        localIP = ip
        if !first, path.status == .satisfied {
            server.ensureRunning()
            for c in connectors.values { c.kick(resetBackoff: true) }
        }
        publishPairing()
        publishUI()
    }

    // MARK: Persistence

    private var outboundIds: [String] {
        get { UserDefaults.standard.stringArray(forKey: PeerManager.outboundKey) ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: PeerManager.outboundKey) }
    }

    private func lastAddress(_ id: String) -> (host: String, port: UInt16)? {
        let all = UserDefaults.standard.dictionary(forKey: PeerManager.lastAddressKey) as? [String: String] ?? [:]
        guard let parts = all[id]?.split(separator: "|"), parts.count == 2, let p = UInt16(parts[1]) else { return nil }
        return (String(parts[0]), p)
    }

    private func setLastAddress(_ id: String, _ value: String?) {
        var all = UserDefaults.standard.dictionary(forKey: PeerManager.lastAddressKey) as? [String: String] ?? [:]
        all[id] = value
        UserDefaults.standard.set(all, forKey: PeerManager.lastAddressKey)
    }

    // MARK: Publishing

    private func publishPeers() {
        let list = ready.values.map(\.peerInfo).sorted { $0.name < $1.name }
        snapLock.lock()
        snapshot = list
        snapLock.unlock()
        PeerBus.shared.notifyPeers(list)
        DevicePowerSources.shared.peersChanged(list)
        ScreenTimeHub.shared.peersChanged(list)
        onMain { AppModel.shared.readyPeers = list }
    }

    fileprivate func publishUI() {
        let out = Set(outboundIds)
        let stored = PeerStore.shared.all()
        let storedIds = Set(stored.map(\.id))
        let devices = stored.map { p in
            DeviceRow(id: p.id, name: ready[p.id]?.peerName ?? p.name, plat: p.plat, connected: ready[p.id] != nil,
                      outbound: out.contains(p.id), status: ready[p.id] == nil ? connectors[p.id]?.status : nil)
        }
        let hosts = discovery.hosts.values
            .filter { !storedIds.contains($0.id) }
            .sorted { $0.name < $1.name }
            .map { HostRow(id: $0.id, name: $0.name, plat: $0.plat, status: connectors[$0.id]?.status) }
        let port = server.port
        let ip = localIP
        onMain { AppModel.shared.applyNetwork(devices: devices, hosts: hosts, ip: ip, port: port) }
    }

    private func publishPairing() {
        let snap = Pairing.shared.snapshot
        var link: String?
        if let port = server.port, let ip = localIP {
            let enc = { (s: String) in s.addingPercentEncoding(withAllowedCharacters: .urlUnreservedStrict) ?? "" }
            link = "airbridge://pair?v=\(Proto.version)&id=\(Identity.id)&name=\(enc(Identity.name))"
                + "&plat=\(Proto.platform)&addr=\(ip)&port=\(port)&k=\(Proto.base64url(snap.secret))"
        }
        let qrLink = link
        onMain { AppModel.shared.applyPairing(snap, link: qrLink) }
    }
}

nonisolated extension CharacterSet {
    static let urlUnreservedStrict = CharacterSet(charactersIn:
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
}

/// Dials one paired (or pairing) host and keeps it connected (§4.1). Runs on the manager queue.
nonisolated final class OutboundConnector: @unchecked Sendable {
    let peerId: String
    private unowned let manager: PeerManager
    private let queue: DispatchQueue
    var pairing: PairMode?
    var hint: (host: String, port: UInt16)?
    var paused = false
    private(set) var status: String?
    private var attempt: Session?
    private var backoff: TimeInterval = 1
    private var retryWork: DispatchWorkItem?

    init(peerId: String, manager: PeerManager, queue: DispatchQueue) {
        self.peerId = peerId
        self.manager = manager
        self.queue = queue
    }

    /// False once there is nothing left to do for this peer (not paired and not pairing).
    var isUseful: Bool { attempt != nil || pairing != nil || PeerStore.shared.hasKey(peerId) }

    func kick(resetBackoff: Bool = false) {
        if resetBackoff { backoff = 1 }
        retryWork?.cancel()
        retryWork = nil
        dial()
    }

    func pause() {
        paused = true
        retryWork?.cancel()
        retryWork = nil
        attempt?.close(bye: "user")
        status = nil
    }

    private func dial() {
        guard attempt == nil, !paused, !manager.hasReady(peerId) else { return }
        guard pairing != nil || PeerStore.shared.hasKey(peerId) else { return }
        guard let endpoint = manager.endpoint(for: peerId, hint: hint) else {
            status = "Not found on the network"
            scheduleRetry()
            return
        }
        let params = WebSocket.tcpParameters()
        (params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let conn = NWConnection(to: endpoint, using: params)
        let s = Session(ws: WebSocket(connection: conn, role: .client, queue: queue), role: .client, queue: queue,
                        manager: manager, expectedPeerId: peerId, pairing: pairing)
        attempt = s
        switch pairing {
        case .confirm: status = "Confirm the code on both devices…"
        case .qr: status = "Pairing…"
        case nil: status = "Connecting…"
        }
        manager.track(s)
        s.start()
    }

    func sessionReady(_ s: Session) {
        if s === attempt {
            attempt = nil
            if pairing != nil { manager.pairingFinished(peerId, error: nil) }
            pairing = nil
            hint = nil
        }
        backoff = 1
        retryWork?.cancel()
        retryWork = nil
        status = nil
    }

    func sessionEnded(_ s: Session, wasReady: Bool) {
        if s === attempt {
            attempt = nil
            let wasPairing = pairing != nil
            switch s.clientOutcome ?? .network {
            case .ready:
                break
            case .needsPairing:
                stop("Pairing required — pair again from Connect to host…", wasPairing: true)
            case .pairFailed("cancelled"):
                stop("Pairing cancelled", wasPairing: true)
            case .pairFailed(let reason):
                let text: String
                switch reason {
                case "locked": text = "too many attempts — wait a minute and try again"
                case "not_pairing": text = "that device is not accepting new pairings"
                case "rejected": text = "the other device declined"
                case "timeout": text = "nobody confirmed within a minute"
                default: text = "the codes did not match"
                }
                stop("Pairing failed: \(text)", wasPairing: true)
            case .badHost(let why):
                if wasPairing { stop(why, wasPairing: true) } else { status = why; scheduleRetry() }
            case .busy, .network:
                if wasPairing { stop("Could not reach the device", wasPairing: true) } else { scheduleRetry() }
            }
            return
        }
        guard wasReady, !manager.hasReady(peerId) else { return }
        switch s.closeReason {
        case "replaced", "unpair":
            break
        case "user":
            paused = true
        default:
            scheduleRetry()
        }
    }

    private func stop(_ message: String, wasPairing: Bool) {
        pairing = nil
        hint = nil
        status = PeerStore.shared.hasKey(peerId) ? message : nil
        manager.pairingFinished(peerId, error: message)
    }

    private func scheduleRetry() {
        guard !paused, isUseful else { return }
        let delay = backoff
        backoff = min(backoff * 2, 30)
        if status == nil || status == "Connecting…" { status = "Reconnecting…" }
        retryWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.retryWork = nil
            self.dial()
            self.manager.publishUI()
        }
        retryWork = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }
}

/// Runs `then` after `one()` has been called `count` times. Thread safe.
nonisolated final class CountDown: @unchecked Sendable {
    private let lock = NSLock()
    private var left: Int
    private let then: () -> Void
    init(_ count: Int, then: @escaping () -> Void) { left = count; self.then = then }
    func one() {
        lock.lock(); left -= 1; let fire = left == 0; lock.unlock()
        if fire { then() }
    }
}
