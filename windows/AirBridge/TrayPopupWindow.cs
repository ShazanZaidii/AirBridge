using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

namespace AirBridge
{
    /// <summary>
    /// The tray menu: a small plain window above the notification area, built when the tray icon is clicked and
    /// thrown away when it loses focus. Nothing of it stays in memory in between (the old always-built popup
    /// kept its whole visual tree, a QR bitmap and a blur shadow alive all day), and it is not a layered
    /// transparent window, which WPF draws in software.
    /// </summary>
    public sealed class TrayPopupWindow : Window
    {
        [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        private static TrayPopupWindow? _current;
        private static DateTime _closedAt = DateTime.MinValue;
        private readonly TrayPopupControl _content;

        /// <summary>Opens it, or closes it if it is already open (a second click on the tray icon).</summary>
        public static void Toggle()
        {
            if (_current != null) { _current.Close(); return; }
            // Clicking the tray icon while the popup is open first takes its focus (closing it): don't reopen at once.
            if (DateTime.UtcNow - _closedAt < TimeSpan.FromMilliseconds(350)) return;
            _current = new TrayPopupWindow();
            _current.Show();
            _current.Activate();
        }

        public static void CloseIfOpen() => _current?.Close();

        private TrayPopupWindow()
        {
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            ShowInTaskbar = false;
            Topmost = true;
            ShowActivated = true;
            SizeToContent = SizeToContent.Height;
            Width = 372;
            UseLayoutRounding = true;
            Background = (Brush)Application.Current.FindResource("AB.Background");
            var wa = SystemParameters.WorkArea;
            MaxHeight = Math.Max(320, wa.Height - 16);
            _content = new TrayPopupControl();
            _content.RequestClose += Close;
            var scroll = new ScrollViewer { Style = (Style)Application.Current.FindResource("AB.ScrollViewer"), Content = _content };
            Content = new Border
            {
                BorderBrush = (Brush)Application.Current.FindResource("AB.Secondary"), BorderThickness = new Thickness(1), Child = scroll,
            };
            UiKit.Glass(this, acrylic: true);
            SourceInitialized += (_, _) =>
            {
                try
                {
                    var h = new WindowInteropHelper(this).Handle;
                    int round = 2; // DWMWCP_ROUND (Windows 11); ignored where it doesn't exist
                    DwmSetWindowAttribute(h, 33, ref round, sizeof(int));
                }
                catch { }
            };
            // Sit in the corner above the taskbar, growing upward as the content grows.
            SizeChanged += (_, _) => Anchor();
            Loaded += (_, _) => Anchor();
            Deactivated += (_, _) => Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() =>
            {
                // A dialog opened from here (file picker) takes the focus: stay for it.
                if (!IsActive && OwnedWindows.Count == 0) Close();
            }));
            Closed += (_, _) =>
            {
                _content.Detach();
                _current = null;
                _closedAt = DateTime.UtcNow;
                UiKit.TrimMemory();
            };
        }

        private void Anchor()
        {
            var wa = SystemParameters.WorkArea;
            Left = wa.Right - ActualWidth - 10;
            Top = wa.Bottom - ActualHeight - 10;
        }
    }
}
