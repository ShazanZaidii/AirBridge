using System;
using System.Diagnostics;
using System.IO;
using AirBridge.Net;
using Windows.Management.Deployment;

namespace AirBridge.Phone
{
    /// <summary>
    /// Package identity for the zip build (issue #10). Windows lets only apps with an identity read notifications and use
    /// other package-only APIs; the installer (MSIX) build has one. The zip build gets one by registering
    /// Identity/AirBridge.Identity.msix (built by tools/make_identity_package.py): a tiny unsigned "package with external
    /// location" that points at the folder AirBridge.exe runs from. AirBridge.exe's own manifest (app.manifest) names it.
    ///
    /// Windows 11 accepts unsigned packages like this (AddPackageOptions.AllowUnsigned, the "unsigned" publisher OID), so it
    /// costs nothing and asks for nothing: no certificate, no admin prompt. Windows 10 doesn't, and the installer build is
    /// the way there. Tried once per version; the app restarts once right after, since identity applies to new processes.
    /// </summary>
    public static class PackageIdentity
    {
        private const int FirstWindows11Build = 22000;

        /// <summary>True when this process runs with a package identity (installer build, or the zip build after this).</summary>
        public static bool Has
        {
            get
            {
                try { return Windows.ApplicationModel.Package.Current != null; }
                catch { return false; }
            }
        }

        /// <summary>
        /// Zip build without identity on Windows 11: registers the identity package. True = registered now, so restart to
        /// run with it. Never throws; never tries twice for the same version.
        /// </summary>
        public static bool RegisterForZipBuild()
        {
            try
            {
                if (Has || Environment.OSVersion.Version.Build < FirstWindows11Build) return false;
                string dir = AppContext.BaseDirectory;
                string msix = Path.Combine(dir, "Identity", "AirBridge.Identity.msix");
                if (!File.Exists(msix)) return false;
                string marker = Path.Combine(Log.Dir, $"identity-{Protocol.AppVersion}.tried");
                if (File.Exists(marker)) return false;
                Directory.CreateDirectory(Log.Dir);
                File.WriteAllText(marker, dir);

                var options = new AddPackageOptions
                {
                    ExternalLocationUri = new Uri(dir),
                    AllowUnsigned = true,
                    ForceUpdateFromAnyVersion = true, // a copy moved to another folder registers again
                };
                var result = new PackageManager().AddPackageByUriAsync(new Uri(msix), options).AsTask().GetAwaiter().GetResult();
                if (result.ExtendedErrorCode != null)
                {
                    Log.Warn($"Package identity: not registered ({result.ErrorText})");
                    return false;
                }
                Log.Info("Package identity: registered for the zip build; restarting once to use it");
                return true;
            }
            catch (Exception ex)
            {
                Log.Warn($"Package identity: could not register ({ex.Message})");
                return false;
            }
        }

        /// <summary>Starts AirBridge again (the new process runs with the identity).</summary>
        public static void Relaunch()
        {
            try
            {
                string? exe = Environment.ProcessPath;
                if (exe != null) Process.Start(new ProcessStartInfo(exe, "--after-identity") { UseShellExecute = false });
            }
            catch (Exception ex) { Log.Warn($"Package identity: relaunch failed ({ex.Message})"); }
        }
    }
}
