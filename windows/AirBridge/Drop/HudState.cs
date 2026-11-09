using System;
using System.Collections.Generic;
using System.Linq;
using AirBridge.Net;

namespace AirBridge.Drop
{
    public enum HudStage { Countdown, Launching, Transfer }

    /// <summary>One card in the transfer HUD.</summary>
    public sealed class HudCard
    {
        public Guid Id { get; } = Guid.NewGuid();
        public HudStage Stage { get; internal set; }
        public bool Incoming { get; init; }
        public string PeerId { get; init; } = "";
        public string PeerName { get; init; } = "";
        /// <summary>"Report.pdf" or "3 files".</summary>
        public string Title { get; internal set; } = "";
        /// <summary>Files to send (countdown/launching) or that were sent, for Retry.</summary>
        public IReadOnlyList<string> Files { get; internal set; } = Array.Empty<string>();
        public DateTime StartedUtc { get; internal set; }
        /// <summary>Undo-send deadline (countdown) or when launching began.</summary>
        public DateTime DeadlineUtc { get; internal set; }
        /// <summary>Key of the transfer this card shows (<see cref="TransferView.Key"/>).</summary>
        public string? TransferKey { get; internal set; }
        public TransferView? Status { get; internal set; }
        public DateTime? FinishedUtc { get; internal set; }
        public bool RetryUnavailable { get; internal set; }

        public bool IsFinished => FinishedUtc != null;
        public bool IsDone => Stage == HudStage.Transfer && Status?.State == TransferState.Done;
        public string? Failure => Stage == HudStage.Transfer && Status?.State == TransferState.Failed ? Status.Error ?? "Failed" : null;
        public bool WasCancelledHere => Failure == TransferView.CancelledMessage;
        public bool CanRetry => !Incoming && Failure != null && !WasCancelledHere && Files.Count > 0 && !RetryUnavailable;
    }

    /// <summary>
    /// The transfer HUD's cards and how they change. Pure: the owner feeds it transfer reports and the clock and
    /// carries out what it returns (start a send). Not thread-safe: use from one thread (the UI thread).
    /// </summary>
    public sealed class HudState
    {
        public static readonly TimeSpan UndoDelay = TimeSpan.FromSeconds(3);
        public static readonly TimeSpan AutoHideAfter = TimeSpan.FromSeconds(5);
        public static readonly TimeSpan CancelledHideAfter = TimeSpan.FromSeconds(1.6);
        public static readonly TimeSpan LaunchTimeout = TimeSpan.FromSeconds(30);
        public const int MaxCards = 6;

        private readonly List<HudCard> _cards = new();
        private readonly HashSet<string> _dismissedKeys = new();

        /// <summary>Newest first.</summary>
        public IReadOnlyList<HudCard> Cards => _cards;

        // ------------------------------------------------------------------ undo-send

        /// <summary>Adds an undo-send card; the send starts when <see cref="Due"/> returns it.</summary>
        public HudCard QueueSend(IReadOnlyList<string> files, string peerId, string peerName, string title, DateTime nowUtc, TimeSpan? delay = null)
        {
            var c = new HudCard
            {
                Stage = HudStage.Countdown, Incoming = false, PeerId = peerId, PeerName = peerName, Title = title, Files = files,
                StartedUtc = nowUtc, DeadlineUtc = nowUtc + (delay ?? UndoDelay),
            };
            _cards.Insert(0, c);
            Trim();
            return c;
        }

        /// <summary>Undo pressed: removes the card if nothing has gone out yet.</summary>
        public HudCard? Undo(Guid id)
        {
            var c = _cards.FirstOrDefault(x => x.Id == id);
            if (c == null || c.Stage != HudStage.Countdown) return null;
            _cards.Remove(c);
            return c;
        }

        /// <summary>Cards whose countdown has run out; they move to Launching and the caller must start their sends.</summary>
        public List<HudCard> Due(DateTime nowUtc)
        {
            var due = _cards.Where(c => c.Stage == HudStage.Countdown && c.DeadlineUtc <= nowUtc).ToList();
            foreach (var c in due) { c.Stage = HudStage.Launching; c.DeadlineUtc = nowUtc; }
            return due;
        }

        /// <summary>A send is being started again (Retry) on an existing card.</summary>
        public void Relaunch(Guid id, DateTime nowUtc)
        {
            var c = _cards.FirstOrDefault(x => x.Id == id);
            if (c == null) return;
            if (c.TransferKey != null) _dismissedKeys.Add(c.TransferKey);
            c.TransferKey = null;
            c.Status = null;
            c.FinishedUtc = null;
            c.Stage = HudStage.Launching;
            c.DeadlineUtc = nowUtc;
        }

        /// <summary>Starting a send failed before any transfer existed (device gone, nothing readable…).</summary>
        public void Fail(Guid id, string reason, DateTime nowUtc, bool filesGone = false)
        {
            var c = _cards.FirstOrDefault(x => x.Id == id);
            if (c == null) return;
            c.Status = new TransferView("local-" + c.Id.ToString("N"), c.PeerName, c.Incoming, c.Title, 0, 0, TransferState.Failed, reason)
            {
                PeerId = c.PeerId, Sources = c.Files, FileCount = Math.Max(1, c.Files.Count),
            };
            c.Stage = HudStage.Transfer;
            c.FinishedUtc = nowUtc;
            if (filesGone) c.RetryUnavailable = true;
        }

        /// <summary>True if the card is still waiting for its send's first report (used when a send returns early).</summary>
        public bool IsLaunching(Guid id) => _cards.Any(c => c.Id == id && c.Stage == HudStage.Launching && c.TransferKey == null);

        // ------------------------------------------------------------------ transfer reports

        public void Ingest(TransferView s, DateTime nowUtc)
        {
            if (_dismissedKeys.Contains(s.Key)) return;
            bool finished = s.State != TransferState.Active;
            var card = _cards.FirstOrDefault(c => c.TransferKey == s.Key);
            if (card == null && !s.Incoming)
            {
                // The first report of a send started from a card attaches to it, so it turns into the progress card.
                card = _cards.LastOrDefault(c => c.Stage == HudStage.Launching && c.TransferKey == null && c.PeerId == s.PeerId);
                if (card != null) card.TransferKey = s.Key;
            }
            if (card == null)
            {
                card = new HudCard { Incoming = s.Incoming, PeerId = s.PeerId, PeerName = s.PeerName, TransferKey = s.Key, StartedUtc = nowUtc };
                _cards.Insert(0, card);
            }
            card.Stage = HudStage.Transfer;
            card.Status = s;
            card.Title = s.Label;
            if (s.Sources.Count > 0) card.Files = s.Sources;
            if (finished) card.FinishedUtc ??= nowUtc;
            else card.FinishedUtc = null;
            Trim();
        }

        // ------------------------------------------------------------------ closing

        /// <summary>Close button: the card goes away (a running transfer keeps going).</summary>
        public HudCard? Dismiss(Guid id)
        {
            var c = _cards.FirstOrDefault(x => x.Id == id);
            if (c == null) return null;
            if (c.TransferKey != null) _dismissedKeys.Add(c.TransferKey);
            _cards.Remove(c);
            return c;
        }

        /// <summary>Time-based housekeeping: hides cancelled (and, with <paramref name="autoHide"/>, finished) cards
        /// and fails sends that never started.</summary>
        public void Expire(DateTime nowUtc, bool autoHide)
        {
            foreach (var c in _cards.Where(c => c.Stage == HudStage.Launching && nowUtc - c.DeadlineUtc >= LaunchTimeout).ToList())
                Fail(c.Id, "The transfer did not start", nowUtc);
            foreach (var c in _cards.ToList())
            {
                if (c.FinishedUtc is not DateTime end) continue;
                var age = nowUtc - end;
                if (c.WasCancelledHere ? age >= CancelledHideAfter : autoHide && c.IsDone && age >= AutoHideAfter) Dismiss(c.Id);
            }
        }

        private void Trim()
        {
            while (_cards.Count > MaxCards)
            {
                var old = _cards.LastOrDefault(c => c.IsFinished);
                if (old == null) break;
                Dismiss(old.Id);
            }
        }
    }
}
