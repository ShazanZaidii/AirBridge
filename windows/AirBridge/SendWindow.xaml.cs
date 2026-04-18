using System.Windows;
using System.Threading.Tasks;

namespace AirBridge
{
    public partial class SendWindow : Window
    {
        private string[] _filesToSend;

        public SendWindow(string[] filesToSend)
        {
            InitializeComponent();
            _filesToSend = filesToSend;
            TitleText.Text = $"Sending {filesToSend.Length} file(s)";

            PopulateTargets();
        }

        private void PopulateTargets()
        {
            bool hasDevices = false;

            if (App.Client != null && App.Client.IsConnected)
            {
                MacButton.Visibility = Visibility.Visible;
                hasDevices = true;
            }

            if (App.Server != null && App.Server.HasActiveClient)
            {
                PhoneNameText.Text = $"Android ({App.Server.ActiveClientIp})";
                PhoneButton.Visibility = Visibility.Visible;
                hasDevices = true;
            }

            if (!hasDevices)
            {
                NoDevicesText.Visibility = Visibility.Visible;
            }
        }

        private void MacButton_Click(object sender, RoutedEventArgs e)
        {
            _ = App.FileTransfer.SendFilesAsync(_filesToSend, TransferTarget.Mac);
            this.Close();
        }

        private void PhoneButton_Click(object sender, RoutedEventArgs e)
        {
            _ = App.FileTransfer.SendFilesAsync(_filesToSend, TransferTarget.Phone);
            this.Close();
        }

        private void Cancel_Click(object sender, RoutedEventArgs e) => this.Close();
    }
}