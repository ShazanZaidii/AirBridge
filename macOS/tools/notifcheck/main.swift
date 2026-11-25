// Self-check for the pure phone-notification logic in AirBridge/PhoneNotifs/PhoneNotifModel.swift (protocol.md §13).
// Run: tools/notifcheck/run.sh   (exit code 0 = all passed). Never launches the app.
import Foundation

var passed = 0, failed = 0
func check(_ ok: Bool, _ what: String) {
    if ok { passed += 1 } else { failed += 1; print("FAIL: \(what)") }
}

/// Messages as the network half hands them over: decoded by JSONSerialization (NSNumber, not Swift Int/Bool).
func wire(_ json: String) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
}

// MARK: parsing

do {
    let n = PhoneNotifWire.parsePost(wire("""
    {"t":"notif_post","key":"k1","pkg":"com.whatsapp","app":"WhatsApp","title":"Asha","text":"See you at 6",
     "ts":1762070400000,"cat":"msg","conv":"Family","reply":true,"reply_label":"Reply","alert":true,
     "msgs":[{"from":"Asha","text":"Hi","ts":1},{"me":true,"text":"Hello","ts":2}],
     "acts":[{"i":1,"label":"Mark as read"},{"i":"x","label":"bad"},{"i":2,"label":""}]}
    """), phone: "P")
    check(n != nil, "post parses")
    check(n?.id == "P|k1" && n?.app == "WhatsApp" && n?.title == "Asha" && n?.conv == "Family", "post fields")
    check(n?.canReply == true && n?.replyLabel == "Reply" && n?.alert == true, "reply + alert")
    check(n?.msgs.count == 2 && n?.msgs[0].from == "Asha" && n?.msgs[1].from == nil, "messages, me = nil sender")
    check(n?.actions == [PhoneAction(index: 1, label: "Mark as read")], "only valid actions")
    check(abs((n?.ts.timeIntervalSince1970 ?? 0) - 1762070400) < 0.001, "timestamp in ms")

    let q = PhoneNotifWire.parsePost(wire(#"{"t":"notif_post","key":"k2","pkg":"p","reply":1,"alert":false}"#), phone: "P")
    check(q?.canReply == false, "an integer 1 is not a boolean true")
    check(q?.alert == false && q?.app == "p", "alert false, app falls back to pkg")
    check(PhoneNotifWire.parsePost(wire(#"{"t":"notif_post","pkg":"p"}"#), phone: "P") == nil, "post without key rejected")
    check(PhoneNotifWire.parsePost(wire(#"{"t":"notif_post","key":"\#(String(repeating: "k", count: 65))"}"#), phone: "P") == nil, "overlong key rejected")
    let long = PhoneNotifWire.parsePost(wire(#"{"t":"notif_post","key":"k","title":"\#(String(repeating: "x", count: 300))"}"#), phone: "P")
    check(long?.title.count == 200 && long?.title.hasSuffix("…") == true, "title trimmed")
}

// MARK: store

do {
    var s = PhoneNotifStore()
    let p1 = wire(#"{"t":"notif_post","key":"a","pkg":"wa","app":"WhatsApp","title":"A","ts":1000}"#)
    let p2 = wire(#"{"t":"notif_post","key":"b","pkg":"gm","app":"Gmail","title":"B","ts":3000}"#)
    let p3 = wire(#"{"t":"notif_post","key":"c","pkg":"wa","app":"WhatsApp","title":"C","ts":2000}"#)
    if case .posted(let n, let isNew) = s.apply(p1, from: "P") { check(isNew && n.key == "a", "first post is new") } else { check(false, "post change") }
    _ = s.apply(p2, from: "P"); _ = s.apply(p3, from: "P")
    if case .posted(_, let isNew) = s.apply(p1, from: "P") { check(!isNew, "same key again = update") } else { check(false, "update change") }
    let g = s.groups()
    check(g.map(\.app) == ["Gmail", "WhatsApp"], "groups newest app first")
    check(g[1].items.map(\.key) == ["c", "a"], "inside a group newest first")
    check(s.apply(wire(#"{"t":"notif_remove","key":"a"}"#), from: "P") == .removed(["P|a"]), "remove")
    check(s.apply(wire(#"{"t":"notif_remove","key":"a"}"#), from: "P") == .none, "remove twice = nothing")
    check(s.apply(wire(#"{"t":"notif_remove","key":"b"}"#), from: "Q") == .none, "another phone's key is not ours")
    _ = s.apply(wire(#"{"t":"notif_post","key":"x","pkg":"wa","title":"X","ts":5}"#), from: "Q")
    if case .removed(let ids) = s.apply(wire(#"{"t":"notif_reset"}"#), from: "P") {
        check(Set(ids) == ["P|b", "P|c"], "reset clears only that phone")
    } else { check(false, "reset change") }
    check(s.count == 1, "other phone kept")
    check(s.apply(wire(#"{"t":"notif_state","notifs":true,"calls":true}"#), from: "Q") == .none, "state on keeps list")
    check(s.apply(wire(#"{"t":"notif_state","notifs":false,"calls":true,"why":"off"}"#), from: "Q") == .removed(["Q|x"]), "state off clears")
    check(s.apply(wire(#"{"t":"something_new"}"#), from: "Q") == .none, "unknown type ignored")

    var small = PhoneNotifStore(maxPerPhone: 3)
    for i in 0..<5 { _ = small.apply(wire(#"{"t":"notif_post","key":"k\#(i)","pkg":"p","ts":\#(i * 1000)}"#), from: "P") }
    check(small.count == 3 && small.items["P|k0"] == nil && small.items["P|k4"] != nil, "oldest dropped beyond the limit")
}

// MARK: calls

do {
    var b = PhoneCallBoard()
    let ring = wire(#"{"t":"call_state","id":"c1","state":"ringing","name":"Asha","muted":false,"speaker":false,"can":["answer","decline"]}"#)
    if case .shown(let c) = b.apply(ring, from: "P") {
        check(c.caller == "Asha" && c.can == ["answer", "decline"] && c.since == nil, "ringing shown")
    } else { check(false, "ringing change") }
    check(b.apply(ring, from: "P") == .none, "same state again = nothing")
    let active = wire(#"{"t":"call_state","id":"c1","state":"active","since":1762070412000,"muted":true,"speaker":false,"can":["end","mute","speaker"]}"#)
    if case .updated(let c) = b.apply(active, from: "P") {
        check(c.state == .active && c.muted && c.since != nil, "answered: active with duration start")
    } else { check(false, "active change") }
    check(b.apply(wire(#"{"t":"call_state","id":"old","state":"ended","can":[]}"#), from: "P") == .none, "ended for another call ignored")
    if case .ended = b.apply(wire(#"{"t":"call_state","id":"c1","state":"ended","can":[]}"#), from: "P") { check(b.calls.isEmpty, "ended removes") }
    else { check(false, "ended change") }
    check(b.apply(wire(#"{"t":"call_state","id":"c2","state":"dialing"}"#), from: "P") == .none, "unknown state rejected")
    _ = b.apply(wire(#"{"t":"call_state","id":"c3","state":"ringing","number":"+91 98"}"#), from: "P")
    check(b.calls["P"]?.caller == "+91 98", "number when no name")
    if case .shown(let c) = b.apply(wire(#"{"t":"call_state","id":"c4","state":"ringing"}"#), from: "P") {
        check(c.caller == "Unknown Caller", "a new call replaces the old one")
    } else { check(false, "new call change") }
    check(b.phoneGone("P")?.id == "c4" && b.calls.isEmpty, "phone gone drops its call")
}

// MARK: commands

do {
    check(PhoneNotifWire.reply("k", String(repeating: "z", count: 5000))["text"] as? String == String(repeating: "z", count: 4000), "reply capped")
    check(PhoneNotifWire.action("k", 2)["i"] as? Int == 2, "action index")
    check(PhoneNotifWire.callAction("c1", "answer")["op"] as? String == "answer", "call action")
    let sub = PhoneNotifWire.sub(notifs: false, calls: true)
    check(sub["t"] as? String == "notif_sub" && sub["notifs"] as? Bool == false && sub["calls"] as? Bool == true, "sub")
    // Round trip through JSON like the wire does.
    let data = try! JSONSerialization.data(withJSONObject: PhoneNotifWire.dismiss("k9"))
    check((try! JSONSerialization.jsonObject(with: data) as! [String: Any])["key"] as? String == "k9", "dismiss encodes")
}

// MARK: shared vectors (docs/test-vectors/phone-notifs.jsonl, the exact JSON the Android phone sends)

do {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../docs/test-vectors/phone-notifs.jsonl")
    let lines = ((try? String(contentsOf: url, encoding: .utf8)) ?? "").split(separator: "\n").map(String.init)
    check(lines.count == 4, "vectors file found")
    if lines.count == 4 {
        let n = PhoneNotifWire.parsePost(wire(lines[0]), phone: "P")
        check(n?.key == "9f2c01ab34cd56ef78" && n?.app == "WhatsApp" && n?.canReply == true && n?.replyLabel == "Reply", "vector post")
        check(n?.msgs.map(\.from) == ["Asha", nil] && n?.actions.first?.index == 1 && n?.conv == "Family", "vector post details")
        let c = PhoneNotifWire.parseCall(wire(lines[1]), phone: "P")
        check(c?.state == .active && c?.speaker == true && c?.muted == false && c?.caller == "Asha" && c?.can.contains("mute") == true, "vector call")
        check(abs((c?.since?.timeIntervalSince1970 ?? 0) - 1762070412) < 0.001, "vector call since")
        check(PhoneNotifWire.parseState(wire(lines[2])) == PhoneRelayState(notifs: false, calls: true, why: "no_access"), "vector state")
        var s = PhoneNotifStore()
        _ = s.apply(wire(lines[0]), from: "P")
        check(s.apply(wire(lines[3]), from: "P") == .removed(["P|9f2c01ab34cd56ef78"]), "vector remove")
    }
}

// ---- exact-match blocking
func notif(_ pkg: String, _ title: String, _ text: String) -> PhoneNotif {
    PhoneNotifWire.parsePost(["t": "notif_post", "key": "k\(title.count)\(text.count)", "pkg": pkg, "app": "App", "title": title, "text": text], phone: "p")!
}
let battery = PhoneNotifBlock(notif("com.android.systemui.power", "Battery Saver is on", "Some features are limited"))
check(battery.matches(notif("com.android.systemui.power", "Battery Saver is on", "Some features are limited")), "the exact same notification is blocked")
check(battery.matches(notif("com.android.systemui.power", "  Battery Saver is on ", "Some features\nare  limited")), "extra spaces and line breaks don't matter")
check(!battery.matches(notif("com.android.systemui.power", "Battery Saver is on", "Some features are limited.")), "one character different is not blocked")
check(!battery.matches(notif("com.android.systemui.power", "battery saver is on", "Some features are limited")), "case counts: only exact text")
check(!battery.matches(notif("com.other.app", "Battery Saver is on", "Some features are limited")), "the same words from another app are not blocked")
let checking = PhoneNotifBlock(notif("com.whatsapp", "WhatsApp", "Checking for new messages"))
check(PhoneNotifBlock.isBlocked(notif("com.whatsapp", "WhatsApp", "Checking for new messages"), by: [battery, checking]), "a list of blocks")
check(!PhoneNotifBlock.isBlocked(notif("com.whatsapp", "Aarav", "Checking for new messages"), by: [battery, checking]), "a real chat from the same app still comes through")
let enc = try! JSONEncoder().encode([checking]); let dec = try! JSONDecoder().decode([PhoneNotifBlock].self, from: enc)
check(dec == [checking] && dec[0].id == checking.id, "blocks survive saving")

// §13.2 kind: the phone's status entries (VPN, music, silent) are listed but never alert.
do {
    var st = PhoneNotifStore()
    let vpn: [String: Any] = ["t": "notif_post", "key": "v1", "pkg": "com.android.vpndialogs", "app": "VPN", "title": "VPN is active",
                              "text": "", "ts": 1, "alert": true, "kind": "ongoing"]
    if case .posted(let n, _) = st.apply(vpn, from: "P") {
        check(n.isStatus && !n.alert, "an ongoing entry never alerts, even if the phone says alert")
        check(!n.canClear, "an ongoing entry can't be cleared")
    } else { check(false, "ongoing entry is listed") }
    let silent: [String: Any] = ["t": "notif_post", "key": "s1", "pkg": "com.google.android.gms", "app": "Play", "title": "Updates ready", "ts": 2, "kind": "silent"]
    if case .posted(let n, _) = st.apply(silent, from: "P") { check(n.isStatus && n.canClear && !n.alert, "a silent entry is listed, clearable, quiet") }
    let future: [String: Any] = ["t": "notif_post", "key": "f1", "pkg": "x", "app": "X", "title": "Something", "ts": 3, "kind": "glance"]
    if case .posted(let n, _) = st.apply(future, from: "P") { check(n.isStatus && !n.alert, "an unknown kind from a newer phone stays quiet") }
    let msg: [String: Any] = ["t": "notif_post", "key": "m1", "pkg": "com.whatsapp", "app": "WhatsApp", "title": "Asha", "text": "Hi", "ts": 4]
    if case .posted(let n, _) = st.apply(msg, from: "P") { check(!n.isStatus && n.alert, "an ordinary message still alerts") }
    check(st.count == 1, "the count is ordinary notifications only")
    check(st.groups().count == 1 && st.groups(status: true).count == 3, "status entries are grouped in their own section")
}

// §13.1 notif_order: the list follows the phone's shade order and its Silent header.
do {
    var st = PhoneNotifStore()
    func post(_ k: String, _ pkg: String, _ ts: Int, kind: String? = nil) {
        var m: [String: Any] = ["t": "notif_post", "key": k, "pkg": pkg, "app": pkg, "title": k, "ts": ts]
        if let kind { m["kind"] = kind }
        _ = st.apply(m, from: "P")
    }
    post("a", "wa", 100); post("b", "gm", 300); post("c", "wa", 200); post("usb", "android", 50, kind: "ongoing"); post("vpn", "vpn", 60, kind: "ongoing")
    // Before an order arrives: newest first, status entries under Silent.
    check(st.groups().map(\.pkg) == ["gm", "wa"], "no order yet: newest app first")
    _ = st.apply(["t": "notif_order", "keys": ["usb", "a", "c", "b", "vpn"], "silent": ["vpn"]], from: "P")
    let main = st.groups()
    check(main.map(\.pkg) == ["android", "wa", "gm"], "groups follow the phone's order (an alerting ongoing one stays where the phone has it)")
    check(main[1].items.map(\.key) == ["a", "c"], "inside a group the phone's order wins over time")
    check(st.groups(status: true).map(\.pkg) == ["vpn"], "only what the phone files under Silent is in Silent")
    post("new", "gm", 400)
    check(st.groups()[0].pkg == "gm" && st.groups()[0].items[0].key == "new", "a just-posted one goes on top until the next order")
    _ = st.apply(["t": "notif_reset"], from: "P")
    check(st.groups().isEmpty && st.groups(status: true).isEmpty, "a reset clears list and order")
}

print("notifcheck: \(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
