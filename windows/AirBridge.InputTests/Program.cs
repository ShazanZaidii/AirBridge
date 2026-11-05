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
}
