using System;
using System.Text.Json.Nodes;

namespace AirBridge.Net
{
    /// <summary>A battery level as sent in <c>dev_battery</c> (§14).</summary>
    public readonly record struct BatteryLevel(int Pct, bool Charging);

    /// <summary>The <c>dev_battery</c> message (§14): a device tells peers that advertised <c>battery_sink</c> how full its
    /// battery is. Sent when the peer becomes ready and whenever the percentage or the charging state changes.</summary>
    public static class DeviceBatteryWire
    {
        public const string Type = "dev_battery";
        public const string Cap = "battery_sink";

        public static JsonObject Message(BatteryLevel l) => new() { ["t"] = Type, ["pct"] = l.Pct, ["charging"] = l.Charging };

        /// <summary>The level in <paramref name="o"/>, or null if it is not a usable one (missing or outside 0..100).</summary>
        public static BatteryLevel? Parse(JsonObject o)
        {
            if (o["pct"] is not JsonValue pv || !pv.TryGetValue(out int pct) || pct < 0 || pct > 100) return null;
            bool charging = o["charging"] is JsonValue cv && cv.TryGetValue(out bool c) && c;
            return new BatteryLevel(pct, charging);
        }
    }

    /// <summary>Turns Windows' GetSystemPowerStatus values into a <see cref="BatteryLevel"/> (null for a PC without a battery).</summary>
    public static class PcBatteryStatus
    {
        /// <param name="flag">BatteryFlag: 8 = charging, 128 = no system battery, 255 = unknown.</param>
        /// <param name="percent">BatteryLifePercent: 0..100, or 255 = unknown.</param>
        public static BatteryLevel? From(byte flag, byte percent)
        {
            if ((flag & 128) != 0) return null;       // a desktop, or a battery Windows can't see
            if (percent > 100) return null;           // unknown
            return new BatteryLevel(percent, (flag & 8) != 0);
        }
    }
}
