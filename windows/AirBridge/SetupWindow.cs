using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using AirBridge.Net;

namespace AirBridge
{
    /// <summary>
    /// First-run setup on Windows (issue #15), the same flow as the Mac's: a few skippable slides about what AirBridge does,
    /// then one full step per thing Windows needs (firewall, start with Windows, sharing this PC's notifications, the network
    /// check), each saying what it is for and what stops working without it, with a live state and a button that does it.
    /// Ends with pairing a phone. Opens by itself on the first launch and again from the tray's Settings.
    /// </summary>
    public sealed class SetupWindow : Window
    {
        private sealed record Step(string Glyph, string Title, string Why, string Without, string Action,
            Func<Task<StepState>> State, Func<Task> Run);
        private enum StepState { Done, Todo, Optional }

        private static readonly (string Glyph, string Title, string Text)[] Slides =
        {
            ("", "Your PC and phone, one desk", "Copy on one, paste on the other. Drop files across at full Wi-Fi speed. No cables, no accounts."),
            ("", "One keyboard and mouse", "Push the cursor off the edge of the screen and it carries on on your phone or Mac, typing included."),
            ("", "Calls and notifications", "Your phone's notifications and calls show up here, to answer, reply or clear."),
            ("", "Screens in a window", "See and control your phone or another computer in a window, and use AirWeb for any browser."),
        };

        private readonly List<Step> _steps;
        private readonly ContentControl _page = new();
        private readonly StackPanel _footer = new() { Orientation = Orientation.Horizontal };
        private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromSeconds(1) };
        private int _index;

        private static Brush Res(string key) => (Brush)Application.Current.FindResource(key);

        public SetupWindow()
        {
            Title = "Set Up AirBridge";
            Width = 760; Height = 560;
            ResizeMode = ResizeMode.NoResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = Res("AB.Background");
            Foreground = Res("AB.Foreground");
            FontFamily = new FontFamily("Segoe UI Variable Text, Segoe UI");
            _steps = BuildSteps();

            var root = new Grid { Margin = new Thickness(48, 40, 48, 28) };
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.Children.Add(_page);
            var footerGrid = new Grid();
            Grid.SetRow(footerGrid, 1);
            _footer.HorizontalAlignment = HorizontalAlignment.Stretch;
            footerGrid.Children.Add(_footer);
            root.Children.Add(footerGrid);
            Content = root;

            Loaded += (_, _) => { try { UiKit.Dark(this); } catch { } _ = ShowPage(); };
            _refresh.Tick += (_, _) => { if (_index >= Slides.Length) _ = ShowPage(); };
            _refresh.Start();
            Closed += (_, _) => _refresh.Stop();
        }

        /// <summary>Shown once, on the first launch of this install.</summary>
        public static void ShowIfFirstRun()
        {
            if (UiSettings.SetupShown) return;
            UiSettings.SetupShown = true;
            ShowNow();
        }

        public static void ShowNow()
        {
            var w = Application.Current.Windows.OfType<SetupWindow>().FirstOrDefault() ?? new SetupWindow();
            w.Show();
            w.Activate();
        }

        private List<Step> BuildSteps()
        {
            var steps = new List<Step>
            {
                new("", "Firewall",
                    "Lets your phone and other computers find this PC and connect to it.",
                    "Devices on some networks can't reach this PC.",
                    "Allow AirBridge",
                    async () => await FirewallHelper.IsConfiguredAsync(force: true) == true ? StepState.Done : StepState.Todo,
                    async () => { await FirewallHelper.AddRulesAsync(); }),
            };
            if (Startup.Supported)
                steps.Add(new("", "Start with Windows",
                    "AirBridge starts with your PC, so devices reconnect by themselves.",
                    "Nothing connects until you open AirBridge.",
                    "Turn On",
                    () => Task.FromResult(Startup.Enabled ? StepState.Done : StepState.Todo),
                    () => { Startup.Enabled = true; return Task.CompletedTask; }));
            steps.Add(new("", "Share this PC's notifications",
                Phone.PcNotifSource.Supported
                    ? "Shows this PC's notifications on your Mac and other devices, where you can clear them."
                    : "Shows this PC's notifications on your Mac and other devices. Windows allows it on Windows 11, or with the installer build.",
                "Your other devices don't see this PC's notifications.",
                Phone.PcNotifSource.Supported ? "Allow" : "",
                async () => Phone.PcNotifSource.Supported ? (await Phone.PcNotifSource.AccessAllowed() ? StepState.Done : StepState.Todo) : StepState.Optional,
                async () => { await Phone.PcNotifSource.RequestAccess(); }));
            steps.Add(new("", "Network check",
                "Checks that this network lets devices see each other, and explains what to change if it doesn't.",
                "Nothing; it only helps when a device can't connect.",
                "Run the check",
                () => Task.FromResult((App.Peers?.Peers().Count ?? 0) > 0 ? StepState.Done : StepState.Optional),
                () => { new NetworkCheckWindow().Show(); return Task.CompletedTask; }));
            return steps;
        }

        // MARK: Pages

        private async Task ShowPage()
        {
            int total = Slides.Length + _steps.Count + 1;
            if (_index < Slides.Length) _page.Content = SlidePage(_index);
            else if (_index < Slides.Length + _steps.Count) _page.Content = await StepPage(_steps[_index - Slides.Length], _index - Slides.Length + 1);
            else _page.Content = PairPage();
            await BuildFooter(total);
        }

        private UIElement SlidePage(int i)
        {
            var (glyph, title, text) = Slides[i];
            var p = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            p.Children.Add(new TextBlock { Text = glyph, FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"), FontSize = 52, Foreground = Res("AB.MutedForeground"), HorizontalAlignment = HorizontalAlignment.Center });
            p.Children.Add(new TextBlock { Text = title, FontSize = 28, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 18, 0, 0), HorizontalAlignment = HorizontalAlignment.Center });
            p.Children.Add(new TextBlock { Text = text, FontSize = 15, Foreground = Res("AB.MutedForeground"), TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, MaxWidth = 460, Margin = new Thickness(0, 10, 0, 0) });
            return p;
        }

        private async Task<UIElement> StepPage(Step s, int n)
        {
            var state = await s.State();
            var p = new StackPanel();
            p.Children.Add(new TextBlock { Text = $"STEP {n} OF {_steps.Count}", FontSize = 10.5, FontWeight = FontWeights.SemiBold, Foreground = Res("AB.MutedForeground") });
            var head = new DockPanel { Margin = new Thickness(0, 14, 0, 0), LastChildFill = true };
            var chip = Chip(state);
            DockPanel.SetDock(chip, Dock.Right);
            head.Children.Add(chip);
            head.Children.Add(new TextBlock { Text = s.Glyph + "  " + s.Title, FontSize = 26, FontWeight = FontWeights.SemiBold,
                FontFamily = new FontFamily("Segoe UI Variable Display, Segoe UI, Segoe Fluent Icons, Segoe MDL2 Assets") });
            p.Children.Add(head);
            p.Children.Add(new TextBlock { Text = s.Why, FontSize = 15, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 14, 0, 0) });
            p.Children.Add(new TextBlock { Text = "Without it: " + s.Without, FontSize = 13.5, Foreground = Res("AB.MutedForeground"), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) });
            if (state != StepState.Done && s.Action.Length > 0)
            {
                var b = PrimaryButton(s.Action);
                b.HorizontalAlignment = HorizontalAlignment.Left;
                b.Margin = new Thickness(0, 18, 0, 0);
                b.Click += async (_, _) => { try { await s.Run(); } catch (Exception ex) { Log.Warn($"setup: {s.Title}: {ex.Message}"); } await ShowPage(); };
                p.Children.Add(b);
            }
            return p;
        }

        private UIElement PairPage()
        {
            var p = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
            var phones = App.Peers?.Peers().Where(x => x.Platform == "android").ToList() ?? new();
            p.Children.Add(new TextBlock { Text = "LAST STEP", FontSize = 10.5, FontWeight = FontWeights.SemiBold, Foreground = Res("AB.MutedForeground"), HorizontalAlignment = HorizontalAlignment.Center });
            p.Children.Add(new TextBlock { Text = phones.Count == 0 ? "Pair your phone" : "Paired with " + string.Join(", ", phones.Select(x => x.Name)),
                FontSize = 26, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 12, 0, 0), HorizontalAlignment = HorizontalAlignment.Center });
            if (phones.Count == 0)
            {
                if (App.Peers?.PairingUri is string uri)
                {
                    var qr = new Image { Source = UiKit.RenderQr(uri, 6), Width = 180, Height = 180 };
                    p.Children.Add(new Border { Background = Brushes.White, CornerRadius = new CornerRadius(12), Padding = new Thickness(10), Margin = new Thickness(0, 16, 0, 0), HorizontalAlignment = HorizontalAlignment.Center, Child = qr });
                }
                p.Children.Add(new TextBlock { Text = "Open AirBridge on your phone, tap Add device and scan this code. Both screens then show the same 6 digits to confirm.",
                    FontSize = 13.5, Foreground = Res("AB.MutedForeground"), TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, MaxWidth = 440, Margin = new Thickness(0, 14, 0, 0) });
            }
            else
            {
                p.Children.Add(new TextBlock { Text = "You're all set. Everything here is also in AirBridge's tray menu and its Settings.",
                    FontSize = 14, Foreground = Res("AB.MutedForeground"), Margin = new Thickness(0, 14, 0, 0), HorizontalAlignment = HorizontalAlignment.Center });
            }
            return p;
        }

        private async Task BuildFooter(int total)
        {
            _footer.Children.Clear();
            var grid = new Grid { Width = 664 };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            if (_index > 0)
            {
                var back = QuietButton("Back");
                back.Click += async (_, _) => { _index--; await ShowPage(); };
                grid.Children.Add(back);
            }
            var right = new StackPanel { Orientation = Orientation.Horizontal };
            Grid.SetColumn(right, 2);
            if (_index < Slides.Length)
            {
                var skip = QuietButton("Skip intro");
                skip.Click += async (_, _) => { _index = Slides.Length; await ShowPage(); };
                var next = PrimaryButton(_index == Slides.Length - 1 ? "Set up AirBridge" : "Next");
                next.Margin = new Thickness(8, 0, 0, 0);
                next.Click += async (_, _) => { _index++; await ShowPage(); };
                right.Children.Add(skip); right.Children.Add(next);
            }
            else if (_index < total - 1)
            {
                var state = await _steps[_index - Slides.Length].State();
                var next = state == StepState.Done ? PrimaryButton("Next") : QuietButton("Skip");
                next.Click += async (_, _) => { _index++; await ShowPage(); };
                right.Children.Add(next);
            }
            else
            {
                var done = PrimaryButton("Done");
                done.Click += (_, _) => Close();
                right.Children.Add(done);
            }
            grid.Children.Add(right);
            _footer.Children.Add(grid);
        }

        private Border Chip(StepState s)
        {
            var (text, bg, fg) = s switch
            {
                StepState.Done => ("✓ Done", Brushes.White, Res("AB.Background")),
                StepState.Todo => ("Not yet", Res("AB.Muted"), Res("AB.MutedForeground")),
                _ => ("Optional", Res("AB.Muted"), Res("AB.MutedForeground")),
            };
            return new Border { Background = bg, CornerRadius = new CornerRadius(12), Padding = new Thickness(10, 4, 10, 4), VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock { Text = text, FontSize = 12, FontWeight = FontWeights.Medium, Foreground = fg } };
        }

        private Button PrimaryButton(string text) => new()
        {
            Content = text, Style = (Style)Application.Current.FindResource("AB.Button.Primary"), MinHeight = 34, Padding = new Thickness(18, 0, 18, 0),
        };

        private Button QuietButton(string text) => new()
        {
            Content = text, Style = (Style)Application.Current.FindResource("AB.Button.Secondary"), MinHeight = 34, Padding = new Thickness(14, 0, 14, 0),
        };
    }
}
