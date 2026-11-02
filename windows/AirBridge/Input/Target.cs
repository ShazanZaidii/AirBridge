using System.IO;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace AirBridge.Input
{
    /// <summary>
    /// Remote → PC (§8.1–8.5): injects ptr_*/key/action from any ready peer with SendInput on a dedicated thread,
    /// handles ctl_enter/ctl_leave, sends ctl_leave when the cursor is pushed back out through the entry edge, and
    /// releases everything a peer holds on ctl_leave / peer loss / takeover by another controller.
    ///
    /// Pointer gain: ptr_move deltas are treated as logical (96-dpi) pixels: injected px = d × monitorScale × TargetPointerGain.
    /// rel: fraction along the bounding box of all monitors (top→bottom for left/right, left→right for top/bottom).
    /// </summary>
    internal sealed class Target
    {
        private const int EntryInset = 2;
        private const int SM_SWAPBUTTON = 23;

        private readonly BlockingCollection<Action> _q = new(new ConcurrentQueue<Action>());
        private Thread? _thread;
        private readonly Action _onEnter;

        // target-thread state
        private string? _controller;
        private Edge _entryEdge;
        private double _cx, _cy;
        private bool _havePos;
        private double _wheelRemX, _wheelRemY;
        private List<MonitorInfo> _mons = new();
        private IRect _union;
        private Dictionary<Edge, List<EdgeSegment>> _outer = new();
        private readonly Stopwatch _clock = Stopwatch.StartNew();
        private double _nextMonRefreshMs;
        private volatile LayoutData _settings = Layout.Settings();

        private sealed class Held
        {
            public readonly HashSet<int> Keys = new();
            public readonly bool[] Buttons = new bool[3];
        }
        private readonly Dictionary<string, Held> _held = new();

        /// <param name="onEnter">Called (on the target thread) when a controller takes over this PC.</param>
        public Target(Action onEnter) => _onEnter = onEnter;

        public void Start()
        {
            if (_thread != null) return;
            Layout.Changed += OnLayoutChanged;
            _thread = new Thread(Run) { IsBackground = true, Name = "AirBridge input target", Priority = ThreadPriority.AboveNormal };
            _thread.Start();
        }

        public void Stop()
        {
            Layout.Changed -= OnLayoutChanged;
            if (_thread == null) return;
            Post(ReleaseAllPeers);
            _q.CompleteAdding();
            _thread.Join(2000);
            _thread = null;
        }

        private void OnLayoutChanged() => _settings = Layout.Settings();

        public void Handle(string peerId, JsonObject msg) => Post(() => Dispatch(peerId, msg));

        public void OnPeersChanged(IReadOnlyList<PeerInfo> peers)
        {
            var alive = new HashSet<string>(peers.Select(p => p.Id));
            Post(() =>
            {
                foreach (var id in _held.Keys.Where(k => !alive.Contains(k)).ToList()) Release(id);
                if (_controller != null && !alive.Contains(_controller)) _controller = null;
            });
        }

        private void Post(Action a)
        {
            if (_q.IsAddingCompleted) return;
            try { _q.TryAdd(a); } catch (InvalidOperationException) { }
        }

        private void Run()
        {
            DpiBootstrap.EnterThread();
            foreach (var a in _q.GetConsumingEnumerable())
            {
                try { a(); } catch (Exception ex) { Debug.WriteLine($"input target: {ex}"); }
            }
        }

        private void Dispatch(string peer, JsonObject m)
        {
            switch (Msg.Str(m, "t"))
            {
                case "ctl_enter": Enter(peer, m); break;
                case "ctl_leave":
                    if (_controller == peer) _controller = null;
                    Release(peer);
                    break;
                case "ptr_move": Move(peer, Msg.Num(m, "dx") ?? 0, Msg.Num(m, "dy") ?? 0); break;
                case "ptr_scroll": Scroll(Msg.Num(m, "dx") ?? 0, Msg.Num(m, "dy") ?? 0, Msg.Bool(m, "px") ?? false); break;
                case "ptr_btn":
                    if (Msg.Num(m, "b") is double b && Msg.Bool(m, "down") is bool down) Button(peer, (int)b, down);
                    break;
                case "ptr_click":
                    Click((int)(Msg.Num(m, "b") ?? 0), (int)(Msg.Num(m, "n") ?? 1));
                    break;
                case "key": Key(peer, m); break;
                case "action": DoAction(Msg.Str(m, "name")); break;
                case "key_state": break; // nothing to show on Windows
            }
        }

        // ---------------------------------------------------------------- geometry

        private void RefreshMonitors(bool force)
        {
            double now = _clock.Elapsed.TotalMilliseconds;
            if (!force && now < _nextMonRefreshMs && _mons.Count > 0) return;
            _nextMonRefreshMs = now + 2000;
            _mons = Monitors.Enumerate();
            _union = Monitors.Union(_mons);
            _outer = new Dictionary<Edge, List<EdgeSegment>>();
            foreach (Edge e in Enum.GetValues<Edge>()) _outer[e] = Monitors.OuterSegments(_mons, e);
        }

        private void SyncPos()
        {
            if (!NativeMethods.GetCursorPos(out var p)) return;
            if (!_havePos || Math.Abs(p.x - Math.Floor(_cx)) > 1 || Math.Abs(p.y - Math.Floor(_cy)) > 1)
            {
                _cx = p.x + 0.5; _cy = p.y + 0.5; _havePos = true;
            }
        }

        private void Enter(string peer, JsonObject m)
        {
            _onEnter(); // if this PC is itself controlling something, take the cursor back first
            if (_controller != null && _controller != peer)
            {
                Send(_controller, Msg.Of("ctl_leave"));
                Release(_controller);
            }
            _controller = peer;
            _entryEdge = EdgeExt.Parse(Msg.Str(m, "edge")) ?? Edge.Left;
            double rel = Math.Clamp(Msg.Num(m, "rel") ?? 0.5, 0, 1);
            RefreshMonitors(force: true);

            bool vertical = _entryEdge.IsVertical();
            double along = vertical ? _union.Top + rel * (_union.Height - 1) : _union.Left + rel * (_union.Width - 1);
            var segs = _outer[_entryEdge];
            EdgeSegment? best = null;
            double bestScore = double.MaxValue;
            foreach (var s in segs)
            {
                double dist = along < s.From ? s.From - along : along >= s.To ? along - (s.To - 1) : 0;
                // Prefer the outermost segment covering the point.
                double outward = _entryEdge switch
                {
                    Edge.Left => s.Fixed - _union.Left,
                    Edge.Right => _union.Right - s.Fixed,
                    Edge.Top => s.Fixed - _union.Top,
                    _ => _union.Bottom - s.Fixed,
                };
                double score = dist * 100000 + outward;
                if (score < bestScore) { bestScore = score; best = s; }
            }
            double x, y;
            if (best is EdgeSegment b)
            {
                double a = Math.Clamp(along, b.From, b.To - 1);
                (x, y) = _entryEdge switch
                {
                    Edge.Left => ((double)(b.Fixed + EntryInset), a),
                    Edge.Right => ((double)(b.Fixed - 1 - EntryInset), a),
                    Edge.Top => (a, (double)(b.Fixed + EntryInset)),
                    _ => (a, (double)(b.Fixed - 1 - EntryInset)),
                };
            }
            else { x = _union.Left + _union.Width / 2.0; y = _union.Top + _union.Height / 2.0; }

            var c = Monitors.ClampToNearest(_mons, x, y);
            _cx = c.x + 0.5; _cy = c.y + 0.5; _havePos = true;
            MoveCursorTo(c.x, c.y);
        }

        private void Move(string peer, double dx, double dy)
        {
            RefreshMonitors(force: false);
            if (_mons.Count == 0) return;
            SyncPos();
            var cur = Monitors.At(_mons, _cx, _cy) ?? Monitors.ClampToNearest(_mons, _cx, _cy).mon;
            double gain = cur.Scale * Math.Clamp(_settings.TargetPointerGain, 0.1, 10);
            double nx = _cx + dx * gain, ny = _cy + dy * gain;

            if (Monitors.At(_mons, nx, ny) == null)
            {
                if (peer == _controller && LeavesThroughEntryEdge(cur, nx, ny, out double rel))
                {
                    var leave = Msg.Of("ctl_leave");
                    leave["edge"] = _entryEdge.ToWire();
                    leave["rel"] = Math.Round(rel, 4);
                    Send(peer, leave);
                    _controller = null;
                    Release(peer);
                    return;
                }
                var c = Monitors.ClampToNearest(_mons, nx, ny);
                // keep the sub-pixel part along the free axis so slow slides along an edge still progress
                nx = Math.Clamp(nx, c.mon.Bounds.Left, c.mon.Bounds.Right - 0.001);
                ny = Math.Clamp(ny, c.mon.Bounds.Top, c.mon.Bounds.Bottom - 0.001);
            }
            _cx = nx; _cy = ny;
            MoveCursorTo((int)Math.Floor(_cx), (int)Math.Floor(_cy));
        }

        private bool LeavesThroughEntryEdge(MonitorInfo cur, double nx, double ny, out double rel)
        {
            rel = 0;
            var b = cur.Bounds;
            bool beyond = _entryEdge switch
            {
                Edge.Left => nx < b.Left,
                Edge.Right => nx >= b.Right,
                Edge.Top => ny < b.Top,
                _ => ny >= b.Bottom,
            };
            if (!beyond) return false;
            bool vertical = _entryEdge.IsVertical();
            int along = (int)Math.Floor(vertical ? Math.Clamp(ny, b.Top, b.Bottom - 1) : Math.Clamp(nx, b.Left, b.Right - 1));
            int fixedCoord = _entryEdge switch { Edge.Left => b.Left, Edge.Right => b.Right, Edge.Top => b.Top, _ => b.Bottom };
            foreach (var s in _outer[_entryEdge])
            {
                if (s.Fixed != fixedCoord || along < s.From || along >= s.To || s.Monitor != b) continue;
                rel = vertical
                    ? (along - _union.Top) / Math.Max(1.0, _union.Height - 1)
                    : (along - _union.Left) / Math.Max(1.0, _union.Width - 1);
                rel = Math.Clamp(rel, 0, 1);
                return true;
            }
            return false;
        }

        private void MoveCursorTo(int x, int y)
        {
            var u = _union;
            if (u.Width > 1 && u.Height > 1)
            {
                // Normalised virtual-desk coordinates; ceil() so Windows' floor(n*W/65536) lands on the intended pixel.
                int nx = (int)Math.Clamp(Math.Ceiling((x - u.Left) * 65536.0 / u.Width), 0, 65535);
                int ny = (int)Math.Clamp(Math.Ceiling((y - u.Top) * 65536.0 / u.Height), 0, 65535);
                var inp = new NativeMethods.INPUT[1];
                inp[0].type = NativeMethods.INPUT_MOUSE;
                inp[0].U.mi = new NativeMethods.MOUSEINPUT
                {
                    dx = nx, dy = ny,
                    dwFlags = NativeMethods.MOUSEEVENTF_MOVE | NativeMethods.MOUSEEVENTF_ABSOLUTE | NativeMethods.MOUSEEVENTF_VIRTUALDESK,
                };
                if (NativeMethods.SendInput(1, inp, NativeMethods.INPUT.Size) == 1) return;
            }
            NativeMethods.SetCursorPos(x, y); // UIPI-blocked or degenerate desktop
        }

        // ---------------------------------------------------------------- pointer buttons / wheel

        private Held HeldFor(string peer)
        {
            if (!_held.TryGetValue(peer, out var h)) _held[peer] = h = new Held();
            return h;
        }

        private static uint ButtonFlag(int b, bool down)
        {
            if (NativeMethods.GetSystemMetrics(SM_SWAPBUTTON) != 0 && b < 2) b = 1 - b; // b is "primary/secondary"
            return b switch
            {
                0 => down ? NativeMethods.MOUSEEVENTF_LEFTDOWN : NativeMethods.MOUSEEVENTF_LEFTUP,
                1 => down ? NativeMethods.MOUSEEVENTF_RIGHTDOWN : NativeMethods.MOUSEEVENTF_RIGHTUP,
                _ => down ? NativeMethods.MOUSEEVENTF_MIDDLEDOWN : NativeMethods.MOUSEEVENTF_MIDDLEUP,
            };
        }

        private static NativeMethods.INPUT MouseInput(uint flags, int data = 0)
        {
            var i = new NativeMethods.INPUT { type = NativeMethods.INPUT_MOUSE };
            i.U.mi = new NativeMethods.MOUSEINPUT { dwFlags = flags, mouseData = unchecked((uint)data) };
            return i;
        }

        private void Button(string peer, int b, bool down)
        {
            if (b < 0 || b > 2) return;
            var h = HeldFor(peer);
            if (h.Buttons[b] == down) return;
            h.Buttons[b] = down;
            NativeMethods.SendInput(1, new[] { MouseInput(ButtonFlag(b, down)) }, NativeMethods.INPUT.Size);
        }

        private static void Click(int b, int n)
        {
            if (b < 0 || b > 2) return;
            // `n` is the click count of this click (a double tap arrives as n:1 then n:2); inject exactly one
            // down/up and let Windows derive double-clicks from timing, never synthesise extra clicks.
            _ = n;
            var inputs = new[] { MouseInput(ButtonFlag(b, true)), MouseInput(ButtonFlag(b, false)) };
            NativeMethods.SendInput((uint)inputs.Length, inputs, NativeMethods.INPUT.Size);
        }

        private void Scroll(double dx, double dy, bool px)
        {
            double perNotch = px ? Math.Max(1, _settings.TargetScrollPixelsPerNotch) : 1.0;
            // Protocol: +dy = content moves up (= Windows negative wheel); +dx = scroll right (= positive HWHEEL).
            _wheelRemY += -dy * 120.0 / perNotch;
            _wheelRemX += dx * 120.0 / perNotch;
            int iy = (int)Math.Truncate(_wheelRemY), ix = (int)Math.Truncate(_wheelRemX);
            _wheelRemY -= iy; _wheelRemX -= ix;
            var inputs = new List<NativeMethods.INPUT>(2);
            if (iy != 0) inputs.Add(MouseInput(NativeMethods.MOUSEEVENTF_WHEEL, iy));
            if (ix != 0) inputs.Add(MouseInput(NativeMethods.MOUSEEVENTF_HWHEEL, ix));
            if (inputs.Count > 0) NativeMethods.SendInput((uint)inputs.Count, inputs.ToArray(), NativeMethods.INPUT.Size);
        }

        // ---------------------------------------------------------------- keys

        private static readonly int[][] ModGroups =
        {
            new[] { KeyMap.HID_LSHIFT, KeyMap.HID_RSHIFT }, // bit 1
            new[] { KeyMap.HID_LCTRL, KeyMap.HID_RCTRL },   // bit 2
            new[] { KeyMap.HID_LALT, KeyMap.HID_RALT },     // bit 4
            new[] { KeyMap.HID_LGUI, KeyMap.HID_RGUI },     // bit 8
        };

        private bool SwapForPeer(string peer) =>
            _settings.SwapCmdCtrlForMac && PeerDirectory.Find(peer)?.Platform == "macos";

        private static int SwapHid(int hid) => hid switch
        {
            KeyMap.HID_LGUI => KeyMap.HID_LCTRL,
            KeyMap.HID_LCTRL => KeyMap.HID_LGUI,
            KeyMap.HID_RGUI => KeyMap.HID_RCTRL,
            KeyMap.HID_RCTRL => KeyMap.HID_RGUI,
            _ => hid,
        };

        private void Key(string peer, JsonObject m)
        {
            int hid = (int)(Msg.Num(m, "hid") ?? 0);
            bool down = Msg.Bool(m, "down") ?? true;
            string? text = Msg.Str(m, "text");
            int? mods = Msg.Num(m, "mods") is double md ? (int)md : null;
            bool swap = SwapForPeer(peer);
            if (swap)
            {
                hid = SwapHid(hid);
                if (mods is int mv) mods = (mv & ~(2 | 8)) | ((mv & 2) != 0 ? 8 : 0) | ((mv & 8) != 0 ? 2 : 0);
            }

            var h = HeldFor(peer);
            var map = KeyMap.FromHid(hid);
            bool known = map.scan != 0 || map.vk != 0;
            if (down)
            {
                if (known)
                {
                    InjectKey(hid, up: false);
                    h.Keys.Add(hid);
                }
                else if (!string.IsNullOrEmpty(text))
                {
                    InjectText(text);
                }
            }
            else if (h.Keys.Remove(hid))
            {
                InjectKey(hid, up: true);
            }

            // Self-heal lost modifier ups: release (never press) modifiers the controller says are not held.
            if (mods is int mm)
            {
                for (int g = 0; g < ModGroups.Length; g++)
                {
                    if ((mm & (1 << g)) != 0) continue;
                    foreach (int mh in ModGroups[g])
                        if (mh != hid && h.Keys.Remove(mh)) InjectKey(mh, up: true);
                }
            }
        }

        private static void InjectKey(int hid, bool up)
        {
            var (scan, ext, vk) = KeyMap.FromHid(hid);
            var i = new NativeMethods.INPUT { type = NativeMethods.INPUT_KEYBOARD };
            uint flags = (ext ? NativeMethods.KEYEVENTF_EXTENDEDKEY : 0) | (up ? NativeMethods.KEYEVENTF_KEYUP : 0);
            if (vk != 0)
                i.U.ki = new NativeMethods.KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags };
            else if (scan != 0)
                i.U.ki = new NativeMethods.KEYBDINPUT { wScan = scan, dwFlags = flags | NativeMethods.KEYEVENTF_SCANCODE };
            else return;
            NativeMethods.SendInput(1, new[] { i }, NativeMethods.INPUT.Size);
        }

        private static void InjectText(string text)
        {
            var inputs = new List<NativeMethods.INPUT>(text.Length * 2);
            foreach (char c in text)
            {
                var d = new NativeMethods.INPUT { type = NativeMethods.INPUT_KEYBOARD };
                d.U.ki = new NativeMethods.KEYBDINPUT { wScan = c, dwFlags = NativeMethods.KEYEVENTF_UNICODE };
                var u = d;
                u.U.ki.dwFlags |= NativeMethods.KEYEVENTF_KEYUP;
                inputs.Add(d); inputs.Add(u);
            }
            if (inputs.Count > 0) NativeMethods.SendInput((uint)inputs.Count, inputs.ToArray(), NativeMethods.INPUT.Size);
        }

        private void Release(string peer)
        {
            if (!_held.Remove(peer, out var h)) return;
            for (int b = 0; b < 3; b++)
                if (h.Buttons[b]) NativeMethods.SendInput(1, new[] { MouseInput(ButtonFlag(b, false)) }, NativeMethods.INPUT.Size);
            // Non-modifiers first, then modifiers, so nothing types a shifted character on the way out.
            foreach (int hid in h.Keys.OrderBy(k => KeyMap.IsModifierHid(k) ? 1 : 0)) InjectKey(hid, up: true);
        }

        private void ReleaseAllPeers()
        {
            foreach (var id in _held.Keys.ToList()) Release(id);
            _controller = null;
        }

        // ---------------------------------------------------------------- actions

        private static void Combo(params int[] vks)
        {
            var inputs = new List<NativeMethods.INPUT>(vks.Length * 2);
            foreach (int vk in vks) inputs.Add(VkInput(vk, false));
            for (int i = vks.Length - 1; i >= 0; i--) inputs.Add(VkInput(vks[i], true));
            NativeMethods.SendInput((uint)inputs.Count, inputs.ToArray(), NativeMethods.INPUT.Size);
        }

        private static NativeMethods.INPUT VkInput(int vk, bool up)
        {
            var i = new NativeMethods.INPUT { type = NativeMethods.INPUT_KEYBOARD };
            i.U.ki = new NativeMethods.KEYBDINPUT
            {
                wVk = (ushort)vk,
                wScan = (ushort)NativeMethods.MapVirtualKey((uint)vk, 0),
                dwFlags = (KeyMap.IsExtendedVk(vk) ? NativeMethods.KEYEVENTF_EXTENDEDKEY : 0) | (up ? NativeMethods.KEYEVENTF_KEYUP : 0),
            };
            return i;
        }

        private static void DoAction(string? name)
        {
            switch (name)
            {
                case "lock": NativeMethods.LockWorkStation(); break;
                case "spotlight": Combo(KeyMap.VK_LWIN, KeyMap.VK_S); break;
                case "mission_control":
                case "recents": Combo(KeyMap.VK_LWIN, KeyMap.VK_TAB); break;
                case "app_expose": Combo(KeyMap.VK_LCONTROL, KeyMap.VK_LMENU, KeyMap.VK_TAB); break; // sticky Alt+Tab switcher
                case "last_app": Combo(KeyMap.VK_LMENU, KeyMap.VK_TAB); break;
                case "desktop_left": Combo(KeyMap.VK_LCONTROL, KeyMap.VK_LWIN, KeyMap.VK_LEFT); break;
                case "desktop_right": Combo(KeyMap.VK_LCONTROL, KeyMap.VK_LWIN, KeyMap.VK_RIGHT); break;
                case "home": Combo(KeyMap.VK_LWIN, KeyMap.VK_D); break;
                case "app_drawer": Combo(KeyMap.VK_LWIN); break;
                case "notifications": Combo(KeyMap.VK_LWIN, KeyMap.VK_N); break;
                case "quick_settings": Combo(KeyMap.VK_LWIN, KeyMap.VK_A); break;
                case "back": Combo(KeyMap.VK_BROWSER_BACK); break;
                case "vol_up": Combo(KeyMap.VK_VOLUME_UP); break;
                case "vol_down": Combo(KeyMap.VK_VOLUME_DOWN); break;
                case "vol_mute": Combo(KeyMap.VK_VOLUME_MUTE); break;
                case "media_play_pause": Combo(KeyMap.VK_MEDIA_PLAY_PAUSE); break;
                case "media_next": Combo(KeyMap.VK_MEDIA_NEXT_TRACK); break;
                case "media_prev": Combo(KeyMap.VK_MEDIA_PREV_TRACK); break;
                case "copy": Combo(KeyMap.VK_LCONTROL, KeyMap.VK_C); break;
                case "paste": Combo(KeyMap.VK_LCONTROL, KeyMap.VK_V); break;
                case "keyboard_toggle": Task.Run(TouchKeyboard.Toggle); break;
                default:
                    // brightness_*, assistant, dnd_toggle, swipe_*: no reliable Windows equivalent → ignored (§8.5).
                    Debug.WriteLine($"action ignored: {name}");
                    break;
            }
        }

        private static void Send(string peer, JsonObject msg)
        {
            try { PeerBus.Send(peer, msg); } catch (Exception ex) { Debug.WriteLine($"PeerBus.Send: {ex.Message}"); }
        }
    }

    /// <summary>Shows/hides the touch keyboard (TabTip) via its COM toggle; falls back to osk.exe.</summary>
    internal static class TouchKeyboard
    {
        [ComImport, Guid("37c994e7-432b-4834-a2f7-dce1f13b834b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface ITipInvocation
        {
            void Toggle(IntPtr hwnd);
        }

        private static readonly Guid UIHostNoLaunch = new("4ce576fa-83dc-4f88-951c-9d0782b4e376");

        [DllImport("user32.dll")]
        private static extern IntPtr GetDesktopWindow();

        private static bool TryToggle()
        {
            try
            {
                var type = Type.GetTypeFromCLSID(UIHostNoLaunch);
                if (type == null) return false;
                object? o = Activator.CreateInstance(type);
                if (o is not ITipInvocation tip) return false;
                tip.Toggle(GetDesktopWindow());
                Marshal.ReleaseComObject(o);
                return true;
            }
            catch { return false; }
        }

        public static void Toggle()
        {
            if (TryToggle()) return;
            try
            {
                string tabTip = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonProgramFiles),
                    "microsoft shared", "ink", "TabTip.exe");
                if (File.Exists(tabTip))
                {
                    Process.Start(new ProcessStartInfo(tabTip) { UseShellExecute = true });
                    Thread.Sleep(600);
                    if (TryToggle()) return;
                }
            }
            catch { }
            try { Process.Start(new ProcessStartInfo("osk.exe") { UseShellExecute = true }); }
            catch (Exception ex) { Debug.WriteLine($"keyboard_toggle failed: {ex.Message}"); }
        }
    }
}
