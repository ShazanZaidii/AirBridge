using System;
using System.Diagnostics;
using System.IO;

namespace AirBridge.Net
{
    /// <summary>Tiny file logger: %LOCALAPPDATA%\AirBridge\airbridge.log (truncated at 2 MiB).</summary>
    public static class Log
    {
        private static readonly object Gate = new();
        /// <summary>%LOCALAPPDATA%\AirBridge (override with AIRBRIDGE_DATA_DIR to run a second instance for testing).</summary>
        public static readonly string Dir = Environment.GetEnvironmentVariable("AIRBRIDGE_DATA_DIR") is { Length: > 0 } d
            ? d : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AirBridge");
        private static readonly string FilePath = Path.Combine(Dir, "airbridge.log");

        public static void Info(string msg) => Write("INFO", msg);
        public static void Warn(string msg) => Write("WARN", msg);
        public static void Error(string msg, Exception? ex = null) => Write("ERROR", ex == null ? msg : $"{msg}: {ex}");

        private static void Write(string level, string msg)
        {
            string line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] {msg}";
            Debug.WriteLine(line);
            lock (Gate)
            {
                try
                {
                    Directory.CreateDirectory(Dir);
                    var fi = new FileInfo(FilePath);
                    if (fi.Exists && fi.Length > 2 * 1024 * 1024) fi.Delete();
                    File.AppendAllText(FilePath, line + Environment.NewLine);
                }
                catch { /* logging must never throw */ }
            }
        }
    }
}
