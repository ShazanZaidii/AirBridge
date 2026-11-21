using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using AirBridge.Input;

namespace AirBridge
{
    /// <summary>
    /// Shows the real monitor layout (physical pixels, scaled), lets the user drop each controllable peer onto one
    /// outer edge of the desktop's bounding box, and makes the mapping explicit: per edge "→ peer", which peer is
    /// being controlled / controlling this PC (§8.6), and the push-through capture settings (§8.2).
    /// Placements are persisted by peer id through <see cref="Layout"/>.
    /// </summary>
    public partial class DisplayArrangementWindow : Window
    {
        private const double Pad = 66;          // room around the desktop for peer tiles
        private const double Thickness = 48;    // tile depth perpendicular to its edge
        private const double Gap = 6;

        private static readonly Edge?[] EdgeChoices = { null, Edge.Left, Edge.Right, Edge.Top, Edge.Bottom };
        private static readonly Edge[] AllEdges = { Edge.Left, Edge.Right, Edge.Top, Edge.Bottom };

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

        private Brush Res(string key) => (Brush)FindResource(key);

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            PeerDirectory.Changed += OnExternalChange;
            Layout.Changed += OnExternalChange;
            ControlState.Changed += OnExternalChange;
            Refresh();
        }

        private void OnClosed(object? sender, EventArgs e)
        {
            PeerDirectory.Changed -= OnExternalChange;
            Layout.Changed -= OnExternalChange;
            ControlState.Changed -= OnExternalChange;
        }

        private void OnExternalChange() => Dispatcher.BeginInvoke(new Action(() => { if (_dragId == null && IsLoaded) Refresh(); }));

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left && _dragId == null && e.ButtonState == MouseButtonState.Pressed)
            {
                try { DragMove(); } catch (InvalidOperationException) { }
            }
        }

        private void Close_Click(object sender, RoutedEventArgs e) => Close();

        // ------------------------------------------------------------------ settings

        private void SwapCmdCtrl_Changed(object sender, RoutedEventArgs e)
        {
            if (_building) return;
            Layout.SetSwapCmdCtrlForMac(SwapCmdCtrl.IsChecked == true);
        }

        private void RequireAlt_Changed(object sender, RoutedEventArgs e)
        {
            if (_building) return;
            Layout.SetCaptureRequireAlt(RequireAlt.IsChecked == true);
        }

        private void Strength_Checked(object sender, RoutedEventArgs e)
        {
            if (_building || sender is not RadioButton { Tag: string strength }) return;
            Layout.SetCaptureStrength(strength);
        }

        // ------------------------------------------------------------------ edge glow colour

        private static readonly string[] GlowChoices =
            { "#5B9DFF", "#F4F4F6", "#FF7A7A", "#FFC53D", "#34D399", "#22D3EE", "#A78BFA", "#F472B6", "#FB923C" };

        private void BuildGlowSwatches(string current)
        {
            EdgeSwatches.Children.Clear();
            foreach (var hex in GlowChoices)
            {
                var c = (Color)ColorConverter.ConvertFromString(hex);
                bool on = string.Equals(hex, current, StringComparison.OrdinalIgnoreCase);
                var swatch = new Border
                {
                    Width = 24, Height = 24, CornerRadius = new CornerRadius(12), Margin = new Thickness(0, 0, 8, 8), Cursor = Cursors.Hand,
                    Background = new SolidColorBrush(c), BorderThickness = new Thickness(2),
                    BorderBrush = on ? Brushes.White : Brushes.Transparent, Tag = hex, Focusable = true,
                };
                AutomationProperties.SetName(swatch, $"Edge glow {hex}" + (on ? ", selected" : ""));
                swatch.MouseLeftButtonUp += (_, _) => Layout.SetEdgeColor(hex);
                swatch.KeyDown += (_, e) => { if (e.Key is Key.Enter or Key.Space) { Layout.SetEdgeColor(hex); e.Handled = true; } };
                EdgeSwatches.Children.Add(swatch);
            }
        }

        private void EdgeHex_Commit(object sender, RoutedEventArgs e)
        {
            if (_building) return;
            string t = EdgeHex.Text.Trim();
            if (!t.StartsWith('#')) t = "#" + t;
            Layout.SetEdgeColor(t);
            EdgeHex.Text = Layout.EdgeColorHex(); // a typo snaps back to the colour in use
        }

        private void EdgeHex_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) { EdgeHex_Commit(sender, e); e.Handled = true; }
        }

        private void EdgeReset_Click(object sender, RoutedEventArgs e) => Layout.SetEdgeColor(LayoutData.DefaultEdgeColor);

        private void LoadSettings(LayoutData s)
        {
            BuildGlowSwatches(s.EdgeColor);
            EdgeHex.Text = s.EdgeColor;
            EdgeReset.Visibility = string.Equals(s.EdgeColor, LayoutData.DefaultEdgeColor, StringComparison.OrdinalIgnoreCase) ? Visibility.Collapsed : Visibility.Visible;
            SwapCmdCtrl.IsChecked = s.SwapCmdCtrlForMac;
            RequireAlt.IsChecked = s.CaptureRequireAlt;
            StrengthEasy.IsChecked = s.CaptureStrength == "easy";
            StrengthNormal.IsChecked = s.CaptureStrength == "normal";
            StrengthFirm.IsChecked = s.CaptureStrength == "firm";
            double px = LayoutData.ThresholdFor(s.CaptureStrength);
            StrengthHint.Text = $"Keep pushing about {px:0} px past the edge within half a second to cross." +
                (s.CaptureRequireAlt ? " Hold Alt while pushing." : "");
        }

        // ------------------------------------------------------------------ data

        private List<Row> Rows()
        {
            var online = PeerDirectory.Current;
            if (online.Count == 0) online = PeerBus.Peers();
            var placements = Layout.Snapshot();
            var rows = new List<Row>();
            foreach (var p in online.Where(p => p.Caps.Contains("ctl_target")))
                rows.Add(new Row(p.Id, p.Name, true, placements.GetValueOrDefault(p.Id)));
            foreach (var (id, pl) in placements)
                if (pl.Edge != null && rows.All(r => r.Id != id) && App.Peers?.Store.Get(id) != null)
                    rows.Add(new Row(id, string.IsNullOrEmpty(pl.Name) ? id[..Math.Min(8, id.Length)] : pl.Name, false, pl));
            return rows;
        }

        // ------------------------------------------------------------------ build

        private void Refresh()
        {
            string? focusKey = (Keyboard.FocusedElement as DependencyObject) is { } f ? AutomationProperties.GetAutomationId(f) : null;
            _building = true;
            try
            {
                var settings = Layout.Settings();
                LoadSettings(settings);
                RefreshLease();

                LayoutCanvas.Children.Clear();
                PeerRows.Children.Clear();
                EdgeRows.Children.Clear();

                var mons = Monitors.Enumerate();
                var u = Monitors.Union(mons);
                double cw = LayoutCanvas.Width, ch = LayoutCanvas.Height;
                double scale = Math.Min((cw - 2 * Pad) / Math.Max(1, u.Width), (ch - 2 * Pad) / Math.Max(1, u.Height));
                _boxW = u.Width * scale; _boxH = u.Height * scale;
                _boxL = (cw - _boxW) / 2; _boxT = (ch - _boxH) / 2;

                var outline = new Rectangle
                {
                    Width = _boxW + 2, Height = _boxH + 2,
                    Stroke = Res("BorderBrush"),
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
                        Background = Res(m.Primary ? "Secondary" : "Muted"),
                        BorderBrush = Res("BorderBrush"), BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(6),
                        Child = new TextBlock
                        {
                            Text = $"{n}{(m.Primary ? " · Primary" : "")}\n{b.Width}×{b.Height}",
                            Foreground = Res("Foreground"), FontSize = 12, TextAlignment = TextAlignment.Center,
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
                        LayoutCanvas.Children.Add(MakeTile(r, e, pl.Offset, pl.Size, settings.EdgeCaptureEnabled(e)));
                    PeerRows.Children.Add(MakeRow(r));
                }
                if (rows.Count == 0)
                {
                    PeerRows.Children.Add(new TextBlock
                    {
                        Text = "No controllable devices connected yet. Connect a phone or Mac to arrange it here.",
                        Style = (Style)FindResource("Hint"),
                    });
                }
                foreach (var edge in AllEdges) EdgeRows.Children.Add(MakeEdgeRow(edge, rows, settings));
            }
            finally { _building = false; }
            if (focusKey is { Length: > 0 }) RestoreFocus(this, focusKey);
        }

        private static bool RestoreFocus(DependencyObject root, string key)
        {
            foreach (var child in LogicalTreeHelper.GetChildren(root).OfType<DependencyObject>())
            {
                if (child is UIElement el && AutomationProperties.GetAutomationId(child) == key && el.Focusable)
                {
                    Keyboard.Focus(el);
                    return true;
                }
                if (RestoreFocus(child, key)) return true;
            }
            return false;
        }

        private void RefreshLease()
        {
            string? controlling = ControlState.ControllingId, controlledBy = ControlState.ControlledById;
            if (controlling != null)
            {
                LeaseDot.Fill = Res("Accent");
                LeaseText.Text = $"Controlling {ControlState.NameOf(controlling)} · Ctrl+Alt+Esc to return";
            }
            else if (controlledBy != null)
            {
                LeaseDot.Fill = Res("Accent");
                LeaseText.Text = $"Controlled by {ControlState.NameOf(controlledBy)}" +
                    (ControlState.ControlledExplicit ? " · its cursor is on this PC" : " · trackpad or touch input");
            }
            else
            {
                LeaseDot.Fill = Res("MutedForeground");
                LeaseText.Text = "Not controlling or being controlled";
            }
        }

        private UIElement MakeEdgeRow(Edge edge, List<Row> rows, LayoutData settings)
        {
            var assigned = rows.FirstOrDefault(r => EdgeExt.Parse(r.Placement?.Edge) == edge);
            var grid = new Grid { Margin = new Thickness(0, 4, 0, 4) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var text = new StackPanel();
            var line = new TextBlock { FontSize = 13, Foreground = Res("Foreground"), TextTrimming = TextTrimming.CharacterEllipsis };
            line.Inlines.Add(new System.Windows.Documents.Run($"{edge} edge "));
            line.Inlines.Add(new System.Windows.Documents.Run("→ ") { Foreground = Res("MutedForeground") });
            line.Inlines.Add(new System.Windows.Documents.Run(assigned?.Name ?? "nothing")
            {
                Foreground = assigned == null ? Res("MutedForeground") : Res("Foreground"),
                FontWeight = assigned == null ? FontWeights.Normal : FontWeights.SemiBold,
            });
            text.Children.Add(line);

            string sub;
            bool enabled = settings.EdgeCaptureEnabled(edge);
            if (assigned == null) sub = "No device on this edge";
            else if (ControlState.ControllingId == assigned.Id) sub = "Controlling now";
            else if (!assigned.Online) sub = "Offline · this edge does nothing";
            else if (!enabled) sub = "Crossing turned off";
            else sub = "Online · push through to control";
            text.Children.Add(new TextBlock
            {
                Text = sub, FontSize = 11, Margin = new Thickness(0, 2, 0, 0),
                Foreground = ControlState.ControllingId == assigned?.Id && assigned != null ? Res("Accent") : Res("MutedForeground"),
            });
            grid.Children.Add(text);

            var toggle = new CheckBox
            {
                Style = (Style)FindResource("Switch"),
                IsChecked = enabled,
                Tag = edge,
                VerticalAlignment = VerticalAlignment.Center,
                ToolTip = enabled ? $"Crossing through the {edge.ToString().ToLowerInvariant()} edge is on" : $"Crossing through the {edge.ToString().ToLowerInvariant()} edge is off",
            };
            AutomationProperties.SetName(toggle, $"Allow crossing through the {edge} edge");
            AutomationProperties.SetAutomationId(toggle, $"edge:{edge}");
            toggle.Checked += EdgeToggle_Changed;
            toggle.Unchecked += EdgeToggle_Changed;
            Grid.SetColumn(toggle, 1);
            grid.Children.Add(toggle);
            return grid;
        }

        private void EdgeToggle_Changed(object sender, RoutedEventArgs e)
        {
            if (_building || sender is not CheckBox { Tag: Edge edge } cb) return;
            Layout.SetEdgeCaptureEnabled(edge, cb.IsChecked == true);
        }

        private Border MakeTile(Row r, Edge e, double offset, double size, bool edgeEnabled)
        {
            bool active = ControlState.ControllingId == r.Id;
            bool lit = r.Online && edgeEnabled;
            var tile = new Border
            {
                Background = Res(lit ? "Accent" : "Secondary"),
                BorderBrush = active ? Brushes.White : Res(lit ? "Accent" : "BorderBrush"),
                BorderThickness = new Thickness(active ? 2 : 1),
                CornerRadius = new CornerRadius(8),
                Cursor = Cursors.SizeAll,
                Tag = r,
                ToolTip = !r.Online ? $"{r.Name} (offline)" : !edgeEnabled ? $"{r.Name} (crossing off)" : active ? $"{r.Name} (controlling now)" : r.Name,
                Child = new TextBlock
                {
                    Text = r.Name, Foreground = Res(lit ? "OnAccent" : "Foreground"), FontSize = 11, FontWeight = FontWeights.SemiBold,
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
            var grid = new Grid { Margin = new Thickness(0, 4, 0, 4) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var name = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            name.Children.Add(new Ellipse
            {
                Width = 8, Height = 8, VerticalAlignment = VerticalAlignment.Center,
                Fill = Res(r.Online ? "Accent" : "BorderBrush"),
                ToolTip = r.Online ? "Online" : "Offline",
            });
            name.Children.Add(new TextBlock
            {
                Text = r.Online ? r.Name : $"{r.Name}  (offline)",
                Foreground = Res(r.Online ? "Foreground" : "MutedForeground"),
                FontSize = 13, Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
            grid.Children.Add(name);

            var seg = new StackPanel { Orientation = Orientation.Horizontal };
            var current = EdgeExt.Parse(r.Placement?.Edge);
            foreach (var c in EdgeChoices)
            {
                var rb = new RadioButton
                {
                    Style = (Style)FindResource("Segment"),
                    GroupName = "edge:" + r.Id,
                    Content = c == null ? "Off" : c.ToString(),
                    IsChecked = c == current,
                    Tag = (r, c),
                };
                AutomationProperties.SetName(rb, c == null ? $"{r.Name}: not arranged" : $"{r.Name}: {c} edge");
                AutomationProperties.SetAutomationId(rb, $"peer:{r.Id}:{c?.ToWire() ?? "off"}");
                rb.Checked += EdgeChoice_Checked;
                seg.Children.Add(rb);
            }
            Grid.SetColumn(seg, 1);
            grid.Children.Add(seg);
            return grid;
        }

        private void EdgeChoice_Checked(object sender, RoutedEventArgs e)
        {
            if (_building || sender is not RadioButton { Tag: ValueTuple<Row, Edge?> tag }) return;
            var (r, edge) = tag;
            double size = r.Placement?.Size ?? 0.5;
            double offset = r.Placement?.Edge != null ? r.Placement.Offset : (1 - size) / 2;
            Layout.Assign(r.Id, r.Name, edge, offset, size);
        }

        // ------------------------------------------------------------------ drag to assign

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
