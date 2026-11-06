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
        /// <summary>Devices picked for a multi-device send. Empty = normal mode: a click sends to that device.</summary>
        private readonly HashSet<string> _chosen = new();
        private readonly Dictionary<string, (Button Row, TextBlock Mark, PeerInfo Peer)> _rows = new();
        private readonly System.Windows.Threading.DispatcherTimer _hold = new() { Interval = TimeSpan.FromMilliseconds(450) };
        private string? _holdId;
        private bool _holdFired;
        private bool _sending;
        private bool _closed;

        private const string Hint = "Click a device to send. Hold, or Ctrl+click, to choose several.";

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
            _hold.Tick += (_, _) =>
            {
                _hold.Stop();
                if (_holdId == null) return;
                _holdFired = true;
                Toggle(_holdId);
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
            var targets = (App.Peers?.Peers() ?? Array.Empty<PeerInfo>()).Where(p => p.Caps.Contains("xfer")).ToList();
            _chosen.IntersectWith(targets.Select(t => t.Id));
            DeviceList.Children.Clear();
            _rows.Clear();

            if (targets.Count == 0)
            {
                DeviceList.Children.Add(new TextBlock
                {
                    Text = "No connected devices. Waiting…", Style = (Style)FindResource("AB.Text.Caption"),
                    HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 20, 0, 0),
                });
                UpdatePicking();
                return;
            }

            foreach (var p in targets)
            {
                var btn = new Button
                {
                    Style = (Style)FindResource("AB.Button.Ghost"), Background = Res("AB.Card"),
                    Height = 56, Margin = new Thickness(0, 0, 0, 8), Tag = p, IsEnabled = _files.Length > 0,
                };
                var grid = new Grid();
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                grid.Children.Add(new Border
                {
                    Width = 36, Height = 36, CornerRadius = new CornerRadius(8), Background = Res("AB.Muted"),
                    Child = new TextBlock { Style = (Style)FindResource("AB.Glyph"), Text = (string)FindResource(HostDiscoveryWindow.PeerGlyphKey(p.Platform)) },
                });
                var name = new TextBlock
                {
                    Text = p.Name, Style = (Style)FindResource("AB.Text"), FontSize = 14, FontWeight = FontWeights.SemiBold,
                    VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 8, 0),
                    TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis,
                };
                Grid.SetColumn(name, 1);
                grid.Children.Add(name);
                // Muted tick, shown only while picking several.
                var mark = new TextBlock { Style = (Style)FindResource("AB.Glyph"), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 4, 0) };
                Grid.SetColumn(mark, 2);
                grid.Children.Add(mark);
                btn.Content = grid;

                string id = p.Id;
                btn.PreviewMouseLeftButtonDown += (_, _) => { _holdId = id; _holdFired = false; _hold.Stop(); _hold.Start(); };
                btn.PreviewMouseLeftButtonUp += (_, _) => _hold.Stop();
                btn.MouseLeave += (_, _) => { if (!_holdFired) _hold.Stop(); };
                btn.Click += (_, _) =>
                {
                    if (_holdFired) { _holdFired = false; return; } // the hold already picked it
                    if (_chosen.Count > 0 || (Keyboard.Modifiers & ModifierKeys.Control) != 0) Toggle(id);
                    else StartSend(new List<PeerInfo> { p });
                };
                _rows[id] = (btn, mark, p);
                DeviceList.Children.Add(btn);
            }
            FastBox.IsEnabled = targets.Any(t => t.Caps.Contains(LinkWire.CapJoin));
            if (!FastBox.IsEnabled) FastBox.IsChecked = false;
            UpdatePicking();
        }

        private Brush Res(string key) => (Brush)FindResource(key);

        private void Toggle(string id)
        {
            if (!_chosen.Remove(id)) _chosen.Add(id);
            UpdatePicking();
        }

        /// <summary>Ticks, subtitle and the Send button follow the picked devices.</summary>
        private void UpdatePicking()
        {
            bool picking = _chosen.Count > 0;
            foreach (var (id, row) in _rows)
            {
                bool on = _chosen.Contains(id);
                row.Mark.Text = picking ? (on ? "\uEC61" : "\uEA3A") : "";
                row.Mark.Foreground = Res(on ? "AB.Foreground" : "AB.MutedForeground");
                AutomationPropertiesHelper.SetName(row.Row, picking ? $"{row.Peer.Name}, {(on ? "selected" : "not selected")}" : $"Send to {row.Peer.Name}");
            }
            if (!_sending) SubtitleText.Text = picking ? $"{_chosen.Count} selected" : Hint;
            SendButton.Visibility = picking ? Visibility.Visible : Visibility.Collapsed;
            SendButton.Content = _chosen.Count > 1 ? $"Send to {_chosen.Count}" : "Send";
            SendButton.IsEnabled = picking && _files.Length > 0;
            Grid.SetColumnSpan(CancelButton, picking ? 1 : 3);
        }

        private void Send_Click(object sender, RoutedEventArgs e) =>
            StartSend(_rows.Where(kv => _chosen.Contains(kv.Key)).Select(kv => kv.Value.Peer).ToList());

        /// <summary>Sends the files to every device in <paramref name="selected"/> side by side, one progress row each.</summary>
        private async void StartSend(List<PeerInfo> selected)
        {
            var peers = App.Peers;
            if (peers == null || selected.Count == 0 || _sending || _files.Length == 0) return;

            _sending = true;
            _hold.Stop();
            SubtitleText.Text = selected.Count == 1 ? $"To {selected[0].Name}" : $"To {selected.Count} devices";
            bool fast = FastBox.IsChecked == true;
            FastBox.IsEnabled = false;
            SendButton.Visibility = Visibility.Collapsed;
            Grid.SetColumnSpan(CancelButton, 3);
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
