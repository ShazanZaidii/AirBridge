using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using AirBridge.Net;

namespace AirBridge.Phone
{
    /// <summary>What this PC shows from phones, in %LOCALAPPDATA%\AirBridge\phone.json.</summary>
    public static class PhoneSettings
    {
        private sealed class Stored
        {
            public bool ShowNotifs { get; set; } = true;
            public bool ShowCalls { get; set; } = true;
            public bool Sounds { get; set; } = true;
            /// <summary>"smart" | "always" | "never" (§13.5).</summary>
            public string Ring { get; set; } = "smart";
        }

        private static readonly string FilePath = Path.Combine(Log.Dir, "phone.json");
        private static readonly object Gate = new();
        private static Stored? _s;

        public static event Action? Changed;

        public static bool ShowNotifs { get { lock (Gate) return Load().ShowNotifs; } set { lock (Gate) { Load().ShowNotifs = value; Save(); } Changed?.Invoke(); } }
        public static bool ShowCalls { get { lock (Gate) return Load().ShowCalls; } set { lock (Gate) { Load().ShowCalls = value; Save(); } Changed?.Invoke(); } }
        public static bool Sounds { get { lock (Gate) return Load().Sounds; } set { lock (Gate) { Load().Sounds = value; Save(); } Changed?.Invoke(); } }

        /// <summary>"Ring on this PC": Smart (where you are), Always, Never.</summary>
        public static RingMode RingMode
        {
            get { lock (Gate) return Load().Ring switch { "always" => RingMode.Always, "never" => RingMode.Never, _ => RingMode.Smart }; }
            set { lock (Gate) { Load().Ring = value.ToString().ToLowerInvariant(); Save(); } Changed?.Invoke(); }
        }

        private static Stored Load()
        {
            if (_s != null) return _s;
            try { _s = File.Exists(FilePath) ? JsonSerializer.Deserialize<Stored>(File.ReadAllText(FilePath)) : null; }
            catch (Exception ex) { Log.Warn($"phone.json unreadable ({ex.Message}); using defaults"); }
            return _s ??= new Stored();
        }

        private static void Save()
        {
            try { Directory.CreateDirectory(Log.Dir); File.WriteAllText(FilePath, JsonSerializer.Serialize(_s)); }
            catch (Exception ex) { Log.Warn($"Could not save phone.json: {ex.Message}"); }
        }
    }

    /// <summary>
    /// Phone notifications and calls on this PC (PROTOCOL §13). Network messages arrive on any thread and are
    /// handled on the UI thread: the list (<see cref="PhoneNotifsWindow"/>), Windows toasts with a reply box where
    /// the phone supports it (<see cref="PhoneToasts"/>), and the call window (<see cref="CallWindow"/>).
    /// </summary>
    public static class PhoneNotifHub
    {
        public static readonly PhoneNotifStore Store = new();
        private static readonly PhoneCallBoard Board = new();
        public static readonly Dictionary<string, PhoneRelayState> States = new();
        private static readonly Dictionary<string, string> LastSub = new();
        private static HashSet<string> _sources = new();

        /// <summary>Notifications since the list was last opened (tray button).</summary>
        public static int Unseen { get; set; }

        /// <summary>The list or the call changed (UI thread).</summary>
        public static event Action? Changed;

        public static string IconDir { get; } = Path.Combine(Log.Dir, "PhoneIcons");

        public static void Start(PeerService peers)
        {
            peers.StateChanged += () => OnUi(PeersChanged);
            PhoneSettings.Changed += () => OnUi(SettingsChanged);
            PhoneToasts.Init();
            OnUi(PeersChanged);
        }

        private static void OnUi(Action a)
        {
            var d = Application.Current?.Dispatcher;
            if (d == null) return;
            d.BeginInvoke(new Action(() =>
            {
                try { a(); } catch (Exception ex) { Log.Error("Phone notifications", ex); }
            }));
        }

        /// <summary>Session (network thread): a `notif_*` / `call_*` message from a ready peer.</summary>
        public static void OnMessage(string peerId, JsonObject o)
        {
            var copy = o.DeepClone().AsObject();
            OnUi(() => Handle(peerId, copy));
        }

        private static bool IsSource(string phone) =>
            PeerBus.Peers().Any(p => p.Id == phone && (p.Caps.Contains(PhoneCaps.NotifSource) || p.Caps.Contains(PhoneCaps.CallSource)));

        public static bool IsConnected(string phone) => IsSource(phone);

        public static string PhoneName(string phone) =>
            PeerBus.Peers().FirstOrDefault(p => p.Id == phone)?.Name ?? "your phone";

        public static IReadOnlyList<PeerInfo> SourcePhones() =>
            PeerBus.Peers().Where(p => p.Caps.Contains(PhoneCaps.NotifSource)).ToList();

        private static string? Str(JsonObject o, string k) => o[k] is JsonValue v && v.TryGetValue(out string? s) ? s : null;
        private static bool IsFalse(JsonObject o, string k) => o[k] is JsonValue v && v.GetValueKind() == JsonValueKind.False;

        private static void Handle(string phone, JsonObject m)
        {
            string? t = Str(m, "t");
            if (t == null || !IsSource(phone)) return;
            switch (t)
            {
                case "notif_icon":
                    if (Str(m, "pkg") is string pkg && Str(m, "png") is string b64 && b64.Length < 400_000)
                    {
                        try
                        {
                            byte[] png = Convert.FromBase64String(b64);
                            Directory.CreateDirectory(IconDir);
                            File.WriteAllBytes(IconPath(pkg), png);
                        }
                        catch (Exception ex) { Log.Warn($"Phone icon for {pkg}: {ex.Message}"); }
                    }
                    break;
                case "notif_state":
                    if (PhoneWire.ParseState(m) is PhoneRelayState st) States[phone] = st;
                    Apply(m, phone);
                    break;
                case "notif_post":
                    if (!PhoneSettings.ShowNotifs) return;
                    Apply(m, phone);
                    break;
                case "notif_remove":
                case "notif_reset":
                    Apply(m, phone);
                    break;
                case "notif_result":
                    if (!IsFalse(m, "ok")) return;
                    string? text = FailureText(Str(m, "op") ?? "", Str(m, "reason"), phone);
                    if (text != null) App.Notify("AirBridge", text);
                    break;
                case "call_state":
                    if (!PhoneSettings.ShowCalls) return;
                    var (kind, call) = Board.Apply(m, phone);
                    if (call == null) return;
                    CallRinger.OnCall(call);
                    if (kind == PhoneCallBoard.Kind.Ended) CallWindow.End(call);
                    else CallWindow.ShowOrUpdate(call, kind == PhoneCallBoard.Kind.Shown);
                    Changed?.Invoke();
                    break;
                case "call_photo":
                    if (PhoneWire.ParseCallPhoto(m) is { } ph) CallWindow.SetPhoto(phone, ph.id, ph.jpg);
                    break;
                case "call_presence":
                    if (PhonePresenceReport.Parse(m, DateTimeOffset.Now) is PhonePresenceReport pr) CallRinger.OnPresence(phone, pr);
                    break;
                case "call_beacon":
                    if (PhoneWire.ParseBeacon(m) is { } bc) BeaconWatcher.SetBeacon(phone, bc.key, bc.uuid, bc.period);
                    break;
                case "call_result":
                    if (!IsFalse(m, "ok")) return;
                    string reason = Str(m, "reason") switch
                    {
                        "no_permission" => $"Allow the Phone permission for AirBridge on {PhoneName(phone)} to answer or end calls from here.",
                        "off" => $"Calls are turned off in AirBridge on {PhoneName(phone)}.",
                        "no_call" => "That call has already ended.",
                        _ => $"{PhoneName(phone)} couldn't do that. Use the phone instead.",
                    };
                    CallWindow.Flash(phone, reason);
                    break;
            }
        }

        private static void Apply(JsonObject m, string phone)
        {
            switch (Store.Apply(m, phone))
            {
                case PhoneNotifStore.Change.Posted p:
                    if (p.Notif.Alert)
                    {
                        Unseen++;
                        PhoneToasts.Show(p.Notif, PhoneName(phone), File.Exists(IconPath(p.Notif.Pkg)) ? IconPath(p.Notif.Pkg) : null, PhoneSettings.Sounds);
                    }
                    Changed?.Invoke();
                    break;
                case PhoneNotifStore.Change.Removed r:
                    foreach (var id in r.Ids) PhoneToasts.Remove(id);
                    Changed?.Invoke();
                    break;
            }
        }

        private static string? FailureText(string op, string? reason, string phone)
        {
            string p = PhoneName(phone);
            return reason switch
            {
                "gone" => op == "dismiss" ? null : $"That notification is no longer on {p}.",
                "no_reply" => "That notification can't be answered from here.",
                "off" => $"Notifications are turned off in AirBridge on {p}.",
                _ => op switch
                {
                    "reply" => $"Couldn't send the reply. Open it on {p} instead.",
                    "open" => $"{p} didn't open it. Unlock the phone and try again.",
                    _ => $"{p} couldn't do that.",
                },
            };
        }

        public static string IconPath(string pkg)
        {
            var safe = new string(pkg.Select(c => char.IsLetterOrDigit(c) || c == '.' || c == '_' ? c : '_').ToArray()).Trim('.');
            if (safe.Length == 0) safe = "app";
            if (safe.Length > 120) safe = safe.Substring(0, 120);
            return Path.Combine(IconDir, safe + ".png");
        }

        // ---- peers & settings

        private static void PeersChanged()
        {
            var now = PeerBus.Peers().Where(p => p.Caps.Contains(PhoneCaps.NotifSource) || p.Caps.Contains(PhoneCaps.CallSource))
                .Select(p => p.Id).ToHashSet();
            foreach (var id in now.Where(id => !_sources.Contains(id))) { LastSub.Remove(id); SendSub(id); }
            foreach (var gone in _sources.Where(id => !now.Contains(id)).ToList())
            {
                LastSub.Remove(gone);
                States.Remove(gone);
                foreach (var n in Store.Items.Values.Where(n => n.PhoneId == gone)) PhoneToasts.Remove(n.Id);
                if (Board.PhoneGone(gone) is PhoneCall c) { CallRinger.OnCall(c with { State = PhoneCallState.Ended }); CallWindow.End(c); }
                CallRinger.PhoneGone(gone);
            }
            bool changed = !now.SetEquals(_sources);
            _sources = now;
            if (changed) Changed?.Invoke();
        }

        private static void SettingsChanged()
        {
            foreach (var id in _sources) SendSub(id);
            if (!PhoneSettings.ShowNotifs)
                foreach (var phone in Store.Items.Values.Select(n => n.PhoneId).Distinct().ToList())
                    if (Store.RemoveAll(phone) is PhoneNotifStore.Change.Removed r) foreach (var id in r.Ids) PhoneToasts.Remove(id);
            if (!PhoneSettings.ShowCalls)
                foreach (var phone in Board.Calls.Keys.ToList())
                    if (Board.PhoneGone(phone) is PhoneCall c) { CallRinger.OnCall(c with { State = PhoneCallState.Ended }); CallWindow.End(c); }
            BeaconWatcher.Update();
            Changed?.Invoke();
        }

        private static void SendSub(string peerId)
        {
            var msg = PhoneWire.Sub(PhoneSettings.ShowNotifs, PhoneSettings.ShowCalls);
            string json = msg.ToJsonString();
            if (LastSub.TryGetValue(peerId, out var old) && old == json) return;
            LastSub[peerId] = json;
            PeerBus.Send(peerId, msg);
        }

        // ---- UI → phone (UI thread)

        public static void Dismiss(PhoneNotif n)
        {
            PeerBus.Send(n.PhoneId, PhoneWire.Dismiss(n.Key));
            if (Store.Remove(n.Id)) { PhoneToasts.Remove(n.Id); Changed?.Invoke(); }
        }

        public static void Reply(PhoneNotif n, string text)
        {
            text = text.Trim();
            if (text.Length == 0) return;
            if (!IsConnected(n.PhoneId)) { App.Notify("AirBridge", $"{PhoneName(n.PhoneId)} isn't connected."); return; }
            PeerBus.Send(n.PhoneId, PhoneWire.Reply(n.Key, text));
        }

        public static void Act(PhoneNotif n, PhoneAction a)
        {
            if (IsConnected(n.PhoneId)) PeerBus.Send(n.PhoneId, PhoneWire.Action(n.Key, a.Index));
        }

        public static void OpenOnPhone(PhoneNotif n)
        {
            if (IsConnected(n.PhoneId)) PeerBus.Send(n.PhoneId, PhoneWire.Open(n.Key));
        }

        public static void CallAction(PhoneCall c, string op)
        {
            if (IsConnected(c.PhoneId)) PeerBus.Send(c.PhoneId, PhoneWire.CallAction(c.Id, op));
        }

        /// <summary>A toast button or the toast itself was used (UI thread). <paramref name="id"/> = notification id.</summary>
        public static void OnToast(string id, string action, string? text)
        {
            if (!Store.Items.TryGetValue(id, out var n)) { if (action == "open") PhoneNotifsWindow.ShowWindow(); return; }
            switch (action)
            {
                case "reply": if (text != null) Reply(n, text); break;
                case "dismiss": Dismiss(n); break;
                case "phone": OpenOnPhone(n); break;
                default: PhoneNotifsWindow.ShowWindow(); break;
            }
        }
    }
}
