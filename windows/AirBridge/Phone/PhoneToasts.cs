using System;
using System.Collections.Generic;
using System.Security;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using AirBridge.Net;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace AirBridge.Phone
{
    /// <summary>
    /// Phone notifications as Windows toasts (WinRT ToastNotificationManager), with a reply box when the phone's
    /// notification takes a reply. Toasts need a package identity (MSIX install); the unpackaged build falls back to
    /// a tray balloon without buttons, and the "Phone notifications" window still offers reply and clear.
    /// Activation is handled through the toast object's events while AirBridge runs (no COM activator).
    /// </summary>
    public static class PhoneToasts
    {
        private static ToastNotifier? _notifier;
        /// <summary>Live toasts by notification id (their events must stay subscribed).</summary>
        private static readonly Dictionary<string, ToastNotification> Live = new();

        public static void Init()
        {
            if (!Win32.IsPackaged) return;
            try { _notifier = ToastNotificationManager.CreateToastNotifier(); }
            catch (Exception ex) { Log.Warn($"Toasts unavailable: {ex.Message}"); _notifier = null; }
        }

        private static string Hash(string s)
        {
            var b = SHA256.HashData(Encoding.UTF8.GetBytes(s));
            return Convert.ToHexString(b, 0, 8).ToLowerInvariant();
        }

        private static string X(string s) => SecurityElement.Escape(s) ?? "";
        private static string Arg(string action, string id) => X($"a={action}&id={Uri.EscapeDataString(id)}");

        /// <summary>UI thread.</summary>
        public static void Show(PhoneNotif n, string phoneName, string? iconPath, bool sound)
        {
            if (_notifier == null)
            {
                App.Notify($"{n.App}: {n.Headline}", n.Body.Length > 200 ? n.Body.Substring(0, 199) + "…" : n.Body);
                return;
            }
            try
            {
                var xml = new StringBuilder();
                xml.Append($"<toast launch=\"{Arg("open", n.Id)}\" activationType=\"foreground\">");
                xml.Append("<visual><binding template=\"ToastGeneric\">");
                xml.Append($"<text hint-maxLines=\"1\">{X(n.Headline)}</text>");
                xml.Append($"<text>{X(n.Body)}</text>");
                string attribution = n.Conv != null && n.Conv != n.Title ? $"{n.App} · {n.Conv}" : $"{n.App} · {phoneName}";
                xml.Append($"<text placement=\"attribution\">{X(attribution)}</text>");
                if (iconPath != null)
                    xml.Append($"<image placement=\"appLogoOverride\" src=\"{X(new Uri(iconPath).AbsoluteUri)}\"/>");
                xml.Append("</binding></visual><actions>");
                if (n.CanReply)
                {
                    xml.Append("<input id=\"reply\" type=\"text\" placeHolderContent=\"Message\"/>");
                    xml.Append($"<action content=\"Send\" arguments=\"{Arg("reply", n.Id)}\" hint-inputId=\"reply\" activationType=\"foreground\"/>");
                }
                xml.Append($"<action content=\"Clear\" arguments=\"{Arg("dismiss", n.Id)}\" activationType=\"foreground\"/>");
                xml.Append("</actions>");
                if (!sound) xml.Append("<audio silent=\"true\"/>");
                xml.Append("</toast>");

                var doc = new XmlDocument();
                doc.LoadXml(xml.ToString());
                var toast = new ToastNotification(doc) { Tag = Hash(n.Id), Group = Hash(n.PhoneId) };
                string id = n.Id;
                toast.Activated += (_, args) =>
                {
                    string? a = null, text = null;
                    if (args is ToastActivatedEventArgs ta)
                    {
                        foreach (var part in (ta.Arguments ?? "").Split('&'))
                            if (part.StartsWith("a=", StringComparison.Ordinal)) a = part.Substring(2);
                        if (ta.UserInput != null && ta.UserInput.TryGetValue("reply", out object? v)) text = v as string;
                    }
                    string action = a ?? "open";
                    Application.Current?.Dispatcher.BeginInvoke(new Action(() => PhoneNotifHub.OnToast(id, action, text)));
                };
                toast.Dismissed += (_, args) =>
                {
                    if (args.Reason != ToastDismissalReason.UserCanceled) return;
                    Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
                    {
                        Live.Remove(id);
                        if (PhoneNotifHub.Store.Items.ContainsKey(id) && PhoneNotifHub.IsConnected(n.PhoneId)) PhoneNotifHub.OnToast(id, "dismiss", null);
                    }));
                };
                Live[id] = toast;
                _notifier.Show(toast);
            }
            catch (Exception ex)
            {
                Log.Warn($"Toast failed: {ex.Message}");
                App.Notify($"{n.App}: {n.Headline}", n.Body);
            }
        }

        /// <summary>UI thread.</summary>
        public static void Remove(string id)
        {
            if (!Live.Remove(id, out var toast) || _notifier == null) return;
            try { ToastNotificationManager.History.Remove(toast.Tag, toast.Group); }
            catch (Exception ex) { Log.Warn($"Toast removal failed: {ex.Message}"); }
        }
    }
}
