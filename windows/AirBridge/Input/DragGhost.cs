using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace AirBridge.Input
{
    /// <summary>
    /// Target side of a carried drag (protocol.md §8.8): a small tilted graphite card with the file's icon, its name (or
    /// "3 items") and size that follows the injected cursor, and after a drop a short "Receiving … from …" line.
    /// Called from the target thread; each call stores the latest request and posts at most one coalesced dispatcher item.
    /// </summary>
    internal static class DragGhost
    {
        private enum Cmd { None, Show, Move, Hide, Drop }

        private static readonly object Gate = new();
        private static Dispatcher? _ui;
        private static Cmd _cmd;
        private static DragGhostState.Info? _info;
        private static int _x, _y;
        private static string? _note;
        private static bool _queued;
        private static GhostWindow? _win;
        private static NoteWindow? _noteWin;

        /// <summary>Call once on the UI thread.</summary>
        public static void Init(Dispatcher ui) => _ui = ui;

        public static void Show(DragGhostState.Info info, int x, int y) => Set(Cmd.Show, info, x, y, null);
        public static void Move(int x, int y) => Set(Cmd.Move, null, x, y, null);
        public static void Hide() => Set(Cmd.Hide, null, 0, 0, null);
        public static void Dropped(string note, int x, int y) => Set(Cmd.Drop, null, x, y, note);

        private static void Set(Cmd c, DragGhostState.Info? info, int x, int y, string? note)
        {
            var ui = _ui;
            if (ui == null || ui.HasShutdownStarted) return;
            lock (Gate)
            {
                // A pending Show/Drop is never downgraded to a plain Move (moves just update the position).
                if (c == Cmd.Move && _cmd is Cmd.Show or Cmd.Drop) { _x = x; _y = y; }
                else
                {
                    _cmd = c;
                    if (info != null) _info = info;
                    if (c != Cmd.Hide) { _x = x; _y = y; }
                    if (note != null) _note = note;
                }
                if (_queued) return;
                _queued = true;
            }
            try { ui.BeginInvoke(DispatcherPriority.Render, new Action(Apply)); }
            catch { lock (Gate) _queued = false; }
        }

        private static void Apply()
        {
            Cmd c; DragGhostState.Info? info; int x, y; string? note;
            lock (Gate)
            {
                c = _cmd; info = _info; x = _x; y = _y; note = _note;
                _cmd = Cmd.None; _note = null; _queued = false;
            }
            try
            {
                switch (c)
                {
                    case Cmd.Show when info != null: (_win ??= new GhostWindow()).ShowFor(info, x, y); break;
                    case Cmd.Move: _win?.Follow(x, y); break;
                    case Cmd.Hide: _win?.FadeOut(dropped: false); break;
                    case Cmd.Drop:
                        _win?.FadeOut(dropped: true);
                        if (note != null) (_noteWin ??= new NoteWindow()).ShowAt(note, x, y);
                        break;
                }
            }
            catch (Exception ex) { Debug.WriteLine($"drag ghost: {ex.Message}"); }
        }

        private static bool Animate => SystemParameters.ClientAreaAnimation; // false = reduced motion

        private static readonly Color CardColor = Color.FromArgb(0xF5, 0x2A, 0x2C, 0x30);
        private static readonly Color Hairline = Color.FromArgb(0x24, 0xFF, 0xFF, 0xFF);

        /// <summary>Base for both overlays: topmost, click-through, never activated, positioned in physical pixels.</summary>
        private abstract class OverlayWindow : Window
        {
            protected OverlayWindow(string title)
            {
                WindowStyle = WindowStyle.None;
                AllowsTransparency = true;
                Background = Brushes.Transparent;
                Topmost = true;
                ShowInTaskbar = false;
                ShowActivated = false;
                ResizeMode = ResizeMode.NoResize;
                Focusable = false;
                IsHitTestVisible = false;
                Left = -32000; Top = -32000;
                Title = title;
                SourceInitialized += (_, _) =>
                {
                    IntPtr h = new WindowInteropHelper(this).Handle;
                    long ex = NativeMethods.GetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE);
                    NativeMethods.SetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE, ex
                        | NativeMethods.WS_EX_TRANSPARENT | NativeMethods.WS_EX_LAYERED
                        | NativeMethods.WS_EX_NOACTIVATE | NativeMethods.WS_EX_TOOLWINDOW);
                };
            }

            private static List<MonitorInfo>? _mons;
            private static long _monsAt;

            /// <summary>Monitor list, refreshed at most every 2 s (the ghost follows the cursor up to 120 times a second).</summary>
            protected static List<MonitorInfo> Mons()
            {
                long now = Environment.TickCount64;
                if (_mons == null || now - _monsAt > 2000)
                {
                    try { _mons = Monitors.Enumerate(); } catch { _mons ??= new List<MonitorInfo>(); }
                    _monsAt = now;
                }
                return _mons;
            }

            /// <summary>DIP → physical pixels for the monitor at (x, y).</summary>
            protected static double ScaleAt(int x, int y)
            {
                var m = Monitors.At(Mons(), x, y);
                return m?.Scale > 0 ? m.Scale : 1;
            }

            protected void PlacePx(int x, int y, int w, int h)
            {
                IntPtr hwnd = new WindowInteropHelper(this).EnsureHandle();
                if (!IsVisible) Show();
                const uint flags = NativeMethods.SWP_NOACTIVATE | NativeMethods.SWP_SHOWWINDOW | NativeMethods.SWP_NOOWNERZORDER;
                NativeMethods.SetWindowPos(hwnd, NativeMethods.HWND_TOPMOST, x, y, w, h, flags);
            }
        }

        private sealed class GhostWindow : OverlayWindow
        {
            private const double CardW = 210, CardH = 58, Pad = 18;
            private const int OffX = 10, OffY = 14;          // card corner relative to the cursor tip (DIP)
            private readonly Grid _root;
            private readonly Border _back;
            private readonly Image _icon;
            private readonly TextBlock _title, _sub, _badgeText;
            private readonly Border _badge;
            private readonly ScaleTransform _scale = new(1, 1);
            private double _s = 1;
            private int _gen;

            public GhostWindow() : base("AirBridge drag")
            {
                _back = new Border
                {
                    Width = CardW, Height = CardH, CornerRadius = new CornerRadius(14),
                    Background = new SolidColorBrush(Color.FromArgb(0xE0, 0x3A, 0x3D, 0x42)),
                    RenderTransformOrigin = new Point(0.5, 0.5),
                    RenderTransform = new TransformGroup { Children = { new RotateTransform(5), new TranslateTransform(4, 3) } },
                    Visibility = Visibility.Collapsed,
                };
                _icon = new Image { Width = 36, Height = 36, Margin = new Thickness(11, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center };
                _title = new TextBlock { Foreground = Brushes.White, FontSize = 13, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis };
                _sub = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(0xA3, 0xA7, 0xAD)), FontSize = 11.5, TextTrimming = TextTrimming.CharacterEllipsis };
                var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 12, 0), Children = { _title, _sub } };
                var row = new DockPanel { LastChildFill = true };
                DockPanel.SetDock(_icon, Dock.Left);
                row.Children.Add(_icon);
                row.Children.Add(text);
                var card = new Border
                {
                    Width = CardW, Height = CardH, CornerRadius = new CornerRadius(14),
                    Background = new SolidColorBrush(CardColor),
                    BorderBrush = new SolidColorBrush(Hairline), BorderThickness = new Thickness(1),
                    Child = row,
                    Effect = new DropShadowEffect { BlurRadius = 16, ShadowDepth = 4, Direction = 270, Opacity = 0.35, Color = Colors.Black },
                };
                _badgeText = new TextBlock { FontSize = 10.5, FontWeight = FontWeights.Bold, Foreground = new SolidColorBrush(Color.FromRgb(0x1F, 0x20, 0x23)), HorizontalAlignment = HorizontalAlignment.Center };
                _badge = new Border
                {
                    MinWidth = 18, Height = 18, CornerRadius = new CornerRadius(9), Background = Brushes.White, Padding = new Thickness(5, 1, 5, 0),
                    HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top,
                    Margin = new Thickness(0, -8, -8, 0), Child = _badgeText, Visibility = Visibility.Collapsed,
                };
                var stack = new Grid
                {
                    Width = CardW, Height = CardH, Margin = new Thickness(Pad),
                    HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
                    RenderTransformOrigin = new Point(0, 0),
                    RenderTransform = new RotateTransform(-4),
                    Children = { _back, card, _badge },
                };
                _root = new Grid { RenderTransformOrigin = new Point(Pad / (CardW + 2 * Pad), Pad / (CardH + 2 * Pad)), RenderTransform = _scale, Children = { stack } };
                Content = new Viewbox { Stretch = Stretch.Fill, Child = new Grid { Width = CardW + 2 * Pad, Height = CardH + 2 * Pad, Children = { _root } } };
            }

            public void ShowFor(DragGhostState.Info info, int x, int y)
            {
                bool many = info.Count > 1;
                _title.Text = many ? $"{info.Count} items" : info.FirstName;
                string size = info.TotalBytes > 0 ? FormatBytes(info.TotalBytes) : "";
                _sub.Text = size.Length > 0 ? size : many ? $"{info.FirstName} and more" : "";
                _sub.Visibility = _sub.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
                _back.Visibility = many ? Visibility.Visible : Visibility.Collapsed;
                _badge.Visibility = many ? Visibility.Visible : Visibility.Collapsed;
                _badgeText.Text = info.Count > 99 ? "99+" : info.Count.ToString();
                _icon.Source = ShellIcon.For(info.FirstName, info.Kind == "folder");
                bool wasShown = IsVisible && _root.Opacity > 0.01;
                _gen++;
                Follow(x, y);
                if (wasShown) return;
                _root.BeginAnimation(OpacityProperty, null);
                _scale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
                _scale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
                if (!Animate) { _root.Opacity = 1; _scale.ScaleX = _scale.ScaleY = 1; return; }
                var ease = new BackEase { Amplitude = 0.35, EasingMode = EasingMode.EaseOut };
                var grow = new DoubleAnimation(0.82, 1, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease };
                _scale.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
                _scale.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
                _root.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)));
            }

            public void Follow(int x, int y)
            {
                _s = ScaleAt(x, y);
                int w = (int)Math.Ceiling((CardW + 2 * Pad) * _s), h = (int)Math.Ceiling((CardH + 2 * Pad) * _s);
                PlacePx(x + (int)((OffX - Pad) * _s), y + (int)((OffY - Pad) * _s), w, h);
            }

            public void FadeOut(bool dropped)
            {
                if (!IsVisible) return;
                int gen = ++_gen;
                if (!Animate) { HideNow(); return; }
                var fade = new DoubleAnimation(0, TimeSpan.FromMilliseconds(dropped ? 200 : 140));
                fade.Completed += (_, _) => { if (gen == _gen) HideNow(); };
                if (dropped)
                {
                    var shrink = new DoubleAnimation(0.5, TimeSpan.FromMilliseconds(200)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseIn } };
                    _scale.BeginAnimation(ScaleTransform.ScaleXProperty, shrink);
                    _scale.BeginAnimation(ScaleTransform.ScaleYProperty, shrink);
                }
                _root.BeginAnimation(OpacityProperty, fade);
            }

            private void HideNow()
            {
                _root.BeginAnimation(OpacityProperty, null);
                _scale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
                _scale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
                _root.Opacity = 0;
                _scale.ScaleX = _scale.ScaleY = 1;
                Hide();
            }

            private static string FormatBytes(long b)
            {
                string[] units = { "bytes", "KB", "MB", "GB", "TB" };
                double v = b;
                int u = 0;
                while (v >= 1000 && u < units.Length - 1) { v /= 1000; u++; }
                return u == 0 ? $"{b} bytes" : $"{v:0.#} {units[u]}";
            }
        }

        private sealed class NoteWindow : OverlayWindow
        {
            private const double W = 360, H = 32;
            private readonly TextBlock _text;
            private readonly Border _pill;
            private readonly DispatcherTimer _timer;
            private int _gen;

            public NoteWindow() : base("AirBridge note")
            {
                _text = new TextBlock { Foreground = Brushes.White, FontSize = 12.5, FontWeight = FontWeights.Medium, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
                _pill = new Border
                {
                    Height = H, CornerRadius = new CornerRadius(H / 2), Padding = new Thickness(14, 0, 14, 0),
                    Background = new SolidColorBrush(CardColor), BorderBrush = new SolidColorBrush(Hairline), BorderThickness = new Thickness(1),
                    HorizontalAlignment = HorizontalAlignment.Center, Child = _text,
                };
                Content = new Viewbox { Stretch = Stretch.Fill, Child = new Grid { Width = W, Height = H, Children = { _pill } } };
                _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(2200) };
                _timer.Tick += (_, _) => { _timer.Stop(); Fade(); };
            }

            public void ShowAt(string text, int x, int y)
            {
                _text.Text = text;
                double s = ScaleAt(x, y);
                int w = (int)Math.Ceiling(W * s), h = (int)Math.Ceiling(H * s);
                int top = y + (int)(28 * s);
                try
                {
                    var m = Monitors.At(Mons(), x, y);
                    if (m != null && top + h > m.Bounds.Bottom - 8) top = y - (int)(28 * s) - h;
                    int left = Math.Clamp(x - w / 2, m?.Bounds.Left + 8 ?? x - w / 2, Math.Max(m?.Bounds.Left + 8 ?? 0, (m?.Bounds.Right ?? x + w) - w - 8));
                    PlacePx(left, top, w, h);
                }
                catch { PlacePx(x - w / 2, top, w, h); }
                _gen++;
                _pill.BeginAnimation(OpacityProperty, null);
                if (Animate) _pill.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180)));
                else _pill.Opacity = 1;
                _timer.Stop();
                _timer.Start();
            }

            private void Fade()
            {
                int gen = ++_gen;
                if (!Animate) { Hide(); return; }
                var a = new DoubleAnimation(0, TimeSpan.FromMilliseconds(220));
                a.Completed += (_, _) => { if (gen == _gen) Hide(); };
                _pill.BeginAnimation(OpacityProperty, a);
            }
        }

        /// <summary>The shell's large icon for a file name (by extension only, nothing touches the disk).</summary>
        private static class ShellIcon
        {
            [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
            private struct SHFILEINFO
            {
                public IntPtr hIcon;
                public int iIcon;
                public uint dwAttributes;
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szDisplayName;
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string szTypeName;
            }

            private const uint SHGFI_ICON = 0x100, SHGFI_LARGEICON = 0x0, SHGFI_USEFILEATTRIBUTES = 0x10;
            private const uint FILE_ATTRIBUTE_NORMAL = 0x80, FILE_ATTRIBUTE_DIRECTORY = 0x10;

            [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
            private static extern IntPtr SHGetFileInfo(string pszPath, uint dwFileAttributes, ref SHFILEINFO psfi, uint cbFileInfo, uint uFlags);

            [DllImport("user32.dll", SetLastError = true)]
            private static extern bool DestroyIcon(IntPtr hIcon);

            public static ImageSource? For(string name, bool folder)
            {
                try
                {
                    var info = new SHFILEINFO();
                    string probe = folder ? "folder" : (System.IO.Path.GetExtension(name) is { Length: > 0 } ext ? "file" + ext : "file");
                    IntPtr r = SHGetFileInfo(probe, folder ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL, ref info,
                        (uint)Marshal.SizeOf<SHFILEINFO>(), SHGFI_ICON | SHGFI_LARGEICON | SHGFI_USEFILEATTRIBUTES);
                    if (r == IntPtr.Zero || info.hIcon == IntPtr.Zero) return null;
                    try
                    {
                        var src = Imaging.CreateBitmapSourceFromHIcon(info.hIcon, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
                        src.Freeze();
                        return src;
                    }
                    finally { DestroyIcon(info.hIcon); }
                }
                catch (Exception ex) { Debug.WriteLine($"shell icon: {ex.Message}"); return null; }
            }
        }
    }
}
