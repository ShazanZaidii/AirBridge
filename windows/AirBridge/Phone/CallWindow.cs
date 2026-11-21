using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace AirBridge.Phone
{
    /// <summary>
    /// Small always-on-top call window (bottom right, never takes focus), with the same content as the Mac's: the
    /// caller's round photo or initials, name, "Mobile · phone name", round Decline / Accept while ringing, then the
    /// duration with Mute, Speaker (the phone's real state, §13.5) and End. Rings per <see cref="CallRinger"/>.
    /// </summary>
    public sealed class CallWindow : Window
    {
        private static readonly Dictionary<string, CallWindow> Open = new();
        private static readonly Dictionary<string, (string id, BitmapImage img)> Photos = new();

        private PhoneCall _call;
        private readonly Border _avatar = new() { Width = 44, Height = 44, CornerRadius = new CornerRadius(22), ClipToBounds = true };
        private readonly TextBlock _caller, _sub, _note;
        private readonly StackPanel _buttons = new() { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
        private readonly Button _close;
        private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
        private bool _ended;

        private static readonly Brush Red = new SolidColorBrush(Color.FromRgb(0xFF, 0x3B, 0x30));
        private static readonly Brush Green = new SolidColorBrush(Color.FromRgb(0x34, 0xC7, 0x59));

        /// <summary>UI thread.</summary>
        public static void ShowOrUpdate(PhoneCall c, bool isNew)
        {
            if (Open.TryGetValue(c.PhoneId, out var w)) { w.Update(c); return; }
            w = new CallWindow(c);
            Open[c.PhoneId] = w;
            w.Closed += (_, _) => { if (Open.TryGetValue(c.PhoneId, out var cur) && cur == w) Open.Remove(c.PhoneId); };
            w.Show();
        }

        /// <summary>UI thread.</summary>
        public static void End(PhoneCall c)
        {
            if (Open.TryGetValue(c.PhoneId, out var w)) w.ShowEnded();
            if (Photos.TryGetValue(c.PhoneId, out var p) && p.id == c.Id) Photos.Remove(c.PhoneId);
        }

        /// <summary>UI thread.</summary>
        public static void Flash(string phone, string text)
        {
            if (Open.TryGetValue(phone, out var w)) { w._note.Text = text; w._note.Visibility = Visibility.Visible; }
            else App.Notify("AirBridge", text);
        }

        /// <summary>`call_photo` (may arrive just before its call). UI thread.</summary>
        public static void SetPhoto(string phone, string id, byte[] jpg)
        {
            try
            {
                var img = new BitmapImage();
                img.BeginInit();
                img.CacheOption = BitmapCacheOption.OnLoad;
                img.StreamSource = new MemoryStream(jpg);
                img.EndInit();
                img.Freeze();
                Photos[phone] = (id, img);
                if (Open.TryGetValue(phone, out var w) && w._call.Id == id) w.RenderAvatar();
            }
            catch (Exception ex) { Net.Log.Warn($"Caller photo: {ex.Message}"); }
        }

        private CallWindow(PhoneCall c)
        {
            _call = c;
            Title = "Call";
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            ShowActivated = false;
            ShowInTaskbar = false;
            SizeToContent = SizeToContent.WidthAndHeight;

            _caller = Ui.Text("", "AB.Text", 15, FontWeights.SemiBold);
            _caller.TextTrimming = TextTrimming.CharacterEllipsis; _caller.TextWrapping = TextWrapping.NoWrap;
            _sub = Ui.Text("", "AB.Text.Caption");
            _sub.TextTrimming = TextTrimming.CharacterEllipsis; _sub.TextWrapping = TextWrapping.NoWrap;
            _note = Ui.Text("", "AB.Text.Caption");
            _note.Margin = new Thickness(0, 10, 0, 0);
            _note.Visibility = Visibility.Collapsed;

            _close = new Button
            {
                Content = new TextBlock { Text = "", FontFamily = new FontFamily("Segoe MDL2 Assets"), FontSize = 9 },
                Width = 20, Height = 20, Padding = new Thickness(0), Style = Ui.Style("AB.Button.Ghost"),
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(4, 4, 0, 0), ToolTip = "Close",
            };
            System.Windows.Automation.AutomationProperties.SetName(_close, "Close");
            _close.Click += (_, _) =>
            {
                // Hides the window; the call keeps ringing on the phone.
                CallRinger.Dismiss(_call.PhoneId);
                Close();
            };

            var names = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 12, 0) };
            names.Children.Add(_caller);
            names.Children.Add(_sub);

            var row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Grid.SetColumn(_avatar, 0); Grid.SetColumn(names, 1); Grid.SetColumn(_buttons, 2);
            row.Children.Add(_avatar); row.Children.Add(names); row.Children.Add(_buttons);

            var stack = new StackPanel();
            stack.Children.Add(row);
            stack.Children.Add(_note);

            var card = new Border
            {
                Width = 380, Padding = new Thickness(14, 12, 14, 12), CornerRadius = new CornerRadius(14),
                Background = Ui.Res("AB.Card", Brushes.DimGray), BorderBrush = Ui.Res("AB.Border", Brushes.Gray), BorderThickness = new Thickness(1),
                Child = stack,
            };
            var root = new Grid { Margin = new Thickness(12) };
            root.Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 24, Opacity = 0.35, ShadowDepth = 6, Direction = 270 };
            root.Children.Add(card);
            root.Children.Add(_close);
            Content = root;
            card.MouseLeftButtonDown += (_, e) => { if (e.ButtonState == MouseButtonState.Pressed) try { DragMove(); } catch { } };

            _timer.Tick += (_, _) => RenderSub();
            Loaded += (_, _) =>
            {
                var wa = SystemParameters.WorkArea;
                Left = wa.Right - ActualWidth - 4;
                Top = wa.Bottom - ActualHeight - 4 - Open.Keys.ToList().IndexOf(_call.PhoneId) * (ActualHeight - 8);
                Opacity = 0;
                var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
                BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220)) { EasingFunction = ease });
            };
            Closed += (_, _) => _timer.Stop();
            Update(c);
        }

        private void Update(PhoneCall c)
        {
            bool newCall = c.Id != _call.Id;
            _call = c;
            _ended = false;
            _caller.Text = c.Caller;
            System.Windows.Automation.AutomationProperties.SetName(this, c.State == PhoneCallState.Ringing ? $"Incoming call from {c.Caller}" : $"Call with {c.Caller}");
            if (newCall) _note.Visibility = Visibility.Collapsed;
            RenderAvatar();
            RenderSub();
            if (c.State == PhoneCallState.Active && c.Since != null) _timer.Start(); else _timer.Stop();
            _close.Visibility = c.State == PhoneCallState.Ringing ? Visibility.Visible : Visibility.Collapsed;

            _buttons.Children.Clear();
            if (c.State == PhoneCallState.Ringing)
            {
                _buttons.Children.Add(Round("", "Decline", Red, c.Can.Contains("decline"), () => PhoneNotifHub.CallAction(_call, "decline")));
                _buttons.Children.Add(Round("", "Accept", Green, c.Can.Contains("answer"), () => PhoneNotifHub.CallAction(_call, "answer")));
            }
            else
            {
                _buttons.Children.Add(Toggle("", c.Muted ? "Unmute" : "Mute", c.Muted, c.Can.Contains("mute"), () => PhoneNotifHub.CallAction(_call, _call.Muted ? "unmute" : "mute")));
                _buttons.Children.Add(Toggle("", "Speaker", c.Speaker, c.Can.Contains("speaker"), () => PhoneNotifHub.CallAction(_call, _call.Speaker ? "speaker_off" : "speaker_on")));
                _buttons.Children.Add(Round("", "End", Red, c.Can.Contains("end"), () => PhoneNotifHub.CallAction(_call, "end")));
            }
        }

        private void RenderAvatar()
        {
            if (Photos.TryGetValue(_call.PhoneId, out var p) && p.id == _call.Id)
            {
                _avatar.Background = new ImageBrush(p.img) { Stretch = Stretch.UniformToFill };
                _avatar.Child = null;
                return;
            }
            _avatar.Background = new LinearGradientBrush(Color.FromRgb(0xA8, 0xAD, 0xBA), Color.FromRgb(0x85, 0x8A, 0x96), 90);
            string? initials = CallerText.Initials(_call.Name);
            _avatar.Child = new TextBlock
            {
                Text = initials ?? "", // person glyph when there's no name
                FontFamily = initials == null ? new FontFamily("Segoe MDL2 Assets") : new FontFamily("Segoe UI"),
                FontSize = initials == null ? 20 : (initials.Length > 1 ? 16 : 18), FontWeight = FontWeights.SemiBold,
                Foreground = Brushes.White, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            };
        }

        private void RenderSub()
        {
            string phone = PhoneNotifHub.PhoneName(_call.PhoneId);
            if (_ended) { _sub.Text = "Call ended"; return; }
            string who = CallerText.Subtitle(string.IsNullOrEmpty(_call.Name) ? null : _call.Label, phone);
            _sub.Text = _call.State switch
            {
                PhoneCallState.Ringing => who,
                PhoneCallState.Held => $"On hold · {phone}",
                _ when _call.Since is DateTimeOffset s => $"{CallerText.Duration(DateTimeOffset.Now - s)} · {phone}",
                _ => $"On a call · {phone}",
            };
        }

        private void ShowEnded()
        {
            _ended = true;
            _timer.Stop();
            _buttons.Children.Clear();
            _close.Visibility = Visibility.Collapsed;
            _note.Visibility = Visibility.Collapsed;
            RenderSub();
            var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1400) };
            t.Tick += (_, _) =>
            {
                t.Stop();
                if (!_ended) return; // a new call took over this window
                var fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(200));
                fade.Completed += (_, _) => Close();
                BeginAnimation(OpacityProperty, fade);
            };
            t.Start();
        }

        /// <summary>A round coloured call button (red Decline / End, green Accept) with a phone glyph.</summary>
        private static Button Round(string glyph, string label, Brush fill, bool enabled, Action onClick)
        {
            var face = new Grid { Width = 36, Height = 36 };
            face.Children.Add(new Ellipse { Fill = fill });
            face.Children.Add(new TextBlock
            {
                Text = glyph, FontFamily = new FontFamily("Segoe MDL2 Assets"), FontSize = 15, Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            });
            return Plain(face, label, enabled, onClick);
        }

        /// <summary>A grey round toggle; filled when on (the phone reports it on).</summary>
        private static Button Toggle(string glyph, string label, bool on, bool enabled, Action onClick)
        {
            var fg = Ui.Res("AB.Foreground", Brushes.White);
            var face = new Grid { Width = 36, Height = 36 };
            face.Children.Add(new Ellipse { Fill = on ? fg : Ui.Res("AB.Secondary", Brushes.Gray) });
            face.Children.Add(new TextBlock
            {
                Text = glyph, FontFamily = new FontFamily("Segoe MDL2 Assets"), FontSize = 14,
                Foreground = on ? Ui.Res("AB.Background", Brushes.Black) : fg,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            });
            var b = Plain(face, label, enabled, onClick);
            System.Windows.Automation.AutomationProperties.SetItemStatus(b, on ? "On" : "Off");
            return b;
        }

        private static Button Plain(UIElement face, string label, bool enabled, Action onClick)
        {
            var b = new Button
            {
                Content = face, Margin = new Thickness(8, 0, 0, 0), Padding = new Thickness(0), IsEnabled = enabled,
                Background = Brushes.Transparent, BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
                ToolTip = label, Opacity = enabled ? 1 : 0.45, FocusVisualStyle = null,
                Template = PlainTemplate,
            };
            System.Windows.Automation.AutomationProperties.SetName(b, label);
            b.Click += (_, _) => onClick();
            return b;
        }

        /// <summary>No chrome: just the face, slightly dimmed while pressed.</summary>
        private static readonly ControlTemplate PlainTemplate = MakeTemplate();

        private static ControlTemplate MakeTemplate()
        {
            var t = new ControlTemplate(typeof(Button));
            var cp = new FrameworkElementFactory(typeof(ContentPresenter)) { Name = "cp" };
            t.VisualTree = cp;
            var pressed = new Trigger { Property = System.Windows.Controls.Primitives.ButtonBase.IsPressedProperty, Value = true };
            pressed.Setters.Add(new Setter(UIElement.OpacityProperty, 0.75, "cp"));
            t.Triggers.Add(pressed);
            return t;
        }
    }
}
