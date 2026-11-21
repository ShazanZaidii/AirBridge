using System;
using System.IO;
using AirBridge.Input;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.WebSockets;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using AirBridge.Net.Link;

namespace AirBridge.Net
{
    /// <summary>A paired device as shown in the UI.</summary>
    public sealed record PairedPeerView(string Id, string Name, string Plat, bool Connected, bool IsHost);

    /// <summary>
    /// Owns the whole network half: identity, pairing, host server, mDNS, outbound reconnects, ready sessions.
    /// Implements <see cref="IPeerTransport"/> for the input half and exposes what the UI needs.
    /// </summary>
    public sealed class PeerService : IPeerTransport, IDisposable
    {
        private const int MaxPendingHandshakes = 32;

        public Identity Identity { get; }
        public PeerStore Store { get; } = new();
        public PairingSecrets Secrets { get; } = new();
        public ClipboardSync Clipboard { get; }
        public Discovery Discovery { get; }
        public TransferBoard Transfers { get; } = new();
        /// <summary>Wi-Fi Direct link upgrades (§10.1, Windows host role).</summary>
        public LinkManager Links { get; }
        private readonly Server _server;
        private readonly Client _client;

        private readonly object _gate = new();
        private readonly object _notifyGate = new();
        private readonly Dictionary<string, Session> _ready = new();
        private readonly ConcurrentDictionary<Session, Task> _all = new();
        private readonly ConcurrentDictionary<Session, byte> _pendingInbound = new();
        private int _pending;
        private Timer? _serverRetry;
        private readonly Timer _netDebounce;
        private PcBatterySharer? _battery;
        private volatile IPAddress? _bestAddress;
        private (int W, int H) _screen;
        private bool _started;

        /// <summary>Raised on any thread when anything the UI shows may have changed.</summary>
        public event Action? StateChanged;

        /// <summary>Raised (throttled, timer thread) when the transfer list changed.</summary>
        public event Action? TransfersChanged
        {
            add => Transfers.Changed += value;
            remove => Transfers.Changed -= value;
        }

        /// <summary>Test hook: AIRBRIDGE_DEBUG_AUTO_ACCEPT=1 accepts every confirm pairing without a prompt.</summary>
        public static readonly bool DebugAutoAccept = Environment.GetEnvironmentVariable("AIRBRIDGE_DEBUG_AUTO_ACCEPT") == "1";

        /// <summary>Hosts may refuse pairing entirely (§3.3 not_pairing). Always on for now.</summary>
        public bool PairingEnabled => true;

        private volatile string? _controlStatus;
        private volatile string? _controlPeerId;

        /// <summary>Control/lease status line from the input half ("Controlling Pixel 9 · Ctrl+Alt+Esc to return",
        /// "Controlled by Shazan's MacBook", "Being controlled by …"), or null when idle. Set via
        /// <see cref="App.SetControlStatus(string?)"/>.</summary>
        public string? ControlStatus => _controlStatus;
        /// <summary>Optional peer the status refers to (gets a lease badge in the device list).</summary>
        public string? ControlPeerId => _controlPeerId;

        internal void SetControlStatus(string? text, string? peerId)
        {
            text = string.IsNullOrWhiteSpace(text) ? null : text.Trim();
            if (text == _controlStatus && peerId == _controlPeerId) return;
            _controlStatus = text;
            _controlPeerId = text == null ? null : peerId;
            RaiseState();
        }

        public PeerService()
        {
            Identity = Identity.LoadOrCreate();
            LinkLog.Sink = Log.Info;
            Links = new LinkManager(this);
            Links.Changed += RaiseState;
            Clipboard = new ClipboardSync(this);
            Discovery = new Discovery(Identity);
            _server = new Server(this);
            _client = new Client(this);
            _netDebounce = new Timer(_ => OnNetworkChanged(), null, Timeout.Infinite, Timeout.Infinite);
            DeviceBatteryStore.Changed += RaiseState;
        }

        // ================================================================== lifecycle

        public void Start()
        {
            if (_started) return;
            _started = true;
            HandshakeCrypto.SelfTest();
            Links.Settings.Probe(); // wfd_host caps probe (activation only) before the first hello
            _screen = Win32.PrimaryScreenPixels();
            _bestAddress = NetUtil.BestAddress();
            PeerBus.Transport = this;
            // Screen placements left behind by devices that are no longer paired (every reinstall used to leave one).
            var paired = Store.All().Select(p => p.Id).ToHashSet();
            if (paired.Count > 0) Layout.Prune(paired);
            _battery = new PcBatterySharer(Peers, Send);
            Secrets.Changed += RaiseState;
            Store.Changed += RaiseState;
            Discovery.Changed += () => { _client.WakeAll(clearSuspension: false); RaiseState(); };

            if (!_server.Start())
            {
                Log.Error("All ports 47632-47641 are busy; retrying every 30 s");
                App.Notify("AirBridge", "Could not open a network port (47632-47641 busy). Retrying…");
                _serverRetry = new Timer(_ => RetryServer(), null, TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(30));
            }
            Discovery.Start(_server.IsListening ? _server.Port : 0);

            foreach (var p in Store.All().Where(p => p.Dial)) _client.EnsureLoop(p.Id);

            NetworkChange.NetworkAddressChanged += OnNetworkEvent;
            NetworkChange.NetworkAvailabilityChanged += OnNetworkAvailability;
            Log.Info($"PeerService started as {Identity.Name} ({Identity.Id}), addr {_bestAddress}, port {Port}");
            RaiseState();
        }

        private void RetryServer()
        {
            if (_server.IsListening || !_server.Start()) return;
            _serverRetry?.Dispose();
            _serverRetry = null;
            Discovery.SetPort(_server.Port);
            RaiseState();
        }

        private void OnNetworkEvent(object? sender, EventArgs e) => _netDebounce.Change(2000, Timeout.Infinite);
        private void OnNetworkAvailability(object? sender, NetworkAvailabilityEventArgs e) => _netDebounce.Change(2000, Timeout.Infinite);

        private void OnNetworkChanged()
        {
            try
            {
                _bestAddress = NetUtil.BestAddress();
                Log.Info($"Network changed; best address now {_bestAddress}");
                Discovery.Restart();
                Discovery.Boost();
                _client.WakeAll(clearSuspension: true);
                RaiseState();
            }
            catch (Exception ex) { Log.Error("Network change handling failed", ex); }
        }

        public void Stop()
        {
            if (!_started) return;
            _started = false;
            NetworkChange.NetworkAddressChanged -= OnNetworkEvent;
            NetworkChange.NetworkAvailabilityChanged -= OnNetworkAvailability;
            _client.StopAll();
            Links.Shutdown();
            foreach (var s in _all.Keys) s.Close(s.IsReady ? "shutdown" : null, terminal: true);
            try { Task.WhenAll(_all.Values).Wait(1500); } catch { }
            _server.Stop();
            Discovery.Dispose();
            PeerBus.Transport = null;
        }

        public void Dispose()
        {
            Stop();
            _netDebounce.Dispose();
            Links.Dispose();
            _serverRetry?.Dispose();
            Secrets.Dispose();
            Transfers.Dispose();
        }

        // ================================================================== sessions

        internal bool TryReservePendingSlot()
        {
            if (Interlocked.Increment(ref _pending) <= MaxPendingHandshakes) return true;
            Interlocked.Decrement(ref _pending);
            return false;
        }

        internal void ReleasePendingSlot() => Interlocked.Decrement(ref _pending);

        internal void RunInbound(WebSocket ws, string remote, int remotePort, IDisposable owner, IPAddress? local = null)
        {
            var s = new Session(ws, SessionRole.Inbound, this, remote, remotePort, owner: owner, localAddress: local);
            _pendingInbound[s] = 0;
            RunSession(s);
        }

        internal Task RunSession(Session s)
        {
            var task = Task.Run(s.RunAsync);
            _all[s] = task;
            return task;
        }

        internal JsonObject BuildHello(bool? paired, byte[] nonce, byte[] pk)
        {
            var h = Protocol.Msg("hello");
            h["v"] = Protocol.Version;
            h["id"] = Identity.Id;
            h["name"] = Identity.Name;
            h["plat"] = Protocol.Platform;
            h["app"] = Protocol.AppVersion;
            var caps = new JsonArray();
            foreach (var c in Protocol.Caps) caps.Add(c);
            if (Links.Settings.AdvertiseWfdHost) caps.Add(LinkWire.CapHost); // policy: LinkSettings.AdvertiseWfdHost
            if (Input.MirrorSource.Available) caps.Add("mirror_source"); // §8.7: only when a self-test showed this PC can capture and encode
            h["caps"] = caps;
            h["nonce"] = Protocol.B64(nonce);
            h["pk"] = Protocol.B64(pk);
            if (_screen.W > 0 && _screen.H > 0) h["screen"] = new JsonObject { ["w"] = _screen.W, ["h"] = _screen.H };
            if (paired is bool p) h["paired"] = p;
            if (Net.Identity.Hw is string hw) h["hw"] = hw;
            return h;
        }

        /// <summary>Our hello for a "who is there?" probe (<see cref="NetworkScan.PeekAsync"/>): never leads to a session.</summary>
        internal JsonObject BuildHelloForPeek(byte[] nonce, byte[] pk) => BuildHello(null, nonce, pk);

        internal void OnSessionReady(Session s)
        {
            if (_pendingInbound.TryRemove(s, out _)) ReleasePendingSlot();
            string id = s.PeerId!;
            Session? old;
            lock (_gate)
            {
                _ready.TryGetValue(id, out old);
                _ready[id] = s;
            }
            // A dial over the Wi-Fi Direct adapter becomes the link session here (§10.1). The replacement below is legal for it:
            // the host always keeps the newest session and sends bye replaced on the old one (§3.4 + §10.1 exception).
            try { Links.OnSessionReady(s, old != s ? old : null); }
            catch (Exception ex) { Log.Error("LinkManager.OnSessionReady threw", ex); }
            if (old != null && old != s)
            {
                Log.Info($"Replacing older session for {s.PeerName}");
                old.Close("replaced");
            }
            Store.Update(id, r =>
            {
                r.Name = s.PeerName;
                r.Plat = s.PeerPlat;
                if (s.Role == SessionRole.Outbound) { r.Addr = s.RemoteAddress; r.Port = s.RemotePort; }
                if (s.PeerHw != null) r.Hw = s.PeerHw;
            });
            TidyOlderEntries(s);
            _battery?.SendTo(s.ToPeerInfo());
            NotifyPeers();
        }

        // ================================================================== entries left by reinstalls

        /// <summary>Older entries that look like earlier installs of a device that just connected, offered to the user.</summary>
        public sealed record StaleOffer(string Name, IReadOnlyList<string> Ids);
        private volatile StaleOffer? _staleOffer;
        public StaleOffer? PendingStaleOffer => _staleOffer;
        private static readonly string DismissedPath = System.IO.Path.Combine(Log.Dir, "stale-dismissed.json");

        private static HashSet<string> LoadDismissed()
        {
            try { if (File.Exists(DismissedPath)) return System.Text.Json.JsonSerializer.Deserialize<HashSet<string>>(File.ReadAllText(DismissedPath)) ?? new(); }
            catch (Exception ex) { Log.Warn($"stale-dismissed.json unreadable: {ex.Message}"); }
            return new();
        }

        private static void SaveDismissed(HashSet<string> d)
        {
            try { Directory.CreateDirectory(Log.Dir); File.WriteAllText(DismissedPath, System.Text.Json.JsonSerializer.Serialize(d)); }
            catch (Exception ex) { Log.Warn($"Could not save stale-dismissed.json: {ex.Message}"); }
        }

        private List<StaleDevices.Entry> StoredEntries() =>
            Store.All().Select(p => new StaleDevices.Entry(p.Id, p.Name, p.Plat, p.Hw)).ToList();

        /// <summary>A device just connected. If it is the same physical device as older entries (same fingerprint) those entries
        /// are dead: replace them, and the screen placement you gave the device moves to its new id. Older entries from before
        /// fingerprints existed can't be proven, so the user is asked about them once instead.</summary>
        private void TidyOlderEntries(Session s)
        {
            string? id = s.PeerId;
            if (id == null || s.PeerHw is not string hw || Store.Get(id) == null) return;
            var connected = ReadySessions().Select(x => x.PeerId!).ToHashSet();
            var gone = StaleDevices.Superseded(id, hw, StoredEntries(), connected);
            foreach (var e in gone)
            {
                Layout.Migrate(e.Id, id);
                _client.StopLoop(e.Id);
                Store.Remove(e.Id);
                Log.Info($"Paired entry {e.Id} is an earlier install of {s.PeerName}: removed");
            }
            if (gone.Count > 0) App.Notify("AirBridge", $"{s.PeerName} was reinstalled: its old entry was replaced.");
            var me = new StaleDevices.Entry(id, s.PeerName, s.PeerPlat, hw);
            var maybe = StaleDevices.Candidates(me, StoredEntries(), connected, LoadDismissed());
            _staleOffer = maybe.Count == 0 ? null : new StaleOffer(s.PeerName, maybe.Select(e => e.Id).ToList());
        }

        /// <summary>The user's answer to the stale-entry offer: remove those entries (their screen placement moves to the device
        /// that is connected now) or keep them and don't ask again.</summary>
        public void ResolveStale(bool remove)
        {
            var o = _staleOffer;
            if (o == null) return;
            if (remove)
            {
                string? current = ReadySessions().FirstOrDefault(x => x.PeerName == o.Name && x.PeerHw != null)?.PeerId;
                foreach (var id in o.Ids)
                {
                    if (current != null) Layout.Migrate(id, current);
                    _client.StopLoop(id);
                    Store.Remove(id);
                }
            }
            else
            {
                var d = LoadDismissed();
                foreach (var id in o.Ids) d.Add(id);
                SaveDismissed(d);
            }
            _staleOffer = null;
            RaiseState();
        }

        internal void OnSessionEnded(Session s)
        {
            if (_pendingInbound.TryRemove(s, out _)) ReleasePendingSlot();
            _all.TryRemove(s, out _);
            bool changed = false;
            lock (_gate)
            {
                if (s.PeerId != null && _ready.TryGetValue(s.PeerId, out var cur) && cur == s)
                {
                    _ready.Remove(s.PeerId);
                    DeviceBatteryStore.Clear(s.PeerId);
                    changed = true;
                }
            }
            try { Links.OnSessionEnded(s); }
            catch (Exception ex) { Log.Error("LinkManager.OnSessionEnded threw", ex); }
            if (changed)
            {
                Log.Info($"Session closed: {s}");
                NotifyPeers();
            }
        }

        /// <summary>A ready session started closing. If it is still the peer's current session (not one being replaced),
        /// the input half learns at once (§8.6 "No stray state"). Any thread; must not block.</summary>
        internal void OnSessionClosing(Session s)
        {
            string? id = s.PeerId;
            if (id == null) return;
            bool current;
            lock (_gate) current = _ready.TryGetValue(id, out var cur) && cur == s;
            if (!current) return;
            try { PeerBus.NotifySessionEnded(id); }
            catch (Exception ex) { Log.Error("SessionEndedHandler threw", ex); }
        }

        internal void OnUnpairReceived(Session s)
        {
            if (s.PeerId == null) return;
            _client.StopLoop(s.PeerId);
            Store.Remove(s.PeerId);
            App.Notify("AirBridge", $"{s.PeerName} removed this PC from its paired devices.");
        }

        internal void OnPeerRenamed(Session s)
        {
            if (s.PeerId != null) Store.Update(s.PeerId, r => r.Name = s.PeerName);
            NotifyPeers();
        }

        internal void ForgetLocally(string peerId, string message)
        {
            _client.StopLoop(peerId);
            Store.Remove(peerId);
            App.Notify("AirBridge", message);
        }

        /// <summary>Host: a confirm pairing needs the local user's decision (§3.3). Any thread.</summary>
        internal void RequestPairApproval(PairApproval a)
        {
            string who = $"{a.PeerName} ({PairApproval.PlatLabel(a.Plat)})";
            if (DebugAutoAccept)
            {
                Log.Warn($"AIRBRIDGE_DEBUG_AUTO_ACCEPT=1: auto-accepting pairing from {who}");
                a.Decide(true);
                return;
            }
            App.Notify("Pairing request", $"{who} wants to pair. Code {a.Digits[..3]} {a.Digits[3..]}");
            App.ShowPairPrompt(a);
        }

        /// <summary>A clipboard transfer (§5.1) finished: put it on the clipboard, quietly.</summary>
        internal void OnClipboardReceived(List<string> paths) => Clipboard.OnReceivedMedia(paths);

        internal void OnFilesReceived(string peerName, List<string> paths)
        {
            Clipboard.SetFileDropList(paths);
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                try { System.Media.SystemSounds.Exclamation.Play(); } catch { }
            }));
            App.Notify("Files received",
                paths.Count == 1 ? $"{System.IO.Path.GetFileName(paths[0])} from {peerName} (copied to clipboard)"
                                 : $"{paths.Count} files from {peerName} (copied to clipboard)");
        }

        public IReadOnlyList<Session> ReadySessions()
        {
            lock (_gate) return _ready.Values.ToList();
        }

        public bool HasReadySession(string peerId)
        {
            lock (_gate) return _ready.ContainsKey(peerId);
        }

        private Session? ReadySession(string peerId)
        {
            lock (_gate) return _ready.TryGetValue(peerId, out var s) ? s : null;
        }

        internal Session? ReadySessionFor(string peerId) => ReadySession(peerId);

        /// <summary>A link ended or failed: clear any "replaced" pause for this peer and dial it now if we are its client.</summary>
        internal void ResumeDialing(string peerId)
        {
            var rec = Store.Get(peerId);
            if (rec?.Dial == true) _client.EnsureLoop(peerId);
        }

        /// <summary>mDNS address for the id first (§2.3), last known address as fallback.</summary>
        internal List<(IPAddress Ip, int Port)> ResolveEndpoints(string peerId, PeerRecord rec)
        {
            var list = new List<(IPAddress, int)>();
            var host = Discovery.Find(peerId);
            if (host != null)
                foreach (var a in host.Addresses) list.Add((a, host.Port));
            if (rec.Addr != null && rec.Port > 0 && IPAddress.TryParse(rec.Addr, out var last) && !list.Contains((last, rec.Port)))
                list.Add((last, rec.Port));
            return list;
        }

        private void NotifyPeers()
        {
            lock (_notifyGate)
            {
                try { PeerBus.NotifyPeers(Peers()); }
                catch (Exception ex) { Log.Error("PeersHandler threw", ex); }
            }
            RaiseState();
        }

        private void RaiseState()
        {
            try { StateChanged?.Invoke(); } catch (Exception ex) { Log.Error("StateChanged handler threw", ex); }
        }

        // ================================================================== IPeerTransport

        public bool Send(string peerId, JsonObject msg) => ReadySession(peerId)?.EnqueueJson(msg) ?? false;

        public long LastReceivedTick(string peerId) => ReadySession(peerId)?.LastReceivedTick ?? -1;

        public void Broadcast(JsonObject msg)
        {
            string json;
            try { json = Protocol.Serialize(msg); } catch (Exception ex) { Log.Error("Broadcast serialize failed", ex); return; }
            foreach (var s in ReadySessions()) s.EnqueueText(json);
        }

        /// <summary>Sends an inner binary frame (e.g. 0x03 video) inside the encrypted envelope. Non-blocking;
        /// copies the bytes; returns false (drop) if the peer is not ready or its send queue is backed up.</summary>
        public bool SendBinary(string peerId, ReadOnlyMemory<byte> frame) => ReadySession(peerId)?.TryEnqueueBinary(frame.Span) ?? false;

        public IReadOnlyList<PeerInfo> Peers() => ReadySessions().Select(s => s.ToPeerInfo()).ToList();

        // ================================================================== UI API

        public int Port => _server.IsListening ? _server.Port : 0;
        public IPAddress? BestAddress => _bestAddress;
        public bool PairingLocked => Secrets.IsLocked;

        public string? PairingUri
        {
            get
            {
                var addr = _bestAddress;
                if (addr == null || Port == 0) return null;
                return $"airbridge://pair?v=2&id={Identity.Id}&name={Uri.EscapeDataString(Identity.Name)}&plat={Protocol.Platform}" +
                       $"&addr={addr}&port={Port}&k={Secrets.QrSecretB64Url}";
            }
        }

        public IReadOnlyList<PairedPeerView> PairedPeers()
        {
            var ready = ReadySessions().Select(s => s.PeerId).ToHashSet();
            return Store.All()
                .Select(p => new PairedPeerView(p.Id, p.Name, p.Plat, ready.Contains(p.Id), p.Dial))
                .OrderByDescending(p => p.Connected).ThenBy(p => p.Name, StringComparer.CurrentCultureIgnoreCase)
                .ToList();
        }

        /// <summary>User clicked Forget: send unpair if connected, then delete locally (§3.5).</summary>
        /// <summary>The user renamed this PC: save it, announce the new name over mDNS and tell every connected device.</summary>
        public void RenameSelf(string name)
        {
            Identity.Rename(name);
            Discovery.Restart();
            var msg = Protocol.Msg("name");
            msg["name"] = Identity.Name;
            Broadcast(msg);
            RaiseState();
        }

        public void Forget(string peerId)
        {
            _client.StopLoop(peerId);
            var s = ReadySession(peerId);
            if (s != null)
            {
                s.EnqueueJson(Protocol.Msg("unpair"));
                s.Close(null, terminal: true); // queued unpair is flushed before the close handshake
            }
            Store.Remove(peerId);
            Layout.Remove(peerId);
            Log.Info($"Forgot peer {peerId}");
        }

        /// <summary>User clicked Disconnect: close the session with bye "user" (the peer stops redialling) and do not
        /// redial it ourselves until the user presses Connect. The pairing is kept.</summary>
        public void Disconnect(string peerId)
        {
            _client.PauseByUser(peerId);
            ReadySession(peerId)?.Close("user", terminal: true);
            Log.Info($"Disconnected peer {peerId} (user)");
        }

        /// <summary>Retry a paired host now (clears backoff, "replaced" suspension and a user Disconnect).</summary>
        public void ConnectNow(string peerId)
        {
            var rec = Store.Get(peerId);
            if (rec?.Dial == true) _client.EnsureLoop(peerId, userAction: true);
        }

        /// <summary>
        /// Pair with a host by numeric comparison (§3.3 mode confirm). <paramref name="confirm"/> is called on a
        /// network thread with the 6 digits; it returns true when the user tapped Confirm. Returns null on success,
        /// else a user-facing error.
        /// </summary>
        public async Task<string?> PairWithHostAsync(IPAddress ip, int port, string? expectedId,
                                                     Func<string, CancellationToken, Task<bool>> confirm)
        {
            string? reason = await _client.PairAsync(ip, port, new OutboundIntent { ExpectedPeerId = expectedId, ConfirmDigits = confirm },
                                                     CancellationToken.None).ConfigureAwait(false);
            return PairError(reason);
        }

        /// <summary>Pair from an airbridge://pair link (§2.4, mode qr). Returns null on success, else a user-facing error.</summary>
        public async Task<string?> PairWithLinkAsync(PairLink link)
        {
            var endpoints = new List<(IPAddress, int)>();
            var host = Discovery.Find(link.Id);
            if (host != null) foreach (var a in host.Addresses) endpoints.Add((a, host.Port));
            if (link.Addr != null && !endpoints.Contains((link.Addr, link.Port))) endpoints.Add((link.Addr, link.Port));
            if (endpoints.Count == 0) return PairError("unreachable");
            string? reason = "unreachable";
            foreach (var (ip, port) in endpoints)
            {
                reason = await _client.PairAsync(ip, port, new OutboundIntent { ExpectedPeerId = link.Id, QrSecret = link.Secret },
                                                 CancellationToken.None).ConfigureAwait(false);
                if (reason != "unreachable") break;
            }
            return PairError(reason);
        }

        private static string? PairError(string? reason) => reason switch
        {
            null => null,
            "bad_proof" or "host_bad_proof" => "The pairing check failed. Make sure the codes match and try again.",
            "rejected" => "The other device declined the pairing.",
            "cancelled" => "Pairing cancelled.",
            "locked" => "Too many attempts. Wait a minute and try again.",
            "not_pairing" => "The other device is not accepting pairings right now.",
            "unreachable" => "Could not reach that device.",
            "version" => "The other device runs an incompatible AirBridge version.",
            "wrong_peer" => "A different device answered at that address.",
            "timeout" => "The other device did not answer in time.",
            "bad_hello" => "The other device sent an invalid handshake (is it up to date?).",
            _ => $"Pairing failed ({reason}).",
        };

        /// <summary>
        /// "Send fast": bring up a Wi-Fi Direct link first (§10.1; the offer is held until the link session is ready), then send on
        /// the newest ready session. Falls back to the current session when the link fails. <paramref name="linkStatus"/> gets
        /// null on success or the fail reason.
        /// </summary>
        public async Task<TransferResult> SendFilesFastAsync(string peerId, IReadOnlyList<string> paths, IProgress<double>? progress,
                                                             Action<string?>? linkStatus, CancellationToken ct)
        {
            long total = 0;
            foreach (var p in paths) { try { total += new System.IO.FileInfo(p).Length; } catch { } }
            string? fail = await Links.EnsureLinkAsync(peerId, "xfer", total, ct).ConfigureAwait(false);
            try { linkStatus?.Invoke(fail); } catch { }
            ct.ThrowIfCancellationRequested();
            return await SendFilesAsync(peerId, paths, progress, ct).ConfigureAwait(false);
        }

        /// <summary>Send files to one ready peer.</summary>
        public Task<TransferResult> SendFilesAsync(string peerId, IReadOnlyList<string> paths, IProgress<double>? progress, CancellationToken ct)
        {
            var s = ReadySession(peerId);
            if (s == null) return Task.FromResult(new TransferResult(false, "Not connected", Array.Empty<string>()));
            return s.Files.SendAsync(paths, progress, ct);
        }
    }
}
