using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using AirBridge.Net;
using Shapes = System.Windows.Shapes;

namespace AirBridge.Drop
{
    /// <summary>
    /// Live transfer cards above the taskbar on the right, one per transfer (sending and receiving), plus the
    /// undo-send card. Cards stay until closed unless "hide finished cards" is on. UI thread only, except
    /// <see cref="Start"/>'s event hook.
    /// </summary>
    internal static class TransferHud
    {
        private static readonly HudState State = new();
        private static readonly Dictionary<Guid, Action> Cleanups = new();
        private static HudWindow? _win;
        private static DispatcherTimer? _clock;
        private static int _refreshQueued;
        private static Dispatcher? _ui;

        /// <summary>Call once on the UI thread after the network half exists.</summary>
        public static void Start()
        {
            _ui = Dispatcher.CurrentDispatcher;
            if (App.Peers != null) App.Peers.TransfersChanged += QueueRefresh;
            DropSettings.Changed += () => _ui.BeginInvoke(new Action(Changed));
            _clock = new DispatcherTimer(DispatcherPriority.Background) { Interval = TimeSpan.FromMilliseconds(66) };
            _clock.Tick += (_, _) => Changed();
        }

        /// <summary>Any thread: coalesces the board's change bursts into one refresh.</summary>
        private static void QueueRefresh()
        {
            if (Interlocked.Exchange(ref _refreshQueued, 1) == 1) return;
            _ui?.BeginInvoke(new Action(() =>
            {
                Interlocked.Exchange(ref _refreshQueued, 0);
                var board = App.Peers?.Transfers;
                if (board == null) return;
                var now = DateTime.UtcNow;
                foreach (var v in board.Snapshot()) State.Ingest(v, now);
                Changed();
            }));
        }

        // ------------------------------------------------------------------ sends started from the shelf

        /// <summary>Sends after a 3 second undo window (one device connected).</summary>
        public static void QueueSend(IReadOnlyList<string> files, PeerInfo peer, Action? cleanup = null)
        {
            if (files.Count == 0) { cleanup?.Invoke(); return; }
            var c = State.QueueSend(files, peer.Id, peer.Name, DropFiles.Title(files), DateTime.UtcNow);
            if (cleanup != null) Cleanups[c.Id] = cleanup;
            Changed();
        }

        /// <summary>Sends right away, with a card waiting for the transfer's first report.</summary>
        public static void SendNow(IReadOnlyList<string> files, PeerInfo peer, Action? cleanup = null)
        {
            if (files.Count == 0) { cleanup?.Invoke(); return; }
            var c = State.QueueSend(files, peer.Id, peer.Name, DropFiles.Title(files), DateTime.UtcNow, TimeSpan.Zero);
            if (cleanup != null) Cleanups[c.Id] = cleanup;
            Changed();
        }

        private static void Launch(HudCard card, IReadOnlyList<string> files)
        {
            var peers = App.Peers;
            Cleanups.Remove(card.Id, out var cleanup);
            if (peers == null || !peers.Peers().Any(p => p.Id == card.PeerId))
            {
                State.Fail(card.Id, $"{card.PeerName} is not connected", DateTime.UtcNow);
                cleanup?.Invoke();
                return;
            }
            var id = card.Id;
            Task.Run(async () =>
            {
                TransferResult res;
                try { res = await peers.SendFilesAsync(card.PeerId, files, null, CancellationToken.None).ConfigureAwait(false); }
                catch (Exception ex) { res = new TransferResult(false, ex.Message, Array.Empty<string>()); }
                try { cleanup?.Invoke(); } catch { }
                _ui?.BeginInvoke(new Action(() =>
                {
                    // Failures before the board knew the transfer (not connected, nothing readable) end the card here.
                    if (!res.Ok && State.IsLaunching(id)) { State.Fail(id, res.Error ?? "Failed", DateTime.UtcNow); Changed(); }
                }));
            });
        }

        // ------------------------------------------------------------------ card actions

        public static void Undo(HudCard c)
        {
            if (State.Undo(c.Id) == null) return;
            if (Cleanups.Remove(c.Id, out var cleanup)) cleanup();
            Changed();
        }

        public static void Cancel(HudCard c)
        {
            if (c.Stage == HudStage.Countdown) { Undo(c); return; }
            if (c.TransferKey != null) App.Peers?.Transfers.Cancel(c.TransferKey);
        }

        public static void Dismiss(HudCard c)
        {
            var gone = State.Dismiss(c.Id);
            if (gone?.Stage == HudStage.Countdown && Cleanups.Remove(c.Id, out var cleanup)) cleanup();
            Changed();
        }

        public static void Retry(HudCard c)
        {
            if (!c.CanRetry) return;
            var files = c.Files.Where(File.Exists).ToList();
            if (files.Count == 0) { State.Fail(c.Id, "The files are no longer there", DateTime.UtcNow, filesGone: true); Changed(); return; }
            State.Relaunch(c.Id, DateTime.UtcNow);
            Launch(c, files);
            Changed();
        }

        public static void ShowInFolder(HudCard c)
        {
            var path = c.Status?.Saved.FirstOrDefault(File.Exists);
            if (path == null) return;
            try { Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = true }); }
            catch (Exception ex) { Log.Warn($"Show in folder failed: {ex.Message}"); }
        }

        public static void Open(HudCard c)
        {
            var saved = c.Status?.Saved.Where(File.Exists).ToList() ?? new List<string>();
            if (saved.Count != 1) { ShowInFolder(c); return; }
            try { Process.Start(new ProcessStartInfo(saved[0]) { UseShellExecute = true }); }
            catch (Exception ex) { Log.Warn($"Open failed: {ex.Message}"); }
        }

        // ------------------------------------------------------------------ clock and window

        private static void Changed()
        {
            var now = DateTime.UtcNow;
            foreach (var c in State.Due(now)) Launch(c, c.Files);
            State.Expire(now, DropSettings.AutoHideFinished);
            try
            {
                if (State.Cards.Count > 0)
                {
                    (_win ??= new HudWindow()).Sync(State.Cards);
                    _clock?.Start();
                }
                else
                {
                    _win?.Sync(State.Cards);
                    _clock?.Stop();
                }
            }
            catch (Exception ex) { Log.Error("Transfer cards failed to draw", ex); }
        }

        /// <summary>Bottom of the cards, so they sit above the drop shelf when it is shown.</summary>
        public static void Reposition() => _win?.Place();

        // ------------------------------------------------------------------ views

        private sealed class HudWindow : Window
        {
            private readonly StackPanel _stack = new() { VerticalAlignment = VerticalAlignment.Bottom };
            private readonly Dictionary<Guid, CardView> _views = new();
            public const double CardWidth = 340;

            public HudWindow()
            {
                DropUi.MakeOverlay(this);
                Title = "AirBridge transfers";
                Width = CardWidth + 24;
                SizeToContent = SizeToContent.Height;
                Content = new Border { Padding = new Thickness(12, 12, 12, 4), Child = _stack };
                SizeChanged += (_, _) => Place();
            }

            public void Place()
            {
                var wa = SystemParameters.WorkArea;
                Left = wa.Right - Width - 4;
                Top = wa.Bottom - ActualHeight - DropShelf.ReservedHeight;
            }

            public void Sync(IReadOnlyList<HudCard> cards)
            {
                var ids = new HashSet<Guid>(cards.Select(c => c.Id));
                foreach (var (id, v) in _views.Where(kv => !ids.Contains(kv.Key)).ToList())
                {
                    _views.Remove(id);
                    v.Leave(() =>
                    {
                        _stack.Children.Remove(v);
                        if (_views.Count == 0) Hide();
                    });
                }
                // Newest at the bottom, next to the taskbar.
                var ordered = cards.Reverse().ToList();
                for (int i = 0; i < ordered.Count; i++)
                {
                    var c = ordered[i];
                    if (!_views.TryGetValue(c.Id, out var v))
                    {
                        v = new CardView();
                        _views[c.Id] = v;
                        _stack.Children.Add(v);
                        v.Enter();
                    }
                    v.Update(c);
                }
                // Keep the panel order in step, touching the tree only when it differs (moving a card would reset
                // its hover state). Cards on their way out stay where they were until gone.
                var want = ordered.Select(c => (UIElement)_views[c.Id]).ToList();
                var have = _stack.Children.Cast<UIElement>().Where(want.Contains).ToList();
                if (!have.SequenceEqual(want))
                {
                    foreach (var v in want) _stack.Children.Remove(v);
                    foreach (var v in want) _stack.Children.Add(v);
                }
                if (cards.Count > 0 && !IsVisible) { Show(); Place(); }
            }
        }

        /// <summary>One card: ring with a glyph, title, device, status line and buttons.</summary>
        private sealed class CardView : Border
        {
            private const double RingSize = 36, RingWidth = 3;
            private readonly Shapes.Path _arc = DropUi.ArcPath(RingWidth, DropUi.Ring);
            private readonly TextBlock _glyph = DropUi.Glyph(DropUi.GUp, 13);
            private readonly TextBlock _count = DropUi.Text("", 13, null, FontWeights.SemiBold);
            private readonly TextBlock _title = DropUi.Text("", 13.5, null, FontWeights.SemiBold);
            private readonly TextBlock _peer = DropUi.Text("", 12, DropUi.Muted);
            private readonly TextBlock _detail = DropUi.Text("", 12, DropUi.Muted);
            private readonly StackPanel _actions = new() { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 7, 0, 0) };
            private readonly Border _close;
            private readonly Border _bar = new() { Height = 2, HorizontalAlignment = HorizontalAlignment.Left, Background = DropUi.Track, Width = 0 };
            private readonly TranslateTransform _shift = new();
            private HudCard? _card;
            private string _actionsKey = "";
            private double _spin;

            public CardView()
            {
                Width = HudWindow.CardWidth;
                Margin = new Thickness(0, 0, 0, 8);
                CornerRadius = new CornerRadius(12);
                Background = DropUi.Card;
                BorderBrush = DropUi.CardBorder;
                BorderThickness = new Thickness(1);
                RenderTransform = _shift;
                Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 16, ShadowDepth = 4, Direction = 270, Opacity = 0.35 };

                var ring = new Grid { Width = RingSize, Height = RingSize, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 1, 12, 0) };
                ring.Children.Add(new Shapes.Ellipse { Stroke = DropUi.Track, StrokeThickness = RingWidth });
                ring.Children.Add(_arc);
                ring.Children.Add(_glyph);
                _count.HorizontalAlignment = HorizontalAlignment.Center;
                _count.VerticalAlignment = VerticalAlignment.Center;
                ring.Children.Add(_count);

                var text = new StackPanel();
                text.Children.Add(_title);
                text.Children.Add(_peer);
                text.Children.Add(_detail);
                text.Children.Add(_actions);
                _detail.TextWrapping = TextWrapping.Wrap;
                _detail.MaxHeight = 34;

                _close = new Border
                {
                    Width = 20, Height = 20, CornerRadius = new CornerRadius(10), Background = DropUi.PillBg, VerticalAlignment = VerticalAlignment.Top,
                    Child = DropUi.Glyph(DropUi.GClose, 8, DropUi.Muted), Cursor = System.Windows.Input.Cursors.Hand, Opacity = 0,
                    Margin = new Thickness(8, 0, 0, 0), ToolTip = "Close",
                };
                System.Windows.Automation.AutomationProperties.SetName(_close, "Close");
                _close.MouseLeftButtonUp += (_, e) => { e.Handled = true; if (_card != null) Dismiss(_card); };

                var row = new DockPanel { Margin = new Thickness(12, 11, 10, 11), LastChildFill = true };
                DockPanel.SetDock(ring, Dock.Left);
                DockPanel.SetDock(_close, Dock.Right);
                row.Children.Add(ring);
                row.Children.Add(_close);
                row.Children.Add(text);

                var root = new Grid { ClipToBounds = true };
                root.Children.Add(row);
                _bar.VerticalAlignment = VerticalAlignment.Bottom;
                root.Children.Add(_bar);
                Child = root;

                MouseEnter += (_, _) => DropUi.Animate(_close, OpacityProperty, 1, 120);
                MouseLeave += (_, _) => { if (_card?.IsFinished != true) DropUi.Animate(_close, OpacityProperty, 0, 160); };
            }

            public void Enter()
            {
                Opacity = 0;
                _shift.Y = 18;
                DropUi.Animate(this, OpacityProperty, 1, 220);
                DropUi.Animate(_shift, TranslateTransform.YProperty, 0, 380, DropUi.Spring);
            }

            public void Leave(Action done)
            {
                IsHitTestVisible = false;
                var a = new DoubleAnimation(0, TimeSpan.FromMilliseconds(200)) { EasingFunction = DropUi.Out };
                a.Completed += (_, _) => done();
                BeginAnimation(OpacityProperty, a);
                DropUi.Animate(_shift, TranslateTransform.XProperty, 40, 200);
            }

            public void Update(HudCard c)
            {
                bool wasDone = _card?.IsDone == true;
                _card = c;
                var now = DateTime.UtcNow;
                var s = c.Status;
                _title.Text = c.Stage == HudStage.Countdown ? $"Sending {c.Title}" : c.Title;
                _peer.Text = c.Incoming ? $"From {c.PeerName}" : $"To {c.PeerName}";

                _count.Visibility = c.Stage == HudStage.Countdown ? Visibility.Visible : Visibility.Collapsed;
                _glyph.Visibility = c.Stage == HudStage.Countdown ? Visibility.Collapsed : Visibility.Visible;
                _glyph.Text = c.IsDone ? DropUi.GCheck : c.Failure != null ? (c.WasCancelledHere ? DropUi.GClose : DropUi.GError)
                            : c.Incoming ? DropUi.GDown : DropUi.GUp;
                if (c.IsDone && !wasDone) Pop();

                double fraction = 0;
                bool spinning = false;
                switch (c.Stage)
                {
                    case HudStage.Countdown:
                    {
                        double total = Math.Max(0.1, (c.DeadlineUtc - c.StartedUtc).TotalSeconds);
                        double left = Math.Max(0, (c.DeadlineUtc - now).TotalSeconds);
                        fraction = left / total;
                        _count.Text = Math.Max(1, (int)Math.Ceiling(left)).ToString();
                        _detail.Text = $"Starts in {Math.Max(1, (int)Math.Ceiling(left))} s";
                        break;
                    }
                    case HudStage.Launching:
                        spinning = true;
                        _detail.Text = "Starting…";
                        break;
                    default:
                        if (s == null) break;
                        if (s.State == TransferState.Done) { fraction = 1; _detail.Text = $"{(c.Incoming ? "Received" : "Sent")} · {TransferText.Bytes(s.Total)}"; }
                        else if (s.State == TransferState.Failed) { fraction = 0; _detail.Text = c.Failure ?? "Failed"; }
                        else if (s.Waiting) { spinning = true; _detail.Text = $"Waiting for {c.PeerName} to accept…"; }
                        else
                        {
                            fraction = s.Fraction;
                            var parts = new List<string> { $"{TransferText.Bytes(s.Done)} of {TransferText.Bytes(s.Total)}" };
                            if (s.BytesPerSec > 0) parts.Add(TransferText.Speed(s.BytesPerSec));
                            if (TransferText.TimeLeft(s.Total, s.Done, s.BytesPerSec) is string left) parts.Add(left);
                            _detail.Text = string.Join(" · ", parts);
                        }
                        break;
                }
                if (spinning)
                {
                    _spin = (_spin + 0.06) % 1;
                    _arc.Data = DropUi.Arc(RingSize, RingWidth, 0.28);
                    _arc.RenderTransformOrigin = new Point(0.5, 0.5);
                    _arc.RenderTransform = new RotateTransform(_spin * 360);
                    _arc.Opacity = 0.7;
                }
                else
                {
                    _arc.RenderTransform = Transform.Identity;
                    _arc.Opacity = 1;
                    _arc.Data = DropUi.Arc(RingSize, RingWidth, fraction);
                }
                bool active = c.Stage == HudStage.Transfer && s?.State == TransferState.Active && !s.Waiting;
                _bar.Width = active ? Width * s!.Fraction : 0;
                if (c.IsFinished) _close.Opacity = 1;

                string key = ActionsKey(c);
                if (key != _actionsKey)
                {
                    _actionsKey = key;
                    _actions.Children.Clear();
                    switch (key)
                    {
                        case "undo": _actions.Children.Add(DropUi.Pill("Undo", () => Undo(c), prominent: true)); break;
                        case "cancel": _actions.Children.Add(DropUi.Pill("Cancel", () => { if (_card != null) Cancel(_card); })); break;
                        case "received":
                            _actions.Children.Add(DropUi.Pill("Show in folder", () => { if (_card != null) ShowInFolder(_card); }));
                            _actions.Children.Add(DropUi.Pill("Open", () => { if (_card != null) Open(_card); }));
                            break;
                        case "retry": _actions.Children.Add(DropUi.Pill("Retry", () => { if (_card != null) Retry(_card); })); break;
                    }
                    _actions.Visibility = _actions.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
                }
                System.Windows.Automation.AutomationProperties.SetName(this, $"{_title.Text}, {_peer.Text}, {_detail.Text}");
            }

            private static string ActionsKey(HudCard c) => c.Stage switch
            {
                HudStage.Countdown => "undo",
                HudStage.Launching => "",
                _ => c.Status?.State switch
                {
                    TransferState.Active => "cancel",
                    TransferState.Done => c.Incoming && c.Status.Saved.Count > 0 ? "received" : "",
                    _ => c.CanRetry ? "retry" : "",
                },
            };

            private void Pop()
            {
                var st = new ScaleTransform(0.6, 0.6);
                _glyph.RenderTransformOrigin = new Point(0.5, 0.5);
                _glyph.RenderTransform = st;
                DropUi.Animate(st, ScaleTransform.ScaleXProperty, 1, 420, DropUi.Spring);
                DropUi.Animate(st, ScaleTransform.ScaleYProperty, 1, 420, DropUi.Spring);
            }
        }
    }
}
