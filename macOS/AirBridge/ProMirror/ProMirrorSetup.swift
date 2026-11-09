import Foundation

/// A phone set up for Pro mirroring (paired with this Mac's adb key).
nonisolated struct ProMirrorRecord: Codable, Equatable, Sendable {
    var peerId: String
    var name: String
    /// adb serial from the pairing guid / mDNS instance (`adb-<serial>-…`); finds the phone again when its
    /// IP or port changes.
    var serial: String?
    var host: String?
    var port: Int?
    var pairedAt: Date
    /// Keep the phone's own screen dark while mirroring.
    var screenOff = false
    /// Use Pro mirroring when it's reachable (off = always normal mirroring).
    var enabled = true
}

/// Saved records, keyed by AirBridge peer id. Thread-safe (UserDefaults).
nonisolated enum ProMirrorStore {
    static let key = "proMirror.records.v1"

    static func all() -> [String: ProMirrorRecord] {
        guard let d = UserDefaults.standard.data(forKey: key),
              let m = try? JSONDecoder().decode([String: ProMirrorRecord].self, from: d) else { return [:] }
        return m
    }

    static func record(_ peerId: String) -> ProMirrorRecord? { all()[peerId] }

    static func save(_ r: ProMirrorRecord) {
        var m = all()
        m[r.peerId] = r
        write(m)
    }

    static func update(_ peerId: String, _ f: (inout ProMirrorRecord) -> Void) {
        var m = all()
        guard var r = m[peerId] else { return }
        f(&r)
        m[peerId] = r
        write(m)
    }

    static func remove(_ peerId: String) {
        var m = all()
        m[peerId] = nil
        write(m)
    }

    private static func write(_ m: [String: ProMirrorRecord]) {
        if let d = try? JSONEncoder().encode(m) { UserDefaults.standard.set(d, forKey: key) }
        NotificationCenter.default.post(name: .proMirrorRecordsChanged, object: nil)
    }
}

extension Notification.Name {
    nonisolated static let proMirrorRecordsChanged = Notification.Name("AirBridgeProMirrorRecordsChanged")
}

/// The setup wizard as a pure state machine (tools/promirrorcheck drives it). The window feeds it events from
/// the user, mDNS and the phone app; it answers with the next step and side effects for the window to run.
nonisolated enum ProSetup {
    enum Step: Equatable {
        /// What Pro mirroring is, what it needs.
        case intro
        /// 1. Settings › About phone › tap Build number 7 times.
        case developerOptions
        /// 2. Developer options › Wireless debugging › On.
        case wirelessDebugging
        /// 3. "Pair device with pairing code": waiting for the phone's pairing service to show up.
        case waitingForPairing
        /// Pairing service found: type the 6 digits (`error` after a wrong code).
        case enterCode(ADBService, error: ProMirrorError?)
        case pairing(ADBService)
        /// Paired; connecting to the phone's debugging port (`serial` from the pairing reply, if any).
        case connecting(serial: String?)
        case done
        case failed(ProMirrorError, retry: Retry)
    }

    enum Retry: Equatable { case pairing, connecting(serial: String?) }

    enum Event: Equatable {
        case begin
        case next
        case back
        /// From the phone app (`adb_wifi_state`); nil = it couldn't tell.
        case phoneStatus(developerOptions: Bool?, wirelessDebugging: Bool?)
        /// Current `_adb-tls-pairing._tcp` services (resolved ones only).
        case pairingServices([ADBService])
        case submitCode(String)
        case paired(guid: String?)
        case pairFailed(ProMirrorError)
        case connected(serial: String, host: String, port: Int)
        case connectFailed(ProMirrorError)
        case retry
    }

    enum Effect: Equatable {
        case askPhoneStatus
        case pair(ADBService, code: String)
        /// Find the phone's connect service (by serial, else the pairing host) and `adb connect` it.
        case connect(serial: String?, pairingHost: String?)
        case save(serial: String, host: String, port: Int)
    }

    /// Context the machine needs but doesn't own.
    struct Context: Equatable {
        /// The phone's IP as AirBridge knows it (matches the right pairing service when there are several).
        var phoneIP: String?
        /// Host of the pairing service we used (the connect service has the same address).
        var pairingHost: String?
    }

    /// "483 920" / "483-920" → "483920"; nil unless exactly six digits.
    static func normalizeCode(_ s: String) -> String? {
        let digits = s.filter { $0.isASCII && $0.isNumber }
        let others = s.filter { !($0.isASCII && $0.isNumber) && $0 != " " && $0 != "-" }
        return digits.count == 6 && others.isEmpty ? String(digits) : nil
    }

    static func reduce(_ step: Step, _ event: Event, _ ctx: inout Context) -> (Step, [Effect]) {
        switch (step, event) {
        case (.intro, .begin), (.intro, .next):
            return (.developerOptions, [.askPhoneStatus])

        case (.developerOptions, .next): return (.wirelessDebugging, [.askPhoneStatus])
        case (.developerOptions, .back): return (.intro, [])
        case (.developerOptions, .phoneStatus(let dev, let wifi)):
            if wifi == true { return (.waitingForPairing, []) }
            return dev == true ? (.wirelessDebugging, []) : (step, [])

        case (.wirelessDebugging, .next): return (.waitingForPairing, [])
        case (.wirelessDebugging, .back): return (.developerOptions, [])
        case (.wirelessDebugging, .phoneStatus(_, let wifi)):
            return wifi == true ? (.waitingForPairing, []) : (step, [])

        case (.waitingForPairing, .back): return (.wirelessDebugging, [])

        // The pairing screen can be opened at any point; jump straight to the code.
        case (.developerOptions, .pairingServices(let list)), (.wirelessDebugging, .pairingServices(let list)),
             (.waitingForPairing, .pairingServices(let list)):
            guard let s = ADBService.match(list, serial: nil, ip: ctx.phoneIP, allowSingle: true) else { return (step, []) }
            return (.enterCode(s, error: nil), [])

        case (.enterCode(let s, _), .pairingServices(let list)):
            // The pairing dialog closed (or moved to a new port) on the phone.
            if let same = list.first(where: { $0.name == s.name }) {
                return same == s ? (step, []) : (.enterCode(same, error: nil), [])
            }
            return (.waitingForPairing, [])
        case (.enterCode(let s, _), .submitCode(let raw)):
            guard let code = normalizeCode(raw) else { return (.enterCode(s, error: .wrongCode), []) }
            ctx.pairingHost = s.host
            return (.pairing(s), [.pair(s, code: code)])
        case (.enterCode, .back): return (.waitingForPairing, [])

        case (.pairing, .paired(let guid)):
            let serial = guid.flatMap(ADBService.serial(fromInstance:))
            return (.connecting(serial: serial), [.connect(serial: serial, pairingHost: ctx.pairingHost)])
        case (.pairing(let s), .pairFailed(let e)):
            if e == .wrongCode { return (.enterCode(s, error: .wrongCode), []) }
            return (.failed(e, retry: .pairing), [])

        case (.connecting, .connected(let serial, let host, let port)):
            return (.done, [.save(serial: serial, host: host, port: port)])
        case (.connecting(let serial), .connectFailed(let e)):
            return (.failed(e, retry: .connecting(serial: serial)), [])

        case (.failed(_, .pairing), .retry): return (.waitingForPairing, [])
        case (.failed(_, .connecting(let serial)), .retry):
            return (.connecting(serial: serial), [.connect(serial: serial, pairingHost: ctx.pairingHost)])

        default:
            return (step, [])
        }
    }
}
