using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace AirBridge.Net
{
    /// <summary>
    /// This PC's screen time (docs/protocol.md 15): time per app, hour and day, and the <c>screen_time</c> message built from it.
    /// Pure (no Windows APIs, no clock of its own), so AirBridge.ScreenTimeTests checks it on any OS.
    /// </summary>
    public sealed class ScreenTimeAccumulator
    {
        public sealed class App { public string Name { get; set; } = ""; public double Seconds { get; set; } }
        public sealed class Day
        {
            public double Seconds { get; set; }
            public double[] Hours { get; set; } = new double[24];
            public Dictionary<string, App> Apps { get; set; } = new();
        }

        /// <summary>By local date, "yyyy-MM-dd".</summary>
        public Dictionary<string, Day> Days { get; set; } = new();

        /// <summary>The longest single stretch counted at once (a missed lock never counts a whole night).</summary>
        public static readonly TimeSpan MaxSegment = TimeSpan.FromHours(4);

        public static string DayKey(DateTime local) => local.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture);

        /// <summary>Adds the local time from <paramref name="start"/> to <paramref name="end"/> in app <paramref name="id"/>, split at hour boundaries.</summary>
        public void Add(string id, string name, DateTime start, DateTime end)
        {
            if (string.IsNullOrEmpty(id) || end <= start) return;
            var stop = end - start > MaxSegment ? start + MaxSegment : end;
            var cur = start;
            while (cur < stop)
            {
                var hourEnd = new DateTime(cur.Year, cur.Month, cur.Day, cur.Hour, 0, 0, cur.Kind).AddHours(1);
                var pieceEnd = hourEnd < stop ? hourEnd : stop;
                double secs = (pieceEnd - cur).TotalSeconds;
                var key = DayKey(cur);
                if (!Days.TryGetValue(key, out var day)) Days[key] = day = new Day();
                day.Seconds += secs;
                day.Hours[cur.Hour] += secs;
                if (!day.Apps.TryGetValue(id, out var app)) day.Apps[id] = app = new App { Name = name };
                app.Seconds += secs;
                if (!string.IsNullOrEmpty(name)) app.Name = name;
                cur = pieceEnd;
            }
        }

        /// <summary>Keeps only the newest <paramref name="keep"/> days.</summary>
        public void Prune(int keep)
        {
            foreach (var k in Days.Keys.OrderBy(k => k, StringComparer.Ordinal).SkipLast(keep).ToList()) Days.Remove(k);
        }

        /// <summary>The <c>screen_time</c> message: the newest days, each up to today with its hours and top apps (for the Mac's
        /// Screen Time page, which steps back through days and weeks).</summary>
        public JsonObject Message(DateTime nowLocal, int maxDays = 14, int maxApps = 20)
        {
            string today = DayKey(nowLocal);
            var arr = new JsonArray();
            foreach (var key in Days.Keys.OrderBy(k => k, StringComparer.Ordinal).TakeLast(maxDays))
            {
                var d = Days[key];
                var o = new JsonObject { ["day"] = key, ["s"] = (long)Math.Round(d.Seconds) };
                if (string.CompareOrdinal(key, today) <= 0)
                {
                    var hours = new JsonArray();
                    foreach (var h in d.Hours) hours.Add((long)Math.Round(h));
                    o["hours"] = hours;
                    var apps = new JsonArray();
                    foreach (var (aid, a) in TopApps(d, maxApps)) apps.Add(new JsonObject { ["id"] = aid, ["name"] = a.Name, ["s"] = (long)Math.Round(a.Seconds) });
                    o["apps"] = apps;
                }
                arr.Add(o);
            }
            return new JsonObject { ["t"] = "screen_time", ["days"] = arr };
        }

        /// <summary>Today's most used apps (whose icons are worth sending).</summary>
        public List<string> TopAppIds(DateTime nowLocal, int n) =>
            Days.TryGetValue(DayKey(nowLocal), out var d) ? TopApps(d, n).Select(x => x.Id).ToList() : new List<string>();

        private static IEnumerable<(string Id, App App)> TopApps(Day d, int n) =>
            d.Apps.Where(x => Math.Round(x.Value.Seconds) > 0)
                  .OrderByDescending(x => Math.Round(x.Value.Seconds)).ThenBy(x => x.Key, StringComparer.Ordinal)
                  .Take(n).Select(x => (x.Key, x.Value));

        public string ToJson() => JsonSerializer.Serialize(this);

        public static ScreenTimeAccumulator FromJson(string? json)
        {
            if (string.IsNullOrWhiteSpace(json)) return new ScreenTimeAccumulator();
            try
            {
                var a = JsonSerializer.Deserialize<ScreenTimeAccumulator>(json) ?? new ScreenTimeAccumulator();
                foreach (var d in a.Days.Values) if (d.Hours == null || d.Hours.Length != 24) d.Hours = new double[24];
                return a;
            }
            catch { return new ScreenTimeAccumulator(); }
        }
    }

    public static class ScreenTimeWire
    {
        /// <summary>The cap of a peer that shows screen time (the Mac's AirBridge widget).</summary>
        public const string Cap = "screentime_sink";
        public const int MaxIconBytes = 96 * 1024;

        public static JsonObject? IconMessage(string id, byte[] png) =>
            png.Length == 0 || png.Length > MaxIconBytes ? null : new JsonObject { ["t"] = "screen_time_icon", ["id"] = id, ["png"] = Convert.ToBase64String(png) };

        /// <summary>The app id for an executable path: the file name in lower case ("chrome.exe").</summary>
        public static string AppId(string exePathOrName) => exePathOrName.Trim().Split('\\', '/').Last().ToLowerInvariant();

        /// <summary>Windows parts that are not "using an app": the lock screen, sign-in, the screen saver.</summary>
        public static readonly HashSet<string> Ignored = new(StringComparer.OrdinalIgnoreCase)
            { "lockapp.exe", "logonui.exe", "scrnsave.scr", "consent.exe" };
    }
}
