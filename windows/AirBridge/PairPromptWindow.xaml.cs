using System;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using AirBridge.Net;

namespace AirBridge
{
    /// <summary>
    /// Host-side prompt for a `confirm` pairing (§3.3): "&lt;name&gt; (&lt;platform&gt;) wants to pair — 483 920 — Accept / Reject".
    /// Closing the window counts as Reject. It closes itself when the request ends (accepted, timed out, the other
    /// device cancelled or disconnected).
    /// </summary>
    public partial class PairPromptWindow : Window
    {
        private readonly PairApproval _approval;
        private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
        private readonly CancellationTokenRegistration _endedReg;
        private bool _decided;

        public PairPromptWindow(PairApproval approval)
        {
            InitializeComponent();
            UiKit.Dark(this);
            UiKit.Glass(this);
            _approval = approval;
            string who = $"{approval.PeerName} ({PairApproval.PlatLabel(approval.Plat)})";
            Title = $"{who} wants to pair";
            WhoText.Text = $"{who} wants to pair with this PC";
            DigitsText.Text = $"{approval.Digits[..3]} {approval.Digits[3..]}";
            HintText.Text = $"Accept only if {approval.PeerName} shows the same code. Nothing needs to be typed.";
            UpdateExpiry();
            _timer.Tick += (_, _) => UpdateExpiry();
            _timer.Start();
            _endedReg = approval.Ended.Register(() => Dispatcher.BeginInvoke(new Action(() => { _decided = true; Close(); })));
            Closed += (_, _) =>
            {
                _timer.Stop();
                _endedReg.Dispose();
                if (!_decided) _approval.Decide(false);
            };
        }

        private void UpdateExpiry()
        {
            var left = _approval.ExpiresUtc - DateTime.UtcNow;
            if (left <= TimeSpan.Zero) { ExpiryText.Text = "Expired"; AcceptButton.IsEnabled = false; return; }
            ExpiryText.Text = $"Expires in {(int)left.TotalMinutes}:{left.Seconds:D2}";
        }

        private void Accept_Click(object sender, RoutedEventArgs e)
        {
            _decided = true;
            _approval.Decide(true);
            AcceptButton.IsEnabled = RejectButton.IsEnabled = false;
            HintText.Text = $"Accepted. Waiting for {_approval.PeerName} to confirm…";
            // The window closes when the request ends (paired or failed).
        }

        private void Reject_Click(object sender, RoutedEventArgs e)
        {
            _decided = true;
            _approval.Decide(false);
            Close();
        }

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left) DragMove();
        }
    }
}
