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
            _target = new Target(onEnter: () => _controller.RequestReturn(sendLeave: true));
        }

        /// <summary>Peer id currently being controlled from this PC, or null.</summary>
        public string? ControlledPeer => _controller.ActivePeer;

        public void Start()
        {
            if (_started) return;
            _started = true;

            CursorHider.InstallSafetyNet();
            CursorHider.Restore(force: true); // undo a blank cursor left by a previous crash

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
            if (PeerBus.ControlHandler == _controlHandler) PeerBus.ControlHandler = null;
            if (PeerBus.PeersHandler == _peersHandler) PeerBus.PeersHandler = null;
            _controller.Stop();
            _target.Stop();
            CursorHider.Restore();
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
