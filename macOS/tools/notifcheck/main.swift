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

print("notifcheck: \(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
