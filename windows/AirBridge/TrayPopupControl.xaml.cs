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
using AirBridge.Input;
using System.Windows.Automation;
using System.Threading;
using AirBridge.Net;
using AirBridge.Net.Link;
using QRCoder;

namespace AirBridge
{
    public partial class TrayPopupControl : UserControl
    {
        private string? _qrData;
        private int _refreshQueued;
        private bool _addOpen, _addTouched;

        /// <summary>The popup (or window) asks its host to close it.</summary>
        public event Action? RequestClose;
        /// <summary>True inside a normal window: no "open in a window" button.</summary>
        public bool Embedded { get; set; }

        public TrayPopupControl()
        {
            InitializeComponent();
            Loaded += (_, _) =>
            {
                OpenWindowButton.Visibility = Embedded ? Visibility.Collapsed : Visibility.Visible;
                App.Peers?.Secrets.OnQrShown();
                App.Peers?.Discovery.Boost();
                Refresh();
                _ = CheckFirewallAsync();
            };
            Unloaded += (_, _) => Detach();
            if (App.Peers != null)
            {
                App.Peers.StateChanged += QueueRefresh;
                App.Peers.TransfersChanged += QueueRefresh;
            }
            AirBridge.AirWeb.AirWebHost.StateChanged += QueueRefresh;
        }

        /// <summary>Lets go of the services' events, so a thrown-away popup can be freed (it is built on demand).</summary>
        public void Detach()
        {
            if (App.Peers != null) { App.Peers.StateChanged -= QueueRefresh; App.Peers.TransfersChanged -= QueueRefresh; }
            AirBridge.AirWeb.AirWebHost.StateChanged -= QueueRefresh;
        }

        /// <summary>Any thread: coalesces bursts into one refresh on the dispatcher.</summary>
        private void QueueRefresh()
        {
            if (Interlocked.Exchange(ref _refreshQueued, 1) == 1) return;
            Dispatcher.BeginInvoke(new Action(() => { Interlocked.Exchange(ref _refreshQueued, 0); Refresh(); }));
        }

        private Brush Res(string key) => (Brush)FindResource(key);
        private Style St(string key) => (Style)FindResource(key);

        private async System.Threading.Tasks.Task CheckFirewallAsync()
        {
            try
            {
                var ok = await FirewallHelper.IsConfiguredAsync();
                if (!IsLoaded) return;
                FirewallBand.Visibility = ok == false ? Visibility.Visible : Visibility.Collapsed;
            }
            catch { }
        }

        private void Refresh()
        {
            var peers = App.Peers;
            if (peers == null) return;
            if (!IsLoaded && !IsVisible) { /* first paint happens in Loaded */ }

            DeviceNameText.Text = peers.Port != 0 && peers.BestAddress != null ? $"{peers.BestAddress}:{peers.Port}" : "";
            if (RenameEdit.Visibility != Visibility.Visible) RenameName.Text = peers.Identity.Name;
            var aw = AirBridge.AirWeb.AirWebHost.Session;
            AirWebRow.Visibility = aw != null ? Visibility.Visible : Visibility.Collapsed;
            if (aw != null) { int n = aw.Peers.Count; AirWebRowSub.Text = n == 0 ? "No one connected yet" : n == 1 ? "1 device connected" : $"{n} devices connected"; }
            int unseen = Phone.PhoneNotifHub.Unseen;
            bool anyPhoneNotifs = Phone.PhoneNotifHub.SourcePhones().Count > 0 || Phone.PhoneNotifHub.Store.Count > 0;
            PhoneNotifsText.Text = unseen > 0 ? $"{unseen} new" : "Notifications";
            PhoneNotifsButton.IsEnabled = anyPhoneNotifs;
            PhoneNotifsButton.Opacity = anyPhoneNotifs ? 1 : 0.45;
            AirWebDot.Visibility = AirBridge.AirWeb.AirWebHost.IsOn ? Visibility.Visible : Visibility.Collapsed;

            DropShelfSwitch.IsChecked = AirBridge.Drop.DropSettings.ShelfEnabled;
            AutoHideSwitch.IsChecked = AirBridge.Drop.DropSettings.AutoHideFinished;
            NotifsSwitch.IsChecked = Phone.PhoneSettings.ShowNotifs;
            CallsSwitch.IsChecked = Phone.PhoneSettings.ShowCalls;
            RingModeText.Text = Phone.PhoneSettings.RingMode switch
            {
                Phone.RingMode.Always => "Always",
                Phone.RingMode.Never => "Never",
                _ => "Smart",
            };
            RingRow.Visibility = Phone.PhoneSettings.ShowCalls ? Visibility.Visible : Visibility.Collapsed;
            StartupRow.Visibility = Startup.Supported ? Visibility.Visible : Visibility.Collapsed;
            if (Startup.Supported) StartupSwitch.IsChecked = Startup.Enabled;
            GlassRow.Visibility = UiKit.GlassSupported ? Visibility.Visible : Visibility.Collapsed;
            GlassSwitch.IsChecked = UiSettings.Glass;

            bool online = peers.Port != 0 && peers.BestAddress != null;
            StatusDot.Fill = online ? Res("AB.Success") : Res("AB.Warning");
            StatusText.Text = online ? "Online" : peers.Port == 0 ? "Starting" : "No network";

            // control / lease status
            string? control = peers.ControlStatus;
            ControlBand.Visibility = control == null ? Visibility.Collapsed : Visibility.Visible;
            ControlText.Text = control ?? "";

            // devices
            PeersPanel.Children.Clear();
            var paired = peers.PairedPeers();
            var joinCapable = peers.Peers().Where(x => x.Caps.Contains(LinkWire.CapJoin)).Select(x => x.Id).ToHashSet();
            if (paired.Count == 0)
                PeersPanel.Children.Add(new TextBlock { Text = "No devices yet. Use Add device below.", Style = St("AB.Text.Caption"), Margin = new Thickness(2, 0, 0, 0) });
            foreach (var p in paired.OrderByDescending(x => x.Connected).ThenBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase))
                PeersPanel.Children.Add(BuildPeerRow(p, p.Id == peers.ControlPeerId, peers.Links.StatusFor(p.Id), joinCapable.Contains(p.Id)));
            if (peers.PendingStaleOffer is { } offer) PeersPanel.Children.Add(BuildStaleBanner(offer, peers));

            // Add a device opens by itself when nothing is paired, and otherwise only when asked.
            if (!_addTouched) _addOpen = paired.Count == 0;
            AddPanel.Visibility = _addOpen ? Visibility.Visible : Visibility.Collapsed;
            if (_addOpen)
            {
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
                    QrCodeImage.Source = UiKit.RenderQr(uri, 6);
                    QrPlaceholder.Visibility = Visibility.Collapsed;
                }
                QrCodeImage.Opacity = peers.PairingLocked ? 0.25 : 1;
                LockedText.Visibility = peers.PairingLocked ? Visibility.Visible : Visibility.Collapsed;
                AddressText.Text = online ? $"{peers.BestAddress}:{peers.Port}" : "";
            }
            else { QrCodeImage.Source = null; _qrData = null; } // not kept in memory while closed

            // transfers
            var transfers = peers.Transfers.Snapshot();
            TransfersSection.Visibility = transfers.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            TransfersPanel.Children.Clear();
            foreach (var t in transfers) TransfersPanel.Children.Add(BuildTransferRow(t));
        }

        private UIElement BuildPeerRow(PairedPeerView p, bool hasLease, LinkStatusView link, bool joinCapable)
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
                Width = 10, Height = 10, Fill = p.Connected ? Res("AB.Success") : Res("AB.Border"), Stroke = Res("AB.Card"), StrokeThickness = 2,
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, -2, -2),
            });
            grid.Children.Add(tile);

            string state = p.Connected ? "Connected" : p.IsHost ? "Not connected" : "Waiting for it to connect";
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
            string battery = p.Connected && DeviceBatteryStore.Get(p.Id) is { } b ? $" · {b.Pct}%{(b.Charging ? " charging" : "")}" : "";
            text.Children.Add(new TextBlock { Text = $"{state} · {role}{battery}", Style = St("AB.Text.Caption"), Foreground = p.Connected ? Res("AB.Foreground") : Res("AB.MutedForeground"), TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis });
            // Wi-Fi Direct "Fast link" chip (§10.1): shown for phones that can join one, or while a link is not Off.
            if ((p.Connected && joinCapable) || link.Phase != LinkPhase.Off)
                text.Children.Add(BuildLinkChip(link));
            Grid.SetColumn(text, 1);
            grid.Children.Add(text);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var mirrorPeer = p.Connected ? PeerDirectory.Find(p.Id) : null;
            if (mirrorPeer != null && (PhoneMirrorHub.CanMirror(mirrorPeer) || PhoneMirrorHub.CanShowScreen(mirrorPeer)))
            {
                bool computer = PhoneMirrorHub.IsComputer(mirrorPeer);
                var mirror = IconButton(computer ? "\uE7F4" : (string)FindResource("AB.G.Phone"), computer ? $"Show {p.Name}'s screen" : $"Mirror {p.Name}");
                mirror.Click += (_, _) => PhoneMirrorWindows.Show(mirrorPeer);
                actions.Children.Add(mirror);
            }
            if (p.Connected)
            {
                var disconnect = IconButton("\uE711", $"Disconnect from {p.Name} (keeps the pairing)");
                disconnect.Click += (_, _) => App.Peers?.Disconnect(p.Id);
                actions.Children.Add(disconnect);
            }
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

        /// <summary>Entries left behind by earlier installs of a device that is connected now: the user decides, nothing is guessed.</summary>
        private UIElement BuildStaleBanner(PeerService.StaleOffer offer, PeerService peers)
        {
            var box = new Border { Background = Res("AB.Card"), CornerRadius = new CornerRadius(10), Padding = new Thickness(10, 8, 10, 8), Margin = new Thickness(0, 0, 0, 6) };
            var sp = new StackPanel();
            sp.Children.Add(new TextBlock
            {
                Text = offer.Ids.Count == 1
                    ? $"1 older entry named “{offer.Name}” may be an earlier install of this device."
                    : $"{offer.Ids.Count} older entries named “{offer.Name}” may be earlier installs of this device.",
                Style = St("AB.Text.Caption"), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 6),
            });
            var buttons = new StackPanel { Orientation = Orientation.Horizontal };
            var remove = new Button { Content = "Remove old", Style = St("AB.Button.Secondary"), Margin = new Thickness(0, 0, 8, 0) };
            remove.Click += (_, _) => peers.ResolveStale(remove: true);
            var keep = new Button { Content = "Keep", Style = St("AB.Button.Ghost") };
            keep.Click += (_, _) => peers.ResolveStale(remove: false);
            buttons.Children.Add(remove);
            buttons.Children.Add(keep);
            sp.Children.Add(buttons);
            box.Child = sp;
            AutomationProperties.SetName(box, "Older entries of a device that is connected now");
            return box;
        }

        private UIElement BuildLinkChip(LinkStatusView link)
        {
            var (bg, fg) = link.Phase switch
            {
                LinkPhase.Ready => ("AB.AccentSoft", "AB.Accent"),
                LinkPhase.Failed => ("AB.Muted", "AB.Warning"),
                LinkPhase.Off => ("AB.Muted", "AB.MutedForeground"),
                _ => ("AB.Muted", "AB.Foreground"),
            };
            var chip = new Border
            {
                Background = Res(bg), CornerRadius = new CornerRadius(6), Padding = new Thickness(6, 1, 6, 1), Margin = new Thickness(0, 3, 0, 0),
                HorizontalAlignment = HorizontalAlignment.Left,
                ToolTip = link.Phase == LinkPhase.Failed ? LinkFailText(link.Reason) : "Wi-Fi Direct link for fast transfers",
            };
            chip.Child = new TextBlock { Text = $"Fast link · {link.Text}", FontSize = 11, Foreground = Res(fg), TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis };
            AutomationProperties.SetName(chip, $"Fast link {link.Text}");
            return chip;
        }

        /// <summary>User-facing text for §10.1 link_fail reasons.</summary>
        public static string LinkFailText(string? reason) => reason switch
        {
            LinkWire.Reason.HotspotActive => "Turn off Mobile Hotspot on this PC to use a fast link.",
            LinkWire.Reason.NoGoSupport => "This PC's Wi-Fi adapter could not start a Wi-Fi Direct group.",
            LinkWire.Reason.RadioOff => "Wi-Fi is off on this PC.",
            LinkWire.Reason.NoIp => "The Wi-Fi Direct adapter got no address.",
            LinkWire.Reason.JoinFailed => "The phone could not join the PC's Wi-Fi Direct network.",
            LinkWire.Reason.ApprovalDenied => "The connection request was declined on the phone.",
            LinkWire.Reason.Vpn => "A VPN is blocking the local connection.",
            LinkWire.Reason.ApiLevel => "The phone's Android version cannot join (Android 10 or later needed).",
            LinkWire.Reason.Timeout => "The fast link did not come up in time.",
            LinkWire.Reason.Busy => "A fast link or transfer is already running.",
            LinkWire.Reason.User => "Cancelled.",
            _ => $"Fast link failed ({reason}).",
        };

        private void Title_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            // Hidden Debug window for the Wi-Fi Direct device tests (S-W1/S-W3/S-W4): Ctrl+Shift+click the title.
            if ((Keyboard.Modifiers & (ModifierKeys.Control | ModifierKeys.Shift)) != (ModifierKeys.Control | ModifierKeys.Shift)) return;
            ClosePopup();
            ShowSingle(() => new LinkDebugWindow());
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

        private void ClosePopup() => RequestClose?.Invoke();

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

        private void OpenNetworkCheck_Click(object sender, RoutedEventArgs e)
        {
            ClosePopup();
            ShowSingle(() => new NetworkCheckWindow());
        }

        private void AirWeb_Click(object sender, RoutedEventArgs e)
        {
            ClosePopup();
            AirBridge.AirWeb.AirWebHost.Show();
        }

        private void SendFiles_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new Microsoft.Win32.OpenFileDialog { Multiselect = true, Title = "Choose files to send" };
            ClosePopup();
            if (dlg.ShowDialog() == true && dlg.FileNames.Length > 0) App.ShowSendWindow(dlg.FileNames);
        }

        private void AddDevice_Click(object sender, RoutedEventArgs e)
        {
            _addTouched = true;
            _addOpen = !_addOpen;
            if (_addOpen) App.Peers?.Secrets.OnQrShown();
            Refresh();
        }

        private void OpenWindow_Click(object sender, RoutedEventArgs e)
        {
            ClosePopup();
            App.ShowMainWindow();
        }

        private void SettingsToggle_Click(object sender, RoutedEventArgs e)
        {
            bool open = SettingsPanel.Visibility != Visibility.Visible;
            SettingsPanel.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
            SettingsChevron.Text = open ? "\uE70E" : "\uE70D";
        }

        /// <summary>The name row was clicked: edit in place.</summary>
        private void RenameRow_Click(object sender, RoutedEventArgs e)
        {
            var peers = App.Peers;
            if (peers == null) return;
            NameBox.Text = peers.Identity.Name;
            RenameRow.Visibility = Visibility.Collapsed;
            RenameEdit.Visibility = Visibility.Visible;
            NameBox.Focus();
            NameBox.SelectAll();
        }

        private void NameBox_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) { NameSave_Click(sender, e); e.Handled = true; }
            else if (e.Key == Key.Escape) { NameCancel_Click(sender, e); e.Handled = true; }
        }

        private void NameSave_Click(object sender, RoutedEventArgs e)
        {
            var peers = App.Peers;
            string name = NameBox.Text.Trim();
            if (peers != null && name.Length > 0 && name != peers.Identity.Name) peers.RenameSelf(name);
            NameCancel_Click(sender, e);
        }

        private void NameCancel_Click(object sender, RoutedEventArgs e)
        {
            RenameEdit.Visibility = Visibility.Collapsed;
            RenameRow.Visibility = Visibility.Visible;
            Keyboard.ClearFocus();
            Refresh();
        }

        private void StopAirWeb_Click(object sender, RoutedEventArgs e) => AirBridge.AirWeb.AirWebHost.Stop();

        private void ToggleGlass_Click(object sender, RoutedEventArgs e) => UiSettings.Glass = GlassSwitch.IsChecked == true;

        private void ToggleStartup_Click(object sender, RoutedEventArgs e)
        {
            Startup.Enabled = StartupSwitch.IsChecked == true;
            Refresh();
        }

        private void ToggleDropShelf_Click(object sender, RoutedEventArgs e)
        {
            AirBridge.Drop.DropSettings.ShelfEnabled = !AirBridge.Drop.DropSettings.ShelfEnabled;
            Refresh();
        }

        private void ToggleCardAutoHide_Click(object sender, RoutedEventArgs e)
        {
            AirBridge.Drop.DropSettings.AutoHideFinished = !AirBridge.Drop.DropSettings.AutoHideFinished;
            Refresh();
        }

        private void OpenPhoneNotifs_Click(object sender, RoutedEventArgs e) => Phone.PhoneNotifsWindow.ShowWindow();

        private void TogglePhoneNotifs_Click(object sender, RoutedEventArgs e)
        {
            Phone.PhoneSettings.ShowNotifs = !Phone.PhoneSettings.ShowNotifs;
            Refresh();
        }

        private void TogglePhoneCalls_Click(object sender, RoutedEventArgs e)
        {
            Phone.PhoneSettings.ShowCalls = !Phone.PhoneSettings.ShowCalls;
            Refresh();
        }

        /// <summary>Smart (rings where you are) → Always → Never.</summary>
        private void RingMode_Click(object sender, RoutedEventArgs e)
        {
            Phone.PhoneSettings.RingMode = Phone.PhoneSettings.RingMode switch
            {
                Phone.RingMode.Smart => Phone.RingMode.Always,
                Phone.RingMode.Always => Phone.RingMode.Never,
                _ => Phone.RingMode.Smart,
            };
            Refresh();
        }

        private void Quit_Click(object sender, RoutedEventArgs e) => Application.Current.Shutdown();
    }
}
