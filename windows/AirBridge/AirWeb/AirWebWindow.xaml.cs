using System;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace AirBridge.AirWeb
{
    /// <summary>
    /// The AirWeb screen: the QR code and link, an optional short link, who is connected, what this PC shares and what
    /// browsers sent. Only a window onto <see cref="AirWebHost"/>'s session: closing it leaves AirWeb running.
    /// </summary>
    public partial class AirWebWindow : Window
    {
        private string? _qrFor;
        private int _refreshQueued;
        private string? _shortUrl;
        private DateTime _shortUntil = DateTime.MinValue;
        private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromSeconds(1) };

        public AirWebWindow()
        {
            InitializeComponent();
            UiKit.Dark(this);
            UiKit.Glass(this);
            AirWebHost.StateChanged += Queue;
            _tick.Tick += (_, _) => PaintShort();
            Loaded += (_, _) => { AirWebHost.Ensure(); Refresh(); };
            Closed += (_, _) =>
            {
                AirWebHost.StateChanged -= Queue;
                _tick.Stop();
                UiKit.TrimMemory(force: true); // the window's visuals go; give the memory back
            };
        }

        private Brush Res(string key) => (Brush)FindResource(key);
        private Style St(string key) => (Style)FindResource(key);

        /// <summary>Any thread: coalesces a burst of changes (upload progress) into one repaint.</summary>
        private void Queue()
        {
            if (Interlocked.Exchange(ref _refreshQueued, 1) == 1) return;
            Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => { Interlocked.Exchange(ref _refreshQueued, 0); Refresh(); }));
        }

        private void Refresh()
        {
            var s = AirWebHost.Session;
            if (s == null) { Close(); return; } // stopped (here, from the tray or by the idle timer)

            string? link = s.Link;
            bool net = link != null;
            NoNetwork.Visibility = !net && !s.Failed ? Visibility.Visible : Visibility.Collapsed;
            InviteBody.Visibility = net ? Visibility.Visible : Visibility.Collapsed;
            if (net)
            {
                if (link != _qrFor) { _qrFor = link; QrImage.Source = UiKit.RenderQr(link!, 6); LinkBox.Text = link; }
            }
            OtherAddress.Visibility = s.Addresses.Count > 1 ? Visibility.Visible : Visibility.Collapsed;

            var peers = s.Peers;
            bool live = s.Port != 0;
            StatusDot.Fill = s.Failed ? Res("AB.Warning") : live && net ? Res("AB.Success") : Res("AB.Border");
            StatusText.Text = s.Failed ? "Couldn't start" : !live ? "Starting" : !net ? "No network"
                : peers.Count == 0 ? "Live" : peers.Count == 1 ? "1 device" : $"{peers.Count} devices";
            PeersText.Text = peers.Count == 0 ? "No one yet. Everyone who joins sees the same files and can add or download."
                : string.Join(" · ", peers.Take(6).Select(p => p.Label)) + (peers.Count > 6 ? $" · +{peers.Count - 6}" : "");

            // shared by this PC
            var offered = s.OfferedFiles;
            OfferedEmpty.Visibility = offered.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            OfferedList.Children.Clear();
            foreach (var f in offered)
            {
                string sub = UiKit.Size(f.Size) + (f.Downloads > 0 ? $" · downloaded {f.Downloads}×" : "");
                var id = f.Id;
                OfferedList.Children.Add(Row(f.Name, sub, null, "", $"Stop sharing {f.Name}", () => s.Remove(id)));
            }

            // from browsers
            var received = s.ReceivedFiles.Reverse().ToList();
            ReceivedEmpty.Visibility = received.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            ReceivedEmpty.Text = $"Nothing yet. Files sent from a browser are saved in Downloads\\AirBridge Transfers and shared with everyone connected.";
            ReceivedList.Children.Clear();
            foreach (var r in received)
            {
                string sub = r.Saved
                    ? string.Join(" · ", new[] { UiKit.Size(r.Size), r.By is { } b ? "from " + b : null, UiKit.Ago(r.At), r.Shared ? "shared" : "not shared" }.Where(x => !string.IsNullOrEmpty(x)))
                    : r.Failed ? "Didn't finish · " + UiKit.Ago(r.At)
                    : $"Receiving {(r.Size > 0 ? (int)(r.Done * 100 / r.Size) : 0)}%";
                var rid = r.Id;
                string? path = r.Saved ? r.Path : null;
                var row = Row(r.Name, sub, !r.Saved && !r.Failed && r.Size > 0 ? (double)r.Done / r.Size : null,
                    r.Saved && r.Shared ? "" : null, $"Stop sharing {r.Name}", () => s.StopSharing(rid));
                if (path != null)
                {
                    row.Cursor = System.Windows.Input.Cursors.Hand;
                    row.ToolTip = "Open";
                    row.MouseLeftButtonUp += (_, e) => { if (e.OriginalSource is not System.Windows.Controls.Primitives.ButtonBase) Open(path); };
                }
                ReceivedList.Children.Add(row);
            }
            PaintShort();
        }

        private Border Row(string title, string sub, double? progress, string? actionGlyph, string actionLabel, Action action)
        {
            var row = new Border { CornerRadius = new CornerRadius(10), Padding = new Thickness(8, 6, 4, 6), Margin = new Thickness(0, 0, 0, 2), Background = Brushes.Transparent };
            row.MouseEnter += (_, _) => row.Background = Res("AB.RowHover");
            row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var body = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            body.Children.Add(new TextBlock { Text = title, Style = St("AB.Text"), TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis });
            body.Children.Add(new TextBlock { Text = sub, Style = St("AB.Text.Caption"), TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis });
            if (progress != null) body.Children.Add(new ProgressBar { Style = St("AB.Progress"), Value = progress.Value, Margin = new Thickness(0, 5, 0, 0) });
            grid.Children.Add(body);
            if (actionGlyph != null)
            {
                var b = new Button { Style = St("AB.Button.Icon"), Content = actionGlyph, ToolTip = actionLabel, Width = 32, Height = 32, MinHeight = 32 };
                System.Windows.Automation.AutomationProperties.SetName(b, actionLabel);
                b.Click += (_, _) => action();
                Grid.SetColumn(b, 1);
                grid.Children.Add(b);
            }
            row.Child = grid;
            return row;
        }

        private static void Open(string path)
        {
            try { Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); }
            catch { try { Process.Start("explorer.exe", $"/select,\"{path}\""); } catch { } }
        }

        // ---------------------------------------------------------------- short link

        private void ShortToggle_Click(object sender, RoutedEventArgs e)
        {
            bool open = ShortBox.Visibility != Visibility.Visible;
            ShortBox.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
            ShortChevron.Text = open ? "" : "";
            if (open) { if (_shortUrl == null || DateTime.UtcNow >= _shortUntil) AskShort(); _tick.Start(); }
            else _tick.Stop();
            PaintShort();
        }

        private void AskShort()
        {
            var r = AirWebHost.Session?.ShortLink();
            if (r == null) return;
            _shortUrl = r.Value.Url;
            _shortUntil = DateTime.UtcNow.AddSeconds(r.Value.SecondsLeft);
        }

        private void PaintShort()
        {
            if (ShortBox.Visibility != Visibility.Visible) return;
            if (_shortUrl == null) { ShortUrl.Text = ""; ShortLeft.Text = "No network."; return; }
            var left = (int)Math.Max(0, (_shortUntil - DateTime.UtcNow).TotalSeconds);
            bool expired = left <= 0;
            ShortUrl.Text = _shortUrl;
            ShortUrl.Foreground = expired ? Res("AB.MutedForeground") : Res("AB.Foreground");
            ShortLeft.Text = expired ? "This short link has expired." : $"Works for {left / 60}:{left % 60:00} more. Same Wi-Fi needed.";
            ShortCopy.Visibility = expired ? Visibility.Collapsed : Visibility.Visible;
            ShortNew.Visibility = expired ? Visibility.Visible : Visibility.Collapsed;
        }

        private void ShortCopy_Click(object sender, RoutedEventArgs e) { if (_shortUrl != null) CopyText("http://" + _shortUrl, ShortCopy, "Copy short link"); }
        private void ShortNew_Click(object sender, RoutedEventArgs e) { AskShort(); PaintShort(); }

        // ---------------------------------------------------------------- actions

        private void Copy_Click(object sender, RoutedEventArgs e) { if (AirWebHost.Session?.Link is { } l) CopyText(l, CopyButton, "Copy"); }

        private static void CopyText(string text, Button b, string back)
        {
            try { Clipboard.SetText(text); b.Content = "Copied"; }
            catch { b.Content = "Couldn't copy"; }
            var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
            t.Tick += (_, _) => { b.Content = back; t.Stop(); };
            t.Start();
        }

        private void OtherAddress_Click(object sender, RoutedEventArgs e) => AirWebHost.Session?.NextAddress();

        private void NewLink_Click(object sender, RoutedEventArgs e)
        {
            if (MessageBox.Show(this, "The old link and QR code stop working for anyone new. Pages that are already open will show a message with the new link.",
                    "Make a new link?", MessageBoxButton.OKCancel, MessageBoxImage.Question) == MessageBoxResult.OK)
            {
                AirWebHost.Session?.NewLink();
                _shortUrl = null;
                Refresh();
            }
        }

        private void AddFiles_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new OpenFileDialog { Multiselect = true, Title = "Add files to AirWeb" };
            if (dlg.ShowDialog(this) == true) AirWebHost.Session?.Add(dlg.FileNames);
        }

        private void Window_Drop(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop) && e.Data.GetData(DataFormats.FileDrop) is string[] files)
                AirWebHost.Session?.Add(files.Where(System.IO.File.Exists));
        }

        private void Stop_Click(object sender, RoutedEventArgs e) => AirWebHost.Stop(); // Refresh() then closes the window
    }
}
