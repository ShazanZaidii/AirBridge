using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using QRCoder;

namespace AirBridge.AirWeb
{
    /// <summary>A file this PC offers.</summary>
    public sealed record OfferedFile(int Id, string Path, string Name, long Size, long Added, int Downloads = 0, long DownloadedAt = 0);

    /// <summary>An upload from a browser: in progress, saved (and shared until stopped) or failed.</summary>
    public sealed record ReceivedFile(long Id, string Name, long Size, long Done = 0, bool Saved = false, bool Failed = false,
        string? Path = null, string? By = null, string? Owner = null, bool Shared = true, int Downloads = 0, long At = 0);

    /// <summary>
    /// The one AirWeb session of this PC: its server, link, shared files and uploads. It lives until it is stopped
    /// (closing the window does not end it), so sharing more files later joins the same server, link and pool.
    /// Pure .NET (no WPF), so it is tested on any OS.
    /// </summary>
    public sealed class AirWebSession : IAirWebHost, IDisposable
    {
        /// <summary>With nobody connected and nothing moving for this long, the session ends by itself.</summary>
        public static readonly TimeSpan IdleStop = TimeSpan.FromHours(2);

        private readonly object _gate = new();
        private readonly Func<IReadOnlyList<string>> _addresses;
        private readonly Func<string> _deviceName;
        private readonly Func<string> _pageText;
        private readonly string _saveDir;
        private readonly AirWebServer _server;
        private List<OfferedFile> _offered = new();
        private List<ReceivedFile> _received = new();
        private int _nextId = 1;
        private int _port;
        private int _lastPort;
        private string _address = "";
        private long _lastBusy = Environment.TickCount64;
        private Timer? _watch;

        /// <summary>Raised on any thread when what the window shows may have changed.</summary>
        public event Action? Changed;
        /// <summary>Raised once when the session ended by itself after a long idle.</summary>
        public event Action? IdleEnded;

        public AirWebSession(Func<IReadOnlyList<string>> addresses, Func<string> deviceName, Func<string> pageText, string saveDir)
        {
            _addresses = addresses; _deviceName = deviceName; _pageText = pageText; _saveDir = saveDir;
            _server = new AirWebServer(AirWebServer.NewToken(), this);
        }

        public string Token => _server.Token;
        public int Port => _port;
        public bool Failed { get; private set; }
        public string Address { get { lock (_gate) return _address; } }
        public IReadOnlyList<string> Addresses => _addresses();
        public IReadOnlyList<WebPeer> Peers => _server.Peers();
        public int ActiveTransfers => _server.ActiveTransfers;

        public IReadOnlyList<OfferedFile> OfferedFiles { get { lock (_gate) return _offered.ToList(); } }
        public IReadOnlyList<ReceivedFile> ReceivedFiles { get { lock (_gate) return _received.ToList(); } }

        /// <summary>The link for the current address, or null when the server isn't up or there is no network.</summary>
        public string? Link => _port != 0 && Address.Length > 0 ? AirWebServer.Url(Address, _port, Token) : null;
        /// <summary>The same address without the token (the base of the short link).</summary>
        public string? Base => _port != 0 && Address.Length > 0 ? $"{Address}:{_port}" : null;

        public (string Url, long SecondsLeft)? ShortLink()
        {
            if (Base is not { } b) return null;
            var s = _server.IssueShort();
            return ($"{b}/j/{s.Code}", s.SecondsLeft);
        }

        /// <summary>A new link on purpose; pages open on the old one are told where to go.</summary>
        public void NewLink() { _server.Rotate(); Raise(); }

        // ---------------------------------------------------------------- lifecycle

        public void Start()
        {
            if (_server.IsRunning) return;
            _port = _server.Start(_lastPort);
            Failed = _port == 0;
            if (_port != 0) _lastPort = _port;
            RefreshAddress();
            _lastBusy = Environment.TickCount64;
            _watch ??= new Timer(_ => Watch(), null, 15_000, 15_000);
            Raise();
        }

        public void Stop()
        {
            _watch?.Dispose(); _watch = null;
            _server.Stop();
            _port = 0;
            lock (_gate) { _offered = new(); _received = new(); }
            Raise();
        }

        public void Dispose() => Stop();

        /// <summary>Looks at the PC's addresses again (Wi-Fi, hotspot, Ethernet come and go); keeps the one in use if still there.</summary>
        public void RefreshAddress()
        {
            var list = _addresses();
            lock (_gate) { if (!list.Contains(_address)) _address = list.FirstOrDefault() ?? ""; }
        }

        /// <summary>Several addresses: show the next one.</summary>
        public void NextAddress()
        {
            var list = _addresses();
            if (list.Count < 2) return;
            lock (_gate) { int i = list.ToList().IndexOf(_address); _address = list[(i + 1 + list.Count) % list.Count]; }
            Raise();
        }

        private void Watch()
        {
            try
            {
                RefreshAddress();
                long now = Environment.TickCount64;
                if (_server.Peers().Count > 0 || _server.ActiveTransfers > 0) _lastBusy = now;
                else if (now - _lastBusy > (long)IdleStop.TotalMilliseconds) { Stop(); IdleEnded?.Invoke(); }
                else Raise(); // a quiet refresh: the address may have changed
            }
            catch { }
        }

        private void Raise() { try { Changed?.Invoke(); } catch { } }

        // ---------------------------------------------------------------- what this PC shares

        public void Add(IEnumerable<string> paths)
        {
            long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            lock (_gate)
            {
                foreach (var p in paths)
                {
                    try
                    {
                        var fi = new FileInfo(p);
                        if (!fi.Exists || _offered.Any(o => string.Equals(o.Path, fi.FullName, StringComparison.OrdinalIgnoreCase))) continue;
                        _offered.Add(new OfferedFile(_nextId++, fi.FullName, fi.Name, fi.Length, now));
                    }
                    catch { }
                }
            }
            Raise();
        }

        public void Remove(int id) { lock (_gate) _offered = _offered.Where(o => o.Id != id).ToList(); Raise(); }

        /// <summary>Stops sharing an uploaded file with the other browsers (it stays in the save folder).</summary>
        public void StopSharing(long id)
        {
            lock (_gate) _received = _received.Select(r => r.Id == id ? r with { Shared = false } : r).ToList();
            Raise();
        }

        // ---------------------------------------------------------------- IAirWebHost

        public string DeviceName() => _deviceName();
        public string Page() => _pageText();

        public IReadOnlyList<WebFile> Offered()
        {
            lock (_gate)
                return _offered.Select(o => new WebFile(o.Id, o.Name, o.Size, null, o.Added))
                    .Concat(_received.Where(r => r.Saved && r.Shared && r.Path != null)
                        .Select(r => new WebFile(AirWebServer.UploadIdBase + (int)r.Id, r.Name, r.Size, null, r.At, r.By, r.Owner)))
                    .ToList();
        }

        public Stream? Open(WebFile file)
        {
            string? path;
            lock (_gate)
            {
                path = file.Id >= AirWebServer.UploadIdBase
                    ? _received.FirstOrDefault(r => r.Id == file.Id - AirWebServer.UploadIdBase && r.Shared)?.Path
                    : _offered.FirstOrDefault(o => o.Id == file.Id)?.Path;
            }
            if (path == null) return null;
            return new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 64 * 1024, FileOptions.SequentialScan | FileOptions.Asynchronous);
        }

        public long FreeSpace()
        {
            try
            {
                Directory.CreateDirectory(_saveDir);
                // Keep a margin so a full upload never leaves the PC with no space at all.
                return Math.Max(0, new DriveInfo(Path.GetPathRoot(Path.GetFullPath(_saveDir))!).AvailableFreeSpace - 64L * 1024 * 1024);
            }
            catch { return -1; }
        }

        public bool[][]? Qr(string text)
        {
            try
            {
                using var gen = new QRCodeGenerator();
                using var data = gen.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
                var rows = data.ModuleMatrix;
                int n = rows.Count;
                // The matrix carries a quiet zone; keep only the symbol itself (the SVG adds its own).
                int min = n, max = -1;
                for (int y = 0; y < n; y++)
                    for (int x = 0; x < n; x++)
                        if (rows[y][x]) { min = Math.Min(min, Math.Min(x, y)); max = Math.Max(max, Math.Max(x, y)); }
                if (max < min) return null;
                int size = max - min + 1;
                var m = new bool[size][];
                for (int y = 0; y < size; y++) { m[y] = new bool[size]; for (int x = 0; x < size; x++) m[y][x] = rows[y + min][x + min]; }
                return m;
            }
            catch { return null; }
        }

        private sealed class Upload : IUploadTarget
        {
            private readonly string _part, _final;
            private readonly FileStream _fs;
            public Upload(string part, string final) { _part = part; _final = final; _fs = new FileStream(part, FileMode.CreateNew, FileAccess.Write, FileShare.None, 64 * 1024, FileOptions.Asynchronous); }
            public Stream Out => _fs;
            public string Commit()
            {
                _fs.Dispose();
                File.Move(_part, _final);
                return Path.GetFileName(_final);
            }
            public void Discard() { try { _fs.Dispose(); } catch { } try { File.Delete(_part); } catch { } }
            public string FinalPath => _final;
        }

        public IUploadTarget CreateUpload(string rawName, string? mime, long size)
        {
            Directory.CreateDirectory(_saveDir);
            string name = SafeName(rawName);
            lock (_gate)
            {
                string stem = Path.GetFileNameWithoutExtension(name), ext = Path.GetExtension(name);
                string candidate = name;
                for (int i = 2; File.Exists(Path.Combine(_saveDir, candidate)) || File.Exists(Path.Combine(_saveDir, candidate) + ".part"); i++) candidate = $"{stem} ({i}){ext}";
                string final = Path.Combine(_saveDir, candidate);
                return new Upload(final + ".part", final);
            }
        }

        private static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
            { "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9" };

        /// <summary>A name Windows accepts: no reserved characters or device names, no trailing dots or spaces.</summary>
        public static string SafeName(string raw)
        {
            var bad = Path.GetInvalidFileNameChars();
            string n = new string((raw ?? "").Select(c => c < ' ' || bad.Contains(c) ? '_' : c).ToArray()).Trim().TrimEnd('.', ' ');
            if (n.Length == 0) n = "file";
            if (Reserved.Contains(Path.GetFileNameWithoutExtension(n))) n = "_" + n;
            if (n.Length > 150) { string ext = Path.GetExtension(n); n = n[..Math.Max(1, 150 - ext.Length)] + ext; }
            return n;
        }

        public void OnUploadStarted(long id, string name, long size, WebPeer? by)
        {
            lock (_gate) _received = _received.Append(new ReceivedFile(id, SafeName(name), size, By: by?.Label, Owner: by?.Id, At: Now())).ToList();
            Raise();
        }

        public void OnUploadProgress(long id, long done, long total)
        {
            lock (_gate) _received = _received.Select(r => r.Id == id ? r with { Done = done } : r).ToList();
            Raise();
        }

        public void OnUploadFinished(long id, IUploadTarget? target, string? savedName)
        {
            lock (_gate)
            {
                _received = _received.Select(r =>
                    r.Id != id ? r
                    : target is Upload u && savedName != null ? r with { Name = savedName, Done = r.Size, Saved = true, Path = u.FinalPath, At = Now() }
                    : r with { Failed = true, At = Now() }).ToList();
            }
            Raise();
        }

        public void OnDownloaded(WebFile file)
        {
            lock (_gate)
            {
                if (file.Id >= AirWebServer.UploadIdBase)
                    _received = _received.Select(r => r.Id == file.Id - AirWebServer.UploadIdBase ? r with { Downloads = r.Downloads + 1 } : r).ToList();
                else
                    _offered = _offered.Select(o => o.Id == file.Id ? o with { Downloads = o.Downloads + 1, DownloadedAt = Now() } : o).ToList();
            }
            Raise();
        }

        public void OnPeersChanged(IReadOnlyList<WebPeer> peers) => Raise();

        IReadOnlyList<string> IAirWebHost.Addresses() => _addresses();

        private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }
}
