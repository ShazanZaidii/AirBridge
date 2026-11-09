using System.Diagnostics;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

namespace AirBridge.Input
{
    /// <summary>
    /// The "edge catcher" for carrying a shell drag to another device (protocol.md §8.8).
    ///
    /// Windows has no public way to ask "is a file drag going on?". So while the left button is held and the cursor is
    /// pinned at an edge that leads to a <c>drag_files</c> peer, a thin, nearly invisible, topmost window appears under
    /// it. It is a normal OLE drop target (WPF AllowDrop): if a shell drag with files is in progress, the source's
    /// DoDragDrop loop sends it DragEnter with CF_HDROP, which hands us the file list and arms the carry. Only then can
    /// the push-through capture the cursor. While the cursor lives on the other device the real cursor stays frozen on
    /// this strip, so on release the source drops onto it: we accept a COPY (never MOVE: an unoptimised move would make
    /// Explorer delete the originals) and do nothing, so nothing lands on the desktop by accident. A drop here while not
    /// carrying (released at the edge without crossing) is refused, which looks like dropping on nothing.
    ///
    /// Called from the hook thread: <see cref="Show"/>/<see cref="Hide"/> only post to the UI dispatcher (coalesced),
    /// never wait.
    /// </summary>
    internal static class DropCatcher
    {
        private static readonly object Gate = new();
        private static Dispatcher? _ui;
        private static CatcherWindow? _win;
        private static IRect _rect;
        private static bool _wantShown, _queued;
        private static int _hideSerial;
        private static volatile bool _carrying;

        /// <summary>Called on the UI thread with the dropped-file list whenever an OLE file drag enters the strip.</summary>
        public static volatile Action<string[]>? Armed;

        /// <summary>The controller is carrying: accept the drop as a no-op copy. False = refuse drops.</summary>
        public static bool Carrying { get => _carrying; set => _carrying = value; }

        /// <summary>Call once on the UI thread.</summary>
        public static void Init(Dispatcher ui) => _ui = ui;

        /// <summary>Show (or move) the strip; physical pixels.</summary>
        public static void Show(IRect r)
        {
            lock (Gate) { _rect = r; _wantShown = true; _hideSerial++; }
            Kick();
        }

        /// <summary>Hide now, or after <paramref name="delayMs"/> unless shown again meanwhile.</summary>
        public static void Hide(int delayMs = 0)
        {
            int serial;
            lock (Gate) serial = ++_hideSerial;
            if (delayMs <= 0) { lock (Gate) _wantShown = false; Kick(); return; }
            var ui = _ui;
            if (ui == null || ui.HasShutdownStarted) return;
            try
            {
                ui.BeginInvoke(new Action(() =>
                {
                    var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(delayMs) };
                    t.Tick += (_, _) =>
                    {
                        t.Stop();
                        lock (Gate) { if (serial != _hideSerial) return; _wantShown = false; }
                        _carrying = false; // the drop (if any) has been performed by now
                        Apply();
                    };
                    t.Start();
                }));
            }
            catch { }
        }

        private static void Kick()
        {
            var ui = _ui;
            if (ui == null || ui.HasShutdownStarted) return;
            lock (Gate)
            {
                if (_queued) return;
                _queued = true;
            }
            try { ui.BeginInvoke(DispatcherPriority.Render, new Action(Apply)); }
            catch { lock (Gate) _queued = false; }
        }

        private static void Apply()
        {
            bool show; IRect r;
            lock (Gate) { show = _wantShown; r = _rect; _queued = false; }
            try
            {
                if (show) (_win ??= new CatcherWindow()).Place(r);
                else _win?.HideNow();
            }
            catch (Exception ex) { Debug.WriteLine($"drop catcher: {ex.Message}"); }
        }

        /// <summary>Topmost, never-activated, layered strip that takes OLE drops. Not click-through on purpose.</summary>
        private sealed class CatcherWindow : Window
        {
            private IRect _placed;

            public CatcherWindow()
            {
                WindowStyle = WindowStyle.None;
                AllowsTransparency = true;
                // Alpha 2/255: layered windows let input (and OLE hit tests) through fully transparent pixels.
                Background = new SolidColorBrush(Color.FromArgb(2, 255, 255, 255));
                Topmost = true;
                ShowInTaskbar = false;
                ShowActivated = false;
                ResizeMode = ResizeMode.NoResize;
                Focusable = false;
                AllowDrop = true;
                Width = 4; Height = 4; Left = -32000; Top = -32000;
                Title = "AirBridge drop";
                SourceInitialized += (_, _) =>
                {
                    IntPtr h = new WindowInteropHelper(this).Handle;
                    long ex = NativeMethods.GetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE);
                    NativeMethods.SetWindowLongPtr(h, NativeMethods.GWL_EXSTYLE, ex
                        | NativeMethods.WS_EX_NOACTIVATE | NativeMethods.WS_EX_TOOLWINDOW);
                };
                DragEnter += OnDragOver;
                DragOver += OnDragOver;
                Drop += OnDrop;
            }

            private static DragDropEffects Allowed(DragEventArgs e)
            {
                // COPY only (LINK as a harmless fallback). Never MOVE: see the class comment.
                if ((e.AllowedEffects & DragDropEffects.Copy) != 0) return DragDropEffects.Copy;
                if ((e.AllowedEffects & DragDropEffects.Link) != 0) return DragDropEffects.Link;
                return DragDropEffects.None;
            }

            private static string[]? Files(IDataObject? data)
            {
                try
                {
                    if (data == null || !data.GetDataPresent(DataFormats.FileDrop)) return null;
                    return data.GetData(DataFormats.FileDrop) as string[];
                }
                catch (Exception ex) { Debug.WriteLine($"drop catcher data: {ex.Message}"); return null; }
            }

            private void OnDragOver(object sender, DragEventArgs e)
            {
                var files = Files(e.Data);
                e.Effects = files is { Length: > 0 } ? Allowed(e) : DragDropEffects.None;
                e.Handled = true;
                if (e.RoutedEvent == DragEnterEvent && files is { Length: > 0 })
                {
                    try { Armed?.Invoke(files); } catch (Exception ex) { Debug.WriteLine($"drop catcher armed: {ex.Message}"); }
                }
            }

            private void OnDrop(object sender, DragEventArgs e)
            {
                // The files are already on their way to the other device; the local drop does nothing.
                e.Effects = _carrying ? Allowed(e) : DragDropEffects.None;
                e.Handled = true;
            }

            public void Place(IRect r)
            {
                if (r.Width <= 0 || r.Height <= 0) return;
                IntPtr h = new WindowInteropHelper(this).EnsureHandle();
                if (!IsVisible) Show();
                if (r == _placed) return;
                _placed = r;
                const uint flags = NativeMethods.SWP_NOACTIVATE | NativeMethods.SWP_SHOWWINDOW | NativeMethods.SWP_NOOWNERZORDER;
                // Twice: moving onto a monitor with another DPI makes WPF apply its own suggested size on the first.
                NativeMethods.SetWindowPos(h, NativeMethods.HWND_TOPMOST, r.Left, r.Top, r.Width, r.Height, flags);
                NativeMethods.SetWindowPos(h, NativeMethods.HWND_TOPMOST, r.Left, r.Top, r.Width, r.Height, flags);
            }

            public void HideNow()
            {
                if (IsVisible) Hide();
                _placed = default;
            }
        }
    }
}
