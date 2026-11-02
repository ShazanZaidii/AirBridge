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
    /// </summary>
    internal sealed class Controller
    {
        private const uint WM_APP_CMD = NativeMethods.WM_APP + 1;
        private const int ReturnInset = 8;         // px inside the edge where the local cursor resumes
        private const double MoveIntervalMs = 1000.0 / 120.0;

        // ---------- shared (volatile snapshots) ----------
        private sealed record Portal(string PeerId, Edge PcEdge, double SpanStart, double SpanEnd, EdgeSegment[] Segments);
        private sealed record PortalMap(Portal[] Portals, IReadOnlyList<MonitorInfo> Monitors);

        private volatile PortalMap _map = new(Array.Empty<Portal>(), Array.Empty<MonitorInfo>());
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
        private readonly bool[] _localHeld = new bool[512];
        private readonly bool[] _remoteHeld = new bool[512];
        private readonly bool[] _suppressUp = new bool[512];
        private readonly bool[] _localBtn = new bool[5];
        private readonly bool[] _remoteBtn = new bool[3];
        private readonly bool[] _suppressBtnUp = new bool[5];
        private bool _capsOn;
        private bool _physLCtrl, _physRCtrl, _physLAlt, _physRAlt;

        // ---------- sender thread ----------
        private enum K { Enter, Leave, Move, Btn, Scroll, Key, Action, CapsCheck, Peers }

        private readonly record struct Ev(K Kind, string? Peer = null, double A = 0, double B = 0, int I = 0, int J = 0,
            int Flags = 0, int Mods = 0, bool F = false, Edge Edge = Edge.Left, int[]? Vks = null, string? S = null, string[]? Ids = null);

        private const int KF_EXT = 1, KF_UP = 2, KF_REP = 4, KF_ALTGR = 8;
        private const int M_LSHIFT = 1, M_RSHIFT = 2, M_LCTRL = 4, M_RCTRL = 8, M_LALT = 16, M_RALT = 32,
            M_LWIN = 64, M_RWIN = 128, M_ALTGRCTRL = 256, M_CAPS = 512;

        private readonly BlockingCollection<Ev> _out = new(new ConcurrentQueue<Ev>());
        private Thread? _sender;
        private volatile bool _stopping;

        // sender-thread-only
        private string? _peer;
        private int _anchorX, _anchorY;
        private double _pdx, _pdy, _sdx, _sdy;
        private bool _pendingMove, _pendingScroll;
        private readonly Stopwatch _clock = Stopwatch.StartNew();
        private double _lastFlushMs = -1000;
        private int _watchdogMisses;
        private double _nextWatchdogMs;
        private double _nextCapsPollMs, _capsCheckAtMs = -1, _nextMonitorPollMs;
        private bool? _lastCapsSent;
        private HashSet<string> _knownTrackpads = new();
        private readonly byte[] _ks = new byte[256];
        private readonly char[] _buf = new char[16];
        private (uint vk, uint scan, byte[] state)? _pendingDead;

        public Controller()
        {
            _mouseProc = MouseHook;
            _kbProc = KeyboardHook;
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
        }

        // =====================================================================
        // Thread-safe entry points
        // =====================================================================

        /// <summary>Return the cursor to this PC (no-op if not controlling).</summary>
        public void RequestReturn(bool sendLeave) => Post(() => DoReturn(null, sendLeave));

        /// <summary>ctl_leave from the target we are controlling: resume at the matching point of our edge.</summary>
        public void OnCtlLeave(string peerId, JsonObject msg)
        {
            if (_activePeer != peerId) return;
            double? rel = Msg.Num(msg, "rel");
            Post(() =>
            {
                if (_remote && _portal?.PeerId == peerId) DoReturn(rel, sendLeave: false);
            });
        }

        public void OnPeersChanged(IReadOnlyList<PeerInfo> peers)
        {
            RebuildPortals();
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
                var portals = new List<Portal>();
                foreach (var peer in PeerDirectory.Current)
                {
                    if (!peer.Caps.Contains("ctl_target")) continue;
                    if (!placements.TryGetValue(peer.Id, out var pl) || EdgeExt.Parse(pl.Edge) is not Edge e) continue;
                    double len = e.IsVertical() ? union.Height : union.Width;
                    double start = (e.IsVertical() ? union.Top : union.Left) + pl.Offset * len;
                    portals.Add(new Portal(peer.Id, e, start, start + pl.Size * len, Monitors.OuterSegments(mons, e).ToArray()));
                }
                _map = new PortalMap(portals.ToArray(), mons);
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
            _hookReady.Set();

            while (NativeMethods.GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
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
            UninstallHooks();
            _hookThreadId = 0;
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
            try
            {
                uint flags = (uint)Marshal.ReadInt32(lParam, 12);
                if ((flags & NativeMethods.LLMHF_INJECTED) != 0)
                    return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);

                int msg = (int)wParam;
                int x = Marshal.ReadInt32(lParam, 0), y = Marshal.ReadInt32(lParam, 4);
                uint data = (uint)Marshal.ReadInt32(lParam, 8);
                return _remote ? RemoteMouse(msg, x, y, data) : LocalMouse(msg, x, y, data, nCode, wParam, lParam);
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
            }
            else if (msg == NativeMethods.WM_MOUSEMOVE && !AnyLocalButton() && TryEnter(x, y))
            {
                return (IntPtr)1;
            }
            return NativeMethods.CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
        }

        private static readonly int[] ButtonVks = { 0x01, 0x02, 0x04, 0x05, 0x06 };

        private bool AnyLocalButton()
        {
            bool any = false;
            foreach (bool b in _localBtn) any |= b;
            if (!any) return false;
            // An up may have been lost (secure desktop, UAC); don't block crossing forever on stale state.
            foreach (int vk in ButtonVks)
                if ((NativeMethods.GetAsyncKeyState(vk) & 0x8000) != 0) return true;
            Array.Clear(_localBtn);
            return false;
        }

        private bool TryEnter(int x, int y)
        {
            var map = _map;
            if (map.Portals.Length == 0) return false;
            foreach (var p in map.Portals)
            {
                foreach (var s in p.Segments)
                {
                    int dead = Math.Min(3, (s.To - s.From) / 4);
                    bool hit;
                    int along;
                    switch (p.PcEdge)
                    {
                        case Edge.Left: hit = x <= s.Fixed; along = y; break;
                        case Edge.Right: hit = x >= s.Fixed - 1; along = y; break;
                        case Edge.Top: hit = y <= s.Fixed; along = x; break;
                        default: hit = y >= s.Fixed - 1; along = x; break;
                    }
                    if (!hit || along < s.From + dead || along >= s.To - dead) continue;
                    if (along < p.SpanStart || along > p.SpanEnd) continue;
                    Enter(p, s, along);
                    return true;
                }
            }
            return false;
        }

        private void Enter(Portal p, EdgeSegment s, int along)
        {
            double rel = Math.Clamp((along - p.SpanStart) / Math.Max(1.0, p.SpanEnd - p.SpanStart), 0.0, 1.0);
            _entry = p.PcEdge switch
            {
                Edge.Left => new NativeMethods.POINT { x = s.Fixed, y = along },
                Edge.Right => new NativeMethods.POINT { x = s.Fixed - 1, y = along },
                Edge.Top => new NativeMethods.POINT { x = along, y = s.Fixed },
                _ => new NativeMethods.POINT { x = along, y = s.Fixed - 1 },
            };
            var m = s.Monitor;
            _anchor = new NativeMethods.POINT { x = m.Left + m.Width / 2, y = m.Top + m.Height / 2 };
            _remote = true;
            _portal = p;
            _activePeer = p.PeerId;
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
                Edge: p.PcEdge.Opposite(), Vks: synth.Distinct().ToArray()));
        }

        private IntPtr RemoteMouse(int msg, int x, int y, uint data)
        {
            switch (msg)
            {
                case NativeMethods.WM_MOUSEMOVE:
                    int dx = x - _anchor.x, dy = y - _anchor.y;
                    if (dx != 0 || dy != 0) Enqueue(new Ev(K.Move, A: dx, B: dy));
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

        /// <summary>Hook thread only. rel = position along the edge reported by the target (null = entry point).</summary>
        private void DoReturn(double? rel, bool sendLeave)
        {
            if (!_remote) return;
            var p = _portal!;
            _remote = false;
            _portal = null;
            _activePeer = null;

            for (int id = 0; id < 512; id++)
            {
                if (!_remoteHeld[id]) continue;
                _remoteHeld[id] = false;
                if ((id & 0xFF) != KeyMap.VK_CAPITAL) _suppressUp[id] = true;
            }
            for (int b = 0; b < 3; b++)
                if (_remoteBtn[b]) { _remoteBtn[b] = false; _suppressBtnUp[b] = true; }

            var (x, y) = ReturnPoint(p, rel);
            NativeMethods.SetCursorPos(x, y);
            Enqueue(new Ev(K.Leave, p.PeerId, F: sendLeave));
        }

        private (int x, int y) ReturnPoint(Portal p, double? rel)
        {
            int x, y;
            if (rel is double r && p.Segments.Length > 0)
            {
                double along = p.SpanStart + Math.Clamp(r, 0, 1) * (p.SpanEnd - p.SpanStart);
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
                int timeout = 100;
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
            CursorHider.Restore();
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
                    SynthLocalUps(ev.Vks);
                    CursorHider.Hide();
                    var enter = Msg.Of("ctl_enter");
                    enter["edge"] = ev.Edge.ToWire();
                    enter["rel"] = Math.Round(ev.A, 4);
                    if (!Send(_peer!, enter)) { RequestReturn(false); return; }
                    var ks = Msg.Of("key_state");
                    ks["caps"] = ev.F;
                    Send(_peer!, ks);
                    break;

                case K.Leave:
                    if (ev.F && ev.Peer != null) Send(ev.Peer, Msg.Of("ctl_leave"));
                    if (_peer == ev.Peer) _peer = null;
                    _pdx = _pdy = _sdx = _sdy = 0;
                    _pendingMove = _pendingScroll = false;
                    _pendingDead = null;
                    CursorHider.Restore();
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

        private static void SynthLocalUps(int[]? vks)
        {
            if (vks == null || vks.Length == 0) return;
            var inputs = new NativeMethods.INPUT[vks.Length];
            for (int i = 0; i < vks.Length; i++)
            {
                int vk = vks[i];
                inputs[i].type = NativeMethods.INPUT_KEYBOARD;
                inputs[i].U.ki = new NativeMethods.KEYBDINPUT
                {
                    wVk = (ushort)vk,
                    wScan = (ushort)NativeMethods.MapVirtualKey((uint)vk, 0),
                    dwFlags = NativeMethods.KEYEVENTF_KEYUP | (KeyMap.IsExtendedVk(vk) ? NativeMethods.KEYEVENTF_EXTENDEDKEY : 0),
                };
            }
            NativeMethods.SendInput((uint)inputs.Length, inputs, NativeMethods.INPUT.Size);
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
