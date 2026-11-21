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
        private Action<string>? _sessionEndedHandler;
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
            CaptureHud.Init(System.Windows.Threading.Dispatcher.CurrentDispatcher);
            DropCatcher.Init(System.Windows.Threading.Dispatcher.CurrentDispatcher); // §8.8 edge catcher (controller)
            DragGhost.Init(System.Windows.Threading.Dispatcher.CurrentDispatcher);   // §8.8 carried-drag ghost (target)
            try { Microsoft.Win32.SystemEvents.SessionSwitch += OnSessionSwitch; }
            catch (Exception ex) { Debug.WriteLine($"SessionSwitch unavailable: {ex.Message}"); }

            PeerDirectory.Update(PeerBus.Peers());
            _controlHandler = OnControl;
            _peersHandler = OnPeers;
            _sessionEndedHandler = OnSessionEnded;
            PeerBus.ControlHandler = _controlHandler;
            PeerBus.PeersHandler = _peersHandler;
            PeerBus.SessionEndedHandler = _sessionEndedHandler;
            // Video from a phone shown in a mirror window (§8.7, PC as sink).
            PeerBus.BinaryHandler = (peer, frame) => PhoneMirrorHub.Frame(peer, frame);

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
            if (PeerBus.SessionEndedHandler == _sessionEndedHandler) PeerBus.SessionEndedHandler = null;
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
                if (e.Reason == Microsoft.Win32.SessionSwitchReason.SessionLock) _target.SetUnavailable(true);
                else if (e.Reason == Microsoft.Win32.SessionSwitchReason.SessionUnlock) _target.SetUnavailable(false);
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
                // Phone-as-source mirroring (§8.7): the phone's side of the stream belongs to its mirror window.
                if (t is "mirror_info" or "mirror_wait" or "mirror_state" or "error"
                    || (t == "mirror_stop" && PhoneMirrorHub.IsMirroring(peerId)))
                {
                    PhoneMirrorHub.Handle(peerId, msg);
                    return;
                }
                if (t == "ctl_leave") _controller.OnCtlLeave(peerId, msg);
                else if (t == "ctl_busy") { _controller.OnCtlBusy(peerId, msg); return; }
                else if (t == "ctl_ack") { _controller.OnCtlAck(peerId); return; }
                _target.Handle(peerId, msg);
            }
            catch (Exception ex) { Debug.WriteLine($"input control handler: {ex}"); }
        }

        // Any thread (whoever closed the session). Must not block: both halves only post to their own threads.
        private void OnSessionEnded(string peerId)
        {
            try
            {
                MirrorSource.PeerGone(peerId);
                _controller.OnSessionEnded(peerId);
                _target.OnSessionEnded(peerId);
            }
            catch (Exception ex) { Debug.WriteLine($"input session-ended handler: {ex}"); }
        }

        // Network thread.
        private void OnPeers(IReadOnlyList<PeerInfo> peers)
        {
            try
            {
                PeerDirectory.Update(peers);
                PhoneMirrorHub.PeersChanged(peers);
                MirrorSource.PeersChanged(peers);
                _controller.OnPeersChanged(peers);
                _target.OnPeersChanged(peers);
            }
            catch (Exception ex) { Debug.WriteLine($"input peers handler: {ex}"); }
        }
    }
}
