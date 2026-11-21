using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using AirBridge.Net;
using Shapes = System.Windows.Shapes;

namespace AirBridge.Drop
{
    /// <summary>
    /// Tray icons cannot take drops, so this is a slim pill just above the notification area. Drag files onto it and
    /// it grows into a shelf of connected devices; drop on one to send. With one device a drop anywhere sends after a
    /// 3 second undo card. It hides while a full-screen app or presentation runs and can be turned off from the tray.
    /// UI thread only.
    /// </summary>
    internal static class DropShelf
    {
        private static ShelfWindow? _win;
        private static DispatcherTimer? _quietCheck;
        private static bool _quiet;

        /// <summary>Space the transfer cards leave at the bottom so they sit above the pill.</summary>
        public static double ReservedHeight => _win?.IsVisible == true ? ShelfWindow.PillHeight + ShelfWindow.Gutter + 10 : 8;

        public static void Start()
        {
            var ui = Dispatcher.CurrentDispatcher;
            DropSettings.Changed += () => ui.BeginInvoke(new Action(Apply));
            if (App.Peers != null) App.Peers.StateChanged += () => ui.BeginInvoke(new Action(() => _win?.PeersChanged()));
            _quietCheck = new DispatcherTimer(DispatcherPriority.Background) { Interval = TimeSpan.FromSeconds(2) };
            _quietCheck.Tick += (_, _) =>
            {
                bool q = UserIsBusy();
                if (q == _quiet) return;
                _quiet = q;
                Apply();
            };
            _quietCheck.Start();
            Apply();
        }

        private static void Apply()
        {
            try
            {
                bool want = DropSettings.ShelfEnabled && !_quiet;
                if (want)
                {
                    _win ??= new ShelfWindow();
                    if (!_win.IsVisible) { _win.Show(); _win.Place(); }
                }
                else if (_win?.IsVisible == true && !_win.Busy)
                {
                    _win.Hide();
                }
                TransferHud.Reposition();
            }
            catch (Exception ex) { Log.Error("Drop shelf failed", ex); }
        }

        // QUNS_BUSY (full-screen app), QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE.
        [DllImport("shell32.dll")]
        private static extern int SHQueryUserNotificationState(out int state);

        private static bool UserIsBusy()
        {
            try { return SHQueryUserNotificationState(out int s) == 0 && s is 2 or 3 or 4; }
            catch { return false; }
        }

        // ------------------------------------------------------------------ window

        private sealed class ShelfWindow : Window
        {
            public const double PillWidth = 92, PillHeight = 28, Gutter = 14;

            private readonly Grid _root = new();
            private readonly Border _pill;
            private readonly Border _dock;
            private readonly Canvas _canvas = new();
            private readonly ScaleTransform _dockScale = new(0.85, 0.85);
            private readonly TextBlock _header = DropUi.Text("", 12, DropUi.Muted, FontWeights.SemiBold);
            private readonly Border _headerClose;
            private readonly List<Bubble> _bubbles = new();
            private readonly DispatcherTimer _collapse = new() { Interval = TimeSpan.FromMilliseconds(300) };
            private readonly DispatcherTimer _holdTimeout = new() { Interval = TimeSpan.FromSeconds(30) };
            private List<PeerInfo> _targets = new();
            private DockLayout _layout = new(Array.Empty<string>());
            private bool _expanded;
            private List<string>? _held;
            private string _itemTitle = "";
            private Point? _cursor;

            /// <summary>Open or holding files: don't hide it under the user's hands.</summary>
            public bool Busy => _expanded || _held != null;

            public ShelfWindow()
            {
                DropUi.MakeOverlay(this);
                Title = "AirBridge drop shelf";
                AllowDrop = true;
                Content = _root;

                _pill = new Border
                {
                    Width = PillWidth, Height = PillHeight, CornerRadius = new CornerRadius(PillHeight / 2),
                    Background = DropUi.Card, BorderBrush = DropUi.CardBorder, BorderThickness = new Thickness(1),
                    HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom,
                    Margin = new Thickness(Gutter), Opacity = 0.72, ToolTip = "Drop files here to send them with AirBridge",
                };
                var pillContent = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                pillContent.Children.Add(DropUi.Glyph(DropUi.GDrop, 12, DropUi.Muted));
                var pillText = DropUi.Text("Drop", 12, DropUi.Muted, FontWeights.SemiBold);
                pillText.Margin = new Thickness(7, 0, 0, 1);
                pillContent.Children.Add(pillText);
                _pill.Child = pillContent;
                System.Windows.Automation.AutomationProperties.SetName(_pill, "AirBridge drop shelf");
                _pill.MouseEnter += (_, _) => DropUi.Animate(_pill, OpacityProperty, 1, 150);
                _pill.MouseLeave += (_, _) => { if (!_expanded) DropUi.Animate(_pill, OpacityProperty, 0.72, 200); };
                var menu = new ContextMenu();
                var hide = new MenuItem { Header = "Hide drop shelf" };
                hide.Click += (_, _) => DropSettings.ShelfEnabled = false;
                menu.Items.Add(hide);
                _pill.ContextMenu = menu;

                _headerClose = new Border
                {
                    Width = 18, Height = 18, CornerRadius = new CornerRadius(9), Background = DropUi.PillBg, Cursor = Cursors.Hand,
                    Child = DropUi.Glyph(DropUi.GClose, 7, DropUi.Muted), Visibility = Visibility.Collapsed, ToolTip = "Cancel",
                };
                System.Windows.Automation.AutomationProperties.SetName(_headerClose, "Cancel");
                _headerClose.MouseLeftButtonUp += (_, e) => { e.Handled = true; DropHeld(); };

                _dock = new Border
                {
                    CornerRadius = new CornerRadius(18), Background = DropUi.Card, BorderBrush = DropUi.CardBorder, BorderThickness = new Thickness(1),
                    HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(Gutter),
                    Child = _canvas, Visibility = Visibility.Collapsed, Opacity = 0,
                    RenderTransformOrigin = new Point(1, 1), RenderTransform = _dockScale,
                    Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 18, ShadowDepth = 5, Direction = 270, Opacity = 0.4 },
                };
                System.Windows.Automation.AutomationProperties.SetName(_dock, "Send with AirBridge");
                _root.Children.Add(_dock);
                _root.Children.Add(_pill);

                DragEnter += OnDragEnter;
                DragOver += OnDragOver;
                DragLeave += (_, _) => { _cursor = null; _collapse.Stop(); _collapse.Start(); };
                Drop += OnDrop;
                _collapse.Tick += (_, _) => { _collapse.Stop(); if (_held == null) Collapse(); };
                _holdTimeout.Tick += (_, _) => { _holdTimeout.Stop(); DropHeld(); };

                Rebuild();
            }

            public void Place()
            {
                var wa = SystemParameters.WorkArea;
                Left = wa.Right - Width;
                Top = wa.Bottom - Height;
            }

            public void PeersChanged()
            {
                var t = Targets();
                if (t.Select(p => p.Id).SequenceEqual(_targets.Select(p => p.Id)) && t.Select(p => p.Name).SequenceEqual(_targets.Select(p => p.Name))) return;
                Rebuild();
            }

            private static List<PeerInfo> Targets() =>
                (App.Peers?.Peers() ?? Array.Empty<PeerInfo>()).Where(p => p.Caps.Contains("xfer")).ToList();

            /// <summary>Lays the dock out for the connected devices and sizes the (mostly transparent) window to fit it.</summary>
            private void Rebuild()
            {
                _targets = Targets();
                _layout = new DockLayout(_targets.Select(p => p.Id).ToList());
                _canvas.Children.Clear();
                _bubbles.Clear();
                _canvas.Width = _layout.Width;
                _canvas.Height = _layout.Height;

                var header = new DockPanel { Width = _layout.Width - 2 * DockLayout.Padding, Height = DockLayout.Header, LastChildFill = true };
                DockPanel.SetDock(_headerClose, Dock.Right);
                if (_headerClose.Parent is Panel old) old.Children.Remove(_headerClose);
                header.Children.Add(_headerClose);
                _header.HorizontalAlignment = HorizontalAlignment.Center;
                _header.VerticalAlignment = VerticalAlignment.Center;
                if (_header.Parent is Panel oldH) oldH.Children.Remove(_header);
                header.Children.Add(_header);
                Canvas.SetLeft(header, DockLayout.Padding);
                Canvas.SetTop(header, DockLayout.Padding);
                _canvas.Children.Add(header);

                if (_layout.Mode == DockMode.None)
                {
                    var hint = new StackPanel { Width = _layout.Width - 2 * DockLayout.Padding };
                    var t1 = DropUi.Text("No devices connected", 13.5, null, FontWeights.SemiBold);
                    var t2 = DropUi.Text("Pair one from the AirBridge tray menu.", 12, DropUi.Muted);
                    t1.HorizontalAlignment = t2.HorizontalAlignment = HorizontalAlignment.Center;
                    hint.Children.Add(t1);
                    hint.Children.Add(t2);
                    Canvas.SetLeft(hint, DockLayout.Padding);
                    Canvas.SetTop(hint, DockLayout.Padding + DockLayout.Header + DockLayout.Gap + 6);
                    _canvas.Children.Add(hint);
                }
                foreach (var slot in _layout.Slots)
                {
                    var peer = _targets.First(p => p.Id == slot.PeerId);
                    var b = new Bubble(slot, peer, _layout.Mode == DockMode.Single);
                    b.Clicked += () => Pick(peer);
                    _bubbles.Add(b);
                    _canvas.Children.Add(b);
                }
                Width = Math.Max(_layout.Width, PillWidth) + 2 * Gutter;
                Height = _layout.Height + 2 * Gutter;
                if (IsVisible) Place();
                UpdateDock();
            }

            // -------------------------------------------------------------- drag and drop

            private static bool HasFiles(DragEventArgs e) => e.Data.GetDataPresent(DataFormats.FileDrop);

            private void OnDragEnter(object sender, DragEventArgs e)
            {
                _collapse.Stop();
                if (!HasFiles(e)) { e.Effects = DragDropEffects.None; e.Handled = true; return; }
                if (e.Data.GetData(DataFormats.FileDrop) is string[] paths)
                    _itemTitle = paths.Length == 1 ? System.IO.Path.GetFileName(paths[0]) : $"{paths.Length} items";
                if (!_expanded) Expand();
                OnDragOver(sender, e);
            }

            private void OnDragOver(object sender, DragEventArgs e)
            {
                _collapse.Stop();
                e.Handled = true;
                if (!HasFiles(e) || _layout.Mode == DockMode.None) { e.Effects = DragDropEffects.None; return; }
                _cursor = _expanded ? e.GetPosition(_canvas) : null;
                e.Effects = DragDropEffects.Copy;
                UpdateDock();
            }

            private void OnDrop(object sender, DragEventArgs e)
            {
                e.Handled = true;
                _collapse.Stop();
                if (!HasFiles(e) || e.Data.GetData(DataFormats.FileDrop) is not string[] paths || paths.Length == 0 || _layout.Mode == DockMode.None)
                {
                    Collapse();
                    return;
                }
                var pos = e.GetPosition(_canvas);
                var slot = _layout.Hit(pos.X, pos.Y);
                PeerInfo? target = slot != null ? _targets.FirstOrDefault(p => p.Id == slot.PeerId)
                                 : _layout.Mode == DockMode.Single ? _targets[0] : null;
                _cursor = null;
                if (target == null) _held = new List<string>(); // keep the dock up while the folders are read
                else Took(target.Id);

                bool single = _layout.Mode == DockMode.Single;
                Task.Run(() => DropFiles.Expand(paths)).ContinueWith(t =>
                {
                    var files = t.IsCompletedSuccessfully ? t.Result : new List<string>();
                    if (files.Count == 0)
                    {
                        _held = null;
                        App.Notify("AirBridge", "Only files and folders with files in them can be sent.");
                        Collapse();
                        return;
                    }
                    if (target == null) { Hold(files); return; }
                    if (single) TransferHud.QueueSend(files, target);
                    else TransferHud.SendNow(files, target);
                }, TaskScheduler.FromCurrentSynchronizationContext());
            }

            private void Hold(List<string> files)
            {
                _held = files;
                _itemTitle = DropFiles.Title(files);
                _holdTimeout.Stop();
                _holdTimeout.Start();
                if (!_expanded) Expand();
                UpdateDock();
            }

            private void Pick(PeerInfo peer)
            {
                if (_held is not { Count: > 0 } files) return;
                _held = null;
                _holdTimeout.Stop();
                Took(peer.Id);
                if (Targets().Count == 1) TransferHud.QueueSend(files, peer);
                else TransferHud.SendNow(files, peer);
            }

            private void DropHeld()
            {
                _held = null;
                _holdTimeout.Stop();
                Collapse();
            }

            /// <summary>The bubble that took the drop pops, then the dock folds away.</summary>
            private void Took(string peerId)
            {
                _bubbles.FirstOrDefault(b => b.Slot.PeerId == peerId)?.Pop();
                var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(380) };
                t.Tick += (_, _) => { t.Stop(); if (_held == null) Collapse(); };
                t.Start();
            }

            // -------------------------------------------------------------- open / close

            private void Expand()
            {
                PeersChanged();
                _expanded = true;
                _dock.Visibility = Visibility.Visible;
                DropUi.Animate(_dock, OpacityProperty, 1, 160);
                DropUi.Animate(_dockScale, ScaleTransform.ScaleXProperty, 1, 340, DropUi.Spring);
                DropUi.Animate(_dockScale, ScaleTransform.ScaleYProperty, 1, 340, DropUi.Spring);
                DropUi.Animate(_pill, OpacityProperty, 0, 120);
                _pill.IsHitTestVisible = false;
                UpdateDock();
            }

            private void Collapse()
            {
                if (!_expanded) return;
                _expanded = false;
                _cursor = null;
                DropUi.Animate(_dock, OpacityProperty, 0, 160);
                DropUi.Animate(_dockScale, ScaleTransform.ScaleXProperty, 0.85, 200);
                DropUi.Animate(_dockScale, ScaleTransform.ScaleYProperty, 0.85, 200);
                var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(210) };
                t.Tick += (_, _) =>
                {
                    t.Stop();
                    if (_expanded) return;
                    _dock.Visibility = Visibility.Collapsed;
                    _pill.IsHitTestVisible = true;
                    DropUi.Animate(_pill, OpacityProperty, 0.72, 200);
                    if (!DropSettings.ShelfEnabled) Hide();
                };
                t.Start();
            }

            private void UpdateDock()
            {
                double? cx = _cursor?.X, cy = _cursor?.Y;
                var hot = _cursor is Point p ? _layout.Hit(p.X, p.Y) : null;
                foreach (var b in _bubbles) b.Update(cx, cy, hot == b.Slot, clickable: _held is { Count: > 0 });
                _headerClose.Visibility = _held != null ? Visibility.Visible : Visibility.Collapsed;
                string what = string.IsNullOrEmpty(_itemTitle) ? "files" : _itemTitle;
                _header.Text = _held is { Count: > 0 } ? $"Choose where to send {what}"
                             : hot != null ? $"Send {what} to {_targets.FirstOrDefault(t => t.Id == hot.PeerId)?.Name}"
                             : _layout.Mode == DockMode.Single ? $"Drop to send to {_targets[0].Name}"
                             : _layout.Mode == DockMode.None ? "AirBridge"
                             : $"Send {what}";
            }
        }

        /// <summary>One round target with the device's name underneath; swells and leans towards the dragged files.</summary>
        private sealed class Bubble : Canvas
        {
            public DockSlot Slot { get; }
            public event Action? Clicked;
            private readonly Grid _disc;
            private readonly Shapes.Ellipse _circle;
            private readonly TextBlock _name;
            private readonly ScaleTransform _scale = new(1, 1);
            private readonly TranslateTransform _lean = new();
            private bool _clickable;

            public Bubble(DockSlot slot, PeerInfo peer, bool single)
            {
                Slot = slot;
                double d = slot.Diameter;
                _circle = new Shapes.Ellipse { Width = d, Height = d, Fill = DropUi.BubbleFill, Stroke = DropUi.BubbleStroke, StrokeThickness = 1 };
                _disc = new Grid { Width = d, Height = d, RenderTransformOrigin = new Point(0.5, 0.5) };
                var tg = new TransformGroup();
                tg.Children.Add(_scale);
                tg.Children.Add(_lean);
                _disc.RenderTransform = tg;
                _disc.Children.Add(_circle);
                _disc.Children.Add(DropUi.Glyph(DropUi.PlatformGlyph(peer.Platform), d * 0.36));
                SetLeft(_disc, slot.X - d / 2);
                SetTop(_disc, slot.Y - d / 2);
                Children.Add(_disc);

                double nameWidth = single ? 140 : 86;
                _name = DropUi.Text(peer.Name, single ? 12.5 : 11.5, DropUi.Muted, single ? FontWeights.SemiBold : FontWeights.Normal);
                _name.Width = nameWidth;
                _name.TextAlignment = TextAlignment.Center;
                SetLeft(_name, slot.X - nameWidth / 2);
                SetTop(_name, slot.Y + d / 2 + DockLayout.Gap);
                Children.Add(_name);

                Background = null;
                Width = 0; Height = 0;
                System.Windows.Automation.AutomationProperties.SetName(this, $"Send to {peer.Name}");
                _disc.Cursor = Cursors.Hand;
                _disc.MouseLeftButtonUp += (_, e) => { if (_clickable) { e.Handled = true; Clicked?.Invoke(); } };
                _disc.MouseEnter += (_, _) => { if (_clickable) Grow(1.08); };
                _disc.MouseLeave += (_, _) => { if (_clickable) Grow(1); };
            }

            public void Update(double? cx, double? cy, bool hot, bool clickable)
            {
                _clickable = clickable;
                double pull = DockLayout.Pull(Slot, cx, cy);
                var (dx, dy) = DockLayout.Lean(Slot, cx, cy);
                _lean.X = dx;
                _lean.Y = dy;
                if (!clickable) Grow(1 + 0.1 * pull + (hot ? 0.08 : 0));
                _circle.Fill = hot ? DropUi.BubbleHot : DropUi.BubbleFill;
                _circle.Stroke = hot ? DropUi.BubbleStrokeHot : DropUi.BubbleStroke;
                _circle.StrokeThickness = hot ? 1.5 : 1;
                _name.Foreground = hot ? DropUi.Foreground : DropUi.Muted;
            }

            private double _target = 1;

            private void Grow(double to)
            {
                if (Math.Abs(to - _target) < 0.004) return;
                _target = to;
                DropUi.Animate(_scale, ScaleTransform.ScaleXProperty, to, 220, DropUi.Spring);
                DropUi.Animate(_scale, ScaleTransform.ScaleYProperty, to, 220, DropUi.Spring);
            }

            /// <summary>A little gulp when it takes the files.</summary>
            public void Pop()
            {
                _target = 1.2;
                DropUi.Animate(_scale, ScaleTransform.ScaleXProperty, 1.2, 260, DropUi.Spring);
                DropUi.Animate(_scale, ScaleTransform.ScaleYProperty, 1.2, 260, DropUi.Spring);
            }
        }
    }
}
