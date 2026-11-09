using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;

namespace AirBridge.Net
{
    public enum TransferState { Active, Done, Failed }

    /// <summary>One file transfer as shown in the tray and the transfer cards.</summary>
    public sealed record TransferView(string Key, string PeerName, bool Incoming, string Label, long Total, long Done,
                                      TransferState State, string? Error)
    {
        public double Fraction => Total <= 0 ? (State == TransferState.Done ? 1 : 0) : Math.Min(1.0, (double)Done / Total);
        /// <summary>The other device's id (for Retry and for matching a just-started send to its card).</summary>
        public string PeerId { get; init; } = "";
        public int FileCount { get; init; } = 1;
        /// <summary>Files being sent (outgoing), so a failed send can be retried.</summary>
        public IReadOnlyList<string> Sources { get; init; } = Array.Empty<string>();
        /// <summary>Received files as saved on disk (incoming, once done).</summary>
        public IReadOnlyList<string> Saved { get; init; } = Array.Empty<string>();
        /// <summary>Outgoing offer not answered yet.</summary>
        public bool Waiting { get; init; }
        public double BytesPerSec { get; init; }

        /// <summary><see cref="Error"/> text when this side's user stopped the transfer.</summary>
        public const string CancelledMessage = "Cancelled";
    }

    /// <summary>
    /// UI-facing list of recent transfers (in and out). Updates are cheap; <see cref="Changed"/> is throttled to
    /// at most ~4 per second so a fast transfer does not flood the dispatcher. Finished entries linger briefly.
    /// </summary>
    public sealed class TransferBoard : IDisposable
    {
        private static readonly TimeSpan Linger = TimeSpan.FromSeconds(8);
        private readonly object _gate = new();
        private readonly Dictionary<string, (TransferView View, DateTime EndedUtc)> _items = new();
        private readonly Dictionary<string, DateTime> _startedUtc = new();
        private readonly Dictionary<string, Action> _cancel = new();
        private readonly Timer _timer;
        private int _dirty;

        /// <summary>Raised on a timer thread when the list changed (throttled).</summary>
        public event Action? Changed;

        public TransferBoard()
        {
            _timer = new Timer(_ => Flush(), null, 250, 250);
        }

        public void Start(string key, string peerName, bool incoming, string label, long total,
                          string peerId = "", IReadOnlyList<string>? sources = null, int fileCount = 1, bool waiting = false)
        {
            var v = new TransferView(key, peerName, incoming, label, total, 0, TransferState.Active, null)
            {
                PeerId = peerId, Sources = sources ?? Array.Empty<string>(), FileCount = Math.Max(1, fileCount), Waiting = waiting,
            };
            lock (_gate)
            {
                _items[key] = (v, DateTime.MaxValue);
                _startedUtc[key] = DateTime.UtcNow;
            }
            MarkDirty();
        }

        /// <summary>The receiver accepted an outgoing offer: speed is measured from here.</summary>
        public void MarkAccepted(string key)
        {
            lock (_gate)
            {
                if (!_items.TryGetValue(key, out var e) || !e.View.Waiting) return;
                _items[key] = (e.View with { Waiting = false }, e.EndedUtc);
                _startedUtc[key] = DateTime.UtcNow;
            }
            MarkDirty();
        }

        public void Progress(string key, long done)
        {
            lock (_gate)
            {
                if (!_items.TryGetValue(key, out var e) || e.View.State != TransferState.Active || e.View.Done == done) return;
                double secs = _startedUtc.TryGetValue(key, out var t0) ? (DateTime.UtcNow - t0).TotalSeconds : 0;
                _items[key] = (e.View with { Done = done, Waiting = false, BytesPerSec = secs > 0.5 ? done / secs : 0 }, e.EndedUtc);
            }
            MarkDirty();
        }

        public void Finish(string key, bool ok, string? error = null, IReadOnlyList<string>? saved = null)
        {
            lock (_gate)
            {
                _cancel.Remove(key);
                if (!_items.TryGetValue(key, out var e) || e.View.State != TransferState.Active) return;
                var v = e.View with
                {
                    State = ok ? TransferState.Done : TransferState.Failed, Error = ok ? null : error, Done = ok ? e.View.Total : e.View.Done,
                    Saved = saved ?? e.View.Saved, Waiting = false, BytesPerSec = 0,
                };
                _items[key] = (v, DateTime.UtcNow);
            }
            MarkDirty();
        }

        /// <summary>How the UI stops a running transfer (removed when it finishes).</summary>
        public void RegisterCancel(string key, Action cancel)
        {
            lock (_gate) _cancel[key] = cancel;
        }

        /// <summary>Stops a running transfer (either direction). False if it is unknown or already over.</summary>
        public bool Cancel(string key)
        {
            Action? a;
            lock (_gate) { if (!_cancel.Remove(key, out a)) return false; }
            try { a(); } catch (Exception ex) { Log.Warn($"Cancel {key} failed: {ex.Message}"); }
            return true;
        }

        public IReadOnlyList<TransferView> Snapshot()
        {
            lock (_gate) return _items.Values.Select(e => e.View).OrderBy(v => v.State != TransferState.Active).ThenBy(v => v.Key).ToList();
        }

        public bool HasActive { get { lock (_gate) return _items.Values.Any(e => e.View.State == TransferState.Active); } }

        private void MarkDirty() => Interlocked.Exchange(ref _dirty, 1);

        private void Flush()
        {
            var now = DateTime.UtcNow;
            lock (_gate)
            {
                foreach (var k in _items.Where(kv => now - kv.Value.EndedUtc > Linger).Select(kv => kv.Key).ToList())
                {
                    _items.Remove(k);
                    _startedUtc.Remove(k);
                    _dirty = 1;
                }
            }
            if (Interlocked.Exchange(ref _dirty, 0) == 1)
            {
                try { Changed?.Invoke(); } catch (Exception ex) { Log.Error("Transfers handler threw", ex); }
            }
        }

        public void Dispose() => _timer.Dispose();
    }
}
