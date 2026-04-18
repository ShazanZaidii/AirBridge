using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace AirBridge
{
    public partial class HostDiscoveryWindow : Window
    {
        public HostDiscoveryWindow()
        {
            InitializeComponent();
            Loaded += OnLoaded;
        }

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left) this.DragMove();
        }

        private async void OnLoaded(object sender, RoutedEventArgs e)
        {
            var hosts = await App.Client.ScanForHostsAsync();

            HostsPanel.Children.Clear();

            if (hosts.Count == 0)
            {
                HostsPanel.Children.Add(new TextBlock
                {
                    Text = "No hosts found on network.",
                    Foreground = Brushes.Gray,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Margin = new Thickness(0, 20, 0, 0)
                });
                return;
            }

            foreach (var host in hosts)
            {
                bool isCurrentHost = App.Client.IsConnected && App.Client.ConnectedHostUrl == host.Url;

                var btn = new Button
                {
                    Height = 60,
                    Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#2C2C2E")),
                    BorderThickness = new Thickness(isCurrentHost ? 1 : 0),
                    BorderBrush = isCurrentHost ? new SolidColorBrush((Color)ColorConverter.ConvertFromString("#4ADE80")) : Brushes.Transparent,
                    Margin = new Thickness(0, 0, 0, 10),
                    Cursor = Cursors.Hand,
                    Tag = host.Url
                };

                btn.Resources.Add(typeof(Border), new Style(typeof(Border))
                {
                    Setters = { new Setter(Border.CornerRadiusProperty, new CornerRadius(8)) }
                });

                var sp = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(15, 0, 15, 0) };
                sp.Children.Add(new TextBlock { Text = "🖥️", FontSize = 24, Margin = new Thickness(0, 0, 15, 0), VerticalAlignment = VerticalAlignment.Center });

                var textSp = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                textSp.Children.Add(new TextBlock { Text = host.Name, Foreground = Brushes.White, FontSize = 14, FontWeight = FontWeights.SemiBold });
                textSp.Children.Add(new TextBlock { Text = isCurrentHost ? "Connected" : host.Url.Replace("ws://", "").Split('?')[0], Foreground = isCurrentHost ? new SolidColorBrush((Color)ColorConverter.ConvertFromString("#4ADE80")) : Brushes.Gray, FontSize = 11 });

                sp.Children.Add(textSp);
                btn.Content = sp;

                btn.Click += async (s, args) =>
                {
                    string clickedUrl = ((Button)s).Tag.ToString();
                    await App.Client.ConnectAsync(clickedUrl);
                    this.Close();
                };

                HostsPanel.Children.Add(btn);
            }
        }

        private void Close_Click(object sender, RoutedEventArgs e) => this.Close();
    }
}