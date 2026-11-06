using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;

namespace AirBridge.Net
{
    public enum TransferState { Active, Done, Failed }

    /// <summary>One file transfer as shown in the tray.</summary>
    public sealed record TransferView(string Key, string PeerName, bool Incoming, string Label, long Total, long Done,
                                      TransferState State, string? Error)
    {
        public double Fraction => Total <= 0 ? (State == TransferState.Done ? 1 : 0) : Math.Min(1.0, (double)Done / Total);
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
        private readonly Timer _timer;
        private int _dirty;

        /// <summary>Raised on a timer thread when the list changed (throttled).</summary>
        public event Action? Changed;

        public TransferBoard()
        {
            _timer = new Timer(_ => Flush(), null, 250, 250);
        }

        public void Start(string key, string peerName, bool incoming, string label, long total)
        {
            lock (_gate) _items[key] = (new TransferView(key, peerName, incoming, label, total, 0, TransferState.Active, null), DateTime.MaxValue);
            MarkDirty();
        }

        public void Progress(string key, long done)
        {
            lock (_gate)
            {
                if (!_items.TryGetValue(key, out var e) || e.View.State != TransferState.Active || e.View.Done == done) return;
                _items[key] = (e.View with { Done = done }, e.EndedUtc);
            }
            MarkDirty();
        }

        public void Finish(string key, bool ok, string? error = null)
        {
            lock (_gate)
            {
                if (!_items.TryGetValue(key, out var e) || e.View.State != TransferState.Active) return;
                var v = e.View with { State = ok ? TransferState.Done : TransferState.Failed, Error = ok ? null : error, Done = ok ? e.View.Total : e.View.Done };
                _items[key] = (v, DateTime.UtcNow);
            }
            MarkDirty();
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
