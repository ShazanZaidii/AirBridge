import CryptoKit
import Foundation

// Where a phone call rings, and who is calling (protocol.md §13.4). Pure: no AppKit, no network, no clocks of its
// own, so tools/callcheck can test it on its own.

/// "Ring on this Mac" (Settings).
nonisolated enum RingMode: String, CaseIterable, Sendable {
    case smart, always, never
    static let key = "PhoneCallsRingMode"
    static var current: RingMode {
        UserDefaults.standard.string(forKey: key).flatMap(RingMode.init(rawValue:)) ?? .smart
    }
}

/// What a phone last said about its user (`call_presence`).
nonisolated struct PhonePresenceReport: Equatable, Sendable {
    var screenOn: Bool
    var locked: Bool
    /// Milliseconds since the user last touched or unlocked the phone, when it sent this; nil = not known.
    var idleMs: Int64?
    /// The phone sees individual taps (so `idleMs` is precise); without, "screen on and unlocked" stands for "in use".
    var touchKnown: Bool
    var receivedAt: Date

    static func parse(_ m: [String: Any], at: Date) -> PhonePresenceReport? {
        guard m["t"] as? String == "call_presence", let screen = callWireBool(m["screen"]), let locked = callWireBool(m["locked"]) else { return nil }
        var idle: Int64?
        if let n = m["idle_ms"] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite, n.doubleValue >= 0 {
            idle = min(n.int64Value, 86_400_000)
        }
        return PhonePresenceReport(screenOn: screen, locked: locked, idleMs: idle, touchKnown: callWireBool(m["touch"]) ?? false, receivedAt: at)
    }

    /// Seconds since the user last touched the phone, as of `now` (the time since it arrived is added).
    func idle(at now: Date) -> TimeInterval? {
        guard let idleMs else { return nil }
        return Double(idleMs) / 1000 + max(0, now.timeIntervalSince(receivedAt))
    }

    /// The user is on the phone right now.
    func inUse(at now: Date, window: TimeInterval = RingPolicy.activeWindow) -> Bool {
        guard screenOn, !locked else { return false }
        if touchKnown { return (idle(at: now) ?? .infinity) < window }
        return true
    }
}

nonisolated func callWireBool(_ any: Any?) -> Bool? {
    // JSON numbers arrive as NSNumber: only a real boolean counts (an integer 1 is not `true`).
    if let n = any as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() ? n.boolValue : nil }
    return any as? Bool
}

nonisolated enum Proximity: Equatable, Sendable { case near, far, unknown }

/// Everything the ring decision looks at, sampled at one moment.
nonisolated struct RingInputs: Sendable {
    var mode: RingMode
    /// Seconds since the last keyboard / mouse / trackpad input on this Mac; nil = unknown.
    var macIdle: TimeInterval?
    /// The Mac's screen is awake and unlocked.
    var macAvailable: Bool
    var phone: PhonePresenceReport?
    var proximity: Proximity
    var now: Date
}

nonisolated struct RingDecision: Equatable, Sendable {
    enum Reason: String, Sendable { case modeNever, modeAlways, atMac, usingPhone, nearby, away }
    /// This Mac plays the ringtone.
    var macRings: Bool
    /// Ask the phone to keep quiet for this call.
    var silencePhone: Bool
    var reason: Reason
}

/// The rules (the user's words): whenever the Mac rings the phone keeps quiet (never both); at the Mac → the
/// Mac rings; not at the Mac but the phone within about a metre → the Mac rings; phone farther away → the Mac shows the call silently; on the phone → silent here.
/// The call can be answered on either device in every case.
nonisolated enum RingPolicy {
    /// "At the Mac" / "using the phone" = input within this many seconds.
    static let activeWindow: TimeInterval = 10

    /// With no proximity reading at all (no beacon heard), input on the Mac within this many seconds stands in for
    /// "the phone is near": someone who just used the Mac is most likely sitting at it with their phone.
    static let recentWindow: TimeInterval = 60

    static func atMac(_ i: RingInputs) -> Bool { i.macAvailable && (i.macIdle ?? .infinity) < activeWindow }
    /// The phone is within about a metre, or (no reading) the Mac was just used.
    static func near(_ i: RingInputs) -> Bool {
        switch i.proximity {
        case .near: return true
        case .far: return false
        case .unknown: return i.macAvailable && (i.macIdle ?? .infinity) < recentWindow
        }
    }
    static func usingPhone(_ i: RingInputs) -> Bool {
        guard let p = i.phone, p.inUse(at: i.now) else { return false }
        if p.touchKnown { return true }
        // Without taps, "screen on and unlocked" is a weak sign (a phone lying on the desk, or one an app install
        // just woke): someone who used this Mac within the last minute is at the Mac.
        return !(i.macAvailable && (i.macIdle ?? .infinity) < recentWindow)
    }

    /// The Mac starts ringing only within this long after the call appears; later it can only stop (ringing
    /// 8 s into a call feels broken, not smart).
    static let startWindow: TimeInterval = 3

    static func decide(_ i: RingInputs) -> RingDecision {
        decide(mode: i.mode, atMac: atMac(i), macIdle: i.macIdle, usingPhone: usingPhone(i),
               phoneIdle: i.phone?.idle(at: i.now), near: near(i))
    }

    static func decide(mode: RingMode, atMac: Bool, macIdle: TimeInterval?, usingPhone: Bool, phoneIdle: TimeInterval?,
                       near: Bool) -> RingDecision {
        switch mode {
        case .never: return RingDecision(macRings: false, silencePhone: false, reason: .modeNever)
        case .always: return RingDecision(macRings: true, silencePhone: true, reason: .modeAlways)
        case .smart: break
        }
        var mac = atMac, phone = usingPhone
        if mac && phone {
            // Both in use: whichever was touched last. A phone that can't tell taps loses to recent typing here.
            if let p = phoneIdle, p < (macIdle ?? .infinity) { mac = false } else { phone = false }
        }
        if mac { return RingDecision(macRings: true, silencePhone: true, reason: .atMac) }
        if phone { return RingDecision(macRings: false, silencePhone: false, reason: .usingPhone) }
        if near { return RingDecision(macRings: true, silencePhone: true, reason: .nearby) }
        return RingDecision(macRings: false, silencePhone: false, reason: .away)
    }
}

/// One ringing call, re-checked while it rings. "At the Mac" and "nearby" only ever switch on (the user who looks at
/// the banner for 10 s without typing is still at the Mac); "using the phone" can take over at any time (picking
/// the phone up quiets the Mac). Once the phone was asked to keep quiet it stays quiet for this call.
nonisolated struct RingSession: Sendable {
    private(set) var decision: RingDecision
    private var sawAtMac: Bool
    private var sawNear: Bool
    private var lastMacInput: Date?
    private let startedAt: Date

    init(_ i: RingInputs) {
        startedAt = i.now
        sawAtMac = RingPolicy.atMac(i)
        sawNear = RingPolicy.near(i)
        lastMacInput = i.macIdle.map { i.now.addingTimeInterval(-$0) }
        decision = RingPolicy.decide(i)
    }

    @discardableResult mutating func update(_ i: RingInputs) -> RingDecision {
        if RingPolicy.atMac(i) { sawAtMac = true; lastMacInput = i.macIdle.map { i.now.addingTimeInterval(-$0) } }
        if RingPolicy.near(i) { sawNear = true }
        let macIdle = lastMacInput.map { i.now.timeIntervalSince($0) } ?? i.macIdle
        var d = RingPolicy.decide(mode: i.mode, atMac: sawAtMac, macIdle: macIdle, usingPhone: RingPolicy.usingPhone(i),
                                  phoneIdle: i.phone?.idle(at: i.now), near: sawNear)
        if decision.silencePhone { d.silencePhone = true }
        // Too late to start ringing now: the call keeps showing silently, and the phone keeps ringing.
        if d.macRings && !decision.macRings && i.now.timeIntervalSince(startedAt) > RingPolicy.startWindow && i.mode == .smart {
            d = RingDecision(macRings: false, silencePhone: decision.silencePhone, reason: decision.reason)
        }
        decision = d
        return d
    }
}

// MARK: Proximity

/// Distance to the phone from its beacon's signal strength: outliers dropped (median of the last few seconds),
/// smoothed, converted with the advertised transmit power, and turned into near / far with hysteresis so the
/// answer doesn't flicker around one metre.
nonisolated struct ProximityEstimator: Sendable {
    /// Enter "near" below this distance (m), leave it above `farAbove`.
    var nearBelow = 1.0
    var farAbove = 1.6
    /// No packet for this long → unknown.
    var staleAfter: TimeInterval = 12
    /// Signal at 1 m = advertised transmit power + this (phone antennas, body, the Mac's antenna). iBeacon-style.
    var lossAt1m = -59.0
    /// Transmit power when the phone doesn't advertise it (Android "medium").
    var defaultTx = -7.0
    /// Indoor path-loss exponent.
    var exponent = 2.2
    var window: TimeInterval = 6
    var alpha = 0.35

    private var samples: [(at: Date, rssi: Double)] = []
    private var smoothed: Double?
    private var tx: Double?
    private(set) var state: Proximity = .unknown
    private(set) var lastAt: Date?

    init() {}

    static func distance(rssi: Double, measuredAt1m: Double, exponent: Double) -> Double {
        pow(10, (measuredAt1m - rssi) / (10 * exponent))
    }

    var distance: Double? {
        guard let smoothed else { return nil }
        return Self.distance(rssi: smoothed, measuredAt1m: (tx ?? defaultTx) + lossAt1m, exponent: exponent)
    }

    mutating func add(rssi: Int, txPower: Int?, at: Date) {
        // 127 = "not available" in CoreBluetooth; anything ≥ 0 or below -110 is noise.
        guard rssi < 0, rssi > -110 else { return }
        if let t = txPower, t > -40, t < 21 { tx = Double(t) }
        lastAt = at
        samples.append((at, Double(rssi)))
        samples.removeAll { at.timeIntervalSince($0.at) > window }
        guard samples.count >= 2 else { return }
        let sorted = samples.map(\.rssi).sorted()
        let median = sorted.count % 2 == 1 ? sorted[sorted.count / 2] : (sorted[sorted.count / 2 - 1] + sorted[sorted.count / 2]) / 2
        smoothed = smoothed.map { $0 + alpha * (median - $0) } ?? median
        guard let d = distance else { return }
        switch state {
        case .near: if d > farAbove { state = .far }
        case .far, .unknown: state = d < nearBelow ? .near : .far
        }
    }

    /// Ages the estimate: nothing heard for a while = unknown (out of range, Bluetooth off, phone asleep).
    @discardableResult mutating func tick(now: Date) -> Proximity {
        if let lastAt, now.timeIntervalSince(lastAt) <= staleAfter { return state }
        samples.removeAll(); smoothed = nil; state = .unknown
        return state
    }
}

// MARK: Beacon id

/// The phone's rotating beacon id: first 8 bytes of HMAC-SHA256(key, "airbridge-beacon" ‖ epoch, 8 bytes big-endian),
/// epoch = unix seconds / period. The key reaches only paired computers (`call_beacon`, inside the encrypted session).
nonisolated enum BeaconCrypto {
    static let serviceUUID = "754338F5-9682-4D7A-9A60-84968B058DDE"
    static let length = 8

    static func epoch(_ date: Date, period: Int) -> Int64 {
        Int64((date.timeIntervalSince1970 / Double(max(60, period))).rounded(.down))
    }

    static func id(key: Data, epoch: Int64) -> Data {
        var msg = Data("airbridge-beacon".utf8)
        withUnsafeBytes(of: epoch.bigEndian) { msg.append(contentsOf: $0) }
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: SymmetricKey(data: key))
        return Data(mac).prefix(length)
    }

    /// The id belongs to this key now (one period of clock difference either way is allowed).
    static func matches(_ id: Data, key: Data, at date: Date, period: Int) -> Bool {
        guard id.count >= length else { return false }
        let e = epoch(date, period: period)
        let probe = id.prefix(length)
        return (e - 1 ... e + 1).contains { Self.id(key: key, epoch: $0) == probe }
    }
}

// MARK: Who is calling

nonisolated enum CallerText {
    /// "Asha Kapoor" → "AK", "asha" → "A"; nil for numbers and when there's no name (a person glyph is shown).
    static func initials(_ name: String?) -> String? {
        guard let name = name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
        if name.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "+()-. 0123456789").contains($0) }) { return nil }
        let words = name.split(whereSeparator: { $0.isWhitespace }).filter { w in w.first.map { $0.isLetter } ?? false }
        guard let first = words.first?.first else { return nil }
        if words.count >= 2, let last = words.last?.first, isLatin(first), isLatin(last) {
            return (String(first) + String(last)).uppercased()
        }
        return String(first).uppercased()
    }

    private static func isLatin(_ c: Character) -> Bool {
        c.unicodeScalars.allSatisfy { $0.value < 0x250 }
    }

    /// The caller's name, else the number, else "Unknown Caller".
    static func title(name: String?, number: String?) -> String {
        if let n = name?.trimmingCharacters(in: .whitespaces), !n.isEmpty { return n }
        if let n = number?.trimmingCharacters(in: .whitespaces), !n.isEmpty { return n }
        return "Unknown Caller"
    }

    /// Under the name: "Mobile · V2404" for a contact, the phone's name otherwise (as macOS shows "iPhone").
    static func subtitle(label: String?, phoneName: String) -> String {
        if let l = label?.trimmingCharacters(in: .whitespaces), !l.isEmpty { return "\(l) · \(phoneName)" }
        return phoneName
    }

    /// "0:42", "12:05", "1:02:03".
    static func duration(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        if s >= 3600 { return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) }
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: Phone audio routes

nonisolated enum RouteText {
    /// How a phone route is called in the audio menu: the earpiece is the phone itself ("V2404"), as a Mac calls it "iPhone".
    static func name(_ r: PhoneRoute, phoneName: String) -> String {
        switch r.kind {
        case "earpiece": return phoneName
        case "speaker": return "Speaker"
        case "wired": return r.name ?? "Headphones"
        case "usb": return r.name ?? "USB Headset"
        case "hearing_aid": return r.name ?? "Hearing Aid"
        default: return r.name ?? "Bluetooth Headset"
        }
    }

    static func symbol(_ r: PhoneRoute) -> String {
        let n = (r.name ?? "").lowercased()
        switch r.kind {
        case "earpiece": return "iphone"
        case "speaker": return "speaker.wave.2"
        case "hearing_aid": return "ear"
        case "wired", "usb": return "headphones"
        default:
            if n.contains("airpods max") { return "airpodsmax" }
            if n.contains("airpods pro") { return "airpodspro" }
            if n.contains("airpods") { return "airpods" }
            if n.contains("buds") { return "earbuds" }
            if n.contains("car") || n.contains("auto") { return "car" }
            return "headphones"
        }
    }

    /// The phone routes for the menu: the computer's own hands-free link left out (it is "This Mac").
    static func menuRoutes(_ routes: [PhoneRoute]) -> [PhoneRoute] { routes.filter { !$0.computer } }
}
