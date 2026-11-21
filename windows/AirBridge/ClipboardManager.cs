using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.IO;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Interop;
using AirBridge.Net;

namespace AirBridge
{
    public enum FilterType { StartsWith, EndsWith, Contains }

    public class ClipboardFilter
    {
        public FilterType Type { get; set; }
        public string Keyword { get; set; } = "";
    }

    /// <summary>
    /// STA-side clipboard access: WM_CLIPBOARDUPDATE listener on a hidden window, safe get/set with retries,
    /// and the user's ignore filters (%APPDATA%\AirBridge\filters.json).
    /// </summary>
    public sealed class ClipboardManager
    {
        private const int WM_CLIPBOARDUPDATE = 0x031D;
        private HwndSource? _hwndSource;
        private IntPtr _hwnd;

        /// <summary>Raised on the STA thread with the new clipboard text (non-text changes are ignored).</summary>
        public event Action<string>? LocalTextChanged;

        public static List<ClipboardFilter> Filters { get; private set; } = new();
        private static readonly string SettingsPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AirBridge", "filters.json");

        public ClipboardManager() => LoadFilters();

        public static void SaveFilters()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath)!);
                File.WriteAllText(SettingsPath, JsonSerializer.Serialize(Filters));
            }
            catch (Exception ex) { Log.Error("Failed to save filters", ex); }
        }

        private static void LoadFilters()
        {
            try
            {
                if (File.Exists(SettingsPath))
                    Filters = JsonSerializer.Deserialize<List<ClipboardFilter>>(File.ReadAllText(SettingsPath)) ?? new();
            }
            catch { Filters = new(); }
        }

        public static bool ShouldIgnore(string text)
        {
            if (string.IsNullOrEmpty(text)) return true;
            foreach (var filter in Filters)
            {
                if (string.IsNullOrEmpty(filter.Keyword)) continue;
                if (filter.Type == FilterType.StartsWith && text.StartsWith(filter.Keyword, StringComparison.OrdinalIgnoreCase)) return true;
                if (filter.Type == FilterType.EndsWith && text.EndsWith(filter.Keyword, StringComparison.OrdinalIgnoreCase)) return true;
                if (filter.Type == FilterType.Contains && text.Contains(filter.Keyword, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }

        /// <summary>STA: attach the listener to a hidden window's handle (created without showing it).</summary>
        public void Start(Window hiddenWindow)
        {
            _hwnd = new WindowInteropHelper(hiddenWindow).EnsureHandle();
            _hwndSource = HwndSource.FromHwnd(_hwnd);
            _hwndSource.AddHook(HwndHook);
            if (!Win32.AddClipboardFormatListener(_hwnd)) Log.Warn("AddClipboardFormatListener failed");
        }

        public void Stop()
        {
            if (_hwnd != IntPtr.Zero) Win32.RemoveClipboardFormatListener(_hwnd);
            _hwndSource?.RemoveHook(HwndHook);
        }

        private IntPtr HwndHook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == WM_CLIPBOARDUPDATE)
            {
                try
                {
                    string text = GetTextSafe();
                    if (!string.IsNullOrEmpty(text)) LocalTextChanged?.Invoke(text);
                }
                catch (Exception ex) { Log.Error("Clipboard change handling failed", ex); }
            }
            return IntPtr.Zero;
        }

        private static string GetTextSafe()
        {
            for (int i = 0; i < 5; i++)
            {
                try { return Clipboard.ContainsText() ? Clipboard.GetText() : string.Empty; }
                catch (Exception) { Thread.Sleep(20); } // clipboard busy (COMException/ExternalException)
            }
            return string.Empty;
        }

        public static bool TrySetText(string text)
        {
            for (int i = 0; i < 10; i++)
            {
                try { Clipboard.SetText(text); return true; }
                catch (Exception) { Thread.Sleep(30); }
            }
            return false;
        }

        public static bool TrySetFiles(StringCollection paths)
        {
            for (int i = 0; i < 10; i++)
            {
                try { Clipboard.SetFileDropList(paths); return true; }
                catch (Exception) { Thread.Sleep(30); }
            }
            Log.Warn("Could not put received files on the clipboard");
            return false;
        }
    }
}
