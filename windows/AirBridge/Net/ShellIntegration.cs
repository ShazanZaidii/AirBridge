using System;
using System.IO;
using Microsoft.Win32;

namespace AirBridge.Net
{
    /// <summary>
    /// Explorer "Send via AirBridge" entry points.
    /// Unpackaged: HKCU *\shell + Directory\shell verbs and a SendTo shortcut pointing at the real exe.
    /// Packaged (MSIX): HKCU\Software\Classes and AppData writes are virtualised, so Explorer would never see
    /// them — we register nothing and rely on the manifest's Share target (see docs in the report for the
    /// desktop4:FileExplorerContextMenus / IExplorerCommand route).
    /// </summary>
    public static class ShellIntegration
    {
        private const string VerbName = "AirBridge";
        private const string VerbText = "Send via AirBridge";

        public static void RegisterIfUnpackaged()
        {
            if (Win32.IsPackaged)
            {
                Log.Info("Packaged: skipping HKCU context-menu registration (Share target is used instead)");
                return;
            }
            string? exe = Environment.ProcessPath;
            if (exe == null || !File.Exists(exe)) return;
            try
            {
                bool changed = false;
                changed |= RegisterVerb(@"Software\Classes\*\shell\" + VerbName, exe);
                // Folders are not sent yet (v2 file lists are flat), so only files get the verb; remove stale v1 folder verb.
                Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\Directory\shell\" + VerbName, throwOnMissingSubKey: false);
                CreateSendToShortcut(exe);
                if (changed) Win32.SHChangeNotify(0x08000000 /* SHCNE_ASSOCCHANGED */, 0, IntPtr.Zero, IntPtr.Zero);
            }
            catch (Exception ex) { Log.Error("Context menu registration failed", ex); }
        }

        /// <summary>Writes the verb if missing or pointing elsewhere. Returns true if something changed.</summary>
        private static bool RegisterVerb(string keyPath, string exe)
        {
            string command = $"\"{exe}\" \"%1\"";
            using (var existing = Registry.CurrentUser.OpenSubKey(keyPath + @"\command"))
            {
                if (existing?.GetValue("") as string == command) return false;
            }
            using var key = Registry.CurrentUser.CreateSubKey(keyPath);
            key.SetValue("", VerbText);
            key.SetValue("Icon", $"\"{exe}\",0");
            key.SetValue("MultiSelectModel", "Player");
            using var cmd = key.CreateSubKey("command");
            cmd.SetValue("", command);
            return true;
        }

        private static void CreateSendToShortcut(string exe)
        {
            string sendTo = Environment.GetFolderPath(Environment.SpecialFolder.SendTo);
            if (string.IsNullOrEmpty(sendTo)) return;
            string lnk = Path.Combine(sendTo, "AirBridge.lnk");
            Type? shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return;
            dynamic? shell = Activator.CreateInstance(shellType);
            if (shell == null) return;
            try
            {
                dynamic shortcut = shell.CreateShortcut(lnk);
                if (File.Exists(lnk) && string.Equals((string)shortcut.TargetPath, exe, StringComparison.OrdinalIgnoreCase)) return;
                shortcut.TargetPath = exe;
                shortcut.IconLocation = exe + ",0";
                shortcut.Description = VerbText;
                shortcut.Save();
            }
            finally { System.Runtime.InteropServices.Marshal.FinalReleaseComObject(shell); }
        }
    }
}
