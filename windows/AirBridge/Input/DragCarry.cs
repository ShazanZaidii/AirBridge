using System.IO;
using System.Text.Json.Nodes;

namespace AirBridge.Input
{
    // Carrying a file drag across the edge onto a target (protocol.md §8.8). Pure and platform-free (no Win32, no WPF,
    // no clocks), checked by AirBridge.InputTests on any OS. The controller (hook thread) and the target (its thread)
    // own one state object each.

    /// <summary>Controller side: is a carry in progress, and what goes on the wire when it ends. Not thread-safe.</summary>
    public sealed class DragCarryController
    {
        public enum Kind { None, Carry, Drop, Cancel }

        /// <summary>What to send after a transition: drag_carry / drag_drop (then the files, §6) / drag_cancel.</summary>
        public readonly record struct Action(Kind Kind, string? Peer = null, long Id = 0)
        {
            public static readonly Action None = new(Kind.None);
        }

        private long _nextId;

        public DragCarryController(long firstId = 1) { _nextId = Math.Max(1, firstId); }

        public string? Peer { get; private set; }
        public long Id { get; private set; }
        public bool IsCarrying => Peer != null;

        /// <summary>The capture into <paramref name="peer"/> started during a file drag (replaces any earlier carry
        /// silently: its capture has already ended).</summary>
        public Action Begin(string peer)
        {
            Id = _nextId;
            _nextId = _nextId >= 0x3FFF_FFFF ? 1 : _nextId + 1;
            Peer = peer;
            return new Action(Kind.Carry, peer, Id);
        }

        /// <summary>The held button was released while the cursor was on the target.</summary>
        public Action Release()
        {
            if (Peer is not { } p) return Action.None;
            Peer = null;
            return new Action(Kind.Drop, p, Id);
        }

        /// <summary>The capture ended before a release, or the button was found up without a release.
        /// <paramref name="peerGone"/>: the session is over, nothing can be sent.</summary>
        public Action End(bool peerGone = false)
        {
            if (Peer is not { } p) return Action.None;
            Peer = null;
            return peerGone ? Action.None : new Action(Kind.Cancel, p, Id);
        }

        /// <summary>Only peers that understand §8.8 and take files get a carry; others keep the old rule (a held
        /// button never crosses).</summary>
        public static bool Eligible(IReadOnlySet<string> caps) =>
            caps.Contains("drag_files") && caps.Contains("xfer") && caps.Contains("ctl_target");
    }

    /// <summary>The display fields of drag_carry.</summary>
    public sealed record DragPayload(IReadOnlyList<string> Names, IReadOnlyList<string> Kinds, int Count, long TotalBytes)
    {
        public const int MaxNames = 3;

        private static readonly HashSet<string> Images = new(StringComparer.OrdinalIgnoreCase)
            { ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".heic", ".heif", ".webp", ".tif", ".tiff", ".svg", ".raw", ".dng", ".avif" };
        private static readonly HashSet<string> Videos = new(StringComparer.OrdinalIgnoreCase)
            { ".mp4", ".mov", ".m4v", ".mkv", ".avi", ".wmv", ".webm", ".3gp", ".mpg", ".mpeg" };
        private static readonly HashSet<string> Audio = new(StringComparer.OrdinalIgnoreCase)
            { ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma", ".aiff", ".aif" };

        public static string KindOf(string path, bool isDirectory)
        {
            if (isDirectory) return "folder";
            string ext = Path.GetExtension(path);
            if (Images.Contains(ext)) return "image";
            if (Videos.Contains(ext)) return "video";
            if (Audio.Contains(ext)) return "audio";
            return "file";
        }

        /// <summary>Describes dropped paths. Folders count as one item and add no bytes (never walk a tree here).</summary>
        public static DragPayload Describe(IReadOnlyList<string> paths)
        {
            var names = new List<string>();
            var kinds = new List<string>();
            long total = 0;
            foreach (var p in paths)
            {
                bool dir = Directory.Exists(p);
                if (!dir) { try { total += new FileInfo(p).Length; } catch { } }
                if (names.Count < MaxNames)
                {
                    string n = Path.GetFileName(p.TrimEnd('\\', '/'));
                    names.Add(n.Length > 0 ? n : p);
                    kinds.Add(KindOf(p, dir));
                }
            }
            return new DragPayload(names, kinds, paths.Count, total);
        }

        public JsonObject Message(long id)
        {
            var names = new JsonArray();
            foreach (var n in Names) names.Add(n);
            var kinds = new JsonArray();
            foreach (var k in Kinds) kinds.Add(k);
            return new JsonObject
            {
                ["t"] = "drag_carry",
                ["id"] = id,
                ["names"] = names,
                ["kinds"] = kinds,
                ["count"] = Count,
                ["totalBytes"] = TotalBytes,
            };
        }
    }

    /// <summary>Target side: which ghost, if any, follows our cursor. Mirrors Android's DragCarryTarget. Not thread-safe.</summary>
    public sealed class DragGhostState
    {
        public sealed record Info(long Id, string From, IReadOnlyList<string> Names, IReadOnlyList<string> Kinds, int Count, long TotalBytes)
        {
            public string Kind => Kinds.Count > 0 ? Kinds[0] : "file";
            public string FirstName => Names.Count > 0 ? Names[0] : "";

            // Lists compare by reference in records; compare by content so tests and replays behave.
            public bool Same(Info? o) => o != null && o.Id == Id && o.From == From && o.Count == Count && o.TotalBytes == TotalBytes
                                         && o.Names.SequenceEqual(Names) && o.Kinds.SequenceEqual(Kinds);
        }

        public enum Kind { None, Show, Hide, Dropped }

        public readonly record struct Effect(Kind Kind, Info? Info = null, bool GhostWasShown = false)
        {
            public static readonly Effect None = new(Kind.None);
            public static readonly Effect Hide = new(Kind.Hide);
        }

        public Info? Shown { get; private set; }
        private Info? _recent; // hidden by a leave, not a cancel: a drop racing our ctl_leave still confirms

        public Effect Carry(string from, string? holder, Info info)
        {
            if (holder == null || holder != from || info.From != from) return Effect.None;
            Shown = info;
            _recent = null;
            return new Effect(Kind.Show, info);
        }

        public Effect Drop(string from, long id)
        {
            if (Shown is { } s && s.From == from && s.Id == id)
            {
                Shown = null; _recent = null;
                return new Effect(Kind.Dropped, s, true);
            }
            if (_recent is { } r && r.From == from && r.Id == id)
            {
                _recent = null;
                return new Effect(Kind.Dropped, r, false);
            }
            return Effect.None;
        }

        public Effect Cancel(string from, long id)
        {
            if (_recent is { } r && r.From == from && r.Id == id) _recent = null;
            if (Shown is not { } s || s.From != from || s.Id != id) return Effect.None;
            Shown = null;
            return Effect.Hide;
        }

        /// <summary>ctl_leave either way, pre-emption, lease end. <paramref name="peer"/> null = whoever it was.</summary>
        public Effect Leave(string? peer)
        {
            if (Shown is not { } s || (peer != null && s.From != peer)) return Effect.None;
            Shown = null;
            _recent = s;
            return Effect.Hide;
        }

        public Effect PeerGone(string peer)
        {
            if (_recent?.From == peer) _recent = null;
            if (Shown is not { } s || s.From != peer) return Effect.None;
            Shown = null;
            return Effect.Hide;
        }

        private static readonly HashSet<string> Kinds = new() { "image", "video", "audio", "folder", "file" };

        /// <summary>Builds <see cref="Info"/> from a drag_carry message, tolerating odd values; null without a usable id.</summary>
        public static Info? Parse(string from, JsonObject m)
        {
            static string? StrOf(JsonNode? n)
            {
                if (n is not JsonValue v) return null;
                if (v.TryGetValue(out string? s)) return s;
                if (v.TryGetValue(out System.Text.Json.JsonElement e) && e.ValueKind == System.Text.Json.JsonValueKind.String) return e.GetString();
                return null;
            }
            static double? Finite(double? d) => d is double x && double.IsFinite(x) ? x : null;

            if (Finite(Msg.Num(m, "id")) is not double id || id < 0 || id >= 9e15) return null;
            var names = new List<string>();
            var rawKinds = new List<string>();
            if (m["names"] is JsonArray na)
                foreach (var n in na)
                {
                    string? s = StrOf(n)?.Trim();
                    if (!string.IsNullOrEmpty(s) && names.Count < DragPayload.MaxNames) names.Add(s.Length > 200 ? s[..200] : s);
                }
            if (m["kinds"] is JsonArray ka)
                foreach (var k in ka) rawKinds.Add(StrOf(k) ?? "");
            var kinds = names.Select((_, i) => i < rawKinds.Count && Kinds.Contains(rawKinds[i]) ? rawKinds[i] : "file").ToList();
            int count = Math.Max(1, Math.Max(names.Count, (int)Math.Clamp(Finite(Msg.Num(m, "count")) ?? names.Count, 0, 1e9)));
            long bytes = (long)Math.Clamp(Finite(Msg.Num(m, "totalBytes")) ?? 0, 0, 9e18);
            return new Info((long)id, from, names, kinds, count, bytes);
        }
    }
}
