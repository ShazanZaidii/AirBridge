using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace AirBridge.Input
{
    /// <summary>
    /// PC → remote target ("universal control" controller side, §8.1–8.3).
    ///
    /// Threads:
    ///  * hook thread — owns both low-level hooks and a bare message loop. Callbacks only read the struct, update
    ///    small arrays, maybe SetCursorPos once on entry, and enqueue. Commands from other threads are posted to it,
    ///    so all routing state is single-threaded.
    ///  * sender thread — coalesces moves (≤120/s), maps keys (HID, ToUnicodeEx), hides/restores the cursor,
    ///    synthesises local key-ups, calls PeerBus, watches for a lost hook, polls caps lock.
    ///
    /// Recall hotkey while controlling: Ctrl+Alt+Esc (tracked from physical events inside the hook).
    ///
    /// Escape never depends on the target (§8.1): after ctl_enter the target must answer ctl_ack within 600 ms, or the
    /// capture ends (cursor back at the entry point, notice, that peer is not captured again for 5 s; same for
    /// ctl_leave reason "unavailable"/"loop"). The local cursor stays visible, frozen at the edge, until the ack and is
    /// hidden only then. While captured, the depth/sideways travel of the remote cursor is estimated from the deltas
    /// sent (<see cref="CaptureEscape"/>); moving back through the entry edge by more than 40 DIP returns control, and
    /// the cursor reappears on the entry edge at the entry point plus the sideways travel. A pill near the top of the
    /// monitor (<see cref="CaptureHud"/>) says how to get back. Loops (§8.6): see <see cref="ControlGate"/>.
    ///
    /// Capture intent (§8.2): touching an edge never crosses. The cursor must be pinned at an outer edge inside a
    /// peer's span and the user must keep pushing outward: the outward delta accumulated over a sliding 500 ms
    /// window must reach the threshold (Easy 60 / Normal 100 / Firm 160 logical px × the edge monitor's scale).
    /// Delta source: raw input (WM_INPUT, relative mouse counts) on a message-only window owned by the hook thread,
    /// because whether WH_MOUSE_LL reports a pre-clamp <c>pt</c> at a screen edge is undocumented. When no fresh
    /// relative raw input exists (absolute devices, registration failure) the hook's overshoot beyond the edge
    /// (<c>pt</c> minus the clamped position) is used instead. Inward motion, a pause, leaving the span or a button
    /// press resets; 700 ms cooldown after returning through an edge; nothing during or 1 s after a drag; nothing
    /// while a remote peer holds this PC's lease.
    /// </summary>
    internal sealed class Controller
    {
        private const uint WM_APP_CMD = NativeMethods.WM_APP + 1;
        private const int ReturnInset = 8;         // px inside the edge where the local cursor resumes
        private const double MoveIntervalMs = 1000.0 / 120.0;

        // ---------- shared (volatile snapshots) ----------
        /// <param name="Drag">The peer takes carried file drags (§8.8, caps drag_files + xfer).</param>
        private sealed record Portal(string PeerId, Edge PcEdge, double SpanStart, double SpanEnd, EdgeSegment[] Segments, bool Drag);
        private sealed record PortalMap(Portal[] Portals, IReadOnlyList<MonitorInfo> Monitors, double Threshold, bool RequireAlt);

        private volatile PortalMap _map = new(Array.Empty<Portal>(), Array.Empty<MonitorInfo>(), 100, false);
        private volatile string? _activePeer;       // written by hook thread only
        private readonly object _rebuildGate = new();

        // ---------- hook thread ----------
        private Thread? _hookThread;
        private volatile uint _hookThreadId;
        private readonly ManualResetEventSlim _hookReady = new(false);
        private readonly ConcurrentQueue<Action> _hookCmds = new();
        private IntPtr _mouseHook, _kbHook;
        private readonly NativeMethods.HookProc _mouseProc;
        private readonly NativeMethods.HookProc _kbProc;

        private bool _remote;
        private Portal? _portal;
        private NativeMethods.POINT _anchor, _entry;
        private CaptureEscape? _esc;                // the current capture's ack/escape state
        private EdgeSegment _entrySeg;
        private int _entryAlong;
        private readonly RefusalList _refused = new();         // peers that didn't ack / refused: 5 s off
        private readonly HashSet<string> _ended = new();       // session closing, peer list not yet updated
        private readonly bool[] _localHeld = new bool[512];
        private readonly bool[] _remoteHeld = new bool[512];
        private readonly bool[] _suppressUp = new bool[512];
        private readonly bool[] _localBtn = new bool[5];
        private readonly bool[] _remoteBtn = new bool[3];
        private readonly bool[] _suppressBtnUp = new bool[5];
        private bool _capsOn;
        private bool _physLCtrl, _physRCtrl, _physLAlt, _physRAlt;
        private volatile int _hookEvents;           // every mouse hook call; read by the idle watchdog

        // ---------- capture intent (hook thread) ----------
        private const long CaptureWindowMs = 500;
        private const long ReturnCooldownMs = 700;
        private const long BusyCooldownMs = 2000;
        private const long DragQuietMs = 1000;
        private const long RawFreshMs = 250;
        private const int PushRing = 1024;
        private const int RawBufSize = 128;

        private Portal? _pinPortal;
        private EdgeSegment _pinSeg;
        private int _pinAlong;
        private double _pinScale = 1;
        private readonly long[] _noCaptureUntil = new long[4];
        private long _dragQuietUntil;
        private bool _movedWhileDown;
        private readonly long[] _pushT = new long[PushRing];
        private readonly double[] _pushV = new double[PushRing];
        private int _pushHead, _pushCount;
        private double _pushSum;
        private int _lastChargeStep = -1;
        private long _lastRawMs = long.MinValue / 2;
        private IntPtr _rawHwnd, _rawBuf;
        private volatile bool _inhibit;             // a remote peer holds this PC's lease

        // ---------- carried file drag, §8.8 (hook thread) ----------
        private const int CatcherThickness = 6;     // px of the OLE drop strip at the edge
        private readonly DragCarryController _carry = new(Random.Shared.Next(1, 0x1000_0000));
        private bool _pinDrag;                      // the current pin is a left-button drag at a drag_files portal
        private bool _catcherShown;
        private string[]? _armedFiles;              // files an OLE DragEnter on the catcher reported for this drag
        private string[]? _carryFiles;

        // ---------- sender thread ----------
        private enum K { Enter, Acked, Leave, Move, Btn, Scroll, Key, Action, CapsCheck, Peers, Carry, Drop, CarryCancel }

        private readonly record struct Ev(K Kind, string? Peer = null, double A = 0, double B = 0, int I = 0, int J = 0,
            int Flags = 0, int Mods = 0, bool F = false, Edge Edge = Edge.Left, int[]? Vks = null, string? S = null, string[]? Ids = null,
            MonitorInfo? Mon = null);

        private const int KF_EXT = 1, KF_UP = 2, KF_REP = 4, KF_ALTGR = 8;
        private const int M_LSHIFT = 1, M_RSHIFT = 2, M_LCTRL = 4, M_RCTRL = 8, M_LALT = 16, M_RALT = 32,
            M_LWIN = 64, M_RWIN = 128, M_ALTGRCTRL = 256, M_CAPS = 512;

        private readonly BlockingCollection<Ev> _out = new(new ConcurrentQueue<Ev>());
        private Thread? _sender;
        private volatile bool _stopping;

        // sender-thread-only
        private string? _peer;
        private int _anchorX, _anchorY;
        private bool _acked;                        // sender's view: ctl_ack arrived for _peer
        private double _enterMs, _nextAckCheckMs;
        private MonitorInfo? _hudMon;
        private double _pdx, _pdy, _sdx, _sdy;
        private bool _pendingMove, _pendingScroll;
        private readonly Stopwatch _clock = Stopwatch.StartNew();
        private double _lastFlushMs = -1000;
        private int _watchdogMisses;
        private double _nextWatchdogMs;
        private int _idleMisses, _idleHookEvents;
        private NativeMethods.POINT _idleLastPos;
        private double _nextIdleCheckMs, _lastRehookMs = -100000;
        private double _nextCapsPollMs, _capsCheckAtMs = -1, _nextMonitorPollMs;
        private bool? _lastCapsSent;
        private HashSet<string> _knownTrackpads = new();
        private bool _senderCarrying;               // sender's view: a carry is in progress (button watchdog)
        private double _carryButtonUpSinceMs = -1;
        private readonly byte[] _ks = new byte[256];
        private readonly char[] _buf = new char[16];
        private (uint vk, uint scan, byte[] state)? _pendingDead;

        public Controller()
        {
            _mouseProc = MouseHook;
            _kbProc = KeyboardHook;
            // UI thread → hook thread: an OLE file drag entered the edge catcher.
            DropCatcher.Armed = files => Post(() => OnArmed(files));
        }

        public string? ActivePeer => _activePeer;

        // =====================================================================
        // Lifecycle
        // =====================================================================

        public void Start()
        {
            if (_hookThread != null || _out.IsAddingCompleted) return; // single use: one Start/Stop per instance
            _stopping = false;
            RebuildPortals();
            Layout.Changed += RebuildPortals;

            _sender = new Thread(SenderMain) { IsBackground = true, Name = "AirBridge input sender", Priority = ThreadPriority.AboveNormal };
            _sender.Start();

            _hookThread = new Thread(HookMain) { IsBackground = true, Name = "AirBridge input hooks", Priority = ThreadPriority.Highest };
            _hookThread.SetApartmentState(ApartmentState.STA);
            _hookThread.Start();
            _hookReady.Wait(3000);
        }

        public void Stop()
        {
            Layout.Changed -= RebuildPortals;
            var t = _hookThread;
            if (t != null)
            {
                Post(() => DoReturn(null, sendLeave: true));
                NativeMethods.PostThreadMessage(_hookThreadId, NativeMethods.WM_QUIT, IntPtr.Zero, IntPtr.Zero);
                t.Join(2000);
                _hookThread = null;
            }
            _stopping = true;
            _out.CompleteAdding();
            _sender?.Join(2000);
            CursorHider.Restore();
            DropCatcher.Carrying = false;
            DropCatcher.Hide();
        }

        // =====================================================================
        // Thread-safe entry points
        // =====================================================================

        /// <summary>Return the cursor to this PC (no-op if not controlling).</summary>
        /// <param name="restoreCursor">false when someone else is about to place the cursor (a remote ctl_enter).</param>
        public void RequestReturn(bool sendLeave, bool restoreCursor = true) => Post(() => DoReturn(null, sendLeave, restoreCursor));

        /// <summary>While a remote peer holds this PC's control lease, edges never capture (§8.6).</summary>
        public void SetInhibited(bool inhibited)
        {
            _inhibit = inhibited;
            if (inhibited) Post(Unpin);
        }

        /// <summary>ctl_busy from a target: stop forwarding, give the cursor back, tell the user (§8.6).</summary>
        public void OnCtlBusy(string peerId, JsonObject msg)
        {
            string holder = Msg.Str(msg, "holder") is { Length: > 0 } h ? h : "another device";
            ControlState.Notice($"{ControlState.NameOf(peerId)} is being controlled by {holder}");
            Post(() =>
            {
                if (!_remote || _portal?.PeerId != peerId) return;
                var edge = _portal.PcEdge;
                DoReturn(null, sendLeave: false);
                _noCaptureUntil[(int)edge] = Environment.TickCount64 + BusyCooldownMs;
            });
        }

        /// <summary>
        /// ctl_leave from the target we are controlling: resume at the matching point of our edge. Reason
        /// "unavailable" (e.g. Android accessibility off) or "loop" (it is controlling us): back at once, notice, 5 s off.
        /// </summary>
        public void OnCtlLeave(string peerId, JsonObject msg)
        {
            if (_activePeer != peerId) return;
            double? rel = Msg.Num(msg, "rel");
            var why = CaptureEndExt.FromLeaveReason(Msg.Str(msg, "reason"));
            if (why != CaptureEnd.Other) rel = null; // a refusal carries no position: back where we entered
            Post(() =>
            {
                if (_remote && _portal?.PeerId == peerId) DoReturn(rel, sendLeave: false, why: why);
            });
        }

        /// <summary>ctl_ack from the target (§8.1): its cursor is up and it injects; now hide ours.</summary>
        public void OnCtlAck(string peerId)
        {
            if (_activePeer != peerId) return;
            Post(() => Acked(peerId));
        }

        /// <summary>The peer's session is closing (any reason): give the cursor back now (§8.6 "No stray state").</summary>
        public void OnSessionEnded(string peerId)
        {
            Post(() =>
            {
                _ended.Add(peerId);
                if (_pinPortal?.PeerId == peerId) Unpin();
                if (_remote && _portal?.PeerId == peerId) DoReturn(null, sendLeave: false);
            });
        }

        public void OnPeersChanged(IReadOnlyList<PeerInfo> peers)
        {
            RebuildPortals();
            Post(_ended.Clear); // the closing session is gone (or replaced) by now
            string? active = _activePeer;
            if (active != null && !peers.Any(p => p.Id == active && p.Caps.Contains("ctl_target")))
                Post(() => { if (_remote && _portal?.PeerId == active) DoReturn(null, sendLeave: false); });
            Enqueue(new Ev(K.Peers, Ids: peers.Where(p => p.Caps.Contains("trackpad")).Select(p => p.Id).ToArray()));
        }

        public void RebuildPortals()
        {
            lock (_rebuildGate)
            {
                var mons = Monitors.Enumerate();
                var union = Monitors.Union(mons);
                var placements = Layout.Snapshot();
                var settings = Layout.Settings();
                var portals = new List<Portal>();
                // Only online peers with ctl_target get a portal: an edge with no assigned or an offline peer is inert.
                foreach (var peer in PeerDirectory.Current)
                {
                    if (!peer.Caps.Contains("ctl_target")) continue;
                    if (!placements.TryGetValue(peer.Id, out var pl) || EdgeExt.Parse(pl.Edge) is not Edge e) continue;
                    if (!settings.EdgeCaptureEnabled(e)) continue;
                    double len = e.IsVertical() ? union.Height : union.Width;
                    double start = (e.IsVertical() ? union.Top : union.Left) + pl.Offset * len;
                    portals.Add(new Portal(peer.Id, e, start, start + pl.Size * len, Monitors.OuterSegments(mons, e).ToArray(),
                        DragCarryController.Eligible(peer.Caps)));
                }
                _map = new PortalMap(portals.ToArray(), mons, LayoutData.ThresholdFor(settings.CaptureStrength), settings.CaptureRequireAlt);
            }
        }

        private void Post(Action a)
        {
            if (_hookThread == null || _hookThreadId == 0) return;
            _hookCmds.Enqueue(a);
            NativeMethods.PostThreadMessage(_hookThreadId, WM_APP_CMD, IntPtr.Zero, IntPtr.Zero);
        }

        private void Enqueue(in Ev ev)
        {
            if (_out.IsAddingCompleted) return;
            try { _out.TryAdd(ev); } catch (InvalidOperationException) { }
        }

        // =====================================================================
        // Hook thread
        // =====================================================================

        private void HookMain()
        {
            DpiBootstrap.EnterThread();
            NativeMethods.PeekMessage(out _, IntPtr.Zero, 0, 0, NativeMethods.PM_NOREMOVE); // create the queue
            _hookThreadId = NativeMethods.GetCurrentThreadId();
            InstallHooks();
            InstallRawInput();
            _hookReady.Set();

            while (NativeMethods.GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                if (msg.message == NativeMethods.WM_INPUT)
                {
                    // Only parsed while pinned at an edge; DispatchMessage lets DefWindowProc free the input.
                    if ((_pinPortal != null && !_remote) || (_remote && _carry.IsCarrying))
                    {
                        try { OnRawInput(msg.lParam); } catch (Exception ex) { Debug.WriteLine($"raw input: {ex.Message}"); }
                    }
                    NativeMethods.DispatchMessage(ref msg);
                    continue;
                }
                if (msg.message == WM_APP_CMD)
                {
                    while (_hookCmds.TryDequeue(out var a))
                    {
                        try { a(); } catch (Exception ex) { Debug.WriteLine($"hook cmd failed: {ex}"); }
                    }
                    continue;
                }
                NativeMethods.TranslateMessage(ref msg);
                NativeMethods.DispatchMessage(ref msg);
            }

            try { DoReturn(null, sendLeave: true); } catch { }
            try { Unpin(); } catch { }
            UninstallHooks();
            UninstallRawInput();
            _hookThreadId = 0;
        }

        private void InstallRawInput()
        {
            try
            {
                // A message-only window of the built-in STATIC class: no WndProc delegate to keep alive.
                _rawHwnd = NativeMethods.CreateWindowEx(0, "STATIC", "AirBridge raw input", 0, 0, 0, 0, 0,
                    NativeMethods.HWND_MESSAGE, IntPtr.Zero, NativeMethods.GetModuleHandle(null), IntPtr.Zero);
                if (_rawHwnd == IntPtr.Zero) { Debug.WriteLine($"raw input window failed: {Marshal.GetLastWin32Error()}"); return; }
                _rawBuf = Marshal.AllocHGlobal(RawBufSize);
                var rid = new[]
                {
                    new NativeMethods.RAWINPUTDEVICE
                    {
                        usUsagePage = 0x01, usUsage = 0x02, // generic desktop / mouse
                        dwFlags = NativeMethods.RIDEV_INPUTSINK, hwndTarget = _rawHwnd,
                    },
                };
                if (!NativeMethods.RegisterRawInputDevices(rid, 1, (uint)Marshal.SizeOf<NativeMethods.RAWINPUTDEVICE>()))
                    Debug.WriteLine($"RegisterRawInputDevices failed: {Marshal.GetLastWin32Error()} (hook overshoot fallback only)");
            }
            catch (Exception ex) { Debug.WriteLine($"raw input setup: {ex.Message}"); }
        }

        private void UninstallRawInput()
        {
            try
            {
                if (_rawHwnd != IntPtr.Zero)
                {
                    var rid = new[] { new NativeMethods.RAWINPUTDEVICE { usUsagePage = 0x01, usUsage = 0x02, dwFlags = NativeMethods.RIDEV_REMOVE } };
                    NativeMethods.RegisterRawInputDevices(rid, 1, (uint)Marshal.SizeOf<NativeMethods.RAWINPUTDEVICE>());
                    NativeMethods.DestroyWindow(_rawHwnd);
                }
            }
            catch { }
            _rawHwnd = IntPtr.Zero;
            if (_rawBuf != IntPtr.Zero) { Marshal.FreeHGlobal(_rawBuf); _rawBuf = IntPtr.Zero; }
        }

        private void InstallHooks()
        {
            IntPtr hMod = NativeMethods.GetModuleHandle(null);
            _mouseHook = NativeMethods.SetWindowsHookEx(NativeMethods.WH_MOUSE_LL, _mouseProc, hMod, 0);
            _kbHook = NativeMethods.SetWindowsHookEx(NativeMethods.WH_KEYBOARD_LL, _kbProc, hMod, 0);
            if (_mouseHook == IntPtr.Zero || _kbHook == IntPtr.Zero)
                Debug.WriteLine($"SetWindowsHookEx failed: {Marshal.GetLastWin32Error()}");
        }

        private void UninstallHooks()
        {
            if (_mouseHook != IntPtr.Zero) NativeMethods.UnhookWindowsHookEx(_mouseHook);
            if (_kbHook != IntPtr.Zero) NativeMethods.UnhookWindowsHookEx(_kbHook);
            _mouseHook = _kbHook = IntPtr.Zero;
        }

        /// <summary>Windows silently removes hooks that time out; the sender's watchdog asks us to re-install.</summary>
        private void Rehook()
        {
            UninstallHooks();
            InstallHooks();
        }

        private IntPtr MouseHook(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode < 0) return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
            _hookEvents++;
            try
            {
                uint flags = (uint)Marshal.ReadInt32(lParam, 12);
                if ((flags & NativeMethods.LLMHF_INJECTED) != 0)
                    return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);

                int msg = (int)wParam;
                int x = Marshal.ReadInt32(lParam, 0), y = Marshal.ReadInt32(lParam, 4);
                uint data = (uint)Marshal.ReadInt32(lParam, 8);
                return _remote ? RemoteMouse(msg, x, y, data, nCode, wParam, lParam) : LocalMouse(msg, x, y, data, nCode, wParam, lParam);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"mouse hook: {ex.Message}");
                return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
            }
        }

        private static int ButtonIndex(int msg, uint data, out bool down)
        {
            down = false;
            switch (msg)
            {
                case NativeMethods.WM_LBUTTONDOWN: down = true; return 0;
                case NativeMethods.WM_LBUTTONUP: return 0;
                case NativeMethods.WM_RBUTTONDOWN: down = true; return 1;
                case NativeMethods.WM_RBUTTONUP: return 1;
                case NativeMethods.WM_MBUTTONDOWN: down = true; return 2;
                case NativeMethods.WM_MBUTTONUP: return 2;
                case NativeMethods.WM_XBUTTONDOWN: down = true; return ((data >> 16) & 0xFFFF) == 2 ? 4 : 3;
                case NativeMethods.WM_XBUTTONUP: return ((data >> 16) & 0xFFFF) == 2 ? 4 : 3;
                default: return -1;
            }
        }

        private IntPtr LocalMouse(int msg, int x, int y, uint data, int nCode, IntPtr wParam, IntPtr lParam)
        {
            int b = ButtonIndex(msg, data, out bool down);
            if (b >= 0)
            {
                if (!down && _suppressBtnUp[b]) { _suppressBtnUp[b] = false; _localBtn[b] = false; return (IntPtr)1; }
                _localBtn[b] = down;
                if (b == 0) _armedFiles = null; // a new drag (or the end of one): arm again from scratch
                if (down) { _movedWhileDown = false; Unpin(); }
                else
                {
                    if (b == 0) Unpin(); // released at the edge without crossing: the catcher goes
                    if (_movedWhileDown && !AnyLocalButtonState())
                    {
                        _movedWhileDown = false;
                        _dragQuietUntil = Environment.TickCount64 + DragQuietMs; // no capture right after a drag
                    }
                }
            }
            else if (msg == NativeMethods.WM_MOUSEMOVE)
            {
                if (AnyLocalButton())
                {
                    _movedWhileDown = true;
                    // §8.8: a left-button drag may still cross into a drag_files peer, once the edge catcher saw files.
                    if (OnlyLeftHeld()) { if (EvaluatePin(x, y, drag: true)) return (IntPtr)1; }
                    else Unpin();
                }
                else if (EvaluatePin(x, y)) return (IntPtr)1;
            }
            return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
        }

        private static readonly int[] ButtonVks = { 0x01, 0x02, 0x04, 0x05, 0x06 };

        private bool AnyLocalButtonState()
        {
            foreach (bool b in _localBtn) if (b) return true;
            return false;
        }

        private bool OnlyLeftHeld()
        {
            if (!_localBtn[0]) return false;
            for (int i = 1; i < _localBtn.Length; i++) if (_localBtn[i]) return false;
            return true;
        }

        private bool AnyLocalButton()
        {
            if (!AnyLocalButtonState()) return false;
            // An up may have been lost (secure desktop, UAC); don't block crossing forever on stale state.
            foreach (int vk in ButtonVks)
                if ((NativeMethods.GetAsyncKeyState(vk) & 0x8000) != 0) return true;
            Array.Clear(_localBtn);
            return false;
        }

        // ---------------------------------------------------------------- capture intent (§8.2)

        /// <summary>
        /// Hook thread, local mode, real mouse move with no button held. Updates the pinned edge and feeds the
        /// hook overshoot into the accumulator when raw input isn't flowing. Returns true if the cursor was captured.
        /// </summary>
        private bool EvaluatePin(int x, int y, bool drag = false)
        {
            var map = _map;
            long now = Environment.TickCount64;
            if (_inhibit || !ControlGate.Shared.MayCapture || map.Portals.Length == 0 || map.Monitors.Count == 0
                || (!drag && now < _dragQuietUntil))
            {
                Unpin();
                return false;
            }

            var c = Monitors.ClampToNearest(map.Monitors, x, y);
            foreach (var p in map.Portals)
            {
                if (drag && !p.Drag) continue;
                if (now < _noCaptureUntil[(int)p.PcEdge] || _refused.IsRefused(p.PeerId, now) || _ended.Contains(p.PeerId)) continue;
                foreach (var s in p.Segments)
                {
                    if (c.mon.Bounds != s.Monitor) continue;
                    bool atEdge;
                    int along, overshoot;
                    switch (p.PcEdge)
                    {
                        case Edge.Left: atEdge = c.x <= s.Fixed; along = c.y; overshoot = s.Fixed - x; break;
                        case Edge.Right: atEdge = c.x >= s.Fixed - 1; along = c.y; overshoot = x - (s.Fixed - 1); break;
                        case Edge.Top: atEdge = c.y <= s.Fixed; along = c.x; overshoot = s.Fixed - y; break;
                        default: atEdge = c.y >= s.Fixed - 1; along = c.x; overshoot = y - (s.Fixed - 1); break;
                    }
                    if (!atEdge) continue;
                    int dead = Math.Min(3, (s.To - s.From) / 4); // corners stay usable for hot corners
                    if (along < s.From + dead || along >= s.To - dead) continue;
                    if (along < p.SpanStart || along > p.SpanEnd) continue;

                    if (!ReferenceEquals(_pinPortal, p) || _pinSeg != s || _pinDrag != drag)
                    {
                        ResetPush();
                        _pinPortal = p;
                        _pinSeg = s;
                        _pinDrag = drag;
                        _pinScale = c.mon.Scale > 0 ? c.mon.Scale : 1;
                    }
                    _pinAlong = along;
                    if (drag)
                    {
                        if (!_catcherShown)
                        {
                            _catcherShown = true;
                            DropCatcher.Carrying = false;
                            DropCatcher.Show(CatcherRect());
                        }
                        if (_armedFiles == null) return false; // no OLE file drag seen on the catcher (yet): never cross
                    }
                    if (map.RequireAlt && !(_physLAlt || _physRAlt)) { ResetPush(); return false; }
                    if (overshoot > 0 && now - _lastRawMs > RawFreshMs) return Push(overshoot, now);
                    return false;
                }
            }
            Unpin();
            return false;
        }

        /// <summary>Hook thread (message loop): relative raw mouse counts while pinned.</summary>
        private void OnRawInput(IntPtr hRawInput)
        {
            if (_rawBuf == IntPtr.Zero) return;
            if (_remote && _carry.IsCarrying) { OnCarryRawInput(hRawInput); return; }
            if (_pinPortal is not { } p) return;
            uint hdr = (uint)(8 + 2 * IntPtr.Size); // RAWINPUTHEADER
            uint size = RawBufSize;
            uint got = NativeMethods.GetRawInputData(hRawInput, NativeMethods.RID_INPUT, _rawBuf, ref size, hdr);
            if (got == uint.MaxValue || got < hdr + 24) return;
            if (Marshal.ReadInt32(_rawBuf, 0) != NativeMethods.RIM_TYPEMOUSE) return;
            int off = (int)hdr; // RAWMOUSE: usFlags@0, buttons@4, rawButtons@8, lLastX@12, lLastY@16
            ushort flags = (ushort)Marshal.ReadInt16(_rawBuf, off);
            if ((flags & NativeMethods.MOUSE_MOVE_ABSOLUTE) != 0) return; // tablets/RDP: hook overshoot fallback
            int dx = Marshal.ReadInt32(_rawBuf, off + 12), dy = Marshal.ReadInt32(_rawBuf, off + 16);
            if (dx == 0 && dy == 0) return;
            long now = Environment.TickCount64;
            _lastRawMs = now;
            if (_inhibit || !ControlGate.Shared.MayCapture || now < _noCaptureUntil[(int)p.PcEdge]) { Unpin(); return; }
            if (_map.RequireAlt && !(_physLAlt || _physRAlt)) return;
            double outward = p.PcEdge switch
            {
                Edge.Right => dx,
                Edge.Left => -dx,
                Edge.Bottom => dy,
                _ => -dy,
            };
            Push(outward, now);
        }

        /// <summary>
        /// §8.8, while carrying: the cursor stays frozen on the edge catcher (never re-anchored to the monitor centre, which
        /// would move the shell drag there), so outward motion is clamped in the hook's coordinates. Relative raw input is
        /// the motion source instead.
        /// </summary>
        private void OnCarryRawInput(IntPtr hRawInput)
        {
            uint hdr = (uint)(8 + 2 * IntPtr.Size);
            uint size = RawBufSize;
            uint got = NativeMethods.GetRawInputData(hRawInput, NativeMethods.RID_INPUT, _rawBuf, ref size, hdr);
            if (got == uint.MaxValue || got < hdr + 24) return;
            if (Marshal.ReadInt32(_rawBuf, 0) != NativeMethods.RIM_TYPEMOUSE) return;
            int off = (int)hdr;
            ushort flags = (ushort)Marshal.ReadInt16(_rawBuf, off);
            if ((flags & NativeMethods.MOUSE_MOVE_ABSOLUTE) != 0) return; // hook deltas are used for absolute devices
            int dx = Marshal.ReadInt32(_rawBuf, off + 12), dy = Marshal.ReadInt32(_rawBuf, off + 16);
            if (dx == 0 && dy == 0) return;
            _lastRawMs = Environment.TickCount64;
            RemoteDelta(dx, dy);
        }

        /// <summary>The CF_HDROP list from the catcher's OLE DragEnter. Kept for this drag only.</summary>
        private void OnArmed(string[] files)
        {
            if (_remote || !_localBtn[0] || files.Length == 0) return;
            _armedFiles = files;
        }

        /// <summary>The catcher: the portal's bar on the pinned segment, a little thicker, flush with the outer edge.</summary>
        private IRect CatcherRect()
        {
            var b = BarRect();
            int t = Math.Max(CatcherThickness, (int)Math.Round(CatcherThickness * _pinScale));
            return _pinPortal!.PcEdge switch
            {
                Edge.Left => new IRect(b.Left, b.Top, b.Left + t, b.Bottom),
                Edge.Right => new IRect(b.Right - t, b.Top, b.Right, b.Bottom),
                Edge.Top => new IRect(b.Left, b.Top, b.Right, b.Top + t),
                _ => new IRect(b.Left, b.Bottom - t, b.Right, b.Bottom),
            };
        }

        /// <summary>Adds an outward sample; captures when the 500 ms window reaches the threshold.</summary>
        private bool Push(double outward, long now)
        {
            if (_pinPortal == null) return false;
            if (_pinDrag && _armedFiles == null) return false; // §8.8: no file drag confirmed, no crossing
            if (outward < 0) { ResetPush(); return false; } // any inward motion resets
            if (outward == 0) return false;

            while (_pushCount > 0)
            {
                int tail = (_pushHead - _pushCount + PushRing) % PushRing;
                if (now - _pushT[tail] <= CaptureWindowMs && _pushCount < PushRing) break;
                _pushSum -= _pushV[tail];
                _pushCount--;
            }
            if (_pushCount == 0) _pushSum = 0;
            _pushT[_pushHead] = now;
            _pushV[_pushHead] = outward;
            _pushHead = (_pushHead + 1) % PushRing;
            _pushCount++;
            _pushSum += outward;

            double threshold = Math.Max(1, _map.Threshold * _pinScale);
            if (_pushSum >= threshold)
            {
                Capture();
                return true;
            }
            int step = (int)(_pushSum * 20 / threshold); // indicator in 5 % steps
            if (step != _lastChargeStep)
            {
                _lastChargeStep = step;
                EdgeIndicator.Charge(BarRect(), step / 20.0);
            }
            return false;
        }

        private void ResetPush()
        {
            _pushCount = 0;
            _pushSum = 0;
            if (_lastChargeStep >= 0)
            {
                _lastChargeStep = -1;
                EdgeIndicator.Hide();
            }
        }

        private void Unpin()
        {
            if (_catcherShown && !_carry.IsCarrying)
            {
                _catcherShown = false;
                DropCatcher.Hide();
            }
            if (_pinPortal == null) return;
            _pinPortal = null;
            _pinDrag = false;
            ResetPush();
        }

        /// <summary>The peer's span on the pinned segment, as a thin bar just inside the screen (physical px).</summary>
        private IRect BarRect()
        {
            var p = _pinPortal!;
            var s = _pinSeg;
            int from = (int)Math.Max(s.From, Math.Floor(p.SpanStart));
            int to = (int)Math.Min(s.To, Math.Ceiling(p.SpanEnd));
            if (to <= from) { from = s.From; to = s.To; }
            int t = Math.Max(2, (int)Math.Round(4 * _pinScale));
            return p.PcEdge switch
            {
                Edge.Left => new IRect(s.Fixed, from, s.Fixed + t, to),
                Edge.Right => new IRect(s.Fixed - t, from, s.Fixed, to),
                Edge.Top => new IRect(from, s.Fixed, to, s.Fixed + t),
                _ => new IRect(from, s.Fixed - t, to, s.Fixed),
            };
        }

        private void Capture()
        {
            var p = _pinPortal!;
            var s = _pinSeg;
            var bar = BarRect();
            var files = _pinDrag ? _armedFiles : null;
            _pinPortal = null;
            _pinDrag = false;
            _pushCount = 0;
            _pushSum = 0;
            _lastChargeStep = -1;
            if (!ControlGate.Shared.MayCapture) { EdgeIndicator.Hide(); Unpin(); return; } // no chaining (§8.6)
            EdgeIndicator.Flash(bar);
            Enter(p, s, _pinAlong, files);
        }

        private void Enter(Portal p, EdgeSegment s, int along, string[]? carryFiles = null)
        {
            double rel = Math.Clamp((along - p.SpanStart) / Math.Max(1.0, p.SpanEnd - p.SpanStart), 0.0, 1.0);
            _entry = p.PcEdge switch
            {
                Edge.Left => new NativeMethods.POINT { x = s.Fixed, y = along },
                Edge.Right => new NativeMethods.POINT { x = s.Fixed - 1, y = along },
                Edge.Top => new NativeMethods.POINT { x = along, y = s.Fixed },
                _ => new NativeMethods.POINT { x = along, y = s.Fixed - 1 },
            };
            _entrySeg = s;
            _entryAlong = along;
            var mon = MonitorOf(s.Monitor);
            double scale = mon?.Scale > 0 ? mon.Scale : 1;
            var info = PeerDirectory.Find(p.PeerId);
            double extent = CaptureEscape.TargetExtent(p.PcEdge, info?.Platform, info?.ScreenW ?? 0, info?.ScreenH ?? 0);
            _esc = new CaptureEscape(p.PcEdge, extent, CaptureEscape.ReturnSlackDip * scale, Environment.TickCount64);
            // Until ctl_ack the cursor stays visible, frozen on the edge where it crossed (§8.6 "Smooth hand-off"); it moves
            // to the monitor centre (more room for deltas) only once hidden, see Acked.
            _anchor = _entry;
            _remote = true;
            _portal = p;
            _activePeer = p.PeerId;
            ControlGate.Shared.Controlling = p.PeerId;
            NativeMethods.SetCursorPos(_anchor.x, _anchor.y);
            _capsOn = (NativeMethods.GetKeyState(KeyMap.VK_CAPITAL) & 1) != 0;

            // Keys still held locally: release them locally now; swallow their physical ups later.
            var synth = new List<int>();
            for (int id = 0; id < 512; id++)
            {
                if (!_localHeld[id]) continue;
                _localHeld[id] = false;
                if ((id & 0xFF) == KeyMap.VK_CAPITAL) continue;
                _suppressUp[id] = true;
                synth.Add(id & 0xFF);
            }
            Enqueue(new Ev(K.Enter, p.PeerId, A: rel, I: _anchor.x, J: _anchor.y, F: _capsOn,
                Edge: p.PcEdge.Opposite(), Vks: synth.Distinct().ToArray(), Mon: mon));
            if (carryFiles is { Length: > 0 })
            {
                // §8.8: the held left button stays local (it never becomes ptr_btn); the target shows a ghost.
                var a = _carry.Begin(p.PeerId);
                _carryFiles = carryFiles;
                DropCatcher.Carrying = true;
                Enqueue(new Ev(K.Carry, p.PeerId, A: a.Id, Ids: carryFiles));
            }
            else if (_catcherShown) { _catcherShown = false; DropCatcher.Hide(); }
        }

        private MonitorInfo? MonitorOf(IRect bounds)
        {
            foreach (var m in _map.Monitors) if (m.Bounds == bounds) return m;
            return null;
        }

        /// <summary>Hook thread: ctl_ack for the current capture. The sender hides the cursor, then we re-anchor.</summary>
        private void Acked(string peerId)
        {
            if (!_remote || _portal?.PeerId != peerId || _esc == null || !_esc.Ack()) return;
            var m = _entrySeg.Monitor;
            // Carrying (§8.8): the cursor must stay on the edge catcher, so it is not moved to the monitor centre.
            if (_carry.IsCarrying) Enqueue(new Ev(K.Acked, peerId, I: _anchor.x, J: _anchor.y));
            else Enqueue(new Ev(K.Acked, peerId, I: m.Left + m.Width / 2, J: m.Top + m.Height / 2));
        }

        /// <summary>Hook thread, after the sender hid the cursor: move the frozen cursor to the monitor centre.</summary>
        private void Reanchor(string peerId, int x, int y)
        {
            if (!_remote || _portal?.PeerId != peerId || _esc?.Acked != true) return;
            if (_carry.IsCarrying) return; // stays on the catcher
            _anchor = new NativeMethods.POINT { x = x, y = y };
            NativeMethods.SetCursorPos(x, y);
        }

        /// <summary>Hook thread: no ctl_ack in time → the target can't or won't take control (§8.1).</summary>
        private void CheckAck(string peerId)
        {
            if (_remote && _portal?.PeerId == peerId && _esc != null && _esc.AckOverdue(Environment.TickCount64))
                DoReturn(null, sendLeave: true, why: CaptureEnd.NoAck);
        }

        private bool AnyRemoteButton() => _remoteBtn[0] || _remoteBtn[1] || _remoteBtn[2];

        /// <summary>Hook thread: the sender found the button up during a carry without a release in the hook.</summary>
        private void CarryButtonLost()
        {
            if (!_carry.IsCarrying) return;
            if ((NativeMethods.GetAsyncKeyState(0x01) & 0x8000) != 0 || (NativeMethods.GetAsyncKeyState(0x02) & 0x8000) != 0) return;
            var a = _carry.End();
            _localBtn[0] = false;
            _armedFiles = null;
            _carryFiles = null;
            _catcherShown = false;
            DropCatcher.Carrying = false;
            DropCatcher.Hide(1000);
            if (a.Kind == DragCarryController.Kind.Cancel) Enqueue(new Ev(K.CarryCancel, a.Peer, A: a.Id));
        }

        /// <summary>Hook thread: one relative motion sample while captured.</summary>
        private void RemoteDelta(double dx, double dy)
        {
            Enqueue(new Ev(K.Move, A: dx, B: dy));
            // Our own estimate of the remote cursor: moving back through the entry edge returns control even
            // if the target never answers (§8.1 "Escape is the controller's job").
            if (_esc != null && _esc.Move(dx, dy, AnyRemoteButton()))
                DoReturn(null, sendLeave: true, why: CaptureEnd.MovedBack);
        }

        /// <summary>
        /// §8.8: the carried drag was let go over the target. Send drag_drop and the files, and let this release through:
        /// the frozen cursor sits on the edge catcher, so the shell drop lands there (accepted as a no-op copy).
        /// </summary>
        private IntPtr DropCarried(int nCode, IntPtr wParam, IntPtr lParam)
        {
            var a = _carry.Release();
            _localBtn[0] = false;
            _armedFiles = null;
            if (a.Kind == DragCarryController.Kind.Drop && a.Peer != null)
                Enqueue(new Ev(K.Drop, a.Peer, A: a.Id, Ids: _carryFiles));
            _carryFiles = null;
            _catcherShown = false;
            DropCatcher.Hide(1500); // keeps Carrying until the drop has been performed on it
            return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
        }

        private IntPtr RemoteMouse(int msg, int x, int y, uint data, int nCode, IntPtr wParam, IntPtr lParam)
        {
            switch (msg)
            {
                case NativeMethods.WM_MOUSEMOVE:
                    int dx = x - _anchor.x, dy = y - _anchor.y;
                    if (dx == 0 && dy == 0) break;
                    // Carrying: raw input drives the motion (see OnCarryRawInput); hook deltas only without it.
                    if (_carry.IsCarrying && Environment.TickCount64 - _lastRawMs <= RawFreshMs) break;
                    RemoteDelta(dx, dy);
                    break;
                case NativeMethods.WM_MOUSEWHEEL:
                    // Windows: positive = wheel away from user = content moves down. Protocol: positive dy = content up.
                    Enqueue(new Ev(K.Scroll, A: 0, B: -(short)((data >> 16) & 0xFFFF) / 120.0));
                    break;
                case NativeMethods.WM_MOUSEHWHEEL:
                    Enqueue(new Ev(K.Scroll, A: (short)((data >> 16) & 0xFFFF) / 120.0, B: 0));
                    break;
                default:
                    int b = ButtonIndex(msg, data, out bool down);
                    if (b < 0) break;
                    if (!down && _suppressBtnUp[b]) { _suppressBtnUp[b] = false; _localBtn[b] = false; break; }
                    if (b == 0 && !down && !_remoteBtn[0] && _carry.IsCarrying) return DropCarried(nCode, wParam, lParam);
                    if (b > 2) break; // X buttons are not part of the protocol
                    if (down == _remoteBtn[b]) break; // keep down/up strictly paired
                    _remoteBtn[b] = down;
                    Enqueue(new Ev(K.Btn, I: b, F: down));
                    break;
            }
            return (IntPtr)1;
        }

        private IntPtr KeyboardHook(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode < 0) return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
            try
            {
                uint vk = (uint)Marshal.ReadInt32(lParam, 0);
                uint scan = (uint)Marshal.ReadInt32(lParam, 4);
                uint flags = (uint)Marshal.ReadInt32(lParam, 8);
                if ((flags & NativeMethods.LLKHF_INJECTED) != 0)
                    return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
                return HandleKey(vk, scan, flags) ? (IntPtr)1 : NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"keyboard hook: {ex.Message}");
                return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
            }
        }

        /// <summary>Returns true to swallow the event.</summary>
        private bool HandleKey(uint vk, uint scan, uint flags)
        {
            bool up = (flags & NativeMethods.LLKHF_UP) != 0;
            bool ext = (flags & NativeMethods.LLKHF_EXTENDED) != 0;
            vk &= 0xFF;
            // LL hooks normally report sided modifier VKs; normalise just in case.
            if (vk == KeyMap.VK_SHIFT) vk = (scan & 0xFF) == 0x36 ? (uint)KeyMap.VK_RSHIFT : KeyMap.VK_LSHIFT;
            else if (vk == KeyMap.VK_CONTROL) vk = ext ? (uint)KeyMap.VK_RCONTROL : KeyMap.VK_LCONTROL;
            else if (vk == KeyMap.VK_MENU) vk = ext ? (uint)KeyMap.VK_RMENU : KeyMap.VK_LMENU;

            bool fakeShift = (vk == KeyMap.VK_LSHIFT || vk == KeyMap.VK_RSHIFT) && KeyMap.IsFakeShift(scan, ext);
            bool altGrCtrl = KeyMap.IsAltGrCtrl(vk, scan);
            int id = (int)vk + (altGrCtrl ? 256 : 0);

            if (!altGrCtrl)
            {
                switch ((int)vk)
                {
                    case KeyMap.VK_LCONTROL: _physLCtrl = !up; break;
                    case KeyMap.VK_RCONTROL: _physRCtrl = !up; break;
                    case KeyMap.VK_LMENU: _physLAlt = !up; break;
                    case KeyMap.VK_RMENU: _physRAlt = !up; break;
                }
            }

            if (!_remote)
            {
                if (fakeShift) return false;
                // Key whose down went to the target: swallow its auto-repeats and its final up.
                if (_suppressUp[id]) { if (up) _suppressUp[id] = false; return true; }
                _localHeld[id] = !up;
                return false;
            }

            // ---- controlling a remote peer ----
            if (!up && vk == KeyMap.VK_ESCAPE && (_physLCtrl || _physRCtrl) && (_physLAlt || _physRAlt))
            {
                _suppressUp[id] = true;
                DoReturn(null, sendLeave: true);
                return true;
            }
            if (fakeShift) return true;
            // Key held locally when we crossed (already released locally): swallow repeats and its up.
            if (_suppressUp[id]) { if (up) _suppressUp[id] = false; return true; }

            if (vk == KeyMap.VK_CAPITAL)
            {
                // Caps lock is forwarded AND passed through locally so the LED and local state stay truthful.
                bool rep = !up && _remoteHeld[id];
                if (!up && !rep) _capsOn = !_capsOn;
                if (up && !_remoteHeld[id]) return false;
                _remoteHeld[id] = !up;
                Enqueue(new Ev(K.Key, I: (int)vk, J: (int)scan, Flags: Kf(ext, up, rep, false), Mods: Mods()));
                Enqueue(new Ev(K.CapsCheck));
                return false;
            }

            string? action = KeyMap.ConsumerAction(vk);
            if (action != null)
            {
                if (!up) Enqueue(new Ev(K.Action, S: action));
                return true;
            }
            if (KeyMap.IsSwallowOnlyVk(vk)) return true;

            if (up && !_remoteHeld[id]) return true; // down never went to the target
            bool repeat = !up && _remoteHeld[id];
            _remoteHeld[id] = !up;
            Enqueue(new Ev(K.Key, I: (int)vk, J: (int)scan, Flags: Kf(ext, up, repeat, altGrCtrl), Mods: Mods()));
            return true;
        }

        private static int Kf(bool ext, bool up, bool rep, bool altGr) =>
            (ext ? KF_EXT : 0) | (up ? KF_UP : 0) | (rep ? KF_REP : 0) | (altGr ? KF_ALTGR : 0);

        private int Mods()
        {
            int m = 0;
            if (_remoteHeld[KeyMap.VK_LSHIFT]) m |= M_LSHIFT;
            if (_remoteHeld[KeyMap.VK_RSHIFT]) m |= M_RSHIFT;
            if (_remoteHeld[KeyMap.VK_LCONTROL]) m |= M_LCTRL;
            if (_remoteHeld[KeyMap.VK_RCONTROL]) m |= M_RCTRL;
            if (_remoteHeld[KeyMap.VK_LMENU]) m |= M_LALT;
            if (_remoteHeld[KeyMap.VK_RMENU]) m |= M_RALT;
            if (_remoteHeld[KeyMap.VK_LWIN]) m |= M_LWIN;
            if (_remoteHeld[KeyMap.VK_RWIN]) m |= M_RWIN;
            if (_remoteHeld[256 + KeyMap.VK_LCONTROL]) m |= M_ALTGRCTRL;
            if (_capsOn) m |= M_CAPS;
            return m;
        }

        /// <summary>Hook thread only. rel = position along the edge reported by the target (null = the entry point
        /// plus the sideways travel sent since entry).</summary>
        private void DoReturn(double? rel, bool sendLeave, bool restoreCursor = true, CaptureEnd why = CaptureEnd.Other)
        {
            if (!_remote) return;
            var p = _portal!;
            var esc = _esc;
            bool gone = _ended.Contains(p.PeerId) || PeerDirectory.Find(p.PeerId) == null;
            var carry = _carry.End(peerGone: gone);
            if (carry.Kind == DragCarryController.Kind.Cancel)
                Enqueue(new Ev(K.CarryCancel, carry.Peer, A: carry.Id)); // the local drag simply continues here
            _carryFiles = null;
            DropCatcher.Carrying = false;
            _remote = false;
            _portal = null;
            _esc = null;
            _activePeer = null;
            ControlGate.Shared.EndControlling(p.PeerId);
            long now = Environment.TickCount64;
            _noCaptureUntil[(int)p.PcEdge] = now + ReturnCooldownMs; // no bounce
            if (why.Refuses()) _refused.Refuse(p.PeerId, now);
            Unpin();

            for (int id = 0; id < 512; id++)
            {
                if (!_remoteHeld[id]) continue;
                _remoteHeld[id] = false;
                if ((id & 0xFF) != KeyMap.VK_CAPITAL) _suppressUp[id] = true;
            }
            for (int b = 0; b < 3; b++)
                if (_remoteBtn[b]) { _remoteBtn[b] = false; _suppressBtnUp[b] = true; }

            if (restoreCursor)
            {
                double along = rel is double r
                    ? p.SpanStart + Math.Clamp(r, 0, 1) * (p.SpanEnd - p.SpanStart)
                    : CaptureEscape.ReturnAlong(_entryAlong, esc?.Lateral ?? 0, p.SpanStart, p.SpanEnd);
                var (x, y) = ReturnPoint(p, along);
                NativeMethods.SetCursorPos(x, y);
            }
            Enqueue(new Ev(K.Leave, p.PeerId, F: sendLeave, I: (int)why));
        }

        private (int x, int y) ReturnPoint(Portal p, double along)
        {
            int x, y;
            if (p.Segments.Length > 0)
            {
                EdgeSegment best = p.Segments[0];
                double bestD = double.MaxValue;
                foreach (var s in p.Segments)
                {
                    double d = along < s.From ? s.From - along : along >= s.To ? along - (s.To - 1) : 0;
                    if (d < bestD) { bestD = d; best = s; }
                }
                int a = (int)Math.Clamp(Math.Round(along), best.From, best.To - 1);
                int inset = Math.Min(ReturnInset, (p.PcEdge.IsVertical() ? best.Monitor.Width : best.Monitor.Height) / 2);
                (x, y) = p.PcEdge switch
                {
                    Edge.Left => (best.Fixed + inset, a),
                    Edge.Right => (best.Fixed - 1 - inset, a),
                    Edge.Top => (a, best.Fixed + inset),
                    _ => (a, best.Fixed - 1 - inset),
                };
            }
            else
            {
                (x, y) = p.PcEdge switch
                {
                    Edge.Left => (_entry.x + ReturnInset, _entry.y),
                    Edge.Right => (_entry.x - ReturnInset, _entry.y),
                    Edge.Top => (_entry.x, _entry.y + ReturnInset),
                    _ => (_entry.x, _entry.y - ReturnInset),
                };
            }
            var mons = _map.Monitors;
            if (mons.Count > 0)
            {
                var c = Monitors.ClampToNearest(mons, x, y);
                return (c.x, c.y);
            }
            return (x, y);
        }

        // =====================================================================
        // Sender thread
        // =====================================================================

        private double Now => _clock.Elapsed.TotalMilliseconds;

        private void SenderMain()
        {
            DpiBootstrap.EnterThread();
            _lastCapsSent = null;
            while (!_stopping || _out.Count > 0)
            {
                int timeout = _peer != null && !_acked ? 20 : 100;
                if (_pendingMove || _pendingScroll)
                    timeout = (int)Math.Max(0, Math.Ceiling(_lastFlushMs + MoveIntervalMs - Now));
                try
                {
                    if (_out.TryTake(out var ev, timeout)) Handle(ev);
                    while (_out.TryTake(out ev)) Handle(ev);
                    if ((_pendingMove || _pendingScroll) && Now - _lastFlushMs >= MoveIntervalMs) Flush();
                    Periodic();
                }
                catch (Exception ex) { Debug.WriteLine($"input sender: {ex}"); }
                if (_out.IsCompleted) break;
            }
            if (_peer != null) { Flush(); Send(_peer, Msg.Of("ctl_leave")); _peer = null; }
            ControlState.SetControlling(null);
            CursorHider.Restore();
            CaptureHud.HideControlling();
        }

        private void Handle(in Ev ev)
        {
            switch (ev.Kind)
            {
                case K.Move:
                    if (_peer == null) return;
                    _pdx += ev.A; _pdy += ev.B; _pendingMove = true;
                    if (Now - _lastFlushMs >= MoveIntervalMs) Flush();
                    return;
                case K.Scroll:
                    if (_peer == null) return;
                    _sdx += ev.A; _sdy += ev.B; _pendingScroll = true;
                    if (Now - _lastFlushMs >= MoveIntervalMs) Flush();
                    return;
            }

            Flush();
            switch (ev.Kind)
            {
                case K.Enter:
                    _peer = ev.Peer;
                    _anchorX = ev.I; _anchorY = ev.J;
                    _watchdogMisses = 0;
                    _pendingDead = null;
                    _acked = false;
                    _enterMs = Now;
                    _nextAckCheckMs = _enterMs + CaptureEscape.AckTimeoutMs + 10;
                    _hudMon = ev.Mon;
                    SynthLocalUps(ev.Vks);
                    // The cursor stays visible (frozen on the edge) until ctl_ack: never a moment without a cursor.
                    var enter = Msg.Of("ctl_enter");
                    enter["edge"] = ev.Edge.ToWire();
                    enter["rel"] = Math.Round(ev.A, 4);
                    if (!Send(_peer!, enter)) { RequestReturn(false); return; }
                    var ks = Msg.Of("key_state");
                    ks["caps"] = ev.F;
                    Send(_peer!, ks);
                    ControlState.SetControlling(_peer);
                    break;

                case K.Acked:
                    if (_peer == null || _peer != ev.Peer) return;
                    _acked = true;
                    // Hand-off: the target's cursor is up, now ours disappears; only then jump it to the monitor centre.
                    CursorHider.Hide();
                    _anchorX = ev.I; _anchorY = ev.J;
                    _watchdogMisses = 0;
                    string ackPeer = ev.Peer!;
                    int ax = ev.I, ay = ev.J;
                    Post(() => Reanchor(ackPeer, ax, ay));
                    CaptureHud.ShowControlling(ControlState.NameOf(ackPeer), _hudMon);
                    break;

                case K.Carry:
                    if (_peer == null || _peer != ev.Peer || ev.Ids == null) return;
                    _senderCarrying = true;
                    _carryButtonUpSinceMs = -1;
                    try { Send(_peer, DragPayload.Describe(ev.Ids).Message((long)ev.A)); }
                    catch (Exception ex) { Debug.WriteLine($"drag_carry: {ex.Message}"); }
                    break;

                case K.Drop:
                    _senderCarrying = false;
                    if (ev.Peer == null) return;
                    var drop = Msg.Of("drag_drop");
                    drop["id"] = (long)ev.A;
                    Send(ev.Peer, drop);
                    if (ev.Ids is { Length: > 0 } files) DragSend.Start(ev.Peer, files);
                    break;

                case K.CarryCancel:
                    _senderCarrying = false;
                    if (ev.Peer == null) return;
                    var cancel = Msg.Of("drag_cancel");
                    cancel["id"] = (long)ev.A;
                    Send(ev.Peer, cancel);
                    break;

                case K.Leave:
                    _senderCarrying = false;
                    if (ev.F && ev.Peer != null) Send(ev.Peer, Msg.Of("ctl_leave"));
                    if (_peer == ev.Peer) { _peer = null; _acked = false; ControlState.SetControlling(null); }
                    _pdx = _pdy = _sdx = _sdy = 0;
                    _pendingMove = _pendingScroll = false;
                    _pendingDead = null;
                    CursorHider.Restore();
                    CaptureHud.HideControlling();
                    // Names are looked up here, never on the hook thread.
                    if (ev.Peer != null
                        && ((CaptureEnd)ev.I).Notice(ControlState.NameOf(ev.Peer), PeerDirectory.Find(ev.Peer)?.Platform == "android") is { } notice)
                    {
                        ControlState.Notice(notice, 8000);
                        CaptureHud.ShowNotice(notice, _hudMon);
                    }
                    break;

                case K.Btn:
                    if (_peer == null) return;
                    var btn = Msg.Of("ptr_btn");
                    btn["b"] = ev.I;
                    btn["down"] = ev.F;
                    SendOrReturn(btn);
                    break;

                case K.Key:
                    if (_peer == null) return;
                    SendKey(ev);
                    break;

                case K.Action:
                    if (_peer == null) return;
                    var act = Msg.Of("action");
                    act["name"] = ev.S;
                    SendOrReturn(act);
                    break;

                case K.CapsCheck:
                    _capsCheckAtMs = Now + 60;
                    break;

                case K.Peers:
                    var ids = new HashSet<string>(ev.Ids ?? Array.Empty<string>());
                    bool caps = (NativeMethods.GetKeyState(KeyMap.VK_CAPITAL) & 1) != 0;
                    foreach (var id in ids)
                    {
                        if (_knownTrackpads.Contains(id)) continue;
                        var m = Msg.Of("key_state");
                        m["caps"] = caps;
                        Send(id, m);
                    }
                    _knownTrackpads = ids;
                    break;
            }
        }

        private void Flush()
        {
            if (_peer == null) { _pendingMove = _pendingScroll = false; _pdx = _pdy = _sdx = _sdy = 0; return; }
            if (!_pendingMove && !_pendingScroll) return;
            _lastFlushMs = Now;
            if (_pendingMove)
            {
                var m = Msg.Of("ptr_move");
                m["dx"] = Msg.Plain(_pdx);
                m["dy"] = Msg.Plain(_pdy);
                _pdx = _pdy = 0; _pendingMove = false;
                if (!SendOrReturn(m)) { _pendingScroll = false; _sdx = _sdy = 0; return; }
            }
            if (_pendingScroll)
            {
                var s = Msg.Of("ptr_scroll");
                s["dx"] = Msg.Plain(_sdx);
                s["dy"] = Msg.Plain(_sdy);
                s["px"] = false;
                _sdx = _sdy = 0; _pendingScroll = false;
                SendOrReturn(s);
            }
        }

        private static bool Send(string peer, JsonObject msg)
        {
            try { return PeerBus.Send(peer, msg); }
            catch (Exception ex) { Debug.WriteLine($"PeerBus.Send: {ex.Message}"); return false; }
        }

        private bool SendOrReturn(JsonObject msg)
        {
            if (_peer == null) return false;
            if (Send(_peer, msg)) return true;
            // Peer has no ready session any more: give the cursor back.
            RequestReturn(false);
            return false;
        }

        private void Periodic()
        {
            double now = Now;
            if (_senderCarrying)
            {
                // §8.8 safety net: carrying, but the primary button is physically up and the hook never saw the release
                // (hook timeout, secure desktop). Cancel the carry on the hook thread; a release we missed already went
                // to the edge catcher, which is harmless.
                bool held = (NativeMethods.GetAsyncKeyState(0x01) & 0x8000) != 0 || (NativeMethods.GetAsyncKeyState(0x02) & 0x8000) != 0;
                if (held) _carryButtonUpSinceMs = -1;
                else if (_carryButtonUpSinceMs < 0) _carryButtonUpSinceMs = now;
                else if (now - _carryButtonUpSinceMs > 300)
                {
                    _carryButtonUpSinceMs = -1; // re-checked every 300 ms until the hook thread decides
                    Post(CarryButtonLost);
                }
            }
            if (_peer != null && !_acked && now >= _nextAckCheckMs)
            {
                // The hook thread owns the capture state and decides; this only wakes it (every 50 ms while overdue).
                _nextAckCheckMs = now + 50;
                string waiting = _peer;
                Post(() => CheckAck(waiting));
            }
            if (_peer != null && now >= _nextWatchdogMs)
            {
                _nextWatchdogMs = now + 150;
                // Watchdog: while controlling, the cursor must stay at the anchor (we swallow every move).
                // If it wanders, Windows has dropped our hook (LowLevelHooksTimeout) → give control back, re-hook.
                if (NativeMethods.GetCursorPos(out var pt))
                {
                    bool off = Math.Abs(pt.x - _anchorX) > 40 || Math.Abs(pt.y - _anchorY) > 40;
                    _watchdogMisses = off ? _watchdogMisses + 1 : 0;
                    if (_watchdogMisses == 1) Post(() => { if (_remote) NativeMethods.SetCursorPos(_anchor.x, _anchor.y); });
                    if (_watchdogMisses >= 6)
                    {
                        _watchdogMisses = 0;
                        Debug.WriteLine("input watchdog: hook appears lost; returning and re-hooking");
                        Post(() => { DoReturn(null, sendLeave: true); Rehook(); });
                    }
                }
            }

            if (_peer == null && now >= _nextIdleCheckMs)
            {
                // Idle watchdog: Windows silently unhooks a hook that exceeds LowLevelHooksTimeout. If the cursor
                // moves while the mouse hook sees nothing, re-install (rate-limited; SetCursorPos by other apps
                // can cause a harmless false positive).
                _nextIdleCheckMs = now + 1000;
                int events = _hookEvents;
                if (NativeMethods.GetCursorPos(out var pos))
                {
                    bool moved = pos.x != _idleLastPos.x || pos.y != _idleLastPos.y;
                    _idleMisses = moved && events == _idleHookEvents ? _idleMisses + 1 : 0;
                    _idleLastPos = pos;
                    if (_idleMisses >= 2 && now - _lastRehookMs > 5000)
                    {
                        _idleMisses = 0;
                        _lastRehookMs = now;
                        Debug.WriteLine("input watchdog: mouse hook silent while the cursor moves; re-hooking");
                        Post(Rehook);
                    }
                }
                _idleHookEvents = events;
            }

            if (now >= _nextCapsPollMs || (_capsCheckAtMs >= 0 && now >= _capsCheckAtMs))
            {
                _nextCapsPollMs = now + 500;
                _capsCheckAtMs = -1;
                bool caps = (NativeMethods.GetKeyState(KeyMap.VK_CAPITAL) & 1) != 0;
                if (_lastCapsSent != caps)
                {
                    bool first = _lastCapsSent == null;
                    _lastCapsSent = caps;
                    if (!first) BroadcastKeyState(caps);
                }
            }

            if (now >= _nextMonitorPollMs)
            {
                _nextMonitorPollMs = now + 2000;
                var current = Monitors.Enumerate();
                var known = _map.Monitors;
                if (current.Count != known.Count || current.Where((m, i) => m.Bounds != known[i].Bounds).Any())
                    RebuildPortals();
            }
        }

        private void BroadcastKeyState(bool caps)
        {
            var targets = new HashSet<string>();
            if (_peer != null) targets.Add(_peer);
            foreach (var p in PeerBus.Peers())
                if (p.Caps.Contains("trackpad")) targets.Add(p.Id);
            foreach (var id in targets)
            {
                var m = Msg.Of("key_state");
                m["caps"] = caps;
                Send(id, m);
            }
        }

        private const int VK_MENU_MASK = 0xE8; // unassigned VK: breaks "Alt/Win tapped alone" (menu bar / Start)

        private static void SynthLocalUps(int[]? vks)
        {
            if (vks == null || vks.Length == 0) return;
            var inputs = new List<NativeMethods.INPUT>(vks.Length + 2);
            // Alt or Win released with nothing in between would activate the menu bar / open Start (e.g. after
            // crossing with "hold Alt to cross"): tap an unassigned key first, like AutoHotkey's menu mask key.
            if (vks.Any(v => v is KeyMap.VK_LMENU or KeyMap.VK_RMENU or KeyMap.VK_LWIN or KeyMap.VK_RWIN))
            {
                inputs.Add(KeyInput(VK_MENU_MASK, up: false));
                inputs.Add(KeyInput(VK_MENU_MASK, up: true));
            }
            foreach (int vk in vks) inputs.Add(KeyInput(vk, up: true));
            NativeMethods.SendInput((uint)inputs.Count, inputs.ToArray(), NativeMethods.INPUT.Size);
        }

        private static NativeMethods.INPUT KeyInput(int vk, bool up)
        {
            var i = new NativeMethods.INPUT { type = NativeMethods.INPUT_KEYBOARD };
            i.U.ki = new NativeMethods.KEYBDINPUT
            {
                wVk = (ushort)vk,
                wScan = (ushort)NativeMethods.MapVirtualKey((uint)vk, 0),
                dwFlags = (up ? NativeMethods.KEYEVENTF_KEYUP : 0) | (KeyMap.IsExtendedVk(vk) ? NativeMethods.KEYEVENTF_EXTENDEDKEY : 0),
            };
            return i;
        }

        private void SendKey(in Ev ev)
        {
            bool ext = (ev.Flags & KF_EXT) != 0, up = (ev.Flags & KF_UP) != 0, rep = (ev.Flags & KF_REP) != 0;
            if ((ev.Flags & KF_ALTGR) != 0) return; // AltGr's fake LCtrl only feeds the ToUnicode state
            uint vk = (uint)ev.I, scan = (uint)ev.J;
            int hid = KeyMap.ToHid(vk, scan, ext);
            string? text = (!up && !IsModifierVk(vk)) ? Translate(vk, scan, ev.Mods) : null;
            if (hid == 0 && text == null) return;

            var m = Msg.Of("key");
            m["hid"] = hid;
            m["down"] = !up;
            m["mods"] = WireMods(ev.Mods);
            if (rep) m["rep"] = true;
            if (!string.IsNullOrEmpty(text)) m["text"] = text;
            SendOrReturn(m);
        }

        private static bool IsModifierVk(uint vk) => vk is >= 0xA0 and <= 0xA5 || vk == KeyMap.VK_LWIN || vk == KeyMap.VK_RWIN
            || vk == KeyMap.VK_CAPITAL || vk == KeyMap.VK_NUMLOCK || vk == KeyMap.VK_SCROLL;

        private static int WireMods(int m)
        {
            int w = 0;
            if ((m & (M_LSHIFT | M_RSHIFT)) != 0) w |= 1;
            if ((m & (M_LCTRL | M_RCTRL)) != 0) w |= 2;
            if ((m & (M_LALT | M_RALT)) != 0) w |= 4;
            if ((m & (M_LWIN | M_RWIN)) != 0) w |= 8;
            if ((m & M_CAPS) != 0) w |= 16;
            return w;
        }

        /// <summary>
        /// Character(s) this key produces with the current layout of the foreground app. Uses ToUnicodeEx flag 0x4
        /// (don't touch kernel keyboard state). Dead keys are composed by replaying dead key + key without the flag,
        /// which sets and then consumes the dead-key state, leaving it as it was.
        /// </summary>
        private string? Translate(uint vk, uint scan, int mods)
        {
            bool ctrl = (mods & (M_LCTRL | M_RCTRL)) != 0;
            bool win = (mods & (M_LWIN | M_RWIN)) != 0;
            bool altGr = (mods & M_RALT) != 0 && (mods & M_ALTGRCTRL) != 0;
            bool alt = (mods & M_LALT) != 0 || ((mods & M_RALT) != 0 && !altGr);
            if (ctrl || win || alt) { _pendingDead = null; return null; }

            Array.Clear(_ks);
            if ((mods & (M_LSHIFT | M_RSHIFT)) != 0) _ks[KeyMap.VK_SHIFT] = 0x80;
            if ((mods & M_LSHIFT) != 0) _ks[KeyMap.VK_LSHIFT] = 0x80;
            if ((mods & M_RSHIFT) != 0) _ks[KeyMap.VK_RSHIFT] = 0x80;
            if (altGr)
            {
                _ks[KeyMap.VK_CONTROL] = 0x80; _ks[KeyMap.VK_LCONTROL] = 0x80;
                _ks[KeyMap.VK_MENU] = 0x80; _ks[KeyMap.VK_RMENU] = 0x80;
            }
            if ((mods & M_CAPS) != 0) _ks[KeyMap.VK_CAPITAL] = 0x01;

            IntPtr hkl;
            try { hkl = NativeMethods.GetKeyboardLayout(NativeMethods.GetWindowThreadProcessId(NativeMethods.GetForegroundWindow(), IntPtr.Zero)); }
            catch { hkl = IntPtr.Zero; }

            uint sc = scan & 0xFF;
            int n;
            if (_pendingDead is { } dead)
            {
                _pendingDead = null;
                NativeMethods.ToUnicodeEx(dead.vk, dead.scan, dead.state, _buf, _buf.Length, 0, hkl);
                n = NativeMethods.ToUnicodeEx(vk, sc, _ks, _buf, _buf.Length, 0, hkl);
                if (n < 0)
                {
                    // Second dead key: flush the dead state with a space and send nothing for this key.
                    var spaceState = new byte[256];
                    NativeMethods.ToUnicodeEx(KeyMap.VK_SPACE, 0x39, spaceState, _buf, _buf.Length, 0, hkl);
                    return null;
                }
            }
            else
            {
                n = NativeMethods.ToUnicodeEx(vk, sc, _ks, _buf, _buf.Length, 0x4, hkl);
                if (n < 0)
                {
                    _pendingDead = (vk, sc, (byte[])_ks.Clone());
                    return null;
                }
            }
            if (n <= 0) return null;
            var s = new string(_buf, 0, Math.Min(n, _buf.Length));
            foreach (char c in s) if (char.IsControl(c)) return null;
            return s;
        }
    }
}
