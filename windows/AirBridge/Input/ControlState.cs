namespace AirBridge.Input
{
    /// <summary>
    /// Who this PC is controlling / is controlled by (§8.6), for the status line and the Display Arrangement window.
    /// Thread-safe; <see cref="Changed"/> fires on the thread that made the change.
    /// Status priority: controlling &gt; controlled &gt; transient notice (e.g. ctl_busy) &gt; nothing.
    /// </summary>
    public static class ControlState
    {
        private static readonly object Gate = new();
        private static string? _controllingId, _controlledById;
        private static bool _controlledExplicit;
        private static string? _notice;
        private static long _noticeSeq;
        private static string? _lastStatus;
        private static Timer? _noticeTimer;

        public static event Action? Changed;

        /// <summary>Peer this PC's keyboard/mouse is currently driving, or null.</summary>
        public static string? ControllingId { get { lock (Gate) return _controllingId; } }
        /// <summary>Peer holding this PC's control lease, or null.</summary>
        public static string? ControlledById { get { lock (Gate) return _controlledById; } }
        /// <summary>True when the lease came from ctl_enter (cursor pushed over), false for trackpad/mirror input.</summary>
        public static bool ControlledExplicit { get { lock (Gate) return _controlledExplicit; } }

        public static string NameOf(string? id)
        {
            if (id == null) return "";
            var p = PeerDirectory.Find(id);
            if (p != null && !string.IsNullOrEmpty(p.Name)) return p.Name;
            var pl = Layout.Snapshot().GetValueOrDefault(id);
            if (pl != null && !string.IsNullOrEmpty(pl.Name)) return pl.Name;
            return "another device";
        }

        public static void SetControlling(string? peerId)
        {
            lock (Gate) { if (_controllingId == peerId) return; _controllingId = peerId; }
            Publish();
        }

        public static void SetControlledBy(string? peerId, bool isExplicit)
        {
            lock (Gate)
            {
                if (_controlledById == peerId && _controlledExplicit == isExplicit) return;
                _controlledById = peerId; _controlledExplicit = isExplicit;
            }
            Publish();
        }

        /// <summary>Short-lived notice shown when nothing more important is going on.</summary>
        public static void Notice(string text, int ms = 4000)
        {
            long seq;
            lock (Gate)
            {
                _notice = text;
                seq = ++_noticeSeq;
                _noticeTimer?.Dispose();
                _noticeTimer = new Timer(_ =>
                {
                    lock (Gate) { if (_noticeSeq != seq) return; _notice = null; }
                    Publish();
                }, null, ms, Timeout.Infinite);
            }
            Publish();
        }

        private static readonly object DeliverGate = new();
        private static long _pubSeq, _deliveredSeq;
        private static string? _pending;

        private static void Publish()
        {
            bool deliver = false;
            lock (Gate)
            {
                string? status;
                if (_controllingId != null) status = $"Controlling {NameOf(_controllingId)} · Ctrl+Alt+Esc to return";
                else if (_controlledById != null) status = $"Controlled by {NameOf(_controlledById)}";
                else status = _notice;
                if (status != _lastStatus) { _lastStatus = status; _pending = status; _pubSeq++; deliver = true; }
            }
            // Delivered on the thread pool, latest-wins, so the hook/target threads never wait on the UI and the UI
            // thread never waits on a delivery lock.
            if (deliver) ThreadPool.QueueUserWorkItem(_ => Deliver());
            try { Changed?.Invoke(); } catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"ControlState.Changed: {ex}"); }
        }

        private static void Deliver()
        {
            lock (DeliverGate)
            {
                string? text; long seq;
                lock (Gate) { text = _pending; seq = _pubSeq; }
                if (seq <= _deliveredSeq) return;
                _deliveredSeq = seq;
                NetHooks.SetControlStatus(text);
            }
        }
    }
}
