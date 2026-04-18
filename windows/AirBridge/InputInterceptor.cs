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
        private const uint LLMHF_INJECTED = 0x00000001;

        private IntPtr _mouseHookID = IntPtr.Zero;
        private IntPtr _keyboardHookID = IntPtr.Zero;
        private NativeMethods.LowLevelMouseProc _mouseProc;
        private NativeMethods.LowLevelMouseProc _keyboardProc;

        private bool _isCursorOnRemote = false;
        private NativeMethods.POINT _frozenAnchor;
        private readonly Channel<string> _inputQueue;

        // Display Arrangement Properties
        public static DeviceEdge PhoneEdge { get; set; } = DeviceEdge.Left;
        public static double PhoneOffset { get; set; } = 0.25; // Start 25% down the screen
        public static double PhoneSize { get; set; } = 0.50;   // Take up 50% of the screen edge

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

        private IntPtr MouseHookCallback(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0)
            {
                var hookStruct = (NativeMethods.MSLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(NativeMethods.MSLLHOOKSTRUCT));

                if ((hookStruct.flags & LLMHF_INJECTED) != 0) return NativeMethods.CallNextHookEx(_mouseHookID, nCode, wParam, lParam);

                if (!_isCursorOnRemote)
                {
                    int physWidth = NativeMethods.GetSystemMetrics(0);
                    int physHeight = NativeMethods.GetSystemMetrics(1);

                    double relativeY = (double)hookStruct.pt.y / physHeight;
                    double relativeX = (double)hookStruct.pt.x / physWidth;

                    bool hitEdge = false;
                    bool withinBounds = false;
                    double entryRatio = 0.5; // Where on the phone's screen the cursor should appear

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
                        _isCursorOnRemote = true;
                        _frozenAnchor = hookStruct.pt;
                        entryRatio = Math.Max(0.01, Math.Min(0.99, entryRatio)); // Clamp safely

                        // Send precise entry ratio so Android knows exactly where to spawn the cursor
                        _ = App.Server?.SendAsync($"INP:TRANSITION:PHONE:{PhoneEdge.ToString().ToLower()}:{entryRatio:F2}:{entryRatio:F2}");
                        System.Media.SystemSounds.Beep.Play();
                        return (IntPtr)1;
                    }
                }
                else
                {
                    int msg = (int)wParam;
                    int dx = hookStruct.pt.x - _frozenAnchor.x;
                    int dy = hookStruct.pt.y - _frozenAnchor.y;

                    bool escapeTriggered = false;
                    int escapeVelocity = 80;

                    switch (PhoneEdge)
                    {
                        case DeviceEdge.Left: escapeTriggered = dx > escapeVelocity; break;
                        case DeviceEdge.Right: escapeTriggered = dx < -escapeVelocity; break;
                        case DeviceEdge.Top: escapeTriggered = dy > escapeVelocity; break;
                        case DeviceEdge.Bottom: escapeTriggered = dy < -escapeVelocity; break;
                    }

                    if (escapeTriggered)
                    {
                        _isCursorOnRemote = false;
                        _ = App.Server?.SendAsync("INP:TRANSITION:MAC");

                        int safeX = _frozenAnchor.x, safeY = _frozenAnchor.y;
                        switch (PhoneEdge)
                        {
                            case DeviceEdge.Left: safeX += 30; break;
                            case DeviceEdge.Right: safeX -= 30; break;
                            case DeviceEdge.Top: safeY += 30; break;
                            case DeviceEdge.Bottom: safeY -= 30; break;
                        }

                        NativeMethods.SetCursorPos(safeX, safeY);
                        System.Media.SystemSounds.Hand.Play();
                        return NativeMethods.CallNextHookEx(_mouseHookID, nCode, wParam, lParam);
                    }

                    if (msg == WM_MOUSEMOVE)
                    {
                        if (dx != 0 || dy != 0)
                        {
                            // RESTORED THE NATIVE ANDROID PROTOCOL
                            _inputQueue.Writer.TryWrite($"INP:M:{dx}:{dy}:0:0");
                            NativeMethods.SetCursorPos(_frozenAnchor.x, _frozenAnchor.y);
                        }
                    }
                    else if (msg == WM_LBUTTONDOWN) _inputQueue.Writer.TryWrite("INP:CLK:L:DOWN");
                    else if (msg == WM_LBUTTONUP) _inputQueue.Writer.TryWrite("INP:CLK:L:UP");
                    else if (msg == WM_RBUTTONDOWN) _inputQueue.Writer.TryWrite("INP:CLK:R:DOWN");
                    else if (msg == WM_RBUTTONUP) _inputQueue.Writer.TryWrite("INP:CLK:R:UP");
                    else if (msg == WM_MOUSEWHEEL)
                    {
                        int delta = (short)((hookStruct.mouseData >> 16) & 0xffff);
                        _inputQueue.Writer.TryWrite($"INP:SCROLL:{delta}");
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

                if (hookStruct.vkCode == 0x1B && Keyboard.IsKeyDown(Key.LeftCtrl) && Keyboard.IsKeyDown(Key.LeftShift))
                {
                    _isCursorOnRemote = false;
                    NativeMethods.SetCursorPos(_frozenAnchor.x + 50, _frozenAnchor.y);
                    return (IntPtr)1;
                }

                if (msg == WM_KEYDOWN) _inputQueue.Writer.TryWrite($"INP:KEY:{hookStruct.vkCode}:DOWN");
                else if (msg == WM_KEYUP) _inputQueue.Writer.TryWrite($"INP:KEY:{hookStruct.vkCode}:UP");

                return (IntPtr)1;
            }
            return NativeMethods.CallNextHookEx(_keyboardHookID, nCode, wParam, lParam);
        }
    }
}