using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using QRCoder;

namespace AirBridge
{
    /// <summary>Small shared helpers for the windows: dark title bar, QR drawing, sizes, and giving memory back.</summary>
    public static class UiKit
    {
        [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
        [DllImport("dwmapi.dll")] private static extern int DwmExtendFrameIntoClientArea(IntPtr hwnd, ref Margins margins);
        [StructLayout(LayoutKind.Sequential)] private struct Margins { public int Left, Right, Top, Bottom; }
        [DllImport("kernel32.dll")] private static extern bool SetProcessWorkingSetSize(IntPtr process, IntPtr min, IntPtr max);

        /// <summary>
        /// A normal, resizable window with a dark native title bar (Windows 10 1809+ / 11): cheap to draw, unlike a
        /// transparent borderless one, which WPF renders in software.
        /// </summary>
        public static void Dark(Window w)
        {
            w.SourceInitialized += (_, _) =>
            {
                try
                {
                    var h = new WindowInteropHelper(w).Handle;
                    int on = 1;
                    if (DwmSetWindowAttribute(h, 20, ref on, sizeof(int)) != 0) DwmSetWindowAttribute(h, 19, ref on, sizeof(int)); // 19 on older builds
                }
                catch { }
            };
        }

        /// <summary>
        /// Frosted glass behind the window: Windows 11's own Mica (long-lived windows) or acrylic (the tray menu),
        /// drawn by the desktop compositor, not by this app, so it costs no memory here and almost no CPU. Not drawn
        /// at all on Windows 10, with transparency effects off, in high contrast or when the user turns it off
        /// (the window then keeps its plain dark background). <paramref name="acrylic"/>: stronger blur for a short-lived popup.
        /// </summary>
        public static void Glass(Window w, bool acrylic = false)
        {
            if (!GlassSupported || !UiSettings.Glass) return;
            w.SourceInitialized += (_, _) =>
            {
                try
                {
                    var h = new WindowInteropHelper(w).Handle;
                    int type = acrylic ? 3 : 2; // DWMSBT_TRANSIENTWINDOW (acrylic) / DWMSBT_MAINWINDOW (Mica)
                    if (DwmSetWindowAttribute(h, 38, ref type, sizeof(int)) != 0) return; // the system said no: keep the plain background
                    var m = new Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 };
                    DwmExtendFrameIntoClientArea(h, ref m);
                    var src = HwndSource.FromHwnd(h);
                    if (src?.CompositionTarget != null) src.CompositionTarget.BackgroundColor = Colors.Transparent;
                    w.Background = Brushes.Transparent;
                }
                catch { }
            };
        }

        /// <summary>Windows 11 22H2 or later (the first build with the system backdrop setting), without high contrast.</summary>
        public static bool GlassSupported => Environment.OSVersion.Version.Build >= 22621 && !SystemParameters.HighContrast;

        /// <summary>The QR code of <paramref name="data"/> as a frozen bitmap (black on white, with a quiet zone).</summary>
        public static BitmapImage RenderQr(string data, int pixelsPerModule = 8)
        {
            using var generator = new QRCodeGenerator();
            using var qrData = generator.CreateQrCode(data, QRCodeGenerator.ECCLevel.M);
            var png = new PngByteQRCode(qrData).GetGraphic(pixelsPerModule);
            var image = new BitmapImage();
            using var stream = new MemoryStream(png);
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
            image.Freeze();
            return image;
        }

        public static string Size(long n)
        {
            if (n < 0) return "";
            if (n < 1024) return n + " B";
            double v = n / 1024.0;
            string[] units = { "KB", "MB", "GB", "TB" };
            int i = 0;
            while (v >= 1024 && i < units.Length - 1) { v /= 1024; i++; }
            return (v < 10 ? v.ToString("0.0") : v.ToString("0")) + " " + units[i];
        }

        public static string Ago(long epochMs)
        {
            var d = DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeMilliseconds(epochMs);
            if (d.TotalSeconds < 45) return "just now";
            if (d.TotalMinutes < 60) return $"{(int)Math.Max(1, d.TotalMinutes)} min ago";
            if (d.TotalHours < 24) return $"{(int)d.TotalHours} h ago";
            return $"{(int)d.TotalDays} d ago";
        }

        private static DateTime _lastTrim = DateTime.MinValue;

        /// <summary>
        /// Gives memory back: collects garbage and trims the working set. Called when a window closes and when the
        /// app has been quiet, so a tray app that sits all day on a small PC stays small. Cheap and rate-limited.
        /// </summary>
        public static void TrimMemory(bool force = false)
        {
            if (!force && DateTime.UtcNow - _lastTrim < TimeSpan.FromSeconds(20)) return;
            _lastTrim = DateTime.UtcNow;
            try
            {
                GC.Collect(2, GCCollectionMode.Forced, blocking: true, compacting: true);
                GC.WaitForPendingFinalizers();
                SetProcessWorkingSetSize(Process.GetCurrentProcess().Handle, (IntPtr)(-1), (IntPtr)(-1));
            }
            catch { }
        }
    }
}
