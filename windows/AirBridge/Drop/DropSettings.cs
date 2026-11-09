using System;
using System.IO;
using System.Text.Json;
using AirBridge.Net;

namespace AirBridge.Drop
{
    /// <summary>Drop shelf and transfer card settings, in %LOCALAPPDATA%\AirBridge\drop.json.</summary>
    public static class DropSettings
    {
        private sealed class Stored
        {
            public bool ShelfEnabled { get; set; } = true;
            public bool AutoHideFinished { get; set; }
        }

        private static readonly string FilePath = Path.Combine(Log.Dir, "drop.json");
        private static readonly object Gate = new();
        private static Stored? _s;

        /// <summary>Raised (on the caller's thread) after a setting changed.</summary>
        public static event Action? Changed;

        /// <summary>Show the small drop shelf above the notification area.</summary>
        public static bool ShelfEnabled
        {
            get { lock (Gate) return Load().ShelfEnabled; }
            set { lock (Gate) { Load().ShelfEnabled = value; Save(); } Changed?.Invoke(); }
        }

        /// <summary>Hide finished transfer cards after a few seconds (off: they stay until closed).</summary>
        public static bool AutoHideFinished
        {
            get { lock (Gate) return Load().AutoHideFinished; }
            set { lock (Gate) { Load().AutoHideFinished = value; Save(); } Changed?.Invoke(); }
        }

        private static Stored Load()
        {
            if (_s != null) return _s;
            try { _s = File.Exists(FilePath) ? JsonSerializer.Deserialize<Stored>(File.ReadAllText(FilePath)) : null; }
            catch (Exception ex) { Log.Warn($"drop.json unreadable ({ex.Message}); using defaults"); }
            return _s ??= new Stored();
        }

        private static void Save()
        {
            try
            {
                Directory.CreateDirectory(Log.Dir);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(_s));
            }
            catch (Exception ex) { Log.Warn($"Could not save drop.json: {ex.Message}"); }
        }
    }
}
