using System;
using System.Linq;
using System.Net;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using AirBridge.Net;

namespace AirBridge
{
    public partial class HostDiscoveryWindow : Window
    {
        private static readonly Brush Green = new SolidColorBrush(Color.FromRgb(0x4A, 0xDE, 0x80));
        private static readonly Brush Card = new SolidColorBrush(Color.FromRgb(0x2C, 0x2C, 0x2E));

        private (IPAddress Ip, int Port, string? Id, string Name)? _target;
        private bool _pairing;

        public HostDiscoveryWindow()
        {
            InitializeComponent();
            Loaded += (_, _) =>
            {
                App.Peers?.Discovery.Query();
                Refresh();
            };
            if (App.Peers != null) App.Peers.StateChanged += OnStateChanged;
            Closed += (_, _) => { if (App.Peers != null) App.Peers.StateChanged -= OnStateChanged; };
        }

        private void OnStateChanged() => Dispatcher.BeginInvoke(new Action(Refresh));

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left && e.OriginalSource is not TextBox) DragMove();
        }

        private void Refresh()
        {
            var peers = App.Peers;
            if (peers == null) return;
            HostsPanel.Children.Clear();

            var hosts = peers.Discovery.Hosts();
            var paired = peers.PairedPeers().ToDictionary(p => p.Id);
            if (hosts.Count == 0)
            {
                HostsPanel.Children.Add(new TextBlock
                {
                    Text = "Searching the network…\nIf nothing shows up, enter the host's address below.",
                    Foreground = Brushes.Gray, TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap,
                    HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 20, 0, 0),
                });
                return;
            }

            foreach (var host in hosts)
            {
                paired.TryGetValue(host.Id, out var pp);
                bool connected = pp?.Connected == true;
                string status = connected ? "Connected" : pp != null ? "Paired · click to reconnect" : "Click to pair";
                string addr = host.Addresses.FirstOrDefault()?.ToString() ?? "resolving…";

                var btn = new Button
                {
                    Height = 58, Background = Card, BorderThickness = new Thickness(connected ? 1 : 0),
                    BorderBrush = connected ? Green : Brushes.Transparent, Margin = new Thickness(0, 0, 0, 8),
                    Cursor = Cursors.Hand, HorizontalContentAlignment = HorizontalAlignment.Left,
                };
                var sp = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(12, 0, 12, 0) };
                sp.Children.Add(new TextBlock
                {
                    Text = host.Plat == "macos" ? "💻" : "🖥️", FontSize = 22,
                    Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center,
                });
                var textSp = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                textSp.Children.Add(new TextBlock { Text = host.Name, Foreground = Brushes.White, FontSize = 14, FontWeight = FontWeights.SemiBold });
                textSp.Children.Add(new TextBlock { Text = $"{status} · {addr}", Foreground = connected ? Green : Brushes.Gray, FontSize = 11 });
                sp.Children.Add(textSp);
                btn.Content = sp;

                btn.Click += (_, _) =>
                {
                    if (connected) return;
                    if (pp != null && pp.IsHost) { peers.ConnectNow(host.Id); return; }
                    var ip = host.Addresses.FirstOrDefault();
                    if (ip == null) { ShowStatus("Still resolving that host's address…"); return; }
                    SelectTarget(ip, host.Port, host.Id, host.Name);
                };
                HostsPanel.Children.Add(btn);
            }
        }

        private void SelectTarget(IPAddress ip, int port, string? id, string name)
        {
            _target = (ip, port, id, name);
            PairTitle.Text = $"Pair with {name}";
            PairStatus.Text = "Enter the 6-digit code shown on that device.";
            PairStatus.Foreground = Brushes.Gray;
            CodeBox.Text = "";
            PairPanel.Visibility = Visibility.Visible;
            CodeBox.Focus();
        }

        private void UseAddress_Click(object sender, RoutedEventArgs e)
        {
            string text = AddressBox.Text.Trim();
            int port = Protocol.BasePort;
            string hostPart = text;
            int colon = text.LastIndexOf(':');
            if (colon > 0 && text.Count(c => c == ':') == 1)
            {
                if (!int.TryParse(text[(colon + 1)..], out port) || port is < 1 or > 65535) { ShowStatus("Invalid port."); return; }
                hostPart = text[..colon];
            }
            if (!IPAddress.TryParse(hostPart, out var ip)) { ShowStatus("Enter an IP address such as 192.168.1.20:47632."); return; }
            SelectTarget(ip, port, null, ip.ToString());
        }

        private void CodeBox_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) Pair_Click(sender, e);
        }

        private async void Pair_Click(object sender, RoutedEventArgs e)
        {
            if (_pairing || _target is not { } t || App.Peers is not { } peers) return;
            string code = new string(CodeBox.Text.Where(char.IsDigit).ToArray());
            if (code.Length != 6) { ShowStatus("The code has 6 digits."); return; }

            _pairing = true;
            PairButton.IsEnabled = false;
            ShowStatus("Pairing…", Brushes.Gray);
            try
            {
                string? error = await peers.PairWithHostAsync(t.Ip, t.Port, code, t.Id);
                if (error == null)
                {
                    ShowStatus("Paired and connected.", Green);
                    await System.Threading.Tasks.Task.Delay(900);
                    Close();
                }
                else ShowStatus(error);
            }
            catch (Exception ex)
            {
                Log.Error("Pairing failed", ex);
                ShowStatus("Pairing failed.");
            }
            finally
            {
                _pairing = false;
                PairButton.IsEnabled = true;
            }
        }

        private void ShowStatus(string text, Brush? brush = null)
        {
            if (PairPanel.Visibility != Visibility.Visible)
            {
                PairTitle.Text = "";
                PairPanel.Visibility = Visibility.Visible;
            }
            PairStatus.Text = text;
            PairStatus.Foreground = brush ?? new SolidColorBrush(Color.FromRgb(0xFF, 0x45, 0x3A));
        }

        private void Close_Click(object sender, RoutedEventArgs e) => Close();
    }
}
