using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using System.Threading;

namespace AirBridge.Net
{
    /// <summary>The batteries of connected devices (phones), by peer id, for the device list. Cleared when a peer disconnects.</summary>
    public static class DeviceBatteryStore
    {
        private static readonly ConcurrentDictionary<string, BatteryLevel> Levels = new();
        public static event Action? Changed;

        public static BatteryLevel? Get(string peerId) => Levels.TryGetValue(peerId, out var l) ? l : null;

        public static void Set(string peerId, BatteryLevel l)
        {
            if (Levels.TryGetValue(peerId, out var old) && old == l) return;
            Levels[peerId] = l;
            Changed?.Invoke();
        }

        public static void Clear(string peerId)
        {
            if (Levels.TryRemove(peerId, out _)) Changed?.Invoke();
        }
    }

    /// <summary>
    /// Tells computers that list batteries (the Mac puts every connected device in its Batteries widget) how full this PC's
    /// battery is. Nothing is sent from a desktop PC. Checked every 30 s and sent only when the percentage or the charging
    /// state changed, plus once when a peer connects.
    /// </summary>
    public sealed class PcBatterySharer : IDisposable
    {
        private readonly Func<IReadOnlyList<PeerInfo>> _peers;
        private readonly Func<string, JsonObject, bool> _send;
        private readonly Timer _timer;
        private BatteryLevel? _last;

        public PcBatterySharer(Func<IReadOnlyList<PeerInfo>> peers, Func<string, JsonObject, bool> send)
        {
            _peers = peers; _send = send;
            _timer = new Timer(_ => Tick(), null, TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(30));
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct SystemPowerStatus
        {
            public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
            public uint BatteryLifeTime, BatteryFullLifeTime;
        }

        [DllImport("kernel32.dll")]
        private static extern bool GetSystemPowerStatus(out SystemPowerStatus status);

        public static BatteryLevel? Read()
        {
            try { return GetSystemPowerStatus(out var s) ? PcBatteryStatus.From(s.BatteryFlag, s.BatteryLifePercent) : null; }
            catch { return null; }
        }

        private void Tick()
        {
            var now = Read();
            if (now == null || now == _last) return;
            _last = now;
            foreach (var p in _peers().Where(p => p.Caps.Contains(DeviceBatteryWire.Cap)))
                _send(p.Id, DeviceBatteryWire.Message(now.Value));
        }

        /// <summary>A peer just became ready: it gets the current level at once, if it lists batteries.</summary>
        public void SendTo(PeerInfo peer)
        {
            if (!peer.Caps.Contains(DeviceBatteryWire.Cap)) return;
            var now = Read();
            if (now == null) return;
            _last = now;
            _send(peer.Id, DeviceBatteryWire.Message(now.Value));
        }

        public void Dispose() => _timer.Dispose();
    }
}
