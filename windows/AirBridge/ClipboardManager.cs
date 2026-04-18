using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;

namespace AirBridge
{
    public enum FilterType { StartsWith, EndsWith, Contains }

    public class ClipboardFilter
    {
        public FilterType Type { get; set; }
        public string Keyword { get; set; }
    }

    public class ClipboardManager
    {
        private const int WM_CLIPBOARDUPDATE = 0x031D;
        private HwndSource _hwndSource;
        private Window _window;
        private bool _isUpdatingFromNetwork = false;
        private string _lastClipboardText = string.Empty;

        // Filtering Engine
        public static List<ClipboardFilter> Filters { get; private set; } = new();
        private static readonly string SettingsPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AirBridge", "filters.json");

        public ClipboardManager()
        {
            LoadFilters();
        }

        public static void SaveFilters()
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath));
            File.WriteAllText(SettingsPath, JsonSerializer.Serialize(Filters));
        }

        private void LoadFilters()
        {
            if (File.Exists(SettingsPath))
            {
                try { Filters = JsonSerializer.Deserialize<List<ClipboardFilter>>(File.ReadAllText(SettingsPath)) ?? new(); }
                catch { Filters = new(); }
            }
        }

        private bool ShouldIgnore(string text)
        {
            if (string.IsNullOrEmpty(text)) return true;
            foreach (var filter in Filters)
            {
                if (filter.Type == FilterType.StartsWith && text.StartsWith(filter.Keyword, StringComparison.OrdinalIgnoreCase)) return true;
                if (filter.Type == FilterType.EndsWith && text.EndsWith(filter.Keyword, StringComparison.OrdinalIgnoreCase)) return true;
                if (filter.Type == FilterType.Contains && text.Contains(filter.Keyword, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }

        public void Start(Window window)
        {
            _window = window;
            var helper = new WindowInteropHelper(_window);
            IntPtr hwnd = helper.EnsureHandle();
            _hwndSource = HwndSource.FromHwnd(hwnd);
            _hwndSource.AddHook(HwndHook);
            NativeMethods.AddClipboardFormatListener(hwnd);
        }

        public void Stop()
        {
            if (_window != null)
            {
                var helper = new WindowInteropHelper(_window);
                NativeMethods.RemoveClipboardFormatListener(helper.Handle);
            }
            _hwndSource?.RemoveHook(HwndHook);
        }

        private IntPtr HwndHook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == WM_CLIPBOARDUPDATE)
            {
                if (_isUpdatingFromNetwork) return IntPtr.Zero;

                string newText = GetClipboardTextSafe();

                // Apply Filters before sending
                if (!string.IsNullOrEmpty(newText) && newText != _lastClipboardText && !ShouldIgnore(newText))
                {
                    _lastClipboardText = newText;
                    _ = App.Server?.SendAsync(newText);
                    _ = App.Client?.SendAsync(newText);
                }
            }
            return IntPtr.Zero;
        }

        public void SetClipboardFromNetwork(string text)
        {
            if (string.IsNullOrEmpty(text) || text == _lastClipboardText || ShouldIgnore(text)) return;

            _isUpdatingFromNetwork = true;
            _lastClipboardText = text;

            Application.Current.Dispatcher.Invoke(() =>
            {
                SetClipboardTextSafe(text);
                Task.Delay(500).ContinueWith(_ => _isUpdatingFromNetwork = false);
            });
        }

        public void LockNetworkEcho(bool isLocked) => _isUpdatingFromNetwork = isLocked;

        private string GetClipboardTextSafe()
        {
            for (int i = 0; i < 5; i++)
            {
                try { if (Clipboard.ContainsText()) return Clipboard.GetText(); return string.Empty; }
                catch (COMException) { System.Threading.Thread.Sleep(20); }
            }
            return string.Empty;
        }

        private void SetClipboardTextSafe(string text)
        {
            for (int i = 0; i < 5; i++)
            {
                try { Clipboard.SetText(text); return; }
                catch (COMException) { System.Threading.Thread.Sleep(20); }
            }
        }
    }
}