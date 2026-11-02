using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Threading;
using System.Threading.Tasks;

namespace AirBridge.Net
{
    /// <summary>Outbound side (§4.1): one reconnect loop per paired host id, plus first-time pairing by code.</summary>
    public sealed class Client
    {
        private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(5);
        private static readonly TimeSpan MaxBackoff = TimeSpan.FromSeconds(30);

        private sealed class DialLoop
        {
            public required string PeerId;
            public readonly CancellationTokenSource Cts = new();
            public readonly SemaphoreSlim Wake = new(0);
            public volatile bool Suspended;   // after bye "replaced" until network change / user action
            public volatile Session? Current;
            public void Poke() { if (Wake.CurrentCount == 0) Wake.Release(); }
        }

        private readonly PeerService _svc;
        private readonly object _gate = new();
        private readonly Dictionary<string, DialLoop> _loops = new();

        public Client(PeerService svc) { _svc = svc; }

        /// <summary>Ensures a reconnect loop exists for a dial peer. <paramref name="adopt"/> = an already-running session.</summary>
        public void EnsureLoop(string peerId, Session? adopt = null, Task? adoptRun = null)
        {
            DialLoop loop;
            lock (_gate)
            {
                if (_loops.TryGetValue(peerId, out var existing))
                {
                    existing.Suspended = false;
                    existing.Poke();
                    return;
                }
                loop = new DialLoop { PeerId = peerId, Current = adopt };
                _loops[peerId] = loop;
            }
            _ = Task.Run(() => RunLoopAsync(loop, adoptRun));
        }

        public void StopLoop(string peerId)
        {
            DialLoop? loop;
            lock (_gate)
            {
                if (!_loops.Remove(peerId, out loop)) return;
            }
            loop.Cts.Cancel();
        }

        public void StopAll()
        {
            List<DialLoop> all;
            lock (_gate) { all = _loops.Values.ToList(); _loops.Clear(); }
            foreach (var l in all) l.Cts.Cancel();
        }

        /// <summary>Network changed: retry immediately (also clears "replaced" suspension).</summary>
        public void WakeAll(bool clearSuspension)
        {
            lock (_gate)
                foreach (var l in _loops.Values)
                {
                    if (clearSuspension) l.Suspended = false;
                    l.Poke();
                }
        }

        public void Wake(string peerId)
        {
            lock (_gate)
                if (_loops.TryGetValue(peerId, out var l)) l.Poke();
        }

        private async Task RunLoopAsync(DialLoop loop, Task? adoptRun)
        {
            var ct = loop.Cts.Token;
            var backoff = TimeSpan.FromSeconds(1);
            try
            {
                if (adoptRun != null && loop.Current != null)
                {
                    await adoptRun.ConfigureAwait(false);
                    if (!AfterSession(loop, loop.Current, wasReady: true)) return;
                }

                while (!ct.IsCancellationRequested)
                {
                    var rec = _svc.Store.Get(loop.PeerId);
                    if (rec == null || !rec.Dial) return;

                    // One session per peer: if any ready session exists (inbound or outbound), just wait.
                    if (loop.Suspended || _svc.HasReadySession(loop.PeerId))
                    {
                        await loop.Wake.WaitAsync(TimeSpan.FromSeconds(10), ct).ConfigureAwait(false);
                        continue;
                    }

                    bool wasReady = false;
                    Session? session = null;
                    foreach (var (ip, port) in _svc.ResolveEndpoints(loop.PeerId, rec))
                    {
                        var ws = await ConnectAsync(ip, port, ct).ConfigureAwait(false);
                        if (ws == null) continue;
                        session = new Session(ws, SessionRole.Outbound, _svc, ip.ToString(), port,
                                              new OutboundIntent { ExpectedPeerId = loop.PeerId });
                        loop.Current = session;
                        var run = _svc.RunSession(session);
                        wasReady = await session.ReadyTask.ConfigureAwait(false);
                        if (wasReady) backoff = TimeSpan.FromSeconds(1);
                        await run.ConfigureAwait(false);
                        break;
                    }
                    loop.Current = null;

                    if (session != null && !AfterSession(loop, session, wasReady)) return;
                    if (wasReady)
                    {
                        // Dropped after being up: retry almost immediately.
                        await Task.Delay(500, ct).ConfigureAwait(false);
                        continue;
                    }

                    await loop.Wake.WaitAsync(backoff, ct).ConfigureAwait(false);
                    backoff = TimeSpan.FromTicks(Math.Min(backoff.Ticks * 2, MaxBackoff.Ticks));
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex) { Log.Error($"Dial loop for {loop.PeerId} crashed", ex); }
            finally
            {
                lock (_gate)
                    if (_loops.TryGetValue(loop.PeerId, out var l) && l == loop) _loops.Remove(loop.PeerId);
            }
        }

        /// <summary>Decides what to do after an outbound session ended. Returns false to stop the loop.</summary>
        private bool AfterSession(DialLoop loop, Session s, bool wasReady)
        {
            if (s.Terminal) return false;
            if (s.FailReason is "unpaired" or "bad_mac")
            {
                // §3.2: the host no longer knows us — forget the key and ask the user to pair again.
                _svc.ForgetLocally(loop.PeerId, $"{s.PeerName} no longer recognises this PC. Pair again from the tray.");
                return false;
            }
            if (s.ByeReason == "replaced") loop.Suspended = true;
            return true;
        }

        /// <summary>First-time pairing with a host by its 6-digit code. Returns null on success, else a reason.</summary>
        public async Task<string?> PairAsync(IPAddress ip, int port, string code, string? expectedId, CancellationToken ct)
        {
            var ws = await ConnectAsync(ip, port, ct).ConfigureAwait(false);
            if (ws == null) return "unreachable";
            var session = new Session(ws, SessionRole.Outbound, _svc, ip.ToString(), port, new OutboundIntent
            {
                ExpectedPeerId = expectedId,
                PairSecret = System.Text.Encoding.UTF8.GetBytes(code),
            });
            var run = _svc.RunSession(session);
            bool ok = await session.ReadyTask.ConfigureAwait(false);
            if (!ok)
            {
                await run.ConfigureAwait(false);
                return session.FailReason ?? "disconnected";
            }
            StopLoop(session.PeerId!); // replace any stale loop with one adopting this session
            EnsureLoop(session.PeerId!, session, run);
            return null;
        }

        public static async Task<WebSocket?> ConnectAsync(IPAddress ip, int port, CancellationToken ct)
        {
            var ws = new ClientWebSocket();
            ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(30);
            string host = ip.AddressFamily == AddressFamily.InterNetworkV6 ? $"[{ip}]" : ip.ToString();
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(ConnectTimeout);
            try
            {
                await ws.ConnectAsync(new Uri($"ws://{host}:{port}{Protocol.Path}"), timeout.Token).ConfigureAwait(false);
                return ws;
            }
            catch (Exception ex)
            {
                if (ct.IsCancellationRequested) { ws.Dispose(); throw new OperationCanceledException(ct); }
                Log.Info($"Connect to {host}:{port} failed: {ex.Message}");
                ws.Dispose();
                return null;
            }
        }
    }
}
