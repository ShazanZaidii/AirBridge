using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AirBridge.Net;

namespace AirBridge
{
    /// <summary>"Can't find a device?": what is working on this PC's side, the one-click firewall fix and a direct scan of the network.</summary>
    public partial class NetworkCheckWindow : Window
    {
        private CancellationTokenSource? _scan;

        public NetworkCheckWindow()
        {
            InitializeComponent();
            UiKit.Dark(this);
            UiKit.Glass(this);
            Loaded += async (_, _) => await RefreshAsync();
            Closed += (_, _) => { _scan?.Cancel(); UiKit.TrimMemory(force: true); };
        }

        private Brush Res(string key) => (Brush)FindResource(key);
        private Style St(string key) => (Style)FindResource(key);

        private void Line(bool? ok, string title, string detail)
        {
            var row = new Grid { Margin = new Thickness(0, 0, 0, 10) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(26) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            // a check, a cross, or a dash when it can't be told
            string glyph = ok == true ? "" : ok == false ? "" : "";
            row.Children.Add(new TextBlock
            {
                Style = St("AB.Glyph"), Text = glyph, FontSize = 13, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 2, 0, 0),
                Foreground = ok == true ? Res("AB.Success") : ok == false ? Res("AB.Warning") : Res("AB.MutedForeground"),
            });
            var text = new StackPanel();
            text.Children.Add(new TextBlock { Text = title, Style = St("AB.Text"), FontWeight = FontWeights.SemiBold });
            text.Children.Add(new TextBlock { Text = detail, Style = St("AB.Text.Caption") });
            Grid.SetColumn(text, 1);
            row.Children.Add(text);
            Checks.Children.Add(row);
        }

        private async Task RefreshAsync()
        {
            var peers = App.Peers;
            Checks.Children.Clear();
            if (peers == null) return;
            var addrs = NetUtil.LocalIPv4Addresses();
            Line(addrs.Count > 0, addrs.Count > 0 ? "On a network" : "Not on a network",
                addrs.Count > 0 ? string.Join(", ", addrs.Take(3)) : "Connect to Wi-Fi or Ethernet. Other devices must be on the same network.");
            Line(peers.Port != 0, peers.Port != 0 ? $"Open for connections (port {peers.Port})" : "Not open for connections",
                peers.Port != 0 ? "Phones and Macs can connect to this PC." : "AirBridge couldn't open its port (47632-47641). Something else may be using them.");
            var fw = await FirewallHelper.IsConfiguredAsync(force: true);
            Line(fw, fw == true ? "Windows Firewall allows AirBridge" : fw == false ? "Windows Firewall may be blocking other devices" : "Firewall status unknown",
                fw == true ? "Both rules are in place." : fw == false ? "Allow AirBridge below. This is the most common reason a phone can't find this PC." : "Couldn't read the firewall rules.");
            FirewallCard.Visibility = fw == false ? Visibility.Visible : Visibility.Collapsed;
            int hosts = peers.Discovery.Hosts().Count;
            Line(hosts > 0 || peers.Discovery.AnswersSeen > 0, hosts > 0 ? $"Found {hosts} device{(hosts == 1 ? "" : "s")} nearby" : "No devices announced themselves yet",
                peers.Discovery.AnswersSeen > 0 ? $"{peers.Discovery.AnswersSeen} announcements heard." : "No announcements heard. The network may block them: try scanning below.");
        }

        private async void Fix_Click(object sender, RoutedEventArgs e)
        {
            FixButton.IsEnabled = false;
            FixStatus.Text = "Waiting for Windows to ask for permission…";
            bool ok = await FirewallHelper.AddRulesAsync();
            FixStatus.Text = ok ? "Done. Other devices can reach this PC now." : "Not changed. If you declined the prompt, try again and choose Yes.";
            FixButton.IsEnabled = true;
            if (ok) { App.Peers?.Discovery.Restart(); App.Peers?.Discovery.Boost(); }
            await RefreshAsync();
        }

        private async void Scan_Click(object sender, RoutedEventArgs e)
        {
            var peers = App.Peers;
            if (peers == null) return;
            _scan?.Cancel();
            _scan = new CancellationTokenSource();
            ScanButton.IsEnabled = false; ScanBar.Visibility = Visibility.Visible; ScanResult.Text = "Looking…";
            try
            {
                var skip = peers.Discovery.Hosts().Select(h => h.Id).ToHashSet();
                var found = await NetworkScan.ScanAsync(skip, _scan.Token);
                peers.Discovery.AddSwept(found);
                ScanResult.Text = found.Count == 0
                    ? $"Nothing new on {string.Join(", ", NetworkScan.Subnets().Select(s => s + ".x"))}. Make sure AirBridge is open on the other device and both are on this network."
                    : "Found " + string.Join(", ", found.Select(f => f.Name)) + ". They are in the connect list now.";
            }
            catch (OperationCanceledException) { ScanResult.Text = ""; }
            finally { ScanButton.IsEnabled = true; ScanBar.Visibility = Visibility.Collapsed; }
            await RefreshAsync();
        }
    }
}
