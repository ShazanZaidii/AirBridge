using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AirBridge.Net;

namespace AirBridge.Phone
{
    internal static class Ui
    {
        public static Brush Res(string key, Brush fallback) =>
            Application.Current?.TryFindResource(key) as Brush ?? fallback;

        public static Style? Style(string key) => Application.Current?.TryFindResource(key) as Style;

        public static TextBlock Text(string s, string style = "AB.Text", double? size = null, FontWeight? weight = null)
        {
            var t = new TextBlock { Text = s, TextWrapping = TextWrapping.Wrap, Style = Style(style) };
            if (size is double sz) t.FontSize = sz;
            if (weight is FontWeight w) t.FontWeight = w;
            return t;
        }

        public static Button Button(string label, string style, Action onClick, string? automation = null)
        {
            var b = new Button { Content = label, Style = Style(style), Margin = new Thickness(0, 0, 6, 0), Padding = new Thickness(10, 4, 10, 4) };
            System.Windows.Automation.AutomationProperties.SetName(b, automation ?? label);
            b.Click += (_, _) => onClick();
            return b;
        }

        public static string Ago(DateTimeOffset t)
        {
            var d = DateTimeOffset.Now - t;
            if (d.TotalMinutes < 1) return "now";
            if (d.TotalHours < 1) return $"{(int)d.TotalMinutes} min";
            if (d.TotalDays < 1) return $"{(int)d.TotalHours} h";
            return t.LocalDateTime.ToString("d MMM");
        }
    }

    /// <summary>The "Phone notifications" window: recent notifications grouped by app, with reply, buttons and clear.</summary>
    public sealed class PhoneNotifsWindow : Window
    {
        private static PhoneNotifsWindow? _open;
        private readonly StackPanel _list = new();
        private readonly StackPanel _hints = new();
        private string? _replyingId;

        /// <summary>UI thread.</summary>
        public static void ShowWindow()
        {
            PhoneNotifHub.Unseen = 0;
            if (_open != null) { _open.Activate(); return; }
            _open = new PhoneNotifsWindow();
            _open.Closed += (_, _) => _open = null;
            _open.Show();
            _open.Activate();
        }

        private PhoneNotifsWindow()
        {
            Title = "Phone notifications";
            Width = 420; Height = 600; MinWidth = 340; MinHeight = 320;
            Background = Ui.Res("AB.Background", Brushes.Black);
            var wa = SystemParameters.WorkArea;
            Left = wa.Right - Width - 16; Top = wa.Bottom - Height - 16;

            var sounds = new CheckBox { Content = "Sounds", IsChecked = PhoneSettings.Sounds, Foreground = Ui.Res("AB.MutedForeground", Brushes.Gray), VerticalAlignment = VerticalAlignment.Center };
            sounds.Click += (_, _) => PhoneSettings.Sounds = sounds.IsChecked == true;
            var header = new DockPanel { Margin = new Thickness(16, 14, 16, 6) };
            DockPanel.SetDock(sounds, Dock.Right);
            header.Children.Add(sounds);
            header.Children.Add(Ui.Text("Phone notifications", "AB.Text", 16, FontWeights.SemiBold));

            var root = new DockPanel();
            DockPanel.SetDock(header, Dock.Top);
            root.Children.Add(header);
            _hints.Margin = new Thickness(16, 0, 16, 6);
            DockPanel.SetDock(_hints, Dock.Top);
            root.Children.Add(_hints);
            root.Children.Add(new ScrollViewer { Content = _list, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(12, 0, 12, 12) });
            Content = root;

            Action onChange = () => Dispatcher.BeginInvoke(new Action(Rebuild));
            PhoneNotifHub.Changed += onChange;
            Closed += (_, _) => PhoneNotifHub.Changed -= onChange;
            Activated += (_, _) => PhoneNotifHub.Unseen = 0;
            Rebuild();
        }

        private void Rebuild()
        {
            _hints.Children.Clear();
            foreach (var p in PhoneNotifHub.SourcePhones())
                if (PhoneNotifHub.States.TryGetValue(p.Id, out var st) && !st.Notifs)
                    _hints.Children.Add(Ui.Text(st.Why == "no_access"
                        ? $"Allow notification access for AirBridge on {p.Name} to see its notifications here."
                        : $"Turn on “Show phone notifications on your computers” in AirBridge on {p.Name}.", "AB.Text.Caption"));
            if (!PhoneSettings.ShowNotifs)
                _hints.Children.Add(Ui.Text("Phone notifications are turned off on this PC (tray menu).", "AB.Text.Caption"));

            _list.Children.Clear();
            var groups = PhoneNotifHub.Store.Groups();
            if (groups.Count == 0)
            {
                var empty = Ui.Text(PhoneNotifHub.SourcePhones().Count == 0
                    ? "No notifications. Connect your phone and turn on notifications in AirBridge on the phone."
                    : "No notifications. New ones from your phone show up here.", "AB.Text.Caption");
                empty.TextAlignment = TextAlignment.Center;
                empty.Margin = new Thickness(24, 60, 24, 0);
                _list.Children.Add(empty);
                return;
            }
            bool several = groups.Select(g => g.PhoneId).Distinct().Count() > 1;
            foreach (var g in groups) _list.Children.Add(GroupCard(g, several));
        }

        private UIElement GroupCard(PhoneNotifStore.Group g, bool showPhone)
        {
            var card = new Border
            {
                Background = Ui.Res("AB.Card", Brushes.DimGray), CornerRadius = new CornerRadius(10),
                Padding = new Thickness(12, 10, 12, 8), Margin = new Thickness(0, 0, 0, 10),
            };
            var stack = new StackPanel();
            var head = new DockPanel { Margin = new Thickness(0, 0, 0, 6) };
            bool connected = PhoneNotifHub.IsConnected(g.PhoneId);
            if (connected)
            {
                var clear = Ui.Button("Clear", "AB.Button.Ghost", () => { foreach (var n in g.Items.ToList()) PhoneNotifHub.Dismiss(n); }, $"Clear {g.App} notifications");
                DockPanel.SetDock(clear, Dock.Right);
                head.Children.Add(clear);
            }
            else
            {
                var off = Ui.Text("Not connected", "AB.Text.Caption");
                DockPanel.SetDock(off, Dock.Right);
                head.Children.Add(off);
            }
            var title = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var icon = AppIcon(g.Pkg);
            if (icon != null) title.Children.Add(icon);
            title.Children.Add(Ui.Text(g.App, "AB.Text", null, FontWeights.SemiBold));
            if (showPhone) { var p = Ui.Text("  " + PhoneNotifHub.PhoneName(g.PhoneId), "AB.Text.Caption"); p.VerticalAlignment = VerticalAlignment.Center; title.Children.Add(p); }
            head.Children.Add(title);
            stack.Children.Add(head);
            foreach (var n in g.Items.Take(5)) stack.Children.Add(Row(n, connected));
            if (g.Items.Count > 5) stack.Children.Add(Ui.Text($"{g.Items.Count - 5} more on the phone", "AB.Text.Caption"));
            card.Child = stack;
            card.Opacity = connected ? 1 : 0.6;
            return card;
        }

        private static Image? AppIcon(string pkg)
        {
            string path = PhoneNotifHub.IconPath(pkg);
            if (!File.Exists(path)) return null;
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.UriSource = new Uri(path);
                bmp.DecodePixelWidth = 40;
                bmp.EndInit();
                return new Image { Source = bmp, Width = 18, Height = 18, Margin = new Thickness(0, 0, 8, 0) };
            }
            catch { return null; }
        }

        private UIElement Row(PhoneNotif n, bool connected)
        {
            var row = new StackPanel { Margin = new Thickness(0, 4, 0, 6) };
            var top = new DockPanel();
            var when = Ui.Text(Ui.Ago(n.Ts), "AB.Text.Caption");
            DockPanel.SetDock(when, Dock.Right);
            top.Children.Add(when);
            if (connected)
            {
                var x = Ui.Button("✕", "AB.Button.Ghost", () => PhoneNotifHub.Dismiss(n), "Clear");
                x.Padding = new Thickness(6, 0, 6, 0);
                DockPanel.SetDock(x, Dock.Right);
                top.Children.Add(x);
            }
            var head = Ui.Text(n.Conv != null && n.Conv != n.Title ? $"{n.Headline} · {n.Conv}" : n.Headline, "AB.Text", null, FontWeights.Medium);
            head.TextTrimming = TextTrimming.CharacterEllipsis; head.TextWrapping = TextWrapping.NoWrap;
            top.Children.Add(head);
            row.Children.Add(top);

            if (n.Msgs.Count > 1)
                foreach (var m in n.Msgs.Skip(Math.Max(0, n.Msgs.Count - 4)))
                    row.Children.Add(Ui.Text((m.From == null ? "You: " : m.From.Length > 0 ? m.From + ": " : "") + m.Text, "AB.Text.Caption"));
            else if (n.Body.Length > 0)
            {
                var body = Ui.Text(n.Body, "AB.Text");
                body.MaxHeight = 80; body.TextTrimming = TextTrimming.CharacterEllipsis; body.Opacity = 0.85;
                row.Children.Add(body);
            }

            if (!connected) return row;
            if (_replyingId == n.Id)
            {
                var box = new TextBox { Style = Ui.Style("AB.TextBox"), AcceptsReturn = false, MinWidth = 200, Margin = new Thickness(0, 6, 6, 0) };
                System.Windows.Automation.AutomationProperties.SetName(box, "Reply");
                void Send() { PhoneNotifHub.Reply(n, box.Text); _replyingId = null; Rebuild(); }
                box.KeyDown += (_, e) =>
                {
                    if (e.Key == Key.Enter) { Send(); e.Handled = true; }
                    else if (e.Key == Key.Escape) { _replyingId = null; Rebuild(); e.Handled = true; }
                };
                var line = new DockPanel();
                var send = Ui.Button("Send", "AB.Button.Secondary", Send);
                send.Margin = new Thickness(0, 6, 0, 0);
                DockPanel.SetDock(send, Dock.Right);
                line.Children.Add(send);
                line.Children.Add(box);
                row.Children.Add(line);
                Dispatcher.BeginInvoke(new Action(() => box.Focus()), System.Windows.Threading.DispatcherPriority.Input);
            }
            else if (n.CanReply || n.Actions.Count > 0)
            {
                var buttons = new WrapPanel { Margin = new Thickness(0, 6, 0, 0) };
                if (n.CanReply) buttons.Children.Add(Ui.Button(n.ReplyLabel ?? "Reply", "AB.Button.Secondary", () => { _replyingId = n.Id; Rebuild(); }));
                foreach (var a in n.Actions) buttons.Children.Add(Ui.Button(a.Label, "AB.Button.Secondary", () => PhoneNotifHub.Act(n, a)));
                row.Children.Add(buttons);
            }
            var menu = new ContextMenu();
            var open = new MenuItem { Header = "Open on phone" };
            open.Click += (_, _) => PhoneNotifHub.OpenOnPhone(n);
            menu.Items.Add(open);
            row.ContextMenu = menu;
            return row;
        }
    }
}
