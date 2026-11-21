using System.Text.Json.Nodes;
using AirBridge.Input;

// Checks for the platform-free control logic in AirBridge/Input/ControlLogic.cs (docs/protocol.md §8.1, §8.6).
internal static class Program
{
    private static int _failed, _passed;

    private static void Check(bool ok, string what)
    {
        if (ok) _passed++;
        else { _failed++; Console.WriteLine("FAIL: " + what); }
    }

    private static bool Near(double a, double b) => Math.Abs(a - b) < 1e-9;

    private static int Main()
    {
        Gate();
        RateLimitAndRefusal();
        AckTimeout();
        Escape();
        Ends();
        Holder();
        Placement();
        CarryController();
        CarryPayload();
        CarryGhost();
        CarryParse();
        PhoneMirror();
        Console.WriteLine($"{_passed} passed, {_failed} failed");
        return _failed == 0 ? 0 : 1;
    }

    private static void Gate()
    {
        var g = new ControlGate();
        Check(g.MayCapture && !g.RefusesEnterFrom("p") && !g.RefusesInputFrom("p"), "idle gate refuses nothing");
        g.Controlling = "p";
        Check(g.RefusesEnterFrom("p") && g.RefusesInputFrom("p"), "driving P: P's ctl_enter and input are refused (loop)");
        Check(!g.RefusesEnterFrom("q") && !g.RefusesInputFrom("q"), "driving P: Q may still take the lease");
        g.EndControlling("q");
        Check(g.Controlling == "p", "EndControlling of another peer keeps P");
        g.EndControlling("p");
        Check(g.Controlling == null, "EndControlling clears");
        g.ControlledBy = "q";
        Check(!g.MayCapture, "controlled: no capture (no chaining)");
        g.ControlledBy = null;
        Check(g.MayCapture, "lease released: capture allowed again");

        // Concurrent writers/readers never tear or throw.
        var shared = new ControlGate();
        var tasks = Enumerable.Range(0, 8).Select(i => Task.Run(() =>
        {
            for (int k = 0; k < 20000; k++)
            {
                shared.Controlling = (k % 2 == 0) ? "a" + i : null;
                _ = shared.RefusesEnterFrom("a" + i);
                shared.ControlledBy = (k % 3 == 0) ? "b" : null;
                _ = shared.MayCapture;
            }
        })).ToArray();
        Check(Task.WaitAll(tasks, 20000), "gate under concurrency");
    }

    private static void RateLimitAndRefusal()
    {
        var r = new PerPeerRateLimit(2000);
        Check(r.TryAcquire("p", 0), "busy: first send");
        Check(!r.TryAcquire("p", 1999), "busy: not again within 2 s");
        Check(r.TryAcquire("q", 1999), "busy: per peer");
        Check(r.TryAcquire("p", 2000), "busy: again after 2 s");
        r.Forget("p");
        Check(r.TryAcquire("p", 2001), "busy: Forget resets");
        r.Retain(new HashSet<string> { "x" });
        Check(r.TryAcquire("q", 2002), "busy: Retain drops absent peers");

        var refused = new RefusalList();
        Check(!refused.IsRefused("p", 0), "not refused initially");
        refused.Refuse("p", 1000);
        Check(refused.IsRefused("p", 5999) && !refused.IsRefused("p", 6000), "refused for exactly 5 s");
        Check(!refused.IsRefused("q", 2000), "refusal is per peer");
    }

    private static void AckTimeout()
    {
        var e = new CaptureEscape(Edge.Right, 1000, 40, startMs: 10_000);
        Check(!e.AckOverdue(10_600), "ack: 600 ms is still in time");
        Check(e.AckOverdue(10_601), "ack: overdue after 600 ms");
        Check(e.Ack(), "ack: first ack accepted");
        Check(!e.Ack(), "ack: duplicate ignored");
        Check(!e.AckOverdue(20_000), "ack: never overdue once acked");
    }

    private static void Escape()
    {
        // PC right edge: moving right (+dx) goes deeper into the target, left (−dx) comes back.
        var e = new CaptureEscape(Edge.Right, 1000, slackPx: 40, startMs: 0);
        Check(!e.Move(-30, 0, false), "30 px back: still captured");
        Check(Near(e.Depth, -30), "depth −30");
        Check(e.Move(-11, 0, false), "41 px back through the entry edge: return");

        var held = new CaptureEscape(Edge.Right, 1000, 40, 0);
        Check(!held.Move(-100, 0, buttonHeld: true), "no return while a remote button is held (drag)");

        // Depth saturates at the target extent: the way back is extent + slack, not the total distance pushed.
        var deep = new CaptureEscape(Edge.Left, 500, 40, 0);
        deep.Move(-5000, 0, false); // left edge: −dx is outward
        Check(Near(deep.Depth, 500), "depth clamps at the target extent");
        Check(!deep.Move(540, 0, false), "back by extent + slack − 0: not yet");
        Check(deep.Move(1, 0, false), "back by extent + slack + 1: return");

        // Lateral travel (along the PC edge) and the return point.
        var lat = new CaptureEscape(Edge.Right, 1000, 40, 0);
        lat.Move(200, 50, false);
        lat.Move(-100, 30, false);
        Check(Near(lat.Lateral, 80), "lateral = sum of dy for a vertical edge");
        Check(Near(lat.ReturnAlong(400, 300, 900), 480), "return at entry + sideways travel");
        Check(Near(lat.ReturnAlong(880, 300, 900), 900), "return point kept inside the span (end)");
        Check(Near(CaptureEscape.ReturnAlong(310, -100, 300, 900), 300), "return point kept inside the span (start)");
        var top = new CaptureEscape(Edge.Top, 1000, 40, 0);
        top.Move(25, -10, false);
        Check(Near(top.Lateral, 25) && Near(top.Depth, 10), "top edge: lateral = dx, depth = −dy");
        var bottom = new CaptureEscape(Edge.Bottom, 1000, 40, 0);
        Check(!bottom.Move(0, -40, false) && bottom.Move(0, -1, false), "bottom edge: back is −dy, return past 40");

        // Extent from the peer's screen.
        Check(Near(CaptureEscape.TargetExtent(Edge.Right, "android", 1080, 2400), 1080 / 1.6), "android extent across a side edge uses width / 1.6");
        Check(Near(CaptureEscape.TargetExtent(Edge.Bottom, "android", 1080, 2400), 2400 / 1.6), "android extent across top/bottom uses height / 1.6");
        Check(Near(CaptureEscape.TargetExtent(Edge.Left, "macos", 1512, 982), 1512), "desktop extent gain 1.0");
        Check(Near(CaptureEscape.TargetExtent(Edge.Left, "android", 0, 0), 1200), "unknown screen: 1200");
        Check(Near(new CaptureEscape(Edge.Left, 50, 40, 0).MaxDepth, CaptureEscape.MinExtent), "tiny extents are raised to the minimum");
    }

    private static void Ends()
    {
        Check(CaptureEndExt.FromLeaveReason("unavailable") == CaptureEnd.Unavailable, "reason unavailable");
        Check(CaptureEndExt.FromLeaveReason("loop") == CaptureEnd.Loop, "reason loop");
        Check(CaptureEndExt.FromLeaveReason(null) == CaptureEnd.Other && CaptureEndExt.FromLeaveReason("x") == CaptureEnd.Other, "other reasons");
        Check(CaptureEnd.NoAck.Refuses() && CaptureEnd.Unavailable.Refuses() && CaptureEnd.Loop.Refuses(), "refusing ends");
        Check(!CaptureEnd.MovedBack.Refuses() && !CaptureEnd.Other.Refuses(), "normal ends don't refuse");
        Check(CaptureEnd.NoAck.Notice("Pixel 9", false) == "Pixel 9 isn't ready to be controlled.", "no-ack notice");
        Check(CaptureEnd.NoAck.Notice("Pixel 9", true)!.Contains("accessibility"), "android hint");
        Check(CaptureEnd.Unavailable.Notice("Pixel 9", false)!.StartsWith("Pixel 9 can't be controlled"), "unavailable notice");
        Check(CaptureEnd.Loop.Notice("Mac", false) != null, "loop notice");
        Check(CaptureEnd.MovedBack.Notice("x", true) == null && CaptureEnd.Other.Notice("x", true) == null, "no notice for normal ends");
    }

    private static void Holder()
    {
        Check(HolderWatch.ShouldDrop(null, 0), "holder without a session: drop now");
        Check(!HolderWatch.ShouldDrop(1000, 11_000), "10 s silence exactly: keep");
        Check(HolderWatch.ShouldDrop(1000, 11_001), "more than 10 s silence: drop");
    }

    private static void Placement()
    {
        var map = new Dictionary<string, PeerPlacement>();
        Check(AutoPlacement.AssignNew(map, new[] { ("a", "Pixel") }), "first peer placed");
        Check(map["a"].Edge == "right" && Near(map["a"].Offset, 0.25) && Near(map["a"].Size, 0.5), "first peer: right edge, middle half");
        AutoPlacement.AssignNew(map, new[] { ("b", "Mac") });
        Check(map["b"].Edge == "left" && Near(map["b"].Offset, 0.25) && Near(map["b"].Size, 0.5), "second peer: left edge, middle half");
        AutoPlacement.AssignNew(map, new[] { ("c", "Tablet") });
        Check(map.ContainsKey("c") && map["c"].Edge == null, "third peer: recorded but unplaced (never top/bottom)");
        map["a"].Edge = null;
        Check(!AutoPlacement.AssignNew(map, new[] { ("a", "Pixel"), ("c", "Tablet") }), "known peers are never re-placed");
        Check(map["a"].Edge == null && map["c"].Edge == null, "deliberately unplaced peers stay unplaced");
        AutoPlacement.AssignNew(map, new[] { ("d", "Phone") });
        Check(map["d"].Edge == "right", "a freed right edge is reused for a new peer");
        var userTop = new Dictionary<string, PeerPlacement> { ["t"] = new PeerPlacement { Edge = "top", Offset = 0.1, Size = 0.4 } };
        AutoPlacement.AssignNew(userTop, new[] { ("n", "New") });
        Check(userTop["t"].Edge == "top" && userTop["n"].Edge == "right", "user placements are kept; auto never picks top/bottom");
        foreach (var p in map.Values.Concat(userTop.Values).Where(p => p != userTop["t"]))
            Check(p.Edge is null or "left" or "right", "auto placement only left/right");

        var old = new Dictionary<string, PeerPlacement>
        {
            ["b"] = new PeerPlacement { Edge = "bottom", Offset = 0, Size = 1.0 },
            ["t"] = new PeerPlacement { Edge = "top", Offset = 0, Size = 1.0 },
            ["s"] = new PeerPlacement { Edge = "bottom", Offset = 0.3, Size = 0.4 },
            ["r"] = new PeerPlacement { Edge = "right", Offset = 0, Size = 1.0 },
        };
        Check(AutoPlacement.ClearFullTopBottom(old), "full top/bottom cleared");
        Check(old["b"].Edge == null && old["t"].Edge == null, "full bottom/top edges unassigned");
        Check(old["s"].Edge == "bottom" && old["r"].Edge == "right", "partial bottom and side edges kept");
        Check(!AutoPlacement.ClearFullTopBottom(old), "clean-up is idempotent");
    }
    // ---------------------------------------------------------------- §8.8 carried drag

    private static void CarryController()
    {
        var c = new DragCarryController(5);
        Check(c.Release().Kind == DragCarryController.Kind.None && c.End().Kind == DragCarryController.Kind.None, "carry idle: release/end do nothing");
        var a = c.Begin("phone");
        Check(a.Kind == DragCarryController.Kind.Carry && a.Peer == "phone" && a.Id == 5, "carry: begin = drag_carry with the first id");
        Check(c.IsCarrying && c.Peer == "phone", "carry: carrying phone");
        var d = c.Release();
        Check(d.Kind == DragCarryController.Kind.Drop && d.Peer == "phone" && d.Id == 5, "carry: release over the target = drop");
        Check(!c.IsCarrying && c.Release().Kind == DragCarryController.Kind.None, "carry: after drop idle; second release does nothing");
        Check(c.End().Kind == DragCarryController.Kind.None, "carry: capture end after a drop sends no cancel");

        var b = c.Begin("pc");
        Check(b.Id == 6, "carry: next carry gets a new id");
        var x = c.End();
        Check(x.Kind == DragCarryController.Kind.Cancel && x.Peer == "pc" && x.Id == 6, "carry: back before release = cancel");
        Check(c.Release().Kind == DragCarryController.Kind.None, "carry: release after cancel is local again");

        c.Begin("pc");
        Check(c.End(peerGone: true).Kind == DragCarryController.Kind.None && !c.IsCarrying, "carry: disconnect mid-carry sends nothing");

        var w = new DragCarryController(0x3FFF_FFFF);
        w.Begin("a");
        Check(w.Begin("a").Id == 1, "carry: ids wrap and stay positive");

        Check(DragCarryController.Eligible(new HashSet<string> { "ctl_target", "xfer", "drag_files" }), "carry: eligible with all caps");
        Check(!DragCarryController.Eligible(new HashSet<string> { "ctl_target", "xfer" }), "carry: older peer never gets a carry");
        Check(!DragCarryController.Eligible(new HashSet<string> { "ctl_target", "drag_files" }), "carry: no xfer, no carry");
    }

    private static void CarryPayload()
    {
        string dir = Path.Combine(Path.GetTempPath(), "dragcheck-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path.Combine(dir, "Photos"));
        string img = Path.Combine(dir, "IMG_0001.JPG"), txt = Path.Combine(dir, "notes.txt"), mov = Path.Combine(dir, "clip.mp4"), mp3 = Path.Combine(dir, "song.mp3");
        File.WriteAllBytes(img, new byte[1000]);
        File.WriteAllBytes(txt, new byte[24]);
        File.WriteAllBytes(mov, new byte[5]);
        File.WriteAllBytes(mp3, new byte[7]);
        File.WriteAllBytes(Path.Combine(dir, "Photos", "inside.jpg"), new byte[99]);
        try
        {
            var one = DragPayload.Describe(new[] { img });
            Check(one.Names.SequenceEqual(new[] { "IMG_0001.JPG" }) && one.Kinds.SequenceEqual(new[] { "image" }) && one.Count == 1 && one.TotalBytes == 1000,
                "payload: one image (extension case-insensitive)");
            var folder = Path.Combine(dir, "Photos") + Path.DirectorySeparatorChar;
            var mixed = DragPayload.Describe(new[] { folder, txt, mov, mp3 });
            Check(mixed.Names.SequenceEqual(new[] { "Photos", "notes.txt", "clip.mp4" }), "payload: names capped at 3, folder by its own name");
            Check(mixed.Kinds.SequenceEqual(new[] { "folder", "file", "video" }), "payload: kinds aligned");
            Check(mixed.Count == 4 && mixed.TotalBytes == 24 + 5 + 7, "payload: count all items; folders add no bytes");
            Check(DragPayload.KindOf(mp3, false) == "audio", "payload: mp3 is audio");
            var m = one.Message(9);
            Check(m["t"]?.GetValue<string>() == "drag_carry" && m["id"]?.GetValue<long>() == 9 && m["count"]?.GetValue<int>() == 1, "payload: message");
            var round = DragGhostState.Parse("pc", (JsonObject)JsonNode.Parse(m.ToJsonString())!);
            Check(round != null && round.FirstName == "IMG_0001.JPG" && round.Kind == "image" && round.TotalBytes == 1000, "payload: target parses what we send");
        }
        finally { try { Directory.Delete(dir, true); } catch { } }
    }

    private static DragGhostState.Info GhostInfo(long id, string from = "mac", int n = 1) =>
        new(id, from, Enumerable.Range(1, n).Select(i => $"f{i}.jpg").ToList(), Enumerable.Repeat("image", n).ToList(), n, 1000L * n);

    private static void CarryGhost()
    {
        var g = new DragGhostState();
        var s = g.Carry("mac", "mac", GhostInfo(1));
        Check(s.Kind == DragGhostState.Kind.Show && GhostInfo(1).Same(s.Info), "ghost: carry from the holder shows");
        var d = g.Drop("mac", 1);
        Check(d.Kind == DragGhostState.Kind.Dropped && d.GhostWasShown && g.Shown == null, "ghost: drop settles it");
        Check(g.Drop("mac", 1).Kind == DragGhostState.Kind.None, "ghost: duplicate drop ignored");

        g.Carry("mac", "mac", GhostInfo(2));
        Check(g.Cancel("mac", 3).Kind == DragGhostState.Kind.None, "ghost: cancel of another id ignored");
        Check(g.Cancel("mac", 2).Kind == DragGhostState.Kind.Hide && g.Drop("mac", 2).Kind == DragGhostState.Kind.None, "ghost: cancel hides, no confirmation later");

        Check(g.Carry("pc", "mac", GhostInfo(1, "pc")).Kind == DragGhostState.Kind.None, "ghost: only the explicit holder may carry");
        Check(g.Carry("mac", null, GhostInfo(1)).Kind == DragGhostState.Kind.None, "ghost: no explicit lease, no ghost");

        g.Carry("mac", "mac", GhostInfo(4));
        Check(g.Leave("pc").Kind == DragGhostState.Kind.None, "ghost: another peer's leave keeps it");
        Check(g.Leave("mac").Kind == DragGhostState.Kind.Hide, "ghost: ctl_leave hides it");
        var late = g.Drop("mac", 4);
        Check(late.Kind == DragGhostState.Kind.Dropped && !late.GhostWasShown, "ghost: a drop racing our ctl_leave still confirms");

        g.Carry("mac", "mac", GhostInfo(5));
        Check(g.PeerGone("mac").Kind == DragGhostState.Kind.Hide && g.Drop("mac", 5).Kind == DragGhostState.Kind.None, "ghost: disconnect forgets it");

        g.Carry("mac", "mac", GhostInfo(6));
        var r = g.Carry("mac", "mac", GhostInfo(7, n: 3));
        Check(r.Kind == DragGhostState.Kind.Show && r.Info!.Count == 3, "ghost: a new carry replaces the old");
        Check(g.Drop("mac", 6).Kind == DragGhostState.Kind.None, "ghost: the old id is gone");
        Check(g.Leave(null).Kind == DragGhostState.Kind.Hide, "ghost: leave of whoever hides");
    }

    private static void CarryParse()
    {
        static JsonObject J(string s) => (JsonObject)JsonNode.Parse(s)!;
        Check(DragGhostState.Parse("m", J("{\"t\":\"drag_carry\",\"names\":[\"a\"]}")) == null, "parse: no id ignored");
        Check(DragGhostState.Parse("m", J("{\"id\":-1}")) == null, "parse: negative id ignored");
        Check(DragGhostState.Parse("m", J("{\"id\":\"x\"}")) == null, "parse: string id ignored");
        var i = DragGhostState.Parse("m", J("{\"id\":7,\"names\":[\" a.txt \",\"\",\"Photos\",\"b\",\"c\",5],\"kinds\":[\"file\",\"folder\",\"weird\"],\"count\":2,\"totalBytes\":-5}"))!;
        Check(i.Names.SequenceEqual(new[] { "a.txt", "Photos", "b" }), "parse: names trimmed, blanks/non-strings dropped, at most 3");
        Check(i.Kinds.SequenceEqual(new[] { "file", "folder", "file" }), "parse: unknown kinds become file");
        Check(i.Count == 3 && i.TotalBytes == 0, "parse: count never below names; negative bytes 0");
        var huge = DragGhostState.Parse("m", J("{\"id\":1,\"count\":1e30,\"totalBytes\":1e30}"))!;
        Check(huge.Count == 1_000_000_000 && huge.TotalBytes > 0, "parse: huge numbers clamped");
        var bare = DragGhostState.Parse("m", J("{\"id\":3}"))!;
        Check(bare.Count == 1 && bare.FirstName == "" && bare.Kind == "file", "parse: bare carry still shows something");
    }

    private sealed class FakeTransport : AirBridge.IPeerTransport
    {
        public readonly List<System.Text.Json.Nodes.JsonObject> Sent = new();
        public bool Send(string peerId, System.Text.Json.Nodes.JsonObject msg) { lock (Sent) Sent.Add(msg); return true; }
        public void Broadcast(System.Text.Json.Nodes.JsonObject msg) { }
        public bool SendBinary(string peerId, ReadOnlyMemory<byte> frame) => true;
        public IReadOnlyList<AirBridge.PeerInfo> Peers() => Array.Empty<AirBridge.PeerInfo>();
        public long LastReceivedTick(string peerId) => -1;
        public List<string?> Types() { lock (Sent) return Sent.Select(m => (string?)m["t"]?.ToString()).ToList(); }
    }

    // §8.7 phone as source, PC as sink: message handling, frame header, acks, geometry.
    private static void PhoneMirror()
    {
        static System.Text.Json.Nodes.JsonObject J(string json) => System.Text.Json.Nodes.JsonNode.Parse(json)!.AsObject();
        var s = new PhoneMirrorState();
        s = PhoneMirrorReducer.Apply(s, J("{\"t\":\"mirror_wait\",\"reason\":\"consent\"}"));
        Check(s.Phase == PhoneMirrorPhase.WaitingForConsent, "mirror_wait → Allow on your phone");
        s = PhoneMirrorReducer.Apply(s, J("{\"t\":\"mirror_info\",\"codec\":\"h264\",\"w\":864,\"h\":1920,\"fps\":60,\"display\":{\"w\":1080,\"h\":2400,\"corner\":110}}"));
        Check(s.Phase == PhoneMirrorPhase.Streaming && s.StreamW == 864 && s.DisplayH == 2400 && s.Corner == 110, "mirror_info sizes");
        s = PhoneMirrorReducer.Apply(s, J("{\"t\":\"mirror_state\",\"control\":true,\"typing\":false,\"locked\":true}"));
        Check(s.Control && !s.Typing && s.Locked, "mirror_state flags");
        var e = PhoneMirrorReducer.Apply(s, J("{\"t\":\"error\",\"code\":\"mirror_permission\",\"reason\":\"timeout\"}"));
        e = PhoneMirrorReducer.Apply(e, J("{\"t\":\"mirror_stop\",\"reason\":\"timeout\"}"));
        Check(e.Phase == PhoneMirrorPhase.Stopped && e.StopText == "Nobody allowed it on the phone", "error then stop keeps the reason");
        var st = PhoneMirrorReducer.Apply(s, J("{\"t\":\"mirror_stop\",\"reason\":\"locked\"}"));
        Check(st.StopText == "Phone is locked", "stop reason locked");
        Check(ReferenceEquals(PhoneMirrorReducer.Apply(s, J("{\"t\":\"error\",\"code\":\"other\"}")), s), "non-mirror error ignored");

        var f = new byte[] { 0x03, 0, 0, 0, 0, 0, 0x0F, 0x42, 0x40, 3, 0, 0, 0, 1, 0x65 };
        Check(MirrorFrame.TryParse(f, out ulong pts, out bool key, out bool ps) && pts == 1_000_000 && key && ps, "frame header");
        Check(!MirrorFrame.TryParse(new byte[] { 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, out _, out _, out _), "other kinds rejected");

        var acks = new MirrorAckPolicy();
        int n = 0;
        for (int i = 0; i < 30; i++) if (acks.OnFrame(1000 + i)) n++; // 30 frames within 30 ms
        Check(n == 4, $"acks: first frame + every 10th ({n})");
        Check(acks.OnFrame(1600), "ack after 500 ms gap");

        var p = PhoneMirrorGeometry.Normalize(100, 300, 400, 900);
        Check(Near(p.x, 0.25) && Near(p.y, 0.3333), "normalize");
        var c = PhoneMirrorGeometry.Normalize(-5, 2000, 400, 900);
        Check(c.x == 0 && c.y == 1, "normalize clamps");
        var w = PhoneMirrorGeometry.Wheel(0, 120, 400, 1080);
        Check(!w.px && Near(w.dy, -1), "wheel notch = one line, content moves down");
        var t = PhoneMirrorGeometry.Wheel(0, 10, 400, 1080);
        Check(t.px && Near(t.dy, -16.88), "touchpad delta scaled to phone pixels");

        // Session without a decoder (this build): mirror_info ends the stream politely.
        var fake = new FakeTransport();
        AirBridge.PeerBus.Transport = fake;
        var session = new PhoneMirrorSession("phone");
        session.Start();
        Check(fake.Types().FirstOrDefault() == "mirror_start", "session sends mirror_start");
        session.Handle(J("{\"t\":\"mirror_info\",\"codec\":\"h264\",\"w\":864,\"h\":1920}"));
        Check(session.State.Phase == PhoneMirrorPhase.Stopped && fake.Types().LastOrDefault() == "mirror_stop", "no decoder: stopped + mirror_stop");
        AirBridge.PeerBus.Transport = null;
    }
}

