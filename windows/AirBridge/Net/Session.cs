using System;
using System.Buffers;
using System.Collections.Generic;
using System.Net.WebSockets;
using System.Security.Cryptography;
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
        /// <summary>Mode qr: pair with the 32-byte secret from a pairing link/QR (§2.4).</summary>
        public byte[]? QrSecret { get; init; }
        /// <summary>
        /// Mode confirm: called (on a network thread) with the 6 digits once both hellos are in. Return true when
        /// the local user tapped Confirm, false on Cancel. The token is cancelled when the attempt ends for any
        /// other reason (host rejected, timeout, disconnect) so the UI can dismiss itself.
        /// </summary>
        public Func<string, CancellationToken, Task<bool>>? ConfirmDigits { get; init; }
    }

    /// <summary>
    /// One WebSocket connection (inbound or outbound): handshake (§3), secure channel (§3.6), liveness (§4) and
    /// routing. All writes go through a single send loop, which is also where frames are sealed, so the
    /// envelope sequence numbers always match wire order and WebSocket.SendAsync is never called concurrently.
    /// </summary>
    public sealed class Session
    {
        private enum State { AwaitHello, AwaitAuthOrPair, AwaitUserConfirm, AwaitResult, Ready, Closed }

        /// <summary>Frame.Inner value for a frame sent as-is (handshake, before ready).</summary>
        private const byte Plain = 0xFF;
        /// <summary>Above this many queued bytes, best-effort binary sends (video) are dropped.</summary>
        private const long MaxQueuedForBestEffort = 4L * 1024 * 1024;
        private static readonly TimeSpan PairTimeout = TimeSpan.FromSeconds(60);

        private sealed class Frame
        {
            public required byte[] Data;
            public required int Length;
            /// <summary><see cref="Plain"/>, or the envelope inner kind (0 JSON, 1 binary).</summary>
            public required byte Inner;
            public WebSocketMessageType Type = WebSocketMessageType.Binary;
            public TaskCompletionSource? Done;
            public bool ReturnToPool;
        }

        private readonly WebSocket _ws;
        private readonly IDisposable? _owner;
        private readonly PeerService _svc;
        private readonly OutboundIntent? _intent;
        private readonly Channel<Frame> _out = Channel.CreateUnbounded<Frame>(new UnboundedChannelOptions { SingleReader = true });
        private readonly CancellationTokenSource _cts = new();
        private readonly CancellationTokenSource _pairCts = new();
        private readonly TaskCompletionSource<bool> _readyTcs = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly object _hs = new(); // guards handshake state (receive thread vs. UI decisions vs. timeout)
        private readonly byte[] _myNonce = Protocol.RandomBytes(16);
        private ECDiffieHellman? _eph = HandshakeCrypto.NewEphemeral();
        private readonly byte[] _myPk;

        private volatile State _state = State.AwaitHello;
        private byte[] _peerNonce = Array.Empty<byte>();
        private byte[]? _z;            // ECDH shared secret, zeroed once the session keys exist
        private byte[]? _t;            // transcript T
        private byte[]? _pendingKey;   // K being established/verified
        private SecureChannel? _channel;
        private volatile bool _encryptOut;
        private long _deadline;        // TickCount64 at which the handshake times out
        private long _lastSent = Environment.TickCount64;
        private long _lastRecv = Environment.TickCount64;
        private long _queued;
        private int _closing;

        // host-side confirm pairing
        private bool _confirmStarted;
        private bool? _userAccepted;
        private byte[]? _confirmProof;

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
            _myPk = HandshakeCrypto.EncodePublicKey(_eph);
            _deadline = Environment.TickCount64 + (long)Protocol.HandshakeTimeout.TotalMilliseconds;
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
                if (Role == SessionRole.Outbound) EnqueueJson(_svc.BuildHello(null, _myNonce, _myPk));
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
                lock (_hs)
                {
                    _state = State.Closed;
                    WipeHandshakeSecrets();
                }
                try { _pairCts.Cancel(); } catch { }
                _readyTcs.TrySetResult(false);
                try { Files.OnSessionLost(); } catch (Exception ex) { Log.Error("File cleanup failed", ex); }
                try { _ws.Abort(); _ws.Dispose(); } catch { }
                try { _owner?.Dispose(); } catch { }
                _svc.OnSessionEnded(this);
                try { await tickLoop.ConfigureAwait(false); } catch { }
                try { await sendLoop.ConfigureAwait(false); } catch { }
                _channel?.Dispose();
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
            try { _pairCts.Cancel(); } catch (ObjectDisposedException) { }
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
            // After ready everything is enveloped (§3.6); before, handshake frames go out as text.
            var f = _encryptOut
                ? new Frame { Data = data, Length = data.Length, Inner = SecureChannel.InnerJson }
                : new Frame { Data = data, Length = data.Length, Inner = Plain, Type = WebSocketMessageType.Text };
            return Write(f);
        }

        private bool Write(Frame f)
        {
            Interlocked.Add(ref _queued, f.Length);
            if (_out.Writer.TryWrite(f)) return true;
            Interlocked.Add(ref _queued, -f.Length);
            return false;
        }

        /// <summary>Queues an inner binary frame (file chunk); completes when it has been handed to the socket.
        /// If <paramref name="pooled"/> the buffer is returned to ArrayPool.Shared afterwards.</summary>
        public async Task SendBinaryAsync(byte[] data, int length, bool pooled, CancellationToken ct)
        {
            var f = new Frame
            {
                Data = data, Length = length, Inner = SecureChannel.InnerBinary, ReturnToPool = pooled,
                Done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously),
            };
            if (_closing == 1 || !_encryptOut || !Write(f))
            {
                if (pooled) ArrayPool<byte>.Shared.Return(data);
                throw new WebSocketException("Session closed");
            }
            using (ct.Register(() => f.Done.TrySetCanceled()))
                await f.Done.Task.ConfigureAwait(false);
        }

        /// <summary>
        /// Best-effort, non-blocking send of an inner binary frame (e.g. 0x03 video) through the envelope.
        /// The bytes are copied. Returns false if not ready, closing, malformed, or the queue is backed up
        /// (latency-sensitive callers should drop rather than buffer, §8.7).
        /// </summary>
        public bool TryEnqueueBinary(ReadOnlySpan<byte> frame)
        {
            if (!_encryptOut || _closing == 1) return false;
            if (frame.Length < 1 || frame.Length > Protocol.MaxBinaryFrame || frame[0] == SecureChannel.KindEnvelope) return false;
            if (Interlocked.Read(ref _queued) > MaxQueuedForBestEffort) return false;
            byte[] buf = ArrayPool<byte>.Shared.Rent(frame.Length);
            frame.CopyTo(buf);
            if (Write(new Frame { Data = buf, Length = frame.Length, Inner = SecureChannel.InnerBinary, ReturnToPool = true })) return true;
            ArrayPool<byte>.Shared.Return(buf);
            return false;
        }

        private async Task SendLoopAsync()
        {
            var ct = _cts.Token;
            var pool = ArrayPool<byte>.Shared;
            try
            {
                await foreach (var f in _out.Reader.ReadAllAsync(ct).ConfigureAwait(false))
                {
                    byte[]? sealedBuf = null;
                    try
                    {
                        Interlocked.Add(ref _queued, -f.Length);
                        if (f.Inner == Plain)
                        {
                            await _ws.SendAsync(new ReadOnlyMemory<byte>(f.Data, 0, f.Length), f.Type, true, ct).ConfigureAwait(false);
                        }
                        else
                        {
                            sealedBuf = pool.Rent(f.Length + SecureChannel.Overhead);
                            int n = _channel!.Seal(f.Inner, new ReadOnlySpan<byte>(f.Data, 0, f.Length), sealedBuf);
                            await _ws.SendAsync(new ReadOnlyMemory<byte>(sealedBuf, 0, n), WebSocketMessageType.Binary, true, ct).ConfigureAwait(false);
                        }
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
                        if (sealedBuf != null) pool.Return(sealedBuf);
                        if (f.ReturnToPool) pool.Return(f.Data);
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
                    if (rest.ReturnToPool) pool.Return(rest.Data);
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
                        if (_state == State.Ready)
                        {
                            ProtocolError("plaintext text frame after ready");
                            return;
                        }
                        string text = Encoding.UTF8.GetString(buf, 0, len);
                        len = 0;
                        HandleHandshakeText(text);
                    }
                    else
                    {
                        byte[] data = buf; int n = len;
                        buf = pool.Rent(64 * 1024);
                        len = 0;
                        // Ownership of `data` passes to the handler (returned to the pool there).
                        if (!await HandleBinaryAsync(data, n).ConfigureAwait(false)) return;
                    }
                }
            }
            finally { pool.Return(buf); }
        }

        /// <summary>Returns false if the session must stop reading (protocol error).</summary>
        private async Task<bool> HandleBinaryAsync(byte[] data, int n)
        {
            var pool = ArrayPool<byte>.Shared;
            bool owned = true;
            try
            {
                if (_state != State.Ready || n < 1) return true; // nothing binary before ready (§1 rule 5)
                byte kind = data[0];
                if (kind == Protocol.KindFileChunk || kind == Protocol.KindVideo)
                {
                    ProtocolError($"unencrypted 0x{kind:X2} frame after ready");
                    return false;
                }
                if (kind != SecureChannel.KindEnvelope) return true; // unknown kind: ignore (§1 rule 2)

                var res = _channel!.Open(data, n, out byte inner, out int plen);
                if (res != SecureChannel.OpenResult.Ok)
                {
                    ProtocolError($"envelope rejected ({res})");
                    return false;
                }
                const int p = SecureChannel.HeaderLength + 1;
                if (inner == SecureChannel.InnerJson)
                {
                    HandleReadyText(Encoding.UTF8.GetString(data, p, plen));
                }
                else if (inner == SecureChannel.InnerBinary && plen >= 1)
                {
                    byte ik = data[p];
                    if (ik == Protocol.KindFileChunk)
                    {
                        owned = false;
                        await Files.OnChunkAsync(data, p, plen).ConfigureAwait(false);
                    }
                    else if (ik == Protocol.KindVideo)
                    {
                        // The memory is valid only during the call; the input half copies what it keeps.
                        try { PeerBus.DispatchBinary(PeerId!, new ReadOnlyMemory<byte>(data, p, plen)); }
                        catch (Exception ex) { Log.Error("Binary handler threw", ex); }
                    }
                    // other inner kinds: ignore
                }
                return true;
            }
            finally
            {
                if (owned) pool.Return(data);
            }
        }

        private void ProtocolError(string what)
        {
            Log.Warn($"{this}: protocol error: {what}; closing");
            Close("error");
        }

        private void HandleHandshakeText(string text)
        {
            var o = Protocol.Parse(text);
            string? t = o == null ? null : Protocol.Str(o, "t");
            if (o == null || t == null) return;
            try
            {
                lock (_hs)
                {
                    switch (_state)
                    {
                        case State.AwaitHello:
                            if (Role == SessionRole.Inbound) HostOnHello(t, o); else ClientOnHello(t, o);
                            break;
                        case State.AwaitAuthOrPair:
                            HostOnAuthOrPair(t, o);
                            break;
                        case State.AwaitUserConfirm:
                        case State.AwaitResult:
                            ClientOnResult(t, o);
                            break;
                    }
                }
            }
            catch (Exception ex) { Log.Error($"{this}: error handling '{t}'", ex); }
        }

        private void HandleReadyText(string text)
        {
            var o = Protocol.Parse(text);
            string? t = o == null ? null : Protocol.Str(o, "t");
            if (o == null || t == null) return;
            try { OnReadyMessage(t, o); }
            catch (Exception ex) { Log.Error($"{this}: error handling '{t}'", ex); }
        }

        // ------------------------------------------------------------------ handshake (all under _hs)

        /// <summary>Reads the common hello fields and computes Z and T. Returns false (and closes) if invalid.</summary>
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
            byte[]? peerPk = Protocol.FromB64(Protocol.Str(o, "pk"));
            using (var peerKey = HandshakeCrypto.ImportPeerKey(peerPk))
            {
                if (peerKey == null || _eph == null)
                {
                    Log.Warn($"hello from {id} without a valid P-256 pk");
                    Fail("bad_hello");
                    return false;
                }
                try { _z = HandshakeCrypto.SharedSecret(_eph, peerKey); }
                catch (CryptographicException ex)
                {
                    Log.Warn($"ECDH with {id} failed: {ex.Message}");
                    Fail("bad_hello");
                    return false;
                }
            }
            // The ephemeral private key is no longer needed once Z exists.
            _eph.Dispose();
            _eph = null;

            PeerId = id;
            PeerName = Protocol.Str(o, "name") ?? "Unknown";
            PeerPlat = Protocol.Str(o, "plat") ?? "";
            _peerNonce = nonce;
            _t = Role == SessionRole.Outbound
                ? HandshakeCrypto.Transcript(_myNonce, nonce, _svc.Identity.Id, id, _myPk, peerPk!)
                : HandshakeCrypto.Transcript(nonce, _myNonce, id, _svc.Identity.Id, peerPk!, _myPk);
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

        private byte[] CNonce => Role == SessionRole.Outbound ? _myNonce : _peerNonce;
        private byte[] HNonce => Role == SessionRole.Outbound ? _peerNonce : _myNonce;
        private string ClientId => Role == SessionRole.Outbound ? _svc.Identity.Id : PeerId!;
        private string HostId => Role == SessionRole.Outbound ? PeerId! : _svc.Identity.Id;

        private void HostOnHello(string t, JsonObject o)
        {
            if (t != "hello") return; // nothing but hello/auth/pair before ready
            if (!ReadHello(o)) return;
            bool paired = _svc.Store.GetKey(PeerId!) != null;
            EnqueueJson(_svc.BuildHello(paired, _myNonce, _myPk));
            _state = State.AwaitAuthOrPair;
        }

        private void HostOnAuthOrPair(string t, JsonObject o)
        {
            string clientId = PeerId!, hostId = _svc.Identity.Id;
            switch (t)
            {
                case "auth":
                {
                    if (_confirmStarted) return; // a pairing attempt is in progress on this socket
                    byte[]? key = _svc.Store.GetKey(clientId);
                    if (key == null) { Reject("auth_fail", "unpaired"); return; }
                    byte[]? mac = Protocol.FromB64(Protocol.Str(o, "mac"));
                    if (!Protocol.MacEquals(HandshakeCrypto.AuthMac(key, _t!), mac))
                    {
                        CryptographicOperations.ZeroMemory(key);
                        Reject("auth_fail", "bad_mac");
                        return;
                    }
                    var ok = Protocol.Msg("auth_ok");
                    ok["mac"] = Protocol.B64(HandshakeCrypto.AuthAckMac(key, _t!));
                    CryptographicOperations.ZeroMemory(key);
                    EnqueueJson(ok);
                    BecomeReady();
                    break;
                }
                case "pair_start":
                {
                    string mode = Protocol.Str(o, "mode") ?? "confirm";
                    if (mode != "confirm") return; // only confirm uses pair_start (§3.3)
                    StartConfirm();
                    break;
                }
                case "pair":
                {
                    string mode = Protocol.Str(o, "mode") ?? "qr";
                    byte[]? proof = Protocol.FromB64(Protocol.Str(o, "proof"));
                    if (mode == "confirm")
                    {
                        if (!_confirmStarted && !StartConfirm()) return; // pair without pair_start: prompt now
                        if (_confirmProof != null) return;
                        if (proof == null) { _svc.Secrets.RecordFailure(); Reject("pair_fail", "bad_proof"); return; }
                        _confirmProof = proof;
                        if (_userAccepted == true) CompleteConfirm();
                        return;
                    }
                    if (mode != "qr") return; // unknown mode: ignore
                    if (_confirmStarted) return;
                    if (proof == null) { _svc.Secrets.RecordFailure(); Reject("pair_fail", "bad_proof"); return; }
                    switch (_svc.Secrets.VerifyQr(clientId, hostId, _t!, proof, out var key))
                    {
                        case PairCheck.Locked: Reject("pair_fail", "locked"); return;
                        case PairCheck.BadProof: Reject("pair_fail", "bad_proof"); return;
                    }
                    FinishHostPairing(key, "qr");
                    break;
                }
            }
        }

        /// <summary>Host: begin a confirm pairing — compute digits, ask the user, start the 60 s clock.</summary>
        private bool StartConfirm()
        {
            if (_confirmStarted) return true;
            if (_svc.Secrets.IsLocked) { Reject("pair_fail", "locked"); return false; }
            if (!_svc.PairingEnabled) { Reject("pair_fail", "not_pairing"); return false; }
            _confirmStarted = true;
            Interlocked.Exchange(ref _deadline, Environment.TickCount64 + (long)PairTimeout.TotalMilliseconds);
            string digits = HandshakeCrypto.ConfirmDigits(_z!, _t!);
            Log.Info($"Confirm pairing requested by {PeerName} ({PeerId}); digits {digits}");
            var approval = new PairApproval(PeerId!, PeerName, PeerPlat, digits, DateTime.UtcNow + PairTimeout,
                                            _pairCts.Token, OnUserDecision);
            _svc.RequestPairApproval(approval);
            return true;
        }

        /// <summary>Host: the local user tapped Accept/Reject (any thread).</summary>
        private void OnUserDecision(bool accept)
        {
            lock (_hs)
            {
                if (_state != State.AwaitAuthOrPair || !_confirmStarted || _userAccepted != null) return;
                _userAccepted = accept;
                if (!accept) { Reject("pair_fail", "rejected"); return; }
                if (_confirmProof != null) CompleteConfirm();
                // else wait for the client's pair (its user has not tapped Confirm yet)
            }
        }

        private void CompleteConfirm()
        {
            byte[] key = HandshakeCrypto.ConfirmPairKey(_z!, CNonce, HNonce, ClientId, HostId);
            if (!Protocol.MacEquals(HandshakeCrypto.PairProof(key, _t!), _confirmProof))
            {
                CryptographicOperations.ZeroMemory(key);
                _svc.Secrets.RecordFailure();
                Reject("pair_fail", "bad_proof");
                return;
            }
            _svc.Secrets.RecordSuccess(qr: false);
            FinishHostPairing(key, "confirm");
        }

        private void FinishHostPairing(byte[] key, string mode)
        {
            _svc.Store.Upsert(PeerId!, PeerName, PeerPlat, key, dial: false, null, 0);
            var ok = Protocol.Msg("pair_ok");
            ok["proof"] = Protocol.B64(HandshakeCrypto.PairAckProof(key, _t!));
            CryptographicOperations.ZeroMemory(key);
            EnqueueJson(ok);
            Log.Info($"Paired with {PeerName} ({PeerId}) by {mode}");
            BecomeReady();
            App.Notify("AirBridge", $"Paired with {PeerName}.");
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
            if (_intent?.ConfirmDigits is { } confirm)
            {
                var start = Protocol.Msg("pair_start"); start["mode"] = "confirm";
                EnqueueJson(start);
                // The host allows 60 s from pair_start; give ourselves a little more so its timeout wins.
                Interlocked.Exchange(ref _deadline, Environment.TickCount64 + (long)PairTimeout.TotalMilliseconds + 5000);
                _state = State.AwaitUserConfirm;
                string digits = HandshakeCrypto.ConfirmDigits(_z!, _t!);
                var token = _pairCts.Token;
                _ = Task.Run(async () =>
                {
                    bool ok;
                    try { ok = await confirm(digits, token).ConfigureAwait(false); }
                    catch (OperationCanceledException) { ok = false; }
                    catch (Exception ex) { Log.Error("Confirm UI failed", ex); ok = false; }
                    OnLocalConfirm(ok);
                });
                return;
            }
            if (_intent?.QrSecret != null)
            {
                _pendingKey = HandshakeCrypto.QrPairKey(_intent.QrSecret, clientId, hostId);
                var pair = Protocol.Msg("pair");
                pair["mode"] = "qr";
                pair["proof"] = Protocol.B64(HandshakeCrypto.PairProof(_pendingKey, _t!));
                EnqueueJson(pair);
            }
            else
            {
                byte[]? key = _svc.Store.GetKey(hostId);
                if (key == null) { Fail("unpaired"); return; }
                _pendingKey = key;
                var auth = Protocol.Msg("auth");
                auth["mac"] = Protocol.B64(HandshakeCrypto.AuthMac(key, _t!));
                EnqueueJson(auth);
            }
            _state = State.AwaitResult;
        }

        /// <summary>Client: the local user tapped Confirm (true) or Cancel (false).</summary>
        private void OnLocalConfirm(bool ok)
        {
            lock (_hs)
            {
                if (_state != State.AwaitUserConfirm) return;
                if (!ok) { Fail("cancelled"); return; }
                _pendingKey = HandshakeCrypto.ConfirmPairKey(_z!, CNonce, HNonce, ClientId, HostId);
                var pair = Protocol.Msg("pair");
                pair["mode"] = "confirm";
                pair["proof"] = Protocol.B64(HandshakeCrypto.PairProof(_pendingKey, _t!));
                EnqueueJson(pair);
                _state = State.AwaitResult;
            }
        }

        private void ClientOnResult(string t, JsonObject o)
        {
            string hostId = PeerId!;
            switch (t)
            {
                case "auth_ok":
                {
                    if (_state != State.AwaitResult) return;
                    byte[]? mac = Protocol.FromB64(Protocol.Str(o, "mac"));
                    if (_pendingKey == null || !Protocol.MacEquals(HandshakeCrypto.AuthAckMac(_pendingKey, _t!), mac))
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
                    if (_state != State.AwaitResult) return;
                    byte[]? proof = Protocol.FromB64(Protocol.Str(o, "proof"));
                    if (_pendingKey == null || !Protocol.MacEquals(HandshakeCrypto.PairAckProof(_pendingKey, _t!), proof))
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
            // §3.6: derive the direction keys, then destroy everything ephemeral.
            var (c2h, h2c) = HandshakeCrypto.SessionKeys(_z!, CNonce, HNonce, _t!);
            _channel = Role == SessionRole.Outbound ? new SecureChannel(c2h, h2c) : new SecureChannel(h2c, c2h);
            WipeHandshakeSecrets();
            _encryptOut = true; // everything queued from here on is enveloped (auth_ok/pair_ok above went in the clear)
            _state = State.Ready;
            try { _pairCts.Cancel(); } catch { }
            Log.Info($"Session ready (encrypted): {this}");
            _svc.OnSessionReady(this);
            _readyTcs.TrySetResult(true);
        }

        private void WipeHandshakeSecrets()
        {
            if (_z != null) CryptographicOperations.ZeroMemory(_z);
            if (_pendingKey != null) CryptographicOperations.ZeroMemory(_pendingKey);
            _z = null; _t = null; _pendingKey = null; _confirmProof = null;
            _eph?.Dispose();
            _eph = null;
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
                        lock (_hs)
                        {
                            if (_state is State.Ready or State.Closed || now <= Interlocked.Read(ref _deadline)) continue;
                            if (Role == SessionRole.Inbound && _confirmStarted) Reject("pair_fail", "timeout");
                            else Fail("timeout");
                        }
                        return;
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
