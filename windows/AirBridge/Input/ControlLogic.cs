namespace AirBridge.Input
{
    // Pure, platform-free control logic (protocol.md §8.1, §8.6). No Win32, no WPF, no clocks of its own: callers pass
    // the time in milliseconds. Checked by AirBridge.InputTests on any OS.

    /// <summary>
    /// Who controls whom, shared by the controller (hook thread) and the target (its own thread) so they can refuse
    /// control loops (§8.6 "No loops"): this PC never accepts control from a peer it is driving, and never captures
    /// onward while it is itself controlled. Thread-safe.
    /// </summary>
    public sealed class ControlGate
    {
        public static readonly ControlGate Shared = new();

        private readonly object _lock = new();
        private string? _controlling, _controlledBy;

        /// <summary>Peer this PC's own mouse/keyboard currently drives (set by the controller), or null.</summary>
        public string? Controlling
        {
            get { lock (_lock) return _controlling; }
            set { lock (_lock) _controlling = value; }
        }

        /// <summary>Peer holding this PC's control lease (set by the target), or null.</summary>
        public string? ControlledBy
        {
            get { lock (_lock) return _controlledBy; }
            set { lock (_lock) _controlledBy = value; }
        }

        /// <summary>Clears <see cref="Controlling"/> only if it still names <paramref name="peer"/>.</summary>
        public void EndControlling(string peer)
        {
            lock (_lock) { if (_controlling == peer) _controlling = null; }
        }

        /// <summary>Target: a ctl_enter from <paramref name="peer"/> must be answered with ctl_leave reason "loop".</summary>
        public bool RefusesEnterFrom(string peer) => Controlling == peer;

        /// <summary>Target: ptr_*/key/action from <paramref name="peer"/> are dropped and answered with ctl_busy (holder = this PC).</summary>
        public bool RefusesInputFrom(string peer) => Controlling == peer;

        /// <summary>Controller: no capture of anyone while this PC is under a lease (no chaining).</summary>
        public bool MayCapture => ControlledBy == null;
    }

    /// <summary>"At most once per interval per peer" limiter (ctl_busy every 2 s, §8.6). Not thread-safe.</summary>
    public sealed class PerPeerRateLimit
    {
        private readonly double _intervalMs;
        private readonly Dictionary<string, double> _last = new();

        public PerPeerRateLimit(double intervalMs) { _intervalMs = intervalMs; }

        /// <summary>True (and records the send) if nothing went to <paramref name="peer"/> within the interval.</summary>
        public bool TryAcquire(string peer, double nowMs)
        {
            if (_last.TryGetValue(peer, out double t) && nowMs - t < _intervalMs) return false;
            _last[peer] = nowMs;
            return true;
        }

        public void Forget(string peer) => _last.Remove(peer);

        public void Retain(ISet<string> alive)
        {
            foreach (var k in _last.Keys.Where(k => !alive.Contains(k)).ToList()) _last.Remove(k);
        }
    }

    /// <summary>Peers that refused or did not answer a capture are not captured again for a while (§8.1). Not thread-safe.</summary>
    public sealed class RefusalList
    {
        public const double RefuseMs = 5000;
        private readonly Dictionary<string, double> _until = new();

        public void Refuse(string peer, double nowMs) => _until[peer] = nowMs + RefuseMs;

        public bool IsRefused(string peer, double nowMs) => _until.TryGetValue(peer, out double u) && nowMs < u;
    }

    /// <summary>Why a capture ended; decides the notice and whether the peer is refused for a while.</summary>
    public enum CaptureEnd
    {
        /// <summary>The user moved back through the entry edge (controller-side depth estimate).</summary>
        MovedBack,
        /// <summary>No ctl_ack within 600 ms of ctl_enter.</summary>
        NoAck,
        /// <summary>ctl_leave reason "unavailable".</summary>
        Unavailable,
        /// <summary>ctl_leave reason "loop".</summary>
        Loop,
        /// <summary>Anything else (target ctl_leave, hotkey, watchdog, peer gone, ctl_busy, …).</summary>
        Other,
    }

    public static class CaptureEndExt
    {
        /// <summary>Peers that could not or would not take control are not captured again for 5 s.</summary>
        public static bool Refuses(this CaptureEnd e) => e is CaptureEnd.NoAck or CaptureEnd.Unavailable or CaptureEnd.Loop;

        /// <summary>Notice for the user, or null when the end needs no explanation.</summary>
        public static string? Notice(this CaptureEnd e, string name, bool android)
        {
            string hint = android ? " Turn on the AirBridge accessibility service on it." : "";
            return e switch
            {
                CaptureEnd.NoAck => $"{name} isn't ready to be controlled.{hint}",
                CaptureEnd.Unavailable => $"{name} can't be controlled right now.{hint}",
                CaptureEnd.Loop => $"{name} is controlling this PC, so it can't be controlled from here.",
                _ => null,
            };
        }

        /// <summary>Maps a ctl_leave "reason" from the target.</summary>
        public static CaptureEnd FromLeaveReason(string? reason) => reason switch
        {
            "unavailable" => CaptureEnd.Unavailable,
            "loop" => CaptureEnd.Loop,
            _ => CaptureEnd.Other,
        };
    }

    /// <summary>
    /// Controller-side state of one capture (§8.1 "Acknowledgement", "Escape is the controller's job"): waits for
    /// ctl_ack, and estimates how deep into the target the cursor is from the deltas it sends, so the way back never
    /// depends on the target. Units are the controller's own pointer units (physical px on Windows).
    /// </summary>
    public sealed class CaptureEscape
    {
        public const double AckTimeoutMs = 600;
        /// <summary>Moving back this far (DIP; callers scale to px) through the entry edge returns control.</summary>
        public const double ReturnSlackDip = 40;
        /// <summary>Target extent assumed when the peer reported no screen size (controller units).</summary>
        public const double UnknownExtent = 1200;
        public const double MinExtent = 200;

        private readonly double _startMs;
        private readonly double _slack;

        public Edge PcEdge { get; }
        /// <summary>Target extent along the travel axis, in controller units.</summary>
        public double MaxDepth { get; }
        public bool Acked { get; private set; }
        /// <summary>How far into the target the cursor is (≥ −slack before returning).</summary>
        public double Depth { get; private set; }
        /// <summary>Sideways travel since entry (along the PC edge: +y for left/right, +x for top/bottom).</summary>
        public double Lateral { get; private set; }

        /// <param name="pcEdge">This PC's edge the cursor left through.</param>
        /// <param name="maxDepth">From <see cref="TargetExtent"/>.</param>
        /// <param name="slackPx">Return slack in controller units (40 DIP × monitor scale).</param>
        public CaptureEscape(Edge pcEdge, double maxDepth, double slackPx, double startMs)
        {
            PcEdge = pcEdge;
            MaxDepth = Math.Max(MinExtent, maxDepth);
            _slack = Math.Max(1, slackPx);
            _startMs = startMs;
        }

        /// <summary>
        /// Target extent along the travel axis in controller units: the peer's screen (hello "screen") divided by the
        /// target's gain (1.6 target px per controller unit for Android, 1.0 otherwise); 1200 when unknown.
        /// </summary>
        public static double TargetExtent(Edge pcEdge, string? platform, int screenW, int screenH)
        {
            double gain = platform == "android" ? 1.6 : 1.0;
            int px = pcEdge.IsVertical() ? screenW : screenH;
            return px > 0 ? px / gain : UnknownExtent;
        }

        /// <summary>True the first time; later acks are ignored.</summary>
        public bool Ack()
        {
            if (Acked) return false;
            Acked = true;
            return true;
        }

        public bool AckOverdue(double nowMs) => !Acked && nowMs - _startMs > AckTimeoutMs;

        /// <summary>Outward (into the target) component of a delta for this PC edge.</summary>
        public static double Outward(Edge pcEdge, double dx, double dy) => pcEdge switch
        {
            Edge.Left => -dx,
            Edge.Right => dx,
            Edge.Top => -dy,
            _ => dy,
        };

        /// <summary>
        /// Records a delta that was sent to the target. Returns true when the user has moved back through the entry
        /// edge by more than the slack and no remote button is held, i.e. control should return now.
        /// </summary>
        public bool Move(double dx, double dy, bool buttonHeld)
        {
            Depth = Math.Min(MaxDepth, Depth + Outward(PcEdge, dx, dy));
            Lateral += PcEdge.IsVertical() ? dy : dx;
            return !buttonHeld && Depth < -_slack;
        }

        /// <summary>Position along the PC edge where the cursor reappears: entry point plus sideways travel, kept inside the span.</summary>
        public double ReturnAlong(double entryAlong, double spanStart, double spanEnd) =>
            ReturnAlong(entryAlong, Lateral, spanStart, spanEnd);

        public static double ReturnAlong(double entryAlong, double lateral, double spanStart, double spanEnd)
        {
            double lo = Math.Min(spanStart, spanEnd), hi = Math.Max(spanStart, spanEnd);
            return Math.Clamp(entryAlong + lateral, lo, hi);
        }
    }

    /// <summary>
    /// Target-side safety net (§8.6 "No stray state"): the lease is dropped when nothing at all (ping/pong included)
    /// was heard from the holder for 10 s, or when the holder has no ready session any more.
    /// </summary>
    public static class HolderWatch
    {
        public const double SilenceMs = 10000;

        /// <param name="lastHeardMs">Latest time anything arrived from the holder, or null if it has no ready session.</param>
        public static bool ShouldDrop(double? lastHeardMs, double nowMs) =>
            lastHeardMs is not double t || nowMs - t > SilenceMs;
    }

    /// <summary>
    /// Automatic placement of newly seen controllable peers (§8.1 "Edge assignment is explicit"): only a free LEFT or
    /// RIGHT edge, middle half of it (0.25–0.75); top and bottom (taskbar) edges and full-length spans are never chosen
    /// automatically. Everything else is placed by the user in Display Arrangement.
    /// </summary>
    public static class AutoPlacement
    {
        public const double Offset = 0.25, Size = 0.5;

        /// <summary>One-time clean-up of an automatic placement on a full top/bottom edge (it hijacked the taskbar).</summary>
        public static bool ClearFullTopBottom(IDictionary<string, PeerPlacement> map)
        {
            bool changed = false;
            foreach (var p in map.Values)
            {
                if (EdgeExt.Parse(p.Edge) is Edge.Top or Edge.Bottom && p.Size >= 0.999)
                {
                    p.Edge = null;
                    changed = true;
                }
            }
            return changed;
        }

        /// <summary>
        /// Gives each peer not yet in <paramref name="map"/> a free right (preferred) or left edge with the middle span,
        /// or records it unplaced when both are taken. Peers already in the map (placed or deliberately unplaced) are
        /// never touched. Returns true if anything changed.
        /// </summary>
        public static bool AssignNew(IDictionary<string, PeerPlacement> map, IEnumerable<(string Id, string Name)> peers)
        {
            bool changed = false;
            foreach (var (id, name) in peers)
            {
                if (string.IsNullOrEmpty(id) || map.ContainsKey(id)) continue;
                var used = new HashSet<string>(map.Values.Select(v => v.Edge ?? "").Where(e => e.Length > 0));
                string? edge = new[] { Edge.Right, Edge.Left }.Select(e => e.ToWire()).FirstOrDefault(e => !used.Contains(e));
                map[id] = new PeerPlacement { Name = name, Edge = edge, Offset = Offset, Size = Size };
                changed = true;
            }
            return changed;
        }
    }
}
