using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;

namespace AirBridge.Net.Link
{
    /// <summary>
    /// The <c>link_*</c> messages of PROTOCOL §10.1 (builders/parsers) plus the §6.4 log-line format and small
    /// diagnostics parsers. Pure logic (System.Text.Json only) so AirBridge.LinkTests can run it on macOS.
    /// </summary>
    public static class LinkWire
    {
        public const string TypeWfdLegacy = "wfd_legacy";
        public const string TypeWfdGo = "wfd_go";
        public const string CapHost = "wfd_host";
        public const string CapJoin = "wfd_join";
        public const int DefaultTtlSeconds = 120;

        /// <summary>link_fail reasons (§10.1). Anything else received is passed through as text.</summary>
        public static class Reason
        {
            public const string NoGoSupport = "no_go_support", HotspotActive = "hotspot_active", RadioOff = "radio_off",
                JoinFailed = "join_failed", NoIp = "no_ip", ApprovalDenied = "approval_denied", Vpn = "vpn",
                ApiLevel = "api_level", Timeout = "timeout", Busy = "busy", User = "user";
        }

        /// <summary>link_done reasons (§10.1).</summary>
        public static class Done
        {
            public const string Idle = "idle", Ttl = "ttl", User = "user", Lost = "lost";
        }

        public static bool IsLinkType(string t) => t.StartsWith("link_", StringComparison.Ordinal);

        public static JsonObject Offer(int lid, WfdCreds creds, IReadOnlyList<string> addrs, int port, string band = "unknown",
                                       int ttl = DefaultTtlSeconds)
        {
            var a = new JsonArray();
            foreach (var s in addrs) a.Add(s);
            return new JsonObject
            {
                ["t"] = "link_offer", ["lid"] = lid, ["type"] = TypeWfdLegacy, ["cred"] = "derived", ["gen"] = creds.Gen,
                ["ssid"] = creds.Ssid, ["sec"] = "wpa2", ["band"] = band, ["addr"] = a, ["port"] = port, ["ttl"] = ttl,
            };
        }

        public static JsonObject Fail(int lid, string reason) => new() { ["t"] = "link_fail", ["lid"] = lid, ["reason"] = reason };

        public static JsonObject DoneMsg(int lid, string reason) => new() { ["t"] = "link_done", ["lid"] = lid, ["reason"] = reason };

        public static int? Lid(JsonObject o)
        {
            if (o["lid"] is not JsonValue v) return null;
            try
            {
                if (v.TryGetValue(out int i)) return i;
                if (v.TryGetValue(out long l) && l is >= int.MinValue and <= int.MaxValue) return (int)l;
                if (v.TryGetValue(out double d) && d == Math.Floor(d) && d is >= int.MinValue and <= int.MaxValue) return (int)d;
            }
            catch { }
            return null;
        }

        public static string? Str(JsonObject o, string key)
        {
            try { return o[key] is JsonValue v && v.TryGetValue(out string? s) ? s : null; }
            catch { return null; }
        }

        public static long? Long(JsonObject o, string key)
        {
            if (o[key] is not JsonValue v) return null;
            try
            {
                if (v.TryGetValue(out long l)) return l;
                if (v.TryGetValue(out double d) && !double.IsNaN(d) && !double.IsInfinity(d)) return (long)d;
            }
            catch { }
            return null;
        }

        /// <summary>link_request.types (absent = ["wfd_legacy"], the only thing a Windows host can offer in A0).</summary>
        public static IReadOnlyList<string> Types(JsonObject o)
        {
            var list = new List<string>();
            if (o["types"] is JsonArray arr)
                foreach (var n in arr)
                    if (n is JsonValue v && v.TryGetValue(out string? s) && s != null) list.Add(s);
            if (o["types"] == null) list.Add(TypeWfdLegacy);
            return list;
        }

        // ------------------------------------------------------------------ §6.4 log lines

        /// <summary>
        /// One line per transition, research doc §6.4:
        /// <c>AirBridge.Link lid=7 peer=&lt;id8&gt; type=wfd_legacy ev=go_started ms=830 ip=192.168.137.1 adapter="Intel AX211" drv=23.60.1</c>.
        /// Null values are skipped; values with spaces or quotes are quoted.
        /// </summary>
        public static string FormatEvent(string lid, string ev, params (string Key, object? Value)[] fields)
        {
            var sb = new StringBuilder("AirBridge.Link lid=").Append(lid);
            // peer/type (if given) go before ev, as in the reference lines.
            foreach (var (k, v) in fields) if (k is "peer" or "type") Append(sb, k, v);
            sb.Append(" ev=").Append(ev);
            foreach (var (k, v) in fields) if (k is not ("peer" or "type")) Append(sb, k, v);
            return sb.ToString();
        }

        private static void Append(StringBuilder sb, string k, object? v)
        {
            if (v == null) return;
            string s = v switch
            {
                bool b => b ? "true" : "false",
                double d => d.ToString("0.0", CultureInfo.InvariantCulture),
                float f => f.ToString("0.0", CultureInfo.InvariantCulture),
                IFormattable fm => fm.ToString(null, CultureInfo.InvariantCulture),
                _ => v.ToString() ?? "",
            };
            if (s.Length == 0 || s.IndexOfAny(new[] { ' ', '"', '=' }) >= 0) s = "\"" + s.Replace("\"", "'") + "\"";
            sb.Append(' ').Append(k).Append('=').Append(s);
        }

        public static string Id8(string? id) => string.IsNullOrEmpty(id) ? "?" : id.Length <= 8 ? id : id[..8];

        // ------------------------------------------------------------------ diagnostics

        /// <summary>Wi-Fi channel → centre frequency in MHz (2.4 / 5 GHz; 6 GHz needs the band, so 0).</summary>
        public static int ChannelToMhz(int channel, string? band = null)
        {
            if (band != null && band.Contains('6') && !band.Contains("2.4")) return channel is >= 1 and <= 233 ? 5950 + 5 * channel : 0;
            if (channel == 14) return 2484;
            if (channel is >= 1 and <= 13) return 2407 + 5 * channel;
            if (channel is >= 32 and <= 177) return 5000 + 5 * channel;
            return 0;
        }

        public static string BandOf(int mhz) => mhz switch
        {
            >= 2400 and < 2500 => "2.4",
            >= 4900 and < 5900 => "5",
            >= 5925 and < 7200 => "6",
            _ => "unknown",
        };

        /// <summary>
        /// Parses English <c>netsh wlan show interfaces</c> output into one dictionary per interface block
        /// (keys lower-cased: "name", "description", "state", "ssid", "channel", "band", "radio type", "receive rate (mbps)"…).
        /// Diagnostic only: the labels are localised on non-English Windows, in which case little is returned.
        /// </summary>
        public static List<Dictionary<string, string>> ParseNetshInterfaces(string output)
        {
            var result = new List<Dictionary<string, string>>();
            Dictionary<string, string>? cur = null;
            foreach (var raw in output.Split('\n'))
            {
                string line = raw.TrimEnd('\r');
                int c = line.IndexOf(" : ", StringComparison.Ordinal);
                if (c < 0) continue;
                string key = line[..c].Trim().ToLowerInvariant();
                string val = line[(c + 3)..].Trim();
                if (key.Length == 0) continue;
                if (key == "name") { cur = new Dictionary<string, string>(); result.Add(cur); }
                if (cur == null) { cur = new Dictionary<string, string>(); result.Add(cur); }
                cur.TryAdd(key, val);
            }
            return result;
        }
    }

    /// <summary>Link log lines (§6.4) with a ring buffer for the Debug window. <see cref="Sink"/> forwards to the app log.</summary>
    public static class LinkLog
    {
        private static readonly object Gate = new();
        private static readonly Queue<string> Recent = new();
        private const int Keep = 400;

        /// <summary>Set by the app to the file logger (AirBridge.Net.Log.Info). Null in the tests.</summary>
        public static Action<string>? Sink;
        /// <summary>Raised (any thread) for every line.</summary>
        public static event Action<string>? Line;

        public static void Ev(string lid, string ev, params (string Key, object? Value)[] fields) =>
            Write(LinkWire.FormatEvent(lid, ev, fields));

        public static void Ev(int lid, string ev, params (string Key, object? Value)[] fields) =>
            Ev(lid.ToString(CultureInfo.InvariantCulture), ev, fields);

        public static void Write(string line)
        {
            lock (Gate)
            {
                Recent.Enqueue($"{DateTime.Now:HH:mm:ss.fff} {line}");
                while (Recent.Count > Keep) Recent.Dequeue();
            }
            try { Sink?.Invoke(line); } catch { }
            try { Line?.Invoke(line); } catch { }
        }

        public static string[] Snapshot()
        {
            lock (Gate) return Recent.ToArray();
        }
    }
}
