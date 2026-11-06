namespace AirBridge.Input
{
    /// <summary>
    /// Hides the pointer system-wide by replacing every standard system cursor with a blank one, and restores the
    /// user's scheme with SPI_SETCURSORS. Restoration is also wired to process exit / unhandled exceptions, and
    /// <see cref="Restore"/> is called once at start-up in case a previous instance was killed while hidden.
    /// Never call from a hook callback (SetSystemCursor is slow).
    /// </summary>
    public static class CursorHider
    {
        private static readonly uint[] Ids =
        {
            NativeMethods.OCR_NORMAL, NativeMethods.OCR_IBEAM, NativeMethods.OCR_WAIT, NativeMethods.OCR_CROSS,
            NativeMethods.OCR_UP, NativeMethods.OCR_SIZENWSE, NativeMethods.OCR_SIZENESW, NativeMethods.OCR_SIZEWE,
            NativeMethods.OCR_SIZENS, NativeMethods.OCR_SIZEALL, NativeMethods.OCR_NO, NativeMethods.OCR_HAND,
            NativeMethods.OCR_APPSTARTING, NativeMethods.OCR_HELP, NativeMethods.OCR_PIN, NativeMethods.OCR_PERSON,
        };

        private static readonly object Gate = new();
        private static bool _hidden;
        private static bool _safetyInstalled;

        public static bool IsHidden { get { lock (Gate) return _hidden; } }

        public static void InstallSafetyNet()
        {
            lock (Gate)
            {
                if (_safetyInstalled) return;
                _safetyInstalled = true;
            }
            AppDomain.CurrentDomain.ProcessExit += (_, _) => Restore();
            AppDomain.CurrentDomain.UnhandledException += (_, _) => Restore();
        }

        public static void Hide()
        {
            lock (Gate)
            {
                if (_hidden) return;
                _hidden = true;
                var and = new byte[128];
                var xor = new byte[128];
                Array.Fill(and, (byte)0xFF);
                IntPtr blank = NativeMethods.CreateCursor(IntPtr.Zero, 0, 0, 32, 32, and, xor);
                if (blank == IntPtr.Zero) return;
                try
                {
                    foreach (uint id in Ids)
                    {
                        // SetSystemCursor takes ownership of (and destroys) the handle it is given.
                        IntPtr copy = NativeMethods.CopyImage(blank, NativeMethods.IMAGE_CURSOR, 0, 0, 0);
                        if (copy != IntPtr.Zero && !NativeMethods.SetSystemCursor(copy, id))
                            NativeMethods.DestroyCursor(copy);
                    }
                }
                finally { NativeMethods.DestroyCursor(blank); }
            }
        }

        public static void Restore() => Restore(force: false);

        public static void Restore(bool force)
        {
            lock (Gate)
            {
                if (!_hidden && !force) return;
                _hidden = false;
                try { NativeMethods.SystemParametersInfo(NativeMethods.SPI_SETCURSORS, 0, IntPtr.Zero, 0); } catch { }
            }
        }
    }
}
