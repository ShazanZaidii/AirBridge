using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using QRCoder;

namespace AirBridge
{
    public partial class TrayPopupControl : UserControl
    {
        private bool _eventsWired = false;

        public TrayPopupControl()
        {
            InitializeComponent();
            IsVisibleChanged += OnIsVisibleChanged;

            string wsUrl = $"ws://{GetLocalIPAddress()}:{App.Server?.Port ?? 8080}?name={Uri.EscapeDataString(Environment.MachineName)}";
            GenerateQrCode(wsUrl);

            Loaded += OnLoaded;
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            if (System.ComponentModel.DesignerProperties.GetIsInDesignMode(this)) return;

            if (!_eventsWired)
            {
                // Wire up true realtime network events
                if (App.Server != null)
                {
                    App.Server.OnClientConnected += (ip) => Dispatcher.Invoke(RefreshUIState);
                    App.Server.OnClientDisconnected += () => Dispatcher.Invoke(RefreshUIState);
                }

                if (App.Client != null)
                {
                    App.Client.OnConnected += (url) => Dispatcher.Invoke(RefreshUIState);
                    App.Client.OnDisconnected += () => Dispatcher.Invoke(RefreshUIState);
                }

                _eventsWired = true;
            }

            // Force an initial paint
            RefreshUIState();
        }

        private void OnIsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
        {
            if ((bool)e.NewValue == true)
            {
                RefreshUIState();
            }
        }

        private void RefreshUIState()
        {
            int deviceCount = 0;
            DevicePopupPanel.Children.Clear();

            // Check Android (Server)
            if (App.Server != null && App.Server.HasActiveClient)
            {
                deviceCount++;
                var phoneBtn = CreateDeviceButton($"📱 Disconnect Android ({App.Server.ActiveClientIp})");
                phoneBtn.Click += (s, ev) => { App.Server.DisconnectClient(); DevicePopup.IsOpen = false; RefreshUIState(); };
                DevicePopupPanel.Children.Add(phoneBtn);
            }

            // Check Mac (Client)
            if (App.Client != null && App.Client.IsConnected)
            {
                deviceCount++;
                var macBtn = CreateDeviceButton("💻 Disconnect Mac");
                macBtn.Click += (s, ev) => { App.Client.Disconnect(); DevicePopup.IsOpen = false; RefreshUIState(); };
                DevicePopupPanel.Children.Add(macBtn);
            }

            if (deviceCount > 0)
            {
                DeviceDropdownText.Text = $"{deviceCount} Device(s) Connected";
                DeviceDropdownText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#4ADE80"));
                DeviceDropdownChevron.Visibility = Visibility.Visible;
                DeviceDropdownBorder.Cursor = Cursors.Hand;
                ScanPromptText.Text = "Scan to add more devices";
            }
            else
            {
                DeviceDropdownText.Text = "Waiting for connection...";
                DeviceDropdownText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF9F0A"));
                DeviceDropdownChevron.Visibility = Visibility.Collapsed;
                DeviceDropdownBorder.Cursor = Cursors.Arrow;
                ScanPromptText.Text = "Scan to add device";
            }
        }

        private Button CreateDeviceButton(string text)
        {
            return new Button
            {
                Content = text,
                Background = Brushes.Transparent,
                Foreground = Brushes.White,
                BorderThickness = new Thickness(0),
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Padding = new Thickness(10, 8, 10, 8),
                Cursor = Cursors.Hand
            };
        }

        private void DeviceDropdown_Click(object sender, MouseButtonEventArgs e)
        {
            if (DevicePopupPanel.Children.Count > 0)
            {
                DevicePopup.IsOpen = true;
            }
        }

        private void GenerateQrCode(string data)
        {
            using (var generator = new QRCodeGenerator())
            {
                var qrData = generator.CreateQrCode(data, QRCodeGenerator.ECCLevel.Q);
                var qrCode = new PngByteQRCode(qrData);
                byte[] bytes = qrCode.GetGraphic(20);

                var image = new BitmapImage();
                using (var stream = new MemoryStream(bytes))
                {
                    image.BeginInit();
                    image.CacheOption = BitmapCacheOption.OnLoad;
                    image.StreamSource = stream;
                    image.EndInit();
                }
                QrCodeImage.Source = image;
            }
        }

        private string GetLocalIPAddress()
        {
            var host = System.Net.Dns.GetHostEntry(System.Net.Dns.GetHostName());
            foreach (var ip in host.AddressList)
            {
                if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !ip.ToString().EndsWith(".1"))
                    return ip.ToString();
            }
            return "127.0.0.1";
        }

        private void OpenHostDiscovery_Click(object sender, RoutedEventArgs e)
        {
            var popup = VisualTreeHelper.GetParent(this) as System.Windows.Controls.Primitives.Popup;
            if (popup != null) popup.IsOpen = false;

            Application.Current.Dispatcher.BeginInvoke(new Action(() =>
            {
                foreach (Window window in Application.Current.Windows)
                {
                    if (window is HostDiscoveryWindow) { window.Activate(); return; }
                }
                new HostDiscoveryWindow().Show();
            }));
        }

        private void OpenArrangement_Click(object sender, RoutedEventArgs e)
        {
            var popup = VisualTreeHelper.GetParent(this) as System.Windows.Controls.Primitives.Popup;
            if (popup != null) popup.IsOpen = false;

            Application.Current.Dispatcher.BeginInvoke(new Action(() =>
            {
                foreach (Window window in Application.Current.Windows)
                {
                    if (window is DisplayArrangementWindow) { window.Activate(); return; }
                }
                new DisplayArrangementWindow().Show();
            }));
        }

        private void OpenFilters_Click(object sender, RoutedEventArgs e)
        {
            var popup = VisualTreeHelper.GetParent(this) as System.Windows.Controls.Primitives.Popup;
            if (popup != null) popup.IsOpen = false;

            Application.Current.Dispatcher.BeginInvoke(new Action(() =>
            {
                foreach (Window window in Application.Current.Windows)
                {
                    if (window is ClipboardFiltersWindow) { window.Activate(); return; }
                }
                new ClipboardFiltersWindow().Show();
            }));
        }

        private void Border_Drop(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                string[] files = (string[])e.Data.GetData(DataFormats.FileDrop);
                if (files != null && files.Length > 0)
                {
                    if (files.Length > 100)
                    {
                        MessageBox.Show("AirBridge limits transfers to 100 files per batch to maintain network stability. Only the first 100 files will be sent.", "Transfer Limit Reached", MessageBoxButton.OK, MessageBoxImage.Warning);
                        files = files.Take(100).ToArray();
                    }

                    var sendWindow = new SendWindow(files);
                    sendWindow.Show();
                    sendWindow.Activate();
                }
            }
        }

        private void Quit_Click(object sender, RoutedEventArgs e) => Application.Current.Shutdown();
    }
}