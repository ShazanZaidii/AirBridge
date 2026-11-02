using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;

namespace AirBridge.Net
{
    public enum PairCheck { Ok, BadCode, Locked }

    /// <summary>
    /// Host-side pairing secrets (§2.4, §3.3): QR secret (32 bytes) and 6-digit code, rotation and lockout.
    /// </summary>
    public sealed class PairingSecrets : IDisposable
    {
        private static readonly TimeSpan QrLifetime = TimeSpan.FromMinutes(10);
        private static readonly TimeSpan CodeLifetime = TimeSpan.FromMinutes(5);
        private static readonly TimeSpan LockDuration = TimeSpan.FromSeconds(60);
        private const int MaxFailures = 5;

        private readonly object _gate = new();
        private readonly Timer _timer;
        private byte[] _qrSecret = Array.Empty<byte>();
        private DateTime _qrCreated;
        private bool _qrUsedSinceShown;
        private string _code = "";
        private DateTime _codeCreated;
        private int _failures;
        private DateTime _lockedUntil = DateTime.MinValue;

        /// <summary>Raised (any thread) when the QR secret, code or lock state changes.</summary>
        public event Action? Changed;

        public PairingSecrets()
        {
            RotateQr(raise: false);
            RotateCode(raise: false);
            _timer = new Timer(_ => Tick(), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));
        }

        public string Code { get { lock (_gate) return _code; } }
        public string QrSecretB64Url { get { lock (_gate) return Protocol.B64Url(_qrSecret); } }
        public DateTime CodeExpires { get { lock (_gate) return _codeCreated + CodeLifetime; } }
        public bool IsLocked { get { lock (_gate) return DateTime.UtcNow < _lockedUntil; } }

        /// <summary>Call whenever the QR becomes visible: rotates it if a pairing succeeded since it was last shown.</summary>
        public void OnQrShown()
        {
            bool rotate;
            lock (_gate) { rotate = _qrUsedSinceShown; }
            if (rotate) RotateQr(raise: true);
        }

        /// <summary>Checks a pair proof against both the QR secret and the code. On success returns K.</summary>
        public PairCheck Verify(string clientId, string hostId, byte[] cNonce, byte[] hNonce, byte[] proof, out byte[] key)
        {
            key = Array.Empty<byte>();
            bool raise = false;
            PairCheck result;
            lock (_gate)
            {
                if (DateTime.UtcNow < _lockedUntil) return PairCheck.Locked;

                foreach (var secret in new[] { _qrSecret, Encoding.UTF8.GetBytes(_code) })
                {
                    var k = Protocol.DeriveKey(secret, clientId, hostId);
                    if (Protocol.MacEquals(Protocol.PairProof(k, cNonce, hNonce, clientId), proof))
                    {
                        key = k;
                        _failures = 0;
                        _qrUsedSinceShown = true;
                        RotateCodeLocked();
                        raise = true;
                        result = PairCheck.Ok;
                        goto done;
                    }
                }

                _failures++;
                result = PairCheck.BadCode;
                if (_failures >= MaxFailures)
                {
                    _failures = 0;
                    _lockedUntil = DateTime.UtcNow + LockDuration;
                    RotateCodeLocked();
                    raise = true;
                    Log.Warn("Pairing locked for 60 s after 5 failed attempts");
                }
            }
        done:
            if (raise) Changed?.Invoke();
            return result;
        }

        private void Tick()
        {
            bool qr, code, unlock;
            lock (_gate)
            {
                var now = DateTime.UtcNow;
                qr = now - _qrCreated >= QrLifetime;
                code = now - _codeCreated >= CodeLifetime;
                unlock = _lockedUntil != DateTime.MinValue && now >= _lockedUntil;
                if (unlock) _lockedUntil = DateTime.MinValue;
            }
            if (qr) RotateQr(raise: false);
            if (code) RotateCode(raise: false);
            if (qr || code || unlock) Changed?.Invoke();
        }

        private void RotateQr(bool raise)
        {
            lock (_gate)
            {
                _qrSecret = Protocol.RandomBytes(32);
                _qrCreated = DateTime.UtcNow;
                _qrUsedSinceShown = false;
            }
            if (raise) Changed?.Invoke();
        }

        private void RotateCode(bool raise)
        {
            lock (_gate) RotateCodeLocked();
            if (raise) Changed?.Invoke();
        }

        private void RotateCodeLocked()
        {
            _code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            _codeCreated = DateTime.UtcNow;
        }

        public void Dispose() => _timer.Dispose();
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
