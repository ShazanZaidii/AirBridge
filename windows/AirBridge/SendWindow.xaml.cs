using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using AirBridge.Net;
using AirBridge.Net.Link;

namespace AirBridge
{
    public partial class SendWindow : Window
    {
        private readonly string[] _files;
        private readonly CancellationTokenSource _cts = new();
        private readonly Dictionary<string, CheckBox> _boxes = new();
        private bool _sending;
        private bool _closed;

        public SendWindow(string[] paths)
        {
            InitializeComponent();
            _files = paths.Where(File.Exists).ToArray();
            int folders = paths.Count(Directory.Exists);
            TitleText.Text = _files.Length == 1 ? $"Send {Path.GetFileName(_files[0])}" : $"Send {_files.Length} files";
            if (folders > 0) StatusText.Text = $"{folders} folder(s) skipped — sending folders is not supported yet.";
            if (_files.Length == 0) SendButton.IsEnabled = false;

            if (App.Peers != null) App.Peers.StateChanged += OnStateChanged;
            Closed += (_, _) =>
            {
                _closed = true;
                if (App.Peers != null) App.Peers.StateChanged -= OnStateChanged;
                _cts.Cancel();
            };
            PopulateTargets();
        }

        private void OnStateChanged() => Dispatcher.BeginInvoke(new Action(() => { if (!_sending) PopulateTargets(); }));

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left) DragMove();
        }

        private void PopulateTargets()
        {
            var checkedIds = _boxes.Where(kv => kv.Value.IsChecked == true).Select(kv => kv.Key).ToHashSet();
            var targets = (App.Peers?.Peers() ?? Array.Empty<PeerInfo>()).Where(p => p.Caps.Contains("xfer")).ToList();
            DeviceList.Children.Clear();
            _boxes.Clear();

            if (targets.Count == 0)
            {
                DeviceList.Children.Add(new TextBlock
                {
                    Text = "No connected devices. Waiting…", Style = (Style)FindResource("AB.Text.Caption"),
                    HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 20, 0, 0),
                });
                SendButton.IsEnabled = false;
                return;
            }

            foreach (var p in targets)
            {
                var content = new StackPanel { Orientation = Orientation.Horizontal };
                content.Children.Add(new TextBlock { Style = (Style)FindResource("AB.Glyph"), Text = (string)FindResource(HostDiscoveryWindow.PeerGlyphKey(p.Platform)), Margin = new Thickness(4, 0, 10, 0) });
                content.Children.Add(new TextBlock { Text = p.Name, Style = (Style)FindResource("AB.Text"), FontSize = 14, VerticalAlignment = VerticalAlignment.Center });
                var cb = new CheckBox
                {
                    Content = content, Foreground = (Brush)FindResource("AB.Foreground"), Cursor = Cursors.Hand,
                    IsChecked = checkedIds.Contains(p.Id) || targets.Count == 1,
                    Margin = new Thickness(4, 4, 4, 8), VerticalContentAlignment = VerticalAlignment.Center,
                    Tag = p,
                };
                _boxes[p.Id] = cb;
                DeviceList.Children.Add(cb);
            }
            SendButton.IsEnabled = _files.Length > 0;
            FastBox.IsEnabled = targets.Any(t => t.Caps.Contains(LinkWire.CapJoin));
            if (!FastBox.IsEnabled) FastBox.IsChecked = false;
        }

        private async void Send_Click(object sender, RoutedEventArgs e)
        {
            var peers = App.Peers;
            var selected = _boxes.Values.Where(b => b.IsChecked == true).Select(b => (PeerInfo)b.Tag).ToList();
            if (peers == null || selected.Count == 0) { StatusText.Text = "Select at least one device."; return; }

            _sending = true;
            bool fast = FastBox.IsChecked == true;
            FastBox.IsEnabled = false;
            SendButton.IsEnabled = false;
            StatusText.Text = "";
            DeviceList.Children.Clear();

            var tasks = new List<Task<(PeerInfo Peer, TransferResult Result)>>();
            foreach (var p in selected)
            {
                var label = new TextBlock { Text = p.Name, Style = (Style)FindResource("AB.Text"), FontWeight = FontWeights.SemiBold };
                var bar = new ProgressBar { Style = (Style)FindResource("AB.Progress"), Margin = new Thickness(0, 6, 0, 4) };
                bool viaLink = fast && p.Caps.Contains(LinkWire.CapJoin);
                var state = new TextBlock { Text = viaLink ? "Starting fast link…" : "Waiting for the device to accept…", Style = (Style)FindResource("AB.Text.Caption") };
                var row = new StackPanel { Margin = new Thickness(0, 0, 0, 12) };
                row.Children.Add(label); row.Children.Add(bar); row.Children.Add(state);
                DeviceList.Children.Add(row);

                var progress = new Progress<double>(v =>
                {
                    bar.Value = v;
                    state.Text = $"{v:P0}";
                });
                tasks.Add(Task.Run(async () =>
                {
                    void OnLink(string? fail) => Dispatcher.BeginInvoke(new Action(() =>
                    {
                        if (state.Text.EndsWith('%')) return; // progress already reported
                        state.Text = fail == null ? "Fast link ready · waiting for the device to accept…"
                                                  : $"{TrayPopupControl.LinkFailText(fail)} Sending over the current connection…";
                    }));
                    try
                    {
                        return viaLink ? (p, await peers.SendFilesFastAsync(p.Id, _files, progress, OnLink, _cts.Token))
                                       : (p, await peers.SendFilesAsync(p.Id, _files, progress, _cts.Token));
                    }
                    catch (Exception ex) { return (p, new TransferResult(false, ex.Message, Array.Empty<string>())); }
                }).ContinueWith(t =>
                {
                    var (peer, res) = t.Result;
                    state.Text = res.Ok ? "Sent" : $"Failed: {res.Error}";
                    state.Foreground = (Brush)FindResource(res.Ok ? "AB.Accent" : "AB.Destructive");
                    if (!res.Ok) bar.Foreground = (Brush)FindResource("AB.Destructive");
                    if (res.Ok) bar.Value = 1;
                    return (peer, res);
                }, TaskScheduler.FromCurrentSynchronizationContext()));
            }

            var results = await Task.WhenAll(tasks);
            if (_closed) return;
            CancelButton.Content = "Close";
            if (results.All(r => r.Result.Ok))
            {
                StatusText.Text = "Done.";
                StatusText.Foreground = (Brush)FindResource("AB.Accent");
                await Task.Delay(1500);
                if (!_closed) Close();
            }
        }

        private void Cancel_Click(object sender, RoutedEventArgs e)
        {
            _cts.Cancel();
            Close();
        }
    }
}
