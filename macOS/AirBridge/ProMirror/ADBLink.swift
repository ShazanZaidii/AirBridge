import Foundation

/// The adb connection to a phone set up for Pro mirroring (paired with this Mac's adb key over Wireless debugging):
/// finds its debugging port, connects, and waits until adb reads it as a device. Used by Pro mirroring and by
/// "Mirror without asking" (MirrorTrust), which needs one shell command on the phone.
nonisolated enum ADBLink {
    /// The adb serial of a connected phone, or why it isn't reachable.
    static func connect(peerId: String, record: ProMirrorRecord) async -> Result<String, ProMirrorError> {
        guard ADB.binary != nil else { return .failure(.adbMissing) }
        _ = await ADB.run(["start-server"], timeout: 10)
        let onAirBridge = PeerBus.shared.peers().contains(where: { $0.id == peerId })

        // 1. Where is the phone's debugging port right now?
        let ip = ProMirrorHub.phoneIP(peerId)
        let svc = await ADBDiscovery.shared.wait(timeout: 2.5) { list in
            ADBService.match(list.filter { $0.kind == .connect }, serial: record.serial, ip: ip, allowSingle: false)
        }
        var candidates: [(String, Int)] = []
        if let svc, let h = svc.host, let p = svc.port { candidates.append((h, p)) }
        if let h = record.host, let p = record.port, !candidates.contains(where: { $0 == (h, p) }) { candidates.append((h, p)) }
        guard !candidates.isEmpty else { return .failure(onAirBridge ? .wirelessDebuggingOff : .notOnNetwork) }

        // 2. adb connect (TLS with this Mac's paired key).
        var lastError: ProMirrorError = .wirelessDebuggingOff
        var serial: String?
        for (h, p) in candidates {
            let out = await ADB.run(ADB.connect(host: h, port: p), timeout: 8)
            if out.timedOut { lastError = .timeout; continue }
            switch ADB.parseConnect(out.text, host: h, port: p) {
            case .connected(let s):
                serial = s
                ProMirrorStore.update(peerId) { r in
                    r.host = h; r.port = p
                    if r.serial == nil, let sv = svc?.serial { r.serial = sv }
                }
            case .failed(let e): lastError = e
            }
            if serial != nil { break }
        }
        guard let serial else {
            // Without a live mDNS record the saved port is just stale: Wireless debugging is off (or the phone is elsewhere).
            if svc == nil, lastError == .wirelessDebuggingOff || lastError == .timeout || lastError == .notOnNetwork {
                return .failure(onAirBridge ? .wirelessDebuggingOff : .notOnNetwork)
            }
            return .failure(lastError)
        }
        // A TLS transport reads "offline" for a moment while it authenticates.
        var stateError: ProMirrorError? = .offline
        for _ in 0..<8 {
            let st = await ADB.run(ADB.getState(serial), timeout: 6)
            stateError = ADB.parseState(st.text)
            if stateError != .offline { break }
            try? await Task.sleep(nanoseconds: 400_000_000)
        }
        if let e = stateError { return .failure(e) }
        return .success(serial)
    }
}
