using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace AirBridge.Input
{
    /// <summary>
    /// Subtle edge indicator for capture intent (§8.2): a thin translucent bar along the peer's span that fades in
    /// as the push-through accumulator charges and flashes once on capture; hidden otherwise.
    ///
    /// Called from the hook thread: each call only stores the latest request under a tiny lock and posts at most one
    /// dispatcher item at a time (coalesced), so the hook thread never waits on the UI.
    /// </summary>
    internal static class EdgeIndicator
    {
        private enum Cmd { None, Charge, Flash, Hide }

        private static readonly object Gate = new();
        private static Dispatcher? _ui;
        private static Cmd _cmd;
        private static IRect _rect;
        private static double _level;
        private static bool _queued;
        private static IndicatorWindow? _win;

        /// <summary>Call once on the UI thread.</summary>
        public static void Init(Dispatcher ui) => _ui = ui;

        public static void Charge(IRect bar, double level) => Set(Cmd.Charge, bar, level);
        public static void Flash(IRect bar) => Set(Cmd.Flash, bar, 1);
        public static void Hide() => Set(Cmd.Hide, default, 0);

        private static void Set(Cmd c, IRect r, double level)
        {
            var ui = _ui;
            if (ui == null || ui.HasShutdownStarted) return;
            lock (Gate)
            {
                // A pending flash wins over the reset that follows it; the flash fades out by itself.
                if (!(_cmd == Cmd.Flash && c == Cmd.Hide)) { _cmd = c; _rect = r; _level = level; }
                if (_queued) return;
                _queued = true;
            }
            try { ui.BeginInvoke(DispatcherPriority.Render, new Action(Apply)); }
            catch { lock (Gate) _queued = false; }
        }

        private static void Apply()
        {
            Cmd c; IRect r; double level;
            lock (Gate)
            {
                c = _cmd; r = _rect; level = _level;
                _cmd = Cmd.None;
                _queued = false;
            }
            try
            {
                switch (c)
                {
                    case Cmd.Charge: (_win ??= new IndicatorWindow()).ShowCharge(r, level); break;
                    case Cmd.Flash: (_win ??= new IndicatorWindow()).ShowFlash(r); break;
                    case Cmd.Hide: _win?.FadeOut(); break;
                }
            }
            catch (Exception ex) { Debug.WriteLine($"edge indicator: {ex.Message}"); }
        }

        /// <summary>Topmost, click-through, never-activated layered window. Positioned in physical pixels.</summary>
        private sealed class IndicatorWindow : Window
        {
            private static readonly Color Accent = Color.FromRgb(0x22, 0xC5, 0x5E); // design system accent
            private readonly Border _bar;
            private readonly DispatcherTimer _decay;
            private IRect _placed;
            private int _gen;

            public IndicatorWindow()
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
                Title = "AirBridge edge";
                _bar = new Border { Background = new SolidColorBrush(Accent), CornerRadius = new CornerRadius(2), Opacity = 0 };
                Content = _bar;
                SourceInitialized += (_, _) =>
                {
                    IntPtr h = new WindowInteropHelper(this).Handle;
                    long ex = NativeMethods.GetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE);
                    NativeMethods.SetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE, ex
                        | NativeMethods.WS_EX_TRANSPARENT | NativeMethods.WS_EX_LAYERED
                        | NativeMethods.WS_EX_NOACTIVATE | NativeMethods.WS_EX_TOOLWINDOW);
                };
                // No update for longer than the accumulator window → the push was abandoned.
                _decay = new DispatcherTimer(DispatcherPriority.Normal) { Interval = TimeSpan.FromMilliseconds(550) };
                _decay.Tick += (_, _) => { _decay.Stop(); FadeOut(); };
            }

            private static bool Animate => SystemParameters.ClientAreaAnimation; // false = reduced motion

            private void Place(IRect r)
            {
                if (r.Width <= 0 || r.Height <= 0) return;
                IntPtr h = new WindowInteropHelper(this).EnsureHandle();
                if (!IsVisible) Show();
                if (r == _placed) return;
                _placed = r;
                const uint flags = NativeMethods.SWP_NOACTIVATE | NativeMethods.SWP_SHOWWINDOW | NativeMethods.SWP_NOOWNERZORDER;
                // Twice: moving onto a monitor with another DPI makes WPF apply its own suggested size on the first.
                NativeMethods.SetWindowPos(h, NativeMethods.HWND_TOPMOST, r.Left, r.Top, r.Width, r.Height, flags);
                NativeMethods.SetWindowPos(h, NativeMethods.HWND_TOPMOST, r.Left, r.Top, r.Width, r.Height, flags);
            }

            private void To(double opacity, int ms, Action? done = null)
            {
                int gen = ++_gen;
                if (!Animate || ms <= 0)
                {
                    _bar.BeginAnimation(OpacityProperty, null);
                    _bar.Opacity = opacity;
                    done?.Invoke();
                    return;
                }
                var a = new DoubleAnimation(opacity, TimeSpan.FromMilliseconds(ms)) { EasingFunction = new QuadraticEase() };
                if (done != null) a.Completed += (_, _) => { if (gen == _gen) done(); };
                _bar.BeginAnimation(OpacityProperty, a);
            }

            public void ShowCharge(IRect r, double level)
            {
                Place(r);
                To(0.15 + 0.75 * Math.Clamp(level, 0, 1), 90);
                _decay.Stop();
                _decay.Start();
            }

            public void ShowFlash(IRect r)
            {
                _decay.Stop();
                Place(r);
                int gen = ++_gen;
                if (!Animate)
                {
                    _bar.BeginAnimation(OpacityProperty, null);
                    _bar.Opacity = 1;
                    var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
                    t.Tick += (_, _) => { t.Stop(); if (gen == _gen) HideNow(); };
                    t.Start();
                    return;
                }
                var k = new DoubleAnimationUsingKeyFrames();
                k.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(60))));
                k.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(160))));
                k.KeyFrames.Add(new EasingDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(460)), new QuadraticEase()));
                k.Completed += (_, _) => { if (gen == _gen) HideNow(); };
                _bar.BeginAnimation(OpacityProperty, k);
            }

            public void FadeOut()
            {
                _decay.Stop();
                if (!IsVisible) return;
                To(0, 180, HideNow);
            }

            private void HideNow()
            {
                _bar.BeginAnimation(OpacityProperty, null);
                _bar.Opacity = 0;
                Hide();
                _placed = default;
            }
        }
    }
}
