// Self-check for AirBridge/Calls/CallLogic.swift and the §13.4 parts of PhoneNotifModel.swift.
// Run: tools/callcheck/run.sh   (exit code 0 = all passed). Never launches the app.
import Foundation

var passed = 0, failed = 0
func check(_ ok: Bool, _ what: String) {
    if ok { passed += 1 } else { failed += 1; print("FAIL: \(what)") }
}
func wire(_ json: String) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
}
let t0 = Date(timeIntervalSince1970: 1_762_080_000)

func presence(screen: Bool = true, locked: Bool = false, idle: Int64? = nil, touch: Bool = true, at: Date = t0) -> PhonePresenceReport {
    PhonePresenceReport(screenOn: screen, locked: locked, idleMs: idle, touchKnown: touch, receivedAt: at)
}
func inputs(_ mode: RingMode = .smart, macIdle: TimeInterval? = 60, macAvailable: Bool = true, phone: PhonePresenceReport? = nil,
            prox: Proximity = .unknown, now: Date = t0) -> RingInputs {
    RingInputs(mode: mode, macIdle: macIdle, macAvailable: macAvailable, phone: phone, proximity: prox, now: now)
}

// MARK: ring policy — the four cases from the user's rules

do {
    let a = RingPolicy.decide(inputs(macIdle: 3))
    check(a == RingDecision(macRings: true, silencePhone: true, reason: .atMac), "(a) typed 3 s ago: Mac rings, phone quiet")
    let b = RingPolicy.decide(inputs(macIdle: 120, prox: .near))
    check(b == RingDecision(macRings: true, silencePhone: true, reason: .nearby), "(b) away from keyboard, phone within 1 m: Mac rings, phone quiet")
    let c = RingPolicy.decide(inputs(macIdle: 120, prox: .far))
    check(c == RingDecision(macRings: false, silencePhone: false, reason: .away), "(c) phone far: banner only")
    check(RingPolicy.decide(inputs(macIdle: 120, prox: .unknown)).macRings == false, "(c) no beacon, Mac idle 2 min = not near")
    check(RingPolicy.decide(inputs(macIdle: 30, prox: .unknown)).macRings == true, "(c) no beacon, Mac used 30 s ago = rings")
    check(RingPolicy.decide(inputs(macIdle: 30, prox: .far)).macRings == false, "(c) phone far, Mac used 30 s ago = silent")
    let d = RingPolicy.decide(inputs(macIdle: 120, phone: presence(idle: 2000), prox: .near))
    check(d == RingDecision(macRings: false, silencePhone: false, reason: .usingPhone), "(d) on the phone: Mac silent even when near")

    check(RingPolicy.decide(inputs(macIdle: 9.9)).reason == .atMac, "9.9 s counts as at the Mac")
    check(RingPolicy.decide(inputs(macIdle: 10, prox: .far)).reason == .away, "10 s does not")

    // Screen on and unlocked without tap tracking is a weak sign: recent Mac use wins; real taps still win.
    let weak = PhonePresenceReport(screenOn: true, locked: false, idleMs: nil, touchKnown: false, receivedAt: t0)
    check(RingPolicy.decide(inputs(macIdle: 12, phone: weak, prox: .unknown)).macRings, "weak phone signal loses to the Mac used 12 s ago (your 22:06 call)")
    check(!RingPolicy.decide(inputs(macIdle: 300, phone: weak, prox: .far)).macRings, "weak phone signal wins when the Mac is idle")
    check(!RingPolicy.decide(inputs(macIdle: 12, phone: presence(idle: 2000), prox: .far)).macRings, "a real tap on the phone wins")

    // No late start: the Mac decides within the first seconds; later it can only stop.
    var late = RingSession(inputs(macIdle: 300, prox: .far))
    let lateD = late.update(inputs(macIdle: 0, prox: .far, now: t0.addingTimeInterval(8)))
    check(!lateD.macRings && !lateD.silencePhone, "back at the Mac 8 s in: no late ring, phone not silenced")
    var early = RingSession(inputs(macIdle: 300, prox: .far))
    check(early.update(inputs(macIdle: 0, prox: .far, now: t0.addingTimeInterval(2))).macRings, "back at the Mac 2 s in: rings")
    check(RingPolicy.decide(inputs(macIdle: 2, macAvailable: false)).reason == .away, "locked / asleep Mac is not 'at the Mac'")
    check(RingPolicy.decide(inputs(macIdle: nil)).reason == .away, "unknown Mac idle")

    // Both in use: the more recent input wins.
    check(RingPolicy.decide(inputs(macIdle: 6, phone: presence(idle: 1000))).reason == .usingPhone, "phone touched after the Mac")
    check(RingPolicy.decide(inputs(macIdle: 1, phone: presence(idle: 4000))).reason == .atMac, "Mac touched after the phone")
    check(RingPolicy.decide(inputs(macIdle: 1, phone: presence(idle: nil, touch: false))).reason == .atMac,
          "phone in use without tap tracking loses to recent typing")

    // Phone presence details.
    check(!presence(screen: false, idle: 100).inUse(at: t0), "dark screen is not in use")
    check(!presence(locked: true, idle: 100).inUse(at: t0), "locked is not in use")
    check(presence(idle: nil, touch: false).inUse(at: t0), "screen on + unlocked without tap tracking = in use")
    check(!presence(idle: nil, touch: true).inUse(at: t0), "tap tracking but never touched = not in use")
    let old = presence(idle: 4000, at: t0)
    check(old.inUse(at: t0.addingTimeInterval(5)), "4 s + 5 s since it arrived = 9 s: in use")
    check(!old.inUse(at: t0.addingTimeInterval(6.5)), "time since arrival is added: 10.5 s, not in use")
    check(abs((old.idle(at: t0.addingTimeInterval(2)) ?? 0) - 6) < 0.001, "idle grows with time")

    // Modes.
    check(RingPolicy.decide(inputs(.always, macIdle: 500, phone: presence(idle: 0))) == RingDecision(macRings: true, silencePhone: true, reason: .modeAlways), "Always rings here, phone quiet")
    // Never both: whenever the Mac rings, the phone is told to keep quiet.
    for idle in [0.0, 5, 30, 120, 500] {
        for prox in [Proximity.near, .far, .unknown] {
            for mode in [RingMode.smart, .always] {
                let d = RingPolicy.decide(inputs(mode, macIdle: idle, prox: prox))
                check(!d.macRings || d.silencePhone, "never both ring (\(mode) idle \(idle) \(prox))")
            }
        }
    }
    check(RingPolicy.decide(inputs(.never, macIdle: 0, prox: .near)) == RingDecision(macRings: false, silencePhone: false, reason: .modeNever), "Never stays silent")
}

// MARK: ring session — latching while it rings

do {
    var s = RingSession(inputs(macIdle: 2, now: t0))
    check(s.decision.macRings && s.decision.silencePhone, "starts at the Mac")
    // The user watches the banner for 12 s without touching anything: still at the Mac.
    check(s.update(inputs(macIdle: 14, now: t0.addingTimeInterval(12))).macRings, "at-the-Mac latches")
    // Then picks the phone up and unlocks it.
    let picked = s.update(inputs(macIdle: 16, phone: presence(idle: 500, at: t0.addingTimeInterval(14)), now: t0.addingTimeInterval(14)))
    check(!picked.macRings && picked.reason == .usingPhone, "picking up the phone quiets the Mac")
    check(picked.silencePhone, "a phone already asked to keep quiet stays quiet")

    var away = RingSession(inputs(macIdle: 300, prox: .far, now: t0))
    check(!away.decision.macRings, "away: silent banner")
    check(away.update(inputs(macIdle: 1, prox: .far, now: t0.addingTimeInterval(3))).macRings, "user sits down at the Mac mid-ring: Mac rings")
    var near = RingSession(inputs(macIdle: 300, prox: .near, now: t0))
    check(near.update(inputs(macIdle: 300, prox: .unknown, now: t0.addingTimeInterval(2))).macRings, "nearby latches (one lost packet doesn't stop the ring)")
}

// MARK: proximity

do {
    var p = ProximityEstimator()
    check(p.state == .unknown && p.distance == nil, "starts unknown")
    // tx -7 dBm → -66 dBm at 1 m.
    p.add(rssi: -60, txPower: -7, at: t0)
    check(p.state == .unknown, "one packet isn't enough")
    p.add(rssi: -61, txPower: -7, at: t0.addingTimeInterval(1))
    check(p.state == .near, "≈ 0.5 m is near (\(p.distance ?? -1))")
    // Wanders to ~1.3 m: inside the hysteresis band, stays near.
    for i in 2..<10 { p.add(rssi: -68, txPower: -7, at: t0.addingTimeInterval(Double(i))) }
    check(p.state == .near, "1.2 m stays near (hysteresis), d=\(p.distance ?? -1)")
    for i in 10..<20 { p.add(rssi: -75, txPower: -7, at: t0.addingTimeInterval(Double(i))) }
    check(p.state == .far, "2.5 m is far, d=\(p.distance ?? -1)")
    // One outlier packet from up close doesn't flip it.
    p.add(rssi: -45, txPower: -7, at: t0.addingTimeInterval(20))
    check(p.state == .far, "a single strong outlier is ignored")
    check(p.tick(now: t0.addingTimeInterval(25)) == .far, "fresh enough")
    check(p.tick(now: t0.addingTimeInterval(40)) == .unknown, "nothing for 20 s: unknown")
    p.add(rssi: 127, txPower: nil, at: t0.addingTimeInterval(41))
    p.add(rssi: 0, txPower: nil, at: t0.addingTimeInterval(41))
    check(p.state == .unknown, "invalid RSSI values ignored")
    let d1 = ProximityEstimator.distance(rssi: -66, measuredAt1m: -66, exponent: 2.2)
    check(abs(d1 - 1) < 0.0001, "measured power = 1 m")
    check(ProximityEstimator.distance(rssi: -80, measuredAt1m: -66, exponent: 2.2) > 4, "weaker = farther")
}

// MARK: beacon id (shared vector with Android and Windows)

do {
    let key = Data((0..<16).map { UInt8($0) })
    let at = Date(timeIntervalSince1970: 1_762_080_300)
    check(BeaconCrypto.epoch(at, period: 900) == 1_957_867, "epoch")
    let id = BeaconCrypto.id(key: key, epoch: 1_957_867)
    check(id.map { String(format: "%02x", $0) }.joined() == "48dfa4903066889a", "HMAC id matches the Android vector")
    check(BeaconCrypto.matches(id, key: key, at: at.addingTimeInterval(900), period: 900), "next period still matches (clock skew)")
    check(!BeaconCrypto.matches(id, key: key, at: at.addingTimeInterval(1800), period: 900), "two periods later doesn't")
    check(!BeaconCrypto.matches(id, key: Data(repeating: 1, count: 16), at: at, period: 900), "another phone's key doesn't")
}

// MARK: caller text

do {
    check(CallerText.initials("Asha Kapoor") == "AK", "two initials")
    check(CallerText.initials("asha") == "A", "one initial")
    check(CallerText.initials("Dr. Asha R. Kapoor") == "DK", "first and last word")
    check(CallerText.initials("+91 98765 43210") == nil, "no initials for numbers")
    check(CallerText.initials("आशा कपूर") == "आ", "one letter for other scripts")
    check(CallerText.initials(nil) == nil && CallerText.initials("  ") == nil, "no name")
    check(CallerText.title(name: nil, number: "+91 98765 43210") == "+91 98765 43210", "number as title")
    check(CallerText.title(name: nil, number: nil) == "Unknown Caller", "unknown caller")
    check(CallerText.subtitle(label: "Mobile", phoneName: "V2404") == "Mobile · V2404", "label and phone")
    check(CallerText.subtitle(label: nil, phoneName: "V2404") == "V2404", "phone only")
    check(CallerText.duration(42) == "0:42" && CallerText.duration(725) == "12:05" && CallerText.duration(3723) == "1:02:03", "durations")
}

// MARK: wire (docs/test-vectors/call-experience.jsonl, built by the Android app)

do {
    let lines = (try? String(contentsOfFile: "docs/test-vectors/call-experience.jsonl", encoding: .utf8))?
        .split(separator: "\n").map(String.init) ?? []
    check(lines.count == 8, "vector file found (\(lines.count) lines)")
    if lines.count == 8 {
        let ringing = PhoneNotifWire.parseCall(wire(lines[0]), phone: "P")
        check(ringing?.state == .ringing && ringing?.name == "Asha Kapoor" && ringing?.label == "Mobile" && ringing?.number == "+91 98765 43210", "ringing with label")
        let active = PhoneNotifWire.parseCall(wire(lines[1]), phone: "P")
        check(active?.muted == true && active?.speaker == true && active?.since != nil, "active, muted, speaker")
        let photo = PhoneNotifWire.parseCallPhoto(wire(lines[2]))
        check(photo?.id == "c4-mxk2b0" && (photo?.jpg.prefix(2) == Data([0xFF, 0xD8])), "photo is a JPEG")
        let pr = PhonePresenceReport.parse(wire(lines[3]), at: t0)
        check(pr == PhonePresenceReport(screenOn: true, locked: false, idleMs: 2500, touchKnown: true, receivedAt: t0), "presence")
        if case .some(.some(let b)) = PhoneNotifWire.parseBeacon(wire(lines[4])) {
            check(b.key == Data((0..<16).map { UInt8($0) }) && b.uuid == BeaconCrypto.serviceUUID && b.period == 900, "beacon key")
        } else { check(false, "beacon on parses") }
        if case .some(.none) = PhoneNotifWire.parseBeacon(wire(lines[5])) { check(true, "") } else { check(false, "beacon off parses") }
        let ring = PhoneNotifWire.ring("c4-mxk2b0", silencePhone: true)
        let back = wire(lines[6])
        check(ring["t"] as? String == back["t"] as? String && ring["phone"] as? String == back["phone"] as? String && ring["id"] as? String == back["id"] as? String, "call_ring shape")
        let v = wire(lines[7])
        let key = Data(base64Encoded: v["key"] as? String ?? "") ?? Data()
        let id = BeaconCrypto.id(key: key, epoch: (v["epoch"] as? NSNumber)?.int64Value ?? 0)
        check(id.map { String(format: "%02x", $0) }.joined() == v["id"] as? String, "beacon vector")
    }
    // Hostile / odd input.
    check(PhonePresenceReport.parse(wire(#"{"t":"call_presence","screen":1,"locked":false}"#), at: t0) == nil, "integer is not a boolean")
    check(PhonePresenceReport.parse(wire(#"{"t":"call_presence","screen":true,"locked":false,"idle_ms":-5}"#), at: t0)?.idleMs == nil, "negative idle ignored")
    check(PhoneNotifWire.parseCallPhoto(wire(#"{"t":"call_photo","id":"c1","jpg":"%%%"}"#)) == nil, "bad base64")
    check(PhoneNotifWire.parseBeacon(wire(#"{"t":"call_beacon","on":true,"key":"c2hvcnQ="}"#)) == nil, "short key rejected")
    check(PhoneNotifWire.parseCall(wire(#"{"t":"call_state","id":"c1","state":"ringing","label":"   "}"#), phone: "P")?.label == nil, "blank label")
}

// MARK: phone audio routes (§13.5)

do {
    // Exactly what the Android app sends (CallRoutesTest builds the same list).
    let c = PhoneNotifWire.parseCall(wire(#"""
    {"t":"call_state","id":"c1","state":"active","since":5,"muted":false,"speaker":false,"can":["end","mute","speaker","route"],
     "routes":[{"id":"phone:earpiece","kind":"earpiece"},{"id":"phone:speaker","kind":"speaker"},
               {"id":"phone:bt:AA:BB:CC:DD:EE:01","kind":"bt","name":"Galaxy Buds2"},
               {"id":"phone:bt:AA:BB:CC:DD:EE:02","kind":"bt","name":"Shazan's MacBook Pro","computer":true},
               {"id":7,"kind":"bt"},{"kind":"bt"}],
     "route":"phone:bt:AA:BB:CC:DD:EE:01"}
    """#), phone: "P")
    check(c?.routes.count == 4, "routes parsed, malformed entries dropped")
    check(c?.route == "phone:bt:AA:BB:CC:DD:EE:01" && c?.can.contains("route") == true, "selected route")
    let menu = RouteText.menuRoutes(c?.routes ?? [])
    check(menu.map(\.id) == ["phone:earpiece", "phone:speaker", "phone:bt:AA:BB:CC:DD:EE:01"], "the Mac's own hands-free link is left out")
    check(RouteText.name(menu[0], phoneName: "V2404") == "V2404" && RouteText.name(menu[1], phoneName: "V2404") == "Speaker"
          && RouteText.name(menu[2], phoneName: "V2404") == "Galaxy Buds2", "route names")
    check(RouteText.symbol(menu[0]) == "iphone" && RouteText.symbol(menu[2]) == "earbuds"
          && RouteText.symbol(PhoneRoute(id: "x", kind: "bt", name: "AirPods Pro", computer: false)) == "airpodspro", "route symbols")
    let a = PhoneNotifWire.callRoute("c1", dev: "phone:speaker")
    check(a["op"] as? String == "route" && a["dev"] as? String == "phone:speaker", "route action")
    check(PhoneNotifWire.parseCall(wire(#"{"t":"call_state","id":"c1","state":"active"}"#), phone: "P")?.routes.isEmpty == true, "no routes from older phones")
}

print("callcheck: \(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
