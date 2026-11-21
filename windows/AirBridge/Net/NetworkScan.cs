using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AirBridge.Net
{
    /// <summary>
    /// Finds AirBridge devices without multicast: many routers drop the mDNS packets <see cref="Discovery"/> relies on
    /// (and some PCs' firewalls or VPN adapters swallow them), while plain TCP still works. Every address of this PC's
    /// /24 networks is tried on the host ports; whatever accepts is asked who it is with a <c>hello</c> (no pairing,
    /// nothing stored) and listed like an mDNS result. Runs only when asked for (or while the connect window finds
    /// nothing), never in the background, so it costs nothing the rest of the time.
    /// </summary>
    public static class NetworkScan
    {
        private static readonly int[] Ports = { 47632, 47633, 47634, 47635 };
        private const int ConnectMs = 400;

        /// <summary>The /24 prefixes of this PC's usable addresses: "192.168.1".</summary>
        public static List<string> Subnets() =>
            NetUtil.LocalIPv4Addresses().Select(a => a.GetAddressBytes())
                .Where(b => !(b[0] == 169 && b[1] == 254))
                .Select(b => $"{b[0]}.{b[1]}.{b[2]}").Distinct().ToList();

        public static async Task<List<DiscoveredHost>> ScanAsync(IReadOnlySet<string> skipIds, CancellationToken ct)
        {
            var mine = NetUtil.LocalIPv4Addresses().Select(a => a.ToString()).ToHashSet();
            var targets = Subnets().SelectMany(p => Enumerable.Range(1, 254).Select(i => $"{p}.{i}")).Where(ip => !mine.Contains(ip)).ToList();
            var open = new List<(string Ip, int Port)>();
            using var gate = new SemaphoreSlim(64);
            using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
            limit.CancelAfter(TimeSpan.FromSeconds(25));
            var probes = targets.SelectMany(ip => Ports.Select(port => (ip, port))).Select(async t =>
            {
                try { await gate.WaitAsync(limit.Token).ConfigureAwait(false); } catch { return; }
                try { if (await PortOpenAsync(t.ip, t.port, limit.Token).ConfigureAwait(false)) lock (open) open.Add(t); }
                finally { gate.Release(); }
            }).ToList();
            try { await Task.WhenAll(probes).ConfigureAwait(false); } catch { }

            var found = new List<DiscoveredHost>();
            var hellos = open.Select(async t =>
            {
                var h = await PeekAsync(t.Ip, t.Port, ct).ConfigureAwait(false);
                if (h != null && !skipIds.Contains(h.Id)) lock (found) found.Add(h);
            }).ToList();
            try { await Task.WhenAll(hellos).ConfigureAwait(false); } catch { }
            Log.Info($"Network scan: {targets.Count} addresses, {open.Count} answered, found: " +
                     (found.Count == 0 ? "none" : string.Join(", ", found.Select(f => $"{f.Name} ({f.Plat}) at {f.Addresses.FirstOrDefault()}"))));
            return found.GroupBy(f => f.Id).Select(g => g.First()).ToList();
        }

        private static async Task<bool> PortOpenAsync(string ip, int port, CancellationToken ct)
        {
            using var c = new TcpClient();
            try
            {
                using var t = CancellationTokenSource.CreateLinkedTokenSource(ct);
                t.CancelAfter(ConnectMs);
                await c.ConnectAsync(IPAddress.Parse(ip), port, t.Token).ConfigureAwait(false);
                return true;
            }
            catch { return false; }
        }

        /// <summary>Opens <c>/v2</c>, sends our hello, reads theirs, closes: who is there.</summary>
        public static async Task<DiscoveredHost?> PeekAsync(string ip, int port, CancellationToken ct)
        {
            var app = App.Peers;
            if (app == null) return null;
            using var eph = HandshakeCrypto.NewEphemeral();
            using var ws = new ClientWebSocket();
            using var t = CancellationTokenSource.CreateLinkedTokenSource(ct);
            t.CancelAfter(TimeSpan.FromSeconds(3));
            try
            {
                await ws.ConnectAsync(new Uri($"ws://{ip}:{port}{Protocol.Path}"), t.Token).ConfigureAwait(false);
                var hello = app.BuildHelloForPeek(Protocol.RandomBytes(16), HandshakeCrypto.EncodePublicKey(eph));
                await ws.SendAsync(Encoding.UTF8.GetBytes(Protocol.Serialize(hello)), WebSocketMessageType.Text, true, t.Token).ConfigureAwait(false);
                var buf = new byte[16 * 1024];
                var res = await ws.ReceiveAsync(buf, t.Token).ConfigureAwait(false);
                if (res.MessageType != WebSocketMessageType.Text) return null;
                var o = Protocol.Parse(Encoding.UTF8.GetString(buf, 0, res.Count));
                if (o == null || Protocol.Str(o, "t") != "hello") return null;
                string id = (Protocol.Str(o, "id") ?? "").ToLowerInvariant();
                if (id.Length == 0 || id == app.Identity.Id) return null;
                try { await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None).ConfigureAwait(false); } catch { }
                return new DiscoveredHost(id, Protocol.Str(o, "name") ?? "Device", Protocol.Str(o, "plat") ?? "", port,
                    new List<IPAddress> { IPAddress.Parse(ip) }, DateTime.UtcNow);
            }
            catch { return null; }
        }
    }
}
