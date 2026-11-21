using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using WpfPath = System.Windows.Shapes.Path;

namespace AirBridge.Drop
{
    /// <summary>Look and motion shared by the drop shelf and the transfer cards: calm graphite, white text, soft springs.</summary>
    internal static class DropUi
    {
        public static readonly Brush Card = Frozen(Color.FromArgb(0xF2, 0x23, 0x24, 0x27));
        public static readonly Brush CardBorder = Frozen(Color.FromArgb(0x22, 0xFF, 0xFF, 0xFF));
        public static readonly Brush Foreground = Frozen(Color.FromRgb(0xF2, 0xF2, 0xF3));
        public static readonly Brush Muted = Frozen(Color.FromRgb(0x9C, 0x9F, 0xA6));
        public static readonly Brush Track = Frozen(Color.FromArgb(0x26, 0xFF, 0xFF, 0xFF));
        public static readonly Brush Ring = Frozen(Color.FromArgb(0xD9, 0xFF, 0xFF, 0xFF));
        public static readonly Brush BubbleFill = Frozen(Color.FromArgb(0x12, 0xFF, 0xFF, 0xFF));
        public static readonly Brush BubbleHot = Frozen(Color.FromArgb(0x2E, 0xFF, 0xFF, 0xFF));
        public static readonly Brush BubbleStroke = Frozen(Color.FromArgb(0x30, 0xFF, 0xFF, 0xFF));
        public static readonly Brush BubbleStrokeHot = Frozen(Color.FromArgb(0x99, 0xFF, 0xFF, 0xFF));
        public static readonly Brush PillBg = Frozen(Color.FromArgb(0x1A, 0xFF, 0xFF, 0xFF));
        public static readonly Brush PillBgHover = Frozen(Color.FromArgb(0x2C, 0xFF, 0xFF, 0xFF));
        public static readonly Brush ProminentBg = Frozen(Color.FromArgb(0xE6, 0xF2, 0xF2, 0xF3));
        public static readonly Brush ProminentBgHover = Frozen(Color.FromRgb(0xFF, 0xFF, 0xFF));
        public static readonly Brush ProminentFg = Frozen(Color.FromRgb(0x1C, 0x1D, 0x20));

        public static readonly FontFamily Font = new("Segoe UI Variable Text, Segoe UI");
        public static readonly FontFamily Icons = new("Segoe Fluent Icons, Segoe MDL2 Assets");

        public const string GPhone = "", GLaptop = "", GDesktop = "", GUp = "", GDown = "",
                            GCheck = "", GError = "", GClose = "", GDrop = "";

        /// <summary>Overshoots a little, like a soft spring.</summary>
        public static readonly IEasingFunction Spring = Freeze(new BackEase { Amplitude = 0.35, EasingMode = EasingMode.EaseOut });
        public static readonly IEasingFunction Out = Freeze(new CubicEase { EasingMode = EasingMode.EaseOut });

        private static Brush Frozen(Color c) { var b = new SolidColorBrush(c); b.Freeze(); return b; }
        private static IEasingFunction Freeze(EasingFunctionBase e) { e.Freeze(); return e; }

        public static string PlatformGlyph(string platform) => platform switch
        {
            "android" or "ios" => GPhone,
            "macos" => GLaptop,
            _ => GDesktop,
        };

        /// <summary>Topmost helper window that never takes focus and stays out of Alt+Tab.</summary>
        public static void MakeOverlay(Window w)
        {
            w.WindowStyle = WindowStyle.None;
            w.AllowsTransparency = true;
            w.Background = Brushes.Transparent;
            w.Topmost = true;
            w.ShowInTaskbar = false;
            w.ShowActivated = false;
            w.ResizeMode = ResizeMode.NoResize;
            w.SourceInitialized += (_, _) =>
            {
                IntPtr h = new WindowInteropHelper(w).Handle;
                long ex = NativeMethods.GetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE);
                NativeMethods.SetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE, ex | NativeMethods.WS_EX_NOACTIVATE | NativeMethods.WS_EX_TOOLWINDOW);
            };
        }

        public static TextBlock Text(string text, double size = 13, Brush? fg = null, FontWeight? weight = null) => new()
        {
            Text = text, FontFamily = Font, FontSize = size, Foreground = fg ?? Foreground, FontWeight = weight ?? FontWeights.Normal,
            TextTrimming = TextTrimming.CharacterEllipsis, TextWrapping = TextWrapping.NoWrap,
        };

        public static TextBlock Glyph(string code, double size, Brush? fg = null) => new()
        {
            Text = code, FontFamily = Icons, FontSize = size, Foreground = fg ?? Foreground,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        };

        /// <summary>Small capsule button. Plain controls only (no template), so it works with or without the app theme.</summary>
        public static Border Pill(string text, Action onClick, bool prominent = false)
        {
            var label = Text(text, 12, prominent ? ProminentFg : Foreground, FontWeights.SemiBold);
            var b = new Border
            {
                Child = label, CornerRadius = new CornerRadius(11), Padding = new Thickness(11, 3, 11, 4),
                Background = prominent ? ProminentBg : PillBg, Cursor = Cursors.Hand, Margin = new Thickness(0, 0, 6, 0),
                Focusable = true,
            };
            System.Windows.Automation.AutomationProperties.SetName(b, text);
            b.MouseEnter += (_, _) => b.Background = prominent ? ProminentBgHover : PillBgHover;
            b.MouseLeave += (_, _) => b.Background = prominent ? ProminentBg : PillBg;
            b.MouseLeftButtonUp += (_, e) => { e.Handled = true; onClick(); };
            b.KeyDown += (_, e) => { if (e.Key is Key.Enter or Key.Space) { e.Handled = true; onClick(); } };
            return b;
        }

        /// <summary>Clockwise arc from 12 o'clock covering <paramref name="fraction"/> of a circle.</summary>
        public static Geometry Arc(double size, double thickness, double fraction)
        {
            fraction = Math.Clamp(fraction, 0, 1);
            double r = (size - thickness) / 2, c = size / 2;
            if (fraction <= 0.0005) return Geometry.Empty;
            if (fraction >= 0.9995) return new EllipseGeometry(new Point(c, c), r, r);
            double a = fraction * 2 * Math.PI;
            var start = new Point(c, c - r);
            var end = new Point(c + r * Math.Sin(a), c - r * Math.Cos(a));
            var fig = new PathFigure { StartPoint = start, IsClosed = false };
            fig.Segments.Add(new ArcSegment(end, new Size(r, r), 0, fraction > 0.5, SweepDirection.Clockwise, true));
            var g = new PathGeometry();
            g.Figures.Add(fig);
            g.Freeze();
            return g;
        }

        public static WpfPath ArcPath(double thickness, Brush stroke) => new()
        {
            Stroke = stroke, StrokeThickness = thickness, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round,
        };

        public static void Animate(DependencyObject target, DependencyProperty prop, double to, int ms, IEasingFunction? ease = null)
        {
            var a = new DoubleAnimation(to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = ease ?? Out };
            if (target is Animatable an) an.BeginAnimation(prop, a);
            else if (target is UIElement el) el.BeginAnimation(prop, a);
        }
    }
}
