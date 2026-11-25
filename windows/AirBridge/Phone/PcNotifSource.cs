using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using AirBridge.Net;
using Windows.UI.Notifications;
using Windows.UI.Notifications.Management;

namespace AirBridge.Phone
{
    /// <summary>
    /// This PC's own notifications on your other devices (PROTOCOL §13 as a source, like a phone): Windows lets an app
    /// read the notifications in the notification center through <see cref="UserNotificationListener"/> once the user
    /// allows it (Windows asks the first time; Settings › Privacy › Notifications can take it back).
    ///
    /// Windows gives that access only to apps with package identity (the MSIX build, AirBridge.Package): the plain
    /// zip build doesn't advertise <c>notif_source</c>. Windows offers no way to press a notification's buttons or
    /// reply from outside, so these go as list entries that can be cleared, not answered.
    ///
    /// The list is read every two seconds while a device that shows notifications is connected (the change event isn't
    /// delivered to desktop apps reliably), and only the differences are sent.
    /// </summary>
    public static class PcNotifSource
    {
        public const string Cap = "notif_source";
        private static readonly TimeSpan PollEvery = TimeSpan.FromSeconds(2);

        private static readonly object Gate = new();
        private static UserNotificationListener? _listener;
        private static bool _access;
        private static bool _asked;
        private static Timer? _poll;
        private static int _polling;
        /// <summary>What each device was last sent: notification id → post.</summary>
        private static readonly Dictionary<uint, JsonObject> Current = new();
        private static readonly Dictionary<string, HashSet<string>> IconsSent = new();
        private static readonly HashSet<string> Ready = new();
        private static readonly HashSet<string> Unsubscribed = new();
        private static string? _lastOrder;

        /// <summary>Windows can hand this app its notifications (package identity, Windows 10 1809 or later).</summary>
        public static bool Supported { get; } = HasIdentity() && OperatingSystem.IsWindowsVersionAtLeast(10, 0, 17763);

        private static bool HasIdentity()
        {
            try { return Windows.ApplicationModel.Package.Current != null; }
            catch { return false; }
        }

        public static void Start(PeerService peers)
        {
            if (!Supported) { Log.Info("This PC's notifications: not available in this build (no package identity)"); return; }
            peers.StateChanged += () => Task.Run(PeersChanged);
        }

        private static List<PeerInfo> Sinks() =>
            PeerBus.Peers().Where(p => p.Caps.Contains(PhoneCaps.NotifSink) && !Unsubscribed.Contains(p.Id)).ToList();

        private static async Task PeersChanged()
        {
            var all = PeerBus.Peers().Where(p => p.Caps.Contains(PhoneCaps.NotifSink)).Select(p => p.Id).ToHashSet();
            lock (Gate)
            {
                Ready.RemoveWhere(id => !all.Contains(id));
                foreach (var gone in IconsSent.Keys.Where(id => !all.Contains(id)).ToList()) IconsSent.Remove(gone);
            }
            if (all.Count == 0) { StopPolling(); return; }
            await EnsureAccess();
            List<string> fresh;
            lock (Gate) { fresh = all.Where(id => !Ready.Contains(id)).ToList(); foreach (var id in fresh) Ready.Add(id); }
            foreach (var id in fresh) Resync(id);
            if (_access) StartPolling();
        }

        /// <summary>Asks Windows once (its own prompt, on the UI thread).</summary>
        private static async Task EnsureAccess()
        {
            if (_asked) { _access = _listener?.GetAccessStatus() == UserNotificationListenerAccessStatus.Allowed; return; }
            _asked = true;
            try
            {
                _listener = UserNotificationListener.Current;
                var tcs = new TaskCompletionSource<UserNotificationListenerAccessStatus>();
                Application.Current?.Dispatcher.BeginInvoke(new Action(async () =>
                {
                    try { tcs.TrySetResult(await _listener.RequestAccessAsync()); }
                    catch (Exception ex) { tcs.TrySetException(ex); }
                }));
                _access = await tcs.Task == UserNotificationListenerAccessStatus.Allowed;
                Log.Info($"This PC's notifications: access {(_access ? "allowed" : "not allowed")}");
            }
            catch (Exception ex)
            {
                Log.Warn($"This PC's notifications unavailable: {ex.Message}");
                _access = false;
            }
        }

        private static JsonObject State()
        {
            var m = Protocol.Msg("notif_state");
            m["notifs"] = _access;
            m["calls"] = false;
            if (!_access) m["why"] = "no_access";
            return m;
        }

        private static void StartPolling()
        {
            lock (Gate) _poll ??= new Timer(_ => _ = Poll(), null, TimeSpan.Zero, PollEvery);
        }

        private static void StopPolling()
        {
            lock (Gate) { _poll?.Dispose(); _poll = null; Current.Clear(); _lastOrder = null; }
        }

        /// <summary>One device just connected: everything now, quietly.</summary>
        private static void Resync(string peerId)
        {
            PeerBus.Send(peerId, State());
            PeerBus.Send(peerId, Protocol.Msg("notif_reset"));
            List<JsonObject> posts;
            lock (Gate) posts = Current.Values.Select(p => (JsonObject)p.DeepClone()).ToList();
            foreach (var p in posts)
            {
                p["alert"] = false;
                SendIconIfNeeded(peerId, Protocol.Str(p, "pkg") ?? "");
                PeerBus.Send(peerId, p);
            }
            if (_lastOrder != null && Protocol.Parse(_lastOrder) is JsonObject order) PeerBus.Send(peerId, order);
        }

        private static async Task Poll()
        {
            if (Interlocked.Exchange(ref _polling, 1) == 1 || _listener == null) return;
            try
            {
                var list = await _listener.GetNotificationsAsync(NotificationKinds.Toast);
                var now = new Dictionary<uint, (JsonObject post, DateTimeOffset at)>();
                foreach (var n in list)
                {
                    var post = Build(n);
                    if (post != null) now[n.Id] = (post, n.CreationTime);
                }
                List<JsonObject> changed = new();
                List<uint> removed;
                bool firstRead;
                lock (Gate)
                {
                    firstRead = Current.Count == 0 && _lastOrder == null;
                    removed = Current.Keys.Where(id => !now.ContainsKey(id)).ToList();
                    foreach (var id in removed) Current.Remove(id);
                    foreach (var (id, (post, _)) in now)
                    {
                        bool isNew = !Current.TryGetValue(id, out var old);
                        if (!isNew && old!.ToJsonString() == post.ToJsonString()) continue;
                        Current[id] = post;
                        var send = (JsonObject)post.DeepClone();
                        // A new one pops up on the other devices; an update or the first read after starting is quiet.
                        send["alert"] = isNew && !firstRead;
                        changed.Add(send);
                    }
                }
                var sinks = Sinks();
                foreach (var id in removed)
                {
                    var rm = Protocol.Msg("notif_remove"); rm["key"] = id.ToString();
                    foreach (var p in sinks) PeerBus.Send(p.Id, rm);
                }
                foreach (var post in changed)
                    foreach (var p in sinks) { SendIconIfNeeded(p.Id, Protocol.Str(post, "pkg") ?? ""); PeerBus.Send(p.Id, post); }
                // The notification center lists newest first.
                var order = Protocol.Msg("notif_order");
                order["keys"] = new JsonArray(now.OrderByDescending(e => e.Value.at).Select(e => (JsonNode)e.Key.ToString()).ToArray());
                order["silent"] = new JsonArray();
                var orderText = order.ToJsonString();
                if (orderText != _lastOrder)
                {
                    _lastOrder = orderText;
                    foreach (var p in sinks) PeerBus.Send(p.Id, (JsonObject)order.DeepClone());
                }
            }
            catch (Exception ex)
            {
                Log.Warn($"Reading this PC's notifications failed: {ex.Message}");
            }
            finally { Interlocked.Exchange(ref _polling, 0); }
        }

        private static JsonObject? Build(UserNotification n)
        {
            try
            {
                var binding = n.Notification?.Visual?.GetBinding(KnownNotificationBindings.ToastGeneric);
                var texts = binding?.GetTextElements().Select(t => t.Text?.Trim() ?? "").Where(t => t.Length > 0).ToList() ?? new List<string>();
                if (texts.Count == 0) return null;
                var m = Protocol.Msg("notif_post");
                m["key"] = n.Id.ToString();
                m["pkg"] = Clip(n.AppInfo?.AppUserModelId ?? "windows", 200);
                m["app"] = Clip(n.AppInfo?.DisplayInfo?.DisplayName ?? "Windows", 80);
                m["title"] = Clip(texts[0], 200);
                m["text"] = Clip(string.Join("\n", texts.Skip(1)), 2000);
                m["ts"] = n.CreationTime.ToUnixTimeMilliseconds();
                m["cat"] = "other";
                m["reply"] = false;
                return m;
            }
            catch { return null; }
        }

        private static string Clip(string s, int max) => s.Length <= max ? s : s[..(max - 1)] + "…";

        private static void SendIconIfNeeded(string peerId, string pkg)
        {
            lock (Gate)
            {
                if (!IconsSent.TryGetValue(peerId, out var have)) IconsSent[peerId] = have = new HashSet<string>();
                if (!have.Add(pkg)) return;
            }
            _ = Task.Run(async () =>
            {
                try
                {
                    var list = _listener == null ? null : await _listener.GetNotificationsAsync(NotificationKinds.Toast);
                    var info = list?.FirstOrDefault(x => x.AppInfo?.AppUserModelId == pkg)?.AppInfo?.DisplayInfo;
                    if (info == null) return;
                    var logo = info.GetLogo(new Windows.Foundation.Size(64, 64));
                    using var stream = await logo.OpenReadAsync();
                    using var s = stream.AsStreamForRead();
                    using var ms = new MemoryStream();
                    await s.CopyToAsync(ms);
                    if (ms.Length == 0 || ms.Length > 300_000) return;
                    var m = Protocol.Msg("notif_icon");
                    m["pkg"] = pkg;
                    m["png"] = Convert.ToBase64String(ms.ToArray());
                    PeerBus.Send(peerId, m);
                }
                catch (Exception ex) { Log.Warn($"App icon for {pkg}: {ex.Message}"); }
            });
        }

        /// <summary>Sink → source commands (`notif_sub`, `notif_dismiss`, `notif_open`, …) from a device showing ours.</summary>
        public static bool OnCommand(string peerId, JsonObject m)
        {
            if (!Supported) return false;
            var t = Protocol.Str(m, "t");
            switch (t)
            {
                case "notif_sub":
                    lock (Gate) { if (Protocol.Bool(m, "notifs") == false) Unsubscribed.Add(peerId); else Unsubscribed.Remove(peerId); }
                    return true;
                case "notif_dismiss":
                    _ = Dismiss(peerId, Protocol.Str(m, "key") ?? "");
                    return true;
                case "notif_open":
                case "notif_reply":
                case "notif_action":
                    // Windows has no way to press a notification's buttons from another app.
                    PeerBus.Send(peerId, Result(Protocol.Str(m, "key") ?? "", t == "notif_open" ? "open" : t == "notif_reply" ? "reply" : "action", false,
                        t == "notif_reply" ? "no_reply" : t == "notif_action" ? "no_action" : "failed"));
                    return true;
                default:
                    return false;
            }
        }

        private static async Task Dismiss(string peerId, string key)
        {
            if (_listener == null || !uint.TryParse(key, out var id)) { PeerBus.Send(peerId, Result(key, "dismiss", false, "gone")); return; }
            try
            {
                _listener.RemoveNotification(id);
                await Task.Delay(300);
                var still = (await _listener.GetNotificationsAsync(NotificationKinds.Toast)).Any(n => n.Id == id);
                PeerBus.Send(peerId, still ? Result(key, "dismiss", false, "failed") : Result(key, "dismiss", true, null));
                if (!still) await Poll();
            }
            catch (Exception ex)
            {
                Log.Warn($"Clearing a notification failed: {ex.Message}");
                PeerBus.Send(peerId, Result(key, "dismiss", false, "failed"));
            }
        }

        private static JsonObject Result(string key, string op, bool ok, string? reason)
        {
            var m = Protocol.Msg("notif_result");
            m["key"] = key; m["op"] = op; m["ok"] = ok;
            if (!ok && reason != null) m["reason"] = reason;
            return m;
        }
    }
}
