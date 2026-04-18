using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading.Channels;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;

namespace AirBridge
{
    public enum DeviceEdge { Left, Right, Top, Bottom }

    public class InputInterceptor
    {
        private const int WH_MOUSE_LL = 14;
        private const int WH_KEYBOARD_LL = 13;
        private const int WM_MOUSEMOVE = 0x0200;
        private const int WM_LBUTTONDOWN = 0x0201;
        private const int WM_LBUTTONUP = 0x0202;
        private const int WM_RBUTTONDOWN = 0x0204;
        private const int WM_RBUTTONUP = 0x0205;
        private const int WM_MOUSEWHEEL = 0x020A;
        private const int WM_KEYDOWN = 0x0100;
        private const int WM_KEYUP = 0x0101;
        private const int WM_SYSKEYDOWN = 0x0104;
        private const int WM_SYSKEYUP = 0x0105;
        private const uint LLMHF_INJECTED = 0x00000001;

        // Virtual Key Codes
        private const int VK_LWIN = 0x5B;
        private const int VK_RWIN = 0x5C;
        private const int VK_ESCAPE = 0x1B;
        private const int VK_SPACE = 0x20;
        private const int VK_DELETE = 0x2E;
        private const int VK_LCONTROL = 0xA2;
        private const int VK_RCONTROL = 0xA3;
        private const int VK_LEFT = 0x25;
        private const int VK_UP = 0x26;
        private const int VK_RIGHT = 0x27;
        private const int VK_DOWN = 0x28;
        private const int VK_VOLUME_MUTE = 0xAD;
        private const int VK_VOLUME_DOWN = 0xAE;
        private const int VK_VOLUME_UP = 0xAF;
        private const int VK_MEDIA_NEXT_TRACK = 0xB0;
        private const int VK_MEDIA_PREV_TRACK = 0xB1;
        private const int VK_MEDIA_PLAY_PAUSE = 0xB3;
        private const int VK_TAB = 0x09;
        private const int VK_MENU = 0x12;
        private const int VK_LMENU = 0xA4;
        private const int VK_RMENU = 0xA5;

        private IntPtr _mouseHookID = IntPtr.Zero;
        private IntPtr _keyboardHookID = IntPtr.Zero;
        private NativeMethods.LowLevelMouseProc _mouseProc;
        private NativeMethods.LowLevelMouseProc _keyboardProc;

        private bool _isCursorOnRemote = false;
        private NativeMethods.POINT _frozenAnchor;
        private NativeMethods.POINT _portalEntry;
        private readonly Channel<string> _inputQueue;

        // State Tracking Modifiers
        private bool _isWinDown = false;
        private bool _winActionHandled = false;
        private bool _isAltDown = false;
        private bool _isTabDown = false;
        private DateTime _lastCtrlPress = DateTime.MinValue;

        public static DeviceEdge PhoneEdge { get; set; } = DeviceEdge.Left;
        public static double PhoneOffset { get; set; } = 0.25;
        public static double PhoneSize { get; set; } = 0.50;

        [StructLayout(LayoutKind.Sequential)]
        private struct KBDLLHOOKSTRUCT
        {
            public uint vkCode; public uint scanCode; public uint flags; public uint time; public IntPtr dwExtraInfo;
        }

        public InputInterceptor()
        {
            _mouseProc = MouseHookCallback;
            _keyboardProc = KeyboardHookCallback;
            _inputQueue = Channel.CreateUnbounded<string>();
        }

        public void Start()
        {
            NativeMethods.SetProcessDPIAware();
            _mouseHookID = SetHook(WH_MOUSE_LL, _mouseProc);
            _keyboardHookID = SetHook(WH_KEYBOARD_LL, _keyboardProc);
            _ = ProcessInputQueueAsync();
        }

        public void Stop()
        {
            if (_isCursorOnRemote) ForceReturnToWindows();
            NativeMethods.UnhookWindowsHookEx(_mouseHookID);
            NativeMethods.UnhookWindowsHookEx(_keyboardHookID);
        }

        private IntPtr SetHook(int idHook, NativeMethods.LowLevelMouseProc proc)
        {
            using (Process curProcess = Process.GetCurrentProcess())
            using (ProcessModule curModule = curProcess.MainModule)
            {
                return NativeMethods.SetWindowsHookEx(idHook, proc, NativeMethods.GetModuleHandle(curModule.ModuleName), 0);
            }
        }

        private async Task ProcessInputQueueAsync()
        {
            await foreach (var command in _inputQueue.Reader.ReadAllAsync())
            {
                if (App.Server != null && App.Server.HasActiveClient) await App.Server.SendAsync(command);
            }
        }

        public void ForceReturnToWindows()
        {
            if (!_isCursorOnRemote) return;
            _isCursorOnRemote = false;

            int safeX = _portalEntry.x, safeY = _portalEntry.y;
            switch (PhoneEdge)
            {
                case DeviceEdge.Left: safeX += 30; break;
                case DeviceEdge.Right: safeX -= 30; break;
                case DeviceEdge.Top: safeY += 30; break;
                case DeviceEdge.Bottom: safeY -= 30; break;
            }

            NativeMethods.SystemParametersInfo(NativeMethods.SPI_SETCURSORS, 0, IntPtr.Zero, 0);
            NativeMethods.SetCursorPos(safeX, safeY);
            System.Media.SystemSounds.Hand.Play();
        }

        private void HideCursorSystemWide()
        {
            byte[] andMask = new byte[128];
            byte[] xorMask = new byte[128];
            for (int i = 0; i < 128; i++) { andMask[i] = 0xFF; xorMask[i] = 0x00; }

            IntPtr blankCursor = NativeMethods.CreateCursor(IntPtr.Zero, 0, 0, 32, 32, andMask, xorMask);
            uint[] cursors = { NativeMethods.OCR_NORMAL, NativeMethods.OCR_IBEAM, NativeMethods.OCR_HAND, NativeMethods.OCR_WAIT, NativeMethods.OCR_APPSTARTING };

            foreach (uint cursor in cursors)
            {
                IntPtr copy = NativeMethods.CopyImage(blankCursor, NativeMethods.IMAGE_CURSOR, 0, 0, 0);
                NativeMethods.SetSystemCursor(copy, cursor);
            }
            NativeMethods.DestroyCursor(blankCursor);
        }

        private IntPtr MouseHookCallback(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0)
            {
                var hookStruct = (NativeMethods.MSLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(NativeMethods.MSLLHOOKSTRUCT));

                if ((hookStruct.flags & LLMHF_INJECTED) != 0) return NativeMethods.CallNextHookEx(_mouseHookID, nCode, wParam, lParam);

                int physWidth = NativeMethods.GetSystemMetrics(0);
                int physHeight = NativeMethods.GetSystemMetrics(1);

                if (!_isCursorOnRemote)
                {
                    double relativeY = (double)hookStruct.pt.y / physHeight;
                    double relativeX = (double)hookStruct.pt.x / physWidth;

                    bool hitEdge = false;
                    bool withinBounds = false;
                    double entryRatio = 0.5;

                    switch (PhoneEdge)
                    {
                        case DeviceEdge.Left:
                            hitEdge = hookStruct.pt.x <= 1;
                            withinBounds = relativeY >= PhoneOffset && relativeY <= (PhoneOffset + PhoneSize);
                            entryRatio = (relativeY - PhoneOffset) / PhoneSize;
                            break;
                        case DeviceEdge.Right:
                            hitEdge = hookStruct.pt.x >= physWidth - 2;
                            withinBounds = relativeY >= PhoneOffset && relativeY <= (PhoneOffset + PhoneSize);
                            entryRatio = (relativeY - PhoneOffset) / PhoneSize;
                            break;
                        case DeviceEdge.Top:
                            hitEdge = hookStruct.pt.y <= 1;
                            withinBounds = relativeX >= PhoneOffset && relativeX <= (PhoneOffset + PhoneSize);
                            entryRatio = (relativeX - PhoneOffset) / PhoneSize;
                            break;
                        case DeviceEdge.Bottom:
                            hitEdge = hookStruct.pt.y >= physHeight - 2;
                            withinBounds = relativeX >= PhoneOffset && relativeX <= (PhoneOffset + PhoneSize);
                            entryRatio = (relativeX - PhoneOffset) / PhoneSize;
                            break;
                    }

                    if (hitEdge && withinBounds && App.Server != null && App.Server.HasActiveClient)
                    {
                        _portalEntry = hookStruct.pt;

                        _frozenAnchor.x = physWidth / 2;
                        _frozenAnchor.y = physHeight / 2;
                        NativeMethods.SetCursorPos(_frozenAnchor.x, _frozenAnchor.y);

                        _isCursorOnRemote = true;
                        HideCursorSystemWide();

                        entryRatio = Math.Max(0.01, Math.Min(0.99, entryRatio));

                        string relX = (PhoneEdge == DeviceEdge.Top || PhoneEdge == DeviceEdge.Bottom) ? $"{entryRatio:F2}" : "0.5";
                        string relY = (PhoneEdge == DeviceEdge.Left || PhoneEdge == DeviceEdge.Right) ? $"{entryRatio:F2}" : "0.5";

                        _inputQueue.Writer.TryWrite($"INP:TRANSITION:PHONE:{PhoneEdge.ToString().ToLower()}:{relX}:{relY}");
                        System.Media.SystemSounds.Beep.Play();
                        return (IntPtr)1;
                    }
                }
                else
                {
                    int msg = (int)wParam;

                    if (msg == WM_MOUSEMOVE)
                    {
                        int dx = hookStruct.pt.x - _frozenAnchor.x;
                        int dy = hookStruct.pt.y - _frozenAnchor.y;

                        if (dx != 0 || dy != 0)
                        {
                            _inputQueue.Writer.TryWrite($"INP:M:{dx}:{dy}:0:0");
                        }
                    }
                    else if (msg == WM_LBUTTONDOWN) _inputQueue.Writer.TryWrite("INP:DRAG");
                    else if (msg == WM_LBUTTONUP) _inputQueue.Writer.TryWrite("INP:DRAG_RELEASE");
                    else if (msg == WM_RBUTTONDOWN) _inputQueue.Writer.TryWrite("INP:HOLD");
                    else if (msg == WM_MOUSEWHEEL)
                    {
                        int delta = (short)((hookStruct.mouseData >> 16) & 0xffff);
                        int scrollY = delta / -120; // Invert for natural Android scroll handling
                        _inputQueue.Writer.TryWrite($"INP:M:0:0:{scrollY}:0");
                    }

                    return (IntPtr)1;
                }
            }
            return NativeMethods.CallNextHookEx(_mouseHookID, nCode, wParam, lParam);
        }

        private IntPtr KeyboardHookCallback(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0 && _isCursorOnRemote)
            {
                var hookStruct = (KBDLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(KBDLLHOOKSTRUCT));
                int msg = (int)wParam;
                uint vk = hookStruct.vkCode;

                if (vk == VK_ESCAPE && Keyboard.IsKeyDown(Key.LeftCtrl) && Keyboard.IsKeyDown(Key.LeftShift))
                {
                    ForceReturnToWindows();
                    return (IntPtr)1;
                }

                if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN)
                {
                    if (vk == VK_LWIN || vk == VK_RWIN)
                    {
                        _isWinDown = true;
                        _winActionHandled = false;
                    }
                    else if (vk == VK_MENU || vk == VK_LMENU || vk == VK_RMENU)
                    {
                        _isAltDown = true;
                    }
                    else if (vk == VK_SPACE && _isWinDown)
                    {
                        _winActionHandled = true;
                        _inputQueue.Writer.TryWrite("INP:SYS:DRAWER");
                    }
                    else if (vk == VK_TAB && _isAltDown)
                    {
                        if (!_isTabDown)
                        {
                            _isTabDown = true;
                            _inputQueue.Writer.TryWrite("INP:SYS:LAST_APP");
                        }
                    }
                    else if (vk == VK_ESCAPE) _inputQueue.Writer.TryWrite("INP:SYS:BACK");
                    else if (vk == VK_DELETE) _inputQueue.Writer.TryWrite("INP:SYS:RECENTS");
                    else if (vk == VK_LCONTROL || vk == VK_RCONTROL)
                    {
                        var now = DateTime.Now;
                        if ((now - _lastCtrlPress).TotalMilliseconds < 400)
                            _inputQueue.Writer.TryWrite("INP:SYS:QUICK_SETTINGS");
                        else
                            _inputQueue.Writer.TryWrite("INP:SYS:NOTIF");

                        _lastCtrlPress = now;
                    }
                    else if (vk == VK_UP) _inputQueue.Writer.TryWrite("INP:SWIPE:DOWN");
                    else if (vk == VK_DOWN) _inputQueue.Writer.TryWrite("INP:SWIPE:UP");
                    else if (vk == VK_LEFT) _inputQueue.Writer.TryWrite("INP:SWIPE:RIGHT");
                    else if (vk == VK_RIGHT) _inputQueue.Writer.TryWrite("INP:SWIPE:LEFT");
                    else if (vk == VK_VOLUME_UP) _inputQueue.Writer.TryWrite("INP:VOL:UP");
                    else if (vk == VK_VOLUME_DOWN) _inputQueue.Writer.TryWrite("INP:VOL:DOWN");
                    else if (vk == VK_VOLUME_MUTE) _inputQueue.Writer.TryWrite("INP:VOL:MUTE");
                    else if (vk == VK_MEDIA_PLAY_PAUSE) _inputQueue.Writer.TryWrite("INP:MEDIA:PLAY_PAUSE");
                    else if (vk == VK_MEDIA_NEXT_TRACK) _inputQueue.Writer.TryWrite("INP:MEDIA:NEXT");
                    else if (vk == VK_MEDIA_PREV_TRACK) _inputQueue.Writer.TryWrite("INP:MEDIA:PREV");
                    else
                    {
                        if (vk == VK_TAB) _isTabDown = true;
                        _inputQueue.Writer.TryWrite($"INP:KD:{vk}");
                    }
                }
                else if (msg == WM_KEYUP || msg == WM_SYSKEYUP)
                {
                    if (vk == VK_LWIN || vk == VK_RWIN)
                    {
                        _isWinDown = false;
                        if (!_winActionHandled) _inputQueue.Writer.TryWrite("INP:SYS:HOME");
                    }
                    else if (vk == VK_MENU || vk == VK_LMENU || vk == VK_RMENU)
                    {
                        _isAltDown = false;
                    }
                    else
                    {
                        if (vk == VK_TAB) _isTabDown = false;
                        _inputQueue.Writer.TryWrite($"INP:KU:{vk}");
                    }
                }

                return (IntPtr)1;
            }
            return NativeMethods.CallNextHookEx(_keyboardHookID, nCode, wParam, lParam);
        }
    }
}