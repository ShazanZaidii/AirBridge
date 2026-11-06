using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

namespace AirBridge.Input
{
    /// <summary>
    /// Small pill near the top of a monitor (§8.1 "Escape is the controller's job"): while this PC's cursor is on
    /// another device it says how to get back; after a capture that could not start it briefly says why.
    /// Same window style as <see cref="EdgeIndicator"/>: topmost, click-through, never activated, positioned in
    /// physical pixels. Thread-safe and non-blocking: calls store the latest request and post at most one dispatcher
    /// item at a time.
    /// </summary>
    internal static class CaptureHud
    {
        private enum Mode { None, Controlling, Notice }

        private static readonly object Gate = new();
        private static Dispatcher? _ui;
        private static Mode _want;
        private static string _text = "";
        private static MonitorInfo? _mon;
        private static int _noticeMs;
        private static long _seq;
        private static bool _queued;
        private static HudWindow? _win;

        /// <summary>Call once on the UI thread.</summary>
        public static void Init(Dispatcher ui) => _ui = ui;

        public static void ShowControlling(string deviceName, MonitorInfo? mon) =>
            Set(Mode.Controlling, $"Controlling {deviceName}  ·  move back through the edge, or press Ctrl+Alt+Esc", mon, 0);

        public static void ShowNotice(string text, MonitorInfo? mon, int ms = 5000) => Set(Mode.Notice, text, mon, ms);

        /// <summary>Hides the "Controlling" pill; a notice stays until its own timeout.</summary>
        public static void HideControlling()
        {
            lock (Gate)
            {
                if (_want != Mode.Controlling) return;
            }
            Set(Mode.None, "", null, 0);
        }

        private static void Set(Mode m, string text, MonitorInfo? mon, int ms)
        {
            var ui = _ui;
            if (ui == null || ui.HasShutdownStarted) return;
            lock (Gate)
            {
                _want = m; _text = text; _mon = mon; _noticeMs = ms; _seq++;
                if (_queued) return;
                _queued = true;
            }
            try { ui.BeginInvoke(DispatcherPriority.Render, new Action(Apply)); }
            catch { lock (Gate) _queued = false; }
        }

        private static void Apply()
        {
            Mode m; string text; MonitorInfo? mon; int ms; long seq;
            lock (Gate)
            {
                m = _want; text = _text; mon = _mon; ms = _noticeMs; seq = _seq;
                _queued = false;
            }
            try
            {
                if (m == Mode.None || mon == null)
                {
                    _win?.HideNow();
                    return;
                }
                (_win ??= new HudWindow()).ShowText(text, mon);
                if (m == Mode.Notice)
                {
                    var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(Math.Max(500, ms)) };
                    t.Tick += (_, _) =>
                    {
                        t.Stop();
                        bool stale;
                        lock (Gate) stale = _seq != seq;
                        if (stale) return; // something newer replaced the notice
                        lock (Gate) _want = Mode.None;
                        _win?.HideNow();
                        };
                    t.Start();
                }
            }
            catch (Exception ex) { Debug.WriteLine($"capture hud: {ex.Message}"); }
        }

        private sealed class HudWindow : Window
        {
            private readonly TextBlock _label;
            private readonly Border _pill;

            public HudWindow()
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
                Width = 4; Height = 4; Left = -32000; Top = -32000;
                Title = "AirBridge control";
                _label = new TextBlock
                {
                    Foreground = Brushes.White,
                    FontFamily = new FontFamily("Segoe UI Variable Text, Segoe UI"),
                    FontSize = 13,
                    FontWeight = FontWeights.Medium,
                    TextWrapping = TextWrapping.NoWrap,
                    VerticalAlignment = VerticalAlignment.Center,
                };
                TextOptions.SetTextFormattingMode(_label, TextFormattingMode.Display);
                _pill = new Border
                {
                    Background = new SolidColorBrush(Color.FromArgb(0xE6, 0x20, 0x20, 0x20)),
                    BorderBrush = new SolidColorBrush(Color.FromArgb(0x60, 0x22, 0xC5, 0x5E)), // design system accent
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(14),
                    Padding = new Thickness(16, 7, 16, 8),
                    Child = _label,
                };
                Content = _pill;
                SourceInitialized += (_, _) =>
                {
                    IntPtr h = new WindowInteropHelper(this).Handle;
                    long ex = NativeMethods.GetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE);
                    NativeMethods.SetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE, ex
                        | NativeMethods.WS_EX_TRANSPARENT | NativeMethods.WS_EX_LAYERED
                        | NativeMethods.WS_EX_NOACTIVATE | NativeMethods.WS_EX_TOOLWINDOW);
                };
            }

            public void ShowText(string text, MonitorInfo mon)
            {
                _label.Text = text;
                _pill.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
                double scale = mon.Scale > 0 ? mon.Scale : 1;
                var b = mon.Bounds;
                int w = (int)Math.Ceiling(_pill.DesiredSize.Width * scale);
                int h = (int)Math.Ceiling(_pill.DesiredSize.Height * scale);
                w = Math.Min(w, Math.Max(1, b.Width - 16));
                int x = b.Left + (b.Width - w) / 2;
                int y = b.Top + (int)Math.Round(48 * scale);
                IntPtr hwnd = new WindowInteropHelper(this).EnsureHandle();
                if (!IsVisible) Show();
                const uint flags = NativeMethods.SWP_NOACTIVATE | NativeMethods.SWP_SHOWWINDOW | NativeMethods.SWP_NOOWNERZORDER;
                // Twice: moving onto a monitor with another DPI makes WPF apply its own suggested size on the first.
                NativeMethods.SetWindowPos(hwnd, NativeMethods.HWND_TOPMOST, x, y, w, h, flags);
                NativeMethods.SetWindowPos(hwnd, NativeMethods.HWND_TOPMOST, x, y, w, h, flags);
            }

            public void HideNow()
            {
                if (IsVisible) Hide();
            }
        }
    }
}
