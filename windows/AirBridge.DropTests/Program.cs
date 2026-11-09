using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using AirBridge.Drop;
using AirBridge.Net;

internal static class Program
{
    private static int _checks, _failures;

    private static void Check(bool ok, string what)
    {
        _checks++;
        if (!ok) { _failures++; Console.WriteLine("FAIL: " + what); }
    }

    private static TransferView View(string key, bool incoming = false, string peer = "p1", TransferState state = TransferState.Active,
                                     long done = 0, string? error = null, bool waiting = false, string[]? saved = null) =>
        new(key, "Phone", incoming, "a.txt", 100, done, state, error)
        {
            PeerId = peer, Sources = incoming ? Array.Empty<string>() : new[] { "/tmp/a.txt" }, Waiting = waiting,
            Saved = saved ?? Array.Empty<string>(),
        };

    private static int Main()
    {
        Environment.SetEnvironmentVariable("AIRBRIDGE_DATA_DIR", Path.Combine(Path.GetTempPath(), "airbridge-droptests"));
        Layout();
        Files();
        Text();
        Hud();
        Board();
        Console.WriteLine($"{_checks} checks, {_failures} failures");
        return _failures == 0 ? 0 : 1;
    }

    private static void Layout()
    {
        var none = new DockLayout(Array.Empty<string>());
        Check(none.Mode == DockMode.None && none.Slots.Count == 0, "none: no slots");
        var single = new DockLayout(new[] { "p1" });
        Check(single.Mode == DockMode.Single && single.Slots.Count == 1 && single.Slots[0].Diameter == 76, "single: one big bubble");
        var several = new DockLayout(new[] { "p1", "p2", "p3" });
        Check(several.Mode == DockMode.Several && several.Slots.Count == 3, "several");
        foreach (var l in new[] { single, several })
        {
            foreach (var s in l.Slots)
            {
                Check(l.Hit(s.X, s.Y)?.PeerId == s.PeerId, "centre hits its slot");
                Check(s.X - s.Radius >= 0 && s.X + s.Radius <= l.Width, "inside width");
                Check(s.Y + s.Radius + DockLayout.Gap + DockLayout.NameHeight <= l.Height, "name fits");
            }
            Check(l.Hit(-100, -100) == null, "far away misses");
        }
        var a = several.Slots[0]; var b = several.Slots[1];
        double mid = (a.X + b.X) / 2;
        Check(several.Hit(mid - 3, a.Y)?.PeerId == a.PeerId, "nearer left");
        Check(several.Hit(mid + 3, a.Y)?.PeerId == b.PeerId, "nearer right");
        Check(several.Hit(a.X, a.Y + a.Radius + 12)?.PeerId == a.PeerId, "on the name");
        Check(several.Hit(a.X, a.Y - a.Radius - 25) == null, "above, beyond slack");
        Check(DockLayout.Pull(a, a.X, a.Y) == 1 && DockLayout.Pull(a, null, null) == 0 && DockLayout.Pull(a, a.X + 900, a.Y) == 0, "pull");
        var (dx, dy) = DockLayout.Lean(a, a.X + 30, a.Y);
        Check(dx > 0 && dx <= 7 && Math.Abs(dy) < 1e-9, "lean towards cursor");
    }

    private static void Files()
    {
        string root = Path.Combine(Path.GetTempPath(), "droptests-" + Guid.NewGuid().ToString("N"));
        string folder = Path.Combine(root, "Photos");
        Directory.CreateDirectory(Path.Combine(folder, "Nested"));
        Directory.CreateDirectory(Path.Combine(root, "Empty"));
        File.WriteAllText(Path.Combine(root, "a.txt"), "a");
        File.WriteAllText(Path.Combine(folder, "b.jpg"), "bb");
        File.WriteAllText(Path.Combine(folder, "Nested", "c.png"), "ccc");
        string hidden = Path.Combine(folder, ".hidden");
        File.WriteAllText(hidden, "x");
        File.SetAttributes(hidden, File.GetAttributes(hidden) | FileAttributes.Hidden);
        string a = Path.Combine(root, "a.txt");
        var got = DropFiles.Expand(new[] { a, folder, Path.Combine(root, "Empty"), Path.Combine(root, "missing"), a });
        var names = got.Select(Path.GetFileName).ToList();
        Check(names.FirstOrDefault() == "a.txt", "file first: " + string.Join(",", names));
        Check(names.Contains("b.jpg") && names.Contains("c.png"), "folder walked");
        Check(!names.Contains(".hidden"), "hidden skipped");
        Check(names.Count(n => n == "a.txt") == 1, "no duplicates");
        Check(DropFiles.Expand(new[] { folder }, limit: 1).Count == 1, "limit");
        Check(DropFiles.Title(new[] { a }) == "a.txt" && DropFiles.Title(got) == $"{got.Count} files", "titles");
        Directory.Delete(root, true);
    }

    private static void Text()
    {
        Check(TransferText.TimeLeft(100, 50, 10) == "5 s left", "5 s");
        Check(TransferText.TimeLeft(100, 100, 10) == null, "done");
        Check(TransferText.TimeLeft(6000, 0, 10) == "10 min left", "minutes");
        Check(TransferText.TimeLeft(40_000, 0, 10) == "1 h 7 min left", "hours");
        Check(TransferText.Bytes(512) == "512 B" && TransferText.Bytes(2_400_000) == "2.4 MB" && TransferText.Bytes(412_000_000) == "412 MB", "bytes");
    }

    private static void Hud()
    {
        var t0 = new DateTime(2026, 11, 9, 12, 0, 0, DateTimeKind.Utc);
        var files = new[] { "/tmp/a.txt", "/tmp/b.txt" };

        var h = new HudState();
        var card = h.QueueSend(files, "p1", "Phone", "2 files", t0);
        Check(h.Cards.Count == 1 && card.Stage == HudStage.Countdown && card.DeadlineUtc == t0.AddSeconds(3), "undo card, 3 s");
        Check(h.Due(t0.AddSeconds(2.9)).Count == 0, "not due yet");

        var h2 = new HudState();
        var c2 = h2.QueueSend(files, "p1", "Phone", "2 files", t0);
        Check(h2.Undo(c2.Id) == c2 && h2.Cards.Count == 0 && h2.Due(t0.AddSeconds(10)).Count == 0, "undo: nothing goes out");

        var due = h.Due(t0.AddSeconds(3));
        Check(due.Count == 1 && due[0] == card && card.Stage == HudStage.Launching, "due after 3 s");
        Check(h.Undo(card.Id) == null && h.Due(t0.AddSeconds(4)).Count == 0, "launches once, no undo after");
        Check(h.IsLaunching(card.Id), "launching");
        h.Ingest(View("out-1", waiting: true), t0.AddSeconds(3.1));
        Check(h.Cards.Count == 1 && card.TransferKey == "out-1" && card.Stage == HudStage.Transfer, "first report attaches to the undo card");
        h.Ingest(View("out-1", done: 40), t0.AddSeconds(4));
        Check(card.Status?.Done == 40 && !card.IsFinished, "progress");
        h.Ingest(View("out-1", state: TransferState.Done, done: 100), t0.AddSeconds(5));
        h.Ingest(View("out-1", state: TransferState.Done, done: 100), t0.AddSeconds(6)); // board re-reports while lingering
        Check(card.IsDone && card.FinishedUtc == t0.AddSeconds(5), "done, finish time kept");
        h.Expire(t0.AddSeconds(500), autoHide: false);
        Check(h.Cards.Count == 1, "persistent without auto-hide");
        h.Expire(t0.AddSeconds(9.9), autoHide: true);
        Check(h.Cards.Count == 1, "not hidden before 5 s");
        h.Expire(t0.AddSeconds(10), autoHide: true);
        Check(h.Cards.Count == 0, "auto-hidden after 5 s");
        h.Ingest(View("out-1", state: TransferState.Done, done: 100), t0.AddSeconds(11));
        Check(h.Cards.Count == 0, "dismissed card stays gone");

        var hi = new HudState();
        hi.Ingest(View("in-1", incoming: true, done: 10), t0);
        hi.Ingest(View("in-1", incoming: true, state: TransferState.Done, done: 100, saved: new[] { "C:/x.pdf" }), t0);
        Check(hi.Cards.Count == 1 && hi.Cards[0].Incoming && hi.Cards[0].Status!.Saved.Count == 1, "incoming card with saved path");
        hi.Ingest(View("in-2", incoming: true, state: TransferState.Failed, error: "Disconnected"), t0);
        Check(hi.Cards[0].TransferKey == "in-2" && !hi.Cards[0].CanRetry, "incoming failure, newest first, no retry");
        hi.Expire(t0.AddSeconds(1000), autoHide: true);
        Check(hi.Cards.Count == 1 && hi.Cards[0].TransferKey == "in-2", "failures not auto-hidden");

        var hr = new HudState();
        hr.Ingest(View("out-9", state: TransferState.Failed, error: "Disconnected"), t0);
        var rc = hr.Cards[0];
        Check(rc.CanRetry, "retry offered");
        hr.Relaunch(rc.Id, t0.AddSeconds(1));
        hr.Ingest(View("out-9", state: TransferState.Failed, error: "Disconnected"), t0.AddSeconds(1.5));
        Check(hr.Cards.Count == 1 && rc.Stage == HudStage.Launching, "old report ignored after retry");
        hr.Ingest(View("out-10", waiting: true), t0.AddSeconds(2));
        Check(hr.Cards.Count == 1 && rc.TransferKey == "out-10", "retry reuses the card");
        hr.Ingest(View("out-10", state: TransferState.Failed, error: TransferView.CancelledMessage), t0.AddSeconds(3));
        Check(rc.WasCancelledHere && !rc.CanRetry, "cancelled: no retry");
        hr.Expire(t0.AddSeconds(4.7), autoHide: false);
        Check(hr.Cards.Count == 0, "cancelled card hides");

        var ht = new HudState();
        var tc = ht.QueueSend(files, "p2", "PC", "2 files", t0, TimeSpan.Zero);
        ht.Due(t0);
        ht.Expire(t0.AddSeconds(29), autoHide: false);
        Check(tc.Stage == HudStage.Launching, "still launching at 29 s");
        ht.Expire(t0.AddSeconds(30), autoHide: false);
        Check(tc.Failure != null && tc.CanRetry, "launch timeout → failed with retry");
        ht.Fail(tc.Id, "gone", t0, filesGone: true);
        Check(!tc.CanRetry, "no retry when files are gone");

        var hm = new HudState();
        hm.QueueSend(files, "p1", "Phone", "2 files", t0, TimeSpan.Zero);
        hm.Due(t0);
        hm.Ingest(View("out-x", peer: "p2", waiting: true), t0);
        Check(hm.Cards.Count == 2, "another device's report gets its own card");

        var hc = new HudState();
        for (int i = 0; i < 4; i++) hc.Ingest(View($"run-{i}"), t0);
        for (int i = 0; i < 5; i++) hc.Ingest(View($"fin-{i}", state: TransferState.Done, done: 100), t0);
        Check(hc.Cards.Count == HudState.MaxCards && hc.Cards.Count(c => !c.IsFinished) == 4 && hc.Cards[0].TransferKey == "fin-4", "cap keeps running cards, newest first");
    }

    private static void Board()
    {
        using var b = new TransferBoard();
        int changed = 0;
        b.Changed += () => Interlocked.Increment(ref changed);
        b.Start("k", "Phone", incoming: false, "a.txt", 1000, peerId: "p1", sources: new[] { "/tmp/a.txt" }, fileCount: 1, waiting: true);
        var v = b.Snapshot().Single();
        Check(v.Waiting && v.PeerId == "p1" && v.Sources.Count == 1, "start carries peer, sources, waiting");
        b.MarkAccepted("k");
        Check(!b.Snapshot().Single().Waiting, "accepted");
        bool cancelled = false;
        b.RegisterCancel("k", () => cancelled = true);
        Check(b.Cancel("k") && cancelled, "cancel runs the registered action");
        Check(!b.Cancel("k"), "cancel only once");
        b.RegisterCancel("k", () => { });
        b.Finish("k", false, TransferView.CancelledMessage);
        Check(!b.Cancel("k"), "finish drops the cancel action");
        Check(b.Snapshot().Single().Error == TransferView.CancelledMessage, "error kept");
        b.Start("i", "PC", incoming: true, "x.pdf", 10, peerId: "p2");
        b.Finish("i", true, null, saved: new[] { "C:/x.pdf" });
        var iv = b.Snapshot().First(x => x.Key == "i");
        Check(iv.State == TransferState.Done && iv.Saved.Count == 1 && iv.Done == 10, "saved paths on finish");
        Thread.Sleep(600);
        Check(changed > 0, "Changed raised");
    }
}
