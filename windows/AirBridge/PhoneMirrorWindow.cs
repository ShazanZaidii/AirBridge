using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Shell;
using System.Windows.Threading;
using AirBridge.Input;

namespace AirBridge
{
    /// <summary>One mirror window per phone (§8.7, phone as source). Only reachable while a decoder exists
    /// (<see cref="PhoneMirrorHub.Available"/>).</summary>
    public static class PhoneMirrorWindows
    {
        private static readonly Dictionary<string, PhoneMirrorWindow> Open = new();

        /// <summary>UI thread.</summary>
        public static void Show(PeerInfo peer)
        {
            if (Open.TryGetValue(peer.Id, out var w)) { w.BringForward(); return; }
            w = new PhoneMirrorWindow(peer);
            Open[peer.Id] = w;
            w.Closed += (_, _) => Open.Remove(peer.Id);
            w.Show();
            w.Activate();
        }
    }

    /// <summary>
    /// Phone-shaped borderless window: a slim bar above the picture (close, minimise, phone name, Back / Home /
    /// Recents), the picture with the phone's corner radius, and the status overlays. Click = tap, drag = swipe,
    /// wheel = scroll, right-click or Esc = Back, Ctrl+1 Home, Ctrl+2 Recents, keys go to the phone.
    /// </summary>
    public sealed class PhoneMirrorWindow : Window
    {
        private const double BarHeight = 40, Gap = 6;
        private readonly PeerInfo _peer;
        private readonly PhoneMirrorSession _session;
        private readonly Border _phone = new();
        private readonly Grid _picture = new() { Background = Brushes.Black };
        private readonly Image _image = new() { Stretch = Stretch.Uniform };
        private readonly TextBlock _title = new(), _sub = new(), _hint = new();
        private readonly StackPanel _center = new() { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
        private readonly Border _hintPill = new();
        private readonly Button _retry = new() { Content = "Try Again" };
        private readonly Border _bar = new();
        private readonly Ellipse _pointer = new() { Width = 22, Height = 22, IsHitTestVisible = false, Visibility = Visibility.Collapsed };
        private readonly Canvas _pointerLayer = new() { IsHitTestVisible = false };
        private WriteableBitmap? _bitmap;
        private PhoneMirrorState _state = new();
        private double _aspect = 9.0 / 19.5;
        private bool _buttonDown, _adjusting;
        private long _lastAbs;
        private readonly HashSet<int> _heldHids = new();
        private DispatcherTimer? _hintTimer;

        public PhoneMirrorWindow(PeerInfo peer)
        {
            _peer = peer;
            _session = new PhoneMirrorSession(peer.Id);
            if (PhoneMirrorHub.IsComputer(peer)) _aspect = 16.0 / 10.0; // until it says its real size
            Title = peer.Name;
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            ResizeMode = ResizeMode.CanResize;
            WindowChrome.SetWindowChrome(this, new WindowChrome { CaptionHeight = 0, GlassFrameThickness = new Thickness(0), ResizeBorderThickness = new Thickness(6), UseAeroCaptionButtons = false });
            double h = Math.Min(SystemParameters.WorkArea.Height * 0.72, 820);
            Height = h + BarHeight + Gap;
            Width = Math.Round(h * _aspect);
            MinWidth = 180;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Content = Build();
            SizeChanged += (_, _) => KeepAspect();
            Loaded += (_, _) => { _session.Start(); Focus(); };
            Deactivated += (_, _) => ReleaseAll();
            Closed += (_, _) => { ReleaseAll(); _session.Stop(); PhoneMirrorHub.Unregister(_session); };
            _session.StateChanged += s => Dispatcher.BeginInvoke(() => Apply(s));
            _session.Frame += (w, hh, stride, px) => Dispatcher.BeginInvoke(DispatcherPriority.Render, () => ShowFrame(w, hh, stride, px));
            PhoneMirrorHub.Register(_session);
            PreviewKeyDown += OnKeyDown;
            PreviewKeyUp += OnKeyUp;
            Opacity = 0;
            Loaded += (_, _) => BeginAnimation(OpacityProperty, new System.Windows.Media.Animation.DoubleAnimation(1, TimeSpan.FromMilliseconds(260))
            { EasingFunction = new System.Windows.Media.Animation.CubicEase { EasingMode = System.Windows.Media.Animation.EasingMode.EaseOut } });
        }

        public void BringForward()
        {
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
            if (_state.Phase == PhoneMirrorPhase.Stopped) Restart();
        }

        private UIElement Build()
        {
            var root = new Grid();
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(BarHeight) });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(Gap) });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            // Bar: drag to move.
            _bar.Background = new SolidColorBrush(Color.FromArgb(0xE6, 0x2A, 0x2A, 0x2C));
            _bar.CornerRadius = new CornerRadius(15);
            _bar.Height = 30;
            _bar.MaxWidth = 360;
            _bar.BorderBrush = new SolidColorBrush(Color.FromArgb(0x1F, 0xFF, 0xFF, 0xFF));
            _bar.BorderThickness = new Thickness(0.5);
            _bar.MouseLeftButtonDown += (_, e) => { if (e.ButtonState == MouseButtonState.Pressed) DragMove(); };
            var bg = new Grid();
            bg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            bg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            bg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(6, 0, 0, 0) };
            left.Children.Add(BarButton("", "Close", Close));
            left.Children.Add(BarButton("", "Minimise", () => WindowState = WindowState.Minimized));
            var name = new TextBlock { Text = _peer.Name, Foreground = new SolidColorBrush(Color.FromRgb(0xB0, 0xB0, 0xB5)), FontSize = 12, FontWeight = FontWeights.Medium,
                VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
            Grid.SetColumn(name, 1);
            var right = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 6, 0) };
            if (!PhoneMirrorHub.IsComputer(_peer)) // a computer has its own; these are the phone's
            {
                right.Children.Add(BarButton("", "Back", () => SendAction("back")));
                right.Children.Add(BarButton("", "Home", () => SendAction("home")));
                right.Children.Add(BarButton("", "Recent apps", () => SendAction("recents")));
            }
            Grid.SetColumn(right, 2);
            bg.Children.Add(left); bg.Children.Add(name); bg.Children.Add(right);
            _bar.Child = bg;
            root.Children.Add(_bar);

            // Phone picture.
            _phone.Background = new SolidColorBrush(Color.FromRgb(0x14, 0x14, 0x14));
            _phone.BorderBrush = new SolidColorBrush(Color.FromArgb(0x17, 0xFF, 0xFF, 0xFF));
            _phone.BorderThickness = new Thickness(1);
            _phone.SnapsToDevicePixels = true;
            RenderOptions.SetBitmapScalingMode(_image, BitmapScalingMode.HighQuality);
            _picture.Children.Add(_image);
            _title.Foreground = Brushes.White; _title.FontSize = 14; _title.FontWeight = FontWeights.SemiBold; _title.TextAlignment = TextAlignment.Center; _title.TextWrapping = TextWrapping.Wrap;
            _sub.Foreground = new SolidColorBrush(Color.FromArgb(0xB3, 0xFF, 0xFF, 0xFF)); _sub.FontSize = 12; _sub.TextAlignment = TextAlignment.Center; _sub.TextWrapping = TextWrapping.Wrap; _sub.Margin = new Thickness(0, 4, 0, 0);
            _retry.Margin = new Thickness(0, 14, 0, 0);
            _retry.Padding = new Thickness(16, 6, 16, 6);
            _retry.HorizontalAlignment = HorizontalAlignment.Center;
            _retry.Click += (_, _) => Restart();
            _center.Margin = new Thickness(24);
            _center.Children.Add(_title); _center.Children.Add(_sub); _center.Children.Add(_retry);
            _picture.Children.Add(_center);
            _hint.Foreground = Brushes.White; _hint.FontSize = 11.5; _hint.TextWrapping = TextWrapping.Wrap; _hint.TextAlignment = TextAlignment.Center;
            _hintPill.Child = _hint;
            _hintPill.Background = new SolidColorBrush(Color.FromArgb(0x9E, 0, 0, 0));
            _hintPill.CornerRadius = new CornerRadius(14);
            _hintPill.Padding = new Thickness(12, 7, 12, 7);
            _hintPill.Margin = new Thickness(14, 0, 14, 18);
            _hintPill.VerticalAlignment = VerticalAlignment.Bottom;
            _hintPill.HorizontalAlignment = HorizontalAlignment.Center;
            _hintPill.IsHitTestVisible = false;
            _picture.Children.Add(_hintPill);
            _pointer.Fill = new SolidColorBrush(Color.FromArgb(0x6B, 0x8C, 0x8C, 0x8C));
            _pointer.Stroke = new SolidColorBrush(Color.FromArgb(0xD9, 0xFF, 0xFF, 0xFF));
            _pointer.StrokeThickness = 1.5;
            _pointerLayer.Children.Add(_pointer);
            _picture.Children.Add(_pointerLayer);
            _phone.Child = _picture;
            _phone.SizeChanged += (_, _) => UpdateCorner();
            Grid.SetRow(_phone, 2);
            root.Children.Add(_phone);

            _picture.MouseLeftButtonDown += OnDown;
            _picture.MouseMove += OnMove;
            _picture.MouseLeftButtonUp += OnUp;
            _picture.MouseRightButtonDown += (_, e) => { SendAction("back"); e.Handled = true; };
            _picture.MouseWheel += OnWheel;
            _picture.MouseLeave += (_, _) => _pointer.Visibility = Visibility.Collapsed;
            Apply(_state);
            return root;
        }

        private Button BarButton(string glyph, string label, Action onClick)
        {
            var b = new Button
            {
                Content = new TextBlock { Text = glyph, FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"), FontSize = 11, Foreground = new SolidColorBrush(Color.FromRgb(0xB0, 0xB0, 0xB5)) },
                Width = 26, Height = 26, Background = Brushes.Transparent, BorderThickness = new Thickness(0), ToolTip = label, Focusable = false,
            };
            AutomationProperties_SetName(b, label);
            b.Click += (_, _) => onClick();
            return b;
        }

        private static void AutomationProperties_SetName(DependencyObject d, string name) => System.Windows.Automation.AutomationProperties.SetName(d, name);

        private void Restart()
        {
            _bitmap = null;
            _image.Source = null;
            _session.Start();
        }

        // ---------------------------------------------------------------- state

        private void Apply(PhoneMirrorState s)
        {
            _state = s;
            if (s.DisplayW > 0 && s.DisplayH > 0)
            {
                double a = (double)s.DisplayW / s.DisplayH;
                if (Math.Abs(a - _aspect) > 0.002) Reshape(a);
            }
            UpdateCorner();
            bool overlay = true;
            _retry.Visibility = Visibility.Collapsed;
            _sub.Visibility = Visibility.Visible;
            switch (s.Phase)
            {
                case PhoneMirrorPhase.Connecting: _title.Text = $"Connecting to {_peer.Name}…"; _sub.Visibility = Visibility.Collapsed; break;
                case PhoneMirrorPhase.WaitingForConsent: _title.Text = "Allow on your phone"; _sub.Text = $"Tap Allow on {_peer.Name} to show its screen here."; break;
                case PhoneMirrorPhase.Streaming:
                    if (s.Locked) { _title.Text = "Phone is locked"; _sub.Text = $"Unlock {_peer.Name} to continue."; }
                    else if (!s.HasPicture) { _title.Text = "Starting…"; _sub.Visibility = Visibility.Collapsed; }
                    else overlay = false;
                    break;
                case PhoneMirrorPhase.Stopped:
                    _title.Text = s.StopText ?? "Stopped"; _sub.Visibility = Visibility.Collapsed; _retry.Visibility = Visibility.Visible;
                    break;
            }
            _center.Visibility = overlay ? Visibility.Visible : Visibility.Collapsed;
            _picture.Background = overlay && !s.Locked ? new SolidColorBrush(Color.FromRgb(0x1A, 0x1A, 0x1A)) : Brushes.Black;
            _image.Opacity = s.Locked ? 0.45 : 1;
            UpdateHint();
            if (!CanControl) ReleaseAll();
        }

        private void UpdateHint(string? transient = null)
        {
            string? text = transient;
            if (text == null && _state.Phase == PhoneMirrorPhase.Streaming && _state.HasPicture && !_state.Control && !_state.Locked)
                text = "Turn on AirBridge Universal Control on the phone to control it";
            _hint.Text = text ?? "";
            _hintPill.Visibility = text == null ? Visibility.Collapsed : Visibility.Visible;
        }

        private void Flash(string text)
        {
            UpdateHint(text);
            _hintTimer?.Stop();
            _hintTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
            _hintTimer.Tick += (_, _) => { _hintTimer?.Stop(); UpdateHint(); };
            _hintTimer.Start();
        }

        private void UpdateCorner()
        {
            double w = _phone.ActualWidth, h = _phone.ActualHeight;
            if (w <= 0 || h <= 0) return;
            double shortSide = Math.Min(w, h), dispShort = Math.Min(_state.DisplayW, _state.DisplayH);
            double r = _state.Corner > 0 && dispShort > 0 ? _state.Corner * shortSide / dispShort : shortSide * 0.1;
            r = Math.Min(r, shortSide / 4);
            _phone.CornerRadius = new CornerRadius(r);
            _picture.Clip = new RectangleGeometry(new Rect(0, 0, Math.Max(0, w - 2), Math.Max(0, h - 2)), Math.Max(0, r - 1), Math.Max(0, r - 1));
        }

        private void Reshape(double a)
        {
            double phoneW = Width, phoneH = Height - BarHeight - Gap;
            bool rotated = (a > 1) != (_aspect > 1);
            _aspect = a;
            double w, h;
            if (rotated) { w = phoneH; h = w / a; } else { h = phoneH; w = h * a; }
            var area = SystemParameters.WorkArea;
            if (h > area.Height - BarHeight - Gap - 20) { h = area.Height - BarHeight - Gap - 20; w = h * a; }
            if (w > area.Width - 40) { w = area.Width - 40; h = w / a; }
            double cx = Left + Width / 2, cy = Top + Height / 2;
            _adjusting = true;
            Width = Math.Round(w);
            Height = Math.Round(h + BarHeight + Gap);
            Left = Math.Max(area.Left, cx - Width / 2);
            Top = Math.Max(area.Top, cy - Height / 2);
            _adjusting = false;
        }

        private void KeepAspect()
        {
            if (_adjusting) return;
            double want = Math.Round(Width / _aspect + BarHeight + Gap);
            if (Math.Abs(want - Height) < 1) return;
            _adjusting = true;
            Height = want;
            _adjusting = false;
        }

        private void ShowFrame(int w, int h, int stride, byte[] px)
        {
            if (_bitmap == null || _bitmap.PixelWidth != w || _bitmap.PixelHeight != h)
            {
                _bitmap = new WriteableBitmap(w, h, 96, 96, PixelFormats.Bgra32, null);
                _image.Source = _bitmap;
            }
            _bitmap.WritePixels(new Int32Rect(0, 0, w, h), px, stride, 0);
        }

        // ---------------------------------------------------------------- input

        private bool CanControl => _state.Phase == PhoneMirrorPhase.Streaming && _state.HasPicture && _state.Control;

        private void Send(JsonObject m) => PeerBus.Send(_peer.Id, m);

        private void SendAction(string name)
        {
            if (!CanControl) { ViewOnlyHint(); return; }
            Send(new JsonObject { ["t"] = "action", ["name"] = name });
        }

        private void ViewOnlyHint()
        {
            if (_state.Phase == PhoneMirrorPhase.Streaming && _state.HasPicture && !_state.Control)
                Flash("Turn on AirBridge Universal Control on the phone to control it");
        }

        private (double x, double y) Point(MouseEventArgs e)
        {
            var p = e.GetPosition(_picture);
            return PhoneMirrorGeometry.Normalize(p.X, p.Y, _picture.ActualWidth, _picture.ActualHeight);
        }

        private void SendAbs((double x, double y) p)
        {
            _lastAbs = Environment.TickCount64;
            Send(new JsonObject { ["t"] = "ptr_abs", ["x"] = p.x, ["y"] = p.y });
        }

        private void OnDown(object sender, MouseButtonEventArgs e)
        {
            Focus();
            if (!CanControl) { ViewOnlyHint(); return; }
            _picture.CaptureMouse();
            SendAbs(Point(e));
            Send(new JsonObject { ["t"] = "ptr_btn", ["b"] = 0, ["down"] = true });
            _buttonDown = true;
            e.Handled = true;
        }

        private void OnMove(object sender, MouseEventArgs e)
        {
            var pos = e.GetPosition(_picture);
            if (CanControl)
            {
                _picture.Cursor = Cursors.None;
                _pointer.Visibility = Visibility.Visible;
                Canvas.SetLeft(_pointer, pos.X - _pointer.Width / 2);
                Canvas.SetTop(_pointer, pos.Y - _pointer.Height / 2);
            }
            else { _picture.Cursor = Cursors.Arrow; _pointer.Visibility = Visibility.Collapsed; }
            // ≤ 120 moves/s (§8.2).
            if (_buttonDown && Environment.TickCount64 - _lastAbs >= 8) SendAbs(Point(e));
        }

        private void OnUp(object sender, MouseButtonEventArgs e)
        {
            if (!_buttonDown) return;
            SendAbs(Point(e));
            Send(new JsonObject { ["t"] = "ptr_btn", ["b"] = 0, ["down"] = false });
            _buttonDown = false;
            _picture.ReleaseMouseCapture();
            e.Handled = true;
        }

        private void OnWheel(object sender, MouseWheelEventArgs e)
        {
            if (!CanControl || _buttonDown) return;
            SendAbs(Point(e));
            var d = PhoneMirrorGeometry.Wheel(0, e.Delta, _picture.ActualWidth, _state.DisplayW);
            Send(new JsonObject { ["t"] = "ptr_scroll", ["dx"] = d.dx, ["dy"] = d.dy, ["px"] = d.px });
            e.Handled = true;
        }

        private static int Mods()
        {
            int m = 0;
            var k = Keyboard.Modifiers;
            if ((k & ModifierKeys.Shift) != 0) m |= 1;
            if ((k & ModifierKeys.Control) != 0) m |= 2;
            if ((k & ModifierKeys.Alt) != 0) m |= 4;
            if ((k & ModifierKeys.Windows) != 0) m |= 8;
            if (Keyboard.IsKeyToggled(Key.CapsLock)) m |= 16;
            return m;
        }

        private void OnKeyDown(object sender, KeyEventArgs e)
        {
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            bool ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            if (ctrl && key == Key.W) { Close(); e.Handled = true; return; }
            if (ctrl && key == Key.D1) { SendAction("home"); e.Handled = true; return; }
            if (ctrl && key == Key.D2) { SendAction("recents"); e.Handled = true; return; }
            e.Handled = true;
            if (!CanControl) { ViewOnlyHint(); return; }
            int vk = KeyInterop.VirtualKeyFromKey(key);
            int hid = KeyMap.ToHid((uint)vk, 0, KeyMap.IsExtendedVk(vk));
            if (hid == 0) return;
            int mods = Mods();
            var msg = new JsonObject { ["t"] = "key", ["hid"] = hid, ["down"] = true, ["mods"] = mods };
            if (e.IsRepeat) msg["rep"] = true;
            string? text = (mods & (2 | 8)) == 0 ? TextFor((uint)vk) : null;
            if (text != null)
            {
                msg["text"] = text;
                if (!_state.Typing) Flash("Turn on AirBridge Typing & Clipboard on the phone to type here");
            }
            _heldHids.Add(hid);
            Send(msg);
        }

        private void OnKeyUp(object sender, KeyEventArgs e)
        {
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            int vk = KeyInterop.VirtualKeyFromKey(key);
            int hid = KeyMap.ToHid((uint)vk, 0, KeyMap.IsExtendedVk(vk));
            e.Handled = true;
            if (hid == 0 || !_heldHids.Remove(hid)) return;
            Send(new JsonObject { ["t"] = "key", ["hid"] = hid, ["down"] = false, ["mods"] = Mods() });
        }

        [DllImport("user32.dll")]
        private static extern bool GetKeyboardState(byte[] lpKeyState);

        /// <summary>Character(s) the key produces with the current layout and modifiers, if printable.</summary>
        private static string? TextFor(uint vk)
        {
            try
            {
                var state = new byte[256];
                if (!GetKeyboardState(state)) return null;
                var buf = new char[8];
                // Flag 4: don't change the keyboard state (dead keys stay pending for the real input).
                int n = NativeMethods.ToUnicodeEx(vk, NativeMethods.MapVirtualKey(vk, 0), state, buf, buf.Length, 4, NativeMethods.GetKeyboardLayout(0));
                if (n <= 0) return null;
                var s = new string(buf, 0, n);
                foreach (char c in s) if (c < 0x20 || c == 0x7F) return null;
                return s;
            }
            catch { return null; }
        }

        /// <summary>Focus lost, window closing, control gone: everything held comes back up on the phone.</summary>
        private void ReleaseAll()
        {
            if (_buttonDown)
            {
                _buttonDown = false;
                Send(new JsonObject { ["t"] = "ptr_btn", ["b"] = 0, ["down"] = false });
                if (_picture.IsMouseCaptured) _picture.ReleaseMouseCapture();
            }
            foreach (int hid in _heldHids) Send(new JsonObject { ["t"] = "key", ["hid"] = hid, ["down"] = false, ["mods"] = 0 });
            _heldHids.Clear();
        }
    }
}
