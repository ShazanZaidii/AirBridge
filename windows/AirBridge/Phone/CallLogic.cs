using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace AirBridge.Phone
{
    // Where a phone call rings and who is calling (protocol.md §13.5). Pure (no WPF, no WinRT, no clocks of its own)
    // so AirBridge.PhoneTests checks it on any OS. Mirrors AirBridge/Calls/CallLogic.swift on the Mac.

    /// <summary>"Ring on this PC".</summary>
    public enum RingMode { Smart, Always, Never }

    public enum Proximity { Unknown, Near, Far }

    /// <summary>What a phone last said about its user (`call_presence`).</summary>
    public sealed record PhonePresenceReport(bool ScreenOn, bool Locked, long? IdleMs, bool TouchKnown, DateTimeOffset ReceivedAt)
    {
        public static PhonePresenceReport? Parse(JsonObject m, DateTimeOffset at)
        {
            if (Str(m, "t") != "call_presence" || Bool(m, "screen") is not bool screen || Bool(m, "locked") is not bool locked) return null;
            long? idle = null;
            if (m["idle_ms"] is JsonValue v && v.GetValueKind() == JsonValueKind.Number && v.TryGetValue(out double d) && !double.IsNaN(d) && d >= 0)
                idle = (long)Math.Min(d, 86_400_000);
            return new PhonePresenceReport(screen, locked, idle, Bool(m, "touch") ?? false, at);
        }

        /// <summary>Seconds since the user last touched the phone, as of <paramref name="now"/>.</summary>
        public double? Idle(DateTimeOffset now) =>
            IdleMs is long ms ? ms / 1000.0 + Math.Max(0, (now - ReceivedAt).TotalSeconds) : null;

        public bool InUse(DateTimeOffset now, double window = RingPolicy.ActiveWindow)
        {
            if (!ScreenOn || Locked) return false;
            return !TouchKnown || (Idle(now) ?? double.PositiveInfinity) < window;
        }

        internal static string? Str(JsonObject o, string k) => o[k] is JsonValue v && v.TryGetValue(out string? s) ? s : null;
        internal static bool? Bool(JsonObject o, string k) =>
            o[k] is JsonValue v && v.GetValueKind() is JsonValueKind.True or JsonValueKind.False ? v.GetValue<bool>() : null;
    }

    public sealed record RingInputs(RingMode Mode, double? PcIdle, bool PcAvailable, PhonePresenceReport? Phone, Proximity Proximity, DateTimeOffset Now);

    public enum RingReason { ModeNever, ModeAlways, AtComputer, UsingPhone, Nearby, Away }

    public sealed record RingDecision(bool ComputerRings, bool SilencePhone, RingReason Reason);

    /// <summary>
    /// At the PC → only the PC rings; not at the PC but the phone within about a metre → the PC rings too; phone
    /// farther → the call shows silently; on the phone → silent here. The call can be answered anywhere.
    /// </summary>
    public static class RingPolicy
    {
        public const double ActiveWindow = 10;

        public static bool AtComputer(RingInputs i) => i.PcAvailable && (i.PcIdle ?? double.PositiveInfinity) < ActiveWindow;
        public static bool UsingPhone(RingInputs i) => i.Phone?.InUse(i.Now) ?? false;

        public static RingDecision Decide(RingInputs i) =>
            Decide(i.Mode, AtComputer(i), i.PcIdle, UsingPhone(i), i.Phone?.Idle(i.Now), i.Proximity == Proximity.Near);

        public static RingDecision Decide(RingMode mode, bool atPc, double? pcIdle, bool usingPhone, double? phoneIdle, bool near)
        {
            if (mode == RingMode.Never) return new RingDecision(false, false, RingReason.ModeNever);
            if (mode == RingMode.Always) return new RingDecision(true, false, RingReason.ModeAlways);
            bool pc = atPc, phone = usingPhone;
            if (pc && phone)
            {
                if (phoneIdle is double p && p < (pcIdle ?? double.PositiveInfinity)) pc = false; else phone = false;
            }
            if (pc) return new RingDecision(true, true, RingReason.AtComputer);
            if (phone) return new RingDecision(false, false, RingReason.UsingPhone);
            if (near) return new RingDecision(true, false, RingReason.Nearby);
            return new RingDecision(false, false, RingReason.Away);
        }
    }

    /// <summary>One ringing call, re-checked while it rings ("at the PC" and "nearby" latch on; "using the phone" can take over).</summary>
    public sealed class RingSession
    {
        private bool _sawPc, _sawNear;
        private DateTimeOffset? _lastPcInput;
        public RingDecision Decision { get; private set; }

        public RingSession(RingInputs i)
        {
            _sawPc = RingPolicy.AtComputer(i);
            _sawNear = i.Proximity == Proximity.Near;
            _lastPcInput = i.PcIdle is double s ? i.Now.AddSeconds(-s) : null;
            Decision = RingPolicy.Decide(i);
        }

        public RingDecision Update(RingInputs i)
        {
            if (RingPolicy.AtComputer(i)) { _sawPc = true; _lastPcInput = i.PcIdle is double s ? i.Now.AddSeconds(-s) : null; }
            if (i.Proximity == Proximity.Near) _sawNear = true;
            double? pcIdle = _lastPcInput is DateTimeOffset t ? (i.Now - t).TotalSeconds : i.PcIdle;
            var d = RingPolicy.Decide(i.Mode, _sawPc, pcIdle, RingPolicy.UsingPhone(i), i.Phone?.Idle(i.Now), _sawNear);
            if (Decision.SilencePhone) d = d with { SilencePhone = true };
            Decision = d;
            return d;
        }
    }

    /// <summary>Distance from the beacon's RSSI: median of the last seconds, smoothed, near / far with hysteresis.</summary>
    public sealed class ProximityEstimator
    {
        public double NearBelow { get; init; } = 1.0;
        public double FarAbove { get; init; } = 1.6;
        public double StaleAfter { get; init; } = 12;
        public double LossAt1m { get; init; } = -59;
        public double DefaultTx { get; init; } = -7;
        public double Exponent { get; init; } = 2.2;
        public double Window { get; init; } = 6;
        public double Alpha { get; init; } = 0.35;

        private readonly List<(DateTimeOffset at, double rssi)> _samples = new();
        private double? _smoothed, _tx;
        public Proximity State { get; private set; } = Proximity.Unknown;
        public DateTimeOffset? LastAt { get; private set; }

        public static double Distance(double rssi, double measuredAt1m, double exponent) =>
            Math.Pow(10, (measuredAt1m - rssi) / (10 * exponent));

        public double? DistanceM => _smoothed is double s ? Distance(s, (_tx ?? DefaultTx) + LossAt1m, Exponent) : null;

        public void Add(int rssi, int? txPower, DateTimeOffset at)
        {
            if (rssi >= 0 || rssi <= -110) return;
            if (txPower is int t && t > -40 && t < 21) _tx = t;
            LastAt = at;
            _samples.Add((at, rssi));
            _samples.RemoveAll(x => (at - x.at).TotalSeconds > Window);
            if (_samples.Count < 2) return;
            var sorted = _samples.Select(x => x.rssi).OrderBy(x => x).ToList();
            double median = sorted.Count % 2 == 1 ? sorted[sorted.Count / 2] : (sorted[sorted.Count / 2 - 1] + sorted[sorted.Count / 2]) / 2;
            _smoothed = _smoothed is double s ? s + Alpha * (median - s) : median;
            if (DistanceM is not double d) return;
            State = State == Proximity.Near ? (d > FarAbove ? Proximity.Far : Proximity.Near) : (d < NearBelow ? Proximity.Near : Proximity.Far);
        }

        public Proximity Tick(DateTimeOffset now)
        {
            if (LastAt is DateTimeOffset l && (now - l).TotalSeconds <= StaleAfter) return State;
            _samples.Clear(); _smoothed = null; State = Proximity.Unknown;
            return State;
        }
    }

    /// <summary>The phone's rotating beacon id: HMAC-SHA256(key, "airbridge-beacon" ‖ epoch as 8 bytes big-endian)[0..8].</summary>
    public static class BeaconCrypto
    {
        public const string ServiceUuid = "754338F5-9682-4D7A-9A60-84968B058DDE";
        public const int Length = 8;

        public static long Epoch(DateTimeOffset at, int period) => (long)Math.Floor(at.ToUnixTimeMilliseconds() / 1000.0 / Math.Max(60, period));

        public static byte[] Id(byte[] key, long epoch)
        {
            var msg = new byte[16 + 8];
            Encoding.ASCII.GetBytes("airbridge-beacon").CopyTo(msg, 0);
            for (int i = 0; i < 8; i++) msg[16 + i] = (byte)(epoch >> (56 - 8 * i));
            using var h = new HMACSHA256(key);
            return h.ComputeHash(msg).Take(Length).ToArray();
        }

        public static bool Matches(byte[] id, byte[] key, DateTimeOffset at, int period)
        {
            if (id.Length < Length) return false;
            var probe = id.Take(Length).ToArray();
            long e = Epoch(at, period);
            for (long x = e - 1; x <= e + 1; x++) if (Id(key, x).SequenceEqual(probe)) return true;
            return false;
        }
    }

    public static class CallerText
    {
        /// <summary>"Asha Kapoor" → "AK"; null for numbers and missing names.</summary>
        public static string? Initials(string? name)
        {
            string n = (name ?? "").Trim();
            if (n.Length == 0 || n.All(c => "+()-. 0123456789".Contains(c))) return null;
            var words = n.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).Where(w => char.IsLetter(w, 0)).ToList();
            if (words.Count == 0) return null;
            string first = char.ConvertFromUtf32(char.ConvertToUtf32(words[0], 0));
            if (words.Count >= 2 && words[0][0] < 0x250 && words[^1][0] < 0x250)
                return (words[0].Substring(0, 1) + words[^1].Substring(0, 1)).ToUpperInvariant();
            return first.ToUpperInvariant();
        }

        public static string Title(string? name, string? number) =>
            !string.IsNullOrWhiteSpace(name) ? name!.Trim() : !string.IsNullOrWhiteSpace(number) ? number!.Trim() : "Unknown Caller";

        public static string Subtitle(string? label, string phoneName) =>
            string.IsNullOrWhiteSpace(label) ? phoneName : $"{label!.Trim()} · {phoneName}";

        public static string Duration(TimeSpan d) =>
            d.TotalHours >= 1 ? $"{(int)d.TotalHours}:{d.Minutes:00}:{d.Seconds:00}" : $"{(int)d.TotalMinutes}:{d.Seconds:00}";
    }
}
