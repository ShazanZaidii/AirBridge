using System.Windows;
using System.Windows.Input;

namespace AirBridge
{
    public partial class DisplayArrangementWindow : Window
    {
        public DisplayArrangementWindow()
        {
            InitializeComponent();
            LoadCurrentSettings();
        }

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left) this.DragMove();
        }

        private void LoadCurrentSettings()
        {
            EdgeCombo.SelectedIndex = (int)InputInterceptor.PhoneEdge;
            OffsetSlider.Value = InputInterceptor.PhoneOffset;
            SizeSlider.Value = InputInterceptor.PhoneSize;
        }

        private void EdgeCombo_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
        {
            if (IsLoaded)
            {
                InputInterceptor.PhoneEdge = (DeviceEdge)EdgeCombo.SelectedIndex;
            }
        }

        private void Slider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (IsLoaded)
            {
                InputInterceptor.PhoneOffset = OffsetSlider.Value;
                InputInterceptor.PhoneSize = SizeSlider.Value;
            }
        }

        private void Save_Click(object sender, RoutedEventArgs e)
        {
            // Settings are applied live via the events, just close
            this.Close();
        }

        private void Close_Click(object sender, RoutedEventArgs e) => this.Close();
    }
}