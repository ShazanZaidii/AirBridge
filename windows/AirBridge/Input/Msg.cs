using System.Text.Json;
using System.Text.Json.Nodes;

namespace AirBridge.Input
{
    /// <summary>Tolerant readers for incoming control messages and helpers for outgoing ones.</summary>
    internal static class Msg
    {
        public static string? Str(JsonObject m, string key)
        {
            if (m.TryGetPropertyValue(key, out var n) && n is JsonValue v)
            {
                if (v.TryGetValue(out string? s)) return s;
                if (v.TryGetValue(out JsonElement e) && e.ValueKind == JsonValueKind.String) return e.GetString();
            }
            return null;
        }

        public static double? Num(JsonObject m, string key)
        {
            if (!m.TryGetPropertyValue(key, out var n) || n is not JsonValue v) return null;
            if (v.TryGetValue(out JsonElement e))
                return e.ValueKind == JsonValueKind.Number && e.TryGetDouble(out double de) ? de : null;
            if (v.TryGetValue(out double d)) return d;
            if (v.TryGetValue(out int i)) return i;
            if (v.TryGetValue(out long l)) return l;
            if (v.TryGetValue(out float f)) return f;
            if (v.TryGetValue(out uint u)) return u;
            if (v.TryGetValue(out short sh)) return sh;
            if (v.TryGetValue(out byte b)) return b;
            if (v.TryGetValue(out decimal dc)) return (double)dc;
            return null;
        }

        public static bool? Bool(JsonObject m, string key)
        {
            if (!m.TryGetPropertyValue(key, out var n) || n is not JsonValue v) return null;
            if (v.TryGetValue(out bool b)) return b;
            if (v.TryGetValue(out JsonElement e))
            {
                if (e.ValueKind == JsonValueKind.True) return true;
                if (e.ValueKind == JsonValueKind.False) return false;
            }
            return null;
        }

        /// <summary>Rounds to 3 decimals so the JSON writer never emits exponents or "-0" (§9).</summary>
        public static double Plain(double d)
        {
            if (double.IsNaN(d) || double.IsInfinity(d)) return 0;
            double r = Math.Round(d, 3, MidpointRounding.AwayFromZero);
            return r == 0 ? 0 : r;
        }

        public static JsonObject Of(string type) => new() { ["t"] = type };
    }
}
