using System;
using System.IO;
using System.Text.Json;
using AirBridge.Net;

namespace AirBridge
{
    /// <summary>Look-and-feel settings, in %LOCALAPPDATA%\AirBridge\ui.json.</summary>
    public static class UiSettings
    {
        private sealed class Stored
        {
            public bool Glass { get; set; } = true;
            /// <summary>The first-run setup (SetupWindow) has been shown once.</summary>
            public bool SetupShown { get; set; }
        }

        private static readonly string FilePath = Path.Combine(Log.Dir, "ui.json");
        private static readonly object Gate = new();
        private static Stored? _s;

        /// <summary>Frosted-glass window backgrounds where Windows supports them (Windows 11). Off: plain dark windows.</summary>
        public static bool Glass
        {
            get { lock (Gate) return Load().Glass; }
            set { lock (Gate) { Load().Glass = value; Save(); } }
        }

        public static bool SetupShown
        {
            get { lock (Gate) return Load().SetupShown; }
            set { lock (Gate) { Load().SetupShown = value; Save(); } }
        }

        private static Stored Load()
        {
            if (_s != null) return _s;
            try { _s = File.Exists(FilePath) ? JsonSerializer.Deserialize<Stored>(File.ReadAllText(FilePath)) : null; }
            catch (Exception ex) { Log.Warn($"ui.json unreadable ({ex.Message}); using defaults"); }
            return _s ??= new Stored();
        }

        private static void Save()
        {
            try { Directory.CreateDirectory(Log.Dir); File.WriteAllText(FilePath, JsonSerializer.Serialize(_s)); }
            catch (Exception ex) { Log.Warn($"Could not save ui.json: {ex.Message}"); }
        }
    }
}
