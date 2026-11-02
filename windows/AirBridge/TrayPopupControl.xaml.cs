using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AirBridge.Net;
using QRCoder;

namespace AirBridge
{
    public partial class TrayPopupControl : UserControl
    {
        private static readonly Brush Green = new SolidColorBrush(Color.FromRgb(0x4A, 0xDE, 0x80));
        private static readonly Brush Grey = new SolidColorBrush(Color.FromRgb(0x70, 0x70, 0x75));
        private string? _qrData;
        private bool _refreshQueued;

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
            if (App.Peers != null) App.Peers.StateChanged += QueueRefresh;
            Loaded += (_, _) => Refresh();
        }

        private void QueueRefresh()
        {
            if (_refreshQueued) return;
            _refreshQueued = true;
            Dispatcher.BeginInvoke(new Action(() => { _refreshQueued = false; Refresh(); }));
        }

        private void Refresh()
        {
            var peers = App.Peers;
            if (peers == null) return;

            DeviceNameText.Text = peers.Identity.Name;
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

            string code = peers.PairingCode;
            CodeText.Text = peers.PairingLocked ? "locked" : $"{code[..3]} {code[3..]}";
            AddressText.Text = peers.BestAddress != null && peers.Port != 0 ? $"{peers.BestAddress}:{peers.Port}" : "";

            PeersPanel.Children.Clear();
            var paired = peers.PairedPeers();
            if (paired.Count == 0)
            {
                PeersPanel.Children.Add(new TextBlock { Text = "No paired devices yet", Foreground = Grey, FontSize = 12 });
            }
            foreach (var p in paired) PeersPanel.Children.Add(BuildPeerRow(p));
        }

        private UIElement BuildPeerRow(PairedPeerView p)
        {
            var grid = new Grid { Margin = new Thickness(0, 2, 0, 2) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var dot = new System.Windows.Shapes.Ellipse
            {
                Width = 8, Height = 8, Fill = p.Connected ? Green : Grey,
                VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 8, 0),
            };
            Grid.SetColumn(dot, 0);
            grid.Children.Add(dot);

            string icon = p.Plat switch { "android" or "ios" => "📱", "macos" => "💻", _ => "🖥️" };
            string state = p.Connected ? "Connected" : p.IsHost ? "Offline · click to retry" : "Offline";
            var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(new TextBlock { Text = $"{icon} {p.Name}", Foreground = Brushes.White, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis });
            text.Children.Add(new TextBlock { Text = state, Foreground = p.Connected ? Green : Grey, FontSize = 10 });
            Grid.SetColumn(text, 1);
            grid.Children.Add(text);
            if (!p.Connected && p.IsHost)
            {
                text.Cursor = Cursors.Hand;
                text.MouseLeftButtonUp += (_, _) => App.Peers?.ConnectNow(p.Id);
            }

            var forget = new Button
            {
                Content = "Forget", FontSize = 11, Height = 22, Padding = new Thickness(8, 0, 8, 0), Margin = new Thickness(6, 0, 0, 0),
                Style = (Style)FindResource("FlatButton"), ToolTip = "Unpair this device",
            };
            forget.Click += (_, _) =>
            {
                if (MessageBox.Show($"Forget {p.Name}? You will need to pair again.", "AirBridge",
                        MessageBoxButton.OKCancel, MessageBoxImage.Question) == MessageBoxResult.OK)
                    App.Peers?.Forget(p.Id);
            };
            Grid.SetColumn(forget, 2);
            grid.Children.Add(forget);
            return grid;
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
