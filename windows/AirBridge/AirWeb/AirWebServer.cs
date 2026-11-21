using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AirBridge.AirWeb
{
    /// <summary>One file in the shared pool. <see cref="By"/>: a device label (null = this PC). <see cref="Owner"/>: the uploading page's client id.</summary>
    public sealed record WebFile(int Id, string Name, long Size, string? Mime, long Added, string? By = null, string? Owner = null);

    /// <summary>A browser that is connected: seen within the peer TTL. <see cref="Label"/> is unique among the connected ones.</summary>
    public sealed record WebPeer(string Id, string Label, long Joined, long LastSeen);

    /// <summary>Where one upload is written. <see cref="Commit"/> makes it visible and returns its final name.</summary>
    public interface IUploadTarget
    {
        Stream Out { get; }
        string Commit();
        void Discard();
    }

    public interface IAirWebHost
    {
        string DeviceName();
        string Page();
        /// <summary>The whole pool: what this PC added and what browsers uploaded. Ids are unique across both.</summary>
        IReadOnlyList<WebFile> Offered();
        Stream? Open(WebFile file);
        /// <summary>Bytes free for uploads, or -1 when unknown.</summary>
        long FreeSpace();
        /// <summary>The QR code of <paramref name="text"/> as rows of modules (true = dark, no quiet zone).</summary>
        bool[][]? Qr(string text);
        /// <summary>Throws <see cref="IOException"/> if it can't be created.</summary>
        IUploadTarget CreateUpload(string rawName, string? mime, long size);
        void OnUploadStarted(long id, string name, long size, WebPeer? by);
        void OnUploadProgress(long id, long done, long total);
        void OnUploadFinished(long id, IUploadTarget? target, string? savedName);
        void OnDownloaded(WebFile file);
        void OnPeersChanged(IReadOnlyList<WebPeer> peers);
        /// <summary>Other addresses this PC can be reached at, so a page can offer them if its own stops answering.</summary>
        IReadOnlyList<string> Addresses();
    }

    /// <summary>
    /// AirWeb on this PC: a plain HTTP/1.1 server (one request per connection) that serves the shared page and the
    /// shared pool to browsers on the same network. The same page and protocol as the phone's and the Mac's, so the
    /// browser can't tell them apart. Every request needs the session token <c>t</c> (128 random bits, in the link),
    /// except the short-link jump. Async I/O, no thread per connection, so many browsers cost very little memory.
    /// </summary>
    public sealed class AirWebServer : IDisposable
    {
        public sealed record Limits
        {
            public int MaxConnections { get; init; } = 128;
            public int PerIp { get; init; } = 24;
            public int MaxTransfers { get; init; } = 6;
            public int MaxWaiting { get; init; } = 32;
            public int TransferWaitMs { get; init; } = 120_000;
            public int HeadDeadlineMs { get; init; } = 10_000;
            public int IdleMs { get; init; } = 30_000;
            public int StallMs { get; init; } = 45_000;
            public int PeerTtlMs { get; init; } = 20_000;
            public int MaxPeers { get; init; } = 256;
            public int MaxPool { get; init; } = 1000;
            public int MaxDownloadLogs { get; init; } = 512;
        }

        public static readonly int[] DefaultPorts = Enumerable.Range(47650, 10).ToArray();
        /// <summary>Pool ids of uploaded files start here (this PC's own files count up from 1).</summary>
        public const int UploadIdBase = 1_000_000;
        private const int Buf = 64 * 1024;
        private const long ProgressEvery = 256L * 1024;
        private const int MaxPeersListed = 24, MaxRetired = 8;

        private readonly IAirWebHost _host;
        private readonly Limits _limits;
        private readonly AirWebShortLink _short = new();
        private readonly SemaphoreSlim _slots;
        private readonly List<string> _retired = new();
        private readonly object _peerLock = new(), _logLock = new(), _reserveLock = new(), _connLock = new();
        private readonly Dictionary<string, PeerRec> _peers = new();
        private readonly Dictionary<string, HashSet<int>> _gotBy = new();
        private readonly Dictionary<string, Dictionary<int, int>> _activeBy = new();
        private readonly ConcurrentDictionary<string, int> _perIp = new();
        private readonly HashSet<Conn> _conns = new();
        private string _lastPeerKey = "";
        private long _reserved;
        private long _uploadIds;
        private int _connections, _waiting;
        private TcpListener? _listener;
        private CancellationTokenSource? _cts;
        private Timer? _sweeper;

        private sealed class PeerRec { public string Id = "", Label = ""; public long Joined, LastSeen; }

        private sealed class Conn
        {
            public TcpClient Client = null!;
            public long Last = Environment.TickCount64;
            public volatile bool Waiting;
            public void Touch() => Last = Environment.TickCount64;
        }

        /// <summary>The session token of the current link. <see cref="Rotate"/> replaces it.</summary>
        public string Token { get; private set; }

        public AirWebServer(string token, IAirWebHost host, Limits? limits = null)
        {
            Token = token; _host = host; _limits = limits ?? new Limits();
            _slots = new SemaphoreSlim(_limits.MaxTransfers, _limits.MaxTransfers);
        }

        public static string NewToken()
        {
            Span<byte> b = stackalloc byte[16];
            System.Security.Cryptography.RandomNumberGenerator.Fill(b);
            return Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        }

        public static string Url(string address, int port, string token) => $"http://{address}:{port}/?t={token}";

        public int Port => (_listener?.LocalEndpoint as IPEndPoint)?.Port ?? 0;
        public bool IsRunning => _listener != null;
        /// <summary>Downloads and uploads in progress right now.</summary>
        public int ActiveTransfers => _limits.MaxTransfers - _slots.CurrentCount;

        /// <summary>The connected browsers, oldest first.</summary>
        public IReadOnlyList<WebPeer> Peers() { lock (_peerLock) return Snapshot(); }

        public (string Code, long SecondsLeft) IssueShort() => _short.Issue();

        /// <summary>Gives the session a new link; a page open on an old one is told to move (and a refresh lands on the new one).</summary>
        public string Rotate(string? newToken = null)
        {
            var t = newToken ?? NewToken();
            lock (_retired)
            {
                _retired.Add(Token);
                while (_retired.Count > MaxRetired) _retired.RemoveAt(0);
                Token = t;
            }
            return t;
        }

        private bool IsRetired(string? given) { lock (_retired) return _retired.Any(r => Http.TokenMatches(given, r)); }

        // ---------------------------------------------------------------- start / stop

        /// <summary>Listens on <paramref name="preferred"/> if it is free, else the first free port of <paramref name="ports"/>. Returns the port; 0 when none is free.</summary>
        public int Start(int preferred = 0, int[]? ports = null)
        {
            if (_listener != null) return Port;
            var candidates = new List<int>();
            if (preferred > 0) candidates.Add(preferred);
            candidates.AddRange(ports ?? DefaultPorts);
            foreach (int p in candidates.Distinct())
            {
                try
                {
                    var l = new TcpListener(IPAddress.Any, p);
                    l.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, false);
                    l.Start(128);
                    _listener = l;
                    break;
                }
                catch (SocketException) { }
            }
            if (_listener == null) return 0;
            _cts = new CancellationTokenSource();
            _sweeper = new Timer(_ => Sweep(), null, 2000, 2000);
            _ = AcceptLoop(_listener, _cts.Token);
            return Port;
        }

        public void Stop()
        {
            var l = _listener; _listener = null;
            try { _cts?.Cancel(); } catch { }
            try { l?.Stop(); } catch { }
            _sweeper?.Dispose(); _sweeper = null;
            List<Conn> open;
            lock (_connLock) open = _conns.ToList();
            foreach (var c in open) try { c.Client.Close(); } catch { }
            lock (_peerLock) { _peers.Clear(); _lastPeerKey = ""; }
            lock (_logLock) { _gotBy.Clear(); _activeBy.Clear(); }
        }

        public void Dispose() => Stop();

        private async Task AcceptLoop(TcpListener l, CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                TcpClient client;
                try { client = await l.AcceptTcpClientAsync(ct).ConfigureAwait(false); }
                catch { break; }
                string ip = (client.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "?";
                if (!Admit(ip)) { _ = Reject(client); continue; }
                var conn = new Conn { Client = client };
                lock (_connLock) _conns.Add(conn);
                _ = Task.Run(async () =>
                {
                    try { await Serve(conn, ct).ConfigureAwait(false); }
                    catch { /* client went away, timed out, or storage failed: nothing more to answer */ }
                    finally { Leave(conn, ip); }
                });
            }
        }

        private bool Admit(string ip)
        {
            if (Interlocked.Increment(ref _connections) > _limits.MaxConnections) { Interlocked.Decrement(ref _connections); return false; }
            int n = _perIp.AddOrUpdate(ip, 1, (_, v) => v + 1);
            if (n > _limits.PerIp)
            {
                _perIp.AddOrUpdate(ip, 0, (_, v) => v - 1);
                Interlocked.Decrement(ref _connections);
                return false;
            }
            return true;
        }

        private void Leave(Conn conn, string ip)
        {
            lock (_connLock) _conns.Remove(conn);
            try { conn.Client.Close(); } catch { }
            Interlocked.Decrement(ref _connections);
            if (_perIp.AddOrUpdate(ip, 0, (_, v) => v - 1) <= 0) _perIp.TryRemove(ip, out _);
        }

        /// <summary>Too busy: answer 503 (the browser retries) without tying up a worker.</summary>
        private static async Task Reject(TcpClient c)
        {
            try
            {
                using var cts = new CancellationTokenSource(1500);
                var body = Encoding.ASCII.GetBytes("503 Service Unavailable\n");
                var head = Encoding.ASCII.GetBytes(Http.Head(503, "Service Unavailable", "text/plain; charset=utf-8", body.Length, "Retry-After: 3\r\n"));
                var s = c.GetStream();
                await s.WriteAsync(head, cts.Token).ConfigureAwait(false);
                await s.WriteAsync(body, cts.Token).ConfigureAwait(false);
                c.Client.Shutdown(SocketShutdown.Send);
                var tmp = new byte[1024];
                while (await s.ReadAsync(tmp, cts.Token).ConfigureAwait(false) > 0) { }
            }
            catch { }
            finally { try { c.Close(); } catch { } }
        }

        /// <summary>Every couple of seconds: browsers that went quiet leave, transfers that stalled are cut.</summary>
        private void Sweep()
        {
            try
            {
                long now = Environment.TickCount64;
                bool gone = false;
                lock (_peerLock)
                {
                    foreach (var id in _peers.Values.Where(p => now - p.LastSeen > _limits.PeerTtlMs).Select(p => p.Id).ToList()) { _peers.Remove(id); gone = true; }
                }
                if (gone) NotifyPeers();
                List<Conn> stalled;
                lock (_connLock) stalled = _conns.Where(c => !c.Waiting && now - c.Last > _limits.StallMs).ToList();
                foreach (var c in stalled) try { c.Client.Close(); } catch { }
            }
            catch { }
        }

        // ---------------------------------------------------------------- who is connected

        private static long Now() => Environment.TickCount64;

        private WebPeer? Touch(string? id, string? rawLabel)
        {
            if (id == null) return null;
            long now = Now();
            string label = Http.CleanLabel(rawLabel);
            bool changed;
            lock (_peerLock)
            {
                if (!_peers.TryGetValue(id, out var p))
                {
                    if (_peers.Count >= _limits.MaxPeers) _peers.Remove(_peers.Values.OrderBy(x => x.LastSeen).First().Id);
                    _peers[id] = new PeerRec { Id = id, Label = label, Joined = now, LastSeen = now };
                    changed = true;
                }
                else { p.LastSeen = now; changed = p.Label != label; p.Label = label; }
            }
            if (changed) NotifyPeers();
            return Peers().FirstOrDefault(x => x.Id == id);
        }

        private List<WebPeer> Snapshot()
        {
            var list = _peers.Values.OrderBy(p => p.Joined).ToList();
            var total = list.GroupBy(p => p.Label).ToDictionary(g => g.Key, g => g.Count());
            var seen = new Dictionary<string, int>();
            var res = new List<WebPeer>(list.Count);
            foreach (var p in list)
            {
                int k = seen.GetValueOrDefault(p.Label) + 1;
                seen[p.Label] = k;
                res.Add(new WebPeer(p.Id, total[p.Label] > 1 ? $"{p.Label} {k}" : p.Label, p.Joined, p.LastSeen));
            }
            return res;
        }

        private void NotifyPeers()
        {
            List<WebPeer> snap;
            lock (_peerLock)
            {
                snap = Snapshot();
                string key = string.Join("|", snap.Select(p => p.Id + "=" + p.Label));
                if (key == _lastPeerKey) return;
                _lastPeerKey = key;
            }
            try { _host.OnPeersChanged(snap); } catch { }
        }

        // ---------------------------------------------------------------- transfer slots (the waiting room)

        private async Task<bool> AcquireSlot(Conn conn, CancellationToken ct)
        {
            if (_slots.Wait(0)) return true;
            if (Interlocked.Increment(ref _waiting) > _limits.MaxWaiting) { Interlocked.Decrement(ref _waiting); return false; }
            conn.Waiting = true;
            try { return await _slots.WaitAsync(_limits.TransferWaitMs, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { return false; }
            finally { conn.Waiting = false; conn.Touch(); Interlocked.Decrement(ref _waiting); }
        }

        private void MarkGot(string? c, int id) { if (c != null) lock (_logLock) { if (!_gotBy.TryGetValue(c, out var s)) { if (_gotBy.Count >= _limits.MaxDownloadLogs) _gotBy.Remove(_gotBy.Keys.First()); _gotBy[c] = s = new(); } s.Add(id); } }

        private void MarkActive(string? c, int id, int delta)
        {
            if (c == null) return;
            lock (_logLock)
            {
                if (!_activeBy.TryGetValue(c, out var m)) _activeBy[c] = m = new();
                int n = m.GetValueOrDefault(id) + delta;
                if (n <= 0) { m.Remove(id); if (m.Count == 0) _activeBy.Remove(c); } else m[id] = n;
            }
        }

        private bool Reserve(long len)
        {
            lock (_reserveLock)
            {
                long free = _host.FreeSpace();
                if (free >= 0 && _reserved + len > free) return false;
                _reserved += len;
                return true;
            }
        }

        private void Unreserve(long len) { lock (_reserveLock) _reserved = Math.Max(0, _reserved - len); }

        // ---------------------------------------------------------------- one request per connection

        private async Task Serve(Conn conn, CancellationToken serverCt)
        {
            var stream = conn.Client.GetStream();
            conn.Client.NoDelay = true;
            var reader = new HttpReader(stream);
            HttpRequest? req;
            using (var head = CancellationTokenSource.CreateLinkedTokenSource(serverCt))
            {
                head.CancelAfter(_limits.HeadDeadlineMs);
                req = await reader.ReadHeadAsync(head.Token).ConfigureAwait(false);
            }
            conn.Touch();
            if (req == null) { await Simple(stream, 400, "Bad Request"); return; }
            // The one way in without the token: a short code that a connected page asked for.
            if (req.Path.StartsWith("/j/", StringComparison.Ordinal) && (req.Method is "GET" or "HEAD")) { await ShortJump(stream, req); return; }
            if (!Http.TokenMatches(req.Q("t"), Token))
            {
                if (IsRetired(req.Q("t"))) { await Moved(stream, req); return; }
                if (req.Path == "/" && req.Method == "GET") await Html(stream, 403, "Forbidden", Http.ExpiredPage());
                else await Simple(stream, 403, "Forbidden");
                return;
            }
            switch (req.Path)
            {
                case "/":
                    if (req.Method is "GET" or "HEAD") await Html(stream, 200, "OK", _host.Page().Replace("{{NAME}}", Http.EscapeHtml(_host.DeviceName())), req.Method == "HEAD");
                    else await Simple(stream, 405, "Method Not Allowed");
                    return;
                case "/api/state":
                    if (req.Method == "GET") await State(stream, req); else await Simple(stream, 405, "Method Not Allowed");
                    return;
                case "/qr.svg":
                    if (req.Method == "GET") await Qr(stream, req); else await Simple(stream, 405, "Method Not Allowed");
                    return;
                case "/api/short":
                    if (req.Method == "POST") { var s = _short.Issue(); await Json(stream, 200, "OK", $"{{\"code\":\"{s.Code}\",\"ttl\":{s.SecondsLeft}}}"); }
                    else await Simple(stream, 405, "Method Not Allowed");
                    return;
                case "/z":
                    if (req.Method is "GET" or "HEAD") await Zip(conn, stream, req, serverCt); else await Simple(stream, 405, "Method Not Allowed");
                    return;
                case "/u":
                    if (req.Method == "POST") await Upload(conn, reader, stream, req, serverCt); else await Simple(stream, 405, "Method Not Allowed");
                    return;
            }
            if (req.Path.StartsWith("/d/", StringComparison.Ordinal))
            {
                if (req.Method is "GET" or "HEAD") await Download(conn, stream, req, serverCt); else await Simple(stream, 405, "Method Not Allowed");
                return;
            }
            await Simple(stream, 404, "Not Found");
        }

        private static async Task Write(Stream s, string text) { var b = Encoding.UTF8.GetBytes(text); await s.WriteAsync(b); }

        private static async Task Simple(Stream s, int code, string reason, string extra = "")
        {
            var body = Encoding.ASCII.GetBytes($"{code} {reason}\n");
            await Write(s, Http.Head(code, reason, "text/plain; charset=utf-8", body.Length, extra));
            await s.WriteAsync(body);
        }

        private static async Task Html(Stream s, int code, string reason, string page, bool headOnly = false)
        {
            var body = Encoding.UTF8.GetBytes(page);
            await Write(s, Http.Head(code, reason, "text/html; charset=utf-8", body.Length, Http.PageHeaders));
            if (!headOnly) await s.WriteAsync(body);
        }

        private static async Task Json(Stream s, int code, string reason, string body)
        {
            var b = Encoding.UTF8.GetBytes(body);
            await Write(s, Http.Head(code, reason, "application/json; charset=utf-8", b.Length));
            await s.WriteAsync(b);
        }

        private async Task ShortJump(Stream s, HttpRequest req)
        {
            string given = req.Path["/j/".Length..].TrimEnd('/');
            switch (_short.Check(given))
            {
                case AirWebShortLink.Outcome.Match:
                    var body = Encoding.ASCII.GetBytes("Opening AirWeb…\n");
                    await Write(s, Http.Head(302, "Found", "text/plain; charset=utf-8", body.Length, $"Location: /?t={Token}\r\n"));
                    if (req.Method != "HEAD") await s.WriteAsync(body);
                    break;
                case AirWebShortLink.Outcome.Miss:
                    await Html(s, 404, "Not Found", Http.NoticePage("This short link has expired or isn't right", "On a device that is already connected, open the Invite section and ask for a new short link."));
                    break;
                default:
                    await Html(s, 429, "Too Many Requests", Http.NoticePage("Too many wrong tries", "Wait a minute, then type the short link again."));
                    break;
            }
        }

        /// <summary>A request with a replaced token: the state poll is told the new link, a page load is sent there.</summary>
        private async Task Moved(Stream s, HttpRequest req)
        {
            string to = "/?t=" + Token;
            if (req.Path == "/api/state" && req.Method == "GET") await Json(s, 200, "OK", $"{{\"moved\":{Http.JsonString(to)}}}");
            else if (req.Path == "/" && req.Method is "GET" or "HEAD")
            {
                var body = Encoding.UTF8.GetBytes("This link was replaced. Opening the new one…\n");
                await Write(s, Http.Head(302, "Found", "text/plain; charset=utf-8", body.Length, $"Location: {to}\r\n"));
                if (req.Method != "HEAD") await s.WriteAsync(body);
            }
            else await Simple(s, 403, "Forbidden");
        }

        private IReadOnlyList<string> Alternates()
        {
            try
            {
                int p = Port;
                return p == 0 ? Array.Empty<string>() : _host.Addresses().Take(4).Select(a => Url(a, p, Token)).ToList();
            }
            catch { return Array.Empty<string>(); }
        }

        private async Task State(Stream s, HttpRequest req)
        {
            string? c = Http.CleanClientId(req.Query.GetValueOrDefault("c"));
            Touch(c, req.Query.GetValueOrDefault("w"));
            var peers = Peers();
            var pool = _host.Offered();
            string sig = Http.Signature(pool);
            bool same = req.Q("v") == sig;
            List<int> got, act;
            if (c == null) { got = new(); act = new(); }
            else lock (_logLock)
            {
                var ids = pool.Select(f => f.Id).ToHashSet();
                if (_gotBy.TryGetValue(c, out var g)) { g.RemoveWhere(x => !ids.Contains(x)); got = g.OrderBy(x => x).ToList(); } else got = new();
                act = _activeBy.TryGetValue(c, out var a) ? a.Keys.Where(ids.Contains).OrderBy(x => x).ToList() : new();
            }
            var sb = new StringBuilder(256 + (same ? 0 : pool.Count * 120));
            sb.Append("{\"name\":").Append(Http.JsonString(_host.DeviceName()))
              .Append(",\"free\":").Append(_host.FreeSpace())
              .Append(",\"now\":").Append(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())
              .Append(",\"poll\":").Append(Math.Clamp(2000 + 100 * peers.Count, 2000, 8000))
              .Append(",\"v\":\"").Append(sig).Append('"')
              .Append(",\"gn\":").Append(got.Count)
              .Append(",\"act\":[").Append(string.Join(",", act)).Append(']')
              .Append(",\"count\":").Append(peers.Count)
              .Append(",\"peers\":[");
            var shown = peers.Take(MaxPeersListed).ToList();
            var mine = peers.FirstOrDefault(p => p.Id == c);
            if (mine != null && shown.All(p => p.Id != c)) { shown.RemoveAt(shown.Count - 1); shown.Add(mine); }
            for (int i = 0; i < shown.Count; i++)
            {
                if (i > 0) sb.Append(',');
                sb.Append("{\"id\":").Append(Http.JsonString(shown[i].Id)).Append(",\"name\":").Append(Http.JsonString(shown[i].Label)).Append('}');
            }
            sb.Append(']');
            var alts = Alternates();
            if (alts.Count > 0) sb.Append(",\"alts\":[").Append(string.Join(",", alts.Select(Http.JsonString))).Append(']');
            if (c != null && req.Q("g") != got.Count.ToString()) sb.Append(",\"got\":[").Append(string.Join(",", got)).Append(']');
            if (same) sb.Append(",\"same\":true");
            else
            {
                sb.Append(",\"files\":[");
                for (int i = 0; i < pool.Count; i++)
                {
                    var f = pool[i];
                    if (i > 0) sb.Append(',');
                    sb.Append("{\"id\":").Append(f.Id).Append(",\"name\":").Append(Http.JsonString(f.Name))
                      .Append(",\"size\":").Append(f.Size).Append(",\"added\":").Append(f.Added);
                    if (f.By != null) sb.Append(",\"by\":").Append(Http.JsonString(f.By));
                    if (f.Owner != null && f.Owner == c) sb.Append(",\"own\":true");
                    sb.Append('}');
                }
                sb.Append(']');
            }
            sb.Append('}');
            await Json(s, 200, "OK", sb.ToString());
        }

        private async Task Qr(Stream s, HttpRequest req)
        {
            // The link the browser itself used (hotspot and Wi-Fi addresses differ), so whoever scans it can reach us.
            string? hostHeader = req.Headers.GetValueOrDefault("host");
            if (hostHeader is not { Length: >= 3 and <= 80 } || !hostHeader.All(ch => char.IsLetterOrDigit(ch) || ".-:[]".IndexOf(ch) >= 0)) hostHeader = null;
            bool[][]? m = null;
            if (hostHeader != null) try { m = _host.Qr($"http://{hostHeader}/?t={Token}"); } catch { }
            if (m == null || m.Length == 0) { await Simple(s, 404, "Not Found"); return; }
            var body = Encoding.UTF8.GetBytes(Http.QrSvg(m));
            await Write(s, Http.Head(200, "OK", "image/svg+xml; charset=utf-8", body.Length));
            await s.WriteAsync(body);
        }

        private async Task Download(Conn conn, Stream s, HttpRequest req, CancellationToken ct)
        {
            string tail = req.Path["/d/".Length..];
            int slash = tail.IndexOf('/');
            string idText = slash < 0 ? tail : tail[..slash];
            var f = int.TryParse(idText, out int id) ? _host.Offered().FirstOrDefault(x => x.Id == id) : null;
            if (f == null) { await Simple(s, 404, "Not Found"); return; }
            string type = f.Mime is { } m && m.Contains('/') ? m : "application/octet-stream";
            string extra = $"Content-Disposition: {Http.ContentDisposition(f.Name)}\r\n";
            if (req.Method == "HEAD") { await Write(s, Http.Head(200, "OK", type, f.Size >= 0 ? f.Size : null, extra)); return; }
            if (!await AcquireSlot(conn, ct)) { await Simple(s, 503, "Service Unavailable", "Retry-After: 5\r\n"); return; }
            string? client = Http.CleanClientId(req.Query.GetValueOrDefault("c"));
            MarkActive(client, f.Id, 1);
            try
            {
                Stream? input; try { input = _host.Open(f); } catch { input = null; }
                if (input == null) { await Simple(s, 410, "Gone"); return; }
                using (input)
                {
                    await Write(s, Http.Head(200, "OK", type, f.Size >= 0 ? f.Size : null, extra));
                    var buf = new byte[Buf];
                    long sent = 0;
                    while (true)
                    {
                        int n = await input.ReadAsync(buf, ct).ConfigureAwait(false);
                        if (n <= 0) break;
                        int k = f.Size >= 0 ? (int)Math.Min(n, f.Size - sent) : n; // never more than announced
                        if (k <= 0) break;
                        await s.WriteAsync(buf.AsMemory(0, k), ct).ConfigureAwait(false);
                        sent += k;
                        conn.Touch();
                    }
                    await s.FlushAsync(ct);
                    if (f.Size < 0 || sent == f.Size) { MarkGot(client, f.Id); try { _host.OnDownloaded(f); } catch { } }
                }
            }
            finally { MarkActive(client, f.Id, -1); _slots.Release(); }
        }

        /// <summary>Wraps the socket for <see cref="ZipArchive"/>: write-only, non-seekable, each write counts as progress.</summary>
        private sealed class OutStream : Stream
        {
            private readonly Stream _s; private readonly Conn _c;
            public OutStream(Stream s, Conn c) { _s = s; _c = c; }
            public override bool CanRead => false; public override bool CanSeek => false; public override bool CanWrite => true;
            public override long Length => throw new NotSupportedException();
            public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
            public override void Flush() => _s.Flush();
            public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
            public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
            public override void SetLength(long value) => throw new NotSupportedException();
            public override void Write(byte[] buffer, int offset, int count) { _s.Write(buffer, offset, count); _c.Touch(); }
        }

        /// <summary>Several pool files as one ZIP, streamed (stored, not compressed: gentle on a slow CPU); each file counts as downloaded once it is through.</summary>
        private async Task Zip(Conn conn, Stream s, HttpRequest req, CancellationToken ct)
        {
            string? client = Http.CleanClientId(req.Query.GetValueOrDefault("c"));
            var pool = _host.Offered();
            var wanted = Http.ParseIds(req.Query.GetValueOrDefault("ids"), _limits.MaxPool * 4);
            var byId = pool.ToDictionary(f => f.Id);
            var chosen = wanted == null ? pool.ToList() : wanted.Where(byId.ContainsKey).Select(i => byId[i]).ToList();
            // Only what can be read right now.
            var files = chosen.Where(f => { try { using var x = _host.Open(f); return x != null; } catch { return false; } }).ToList();
            if (chosen.Count == 0) { await Simple(s, 404, "Not Found"); return; }
            if (files.Count == 0) { await Simple(s, 410, "Gone"); return; }
            string extra = $"Content-Disposition: {Http.ContentDisposition($"AirWeb-{files.Count}-files.zip")}\r\n";
            if (req.Method == "HEAD") { await Write(s, Http.Head(200, "OK", "application/zip", null, extra)); return; }
            if (!await AcquireSlot(conn, ct)) { await Simple(s, 503, "Service Unavailable", "Retry-After: 5\r\n"); return; }
            try
            {
                await Write(s, Http.Head(200, "OK", "application/zip", null, extra));
                var done = new List<WebFile>();
                await Task.Run(() =>
                {
                    var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                    using var archive = new ZipArchive(new OutStream(s, conn), ZipArchiveMode.Create, leaveOpen: true);
                    foreach (var f in files)
                    {
                        Stream? input; try { input = _host.Open(f); } catch { input = null; }
                        if (input == null) continue;
                        using (input)
                        {
                            var entry = archive.CreateEntry(UniqueName(names, f.Name), CompressionLevel.NoCompression);
                            entry.LastWriteTime = f.Added > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(f.Added).LocalDateTime : DateTime.Now;
                            using var o = entry.Open();
                            var buf = new byte[Buf];
                            int n;
                            while ((n = input.Read(buf, 0, buf.Length)) > 0) { ct.ThrowIfCancellationRequested(); o.Write(buf, 0, n); }
                        }
                        MarkGot(client, f.Id);
                        try { _host.OnDownloaded(f); } catch { }
                    }
                }, ct).ConfigureAwait(false);
                await s.FlushAsync(ct);
            }
            finally { _slots.Release(); }
        }

        /// <summary>Names made safe and unique inside the archive: no folders, no control characters, `photo (2).jpg` for a repeat.</summary>
        private static string UniqueName(HashSet<string> used, string raw)
        {
            string name = new string(raw.Select(c => c < ' ' || c == '/' || c == '\\' || c == ':' ? '_' : c).ToArray()).Trim();
            if (name.Length == 0 || name is "." or "..") name = "file";
            if (Encoding.UTF8.GetByteCount(name) > 200) name = name[..Math.Min(name.Length, 100)];
            string stem = Path.GetFileNameWithoutExtension(name), ext = Path.GetExtension(name), candidate = name;
            for (int i = 2; !used.Add(candidate); i++) candidate = $"{stem} ({i}){ext}";
            return candidate;
        }

        private async Task Upload(Conn conn, HttpReader input, Stream s, HttpRequest req, CancellationToken serverCt)
        {
            if (req.Headers.ContainsKey("transfer-encoding")) { await Simple(s, 411, "Length Required"); return; }
            long? len = req.ContentLength;
            if (len == null) { await Simple(s, 411, "Length Required"); return; }
            if (len < 0) { await Simple(s, 400, "Bad Request"); return; }
            string rawName = Http.BaseName(req.Q("n"));
            string? mime = req.Headers.GetValueOrDefault("content-type")?.Split(';')[0].Trim();
            if (mime == null || !mime.Contains('/') || mime == "application/octet-stream") mime = null;
            var who = Touch(Http.CleanClientId(req.Query.GetValueOrDefault("c")), req.Query.GetValueOrDefault("w"));
            if (_host.Offered().Count >= _limits.MaxPool) { await Json(s, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"full\"}"); return; }
            // Wait for a transfer slot first: the browser keeps sending into the connection's buffers meanwhile.
            if (!await AcquireSlot(conn, serverCt)) { await Simple(s, 503, "Service Unavailable", "Retry-After: 5\r\n"); return; }
            bool reserved = false;
            try
            {
                if (!Reserve(len.Value)) { await Json(s, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"space\"}"); return; }
                reserved = true;
                IUploadTarget target;
                try { target = _host.CreateUpload(rawName, mime, len.Value); }
                catch { await Json(s, 507, "Insufficient Storage", "{\"ok\":false,\"error\":\"save\"}"); return; }
                if (string.Equals(req.Headers.GetValueOrDefault("expect"), "100-continue", StringComparison.OrdinalIgnoreCase))
                    await Write(s, "HTTP/1.1 100 Continue\r\n\r\n");
                long id = Interlocked.Increment(ref _uploadIds);
                try { _host.OnUploadStarted(id, rawName, len.Value, who); } catch { }
                string? saved = null;
                try
                {
                    var buf = new byte[Buf];
                    long got = 0, lastReport = 0;
                    while (got < len)
                    {
                        using var idle = CancellationTokenSource.CreateLinkedTokenSource(serverCt);
                        idle.CancelAfter(_limits.IdleMs);
                        int n = await input.ReadAsync(buf, 0, (int)Math.Min(buf.Length, len.Value - got), idle.Token).ConfigureAwait(false);
                        if (n <= 0) throw new IOException("upload ended early");
                        await target.Out.WriteAsync(buf.AsMemory(0, n), serverCt).ConfigureAwait(false);
                        got += n;
                        conn.Touch();
                        if (got - lastReport >= ProgressEvery) { lastReport = got; try { _host.OnUploadProgress(id, got, len.Value); } catch { } }
                    }
                    await target.Out.FlushAsync(serverCt);
                    saved = target.Commit();
                }
                catch { target.Discard(); throw; }
                finally { try { _host.OnUploadFinished(id, saved != null ? target : null, saved); } catch { } }
                await Json(s, 200, "OK", $"{{\"ok\":true,\"name\":{Http.JsonString(saved ?? "")}}}");
            }
            finally { if (reserved) Unreserve(len.Value); _slots.Release(); }
        }
    }
}
