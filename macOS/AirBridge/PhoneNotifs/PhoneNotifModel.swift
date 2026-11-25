import Foundation

// Phone notifications and calls on this Mac (protocol.md §13): message parsing and the list/call state,
// without AppKit or the network so tools/notifcheck can test it on its own.

nonisolated enum PhoneNotifCaps {
    static let notifSource = "notif_source"
    static let notifSink = "notif_sink"
    static let callSource = "call_source"
    static let callSink = "call_sink"
    /// §13.4: presence, beacon and ring decisions.
    static let callRing = "call_ring"
}

nonisolated struct PhoneMsg: Equatable, Sendable {
    /// nil = the phone's user ("me").
    let from: String?
    let text: String
    let ts: Date
}

nonisolated struct PhoneAction: Equatable, Sendable {
    let index: Int
    let label: String
}

nonisolated struct PhoneNotif: Identifiable, Equatable, Sendable {
    let phoneId: String
    let key: String
    var pkg: String
    var app: String
    var title: String
    var text: String
    var sub: String
    var ts: Date
    var cat: String
    var conv: String?
    var msgs: [PhoneMsg]
    var canReply: Bool
    var replyLabel: String?
    var actions: [PhoneAction]
    var alert: Bool
    /// §13.2 `kind`: nil = an ordinary notification; "ongoing" (VPN, USB, music: can't be cleared) or "silent" = a status
    /// entry, listed like the phone's shade lists it but never a banner or a sound.
    var kind: String? = nil

    var id: String { PhoneNotif.makeId(phoneId, key) }
    /// Listed only: never a banner, a sound or a count.
    var isStatus: Bool { kind != nil }
    /// The phone won't clear it (an ongoing notification stays until its app ends it).
    var canClear: Bool { kind != "ongoing" }
    static func makeId(_ phone: String, _ key: String) -> String { phone + "|" + key }

    /// What a banner or list row shows as the main line.
    var headline: String { title.isEmpty ? app : title }
    var body: String { text.isEmpty ? (msgs.last?.text ?? "") : text }
}

nonisolated enum PhoneCallState: String, Sendable { case ringing, active, held, ended }

/// One place the phone can play a call (§13.5 `routes`).
nonisolated struct PhoneRoute: Equatable, Sendable {
    let id: String
    /// earpiece, speaker, wired, usb, bt, ble, hearing_aid
    let kind: String
    let name: String?
    /// The computer's own hands-free link (this Mac, shown under "This Mac" instead).
    let computer: Bool
}

nonisolated struct PhoneCall: Equatable, Sendable {
    let phoneId: String
    let id: String
    var state: PhoneCallState
    var name: String?
    var number: String?
    var since: Date?
    var muted: Bool
    var speaker: Bool
    var can: Set<String>
    /// The number's label in the phone's contacts ("Mobile", "Work"), §13.5.
    var label: String? = nil
    /// §13.5: where the phone can play the call, and the one it uses now.
    var routes: [PhoneRoute] = []
    var route: String? = nil

    /// "Asha", else the number, else "Unknown Caller".
    var caller: String { CallerText.title(name: name, number: number) }
}

/// What the phone says about forwarding (`notif_state`).
nonisolated struct PhoneRelayState: Equatable, Sendable {
    var notifs: Bool
    var calls: Bool
    var why: String?
}

nonisolated enum PhoneNotifWire {
    static let maxKey = 64

    private static func s(_ m: [String: Any], _ k: String) -> String? { m[k] as? String }
    private static func b(_ m: [String: Any], _ k: String) -> Bool? {
        // JSON numbers arrive as NSNumber: only a real boolean counts (an integer 1 is not `true`).
        if let n = m[k] as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() ? n.boolValue : nil }
        return m[k] as? Bool
    }
    private static func i64(_ m: [String: Any], _ k: String) -> Int64? {
        guard let n = m[k] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        guard d.isFinite, abs(d) < 9e15 else { return nil }
        return n.int64Value
    }
    private static func date(_ ms: Int64?) -> Date? { ms.map { Date(timeIntervalSince1970: Double($0) / 1000) } }
    private static func clip(_ s: String?, _ max: Int) -> String {
        let t = (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return t.count <= max ? t : String(t.prefix(max - 1)) + "…"
    }

    static func validKey(_ k: String?) -> String? {
        guard let k, !k.isEmpty, k.count <= maxKey else { return nil }
        return k
    }

    static func parsePost(_ m: [String: Any], phone: String) -> PhoneNotif? {
        guard s(m, "t") == "notif_post", let key = validKey(s(m, "key")) else { return nil }
        let msgs: [PhoneMsg] = ((m["msgs"] as? [Any]) ?? []).prefix(10).compactMap { any in
            guard let d = any as? [String: Any], let text = d["text"] as? String, !text.isEmpty else { return nil }
            let me = b(d, "me") == true
            return PhoneMsg(from: me ? nil : clip(d["from"] as? String, 80), text: clip(text, 1000),
                            ts: date(i64(d, "ts")) ?? Date())
        }
        let acts: [PhoneAction] = ((m["acts"] as? [Any]) ?? []).prefix(3).compactMap { any in
            guard let d = any as? [String: Any], let label = d["label"] as? String, !label.isEmpty,
                  let i = i64(d, "i"), i >= 0, i < 16 else { return nil }
            return PhoneAction(index: Int(i), label: clip(label, 40))
        }
        let pkg = clip(s(m, "pkg"), 200)
        // Unknown kinds from a newer phone are still status entries: listing quietly is the safe reading.
        let kind: String? = s(m, "kind").flatMap { $0.isEmpty ? nil : ($0 == "ongoing" || $0 == "silent" ? $0 : "silent") }
        return PhoneNotif(
            phoneId: phone, key: key, pkg: pkg,
            app: clip(s(m, "app"), 80).isEmpty ? pkg : clip(s(m, "app"), 80),
            title: clip(s(m, "title"), 200), text: clip(s(m, "text"), 2000), sub: clip(s(m, "sub"), 200),
            ts: date(i64(m, "ts")) ?? Date(), cat: s(m, "cat") ?? "other",
            conv: s(m, "conv").map { clip($0, 200) }.flatMap { $0.isEmpty ? nil : $0 },
            msgs: msgs, canReply: b(m, "reply") == true,
            replyLabel: s(m, "reply_label").map { clip($0, 40) },
            actions: acts, alert: kind == nil && (b(m, "alert") ?? true), kind: kind)
    }

    static func parseCall(_ m: [String: Any], phone: String) -> PhoneCall? {
        guard s(m, "t") == "call_state", let id = validKey(s(m, "id")),
              let st = s(m, "state").flatMap(PhoneCallState.init(rawValue:)) else { return nil }
        let can = Set(((m["can"] as? [Any]) ?? []).compactMap { $0 as? String })
        return PhoneCall(phoneId: phone, id: id, state: st,
                         name: s(m, "name").map { clip($0, 100) }, number: s(m, "number").map { clip($0, 40) },
                         since: date(i64(m, "since")), muted: b(m, "muted") == true, speaker: b(m, "speaker") == true,
                         can: can, label: s(m, "label").map { clip($0, 40) }.flatMap { $0.isEmpty ? nil : $0 },
                         routes: parseRoutes(m["routes"]), route: s(m, "route").flatMap { $0.count <= 80 ? $0 : nil })
    }

    static func parseRoutes(_ any: Any?) -> [PhoneRoute] {
        ((any as? [Any]) ?? []).prefix(12).compactMap { e in
            guard let d = e as? [String: Any], let id = d["id"] as? String, !id.isEmpty, id.count <= 80,
                  let kind = d["kind"] as? String, !kind.isEmpty else { return nil }
            let name = (d["name"] as? String).map { clip($0, 60) }.flatMap { $0.isEmpty ? nil : $0 }
            return PhoneRoute(id: id, kind: kind, name: name, computer: b(d, "computer") == true)
        }
    }

    static func callRoute(_ id: String, dev: String) -> [String: Any] { ["t": "call_action", "id": id, "op": "route", "dev": dev] }

    /// `call_photo` (§13.4): the caller's contact photo for call `id`, as JPEG bytes.
    static func parseCallPhoto(_ m: [String: Any]) -> (id: String, jpg: Data)? {
        guard s(m, "t") == "call_photo", let id = validKey(s(m, "id")), let b64 = s(m, "jpg"), b64.count <= 60_000,
              let data = Data(base64Encoded: b64), !data.isEmpty else { return nil }
        return (id, data)
    }

    /// `call_beacon` (§13.4): the phone's beacon key, or nil when it doesn't advertise.
    static func parseBeacon(_ m: [String: Any]) -> (key: Data, uuid: String, period: Int)?? {
        guard s(m, "t") == "call_beacon" else { return nil }
        guard b(m, "on") == true else { return .some(nil) }
        guard let k = s(m, "key").flatMap({ Data(base64Encoded: $0) }), k.count >= 16, k.count <= 64 else { return nil }
        let uuid = s(m, "uuid") ?? BeaconCrypto.serviceUUID
        let period = Int(i64(m, "period") ?? 900)
        return .some((k, uuid, min(max(period, 60), 86_400)))
    }

    /// K→S `call_ring` (§13.4): where call `id` rings.
    static func ring(_ id: String, silencePhone: Bool) -> [String: Any] {
        ["t": "call_ring", "id": id, "phone": silencePhone ? "silent" : "ring"]
    }

    static func parseState(_ m: [String: Any]) -> PhoneRelayState? {
        guard s(m, "t") == "notif_state" else { return nil }
        return PhoneRelayState(notifs: b(m, "notifs") == true, calls: b(m, "calls") == true, why: s(m, "why"))
    }

    // Sink → source.
    static func sub(notifs: Bool, calls: Bool) -> [String: Any] { ["t": "notif_sub", "notifs": notifs, "calls": calls] }
    static func dismiss(_ key: String) -> [String: Any] { ["t": "notif_dismiss", "key": key] }
    static func reply(_ key: String, _ text: String) -> [String: Any] {
        ["t": "notif_reply", "key": key, "text": String(text.prefix(4000))]
    }
    static func action(_ key: String, _ i: Int) -> [String: Any] { ["t": "notif_action", "key": key, "i": i] }
    static func open(_ key: String) -> [String: Any] { ["t": "notif_open", "key": key] }
    static func callAction(_ id: String, _ op: String) -> [String: Any] { ["t": "call_action", "id": id, "op": op] }
}

/// The notifications shown on this Mac, from every phone. Pure; the UI wrapper is `PhoneNotifCenter`.
nonisolated struct PhoneNotifStore {
    /// Per phone at most this many; the oldest go first.
    var maxPerPhone = 200
    private(set) var items: [String: PhoneNotif] = [:]
    /// §13.1 `notif_order`: each notification's place in its phone's shade (0 = top), by id.
    private(set) var rank: [String: Int] = [:]
    /// Ids the phone shows under its Silent header.
    private(set) var silentIds: Set<String> = []
    /// Phones that sent an order (older ones don't: their list falls back to newest first, status entries as Silent).
    private(set) var orderedPhones: Set<String> = []

    enum Change: Equatable {
        case none
        /// The phone's shade order or sections changed.
        case reordered
        /// `isNew` = first time this key is seen (vs. an update of one already listed).
        case posted(PhoneNotif, isNew: Bool)
        case removed([String])
    }

    /// Applies one source → sink message from [phone].
    mutating func apply(_ m: [String: Any], from phone: String) -> Change {
        switch m["t"] as? String {
        case "notif_post":
            guard let n = PhoneNotifWire.parsePost(m, phone: phone) else { return .none }
            let isNew = items[n.id] == nil
            items[n.id] = n
            _ = trim(phone) // Notification Center keeps its own limit for banners already delivered
            return .posted(n, isNew: isNew)
        case "notif_remove":
            guard let key = PhoneNotifWire.validKey(m["key"] as? String) else { return .none }
            return items.removeValue(forKey: PhoneNotif.makeId(phone, key)) != nil ? .removed([PhoneNotif.makeId(phone, key)]) : .none
        case "notif_order":
            let keys = ((m["keys"] as? [Any]) ?? []).prefix(1000).compactMap { PhoneNotifWire.validKey($0 as? String) }
            let silent = Set(((m["silent"] as? [Any]) ?? []).compactMap { PhoneNotifWire.validKey($0 as? String) })
            rank = rank.filter { !$0.key.hasPrefix(phone + "|") }
            silentIds = silentIds.filter { !$0.hasPrefix(phone + "|") }
            for (i, k) in keys.enumerated() where rank[PhoneNotif.makeId(phone, k)] == nil { rank[PhoneNotif.makeId(phone, k)] = i }
            for k in silent { silentIds.insert(PhoneNotif.makeId(phone, k)) }
            orderedPhones.insert(phone)
            return .reordered
        case "notif_reset":
            return removeAll(phone: phone)
        case "notif_state":
            if let st = PhoneNotifWire.parseState(m), !st.notifs { return removeAll(phone: phone) }
            return .none
        default:
            return .none
        }
    }

    mutating func removeAll(phone: String) -> Change {
        rank = rank.filter { !$0.key.hasPrefix(phone + "|") }
        silentIds = silentIds.filter { !$0.hasPrefix(phone + "|") }
        orderedPhones.remove(phone)
        let ids = items.values.filter { $0.phoneId == phone }.map(\.id)
        ids.forEach { items.removeValue(forKey: $0) }
        return ids.isEmpty ? .none : .removed(ids)
    }

    mutating func remove(id: String) -> Bool { items.removeValue(forKey: id) != nil }

    /// Puts back one taken out ahead of the phone (a clear the phone then refused).
    mutating func restore(_ n: PhoneNotif) { items[n.id] = n }

    /// Drops the oldest of [phone] beyond `maxPerPhone`; returns their ids.
    private mutating func trim(_ phone: String) -> [String] {
        let mine = items.values.filter { $0.phoneId == phone }
        guard mine.count > maxPerPhone else { return [] }
        let drop = mine.sorted { $0.ts < $1.ts }.prefix(mine.count - maxPerPhone).map(\.id)
        drop.forEach { items.removeValue(forKey: $0) }
        return drop
    }

    struct Group: Identifiable, Equatable {
        let phoneId: String
        let pkg: String
        let app: String
        let items: [PhoneNotif]
        var id: String { phoneId + "|" + pkg }
        var latest: Date { items.first?.ts ?? .distantPast }
    }

    /// Under the phone's Silent header: where the phone puts it, or (a phone that sends no order) any status entry.
    func isSilent(_ n: PhoneNotif) -> Bool {
        orderedPhones.contains(n.phoneId) ? silentIds.contains(n.id) : n.isStatus
    }

    /// Before [b] in the list: the phone's shade order; ones it hasn't placed yet (just posted) on top, newest first.
    private func precedes(_ a: PhoneNotif, _ b: PhoneNotif) -> Bool {
        switch (rank[a.id], rank[b.id]) {
        case let (x?, y?): return x != y ? x < y : a.ts > b.ts
        case (nil, _?): return true
        case (_?, nil): return false
        case (nil, nil): return a.ts > b.ts
        }
    }

    /// Grouped by device and app (as the device bundles them). `status` picks the main list (false) or the Silent section
    /// (true). One device (`device`, or the only one there is): exactly its shade order, a group sitting where its first
    /// notification sits. All devices together: newest group first across them (their orders can't be merged), each group
    /// still in its device's order inside.
    func groups(status: Bool = false, device: String? = nil) -> [Group] {
        var byApp: [String: [PhoneNotif]] = [:]
        for n in items.values where isSilent(n) == status && (device == nil || n.phoneId == device) {
            byApp[n.phoneId + "|" + n.pkg, default: []].append(n)
        }
        let list = byApp.values.map { list in
            let sorted = list.sorted(by: precedes)
            return Group(phoneId: sorted[0].phoneId, pkg: sorted[0].pkg, app: sorted[0].app, items: sorted)
        }
        let several = Set(list.map(\.phoneId)).count > 1
        guard several else { return list.sorted { precedes($0.items[0], $1.items[0]) } }
        // One consistent rule across devices (mixing it with each device's order would make the sort contradict itself).
        func latest(_ g: Group) -> Date { g.items.map(\.ts).max() ?? .distantPast }
        return list.sorted { a, b in latest(a) != latest(b) ? latest(a) > latest(b) : a.id < b.id }
    }

    /// Devices with something listed here, in a stable order.
    var devices: [String] { Array(Set(items.values.map(\.phoneId))).sorted() }

    /// Ordinary notifications only (status entries are not news).
    var count: Int { items.values.filter { !$0.isStatus }.count }
}

/// Which calls to show: one per phone; ended calls disappear.
nonisolated struct PhoneCallBoard {
    private(set) var calls: [String: PhoneCall] = [:]

    enum Change: Equatable { case none, shown(PhoneCall), updated(PhoneCall), ended(PhoneCall) }

    mutating func apply(_ m: [String: Any], from phone: String) -> Change {
        guard let c = PhoneNotifWire.parseCall(m, phone: phone) else { return .none }
        let old = calls[phone]
        if c.state == .ended {
            // An "ended" for a call we never showed (or an older one) changes nothing.
            guard let old, old.id == c.id else { return .none }
            calls.removeValue(forKey: phone)
            return .ended(c)
        }
        calls[phone] = c
        if let old, old.id == c.id { return old == c ? .none : .updated(c) }
        return .shown(c)
    }

    /// The phone disconnected: its call can no longer be controlled from here.
    mutating func phoneGone(_ phone: String) -> PhoneCall? { calls.removeValue(forKey: phone) }
}

/// A notification the user never wants on this Mac again: one app, exactly this title and text (spaces ignored). Not a type
/// or an id: "Battery saver is on" is blocked while the same app's other notifications still come through. The phone keeps
/// showing it; only this Mac stays quiet.
nonisolated struct PhoneNotifBlock: Codable, Hashable, Identifiable, Sendable {
    let pkg: String
    let app: String
    let title: String
    let text: String
    var id: String { pkg + "\u{1}" + title + "\u{1}" + text }

    init(pkg: String, app: String, title: String, text: String) {
        self.pkg = pkg
        self.app = app
        self.title = Self.normal(title)
        self.text = Self.normal(text)
    }

    init(_ n: PhoneNotif) { self.init(pkg: n.pkg, app: n.app, title: n.title, text: n.body) }

    /// Same app, same words: surrounding and repeated spaces and line breaks don't count, everything else does.
    func matches(_ n: PhoneNotif) -> Bool {
        n.pkg == pkg && Self.normal(n.title) == title && Self.normal(n.body) == text
    }

    static func normal(_ s: String) -> String {
        s.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    static func isBlocked(_ n: PhoneNotif, by blocks: [PhoneNotifBlock]) -> Bool { blocks.contains { $0.matches(n) } }
}
