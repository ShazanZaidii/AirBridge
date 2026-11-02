using System;
using System.Buffers;
using System.Collections.Generic;
using System.Linq;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;

namespace AirBridge.Net
{
    public enum SessionRole { Inbound, Outbound }

    /// <summary>What an outbound session should do after the host's hello.</summary>
    public sealed class OutboundIntent
    {
        /// <summary>If set, the host's hello id must match (null for manual-address pairing).</summary>
        public string? ExpectedPeerId { get; init; }
        /// <summary>If set, pair with this secret (6-digit code bytes) instead of authenticating.</summary>
        public byte[]? PairSecret { get; init; }
    }

    /// <summary>
    /// One WebSocket connection (inbound or outbound): handshake (§3), liveness (§4) and routing.
    /// All writes go through a single send loop so WebSocket.SendAsync is never called concurrently.
    /// </summary>
    public sealed class Session
    {
        private enum State { AwaitHello, AwaitAuthOrPair, AwaitResult, Ready, Closed }

        private sealed class Frame
        {
            public required byte[] Data;
            public required int Length;
            public required WebSocketMessageType Type;
            public TaskCompletionSource? Done;
            public bool ReturnToPool;
        }

        private readonly WebSocket _ws;
        private readonly IDisposable? _owner;
        private readonly PeerService _svc;
        private readonly OutboundIntent? _intent;
        private readonly Channel<Frame> _out = Channel.CreateUnbounded<Frame>(new UnboundedChannelOptions { SingleReader = true });
        private readonly CancellationTokenSource _cts = new();
        private readonly TaskCompletionSource<bool> _readyTcs = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly byte[] _myNonce = Protocol.RandomBytes(16);
        private readonly long _startedAt = Environment.TickCount64;

        private volatile State _state = State.AwaitHello;
        private byte[] _peerNonce = Array.Empty<byte>();
        private byte[]? _pendingKey;
        private long _lastSent = Environment.TickCount64;
        private long _lastRecv = Environment.TickCount64;
        private int _closing;

        public SessionRole Role { get; }
        public string RemoteAddress { get; }
        public int RemotePort { get; }
        public string? PeerId { get; private set; }
        public string PeerName { get; private set; } = "";
        public string PeerPlat { get; private set; } = "";
        public IReadOnlySet<string> PeerCaps { get; private set; } = new HashSet<string>();
        public int ScreenW { get; private set; }
        public int ScreenH { get; private set; }
        public bool IsReady => _state == State.Ready;
        /// <summary>Reason from a received bye.</summary>
        public string? ByeReason { get; private set; }
        /// <summary>Handshake failure reason (auth_fail/pair_fail reason, or a local one).</summary>
        public string? FailReason { get; private set; }
        /// <summary>Peer sent unpair or the user closed/forgot it locally: never reconnect.</summary>
        public bool Terminal { get; private set; }
        /// <summary>Completes with true when the session becomes ready, false if it ends first.</summary>
        public Task<bool> ReadyTask => _readyTcs.Task;
        public FileTransferSession Files { get; }

        public Session(WebSocket ws, SessionRole role, PeerService svc, string remoteAddress, int remotePort,
                       OutboundIntent? intent = null, IDisposable? owner = null)
        {
            _ws = ws; Role = role; _svc = svc; RemoteAddress = remoteAddress; RemotePort = remotePort;
            _intent = intent; _owner = owner;
            Files = new FileTransferSession(this, svc);
        }

        public PeerInfo ToPeerInfo() => new(PeerId ?? "", PeerName, PeerPlat, PeerCaps, ScreenW, ScreenH);

        public override string ToString() => $"{Role} {PeerName}({PeerId?[..Math.Min(8, PeerId.Length)]}) @{RemoteAddress}";

        // ------------------------------------------------------------------ lifecycle

        public async Task RunAsync()
        {
            var sendLoop = Task.Run(SendLoopAsync);
            var tickLoop = Task.Run(TickLoopAsync);
            try
            {
                if (Role == SessionRole.Outbound) EnqueueJson(_svc.BuildHello(null, _myNonce));
                await ReceiveLoopAsync().ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or ObjectDisposedException)
            {
                // normal disconnect paths
            }
            catch (Exception ex) { Log.Error($"Session {this} receive failed", ex); }
            finally
            {
                Close(null);
                try { await Task.WhenAny(sendLoop, Task.Delay(3000)).ConfigureAwait(false); } catch { }
                _cts.Cancel();
                _state = State.Closed;
                _readyTcs.TrySetResult(false);
                try { Files.OnSessionLost(); } catch (Exception ex) { Log.Error("File cleanup failed", ex); }
                try { _ws.Abort(); _ws.Dispose(); } catch { }
                try { _owner?.Dispose(); } catch { }
                _svc.OnSessionEnded(this);
                try { await tickLoop.ConfigureAwait(false); } catch { }
            }
        }

        /// <summary>Gracefully closes: optional bye, flush queued frames, close handshake, hard abort after 3 s.</summary>
        public void Close(string? byeReason, bool terminal = false)
        {
            if (terminal) Terminal = true;
            if (Interlocked.Exchange(ref _closing, 1) == 1) return;
            if (byeReason != null && _ws.State == WebSocketState.Open)
            {
                var bye = Protocol.Msg("bye"); bye["reason"] = byeReason;
                EnqueueJson(bye, force: true);
            }
            _out.Writer.TryComplete();
            try { _cts.CancelAfter(3000); } catch (ObjectDisposedException) { }
        }

        // ------------------------------------------------------------------ sending

        public bool EnqueueJson(JsonObject msg) => EnqueueJson(msg, force: false);

        private bool EnqueueJson(JsonObject msg, bool force)
        {
            string json;
            try { json = Protocol.Serialize(msg); }
            catch (Exception ex) { Log.Error("Serialize failed", ex); return false; }
            return EnqueueText(json, force);
        }

        public bool EnqueueText(string json) => EnqueueText(json, force: false);

        private bool EnqueueText(string json, bool force)
        {
            if (!force && _closing == 1) return false;
            byte[] data = Encoding.UTF8.GetBytes(json);
            if (data.Length > Protocol.MaxTextFrame)
            {
                Log.Warn($"Dropping oversized text frame ({data.Length} bytes)");
                return false;
            }
            return _out.Writer.TryWrite(new Frame { Data = data, Length = data.Length, Type = WebSocketMessageType.Text });
        }

        /// <summary>Queues a binary frame; completes when it has been handed to the socket.
        /// If <paramref name="pooled"/> the buffer is returned to ArrayPool.Shared afterwards.</summary>
        public async Task SendBinaryAsync(byte[] data, int length, bool pooled, CancellationToken ct)
        {
            var f = new Frame
            {
                Data = data, Length = length, Type = WebSocketMessageType.Binary, ReturnToPool = pooled,
                Done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously),
            };
            if (_closing == 1 || !_out.Writer.TryWrite(f))
            {
                if (pooled) ArrayPool<byte>.Shared.Return(data);
                throw new WebSocketException("Session closed");
            }
            using (ct.Register(() => f.Done.TrySetCanceled()))
                await f.Done.Task.ConfigureAwait(false);
        }

        private async Task SendLoopAsync()
        {
            var ct = _cts.Token;
            try
            {
                await foreach (var f in _out.Reader.ReadAllAsync(ct).ConfigureAwait(false))
                {
                    try
                    {
                        await _ws.SendAsync(new ReadOnlyMemory<byte>(f.Data, 0, f.Length), f.Type, true, ct).ConfigureAwait(false);
                        Interlocked.Exchange(ref _lastSent, Environment.TickCount64);
                        f.Done?.TrySetResult();
                    }
                    catch (Exception ex)
                    {
                        f.Done?.TrySetException(ex);
                        throw;
                    }
                    finally
                    {
                        if (f.ReturnToPool) ArrayPool<byte>.Shared.Return(f.Data);
                    }
                }
                // Writer completed (Close): finish the close handshake politely.
                if (_ws.State is WebSocketState.Open or WebSocketState.CloseReceived)
                {
                    using var t = new CancellationTokenSource(2000);
                    await _ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, t.Token).ConfigureAwait(false);
                }
            }
            catch { /* socket gone */ }
            finally
            {
                Close(null);
                while (_out.Reader.TryRead(out var rest))
                {
                    rest.Done?.TrySetException(new WebSocketException("Session closed"));
                    if (rest.ReturnToPool) ArrayPool<byte>.Shared.Return(rest.Data);
                }
            }
        }

        // ------------------------------------------------------------------ receiving

        private async Task ReceiveLoopAsync()
        {
            var ct = _cts.Token;
            var pool = ArrayPool<byte>.Shared;
            byte[] buf = pool.Rent(64 * 1024);
            int len = 0;
            try
            {
                while (!ct.IsCancellationRequested)
                {
                    if (len == buf.Length)
                    {
                        if (buf.Length >= Protocol.MaxReceiveFrame)
                        {
                            Log.Warn($"{this}: frame too large, closing");
                            await _ws.CloseOutputAsync(WebSocketCloseStatus.MessageTooBig, null, CancellationToken.None).ConfigureAwait(false);
                            return;
                        }
                        var bigger = pool.Rent(Math.Min(buf.Length * 2, Protocol.MaxReceiveFrame));
                        Buffer.BlockCopy(buf, 0, bigger, 0, len);
                        pool.Return(buf);
                        buf = bigger;
                    }

                    var r = await _ws.ReceiveAsync(new Memory<byte>(buf, len, buf.Length - len), ct).ConfigureAwait(false);
                    if (r.MessageType == WebSocketMessageType.Close) return;
                    len += r.Count;
                    if (!r.EndOfMessage) continue;

                    Interlocked.Exchange(ref _lastRecv, Environment.TickCount64);
                    if (r.MessageType == WebSocketMessageType.Text)
                    {
                        string text = Encoding.UTF8.GetString(buf, 0, len);
                        len = 0;
                        HandleText(text);
                    }
                    else
                    {
                        byte[] data = buf; int n = len;
                        buf = pool.Rent(64 * 1024);
                        len = 0;
                        // Ownership of `data` passes to the handler (returned to the pool there).
                        await HandleBinaryAsync(data, n).ConfigureAwait(false);
                    }
                }
            }
            finally { pool.Return(buf); }
        }

        private async Task HandleBinaryAsync(byte[] data, int n)
        {
            if (_state == State.Ready && n >= 1 && data[0] == Protocol.KindFileChunk)
            {
                await Files.OnChunkAsync(data, n).ConfigureAwait(false);
                return;
            }
            ArrayPool<byte>.Shared.Return(data); // unknown kind or not ready: ignore (§1 rule 2/5)
        }

        private void HandleText(string text)
        {
            var o = Protocol.Parse(text);
            string? t = o == null ? null : Protocol.Str(o, "t");
            if (o == null || t == null) return;

            try
            {
                switch (_state)
                {
                    case State.AwaitHello:
                        if (Role == SessionRole.Inbound) HostOnHello(t, o); else ClientOnHello(t, o);
                        break;
                    case State.AwaitAuthOrPair:
                        HostOnAuthOrPair(t, o);
                        break;
                    case State.AwaitResult:
                        ClientOnResult(t, o);
                        break;
                    case State.Ready:
                        OnReadyMessage(t, o);
                        break;
                }
            }
            catch (Exception ex) { Log.Error($"{this}: error handling '{t}'", ex); }
        }

        // ------------------------------------------------------------------ handshake

        /// <summary>Reads the common hello fields. Returns false (and closes) if invalid.</summary>
        private bool ReadHello(JsonObject o)
        {
            if (Protocol.Long(o, "v") != Protocol.Version)
            {
                var fail = Protocol.Msg("auth_fail"); fail["reason"] = "version";
                EnqueueJson(fail);
                Fail("version");
                return false;
            }
            string? id = Protocol.Str(o, "id")?.ToLowerInvariant();
            byte[]? nonce = Protocol.FromB64(Protocol.Str(o, "nonce"));
            if (id == null || !Guid.TryParse(id, out _) || nonce == null || nonce.Length is < 8 or > 64 || id == _svc.Identity.Id)
            {
                Fail("bad_hello");
                return false;
            }
            PeerId = id;
            PeerName = Protocol.Str(o, "name") ?? "Unknown";
            PeerPlat = Protocol.Str(o, "plat") ?? "";
            _peerNonce = nonce;
            var caps = new HashSet<string>();
            if (o["caps"] is JsonArray arr)
                foreach (var c in arr)
                    if (c is JsonValue v && v.TryGetValue(out string? s) && s != null) caps.Add(s);
            PeerCaps = caps;
            if (o["screen"] is JsonObject scr)
            {
                ScreenW = (int)(Protocol.Long(scr, "w") ?? 0);
                ScreenH = (int)(Protocol.Long(scr, "h") ?? 0);
            }
            return true;
        }

        private void HostOnHello(string t, JsonObject o)
        {
            if (t != "hello") return; // nothing but hello/auth/pair before ready
            if (!ReadHello(o)) return;
            bool paired = _svc.Store.GetKey(PeerId!) != null;
            EnqueueJson(_svc.BuildHello(paired, _myNonce));
            _state = State.AwaitAuthOrPair;
        }

        private void HostOnAuthOrPair(string t, JsonObject o)
        {
            string clientId = PeerId!, hostId = _svc.Identity.Id;
            if (t == "auth")
            {
                byte[]? key = _svc.Store.GetKey(clientId);
                if (key == null) { Reject("auth_fail", "unpaired"); return; }
                byte[]? mac = Protocol.FromB64(Protocol.Str(o, "mac"));
                if (!Protocol.MacEquals(Protocol.AuthMac(key, _peerNonce, _myNonce, clientId), mac))
                {
                    Reject("auth_fail", "bad_mac");
                    return;
                }
                var ok = Protocol.Msg("auth_ok");
                ok["mac"] = Protocol.B64(Protocol.AuthAckMac(key, _myNonce, _peerNonce, hostId));
                EnqueueJson(ok);
                BecomeReady();
            }
            else if (t == "pair")
            {
                byte[]? proof = Protocol.FromB64(Protocol.Str(o, "proof"));
                if (proof == null) { Reject("pair_fail", "bad_code"); return; }
                switch (_svc.Secrets.Verify(clientId, hostId, _peerNonce, _myNonce, proof, out var key))
                {
                    case PairCheck.Locked: Reject("pair_fail", "locked"); return;
                    case PairCheck.BadCode: Reject("pair_fail", "bad_code"); return;
                }
                _svc.Store.Upsert(clientId, PeerName, PeerPlat, key, dial: false, null, 0);
                var ok = Protocol.Msg("pair_ok");
                ok["proof"] = Protocol.B64(Protocol.PairAckProof(key, _myNonce, _peerNonce, hostId));
                EnqueueJson(ok);
                Log.Info($"Paired with {PeerName} ({clientId})");
                BecomeReady();
            }
        }

        private void ClientOnHello(string t, JsonObject o)
        {
            if (t == "auth_fail") { Fail(Protocol.Str(o, "reason") ?? "auth_fail"); return; }
            if (t != "hello") return;
            if (!ReadHello(o)) return;
            if (_intent?.ExpectedPeerId != null && _intent.ExpectedPeerId != PeerId)
            {
                Fail("wrong_peer");
                return;
            }
            string clientId = _svc.Identity.Id, hostId = PeerId!;
            if (_intent?.PairSecret != null)
            {
                _pendingKey = Protocol.DeriveKey(_intent.PairSecret, clientId, hostId);
                var pair = Protocol.Msg("pair");
                pair["proof"] = Protocol.B64(Protocol.PairProof(_pendingKey, _myNonce, _peerNonce, clientId));
                EnqueueJson(pair);
            }
            else
            {
                byte[]? key = _svc.Store.GetKey(hostId);
                if (key == null) { Fail("unpaired"); return; }
                _pendingKey = key;
                var auth = Protocol.Msg("auth");
                auth["mac"] = Protocol.B64(Protocol.AuthMac(key, _myNonce, _peerNonce, clientId));
                EnqueueJson(auth);
            }
            _state = State.AwaitResult;
        }

        private void ClientOnResult(string t, JsonObject o)
        {
            string hostId = PeerId!;
            switch (t)
            {
                case "auth_ok":
                {
                    byte[]? mac = Protocol.FromB64(Protocol.Str(o, "mac"));
                    if (_pendingKey == null || !Protocol.MacEquals(Protocol.AuthAckMac(_pendingKey, _peerNonce, _myNonce, hostId), mac))
                    {
                        Log.Warn($"{this}: host failed mutual authentication");
                        Fail("host_bad_mac");
                        return;
                    }
                    BecomeReady();
                    break;
                }
                case "pair_ok":
                {
                    byte[]? proof = Protocol.FromB64(Protocol.Str(o, "proof"));
                    if (_pendingKey == null || !Protocol.MacEquals(Protocol.PairAckProof(_pendingKey, _peerNonce, _myNonce, hostId), proof))
                    {
                        Log.Warn($"{this}: host pair proof invalid");
                        Fail("host_bad_proof");
                        return;
                    }
                    _svc.Store.Upsert(hostId, PeerName, PeerPlat, _pendingKey, dial: true, RemoteAddress, RemotePort);
                    Log.Info($"Paired with host {PeerName} ({hostId})");
                    BecomeReady();
                    break;
                }
                case "auth_fail":
                case "pair_fail":
                    Fail(Protocol.Str(o, "reason") ?? t);
                    break;
            }
        }

        private void Reject(string type, string reason)
        {
            var m = Protocol.Msg(type); m["reason"] = reason;
            EnqueueJson(m);
            Fail(reason);
        }

        private void Fail(string reason)
        {
            FailReason = reason;
            Log.Info($"Handshake failed ({reason}) {this}");
            Close(null);
        }

        private void BecomeReady()
        {
            _pendingKey = null;
            _state = State.Ready;
            Log.Info($"Session ready: {this}");
            _svc.OnSessionReady(this);
            _readyTcs.TrySetResult(true);
        }

        // ------------------------------------------------------------------ ready-state routing

        private void OnReadyMessage(string t, JsonObject o)
        {
            if (PeerBus.ControlTypes.Contains(t))
            {
                try { PeerBus.DispatchControl(PeerId!, o); }
                catch (Exception ex) { Log.Error($"Control handler threw on '{t}'", ex); }
                return;
            }
            if (t.StartsWith("xfer_", StringComparison.Ordinal)) { Files.OnText(t, o); return; }

            switch (t)
            {
                case "ping":
                    var pong = Protocol.Msg("pong"); pong["ts"] = o["ts"]?.DeepClone();
                    EnqueueJson(pong);
                    break;
                case "pong":
                    break;
                case "clip":
                    _svc.Clipboard.OnRemoteClip(this, o);
                    break;
                case "bye":
                    ByeReason = Protocol.Str(o, "reason") ?? "";
                    Log.Info($"{this} said bye ({ByeReason})");
                    Close(null);
                    break;
                case "unpair":
                    Log.Info($"{this} unpaired us");
                    _svc.OnUnpairReceived(this);
                    Close(null, terminal: true);
                    break;
                case "name":
                    string? name = Protocol.Str(o, "name");
                    if (!string.IsNullOrWhiteSpace(name)) { PeerName = name; _svc.OnPeerRenamed(this); }
                    break;
                case "error":
                    Log.Warn($"{this} error {Protocol.Str(o, "code")}: {Protocol.Str(o, "msg")}");
                    break;
                // hello/auth/pair after ready and unknown types: ignore
            }
        }

        // ------------------------------------------------------------------ liveness

        private async Task TickLoopAsync()
        {
            var ct = _cts.Token;
            try
            {
                while (!ct.IsCancellationRequested)
                {
                    await Task.Delay(1000, ct).ConfigureAwait(false);
                    long now = Environment.TickCount64;
                    if (_state != State.Ready)
                    {
                        if (now - _startedAt > Protocol.HandshakeTimeout.TotalMilliseconds)
                        {
                            Fail("timeout");
                            return;
                        }
                        continue;
                    }
                    if (now - Interlocked.Read(ref _lastRecv) > Protocol.DeadAfter.TotalMilliseconds)
                    {
                        Log.Info($"{this}: nothing received for 15 s, closing");
                        Close(null);
                        return;
                    }
                    if (now - Interlocked.Read(ref _lastSent) >= Protocol.PingAfterIdle.TotalMilliseconds)
                    {
                        var ping = Protocol.Msg("ping"); ping["ts"] = Protocol.NowMs();
                        EnqueueJson(ping);
                        Interlocked.Exchange(ref _lastSent, now); // avoid re-queuing every tick while the socket is busy
                    }
                }
            }
            catch (OperationCanceledException) { }
        }
    }
}
