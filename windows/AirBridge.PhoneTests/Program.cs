using System;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using AirBridge.Phone;

internal static class Program
{
    private static int _checks, _failures;

    private static void Check(bool ok, string what)
    {
        _checks++;
        if (!ok) { _failures++; Console.WriteLine("FAIL: " + what); }
    }

    private static JsonObject J(string json) => JsonNode.Parse(json)!.AsObject();

    private static int Main()
    {
        Parsing();
        Store();
        Calls();
        Commands();
        Vectors();
        CallExperience();
        Console.WriteLine($"{_checks} checks, {_failures} failures");
        return _failures == 0 ? 0 : 1;
    }

    private static void Parsing()
    {
        var n = PhoneWire.ParsePost(J("""
            {"t":"notif_post","key":"k1","pkg":"com.whatsapp","app":"WhatsApp","title":"Asha","text":"See you at 6",
             "ts":1762070400000,"cat":"msg","conv":"Family","reply":true,"reply_label":"Reply","alert":true,
             "msgs":[{"from":"Asha","text":"Hi","ts":1},{"me":true,"text":"Hello","ts":2}],
             "acts":[{"i":1,"label":"Mark as read"},{"i":"x","label":"bad"},{"i":2,"label":""}]}
            """), "P");
        Check(n != null, "post parses");
        Check(n?.Id == "P|k1" && n.App == "WhatsApp" && n.Title == "Asha" && n.Conv == "Family", "post fields");
        Check(n?.CanReply == true && n.ReplyLabel == "Reply" && n.Alert, "reply + alert");
        Check(n?.Msgs.Count == 2 && n.Msgs[0].From == "Asha" && n.Msgs[1].From == null, "messages, me = null sender");
        Check(n?.Actions.Count == 1 && n.Actions[0] == new PhoneAction(1, "Mark as read"), "only valid actions");
        Check(n?.Ts.ToUnixTimeMilliseconds() == 1762070400000, "timestamp in ms");

        var q = PhoneWire.ParsePost(J("""{"t":"notif_post","key":"k2","pkg":"p","reply":1,"alert":false}"""), "P");
        Check(q?.CanReply == false, "an integer 1 is not true");
        Check(q?.Alert == false && q.App == "p", "alert false, app falls back to pkg");
        Check(PhoneWire.ParsePost(J("""{"t":"notif_post","pkg":"p"}"""), "P") == null, "no key rejected");
        Check(PhoneWire.ParsePost(J("{\"t\":\"notif_post\",\"key\":\"" + new string('k', 65) + "\"}"), "P") == null, "overlong key rejected");
        var longT = PhoneWire.ParsePost(J("{\"t\":\"notif_post\",\"key\":\"k\",\"title\":\"" + new string('x', 300) + "\"}"), "P");
        Check(longT?.Title.Length == 200 && longT.Title.EndsWith("…"), "title trimmed");
        Check(PhoneWire.ParsePost(J("""{"t":"notif_post","key":"k","ts":1e300}"""), "P") != null, "absurd ts tolerated");
    }

    private static void Store()
    {
        var s = new PhoneNotifStore();
        var r = s.Apply(J("""{"t":"notif_post","key":"a","pkg":"wa","app":"WhatsApp","title":"A","ts":1000}"""), "P");
        Check(r is PhoneNotifStore.Change.Posted { IsNew: true }, "first post is new");
        s.Apply(J("""{"t":"notif_post","key":"b","pkg":"gm","app":"Gmail","title":"B","ts":3000}"""), "P");
        s.Apply(J("""{"t":"notif_post","key":"c","pkg":"wa","app":"WhatsApp","title":"C","ts":2000}"""), "P");
        Check(s.Apply(J("""{"t":"notif_post","key":"a","pkg":"wa","app":"WhatsApp","title":"A2","ts":1000}"""), "P") is PhoneNotifStore.Change.Posted { IsNew: false }, "same key = update");
        var g = s.Groups();
        Check(g.Select(x => x.App).SequenceEqual(new[] { "Gmail", "WhatsApp" }), "groups newest app first");
        Check(g[1].Items.Select(x => x.Key).SequenceEqual(new[] { "c", "a" }), "inside a group newest first");
        Check(s.Apply(J("""{"t":"notif_remove","key":"a"}"""), "P") is PhoneNotifStore.Change.Removed rm && rm.Ids.SequenceEqual(new[] { "P|a" }), "remove");
        Check(s.Apply(J("""{"t":"notif_remove","key":"a"}"""), "P") is PhoneNotifStore.Change.None, "remove twice = nothing");
        Check(s.Apply(J("""{"t":"notif_remove","key":"b"}"""), "Q") is PhoneNotifStore.Change.None, "other phone's key is not ours");
        s.Apply(J("""{"t":"notif_post","key":"x","pkg":"wa","title":"X","ts":5}"""), "Q");
        Check(s.Apply(J("""{"t":"notif_reset"}"""), "P") is PhoneNotifStore.Change.Removed rr && rr.Ids.OrderBy(x => x).SequenceEqual(new[] { "P|b", "P|c" }), "reset clears only that phone");
        Check(s.Count == 1, "other phone kept");
        Check(s.Apply(J("""{"t":"notif_state","notifs":true,"calls":true}"""), "Q") is PhoneNotifStore.Change.None, "state on keeps list");
        Check(s.Apply(J("""{"t":"notif_state","notifs":false,"calls":true,"why":"off"}"""), "Q") is PhoneNotifStore.Change.Removed, "state off clears");
        Check(s.Apply(J("""{"t":"something_new"}"""), "Q") is PhoneNotifStore.Change.None, "unknown type ignored");

        var small = new PhoneNotifStore { MaxPerPhone = 3 };
        for (int i = 0; i < 5; i++) small.Apply(J($$"""{"t":"notif_post","key":"k{{i}}","pkg":"p","ts":{{i * 1000}}}"""), "P");
        Check(small.Count == 3 && !small.Items.ContainsKey("P|k0") && small.Items.ContainsKey("P|k4"), "oldest dropped beyond the limit");
    }

    private static void Calls()
    {
        var b = new PhoneCallBoard();
        var ring = J("""{"t":"call_state","id":"c1","state":"ringing","name":"Asha","muted":false,"speaker":false,"can":["answer","decline"]}""");
        var (k1, c1) = b.Apply(ring, "P");
        Check(k1 == PhoneCallBoard.Kind.Shown && c1!.Caller == "Asha" && c1.Can.Contains("answer") && c1.Since == null, "ringing shown");
        Check(b.Apply(J(ring.ToJsonString()), "P").kind == PhoneCallBoard.Kind.None, "same state again = nothing");
        var (k2, c2) = b.Apply(J("""{"t":"call_state","id":"c1","state":"active","since":1762070412000,"muted":true,"can":["end","mute","speaker"]}"""), "P");
        Check(k2 == PhoneCallBoard.Kind.Updated && c2!.State == PhoneCallState.Active && c2.Muted && c2.Since != null, "answered");
        Check(b.Apply(J("""{"t":"call_state","id":"old","state":"ended","can":[]}"""), "P").kind == PhoneCallBoard.Kind.None, "ended for another call ignored");
        Check(b.Apply(J("""{"t":"call_state","id":"c1","state":"ended","can":[]}"""), "P").kind == PhoneCallBoard.Kind.Ended && b.Calls.Count == 0, "ended removes");
        Check(b.Apply(J("""{"t":"call_state","id":"c2","state":"dialing"}"""), "P").kind == PhoneCallBoard.Kind.None, "unknown state rejected");
        b.Apply(J("""{"t":"call_state","id":"c3","state":"ringing","number":"+91 98"}"""), "P");
        Check(b.Calls["P"].Caller == "+91 98", "number when no name");
        var (k4, c4) = b.Apply(J("""{"t":"call_state","id":"c4","state":"ringing"}"""), "P");
        Check(k4 == PhoneCallBoard.Kind.Shown && c4!.Caller == "Unknown Caller", "a new call replaces the old one");
        Check(b.PhoneGone("P")?.Id == "c4" && b.Calls.Count == 0, "phone gone drops its call");
    }

    /// <summary>docs/test-vectors/phone-notifs.jsonl: the exact JSON the Android phone sends.</summary>
    private static void Vectors()
    {
        string? dir = AppContext.BaseDirectory;
        string? file = null;
        while (dir != null && file == null)
        {
            string f = Path.Combine(dir, "docs", "test-vectors", "phone-notifs.jsonl");
            if (File.Exists(f)) file = f; else dir = Path.GetDirectoryName(dir);
        }
        Check(file != null, "vectors file found");
        if (file == null) return;
        var lines = File.ReadAllLines(file).Where(l => l.Trim().Length > 0).ToArray();
        Check(lines.Length == 4, "four vectors");
        if (lines.Length != 4) return;
        var n = PhoneWire.ParsePost(J(lines[0]), "P");
        Check(n?.Key == "9f2c01ab34cd56ef78" && n.App == "WhatsApp" && n.CanReply && n.ReplyLabel == "Reply" && n.Conv == "Family", "vector post");
        Check(n?.Msgs.Count == 2 && n.Msgs[0].From == "Asha" && n.Msgs[1].From == null && n.Actions[0].Index == 1, "vector post details");
        var c = PhoneWire.ParseCall(J(lines[1]), "P");
        Check(c?.State == PhoneCallState.Active && c.Speaker && !c.Muted && c.Caller == "Asha" && c.Since?.ToUnixTimeMilliseconds() == 1762070412000, "vector call");
        Check(PhoneWire.ParseState(J(lines[2])) == new PhoneRelayState(false, true, "no_access"), "vector state");
        var s = new PhoneNotifStore();
        s.Apply(J(lines[0]), "P");
        Check(s.Apply(J(lines[3]), "P") is PhoneNotifStore.Change.Removed r && r.Ids.Single() == "P|9f2c01ab34cd56ef78", "vector remove");
    }

    private static void Commands()
    {
        Check(PhoneWire.Reply("k", new string('z', 5000))["text"]!.GetValue<string>().Length == 4000, "reply capped");
        Check(PhoneWire.Action("k", 2)["i"]!.GetValue<int>() == 2, "action index");
        Check(PhoneWire.CallAction("c1", "answer")["op"]!.GetValue<string>() == "answer", "call action");
        var sub = PhoneWire.Sub(false, true);
        Check(sub.ToJsonString() == """{"t":"notif_sub","notifs":false,"calls":true}""", "sub encodes");
        Check(PhoneWire.Dismiss("k9").ToJsonString() == """{"t":"notif_dismiss","key":"k9"}""", "dismiss encodes");
        Check(PhoneWire.Open("k9")["t"]!.GetValue<string>() == "notif_open", "open");
    }

    /// <summary>§13.5: ring policy, proximity, presence, beacon id, caller text and docs/test-vectors/call-experience.jsonl.</summary>
    private static void CallExperience()
    {
        var t0 = DateTimeOffset.FromUnixTimeSeconds(1_762_080_000);
        PhonePresenceReport P(bool screen = true, bool locked = false, long? idle = null, bool touch = true, DateTimeOffset? at = null) =>
            new(screen, locked, idle, touch, at ?? t0);
        RingInputs I(RingMode mode = RingMode.Smart, double? pcIdle = 60, bool avail = true, PhonePresenceReport? phone = null, Proximity prox = Proximity.Unknown, DateTimeOffset? now = null) =>
            new(mode, pcIdle, avail, phone, prox, now ?? t0);

        Check(RingPolicy.Decide(I(pcIdle: 3)) == new RingDecision(true, true, RingReason.AtComputer), "(a) at the PC: rings, phone quiet");
        Check(RingPolicy.Decide(I(pcIdle: 120, prox: Proximity.Near)) == new RingDecision(true, false, RingReason.Nearby), "(b) near: rings too");
        Check(RingPolicy.Decide(I(pcIdle: 120, prox: Proximity.Far)) == new RingDecision(false, false, RingReason.Away), "(c) far: silent");
        Check(RingPolicy.Decide(I(pcIdle: 120, phone: P(idle: 2000), prox: Proximity.Near)).Reason == RingReason.UsingPhone, "(d) on the phone: silent");
        Check(RingPolicy.Decide(I(pcIdle: 10)).Reason == RingReason.Away && RingPolicy.Decide(I(pcIdle: 9.9)).Reason == RingReason.AtComputer, "10 s window");
        Check(RingPolicy.Decide(I(pcIdle: 2, avail: false)).Reason == RingReason.Away, "locked PC");
        Check(RingPolicy.Decide(I(pcIdle: 6, phone: P(idle: 1000))).Reason == RingReason.UsingPhone, "phone more recent");
        Check(RingPolicy.Decide(I(pcIdle: 1, phone: P(idle: null, touch: false))).Reason == RingReason.AtComputer, "phone without taps loses");
        Check(RingPolicy.Decide(I(RingMode.Never, pcIdle: 0, prox: Proximity.Near)) == new RingDecision(false, false, RingReason.ModeNever), "Never");
        Check(RingPolicy.Decide(I(RingMode.Always)) == new RingDecision(true, false, RingReason.ModeAlways), "Always");
        Check(P(idle: 4000).InUse(t0.AddSeconds(5)) && !P(idle: 4000).InUse(t0.AddSeconds(6.5)), "time since arrival is added");
        Check(P(idle: null, touch: false).InUse(t0) && !P(screen: false).InUse(t0) && !P(locked: true, idle: 0).InUse(t0), "in-use rules");

        var s = new RingSession(I(pcIdle: 2));
        Check(s.Update(I(pcIdle: 14, now: t0.AddSeconds(12))).ComputerRings, "at-the-PC latches");
        var picked = s.Update(I(pcIdle: 16, phone: P(idle: 500, at: t0.AddSeconds(14)), now: t0.AddSeconds(14)));
        Check(!picked.ComputerRings && picked.SilencePhone, "picking the phone up quiets the PC; phone stays quiet");

        var e = new ProximityEstimator();
        e.Add(-60, -7, t0); Check(e.State == Proximity.Unknown, "one packet");
        e.Add(-61, -7, t0.AddSeconds(1)); Check(e.State == Proximity.Near, "near");
        for (int i = 2; i < 10; i++) e.Add(-68, -7, t0.AddSeconds(i));
        Check(e.State == Proximity.Near, "hysteresis");
        for (int i = 10; i < 20; i++) e.Add(-75, -7, t0.AddSeconds(i));
        Check(e.State == Proximity.Far, "far");
        e.Add(-45, -7, t0.AddSeconds(20)); Check(e.State == Proximity.Far, "outlier ignored");
        Check(e.Tick(t0.AddSeconds(40)) == Proximity.Unknown, "stale");

        var key = Enumerable.Range(0, 16).Select(i => (byte)i).ToArray();
        var at = DateTimeOffset.FromUnixTimeMilliseconds(1_762_080_300_000);
        Check(BeaconCrypto.Epoch(at, 900) == 1_957_867, "epoch");
        var id = BeaconCrypto.Id(key, 1_957_867);
        Check(Convert.ToHexString(id).ToLowerInvariant() == "48dfa4903066889a", "beacon id vector");
        Check(BeaconCrypto.Matches(id, key, at.AddSeconds(900), 900) && !BeaconCrypto.Matches(id, key, at.AddSeconds(1800), 900), "epoch window");

        Check(CallerText.Initials("Asha Kapoor") == "AK" && CallerText.Initials("asha") == "A" && CallerText.Initials("+91 98") == null, "initials");
        Check(CallerText.Title(null, null) == "Unknown Caller" && CallerText.Subtitle("Mobile", "V2404") == "Mobile · V2404", "caller text");
        Check(CallerText.Duration(TimeSpan.FromSeconds(42)) == "0:42" && CallerText.Duration(TimeSpan.FromSeconds(3723)) == "1:02:03", "durations");

        string? dir = AppContext.BaseDirectory, file = null;
        while (dir != null && file == null)
        {
            string f = Path.Combine(dir, "docs", "test-vectors", "call-experience.jsonl");
            if (File.Exists(f)) file = f; else dir = Path.GetDirectoryName(dir);
        }
        Check(file != null, "call vectors found");
        if (file == null) return;
        var lines = File.ReadAllLines(file).Where(l => l.Trim().Length > 0).ToArray();
        Check(lines.Length == 8, "eight call vectors");
        if (lines.Length != 8) return;
        var ring = PhoneWire.ParseCall(J(lines[0]), "P");
        Check(ring?.Label == "Mobile" && ring.Caller == "Asha Kapoor" && ring.State == PhoneCallState.Ringing, "ringing with label");
        var act = PhoneWire.ParseCall(J(lines[1]), "P");
        Check(act?.Muted == true && act.Speaker && act.Since != null, "active muted speaker");
        var photo = PhoneWire.ParseCallPhoto(J(lines[2]));
        Check(photo?.id == "c4-mxk2b0" && photo.Value.jpg[0] == 0xFF && photo.Value.jpg[1] == 0xD8, "photo");
        Check(PhonePresenceReport.Parse(J(lines[3]), t0) == new PhonePresenceReport(true, false, 2500, true, t0), "presence");
        var b = PhoneWire.ParseBeacon(J(lines[4]));
        Check(b?.key != null && b.Value.key!.SequenceEqual(key) && b.Value.period == 900, "beacon on");
        Check(PhoneWire.ParseBeacon(J(lines[5])) is { ok: true, key: null }, "beacon off");
        Check(PhoneWire.Ring("c4-mxk2b0", true).ToJsonString() == lines[6], "call_ring encodes exactly");
        Check(PhonePresenceReport.Parse(J("""{"t":"call_presence","screen":1,"locked":false}"""), t0) == null, "integer is not a boolean");
        Check(PhoneWire.ParseCallPhoto(J("""{"t":"call_photo","id":"c1","jpg":"%%%"}""")) == null, "bad base64");
    }
}
