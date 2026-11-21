using System;
using Microsoft.Win32;

namespace AirBridge
{
    /// <summary>"Start with Windows": a Run-key entry for this user (unpackaged build). The Store build uses its own startup task.</summary>
    public static class Startup
    {
        private const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string ValueName = "AirBridge";

        public static bool Supported => !AirBridge.Net.Win32.IsPackaged && Environment.ProcessPath != null;

        public static bool Enabled
        {
            get
            {
                try
                {
                    using var k = Registry.CurrentUser.OpenSubKey(KeyPath);
                    return k?.GetValue(ValueName) is string v && v.Length > 0;
                }
                catch { return false; }
            }
            set
            {
                try
                {
                    using var k = Registry.CurrentUser.OpenSubKey(KeyPath, writable: true) ?? Registry.CurrentUser.CreateSubKey(KeyPath);
                    if (value && Environment.ProcessPath is string exe) k.SetValue(ValueName, $"\"{exe}\" --background"); // at login: just the tray, no window
                    else k.DeleteValue(ValueName, throwOnMissingValue: false);
                }
                catch (Exception ex) { AirBridge.Net.Log.Warn($"Start with Windows not changed: {ex.Message}"); }
            }
        }
    }
}
