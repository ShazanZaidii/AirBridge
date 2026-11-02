using System;
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
        private volatile IPAddress? _bestAddress;
        private (int W, int H) _screen;
        private bool _started;

        /// <summary>Raised on any thread when anything the UI shows may have changed.</summary>
        public event Action? StateChanged;

        public PeerService()
        {
            Identity = Identity.LoadOrCreate();
            Clipboard = new ClipboardSync(this);
            Discovery = new Discovery(Identity);
            _server = new Server(this);
            _client = new Client(this);
            _netDebounce = new Timer(_ => OnNetworkChanged(), null, Timeout.Infinite, Timeout.Infinite);
        }

        // ================================================================== lifecycle

        public void Start()
        {
            if (_started) return;
            _started = true;
            _screen = Win32.PrimaryScreenPixels();
            _bestAddress = NetUtil.BestAddress();
            PeerBus.Transport = this;
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
            _serverRetry?.Dispose();
            Secrets.Dispose();
        }

        // ================================================================== sessions

        internal bool TryReservePendingSlot()
        {
            if (Interlocked.Increment(ref _pending) <= MaxPendingHandshakes) return true;
            Interlocked.Decrement(ref _pending);
            return false;
        }

        internal void ReleasePendingSlot() => Interlocked.Decrement(ref _pending);

        internal void RunInbound(WebSocket ws, string remote, int remotePort, IDisposable owner)
        {
            var s = new Session(ws, SessionRole.Inbound, this, remote, remotePort, owner: owner);
            _pendingInbound[s] = 0;
            RunSession(s);
        }

        internal Task RunSession(Session s)
        {
            var task = Task.Run(s.RunAsync);
            _all[s] = task;
            return task;
        }

        internal JsonObject BuildHello(bool? paired, byte[] nonce)
        {
            var h = Protocol.Msg("hello");
            h["v"] = Protocol.Version;
            h["id"] = Identity.Id;
            h["name"] = Identity.Name;
            h["plat"] = Protocol.Platform;
            h["app"] = Protocol.AppVersion;
            var caps = new JsonArray();
            foreach (var c in Protocol.Caps) caps.Add(c);
            h["caps"] = caps;
            h["nonce"] = Protocol.B64(nonce);
            if (_screen.W > 0 && _screen.H > 0) h["screen"] = new JsonObject { ["w"] = _screen.W, ["h"] = _screen.H };
            if (paired is bool p) h["paired"] = p;
            return h;
        }

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
            });
            NotifyPeers();
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
                    changed = true;
                }
            }
            if (changed)
            {
                Log.Info($"Session closed: {s}");
                NotifyPeers();
            }
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

        public void Broadcast(JsonObject msg)
        {
            string json;
            try { json = Protocol.Serialize(msg); } catch (Exception ex) { Log.Error("Broadcast serialize failed", ex); return; }
            foreach (var s in ReadySessions()) s.EnqueueText(json);
        }

        // TODO(net half): route through Session's binary send path once it is exposed.
        public bool SendBinary(string peerId, ReadOnlyMemory<byte> frame) => false;

        public IReadOnlyList<PeerInfo> Peers() => ReadySessions().Select(s => s.ToPeerInfo()).ToList();

        // ================================================================== UI API

        public int Port => _server.IsListening ? _server.Port : 0;
        public IPAddress? BestAddress => _bestAddress;
        public string PairingCode => Secrets.Code;
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
            Log.Info($"Forgot peer {peerId}");
        }

        /// <summary>Retry a paired host now (clears backoff and "replaced" suspension).</summary>
        public void ConnectNow(string peerId)
        {
            var rec = Store.Get(peerId);
            if (rec?.Dial == true) _client.EnsureLoop(peerId);
        }

        /// <summary>Pair with a host by the 6-digit code it displays. Returns null on success, else a user-facing error.</summary>
        public async Task<string?> PairWithHostAsync(IPAddress ip, int port, string code, string? expectedId)
        {
            string? reason = await _client.PairAsync(ip, port, code, expectedId, CancellationToken.None).ConfigureAwait(false);
            return reason switch
            {
                null => null,
                "bad_code" => "Wrong code. Check the code shown on the other device.",
                "locked" => "Too many attempts. Wait a minute and try again.",
                "not_pairing" => "The other device is not accepting pairings right now.",
                "unreachable" => "Could not reach that device.",
                "version" => "The other device runs an incompatible AirBridge version.",
                "wrong_peer" => "A different device answered at that address.",
                "timeout" => "The other device did not answer in time.",
                _ => $"Pairing failed ({reason}).",
            };
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
