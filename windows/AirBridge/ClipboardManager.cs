using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
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

        /// <summary>Raised on the STA thread with the new clipboard text.</summary>
        public event Action<string>? LocalTextChanged;
        /// <summary>Raised on the STA thread when the clipboard holds files and no text is wanted instead (docs/protocol.md 5.1).</summary>
        public event Action<IReadOnlyList<string>>? LocalFilesChanged;
        /// <summary>Raised on the STA thread with a picture on the clipboard, as PNG bytes (only when there is no text or file).</summary>
        public event Action<byte[]>? LocalImageChanged;

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
                    var snap = ReadSafe();
                    // Files win over text, text wins over an image (docs/protocol.md 5.1).
                    if (snap.Files is { Count: > 0 }) LocalFilesChanged?.Invoke(snap.Files);
                    else if (!string.IsNullOrEmpty(snap.Text)) LocalTextChanged?.Invoke(snap.Text);
                    else if (snap.Png != null) LocalImageChanged?.Invoke(snap.Png);
                }
                catch (Exception ex) { Log.Error("Clipboard change handling failed", ex); }
            }
            return IntPtr.Zero;
        }

        private readonly record struct Snapshot(string? Text, IReadOnlyList<string>? Files, byte[]? Png);

        /// <summary>What is on the clipboard now: files, text or a picture. Password managers mark their copies with
        /// "ExcludeClipboardContentFromMonitorProcessing"; those are never read.</summary>
        private static Snapshot ReadSafe()
        {
            for (int i = 0; i < 5; i++)
            {
                try
                {
                    var data = Clipboard.GetDataObject();
                    if (data == null || data.GetDataPresent("ExcludeClipboardContentFromMonitorProcessing")) return default;
                    if (Clipboard.ContainsFileDropList()) return new Snapshot(null, Clipboard.GetFileDropList().Cast<string>().ToList(), null);
                    if (Clipboard.ContainsText()) return new Snapshot(Clipboard.GetText(), null, null);
                    if (Clipboard.ContainsImage() && Clipboard.GetImage() is { } img) return new Snapshot(null, null, EncodePng(img));
                    return default;
                }
                catch (Exception) { Thread.Sleep(20); } // clipboard busy (COMException/ExternalException)
            }
            return default;
        }

        private static byte[] EncodePng(BitmapSource image)
        {
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(image));
            using var ms = new MemoryStream();
            enc.Save(ms);
            return ms.ToArray();
        }

        /// <summary>Puts a picture file on the clipboard as an image.</summary>
        public static bool TrySetImageFile(string path)
        {
            BitmapImage bmp;
            try
            {
                bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.UriSource = new Uri(path);
                bmp.EndInit();
                bmp.Freeze();
            }
            catch (Exception ex) { Log.Warn($"Cannot read the received picture: {ex.Message}"); return false; }
            for (int i = 0; i < 10; i++)
            {
                try { Clipboard.SetImage(bmp); return true; }
                catch (Exception) { Thread.Sleep(30); }
            }
            Log.Warn("Could not put the received picture on the clipboard");
            return false;
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
