using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace AirBridge.Phone
{
    // Phone notifications and calls on this PC (protocol.md §13): message parsing and the list / call state,
    // with no WPF or WinRT so AirBridge.PhoneTests can check it on any OS.

    public static class PhoneCaps
    {
        public const string NotifSource = "notif_source";
        public const string NotifSink = "notif_sink";
        public const string CallSource = "call_source";
        public const string CallSink = "call_sink";
        /// <summary>§13.5: presence, beacon and ring decisions.</summary>
        public const string CallRing = "call_ring";
    }

    /// <summary>One message of a conversation. <see cref="From"/> null = the phone's user.</summary>
    public sealed record PhoneMsg(string? From, string Text, DateTimeOffset Ts);

    public sealed record PhoneAction(int Index, string Label);

    public sealed record PhoneNotif(
        string PhoneId, string Key, string Pkg, string App, string Title, string Text, string Sub, DateTimeOffset Ts,
        string Cat, string? Conv, IReadOnlyList<PhoneMsg> Msgs, bool CanReply, string? ReplyLabel,
        IReadOnlyList<PhoneAction> Actions, bool Alert)
    {
        public string Id => MakeId(PhoneId, Key);
        public static string MakeId(string phone, string key) => phone + "|" + key;
        public string Headline => Title.Length == 0 ? App : Title;
        public string Body => Text.Length == 0 ? (Msgs.Count > 0 ? Msgs[^1].Text : "") : Text;
    }

    public enum PhoneCallState { Ringing, Active, Held, Ended }

    public sealed record PhoneCall(
        string PhoneId, string Id, PhoneCallState State, string? Name, string? Number, DateTimeOffset? Since,
        bool Muted, bool Speaker, IReadOnlyCollection<string> Can)
    {
        /// <summary>The number's label in the phone's contacts ("Mobile"), §13.5.</summary>
        public string? Label { get; init; }

        public string Caller => CallerText.Title(Name, Number);

        public bool Same(PhoneCall o) => o.Id == Id && o.State == State && o.Name == Name && o.Number == Number && o.Label == Label
                                         && o.Since == Since && o.Muted == Muted && o.Speaker == Speaker
                                         && o.Can.OrderBy(x => x).SequenceEqual(Can.OrderBy(x => x));
    }

    public sealed record PhoneRelayState(bool Notifs, bool Calls, string? Why);

    public static class PhoneWire
    {
        public const int MaxKey = 64;

        private static string? S(JsonObject o, string k) =>
            o[k] is JsonValue v && v.TryGetValue(out string? s) ? s : null;

        private static bool? B(JsonObject o, string k) =>
            o[k] is JsonValue v && v.GetValueKind() is JsonValueKind.True or JsonValueKind.False ? v.GetValue<bool>() : null;

        private static long? L(JsonObject o, string k)
        {
            if (o[k] is not JsonValue v || v.GetValueKind() != JsonValueKind.Number) return null;
            if (v.TryGetValue(out long l)) return l;
            if (v.TryGetValue(out double d) && !double.IsNaN(d) && Math.Abs(d) < 9e15) return (long)d;
            return null;
        }

        private static DateTimeOffset? At(long? ms)
        {
            if (ms is not long m) return null;
            try { return DateTimeOffset.FromUnixTimeMilliseconds(m); } catch (ArgumentOutOfRangeException) { return null; }
        }

        private static string Clip(string? s, int max)
        {
            string t = (s ?? "").Trim();
            return t.Length <= max ? t : t.Substring(0, max - 1).TrimEnd() + "…";
        }

        public static string? ValidKey(string? k) => string.IsNullOrEmpty(k) || k.Length > MaxKey ? null : k;

        public static PhoneNotif? ParsePost(JsonObject m, string phone)
        {
            if (S(m, "t") != "notif_post" || ValidKey(S(m, "key")) is not string key) return null;
            var msgs = new List<PhoneMsg>();
            if (m["msgs"] is JsonArray arr)
                foreach (var n in arr.Take(10))
                {
                    if (n is not JsonObject d || S(d, "text") is not string text || text.Length == 0) continue;
                    bool me = B(d, "me") == true;
                    msgs.Add(new PhoneMsg(me ? null : Clip(S(d, "from"), 80), Clip(text, 1000), At(L(d, "ts")) ?? DateTimeOffset.Now));
                }
            var acts = new List<PhoneAction>();
            if (m["acts"] is JsonArray aa)
                foreach (var n in aa.Take(3))
                {
                    if (n is not JsonObject d || S(d, "label") is not string label || label.Length == 0) continue;
                    if (L(d, "i") is not long i || i < 0 || i >= 16) continue;
                    acts.Add(new PhoneAction((int)i, Clip(label, 40)));
                }
            string pkg = Clip(S(m, "pkg"), 200);
            string app = Clip(S(m, "app"), 80);
            string? conv = S(m, "conv") is string c && Clip(c, 200).Length > 0 ? Clip(c, 200) : null;
            return new PhoneNotif(phone, key, pkg, app.Length == 0 ? pkg : app, Clip(S(m, "title"), 200), Clip(S(m, "text"), 2000),
                Clip(S(m, "sub"), 200), At(L(m, "ts")) ?? DateTimeOffset.Now, S(m, "cat") ?? "other", conv, msgs,
                B(m, "reply") == true, S(m, "reply_label") is string rl ? Clip(rl, 40) : null, acts, B(m, "alert") ?? true);
        }

        public static PhoneCall? ParseCall(JsonObject m, string phone)
        {
            if (S(m, "t") != "call_state" || ValidKey(S(m, "id")) is not string id) return null;
            PhoneCallState? st = S(m, "state") switch
            {
                "ringing" => PhoneCallState.Ringing, "active" => PhoneCallState.Active,
                "held" => PhoneCallState.Held, "ended" => PhoneCallState.Ended, _ => null,
            };
            if (st is null) return null;
            var can = new HashSet<string>();
            if (m["can"] is JsonArray ca)
                foreach (var n in ca) if (n is JsonValue v && v.TryGetValue(out string? s) && s != null) can.Add(s);
            return new PhoneCall(phone, id, st.Value, S(m, "name") is string nm ? Clip(nm, 100) : null,
                S(m, "number") is string nb ? Clip(nb, 40) : null, At(L(m, "since")), B(m, "muted") == true, B(m, "speaker") == true, can)
            {
                Label = S(m, "label") is string lb && Clip(lb, 40).Length > 0 ? Clip(lb, 40) : null,
            };
        }

        /// <summary>`call_photo` (§13.5): JPEG bytes of the caller's photo for call `id`.</summary>
        public static (string id, byte[] jpg)? ParseCallPhoto(JsonObject m)
        {
            if (S(m, "t") != "call_photo" || ValidKey(S(m, "id")) is not string id || S(m, "jpg") is not string b64 || b64.Length > 60_000) return null;
            try { var d = Convert.FromBase64String(b64); return d.Length == 0 ? null : (id, d); } catch (FormatException) { return null; }
        }

        /// <summary>`call_beacon` (§13.5): (true, key…) while the phone advertises, (true, null…) when it stopped; null = not this message / malformed.</summary>
        public static (bool ok, byte[]? key, string uuid, int period)? ParseBeacon(JsonObject m)
        {
            if (S(m, "t") != "call_beacon") return null;
            if (B(m, "on") != true) return (true, null, BeaconCrypto.ServiceUuid, 900);
            byte[] key;
            try { key = Convert.FromBase64String(S(m, "key") ?? ""); } catch (FormatException) { return null; }
            if (key.Length < 16 || key.Length > 64) return null;
            int period = (int)Math.Clamp(L(m, "period") ?? 900, 60, 86_400);
            return (true, key, S(m, "uuid") ?? BeaconCrypto.ServiceUuid, period);
        }

        public static JsonObject Ring(string id, bool silencePhone) => new() { ["t"] = "call_ring", ["id"] = id, ["phone"] = silencePhone ? "silent" : "ring" };

        public static PhoneRelayState? ParseState(JsonObject m) =>
            S(m, "t") == "notif_state" ? new PhoneRelayState(B(m, "notifs") == true, B(m, "calls") == true, S(m, "why")) : null;

        // Sink → source.
        public static JsonObject Sub(bool notifs, bool calls) => new() { ["t"] = "notif_sub", ["notifs"] = notifs, ["calls"] = calls };
        public static JsonObject Dismiss(string key) => new() { ["t"] = "notif_dismiss", ["key"] = key };
        public static JsonObject Reply(string key, string text) =>
            new() { ["t"] = "notif_reply", ["key"] = key, ["text"] = text.Length > 4000 ? text.Substring(0, 4000) : text };
        public static JsonObject Action(string key, int i) => new() { ["t"] = "notif_action", ["key"] = key, ["i"] = i };
        public static JsonObject Open(string key) => new() { ["t"] = "notif_open", ["key"] = key };
        public static JsonObject CallAction(string id, string op) => new() { ["t"] = "call_action", ["id"] = id, ["op"] = op };
    }

    /// <summary>The notifications shown on this PC, from every phone. Not thread-safe (UI thread).</summary>
    public sealed class PhoneNotifStore
    {
        private readonly Dictionary<string, PhoneNotif> _items = new();
        public int MaxPerPhone { get; init; } = 200;
        public IReadOnlyDictionary<string, PhoneNotif> Items => _items;
        public int Count => _items.Count;

        public abstract record Change
        {
            public sealed record None : Change;
            public sealed record Posted(PhoneNotif Notif, bool IsNew) : Change;
            public sealed record Removed(IReadOnlyList<string> Ids) : Change;
        }

        public Change Apply(JsonObject m, string phone)
        {
            switch (m["t"] is JsonValue tv && tv.TryGetValue(out string? t) ? t : null)
            {
                case "notif_post":
                    if (PhoneWire.ParsePost(m, phone) is not PhoneNotif n) return new Change.None();
                    bool isNew = !_items.ContainsKey(n.Id);
                    _items[n.Id] = n;
                    Trim(phone);
                    return new Change.Posted(n, isNew);
                case "notif_remove":
                    if (PhoneWire.ValidKey(m["key"] is JsonValue kv && kv.TryGetValue(out string? k) ? k : null) is not string key)
                        return new Change.None();
                    string id = PhoneNotif.MakeId(phone, key);
                    return _items.Remove(id) ? new Change.Removed(new[] { id }) : new Change.None();
                case "notif_reset":
                    return RemoveAll(phone);
                case "notif_state":
                    return PhoneWire.ParseState(m) is { Notifs: false } ? RemoveAll(phone) : new Change.None();
                default:
                    return new Change.None();
            }
        }

        public Change RemoveAll(string phone)
        {
            var ids = _items.Values.Where(n => n.PhoneId == phone).Select(n => n.Id).ToList();
            foreach (var id in ids) _items.Remove(id);
            return ids.Count == 0 ? new Change.None() : new Change.Removed(ids);
        }

        public bool Remove(string id) => _items.Remove(id);

        private void Trim(string phone)
        {
            var mine = _items.Values.Where(n => n.PhoneId == phone).OrderBy(n => n.Ts).ToList();
            for (int i = 0; i < mine.Count - MaxPerPhone; i++) _items.Remove(mine[i].Id);
        }

        public sealed record Group(string PhoneId, string Pkg, string App, IReadOnlyList<PhoneNotif> Items)
        {
            public string Id => PhoneId + "|" + Pkg;
        }

        /// <summary>Grouped by phone and app, newest app first; inside a group newest first.</summary>
        public IReadOnlyList<Group> Groups() =>
            _items.Values.GroupBy(n => n.PhoneId + "|" + n.Pkg)
                .Select(g => g.OrderByDescending(n => n.Ts).ToList())
                .Select(l => new Group(l[0].PhoneId, l[0].Pkg, l[0].App, l))
                .OrderByDescending(g => g.Items[0].Ts)
                .ToList();
    }

    /// <summary>One call per phone; ended calls disappear. Not thread-safe (UI thread).</summary>
    public sealed class PhoneCallBoard
    {
        private readonly Dictionary<string, PhoneCall> _calls = new();
        public IReadOnlyDictionary<string, PhoneCall> Calls => _calls;

        public enum Kind { None, Shown, Updated, Ended }

        public (Kind kind, PhoneCall? call) Apply(JsonObject m, string phone)
        {
            if (PhoneWire.ParseCall(m, phone) is not PhoneCall c) return (Kind.None, null);
            _calls.TryGetValue(phone, out var old);
            if (c.State == PhoneCallState.Ended)
            {
                if (old == null || old.Id != c.Id) return (Kind.None, null);
                _calls.Remove(phone);
                return (Kind.Ended, c);
            }
            _calls[phone] = c;
            if (old != null && old.Id == c.Id) return old.Same(c) ? (Kind.None, null) : (Kind.Updated, c);
            return (Kind.Shown, c);
        }

        public PhoneCall? PhoneGone(string phone) => _calls.Remove(phone, out var c) ? c : null;
    }
}
