using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Automation;
using System.Threading;
using AirBridge.Net;
using QRCoder;

namespace AirBridge
{
    public partial class TrayPopupControl : UserControl
    {
        private string? _qrData;
        private int _refreshQueued;

        public TrayPopupControl()
        {
            InitializeComponent();
            IsVisibleChanged += (_, e) =>
            {
                if ((bool)e.NewValue)
                {
                    App.Peers?.Secrets.OnQrShown();
                    App.Peers?.Discovery.Query();
                    Refresh();
                }
            };
            if (App.Peers != null)
            {
                App.Peers.StateChanged += QueueRefresh;
                App.Peers.TransfersChanged += QueueRefresh;
            }
            Loaded += (_, _) => Refresh();
        }

        /// <summary>Any thread: coalesces bursts into one refresh on the dispatcher.</summary>
        private void QueueRefresh()
        {
            if (Interlocked.Exchange(ref _refreshQueued, 1) == 1) return;
            Dispatcher.BeginInvoke(new Action(() => { Interlocked.Exchange(ref _refreshQueued, 0); Refresh(); }));
        }

        private Brush Res(string key) => (Brush)FindResource(key);
        private Style St(string key) => (Style)FindResource(key);

        private void Refresh()
        {
            var peers = App.Peers;
            if (peers == null) return;
            if (!IsVisible && IsLoaded) return; // re-rendered when shown

            DeviceNameText.Text = $"This PC · {peers.Identity.Name}";
            bool online = peers.Port != 0 && peers.BestAddress != null;
            StatusDot.Fill = online ? Res("AB.Accent") : Res("AB.Warning");
            StatusText.Text = online ? "Online" : peers.Port == 0 ? "Starting" : "No network";

            // control / lease status
            string? control = peers.ControlStatus;
            ControlBand.Visibility = control == null ? Visibility.Collapsed : Visibility.Visible;
            ControlText.Text = control ?? "";

            // QR
            string? uri = peers.PairingUri;
            if (uri == null)
            {
                QrCodeImage.Source = null;
                _qrData = null;
                QrPlaceholder.Text = peers.Port == 0 ? "Starting…" : "No network connection";
                QrPlaceholder.Visibility = Visibility.Visible;
            }
            else if (uri != _qrData)
            {
                _qrData = uri;
                QrCodeImage.Source = RenderQr(uri);
                QrPlaceholder.Visibility = Visibility.Collapsed;
            }
            QrCodeImage.Opacity = peers.PairingLocked ? 0.25 : 1;
            LockedText.Visibility = peers.PairingLocked ? Visibility.Visible : Visibility.Collapsed;
            AddressText.Text = online ? $"{peers.BestAddress}:{peers.Port}" : "";

            // devices
            PeersPanel.Children.Clear();
            var paired = peers.PairedPeers();
            if (paired.Count == 0)
                PeersPanel.Children.Add(new TextBlock { Text = "No paired devices yet. Scan the code below with your phone to start.", Style = St("AB.Text.Caption"), Margin = new Thickness(2, 0, 0, 0) });
            foreach (var p in paired) PeersPanel.Children.Add(BuildPeerRow(p, p.Id == peers.ControlPeerId));

            // transfers
            var transfers = peers.Transfers.Snapshot();
            TransfersSection.Visibility = transfers.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            TransfersPanel.Children.Clear();
            foreach (var t in transfers) TransfersPanel.Children.Add(BuildTransferRow(t));
        }

        private UIElement BuildPeerRow(PairedPeerView p, bool hasLease)
        {
            var row = new Border { Background = Res("AB.Card"), CornerRadius = new CornerRadius(10), Padding = new Thickness(10, 8, 6, 8), Margin = new Thickness(0, 0, 0, 6) };
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Child = grid;

            // platform glyph tile with a presence dot
            var tile = new Grid { Width = 36, Height = 36 };
            tile.Children.Add(new Border { CornerRadius = new CornerRadius(8), Background = Res("AB.Muted") });
            tile.Children.Add(new TextBlock { Style = St("AB.Glyph"), Text = (string)FindResource(HostDiscoveryWindow.PeerGlyphKey(p.Plat)) });
            tile.Children.Add(new Ellipse
            {
                Width = 10, Height = 10, Fill = p.Connected ? Res("AB.Accent") : Res("AB.Border"), Stroke = Res("AB.Card"), StrokeThickness = 2,
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, -2, -2),
            });
            grid.Children.Add(tile);

            string state = p.Connected ? "Connected" : "Offline";
            string role = p.Plat switch { "android" or "ios" => "phone", _ => p.IsHost ? "computer · you connect to it" : "computer" };
            var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(10, 0, 6, 0) };
            var nameLine = new StackPanel { Orientation = Orientation.Horizontal };
            nameLine.Children.Add(new TextBlock { Text = p.Name, Style = St("AB.Text"), FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = hasLease ? 120 : 180 });
            if (hasLease)
            {
                var badge = new Border { Background = Res("AB.AccentSoft"), CornerRadius = new CornerRadius(6), Padding = new Thickness(6, 1, 6, 1), Margin = new Thickness(6, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
                var bsp = new StackPanel { Orientation = Orientation.Horizontal };
                bsp.Children.Add(new TextBlock { Style = St("AB.Glyph"), Text = (string)FindResource("AB.G.Mouse"), FontSize = 10, Foreground = Res("AB.Accent"), Margin = new Thickness(0, 0, 4, 0) });
                bsp.Children.Add(new TextBlock { Text = "Control", FontSize = 11, Foreground = Res("AB.Accent"), FontWeight = FontWeights.SemiBold });
                badge.Child = bsp;
                nameLine.Children.Add(badge);
            }
            text.Children.Add(nameLine);
            text.Children.Add(new TextBlock { Text = $"{state} · {role}", Style = St("AB.Text.Caption"), Foreground = p.Connected ? Res("AB.Accent") : Res("AB.MutedForeground"), TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis });
            Grid.SetColumn(text, 1);
            grid.Children.Add(text);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            if (!p.Connected && p.IsHost)
            {
                var retry = IconButton("\uE72C", $"Reconnect to {p.Name}");
                retry.Click += (_, _) => App.Peers?.ConnectNow(p.Id);
                actions.Children.Add(retry);
            }
            var forget = IconButton("\uE74D", $"Forget {p.Name}");
            forget.Click += (_, _) =>
            {
                if (MessageBox.Show($"Forget {p.Name}? You will need to pair again.", "AirBridge",
                        MessageBoxButton.OKCancel, MessageBoxImage.Question) == MessageBoxResult.OK)
                    App.Peers?.Forget(p.Id);
            };
            actions.Children.Add(forget);
            Grid.SetColumn(actions, 2);
            grid.Children.Add(actions);
            AutomationProperties.SetName(row, $"{p.Name}, {state}{(hasLease ? ", in control" : "")}");
            return row;
        }

        private Button IconButton(string glyph, string label)
        {
            var b = new Button { Style = St("AB.Button.Icon"), Content = glyph, ToolTip = label };
            AutomationProperties.SetName(b, label);
            return b;
        }

        private UIElement BuildTransferRow(TransferView t)
        {
            var row = new Border { Background = Res("AB.Card"), CornerRadius = new CornerRadius(10), Padding = new Thickness(10, 8, 10, 10), Margin = new Thickness(0, 0, 0, 6) };
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Child = grid;

            var icon = new TextBlock
            {
                Style = St("AB.Glyph"), Text = t.Incoming ? "\uE896" : "\uE898", FontSize = 14, Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Top,
                Foreground = t.State == TransferState.Failed ? Res("AB.Destructive") : Res("AB.Accent"),
            };
            grid.Children.Add(icon);

            var body = new StackPanel();
            body.Children.Add(new TextBlock { Text = t.Label, Style = St("AB.Text"), TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis });
            body.Children.Add(new TextBlock { Text = (t.Incoming ? "from " : "to ") + t.PeerName + (t.State == TransferState.Failed && t.Error != null ? $" · {t.Error}" : ""), Style = St("AB.Text.Caption"), TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis });
            var bar = new ProgressBar { Style = St("AB.Progress"), Value = t.Fraction, Margin = new Thickness(0, 6, 0, 0) };
            if (t.State == TransferState.Failed) bar.Foreground = Res("AB.Destructive");
            body.Children.Add(bar);
            Grid.SetColumn(body, 1);
            grid.Children.Add(body);

            string right = t.State switch
            {
                TransferState.Done => "Done",
                TransferState.Failed => "Failed",
                _ => $"{(int)(t.Fraction * 100)}%",
            };
            var pct = new TextBlock { Text = right, Style = St("AB.Text.Caption"), Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Top, TextWrapping = TextWrapping.NoWrap };
            Grid.SetColumn(pct, 2);
            grid.Children.Add(pct);
            AutomationProperties.SetName(row, $"{(t.Incoming ? "Receiving" : "Sending")} {t.Label}, {right}");
            return row;
        }

        private static BitmapImage RenderQr(string data)
        {
            using var generator = new QRCodeGenerator();
            using var qrData = generator.CreateQrCode(data, QRCodeGenerator.ECCLevel.M);
            var png = new PngByteQRCode(qrData).GetGraphic(10);
            var image = new BitmapImage();
            using var stream = new MemoryStream(png);
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
            image.Freeze();
            return image;
        }

        private void ClosePopup()
        {
            if (VisualTreeHelper.GetParent(this) is Popup popup) popup.IsOpen = false;
            if (Parent is Popup p2) p2.IsOpen = false;
        }

        private static void ShowSingle<T>(Func<T> create) where T : Window
        {
            Application.Current.Dispatcher.BeginInvoke(new Action(() =>
            {
                var existing = Application.Current.Windows.OfType<T>().FirstOrDefault();
                if (existing != null) { existing.Activate(); return; }
                create().Show();
            }));
        }

        private void OpenHostDiscovery_Click(object sender, RoutedEventArgs e)
        {
            ClosePopup();
            ShowSingle(() => new HostDiscoveryWindow());
        }

        private void OpenArrangement_Click(object sender, RoutedEventArgs e)
        {
            ClosePopup();
            ShowSingle(() => new DisplayArrangementWindow());
        }

        private void OpenFilters_Click(object sender, RoutedEventArgs e)
        {
            ClosePopup();
            ShowSingle(() => new ClipboardFiltersWindow());
        }

        private void Border_Drop(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop) && e.Data.GetData(DataFormats.FileDrop) is string[] files && files.Length > 0)
            {
                ClosePopup();
                App.ShowSendWindow(files);
            }
        }

        private void Quit_Click(object sender, RoutedEventArgs e) => Application.Current.Shutdown();
    }
}
