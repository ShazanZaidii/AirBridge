using System;
using System.Runtime.InteropServices;
using System.Text;

namespace AirBridge.Net
{
    /// <summary>P/Invoke used by the network half (kept separate from the input half's NativeMethods).</summary>
    internal static class Win32
    {
        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool AddClipboardFormatListener(IntPtr hwnd);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

        [DllImport("shell32.dll")]
        public static extern void SHChangeNotify(int wEventId, int uFlags, IntPtr dwItem1, IntPtr dwItem2);

        [DllImport("user32.dll")]
        private static extern IntPtr GetDC(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

        [DllImport("gdi32.dll")]
        private static extern int GetDeviceCaps(IntPtr hdc, int nIndex);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetCurrentPackageFullName(ref int packageFullNameLength, StringBuilder? packageFullName);

        private const int DESKTOPVERTRES = 117;
        private const int DESKTOPHORZRES = 118;
        private const int APPMODEL_ERROR_NO_PACKAGE = 15700;

        /// <summary>Primary monitor size in physical pixels, independent of the process DPI awareness.</summary>
        public static (int W, int H) PrimaryScreenPixels()
        {
            try
            {
                IntPtr dc = GetDC(IntPtr.Zero);
                try { return (GetDeviceCaps(dc, DESKTOPHORZRES), GetDeviceCaps(dc, DESKTOPVERTRES)); }
                finally { ReleaseDC(IntPtr.Zero, dc); }
            }
            catch { return (0, 0); }
        }

        private static bool? _packaged;

        /// <summary>True when running with MSIX package identity.</summary>
        public static bool IsPackaged
        {
            get
            {
                if (_packaged is bool b) return b;
                try
                {
                    int len = 0;
                    _packaged = GetCurrentPackageFullName(ref len, null) != APPMODEL_ERROR_NO_PACKAGE;
                }
                catch { _packaged = false; } // API missing (pre-Win8) => unpackaged
                return _packaged.Value;
            }
        }
    }
}
