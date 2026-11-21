using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using AirBridge.Net;
using AirBridge.Net.Link;

namespace AirBridge
{
    /// <summary>
    /// Hidden Wi-Fi Direct debug window (Ctrl+Shift+click the tray title) for device tests S-W1 (start/stop 10×, Started time,
    /// IP, adapter/driver, STA channel), S-W3 (hold with/without per-request FromIdAsync) and S-W4 (TCP sink/blaster on the WFD
    /// interface). Runs its own <see cref="WfdLegacyHost"/> outside LinkManager (log lid "dbg").
    /// </summary>
    public partial class LinkDebugWindow : Window
    {
        private sealed record PeerItem(string Id, string Label);

        private WfdLegacyHost? _host;
        private WfdWatchdog? _watchdog;
        private WfdCreds? _creds;
        private WfdAdapterInfo? _adapter;
        private WfdStartResult? _lastStart;
        private DateTime _startedAt;
        private string _adapterNote = "";
        private string _physNote = "";
        private string _staNote = "";
        private bool _busy;
        private TcpListener? _tcp;
        private CancellationTokenSource? _tcpCts;
        private readonly StringBuilder _logText = new();
        private readonly System.Windows.Threading.DispatcherTimer _infoTimer;

        public LinkDebugWindow()
        {
            InitializeComponent();
            foreach (var line in LinkLog.Snapshot()) _logText.AppendLine(line);
            LogBox.Text = _logText.ToString();
            LogBox.ScrollToEnd();
            LinkLog.Line += OnLogLine;
            LoadPeers();
            _infoTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            _infoTimer.Tick += (_, _) => RenderInfo();
            _infoTimer.Start();
            Closed += (_, _) =>
            {
                LinkLog.Line -= OnLogLine;
                _infoTimer.Stop();
                StopTcp();
                StopHost("window_closed");
            };
            _ = RefreshDiagnosticsAsync();
            RenderInfo();
        }

        private void LoadPeers()
        {
            var peers = App.Peers;
            if (peers == null) return;
            var items = peers.PairedPeers()
                .Select(p => new PeerItem(p.Id, $"{p.Name} ({p.Plat}, {LinkWire.Id8(p.Id)}){(p.Connected ? " · connected" : "")}"))
                .ToList();
            PeerBox.ItemsSource = items;
            PeerBox.SelectedIndex = items.FindIndex(i => peers.PairedPeers().Any(p => p.Id == i.Id && p.Plat == "android"));
            if (PeerBox.SelectedIndex < 0 && items.Count > 0) PeerBox.SelectedIndex = 0;
        }

        private void OnLogLine(string line) => Dispatcher.BeginInvoke(new Action(() =>
        {
            string stamped = $"{DateTime.Now:HH:mm:ss.fff} {line}";
            _logText.AppendLine(stamped);
            if (_logText.Length > 200_000) _logText.Remove(0, _logText.Length - 150_000);
            LogBox.AppendText(stamped + Environment.NewLine);
            if (LogBox.Text.Length > 200_000) LogBox.Text = _logText.ToString();
            LogBox.ScrollToEnd();
        }));

        // ------------------------------------------------------------------ group owner

        private WfdCreds? DeriveCreds()
        {
            var peers = App.Peers;
            if (peers == null || PeerBox.SelectedItem is not PeerItem item) { MessageBox.Show(this, "Pick a paired device first.", Title); return null; }
            byte[]? k = peers.Store.GetKey(item.Id);
            if (k == null) { MessageBox.Show(this, "No pairing key for that device.", Title); return null; }
            try { return LinkCreds.Wfd(k, peers.Identity.Id, item.Id, peers.Links.Settings.GenFor(item.Id)); }
            finally { CryptographicOperations.ZeroMemory(k); }
        }

        private async Task<bool> StartOnceAsync(WfdCreds creds)
        {
            StopHost("restart");
            var upBefore = WfdInterface.SnapshotUpIds();
            var host = new WfdLegacyHost(creds, "dbg") { DiscoverabilityNone = DiscNoneBox.IsChecked == true, KeepDevices = KeepDevicesBox.IsChecked == true };
            host.Lost += r => LinkLog.Ev("dbg", "lost", ("why", r));
            host.ClientsChanged += () => Dispatcher.BeginInvoke(new Action(RenderInfo));
            _host = host;
            _creds = creds;
            LinkLog.Ev("dbg", "start", ("ssid", creds.Ssid), ("gen", creds.Gen), ("disc_none", host.DiscoverabilityNone), ("keep", host.KeepDevices));
            var r = await host.StartAsync(CancellationToken.None);
            _lastStart = r;
            if (!r.Ok)
            {
                if (r.FailReason == LinkWire.Reason.NoGoSupport) App.Peers?.Links.Settings.MarkGoUnsupported();
                LinkLog.Ev("dbg", "fail", ("reason", r.FailReason), ("ms", r.Ms), ("attempts", r.Attempts), ("detail", r.Detail));
                RenderInfo();
                return false;
            }
            App.Peers?.Links.Settings.MarkGoStarted();
            _startedAt = DateTime.Now;
            _adapter = await WfdInterface.WaitForIpv4Async(upBefore, CancellationToken.None);
            var phys = WfdInterface.PhysicalWifi();
            LinkLog.Ev("dbg", "go_started", ("type", LinkWire.TypeWfdLegacy), ("ms", r.Ms), ("ip", _adapter?.IPv4), ("adapter", phys?.Description),
                       ("drv", phys?.DriverVersion), ("attempts", r.Attempts), ("wfd", _adapter?.Description));
            if (_adapter != null)
            {
                _watchdog = new WfdWatchdog(_adapter.Id);
                _watchdog.Lost += why => LinkLog.Ev("dbg", "lost", ("why", why));
            }
            else LinkLog.Ev("dbg", "fail", ("reason", LinkWire.Reason.NoIp));
            await RefreshDiagnosticsAsync();
            return true;
        }

        private void StopHost(string why)
        {
            if (_host == null) return;
            try { _watchdog?.Dispose(); } catch { }
            _watchdog = null;
            try { _host.Dispose(); } catch (Exception ex) { LinkLog.Ev("dbg", "stop_error", ("err", ex.Message)); }
            _host = null;
            _adapter = null;
            LinkLog.Ev("dbg", "stopped", ("why", why), ("up_s", (long)(DateTime.Now - _startedAt).TotalSeconds));
            RenderInfo();
        }

        private void SetBusy(bool busy)
        {
            _busy = busy;
            StartButton.IsEnabled = LoopButton.IsEnabled = RotateButton.IsEnabled = PeerBox.IsEnabled = !busy;
        }

        private async void Start_Click(object sender, RoutedEventArgs e)
        {
            if (_busy) return;
            var creds = DeriveCreds();
            if (creds == null) return;
            SetBusy(true);
            try { await StartOnceAsync(creds); }
            finally { SetBusy(false); RenderInfo(); }
        }

        private void Stop_Click(object sender, RoutedEventArgs e) => StopHost("user");

        private async void Loop_Click(object sender, RoutedEventArgs e)
        {
            if (_busy) return;
            var creds = DeriveCreds();
            if (creds == null) return;
            SetBusy(true);
            var times = new List<long>();
            int ok = 0, fast = 0;
            try
            {
                for (int i = 1; i <= 10; i++)
                {
                    bool started = await StartOnceAsync(creds);
                    long ms = _lastStart?.Ms ?? -1;
                    if (started) { ok++; times.Add(ms); if (ms <= 5000 && _lastStart?.Attempts == 1) fast++; }
                    LinkLog.Ev("dbg", "loop", ("run", i), ("ok", started), ("ms", ms), ("attempts", _lastStart?.Attempts), ("ip", _adapter?.IPv4));
                    await Task.Delay(3000);
                    StopHost($"loop {i}");
                    await Task.Delay(2000);
                }
                LinkLog.Ev("dbg", "loop_summary", ("started", $"{ok}/10"), ("first_try_le5s", $"{fast}/10"),
                           ("max_ms", times.Count > 0 ? times.Max() : null), ("avg_ms", times.Count > 0 ? (long)times.Average() : null));
            }
            finally { SetBusy(false); RenderInfo(); }
        }

        private void Rotate_Click(object sender, RoutedEventArgs e)
        {
            var peers = App.Peers;
            if (peers == null || PeerBox.SelectedItem is not PeerItem item) return;
            if (MessageBox.Show(this, "Rotate the Wi-Fi Direct key for this device? The phone will ask for approval to join once more.",
                                Title, MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK) return;
            int g = peers.Links.Settings.BumpGen(item.Id);
            LinkLog.Ev("dbg", "gen_rotated", ("peer", LinkWire.Id8(item.Id)), ("gen", g));
            RenderInfo();
        }

        private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshDiagnosticsAsync();

        private void ShowPsk_Changed(object sender, RoutedEventArgs e) => RenderInfo();

        private async Task RefreshDiagnosticsAsync()
        {
            var phys = await Task.Run(WfdInterface.PhysicalWifi);
            _physNote = phys == null ? "no Wi-Fi adapter found"
                : $"{phys.Description} · driver {phys.DriverVersion ?? "?"} ({phys.Provider ?? "?"}, {phys.DriverDate ?? "?"})";
            var (ch, mhz, band) = await WfdInterface.StaChannelAsync();
            _staNote = ch > 0 ? $"STA channel {ch} · {mhz} MHz · {band} GHz (netsh; the GO shares it)" : "STA channel unknown (netsh)";
            var wfd = await Task.Run(() => WfdInterface.Find(null));
            _adapterNote = wfd == null ? "no Wi-Fi Direct adapter with IPv4"
                : $"{wfd.Description} · {wfd.IPv4}/{wfd.PrefixLength} · v6 {string.Join(",", wfd.V6LinkLocal)} · driver {WfdInterface.DriverOf(wfd.Id).Version ?? "?"}";
            RenderInfo();
        }

        private void RenderInfo()
        {
            var sb = new StringBuilder();
            var peers = App.Peers;
            if (PeerBox.SelectedItem is PeerItem item && peers != null)
                sb.AppendLine($"gen {peers.Links.Settings.GenFor(item.Id)} · wfd_host advertised: {peers.Links.Settings.AdvertiseWfdHost} (started once: {peers.Links.Settings.GoStartedOnce})");
            if (_creds != null)
                sb.AppendLine($"SSID {_creds.Ssid} · passphrase {(ShowPskBox.IsChecked == true ? _creds.Psk : new string('•', 12))}");
            sb.AppendLine($"Publisher {(_host == null ? "none" : _host.StatusText)}" +
                          (_lastStart == null ? "" : $" · last start {(_lastStart.Ok ? "OK" : "FAILED " + _lastStart.FailReason)} in {_lastStart.Ms} ms ({_lastStart.Attempts} attempt(s)) {_lastStart.Detail}"));
            if (_host != null && _lastStart?.Ok == true) sb.AppendLine($"Up {(long)(DateTime.Now - _startedAt).TotalSeconds} s");
            sb.AppendLine($"GO adapter: {(_adapter != null ? $"{_adapter.Description} · {_adapter.IPv4}/{_adapter.PrefixLength} ({(_adapter.MatchedByDescription ? "by description" : "by new-interface fallback")}) · offer {string.Join(", ", _adapter.OfferAddresses())}" : _adapterNote)}");
            sb.AppendLine($"Wi-Fi: {_physNote}");
            sb.AppendLine(_staNote);
            if (_host != null)
            {
                var clients = _host.ClientSummaries();
                sb.Append($"Clients ({clients.Count}): {(clients.Count == 0 ? "none" : string.Join(" | ", clients))}");
            }
            InfoText.Text = sb.ToString().TrimEnd();
        }

        // ------------------------------------------------------------------ TCP sink / blaster (S-W4)

        private void Tcp_Click(object sender, RoutedEventArgs e)
        {
            if (_tcp != null) { StopTcp(); return; }
            if (!int.TryParse(PortBox.Text, NumberStyles.Integer, CultureInfo.InvariantCulture, out int port) || port is < 1 or > 65535)
            {
                TcpText.Text = "Invalid port.";
                return;
            }
            var bind = _adapter?.IPv4 ?? WfdInterface.Find(null)?.IPv4;
            try
            {
                _tcp = new TcpListener(bind ?? IPAddress.Any, port);
                _tcp.Server.ReceiveBufferSize = 4 << 20;
                _tcp.Server.SendBufferSize = 4 << 20;
                _tcp.Start(4);
            }
            catch (SocketException ex)
            {
                TcpText.Text = $"Listen failed: {ex.SocketErrorCode}";
                _tcp = null;
                return;
            }
            _tcpCts = new CancellationTokenSource();
            bool blast = BlastRadio.IsChecked == true;
            TcpButton.Content = "Stop";
            TcpText.Text = $"Listening on {(bind?.ToString() ?? "0.0.0.0 (no Wi-Fi Direct adapter yet!)")}:{port} · {(blast ? "blast PC → phone" : "sink phone → PC")}";
            LinkLog.Ev("dbg", "tcp_listen", ("addr", bind), ("port", port), ("mode", blast ? "blast" : "sink"));
            _ = AcceptLoopAsync(_tcp, blast, _tcpCts.Token);
        }

        private void StopTcp()
        {
            try { _tcpCts?.Cancel(); } catch { }
            try { _tcp?.Stop(); } catch { }
            _tcp = null;
            _tcpCts = null;
            TcpButton.Content = "Listen";
        }

        private async Task AcceptLoopAsync(TcpListener l, bool blast, CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                TcpClient c;
                try { c = await l.AcceptTcpClientAsync(ct); }
                catch (Exception) { return; }
                _ = Task.Run(() => RunTestAsync(c, blast, ct), CancellationToken.None);
            }
        }

        private async Task RunTestAsync(TcpClient c, bool blast, CancellationToken ct)
        {
            string remote = c.Client.RemoteEndPoint?.ToString() ?? "?";
            long sent = 0, recv = 0;
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using (c)
            using (var stop = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                stop.CancelAfter(TimeSpan.FromSeconds(10));
                c.NoDelay = true;
                var s = c.GetStream();
                var reader = Task.Run(async () =>
                {
                    var buf = new byte[1 << 20];
                    try
                    {
                        int n;
                        while ((n = await s.ReadAsync(buf, stop.Token)) > 0) Interlocked.Add(ref recv, n);
                    }
                    catch (Exception) { }
                });
                if (blast)
                {
                    var buf = new byte[1 << 20];
                    Random.Shared.NextBytes(buf);
                    try
                    {
                        while (!stop.IsCancellationRequested)
                        {
                            await s.WriteAsync(buf, stop.Token);
                            sent += buf.Length;
                        }
                    }
                    catch (Exception) { }
                    try { c.Client.Shutdown(SocketShutdown.Send); } catch { }
                }
                try { await reader.WaitAsync(TimeSpan.FromSeconds(blast ? 2 : 11)); } catch { }
            }
            double secs = Math.Max(0.001, sw.Elapsed.TotalSeconds);
            long bytes = blast ? sent : Interlocked.Read(ref recv);
            double mbps = bytes / secs / 1e6;
            LinkLog.Ev("dbg", "tcp_result", ("remote", remote), ("dir", blast ? "pc_to_phone" : "phone_to_pc"), ("bytes", bytes),
                       ("secs", secs), ("MBps", mbps));
            _ = Dispatcher.BeginInvoke(new Action(() =>
                TcpText.Text = $"{remote}: {(blast ? "PC → phone" : "phone → PC")} {bytes / 1e6:0.0} MB in {secs:0.0} s = {mbps:0.0} MB/s"));
        }

        private void CopyLog_Click(object sender, RoutedEventArgs e)
        {
            try { Clipboard.SetText(_logText.ToString()); } catch { }
        }
    }
}
