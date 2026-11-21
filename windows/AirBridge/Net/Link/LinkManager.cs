using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Windows.Networking.Connectivity;
using Windows.Networking.NetworkOperators;

namespace AirBridge.Net.Link
{
    public enum LinkPhase { Off, Starting, Offered, Joined, Ready, Closing, Failed }

    /// <summary>What the tray chip shows for one peer.</summary>
    public sealed record LinkStatusView(LinkPhase Phase, string Text, string? Reason);

    /// <summary>
    /// Persisted link state in the data dir (link.json): the wfd_host capability evidence and the per-peer credential
    /// generation (§10.1 <c>gen</c>, bumped by "Rotate Wi-Fi key").
    /// </summary>
    public sealed class LinkSettings
    {
        private sealed class Stored
        {
            public bool GoStartedOnce { get; set; }
            public DateTime? GoStartedAt { get; set; }
            public bool GoFailedSinceSuccess { get; set; }
            public Dictionary<string, int> Gens { get; set; } = new();
        }

        private static readonly string FilePath = Path.Combine(Log.Dir, "link.json");
        private readonly object _gate = new();
        private readonly Stored _s;
        private readonly Lazy<bool> _canConstruct = new(WfdLegacyHost.CanConstructPublisher, LazyThreadSafetyMode.ExecutionAndPublication);

        public LinkSettings()
        {
            try { _s = File.Exists(FilePath) ? JsonSerializer.Deserialize<Stored>(File.ReadAllText(FilePath)) ?? new Stored() : new Stored(); }
            catch (Exception ex) { Log.Warn($"link.json unreadable ({ex.Message}); starting fresh"); _s = new Stored(); }
        }

        /// <summary>
        /// hello cap <c>wfd_host</c> policy (PROTOCOL §10.1: "Windows after at least one successful start"):
        /// 1. a group-owner start has succeeded on this machine before (persisted flag) → advertise, always;
        /// 2. otherwise, if the last real start attempt failed with no_go_support (no Started after the retry, or Start() threw)
        ///    → do NOT advertise (until a later start succeeds, e.g. from the Debug window or a phone-initiated request);
        /// 3. otherwise (never tried) → advertise only if a <c>WiFiDirectAdvertisementPublisher</c> can be constructed without
        ///    throwing (activation probe, cached per process; it does not start the radio). This is optimistic on purpose: without
        ///    it the cap could never be earned, because phones only request links from hosts that advertise it.
        /// radio_off / hotspot_active failures say nothing about driver support and don't change the flags.
        /// </summary>
        public bool AdvertiseWfdHost
        {
            get
            {
                lock (_gate)
                {
                    if (_s.GoStartedOnce) return true;
                    if (_s.GoFailedSinceSuccess) return false;
                }
                return _canConstruct.Value;
            }
        }

        public bool GoStartedOnce { get { lock (_gate) return _s.GoStartedOnce; } }

        /// <summary>Warms the construction probe off the hello path.</summary>
        public void Probe() => _ = _canConstruct.Value;

        public void MarkGoStarted()
        {
            lock (_gate)
            {
                if (_s.GoStartedOnce && !_s.GoFailedSinceSuccess) return;
                _s.GoStartedOnce = true;
                _s.GoStartedAt = DateTime.UtcNow;
                _s.GoFailedSinceSuccess = false;
            }
            Save();
        }

        public void MarkGoUnsupported()
        {
            lock (_gate)
            {
                if (_s.GoFailedSinceSuccess) return;
                _s.GoFailedSinceSuccess = true;
            }
            Save();
        }

        public int GenFor(string peerId) { lock (_gate) return _s.Gens.TryGetValue(peerId, out int g) ? g : 0; }

        /// <summary>"Rotate Wi-Fi key": next gen (mod 256). The phone shows its join approval dialog once more.</summary>
        public int BumpGen(string peerId)
        {
            int g;
            lock (_gate) { g = (GenFor(peerId) + 1) & 0xFF; _s.Gens[peerId] = g; }
            Save();
            return g;
        }

        private void Save()
        {
            try
            {
                string json;
                lock (_gate) json = JsonSerializer.Serialize(_s);
                Directory.CreateDirectory(Log.Dir);
                File.WriteAllText(FilePath, json);
            }
            catch (Exception ex) { Log.Warn($"link.json save failed: {ex.Message}"); }
        }
    }

    /// <summary>
    /// Per-<c>lid</c> state machine for the Windows host role of PROTOCOL §10.1 (<c>wfd_legacy</c>, topology T1), signalled over the
    /// existing ready session (LAN in phase A0, BLE later):
    /// <code>
    /// link_request (or local "Send fast")  → Starting: hotspot check → derived creds → fresh publisher → Started (≤5 s, 1 retry)
    ///   → WFD IPv4 (10×500 ms) → link_offer                                   → Offered
    /// link_ready from the phone                                               → Joined   (log only; the dial is what counts)
    /// phone's inbound session handshakes on the WFD adapter (Server listener) → Ready    (PeerService replaces the old session: bye replaced)
    /// 20 s without file/video bytes, ttl (120 s, +60 s while bytes flow), user, link_done, socket/adapter loss → Closing
    ///   → link_done sent, LAN redial allowed (make-before-break back), ≤3 s later the WFD session is closed and the publisher stopped → Off
    /// any failure before Ready (30 s whole-link, 20 s without link_ready after the offer, publisher/IP errors, link_fail) → Failed
    /// </code>
    /// One link per peer at a time. Every transition logs one "AirBridge.Link …" line (research §6.4).
    /// </summary>
    public sealed class LinkManager : IDisposable
    {
        public static readonly TimeSpan WholeLinkTimeout = TimeSpan.FromSeconds(30);
        public static readonly TimeSpan ReadyAfterOfferTimeout = TimeSpan.FromSeconds(20);
        public static readonly TimeSpan IdleTimeout = TimeSpan.FromSeconds(20);
        public static readonly TimeSpan TtlExtension = TimeSpan.FromSeconds(60);
        public static readonly TimeSpan ClosingGrace = TimeSpan.FromSeconds(3);
        private static readonly TimeSpan FailShownFor = TimeSpan.FromSeconds(90);

        private sealed class ActiveLink
        {
            public required int Lid;
            public required string PeerId;
            public required string PeerName;
            public required bool Mine;
            public required string Why;
            public long Bytes;
            public readonly long T0 = Environment.TickCount64;
            public LinkPhase Phase = LinkPhase.Starting;
            public Session? Signal;
            public Session? LinkSession;
            public WfdLegacyHost? Host;
            public WfdAdapterInfo? Adapter;
            public WfdWatchdog? Watchdog;
            public readonly CancellationTokenSource Cts = new();
            public readonly TaskCompletionSource<string?> ReadyTcs = new(TaskCreationOptions.RunContinuationsAsynchronously);
            public long OfferAt, ReadyAt, ClosingAt, LastActivity, TtlDeadline;
            public long LastBinary, BinaryAtReady;
            public int TtlSeconds = LinkWire.DefaultTtlSeconds;
        }

        private readonly PeerService _svc;
        private readonly object _gate = new();
        private readonly Dictionary<string, ActiveLink> _links = new();               // by peer id
        private readonly Dictionary<string, (string Reason, long At)> _lastFail = new(); // for the chip
        private readonly Timer _tick;
        private int _nextLid = 0x40000000 + Random.Shared.Next(0x10000); // far from the phone's small counters
        private int _disposed;

        public LinkSettings Settings { get; } = new();

        /// <summary>Raised (any thread) when a link status changed.</summary>
        public event Action? Changed;

        public LinkManager(PeerService svc)
        {
            _svc = svc;
            _tick = new Timer(_ => Tick(), null, 1000, 1000);
        }

        // ================================================================== queries (any thread)

        public LinkStatusView StatusFor(string peerId)
        {
            lock (_gate)
            {
                if (_links.TryGetValue(peerId, out var l))
                    return l.Phase switch
                    {
                        LinkPhase.Ready => new LinkStatusView(l.Phase, "Ready", null),
                        LinkPhase.Closing => new LinkStatusView(l.Phase, "Closing", null),
                        _ => new LinkStatusView(l.Phase, "Starting", null),
                    };
                if (_lastFail.TryGetValue(peerId, out var f) && Environment.TickCount64 - f.At < FailShownFor.TotalMilliseconds)
                    return new LinkStatusView(LinkPhase.Failed, $"Failed: {f.Reason}", f.Reason);
            }
            return new LinkStatusView(LinkPhase.Off, "Off", null);
        }

        /// <summary>Client dial loop: the "do not redial after bye replaced" pause does not apply while a link for this peer is
        /// active (§10.1 exception) — the replaced LAN session must come back when the link ends.</summary>
        public bool SuppressReplacedPause(string peerId)
        {
            lock (_gate) return _links.ContainsKey(peerId);
        }

        /// <summary>Client dial loop: may dial the LAN although a ready session exists, because that session is the link session
        /// being torn down (make-before-break back). The new LAN session then replaces it (bye replaced).</summary>
        public bool AllowDialWhileReady(string peerId)
        {
            lock (_gate) return _links.TryGetValue(peerId, out var l) && l.Phase == LinkPhase.Closing;
        }

        // ================================================================== local trigger ("Send fast", mirror)

        /// <summary>
        /// Brings up a Wi-Fi Direct link to <paramref name="peerId"/> (or joins one already starting) and returns null once the new
        /// session is ready (the caller then sends on it: PeerService routes by peer to the newest ready session), else a §10.1
        /// fail reason. The caller falls back to the existing session on failure. <paramref name="why"/>: xfer | mirror | user.
        /// Windows hosts, so it sends link_offer directly (no link_request; research §3 "PC-initiated sends skip step 1").
        /// </summary>
        public async Task<string?> EnsureLinkAsync(string peerId, string why, long bytes, CancellationToken ct)
        {
            ActiveLink? l;
            bool start = false;
            lock (_gate)
            {
                if (_links.TryGetValue(peerId, out l))
                {
                    if (l.Phase == LinkPhase.Ready) return null;
                    if (l.Phase == LinkPhase.Closing) return LinkWire.Reason.Busy;
                }
                else
                {
                    var s = _svc.ReadySessionFor(peerId);
                    if (s == null) return LinkWire.Reason.Timeout;
                    l = new ActiveLink
                    {
                        Lid = Interlocked.Increment(ref _nextLid), PeerId = peerId, PeerName = s.PeerName, Mine = true, Why = why,
                        Bytes = bytes, Signal = s,
                    };
                    _links[peerId] = l;
                    _lastFail.Remove(peerId);
                    start = true;
                }
            }
            if (start) { RaiseChanged(); _ = Task.Run(() => RunHostAsync(l)); }
            using (ct.Register(() => Fail(l, LinkWire.Reason.User, send: true)))
                return await l.ReadyTcs.Task.ConfigureAwait(false);
        }

        /// <summary>User stop (tray/Debug): link_done user after Ready, link_fail user before.</summary>
        public void Stop(string peerId)
        {
            ActiveLink? l;
            lock (_gate) _links.TryGetValue(peerId, out l);
            if (l == null) return;
            if (l.Phase == LinkPhase.Ready) BeginClosing(l, LinkWire.Done.User, send: true);
            else if (l.Phase != LinkPhase.Closing) Fail(l, LinkWire.Reason.User, send: true);
        }

        // ================================================================== messages (network thread)

        public void OnMessage(Session s, string t, JsonObject o)
        {
            string peerId = s.PeerId!;
            int? lidOpt = LinkWire.Lid(o);
            if (lidOpt is not int lid)
            {
                LinkLog.Ev("?", "bad_msg", ("peer", LinkWire.Id8(peerId)), ("t", t));
                return;
            }
            ActiveLink? l;
            lock (_gate) _links.TryGetValue(peerId, out l);
            bool match = l != null && l.Lid == lid;

            switch (t)
            {
                case "link_request":
                {
                    if (l != null)
                    {
                        if (match && !l.Mine) return; // duplicate
                        s.EnqueueJson(LinkWire.Fail(lid, LinkWire.Reason.Busy));
                        LinkLog.Ev(lid, "fail", ("peer", LinkWire.Id8(peerId)), ("reason", LinkWire.Reason.Busy), ("why", "link_active"));
                        return;
                    }
                    if (!LinkWire.Types(o).Contains(LinkWire.TypeWfdLegacy))
                    {
                        // Only wfd_legacy (Windows hosts) exists on Windows in phase A0; wfd_go (Windows joins) is not built.
                        s.EnqueueJson(LinkWire.Fail(lid, LinkWire.Reason.NoGoSupport));
                        LinkLog.Ev(lid, "fail", ("peer", LinkWire.Id8(peerId)), ("reason", LinkWire.Reason.NoGoSupport), ("why", "types"));
                        return;
                    }
                    if (s.Files.HasActiveTransfers)
                    {
                        // Migrating now would kill a transfer running on this session.
                        s.EnqueueJson(LinkWire.Fail(lid, LinkWire.Reason.Busy));
                        LinkLog.Ev(lid, "fail", ("peer", LinkWire.Id8(peerId)), ("reason", LinkWire.Reason.Busy), ("why", "xfer_running"));
                        return;
                    }
                    var nl = new ActiveLink
                    {
                        Lid = lid, PeerId = peerId, PeerName = s.PeerName, Mine = false, Why = LinkWire.Str(o, "why") ?? "user",
                        Bytes = LinkWire.Long(o, "bytes") ?? 0, Signal = s,
                    };
                    lock (_gate)
                    {
                        if (_links.ContainsKey(peerId)) { s.EnqueueJson(LinkWire.Fail(lid, LinkWire.Reason.Busy)); return; }
                        _links[peerId] = nl;
                        _lastFail.Remove(peerId);
                    }
                    RaiseChanged();
                    _ = Task.Run(() => RunHostAsync(nl));
                    return;
                }
                case "link_ready":
                    if (!match) { LinkLog.Ev(lid, "stale_msg", ("t", t)); return; }
                    lock (_gate) if (l!.Phase == LinkPhase.Offered) l.Phase = LinkPhase.Joined;
                    LinkLog.Ev(lid, "joined", ("ms", Environment.TickCount64 - l!.T0), ("addr", LinkWire.Str(o, "addr")),
                               ("freq", LinkWire.Long(o, "freq")), ("sta", LinkWire.Str(o, "sta")));
                    RaiseChanged();
                    return;
                case "link_fail":
                    if (!match) { LinkLog.Ev(lid, "stale_msg", ("t", t)); return; }
                    Fail(l!, LinkWire.Str(o, "reason") ?? LinkWire.Reason.JoinFailed, send: false, fromPeer: true);
                    return;
                case "link_done":
                    if (!match) { LinkLog.Ev(lid, "stale_msg", ("t", t)); return; }
                    if (l!.Phase == LinkPhase.Ready) BeginClosing(l, LinkWire.Str(o, "reason") ?? LinkWire.Done.User, send: false);
                    else if (l.Phase != LinkPhase.Closing) Fail(l, LinkWire.Reason.User, send: false, fromPeer: true);
                    return;
                case "link_offer":
                    // A phone-hosted group (wfd_go, T2): Windows would join as a Wi-Fi client — not part of phase A0.
                    s.EnqueueJson(LinkWire.Fail(lid, LinkWire.Reason.JoinFailed));
                    LinkLog.Ev(lid, "fail", ("peer", LinkWire.Id8(peerId)), ("reason", LinkWire.Reason.JoinFailed),
                               ("why", $"offer type {LinkWire.Str(o, "type")} not supported"));
                    return;
                default:
                    LinkLog.Ev(lid, "ignored", ("t", t)); // unknown link_* type: ignore safely (§1 rule 2)
                    return;
            }
        }

        // ================================================================== host flow

        private async Task RunHostAsync(ActiveLink l)
        {
            string lidS = l.Lid.ToString(System.Globalization.CultureInfo.InvariantCulture);
            var ct = l.Cts.Token;
            try
            {
                LinkLog.Ev(lidS, "request", ("peer", LinkWire.Id8(l.PeerId)), ("type", LinkWire.TypeWfdLegacy), ("why", l.Why),
                           ("bytes", l.Bytes), ("by", l.Mine ? "pc" : "peer"));

                byte[]? k = _svc.Store.GetKey(l.PeerId);
                if (k == null) { Fail(l, LinkWire.Reason.Busy, send: true, detail: "no pairing key"); return; }
                int gen = Settings.GenFor(l.PeerId);
                WfdCreds creds;
                try { creds = LinkCreds.Wfd(k, _svc.Identity.Id, l.PeerId, gen); }
                finally { CryptographicOperations.ZeroMemory(k); }

                if (await HotspotActiveAsync().ConfigureAwait(false))
                {
                    Fail(l, LinkWire.Reason.HotspotActive, send: true, detail: "tethering state On");
                    return;
                }

                // STA channel (diagnostic; the GO follows it) in parallel with the start.
                var channelTask = WfdInterface.StaChannelAsync();
                var upBefore = WfdInterface.SnapshotUpIds();
                var host = new WfdLegacyHost(creds, lidS);
                host.Lost += reason => OnLost(l, reason == "stopped" ? "publisher_stopped" : reason);
                lock (_gate)
                {
                    if (!IsCurrent(l)) { host.Dispose(); return; }
                    l.Host = host;
                }

                var started = await host.StartAsync(ct).ConfigureAwait(false);
                if (!started.Ok)
                {
                    if (started.FailReason == LinkWire.Reason.NoGoSupport) Settings.MarkGoUnsupported();
                    Fail(l, started.FailReason ?? LinkWire.Reason.NoGoSupport, send: true, detail: started.Detail);
                    return;
                }
                Settings.MarkGoStarted();

                var adapter = await WfdInterface.WaitForIpv4Async(upBefore, ct).ConfigureAwait(false);
                var phys = WfdInterface.PhysicalWifi();
                LinkLog.Ev(lidS, "go_started", ("peer", LinkWire.Id8(l.PeerId)), ("type", LinkWire.TypeWfdLegacy), ("ms", started.Ms),
                           ("ip", adapter?.IPv4), ("adapter", phys?.Description), ("drv", phys?.DriverVersion),
                           ("attempts", started.Attempts), ("wfd", adapter?.Description), ("gen", gen));
                if (adapter == null) { Fail(l, LinkWire.Reason.NoIp, send: true); return; }

                var watchdog = new WfdWatchdog(adapter.Id);
                watchdog.Lost += reason => OnLost(l, reason);
                lock (_gate)
                {
                    if (!IsCurrent(l)) { watchdog.Dispose(); return; }
                    l.Adapter = adapter;
                    l.Watchdog = watchdog;
                }

                string band = "unknown";
                int staMhz = 0;
                if (await Task.WhenAny(channelTask, Task.Delay(500, ct)).ConfigureAwait(false) == channelTask)
                {
                    var (_, mhz, b) = await channelTask.ConfigureAwait(false);
                    band = b == "6" ? "unknown" : b; // §10.1 band is 2.4|5|unknown; the GO follows the STA channel [S]
                    staMhz = mhz;
                }

                int port = _svc.Port;
                if (port == 0) { Fail(l, LinkWire.Reason.Busy, send: true, detail: "server not listening"); return; }
                var offer = LinkWire.Offer(l.Lid, creds, adapter.OfferAddresses(), port, band, l.TtlSeconds);
                lock (_gate)
                {
                    if (!IsCurrent(l)) return;
                    l.Phase = LinkPhase.Offered;
                    l.OfferAt = Environment.TickCount64;
                }
                if (!_svc.Send(l.PeerId, offer))
                {
                    Fail(l, LinkWire.Reason.Timeout, send: false, detail: "no signalling session for link_offer");
                    return;
                }
                LinkLog.Ev(lidS, "offered", ("ms", Environment.TickCount64 - l.T0), ("ssid", creds.Ssid), ("addr", string.Join(",", adapter.OfferAddresses())),
                           ("port", port), ("band", band), ("sta_mhz", staMhz > 0 ? staMhz : null));
                RaiseChanged();
                // From here the 1 s tick enforces the 20 s / 30 s limits; OnSessionReady completes the link.
            }
            catch (OperationCanceledException) { /* Fail/teardown already ran */ }
            catch (Exception ex)
            {
                Log.Error($"Link {l.Lid} host flow crashed", ex);
                Fail(l, LinkWire.Reason.NoGoSupport, send: true, detail: ex.GetType().Name);
            }
        }

        /// <summary>Mobile Hotspot "takes precedence over all Wi-Fi Direct scenarios". Cheap check via the tethering manager of
        /// the internet profile (2 s budget); any error → unknown → false (Aborted(ResourceInUse) still maps to hotspot_active).</summary>
        private static async Task<bool> HotspotActiveAsync()
        {
            var check = Task.Run(() =>
            {
                try
                {
                    var profile = NetworkInformation.GetInternetConnectionProfile();
                    if (profile == null) return false;
                    var mgr = NetworkOperatorTetheringManager.CreateFromConnectionProfile(profile);
                    return mgr.TetheringOperationalState == TetheringOperationalState.On;
                }
                catch (Exception) { return false; }
            });
            return await Task.WhenAny(check, Task.Delay(2000)).ConfigureAwait(false) == check && await check.ConfigureAwait(false);
        }

        private bool IsCurrent(ActiveLink l) =>
            _links.TryGetValue(l.PeerId, out var cur) && cur == l && l.Phase is not (LinkPhase.Failed or LinkPhase.Off);

        // ================================================================== session hooks (PeerService)

        /// <summary>Called by PeerService.OnSessionReady after the ready table points at <paramref name="s"/> and before the old
        /// session is closed with bye replaced. Binds the phone's dial over the WFD adapter to the link.</summary>
        internal void OnSessionReady(Session s, Session? old)
        {
            ActiveLink? l;
            bool bound = false, moved = false;
            lock (_gate)
            {
                if (s.PeerId == null || !_links.TryGetValue(s.PeerId, out l)) return;
                if (l.Phase is LinkPhase.Offered or LinkPhase.Joined && s.Role == SessionRole.Inbound && l.Adapter != null)
                {
                    bool onWfd = (s.LocalAddress != null && WfdInterface.IsLocalAddressOn(l.Adapter, s.LocalAddress))
                                 || (IPAddress.TryParse(s.RemoteAddress, out var remote) && WfdInterface.IsInSubnet(l.Adapter, remote));
                    if (onWfd)
                    {
                        long now = Environment.TickCount64;
                        l.LinkSession = s;
                        l.Phase = LinkPhase.Ready;
                        l.ReadyAt = now;
                        l.LastActivity = now;
                        l.TtlDeadline = now + l.TtlSeconds * 1000L;
                        l.LastBinary = l.BinaryAtReady = s.BinaryBytes;
                        bound = true;
                    }
                    else
                    {
                        l.Signal = s; // the phone re-dialled the LAN for some other reason: keep signalling on it
                        moved = true;
                    }
                }
                else if (l.Phase is LinkPhase.Starting or LinkPhase.Offered or LinkPhase.Joined)
                {
                    l.Signal = s;
                    moved = true;
                }
            }
            if (bound)
            {
                LinkLog.Ev(l.Lid, "ready", ("ms", l.ReadyAt - l.T0), ("remote", s.RemoteAddress), ("local", s.LocalAddress),
                           ("replaces", old != null ? old.RemoteAddress : null));
                l.ReadyTcs.TrySetResult(null);
                RaiseChanged();
            }
            else if (moved) LinkLog.Ev(l.Lid, "signal_moved", ("remote", s.RemoteAddress));
        }

        internal void OnSessionEnded(Session s)
        {
            ActiveLink? l = null;
            lock (_gate)
            {
                if (s.PeerId == null || !_links.TryGetValue(s.PeerId, out l) || l.LinkSession != s) l = null;
            }
            if (l == null) return;
            if (l.Phase == LinkPhase.Ready) BeginClosing(l, LinkWire.Done.Lost, send: false);
            Finish(l);
        }

        // ================================================================== transitions

        private void OnLost(ActiveLink l, string why)
        {
            LinkPhase phase;
            lock (_gate) { if (!IsCurrent(l)) return; phase = l.Phase; }
            LinkLog.Ev(l.Lid, "lost", ("why", why), ("phase", phase.ToString()));
            if (phase == LinkPhase.Ready)
            {
                BeginClosing(l, LinkWire.Done.Lost, send: true);
                Finish(l);
            }
            else if (phase != LinkPhase.Closing)
            {
                Fail(l, why == LinkWire.Reason.HotspotActive ? LinkWire.Reason.HotspotActive : LinkWire.Reason.RadioOff, send: true, detail: why);
            }
        }

        private void Tick()
        {
            if (Volatile.Read(ref _disposed) != 0) return;
            List<ActiveLink> links;
            lock (_gate) links = _links.Values.ToList();
            long now = Environment.TickCount64;
            foreach (var l in links)
            {
                try
                {
                    switch (l.Phase)
                    {
                        case LinkPhase.Starting:
                        case LinkPhase.Offered:
                        case LinkPhase.Joined:
                            if (now - l.T0 > WholeLinkTimeout.TotalMilliseconds)
                                Fail(l, LinkWire.Reason.Timeout, send: true, detail: $"not ready {WholeLinkTimeout.TotalSeconds:0} s after request");
                            else if (l.Phase == LinkPhase.Offered && now - l.OfferAt > ReadyAfterOfferTimeout.TotalMilliseconds)
                                Fail(l, LinkWire.Reason.Timeout, send: true, detail: "no link_ready 20 s after link_offer");
                            break;
                        case LinkPhase.Ready:
                        {
                            var s = l.LinkSession!;
                            long bin = s.BinaryBytes;
                            bool active = bin != l.LastBinary || s.Files.HasActiveTransfers;
                            l.LastBinary = bin;
                            if (active)
                            {
                                l.LastActivity = now;
                                if (l.TtlDeadline - now < TtlExtension.TotalMilliseconds) l.TtlDeadline = now + (long)TtlExtension.TotalMilliseconds;
                            }
                            if (now - l.LastActivity > IdleTimeout.TotalMilliseconds) BeginClosing(l, LinkWire.Done.Idle, send: true);
                            else if (now > l.TtlDeadline) BeginClosing(l, LinkWire.Done.Ttl, send: true);
                            break;
                        }
                        case LinkPhase.Closing:
                            if (now - l.ClosingAt > ClosingGrace.TotalMilliseconds) Finish(l);
                            break;
                    }
                }
                catch (Exception ex) { Log.Error($"Link {l.Lid} tick failed", ex); }
            }
            // forget old failures
            lock (_gate)
                foreach (var key in _lastFail.Where(kv => now - kv.Value.At > FailShownFor.TotalMilliseconds).Select(kv => kv.Key).ToList())
                    _lastFail.Remove(key);
        }

        /// <summary>Any failure before Ready: link_fail (unless the peer sent it), stop the group, let the old path dial.</summary>
        private void Fail(ActiveLink l, string reason, bool send, string? detail = null, bool fromPeer = false)
        {
            lock (_gate)
            {
                if (l.Phase is LinkPhase.Failed or LinkPhase.Off or LinkPhase.Ready or LinkPhase.Closing) return;
                l.Phase = LinkPhase.Failed;
                if (_links.TryGetValue(l.PeerId, out var cur) && cur == l) _links.Remove(l.PeerId);
                _lastFail[l.PeerId] = (reason, Environment.TickCount64);
            }
            // A PC-initiated link the phone never heard of (failed before link_offer) needs no link_fail.
            if (send && (!l.Mine || l.OfferAt != 0)) _svc.Send(l.PeerId, LinkWire.Fail(l.Lid, reason));
            LinkLog.Ev(l.Lid, fromPeer ? "peer_fail" : "fail", ("reason", reason), ("ms", Environment.TickCount64 - l.T0), ("detail", detail));
            l.ReadyTcs.TrySetResult(reason);
            Release(l);
            _svc.ResumeDialing(l.PeerId);
            RaiseChanged();
        }

        /// <summary>Ready → Closing: link_done, allow the LAN dial (make-before-break back). <see cref="Finish"/> follows.</summary>
        private void BeginClosing(ActiveLink l, string reason, bool send)
        {
            Session? s;
            long now = Environment.TickCount64;
            lock (_gate)
            {
                if (l.Phase != LinkPhase.Ready) return;
                l.Phase = LinkPhase.Closing;
                l.ClosingAt = now;
                s = l.LinkSession;
            }
            if (send && s != null) s.EnqueueJson(LinkWire.DoneMsg(l.Lid, reason));
            double secs = Math.Max(0.001, (now - l.ReadyAt) / 1000.0);
            long bytes = (s?.BinaryBytes ?? 0) - l.BinaryAtReady;
            LinkLog.Ev(l.Lid, "done", ("reason", reason), ("dur", $"{(long)secs}s"), ("bytes", bytes), ("MBps", bytes / secs / 1e6));
            _svc.ResumeDialing(l.PeerId);
            RaiseChanged();
        }

        /// <summary>Closing → Off: close the WFD session if still open (no bye: the peer treats it as a drop and comes back over
        /// LAN), stop the publisher, clear any redial pause.</summary>
        private void Finish(ActiveLink l)
        {
            Session? s;
            lock (_gate)
            {
                if (l.Phase == LinkPhase.Off) return;
                l.Phase = LinkPhase.Off;
                if (_links.TryGetValue(l.PeerId, out var cur) && cur == l) _links.Remove(l.PeerId);
                s = l.LinkSession;
            }
            try { if (s != null && s.IsReady) s.Close(null); } catch { }
            Release(l);
            LinkLog.Ev(l.Lid, "teardown", ("ms", Environment.TickCount64 - l.T0));
            _svc.ResumeDialing(l.PeerId);
            RaiseChanged();
        }

        private static void Release(ActiveLink l)
        {
            try { l.Cts.Cancel(); } catch { }
            try { l.Watchdog?.Dispose(); } catch { }
            try { l.Host?.Dispose(); } catch (Exception ex) { Log.Warn($"Link {l.Lid}: publisher teardown failed: {ex.Message}"); }
        }

        private void RaiseChanged()
        {
            try { Changed?.Invoke(); } catch (Exception ex) { Log.Error("Link Changed handler threw", ex); }
        }

        /// <summary>App shutdown: stop every publisher (no messages; sessions are closing anyway).</summary>
        public void Shutdown()
        {
            List<ActiveLink> all;
            lock (_gate) { all = _links.Values.ToList(); _links.Clear(); }
            foreach (var l in all)
            {
                l.Phase = LinkPhase.Off;
                l.ReadyTcs.TrySetResult(LinkWire.Reason.User);
                Release(l);
            }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 1) return;
            _tick.Dispose();
            Shutdown();
        }
    }
}
