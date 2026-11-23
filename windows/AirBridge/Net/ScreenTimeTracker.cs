using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using System.Threading;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using Microsoft.Win32;

namespace AirBridge.Net
{
    /// <summary>
    /// Counts this PC's screen time (docs/protocol.md 15) and sends it to computers that show it (the Mac's AirBridge widget):
    /// the app whose window is in front, minus idle time (no input for 5 minutes) and while the session is locked. Windows keeps
    /// no app usage history for other apps to read, so this counts from when AirBridge runs. The numbers are saved in
    /// %LOCALAPPDATA%\AirBridge\screentime.json and never leave your own paired devices.
    /// </summary>
    public sealed class ScreenTimeTracker : IDisposable
    {
        private static readonly TimeSpan IdleLimit = TimeSpan.FromMinutes(5);
        private static readonly string FilePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AirBridge", "screentime.json");

        private readonly Func<IReadOnlyList<PeerInfo>> _peers;
        private readonly Func<string, JsonObject, bool> _send;
        private readonly object _gate = new();
        private readonly Timer _poll;
        private readonly ScreenTimeAccumulator _acc;
        private (string Id, string Name, string Exe, DateTime Since)? _current;
        private bool _locked;
        private DateTime _lastFlush = DateTime.Now, _lastSend = DateTime.MinValue;
        private string? _lastSentText;
        private readonly ConcurrentDictionary<string, HashSet<string>> _sentIcons = new();
        private readonly ConcurrentDictionary<string, string> _exeById = new();

        public ScreenTimeTracker(Func<IReadOnlyList<PeerInfo>> peers, Func<string, JsonObject, bool> send)
        {
            _peers = peers; _send = send;
            try { _acc = ScreenTimeAccumulator.FromJson(File.Exists(FilePath) ? File.ReadAllText(FilePath) : null); }
            catch { _acc = new ScreenTimeAccumulator(); }
            try { SystemEvents.SessionSwitch += OnSessionSwitch; } catch { }
            _poll = new Timer(_ => Poll(), null, TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(2));
        }

        private void OnSessionSwitch(object? sender, SessionSwitchEventArgs e)
        {
            lock (_gate)
            {
                switch (e.Reason)
                {
                    case SessionSwitchReason.SessionLock:
                    case SessionSwitchReason.ConsoleDisconnect:
                    case SessionSwitchReason.RemoteDisconnect:
                        Close(DateTime.Now); _locked = true; Save(); break;
                    case SessionSwitchReason.SessionUnlock:
                    case SessionSwitchReason.ConsoleConnect:
                    case SessionSwitchReason.RemoteConnect:
                        _locked = false; break;
                }
            }
        }

        private void Poll()
        {
            try
            {
                lock (_gate)
                {
                    var now = DateTime.Now;
                    var idle = IdleTime();
                    if (_locked || idle >= IdleLimit)
                    {
                        if (_current != null) { Close(now - idle); Save(); }
                        return;
                    }
                    var fg = Foreground();
                    if (fg == null || ScreenTimeWire.Ignored.Contains(fg.Value.Id)) { Close(now); }
                    else if (_current == null || _current.Value.Id != fg.Value.Id)
                    {
                        Close(now);
                        _current = (fg.Value.Id, fg.Value.Name, fg.Value.Exe, now);
                        _exeById[fg.Value.Id] = fg.Value.Exe;
                    }
                    if (now - _lastFlush >= TimeSpan.FromSeconds(30))
                    {
                        if (_current is { } c) { Close(now); _current = (c.Id, c.Name, c.Exe, now); }
                        Save();
                    }
                }
                if (DateTime.Now - _lastSend >= TimeSpan.FromMinutes(5)) SendAll(force: false);
            }
            catch (Exception ex) { Log.Warn($"screen time poll: {ex.Message}"); }
        }

        private void Close(DateTime end)
        {
            if (_current is not { } c) return;
            _acc.Add(c.Id, c.Name, c.Since, end > c.Since ? end : c.Since);
            _current = null;
        }

        private void Save()
        {
            _lastFlush = DateTime.Now;
            _acc.Prune(30);
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
                File.WriteAllText(FilePath, _acc.ToJson());
            }
            catch (Exception ex) { Log.Warn($"screen time save: {ex.Message}"); }
        }

        /// <summary>A peer became ready: if it shows screen time it gets the numbers and the top apps' icons at once.</summary>
        public void SendTo(PeerInfo peer)
        {
            if (!peer.Caps.Contains(ScreenTimeWire.Cap)) return;
            _sentIcons.TryRemove(peer.Id, out _);
            ThreadPool.QueueUserWorkItem(_ => { try { Send(peer.Id, Snapshot()); } catch (Exception ex) { Log.Warn($"screen time send: {ex.Message}"); } });
        }

        private (JsonObject Msg, List<string> Top) Snapshot()
        {
            lock (_gate)
            {
                var now = DateTime.Now;
                if (_current is { } c) { Close(now); _current = (c.Id, c.Name, c.Exe, now); }
                return (_acc.Message(now), _acc.TopAppIds(now, 8));
            }
        }

        private void SendAll(bool force)
        {
            var sinks = _peers().Where(p => p.Caps.Contains(ScreenTimeWire.Cap)).ToList();
            _lastSend = DateTime.Now;
            if (sinks.Count == 0) return;
            var snap = Snapshot();
            string text = snap.Msg.ToJsonString();
            if (!force && text == _lastSentText) return;
            _lastSentText = text;
            foreach (var p in sinks) Send(p.Id, snap);
        }

        private void Send(string peerId, (JsonObject Msg, List<string> Top) snap)
        {
            _send(peerId, (JsonObject)snap.Msg.DeepClone());
            var have = _sentIcons.GetOrAdd(peerId, _ => new HashSet<string>());
            foreach (var id in snap.Top)
            {
                if (have.Contains(id) || !_exeById.TryGetValue(id, out var exe)) continue;
                var png = IconPng(exe);
                if (png != null && ScreenTimeWire.IconMessage(id, png) is { } m && _send(peerId, m)) have.Add(id);
            }
        }

        // ------------------------------------------------------------------ Windows

        [StructLayout(LayoutKind.Sequential)] private struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
        [DllImport("user32.dll")] private static extern bool GetLastInputInfo(ref LASTINPUTINFO info);
        [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
        private delegate bool EnumProc(IntPtr hwnd, IntPtr lParam);
        [DllImport("user32.dll")] private static extern bool EnumChildWindows(IntPtr parent, EnumProc cb, IntPtr lParam);
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct SHFILEINFO { public IntPtr hIcon; public int iIcon; public uint dwAttributes; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szDisplayName; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string szTypeName; }
        [DllImport("shell32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr SHGetFileInfo(string path, uint attrs, ref SHFILEINFO info, uint size, uint flags);
        [DllImport("user32.dll")] private static extern bool DestroyIcon(IntPtr h);
        private const uint SHGFI_ICON = 0x100, SHGFI_LARGEICON = 0x0;

        private static TimeSpan IdleTime()
        {
            var li = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
            if (!GetLastInputInfo(ref li)) return TimeSpan.Zero;
            return TimeSpan.FromMilliseconds(unchecked((uint)Environment.TickCount - li.dwTime));
        }

        /// <summary>The app whose window is in front: (id "chrome.exe", name "Google Chrome", path). Store apps live inside
        /// ApplicationFrameHost, so its child window's process is used instead.</summary>
        private static (string Id, string Name, string Exe)? Foreground()
        {
            var hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return null;
            GetWindowThreadProcessId(hwnd, out uint pid);
            var exe = ExePath(pid);
            if (exe != null && Path.GetFileName(exe).Equals("ApplicationFrameHost.exe", StringComparison.OrdinalIgnoreCase))
            {
                uint inner = 0;
                EnumChildWindows(hwnd, (child, _) =>
                {
                    GetWindowThreadProcessId(child, out uint cp);
                    if (cp != pid) { inner = cp; return false; }
                    return true;
                }, IntPtr.Zero);
                if (inner != 0) { exe = ExePath(inner) ?? exe; pid = inner; }
            }
            if (exe == null) return null;
            return (ScreenTimeWire.AppId(exe), DisplayName(exe), exe);
        }

        private static string? ExePath(uint pid)
        {
            try { using var p = Process.GetProcessById((int)pid); return p.MainModule?.FileName ?? (p.ProcessName + ".exe"); }
            catch { try { using var p = Process.GetProcessById((int)pid); return p.ProcessName + ".exe"; } catch { return null; } }
        }

        private static readonly ConcurrentDictionary<string, string> Names = new(StringComparer.OrdinalIgnoreCase);
        private static string DisplayName(string exe) => Names.GetOrAdd(exe, e =>
        {
            try
            {
                if (File.Exists(e))
                {
                    var v = FileVersionInfo.GetVersionInfo(e);
                    var n = !string.IsNullOrWhiteSpace(v.FileDescription) ? v.FileDescription : v.ProductName;
                    if (!string.IsNullOrWhiteSpace(n)) return n!.Trim();
                }
            }
            catch { }
            return Path.GetFileNameWithoutExtension(e);
        });

        /// <summary>The executable's icon as a 64x64 PNG, or null.</summary>
        private static byte[]? IconPng(string exe)
        {
            if (!File.Exists(exe)) return null;
            byte[]? result = null;
            void Make()
            {
                var info = new SHFILEINFO();
                if (SHGetFileInfo(exe, 0, ref info, (uint)Marshal.SizeOf<SHFILEINFO>(), SHGFI_ICON | SHGFI_LARGEICON) == IntPtr.Zero || info.hIcon == IntPtr.Zero) return;
                try
                {
                    var src = Imaging.CreateBitmapSourceFromHIcon(info.hIcon, Int32Rect.Empty, BitmapSizeOptions.FromWidthAndHeight(64, 64));
                    var enc = new PngBitmapEncoder();
                    enc.Frames.Add(BitmapFrame.Create(src));
                    using var ms = new MemoryStream();
                    enc.Save(ms);
                    result = ms.ToArray();
                }
                finally { DestroyIcon(info.hIcon); }
            }
            try
            {
                var d = Application.Current?.Dispatcher;
                if (d != null && !d.CheckAccess()) d.Invoke(Make); else Make();
            }
            catch { return null; }
            return result;
        }

        public void Dispose()
        {
            try { SystemEvents.SessionSwitch -= OnSessionSwitch; } catch { }
            _poll.Dispose();
            lock (_gate) { Close(DateTime.Now); Save(); }
        }
    }
}
