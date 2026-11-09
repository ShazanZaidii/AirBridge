using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Threading;
using AirBridge.Net;

namespace AirBridge.Phone
{
    /// <summary>Is the user at this PC (§13.5)? Last keyboard / mouse input and whether the session is locked.</summary>
    internal static class PcPresence
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }

        [DllImport("user32.dll")]
        private static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

        private static bool _locked;
        private static bool _hooked;

        public static void Start()
        {
            if (_hooked) return;
            _hooked = true;
            try
            {
                Microsoft.Win32.SystemEvents.SessionSwitch += (_, e) =>
                {
                    if (e.Reason == Microsoft.Win32.SessionSwitchReason.SessionLock) _locked = true;
                    else if (e.Reason == Microsoft.Win32.SessionSwitchReason.SessionUnlock) _locked = false;
                };
            }
            catch (Exception ex) { Log.Warn($"Session lock events unavailable: {ex.Message}"); }
        }

        /// <summary>Seconds since the last input in this session; null if Windows won't say.</summary>
        public static double? IdleSeconds()
        {
            try
            {
                var li = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
                if (!GetLastInputInfo(ref li)) return null;
                uint now = unchecked((uint)Environment.TickCount);
                return unchecked(now - li.dwTime) / 1000.0;
            }
            catch { return null; }
        }

        public static bool Available() => !_locked;
    }

    /// <summary>
    /// Listens for the phones' proximity beacons (BLE advertisement with our service UUID and a rotating id in the scan
    /// response) and keeps a distance estimate per phone. Runs only in Smart mode while a phone shares its key. Best
    /// effort: any Bluetooth error just leaves proximity "unknown".
    /// </summary>
    internal static class BeaconWatcher
    {
        private sealed record PhoneKey(byte[] Key, Guid Uuid, int Period);
        private static readonly Dictionary<string, PhoneKey> Keys = new();
        private static readonly Dictionary<string, ProximityEstimator> Est = new();
        private static readonly Dictionary<ulong, string> Known = new();
        private static readonly object Gate = new();
        private static Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher? _watcher;

        public static void SetBeacon(string phone, byte[]? key, string uuid, int period)
        {
            lock (Gate)
            {
                if (key != null && Guid.TryParse(uuid, out var g)) Keys[phone] = new PhoneKey(key, g, period);
                else
                {
                    Keys.Remove(phone); Est.Remove(phone);
                    foreach (var k in Known.Where(kv => kv.Value == phone).Select(kv => kv.Key).ToList()) Known.Remove(k);
                }
            }
            Update();
        }

        public static Proximity Proximity(string phone)
        {
            lock (Gate) return Est.TryGetValue(phone, out var e) ? e.Tick(DateTimeOffset.Now) : Phone.Proximity.Unknown;
        }

        public static void Update()
        {
            bool want;
            lock (Gate) want = PhoneSettings.RingMode == RingMode.Smart && Keys.Count > 0;
            try
            {
                if (want && _watcher == null)
                {
                    var w = new Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher
                    {
                        ScanningMode = Windows.Devices.Bluetooth.Advertisement.BluetoothLEScanningMode.Active,
                    };
                    w.Received += OnReceived;
                    w.Start();
                    _watcher = w;
                }
                else if (!want && _watcher != null)
                {
                    _watcher.Received -= OnReceived;
                    _watcher.Stop();
                    _watcher = null;
                }
            }
            catch (Exception ex) { Log.Warn($"Bluetooth beacon watcher: {ex.Message}"); _watcher = null; }
        }

        private static void OnReceived(Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher sender,
                                       Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementReceivedEventArgs a)
        {
            try
            {
                var now = DateTimeOffset.Now;
                lock (Gate)
                {
                    if (Keys.Count == 0) return;
                    Known.TryGetValue(a.BluetoothAddress, out var phone);
                    foreach (var sec in a.Advertisement.DataSections)
                    {
                        if (sec.DataType != 0x21 || sec.Data.Length < 16 + BeaconCrypto.Length) continue; // service data, 128-bit UUID
                        var bytes = new byte[sec.Data.Length];
                        using (var r = Windows.Storage.Streams.DataReader.FromBuffer(sec.Data)) r.ReadBytes(bytes);
                        var uuid = new Guid(bytes.Take(16).Reverse().ToArray()); // little-endian on air
                        var id = bytes.Skip(16).ToArray();
                        foreach (var kv in Keys)
                            if (kv.Value.Uuid == uuid && BeaconCrypto.Matches(id, kv.Value.Key, now, kv.Value.Period))
                            {
                                phone = kv.Key;
                                Known[a.BluetoothAddress] = kv.Key;
                            }
                    }
                    if (phone == null || !Keys.ContainsKey(phone)) return;
                    if (!Est.TryGetValue(phone, out var est)) Est[phone] = est = new ProximityEstimator();
                    est.Add(a.RawSignalStrengthInDBm, a.TransmitPowerLevelInDBm, now);
                    if (Known.Count > 64) foreach (var k in Known.Where(kv => kv.Value != phone).Select(kv => kv.Key).ToList()) Known.Remove(k);
                }
            }
            catch (Exception ex) { Log.Warn($"Beacon packet: {ex.Message}"); }
        }
    }

    /// <summary>A soft, self-made mallet arpeggio (synthesised, no audio files), looped while a call rings here.</summary>
    internal static class Ringtone
    {
        private static System.Media.SoundPlayer? _player;
        private static bool _playing;

        public static void Start()
        {
            if (_playing) return;
            try
            {
                _player ??= new System.Media.SoundPlayer(new MemoryStream(Wav()));
                _player.PlayLooping();
                _playing = true;
            }
            catch (Exception ex) { Log.Warn($"Ringtone: {ex.Message}"); }
        }

        public static void Stop()
        {
            if (!_playing) return;
            _playing = false;
            try { _player?.Stop(); } catch { }
        }

        private static byte[] Wav()
        {
            const int rate = 44100;
            int n = (int)(rate * 3.4);
            var buf = new double[n];
            (double f, double t, double a)[] notes =
            {
                (659.25, 0.00, 0.55), (830.61, 0.17, 0.50), (987.77, 0.34, 0.50), (1318.51, 0.51, 0.45),
                (659.25, 1.10, 0.50), (830.61, 1.27, 0.46), (987.77, 1.44, 0.46), (1318.51, 1.61, 0.42),
            };
            foreach (var (f, t0, a) in notes)
            {
                int start = (int)(t0 * rate), dur = (int)(1.4 * rate);
                for (int i = 0; i < dur && start + i < n; i++)
                {
                    double t = (double)i / rate, attack = Math.Min(1, t / 0.004);
                    double v = Math.Sin(2 * Math.PI * f * t) * Math.Exp(-t / 0.42)
                             + 0.35 * Math.Sin(2 * Math.PI * f * 3.93 * t) * Math.Exp(-t / 0.06)
                             + 0.12 * Math.Sin(2 * Math.PI * f * 2 * t) * Math.Exp(-t / 0.2);
                    buf[start + i] += a * attack * v;
                }
            }
            foreach (var (delay, gain) in new[] { (0.085, 0.18), (0.19, 0.08) })
            {
                int d = (int)(delay * rate);
                for (int i = n - 1; i >= d; i--) buf[i] += gain * buf[i - d];
            }
            double peak = buf.Max(Math.Abs), scale = 0.7 / Math.Max(peak, 0.0001);
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);
            w.Write("RIFF"u8.ToArray()); w.Write(36 + n * 2); w.Write("WAVE"u8.ToArray());
            w.Write("fmt "u8.ToArray()); w.Write(16); w.Write((short)1); w.Write((short)1); w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write((short)16);
            w.Write("data"u8.ToArray()); w.Write(n * 2);
            foreach (var v in buf) w.Write((short)(Math.Clamp(Math.Tanh(v * scale * 1.2) / Math.Tanh(1.2), -1, 1) * 32000));
            w.Flush();
            return ms.ToArray();
        }
    }

    /// <summary>
    /// Where a ringing call rings on this PC (§13.5): decides with <see cref="RingSession"/> from this PC's input, the
    /// phone's `call_presence` and the beacon distance; plays the ringtone and asks the phone to keep quiet when the
    /// user is here. UI thread.
    /// </summary>
    internal static class CallRinger
    {
        private sealed class State { public string CallId = ""; public RingSession? Session; public bool SilenceSent; public bool Dismissed; }
        private static readonly Dictionary<string, State> Ringing = new();
        private static readonly Dictionary<string, PhonePresenceReport> Presence = new();
        private static DispatcherTimer? _tick;

        /// <summary>The phone's call changed (any state; only a ringing call is decided).</summary>
        public static void OnCall(PhoneCall c)
        {
            PcPresence.Start();
            if (c.State != PhoneCallState.Ringing) { Ringing.Remove(c.PhoneId); Refresh(); return; }
            if (Ringing.TryGetValue(c.PhoneId, out var s) && s.CallId == c.Id) return;
            s = new State { CallId = c.Id, Session = new RingSession(Inputs(c.PhoneId)) };
            Ringing[c.PhoneId] = s;
            Apply(c.PhoneId, s);
            Refresh();
        }

        /// <summary>The user closed the call window while it rang: no ringing here for that call.</summary>
        public static void Dismiss(string phone)
        {
            if (Ringing.TryGetValue(phone, out var s)) s.Dismissed = true;
            Refresh();
        }

        public static void OnPresence(string phone, PhonePresenceReport r)
        {
            Presence[phone] = r;
            if (Ringing.TryGetValue(phone, out var s) && s.Session != null) { s.Session.Update(Inputs(phone)); Apply(phone, s); Refresh(); }
        }

        public static void PhoneGone(string phone)
        {
            Presence.Remove(phone);
            Ringing.Remove(phone);
            BeaconWatcher.SetBeacon(phone, null, "", 0);
            Refresh();
        }

        public static bool RingsHere(string phone) =>
            Ringing.TryGetValue(phone, out var s) && !s.Dismissed && s.Session?.Decision.ComputerRings == true;

        private static RingInputs Inputs(string phone) =>
            new(PhoneSettings.RingMode, PcPresence.IdleSeconds(), PcPresence.Available(),
                Presence.TryGetValue(phone, out var p) ? p : null, BeaconWatcher.Proximity(phone), DateTimeOffset.Now);

        private static void Apply(string phone, State s)
        {
            if (s.Session is not RingSession rs) return;
            if (rs.Decision.SilencePhone && !s.SilenceSent && PeerBus.Peers().Any(p => p.Id == phone && p.Caps.Contains(PhoneCaps.CallRing)))
            {
                s.SilenceSent = true;
                PeerBus.Send(phone, PhoneWire.Ring(s.CallId, true));
            }
        }

        private static void Refresh()
        {
            if (Ringing.Keys.Any(RingsHere)) Ringtone.Start(); else Ringtone.Stop();
            if (Ringing.Count > 0 && _tick == null)
            {
                _tick = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
                _tick.Tick += (_, _) =>
                {
                    foreach (var (phone, s) in Ringing.ToList())
                        if (!s.Dismissed && s.Session != null) { s.Session.Update(Inputs(phone)); Apply(phone, s); }
                    if (Ringing.Keys.Any(RingsHere)) Ringtone.Start(); else Ringtone.Stop();
                };
                _tick.Start();
            }
            else if (Ringing.Count == 0 && _tick != null) { _tick.Stop(); _tick = null; }
        }
    }
}
