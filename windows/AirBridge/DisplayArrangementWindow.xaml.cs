using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using AirBridge.Input;

namespace AirBridge
{
    /// <summary>
    /// Shows the real monitor layout (physical pixels, scaled) and lets the user drop each controllable peer onto one
    /// outer edge of the desktop's bounding box. Placements are persisted by peer id through <see cref="Layout"/>.
    /// </summary>
    public partial class DisplayArrangementWindow : Window
    {
        private const double Pad = 66;          // room around the desktop for peer tiles
        private const double Thickness = 48;    // tile depth perpendicular to its edge
        private const double Gap = 6;

        private static readonly Edge?[] EdgeChoices = { null, Edge.Left, Edge.Right, Edge.Top, Edge.Bottom };

        private double _boxL, _boxT, _boxW, _boxH;
        private bool _building;

        private string? _dragId;
        private Border? _dragTile;
        private Edge _dragEdge;
        private double _dragOffset, _dragSize;

        private sealed record Row(string Id, string Name, bool Online, PeerPlacement? Placement);

        public DisplayArrangementWindow()
        {
            InitializeComponent();
            Loaded += OnLoaded;
            Closed += OnClosed;
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            PeerDirectory.Changed += OnExternalChange;
            Layout.Changed += OnExternalChange;
            _building = true;
            SwapCmdCtrl.IsChecked = Layout.Settings().SwapCmdCtrlForMac;
            _building = false;
            Refresh();
        }

        private void OnClosed(object? sender, EventArgs e)
        {
            PeerDirectory.Changed -= OnExternalChange;
            Layout.Changed -= OnExternalChange;
        }

        private void OnExternalChange() => Dispatcher.BeginInvoke(new Action(() => { if (_dragId == null) Refresh(); }));

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left && _dragId == null && e.ButtonState == MouseButtonState.Pressed)
            {
                try { DragMove(); } catch (InvalidOperationException) { }
            }
        }

        private void Close_Click(object sender, RoutedEventArgs e) => Close();

        private void SwapCmdCtrl_Changed(object sender, RoutedEventArgs e)
        {
            if (_building) return;
            Layout.SetSwapCmdCtrlForMac(SwapCmdCtrl.IsChecked == true);
        }

        private List<Row> Rows()
        {
            var online = PeerDirectory.Current;
            if (online.Count == 0) online = PeerBus.Peers();
            var placements = Layout.Snapshot();
            var rows = new List<Row>();
            foreach (var p in online.Where(p => p.Caps.Contains("ctl_target")))
                rows.Add(new Row(p.Id, p.Name, true, placements.GetValueOrDefault(p.Id)));
            foreach (var (id, pl) in placements)
                if (pl.Edge != null && rows.All(r => r.Id != id))
                    rows.Add(new Row(id, string.IsNullOrEmpty(pl.Name) ? id[..Math.Min(8, id.Length)] : pl.Name, false, pl));
            return rows;
        }

        private void Refresh()
        {
            _building = true;
            try
            {
                LayoutCanvas.Children.Clear();
                PeerRows.Children.Clear();

                var mons = Monitors.Enumerate();
                var u = Monitors.Union(mons);
                double cw = LayoutCanvas.Width, ch = LayoutCanvas.Height;
                double scale = Math.Min((cw - 2 * Pad) / Math.Max(1, u.Width), (ch - 2 * Pad) / Math.Max(1, u.Height));
                _boxW = u.Width * scale; _boxH = u.Height * scale;
                _boxL = (cw - _boxW) / 2; _boxT = (ch - _boxH) / 2;

                var outline = new System.Windows.Shapes.Rectangle
                {
                    Width = _boxW + 2, Height = _boxH + 2,
                    Stroke = new SolidColorBrush(Color.FromRgb(0x5A, 0x5A, 0x5E)),
                    StrokeDashArray = new DoubleCollection { 3, 3 }, StrokeThickness = 1,
                };
                Canvas.SetLeft(outline, _boxL - 1); Canvas.SetTop(outline, _boxT - 1);
                LayoutCanvas.Children.Add(outline);

                int n = 1;
                foreach (var m in mons)
                {
                    var b = m.Bounds;
                    var mon = new Border
                    {
                        Width = Math.Max(4, b.Width * scale - 2), Height = Math.Max(4, b.Height * scale - 2),
                        Background = new SolidColorBrush(m.Primary ? Color.FromRgb(0x42, 0x85, 0xF4) : Color.FromRgb(0x35, 0x5F, 0xB0)),
                        CornerRadius = new CornerRadius(6),
                        Child = new TextBlock
                        {
                            Text = $"{n}{(m.Primary ? " · Primary" : "")}\n{b.Width}×{b.Height}",
                            Foreground = Brushes.White, FontSize = 12, TextAlignment = TextAlignment.Center,
                            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                        },
                        ToolTip = $"{m.Device}  {b.Width}×{b.Height} at ({b.Left},{b.Top}), {m.Scale * 100:0}%",
                    };
                    Canvas.SetLeft(mon, _boxL + (b.Left - u.Left) * scale + 1);
                    Canvas.SetTop(mon, _boxT + (b.Top - u.Top) * scale + 1);
                    LayoutCanvas.Children.Add(mon);
                    n++;
                }

                var rows = Rows();
                foreach (var r in rows)
                {
                    if (r.Placement is { } pl && EdgeExt.Parse(pl.Edge) is Edge e)
                        LayoutCanvas.Children.Add(MakeTile(r, e, pl.Offset, pl.Size));
                    PeerRows.Children.Add(MakeRow(r));
                }
                if (rows.Count == 0)
                {
                    PeerRows.Children.Add(new TextBlock
                    {
                        Text = "No controllable devices connected yet. Connect a phone or Mac to arrange it here.",
                        Foreground = new SolidColorBrush(Color.FromRgb(0x8E, 0x8E, 0x93)), FontSize = 13,
                    });
                }
            }
            finally { _building = false; }
        }

        private Border MakeTile(Row r, Edge e, double offset, double size)
        {
            var tile = new Border
            {
                Background = new SolidColorBrush(r.Online ? Color.FromRgb(0x4A, 0xDE, 0x80) : Color.FromRgb(0x6B, 0x72, 0x80)),
                CornerRadius = new CornerRadius(8),
                Cursor = Cursors.SizeAll,
                Tag = r,
                ToolTip = r.Online ? r.Name : $"{r.Name} (offline)",
                Child = new TextBlock
                {
                    Text = r.Name, Foreground = Brushes.White, FontSize = 11, FontWeight = FontWeights.Bold,
                    TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.CharacterEllipsis, TextAlignment = TextAlignment.Center,
                    HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(3),
                },
            };
            PlaceTile(tile, e, offset, size);
            tile.MouseLeftButtonDown += Tile_MouseDown;
            tile.MouseMove += Tile_MouseMove;
            tile.MouseLeftButtonUp += Tile_MouseUp;
            tile.MouseWheel += Tile_MouseWheel;
            return tile;
        }

        private void PlaceTile(Border tile, Edge e, double offset, double size)
        {
            double len = e.IsVertical() ? _boxH : _boxW;
            double along = (e.IsVertical() ? _boxT : _boxL) + offset * len;
            double span = Math.Max(16, size * len);
            switch (e)
            {
                case Edge.Left: tile.Width = Thickness; tile.Height = span; Canvas.SetLeft(tile, _boxL - Thickness - Gap); Canvas.SetTop(tile, along); break;
                case Edge.Right: tile.Width = Thickness; tile.Height = span; Canvas.SetLeft(tile, _boxL + _boxW + Gap); Canvas.SetTop(tile, along); break;
                case Edge.Top: tile.Width = span; tile.Height = Thickness; Canvas.SetLeft(tile, along); Canvas.SetTop(tile, _boxT - Thickness - Gap); break;
                default: tile.Width = span; tile.Height = Thickness; Canvas.SetLeft(tile, along); Canvas.SetTop(tile, _boxT + _boxH + Gap); break;
            }
        }

        private UIElement MakeRow(Row r)
        {
            var grid = new Grid { Margin = new Thickness(0, 3, 0, 3) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var name = new TextBlock
            {
                Text = r.Online ? r.Name : $"{r.Name}  (offline)",
                Foreground = r.Online ? Brushes.White : new SolidColorBrush(Color.FromRgb(0x8E, 0x8E, 0x93)),
                FontSize = 13, VerticalAlignment = VerticalAlignment.Center,
            };
            var combo = new ComboBox { Width = 140, Tag = r };
            foreach (var c in EdgeChoices) combo.Items.Add(c == null ? "Not arranged" : $"{c} edge");
            var current = EdgeExt.Parse(r.Placement?.Edge);
            combo.SelectedIndex = Array.IndexOf(EdgeChoices, current);
            combo.SelectionChanged += Combo_SelectionChanged;
            Grid.SetColumn(combo, 1);
            grid.Children.Add(name);
            grid.Children.Add(combo);
            return grid;
        }

        private void Combo_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_building || sender is not ComboBox { Tag: Row r } combo || combo.SelectedIndex < 0) return;
            Edge? edge = EdgeChoices[combo.SelectedIndex];
            double size = r.Placement?.Size ?? 0.5;
            double offset = r.Placement?.Edge != null ? r.Placement.Offset : (1 - size) / 2;
            Layout.Assign(r.Id, r.Name, edge, offset, size);
        }

        private void Tile_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (sender is not Border { Tag: Row r } tile || r.Placement is null) return;
            _dragId = r.Id;
            _dragTile = tile;
            _dragEdge = EdgeExt.Parse(r.Placement.Edge) ?? Edge.Right;
            _dragOffset = r.Placement.Offset;
            _dragSize = r.Placement.Size;
            tile.CaptureMouse();
            e.Handled = true; // don't start a window drag
        }

        private void Tile_MouseMove(object sender, MouseEventArgs e)
        {
            if (_dragId == null || _dragTile == null) return;
            var p = e.GetPosition(LayoutCanvas);
            // The side the pointer is most "outside" of (or nearest to, when inside the desktop box).
            var candidates = new (Edge edge, double outside)[]
            {
                (Edge.Left, _boxL - p.X),
                (Edge.Right, p.X - (_boxL + _boxW)),
                (Edge.Top, _boxT - p.Y),
                (Edge.Bottom, p.Y - (_boxT + _boxH)),
            };
            _dragEdge = candidates.OrderByDescending(c => c.outside).First().edge;
            double frac = _dragEdge.IsVertical() ? (p.Y - _boxT) / _boxH : (p.X - _boxL) / _boxW;
            _dragOffset = Math.Clamp(frac - _dragSize / 2, 0, 1 - _dragSize);
            PlaceTile(_dragTile, _dragEdge, _dragOffset, _dragSize);
        }

        private void Tile_MouseUp(object sender, MouseButtonEventArgs e)
        {
            if (_dragId == null || _dragTile == null) return;
            _dragTile.ReleaseMouseCapture();
            var r = (Row)_dragTile.Tag;
            string id = _dragId;
            _dragId = null;
            _dragTile = null;
            Layout.Assign(id, r.Name, _dragEdge, _dragOffset, _dragSize);
            e.Handled = true;
        }

        private void Tile_MouseWheel(object sender, MouseWheelEventArgs e)
        {
            if (_dragId != null || sender is not Border { Tag: Row r } || r.Placement is not { } pl || EdgeExt.Parse(pl.Edge) is not Edge edge) return;
            double size = Math.Clamp(pl.Size + (e.Delta > 0 ? 0.05 : -0.05), 0.1, 1.0);
            double centre = pl.Offset + pl.Size / 2;
            Layout.Assign(r.Id, r.Name, edge, Math.Clamp(centre - size / 2, 0, 1 - size), size);
            e.Handled = true;
        }
    }
}
