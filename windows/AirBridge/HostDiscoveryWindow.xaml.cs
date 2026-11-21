using System;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using AirBridge.Net;

namespace AirBridge
{
    /// <summary>
    /// Windows as a client (§1): lists AirBridge hosts found over mDNS and pairs by numeric comparison
    /// (§3.3 mode confirm): both screens show the same 6 digits, the user confirms here and accepts on the host.
    /// An airbridge://pair link pasted into the address box pairs in mode qr.
    /// </summary>
    public partial class HostDiscoveryWindow : Window
    {
        private bool _pairing;
        private TaskCompletionSource<bool>? _confirmTcs;
        private string _targetName = "";

        public HostDiscoveryWindow()
        {
            InitializeComponent();
            UiKit.Dark(this);
            UiKit.Glass(this);
            Loaded += (_, _) =>
            {
                App.Peers?.Discovery.Boost();
                Refresh();
                _ = ScanIfNothingAppears();
            };
            if (App.Peers != null) App.Peers.StateChanged += OnStateChanged;
            Closed += (_, _) =>
            {
                if (App.Peers != null) App.Peers.StateChanged -= OnStateChanged;
                _confirmTcs?.TrySetResult(false); // closing the window cancels a pending confirmation
            };
            AddressBox.TextChanged += (_, _) => AddressHint.Visibility = AddressBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        /// <summary>If no device has announced itself after a few seconds, look for them directly (no multicast needed).</summary>
        private async System.Threading.Tasks.Task ScanIfNothingAppears()
        {
            try
            {
                await System.Threading.Tasks.Task.Delay(6000);
                var peers = App.Peers;
                if (!IsLoaded || peers == null || peers.Discovery.Hosts().Count > 0) return;
                var found = await NetworkScan.ScanAsync(peers.Discovery.Hosts().Select(h => h.Id).ToHashSet(), default);
                peers.Discovery.AddSwept(found);
            }
            catch { }
        }

        private void Help_Click(object sender, RoutedEventArgs e)
        {
            var w = Application.Current.Windows.OfType<NetworkCheckWindow>().FirstOrDefault();
            if (w == null) { w = new NetworkCheckWindow(); w.Show(); }
            w.Activate();
        }

        private void OnStateChanged() => Dispatcher.BeginInvoke(new Action(Refresh));

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left && e.OriginalSource is not TextBox) DragMove();
        }

        private Brush Res(string key) => (Brush)FindResource(key);

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
                    Text = "Searching the network…\nIf your computer doesn't show up, make sure AirBridge is open on it and both are on the same network, or enter its address below.",
                    Style = (Style)FindResource("AB.Text.Caption"), TextAlignment = TextAlignment.Center,
                    Margin = new Thickness(8, 24, 8, 0),
                });
                return;
            }

            foreach (var host in hosts)
            {
                paired.TryGetValue(host.Id, out var pp);
                bool connected = pp?.Connected == true;
                string status = connected ? "Connected" : pp != null ? "Paired · select to reconnect" : "Select to pair";
                string addr = host.Addresses.FirstOrDefault()?.ToString() ?? "resolving…";

                var btn = new Button
                {
                    Style = (Style)FindResource("AB.Button.Ghost"),
                    Background = Res("AB.Card"), Height = 60, Margin = new Thickness(0, 0, 0, 8),
                    BorderBrush = connected ? Res("AB.Accent") : Brushes.Transparent, BorderThickness = new Thickness(connected ? 1 : 0),
                    IsEnabled = !_pairing,
                };
                AutomationPropertiesHelper.SetName(btn, $"{host.Name}, {status}");
                var grid = new Grid();
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                var tile = new Border
                {
                    Width = 36, Height = 36, CornerRadius = new CornerRadius(8), Background = Res("AB.Muted"),
                    Child = new TextBlock { Style = (Style)FindResource("AB.Glyph"), Text = (string)FindResource(PeerGlyphKey(host.Plat)) },
                };
                grid.Children.Add(tile);
                var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0) };
                text.Children.Add(new TextBlock { Text = host.Name, Style = (Style)FindResource("AB.Text"), FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis });
                text.Children.Add(new TextBlock { Text = $"{status} · {addr}", Style = (Style)FindResource("AB.Text.Caption"), Foreground = connected ? Res("AB.Accent") : Res("AB.MutedForeground"), TextWrapping = TextWrapping.NoWrap });
                Grid.SetColumn(text, 1);
                grid.Children.Add(text);
                btn.Content = grid;

                btn.Click += (_, _) =>
                {
                    if (connected || _pairing) return;
                    if (pp != null && pp.IsHost) { peers.ConnectNow(host.Id); ShowInfo(host.Name, "Reconnecting…"); return; }
                    var ip = host.Addresses.FirstOrDefault();
                    if (ip == null) { ShowInfo(host.Name, "Still resolving that computer's address…", error: true); return; }
                    _ = PairConfirmAsync(ip, host.Port, host.Id, host.Name);
                };
                HostsPanel.Children.Add(btn);
            }
        }

        internal static string PeerGlyphKey(string plat) => plat switch
        {
            "android" or "ios" => "AB.G.Phone",
            "macos" => "AB.G.Laptop",
            _ => "AB.G.Desktop",
        };

        // ------------------------------------------------------------------ confirm pairing

        private async Task PairConfirmAsync(IPAddress ip, int port, string? id, string name)
        {
            if (_pairing || App.Peers is not { } peers) return;
            SetPairing(true, name);
            ShowInfo(name, $"Connecting to {name}…", busy: true);
            try
            {
                string? error = await peers.PairWithHostAsync(ip, port, id, (digits, ct) => AskConfirmAsync(digits, ct));
                if (error == null)
                {
                    ShowInfo(_targetName, $"Paired with {_targetName}.", success: true);
                    await Task.Delay(900);
                    Close();
                    return;
                }
                ShowInfo(_targetName, error, error: true);
            }
            catch (Exception ex)
            {
                Log.Error("Pairing failed", ex);
                ShowInfo(name, "Pairing failed.", error: true);
            }
            finally
            {
                SetPairing(false, null);
            }
        }

        /// <summary>Called on a network thread with the 6 digits; resolves when the user taps Confirm/Cancel.</summary>
        private Task<bool> AskConfirmAsync(string digits, CancellationToken ct)
        {
            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            ct.Register(() =>
            {
                tcs.TrySetResult(false);
                Dispatcher.BeginInvoke(new Action(() => { ConfirmButtons.Visibility = Visibility.Collapsed; DigitsText.Visibility = Visibility.Collapsed; }));
            });
            Dispatcher.BeginInvoke(new Action(() =>
            {
                if (tcs.Task.IsCompleted) return;
                _confirmTcs = tcs;
                PairPanel.Visibility = Visibility.Visible;
                PairTitle.Text = $"Pair with {_targetName}";
                DigitsText.Text = $"{digits[..3]} {digits[3..]}";
                DigitsText.Visibility = Visibility.Visible;
                BusyBar.Visibility = Visibility.Collapsed;
                PairStatus.Foreground = Res("AB.MutedForeground");
                PairStatus.Text = $"Check that {_targetName} shows the same code, then select \"Codes match\" here and Accept on {_targetName}.";
                ConfirmButtons.Visibility = Visibility.Visible;
                ConfirmButton.IsEnabled = CancelButton.IsEnabled = true;
                ConfirmButton.Focus();
                Activate();
            }));
            return tcs.Task;
        }

        private void ConfirmPair_Click(object sender, RoutedEventArgs e)
        {
            if (_confirmTcs == null) return;
            _confirmTcs.TrySetResult(true);
            _confirmTcs = null;
            ConfirmButtons.Visibility = Visibility.Collapsed;
            BusyBar.Visibility = Visibility.Visible;
            PairStatus.Foreground = Res("AB.MutedForeground");
            PairStatus.Text = $"Waiting for {_targetName} to accept…";
        }

        private void CancelPair_Click(object sender, RoutedEventArgs e)
        {
            _confirmTcs?.TrySetResult(false);
            _confirmTcs = null;
            ConfirmButtons.Visibility = Visibility.Collapsed;
        }

        private void SetPairing(bool on, string? name)
        {
            _pairing = on;
            if (name != null) _targetName = name;
            if (!on) { _confirmTcs = null; BusyBar.Visibility = Visibility.Collapsed; ConfirmButtons.Visibility = Visibility.Collapsed; }
            foreach (var b in HostsPanel.Children.OfType<Button>()) b.IsEnabled = !on;
        }

        // ------------------------------------------------------------------ manual address / pairing link

        private void AddressBox_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) UseAddress_Click(sender, e);
        }

        private async void UseAddress_Click(object sender, RoutedEventArgs e)
        {
            if (_pairing || App.Peers is not { } peers) return;
            string text = AddressBox.Text.Trim();
            if (text.StartsWith("airbridge:", StringComparison.OrdinalIgnoreCase))
            {
                var link = PairLink.TryParse(text);
                if (link == null) { ShowInfo("Pairing link", "That pairing link is not valid or has expired.", error: true); return; }
                SetPairing(true, link.Name);
                ShowInfo(link.Name, $"Pairing with {link.Name}…", busy: true);
                try
                {
                    string? error = await peers.PairWithLinkAsync(link);
                    if (error == null) { ShowInfo(link.Name, $"Paired with {link.Name}.", success: true); await Task.Delay(900); Close(); return; }
                    ShowInfo(link.Name, error, error: true);
                }
                finally { SetPairing(false, null); }
                return;
            }

            int port = Protocol.BasePort;
            string hostPart = text;
            int colon = text.LastIndexOf(':');
            if (colon > 0 && text.Count(c => c == ':') == 1)
            {
                if (!int.TryParse(text[(colon + 1)..], out port) || port is < 1 or > 65535) { ShowInfo("Address", "Invalid port.", error: true); return; }
                hostPart = text[..colon];
            }
            if (!IPAddress.TryParse(hostPart.Trim('[', ']'), out var ip)) { ShowInfo("Address", "Enter an IP address such as 192.168.1.20:47632, or paste a pairing link.", error: true); return; }
            await PairConfirmAsync(ip, port, null, ip.ToString());
        }

        private void ShowInfo(string title, string text, bool error = false, bool success = false, bool busy = false)
        {
            PairPanel.Visibility = Visibility.Visible;
            PairTitle.Text = title;
            if (!busy) DigitsText.Visibility = Visibility.Collapsed;
            BusyBar.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
            PairStatus.Text = text;
            PairStatus.Foreground = error ? Res("AB.Destructive") : success ? Res("AB.Accent") : Res("AB.MutedForeground");
        }

        private void Close_Click(object sender, RoutedEventArgs e) => Close();
    }

    internal static class AutomationPropertiesHelper
    {
        public static void SetName(DependencyObject o, string name) => System.Windows.Automation.AutomationProperties.SetName(o, name);
    }
}
