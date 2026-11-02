using System.Diagnostics;
using System.Text.Json.Nodes;
using AirBridge.Input;

namespace AirBridge
{
    /// <summary>
    /// Input half entry point (docs/protocol.md §8, §12). Owns the controller (PC → remote target, low-level hooks)
    /// and the target (remote → PC, SendInput), and wires them to <see cref="PeerBus"/>.
    ///
    /// Recall hotkey while controlling another device: Ctrl+Alt+Esc.
    ///
    /// Arbitration (§8.6): the <see cref="Target"/> owns this PC's single control lease; while a remote peer holds
    /// it the <see cref="Controller"/> never captures. Status goes to <see cref="ControlState"/> (and from there to
    /// App.SetControlStatus).
    /// </summary>
    public class InputInterceptor
    {
        private readonly Controller _controller = new();
        private readonly Target _target;
        private Action<string, JsonObject>? _controlHandler;
        private Action<IReadOnlyList<PeerInfo>>? _peersHandler;
        private bool _started;

        public InputInterceptor()
        {
            _target = new Target(
                // A remote peer took this PC: stop controlling others; the target places the cursor itself.
                onEnter: () => _controller.RequestReturn(sendLeave: true, restoreCursor: false),
                onLease: (holder, isExplicit) =>
                {
                    _controller.SetInhibited(holder != null);
                    ControlState.SetControlledBy(holder, isExplicit);
                });
        }

        /// <summary>Peer id currently being controlled from this PC, or null.</summary>
        public string? ControlledPeer => _controller.ActivePeer;

        public void Start()
        {
            if (_started) return;
            _started = true;

            CursorHider.InstallSafetyNet();
            CursorHider.Restore(force: true); // undo a blank cursor left by a previous crash
            EdgeIndicator.Init(System.Windows.Threading.Dispatcher.CurrentDispatcher);
            try { Microsoft.Win32.SystemEvents.SessionSwitch += OnSessionSwitch; }
            catch (Exception ex) { Debug.WriteLine($"SessionSwitch unavailable: {ex.Message}"); }

            PeerDirectory.Update(PeerBus.Peers());
            _controlHandler = OnControl;
            _peersHandler = OnPeers;
            PeerBus.ControlHandler = _controlHandler;
            PeerBus.PeersHandler = _peersHandler;

            _target.Start();
            _controller.Start();
        }

        public void Stop()
        {
            if (!_started) return;
            _started = false;
            try { Microsoft.Win32.SystemEvents.SessionSwitch -= OnSessionSwitch; } catch { }
            if (PeerBus.ControlHandler == _controlHandler) PeerBus.ControlHandler = null;
            if (PeerBus.PeersHandler == _peersHandler) PeerBus.PeersHandler = null;
            _controller.Stop();
            _target.Stop();
            CursorHider.Restore();
        }

        /// <summary>
        /// Lock screen / fast user switch / remote session change: hooks stop seeing input on the secure desktop, so
        /// give the cursor back and drop any incoming lease (releasing held keys and buttons on both sides).
        /// </summary>
        private void OnSessionSwitch(object? sender, Microsoft.Win32.SessionSwitchEventArgs e)
        {
            try
            {
                _controller.RequestReturn(sendLeave: true);
                _target.ReleaseAll();
            }
            catch (Exception ex) { Debug.WriteLine($"session switch: {ex}"); }
        }

        /// <summary>Bring the cursor back to this PC (e.g. on network loss). Safe from any thread.</summary>
        public void ForceReturnToWindows() => _controller.RequestReturn(sendLeave: true);

        // Network thread.
        private void OnControl(string peerId, JsonObject msg)
        {
            try
            {
                string? t = Msg.Str(msg, "t");
                if (t == "ctl_leave") _controller.OnCtlLeave(peerId, msg);
                else if (t == "ctl_busy") { _controller.OnCtlBusy(peerId, msg); return; }
                _target.Handle(peerId, msg);
            }
            catch (Exception ex) { Debug.WriteLine($"input control handler: {ex}"); }
        }

        // Network thread.
        private void OnPeers(IReadOnlyList<PeerInfo> peers)
        {
            try
            {
                PeerDirectory.Update(peers);
                _controller.OnPeersChanged(peers);
                _target.OnPeersChanged(peers);
            }
            catch (Exception ex) { Debug.WriteLine($"input peers handler: {ex}"); }
        }
    }
}
