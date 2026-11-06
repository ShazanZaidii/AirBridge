using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;

namespace AirBridge.Net
{
    public enum PairCheck { Ok, BadProof, Locked }

    /// <summary>
    /// Host-side pairing state (§2.4, §3.3): the QR secret (32 bytes, ≤ 10 min, rotated after use), and the
    /// lockout after 5 failed proofs (either mode) for 60 s, which also rotates the QR secret.
    /// </summary>
    public sealed class PairingSecrets : IDisposable
    {
        private static readonly TimeSpan QrLifetime = TimeSpan.FromMinutes(10);
        private static readonly TimeSpan LockDuration = TimeSpan.FromSeconds(60);
        private const int MaxFailures = 5;

        private readonly object _gate = new();
        private readonly Timer _timer;
        private byte[] _qrSecret = Array.Empty<byte>();
        private DateTime _qrCreated;
        private bool _qrUsedSinceShown;
        private int _failures;
        private DateTime _lockedUntil = DateTime.MinValue;

        /// <summary>Raised (any thread) when the QR secret or lock state changes.</summary>
        public event Action? Changed;

        public PairingSecrets()
        {
            RotateQr(raise: false);
            _timer = new Timer(_ => Tick(), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));
        }

        public string QrSecretB64Url { get { lock (_gate) return Protocol.B64Url(_qrSecret); } }
        public bool IsLocked { get { lock (_gate) return DateTime.UtcNow < _lockedUntil; } }

        /// <summary>Call whenever the QR becomes visible: rotates it if a pairing succeeded since it was last shown.</summary>
        public void OnQrShown()
        {
            bool rotate;
            lock (_gate) { rotate = _qrUsedSinceShown; }
            if (rotate) RotateQr(raise: true);
        }

        /// <summary>Mode qr: checks proof = HMAC(K, "pair|" | T) with K derived from the current QR secret.</summary>
        public PairCheck VerifyQr(string clientId, string hostId, byte[] transcript, byte[] proof, out byte[] key)
        {
            key = Array.Empty<byte>();
            byte[] k;
            lock (_gate)
            {
                if (DateTime.UtcNow < _lockedUntil) return PairCheck.Locked;
                k = HandshakeCrypto.QrPairKey(_qrSecret, clientId, hostId);
            }
            if (Protocol.MacEquals(HandshakeCrypto.PairProof(k, transcript), proof))
            {
                key = k;
                RecordSuccess(qr: true);
                return PairCheck.Ok;
            }
            CryptographicOperations.ZeroMemory(k);
            RecordFailure();
            return PairCheck.BadProof;
        }

        public void RecordSuccess(bool qr)
        {
            lock (_gate)
            {
                _failures = 0;
                if (qr) _qrUsedSinceShown = true;
            }
            Changed?.Invoke();
        }

        /// <summary>A failed pair proof (either mode). The 5th failure locks pairing for 60 s and rotates the QR.</summary>
        public void RecordFailure()
        {
            bool locked = false;
            lock (_gate)
            {
                if (++_failures >= MaxFailures)
                {
                    _failures = 0;
                    _lockedUntil = DateTime.UtcNow + LockDuration;
                    locked = true;
                }
            }
            if (locked)
            {
                Log.Warn("Pairing locked for 60 s after 5 failed attempts; QR secret rotated");
                RotateQr(raise: true);
            }
        }

        private void Tick()
        {
            bool qr, unlock;
            lock (_gate)
            {
                var now = DateTime.UtcNow;
                qr = now - _qrCreated >= QrLifetime;
                unlock = _lockedUntil != DateTime.MinValue && now >= _lockedUntil;
                if (unlock) _lockedUntil = DateTime.MinValue;
            }
            if (qr) RotateQr(raise: false);
            if (qr || unlock) Changed?.Invoke();
        }

        private void RotateQr(bool raise)
        {
            lock (_gate)
            {
                CryptographicOperations.ZeroMemory(_qrSecret);
                _qrSecret = Protocol.RandomBytes(32);
                _qrCreated = DateTime.UtcNow;
                _qrUsedSinceShown = false;
            }
            if (raise) Changed?.Invoke();
        }

        public void Dispose() => _timer.Dispose();
    }

    /// <summary>
    /// A host-side `confirm` pairing request waiting for the local user (§3.3). The UI shows
    /// "<see cref="PeerName"/> (<see cref="Plat"/>) wants to pair — <see cref="Digits"/> — Accept / Reject" and calls
    /// <see cref="Decide"/> once; <see cref="Ended"/> fires when the request is over for any reason (accepted,
    /// rejected, timed out, peer disconnected) so the prompt can close itself.
    /// </summary>
    public sealed class PairApproval
    {
        private readonly Action<bool> _decide;
        private int _decided;

        public PairApproval(string peerId, string peerName, string plat, string digits, DateTime expiresUtc,
                            CancellationToken ended, Action<bool> decide)
        {
            PeerId = peerId; PeerName = peerName; Plat = plat; Digits = digits; ExpiresUtc = expiresUtc;
            Ended = ended; _decide = decide;
        }

        public string PeerId { get; }
        public string PeerName { get; }
        public string Plat { get; }
        /// <summary>The 6 confirmation digits, e.g. "483920".</summary>
        public string Digits { get; }
        public DateTime ExpiresUtc { get; }
        public CancellationToken Ended { get; }

        /// <summary>Accept (true) or reject (false). Only the first call counts; safe from any thread.</summary>
        public void Decide(bool accept)
        {
            if (Interlocked.Exchange(ref _decided, 1) == 1) return;
            try { _decide(accept); } catch (Exception ex) { Log.Error("Pair decision failed", ex); }
        }

        /// <summary>User-facing platform label.</summary>
        public static string PlatLabel(string plat) => plat switch
        {
            "android" => "Android", "macos" => "macOS", "windows" => "Windows", "linux" => "Linux", "ios" => "iOS",
            _ => string.IsNullOrEmpty(plat) ? "unknown device" : plat,
        };
    }

    /// <summary>A parsed airbridge://pair link (§2.4).</summary>
    public sealed record PairLink(string Id, string Name, string Plat, IPAddress? Addr, int Port, byte[] Secret)
    {
        public static PairLink? TryParse(string? text)
        {
            if (string.IsNullOrWhiteSpace(text) || !Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri)) return null;
            if (!uri.Scheme.Equals("airbridge", StringComparison.OrdinalIgnoreCase) || !uri.Host.Equals("pair", StringComparison.OrdinalIgnoreCase)) return null;
            var q = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var part in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
            {
                int eq = part.IndexOf('=');
                if (eq <= 0) continue;
                q[Uri.UnescapeDataString(part[..eq])] = Uri.UnescapeDataString(part[(eq + 1)..].Replace('+', ' '));
            }
            if (!q.TryGetValue("v", out var v) || v != "2") return null;
            if (!q.TryGetValue("id", out var id) || !Guid.TryParse(id, out _)) return null;
            if (!q.TryGetValue("k", out var k)) return null;
            byte[] secret;
            try
            {
                string b64 = k.Replace('-', '+').Replace('_', '/');
                b64 = b64.PadRight(b64.Length + (4 - b64.Length % 4) % 4, '=');
                secret = Convert.FromBase64String(b64);
            }
            catch (FormatException) { return null; }
            if (secret.Length != 32) return null;
            IPAddress? addr = q.TryGetValue("addr", out var a) && IPAddress.TryParse(a.Trim('[', ']'), out var ip) ? ip : null;
            int port = q.TryGetValue("port", out var ps) && int.TryParse(ps, out var pn) && pn is > 0 and < 65536 ? pn : Protocol.BasePort;
            return new PairLink(id.ToLowerInvariant(), q.GetValueOrDefault("name") ?? "AirBridge host", q.GetValueOrDefault("plat") ?? "", addr, port, secret);
        }
    }

    public sealed class PeerRecord
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public string Plat { get; set; } = "";
        /// <summary>K protected with DPAPI (CurrentUser), base64.</summary>
        public string Key { get; set; } = "";
        /// <summary>True if this peer is a host we dial (we paired with it as a client).</summary>
        public bool Dial { get; set; }
        public string? Addr { get; set; }
        public int Port { get; set; }
        public long PairedAt { get; set; }
    }

    /// <summary>Paired peers in %LOCALAPPDATA%\AirBridge\peers.json; keys protected with DPAPI.</summary>
    public sealed class PeerStore
    {
        private static readonly string FilePath = System.IO.Path.Combine(Log.Dir, "peers.json");
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("AirBridge.v2.peerkey");
        private readonly object _gate = new();
        private readonly Dictionary<string, PeerRecord> _peers = new();

        public event Action? Changed;

        public PeerStore()
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    var list = JsonSerializer.Deserialize<List<PeerRecord>>(File.ReadAllText(FilePath)) ?? new();
                    foreach (var p in list.Where(p => !string.IsNullOrEmpty(p.Id))) _peers[p.Id] = p;
                }
            }
            catch (Exception ex) { Log.Error("peers.json unreadable; starting with no paired peers", ex); }
        }

        public List<PeerRecord> All() { lock (_gate) return _peers.Values.Select(Clone).ToList(); }

        public PeerRecord? Get(string id) { lock (_gate) return _peers.TryGetValue(id, out var p) ? Clone(p) : null; }

        public byte[]? GetKey(string id)
        {
            string? prot;
            lock (_gate) prot = _peers.TryGetValue(id, out var p) ? p.Key : null;
            if (string.IsNullOrEmpty(prot)) return null;
            try { return ProtectedData.Unprotect(Convert.FromBase64String(prot), Entropy, DataProtectionScope.CurrentUser); }
            catch (Exception ex) { Log.Error($"Cannot unprotect key for {id}", ex); return null; }
        }

        public void Upsert(string id, string name, string plat, byte[] key, bool dial, string? addr, int port)
        {
            string prot = Convert.ToBase64String(ProtectedData.Protect(key, Entropy, DataProtectionScope.CurrentUser));
            lock (_gate)
            {
                _peers.TryGetValue(id, out var old);
                _peers[id] = new PeerRecord
                {
                    Id = id, Name = name, Plat = plat, Key = prot,
                    Dial = dial || (old?.Dial ?? false),
                    Addr = addr ?? old?.Addr, Port = port != 0 ? port : old?.Port ?? 0,
                    PairedAt = Protocol.NowMs(),
                };
                SaveLocked();
            }
            Changed?.Invoke();
        }

        public void Update(string id, Action<PeerRecord> change)
        {
            lock (_gate)
            {
                if (!_peers.TryGetValue(id, out var p)) return;
                change(p);
                SaveLocked();
            }
            Changed?.Invoke();
        }

        public bool Remove(string id)
        {
            bool removed;
            lock (_gate)
            {
                removed = _peers.Remove(id);
                if (removed) SaveLocked();
            }
            if (removed) Changed?.Invoke();
            return removed;
        }

        private void SaveLocked()
        {
            try
            {
                Directory.CreateDirectory(Log.Dir);
                string tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(_peers.Values.ToList()));
                File.Move(tmp, FilePath, overwrite: true);
            }
            catch (Exception ex) { Log.Error("Failed to save peers.json", ex); }
        }

        private static PeerRecord Clone(PeerRecord p) => new()
        {
            Id = p.Id, Name = p.Name, Plat = p.Plat, Key = p.Key, Dial = p.Dial, Addr = p.Addr, Port = p.Port, PairedAt = p.PairedAt,
        };
    }
}
