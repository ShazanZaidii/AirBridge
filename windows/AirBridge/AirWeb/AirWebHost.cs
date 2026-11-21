using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows;
using AirBridge.Net;

namespace AirBridge.AirWeb
{
    /// <summary>
    /// The one AirWeb session of this app and its window. Closing the window leaves AirWeb running (same link, same
    /// server); sending more files later joins the same session. Stop it from the window or the tray popup.
    /// </summary>
    public static class AirWebHost
    {
        private static readonly object Gate = new();
        private static AirWebSession? _session;
        private static string? _page;

        public static event Action? StateChanged;

        public static bool IsOn { get { lock (Gate) return _session != null; } }
        public static AirWebSession? Session { get { lock (Gate) return _session; } }

        private static string PageText()
        {
            if (_page != null) return _page;
            using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("airweb.html")
                ?? throw new FileNotFoundException("airweb.html is not built into the app");
            using var r = new StreamReader(s, System.Text.Encoding.UTF8);
            return _page = r.ReadToEnd();
        }

        /// <summary>The session, started if there is none yet (one server at a time).</summary>
        public static AirWebSession Ensure()
        {
            AirWebSession s;
            bool created = false;
            lock (Gate)
            {
                if (_session == null)
                {
                    _session = new AirWebSession(
                        () => NetUtil.LocalIPv4Addresses().Select(a => a.ToString()).ToList(),
                        () => App.Peers?.Identity.Name ?? Environment.MachineName,
                        PageText,
                        FileTransferSession.SaveDirectory);
                    _session.Changed += () => StateChanged?.Invoke();
                    _session.IdleEnded += OnIdleEnded;
                    created = true;
                }
                s = _session;
            }
            if (created) s.Start();
            StateChanged?.Invoke();
            return s;
        }

        /// <summary>Adds files to the session (starting it if need be).</summary>
        public static void Offer(string[] files)
        {
            var s = Ensure();
            if (files.Length > 0) s.Add(files);
        }

        public static void Stop()
        {
            AirWebSession? s;
            lock (Gate) { s = _session; _session = null; }
            s?.Dispose();
            StateChanged?.Invoke();
        }

        private static void OnIdleEnded()
        {
            lock (Gate) _session = null;
            StateChanged?.Invoke();
            App.Notify("AirWeb stopped", "Nobody was connected for two hours, so AirWeb turned itself off.");
        }

        /// <summary>Shows the AirWeb window (any thread), offering <paramref name="files"/> if given.</summary>
        public static void Show(string[]? files = null)
        {
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                try
                {
                    Offer(files ?? Array.Empty<string>());
                    var w = Application.Current.Windows.OfType<AirWebWindow>().FirstOrDefault();
                    if (w == null) { w = new AirWebWindow(); w.Show(); }
                    if (w.WindowState == WindowState.Minimized) w.WindowState = WindowState.Normal;
                    w.Activate();
                }
                catch (Exception ex) { Log.Error("AirWeb window failed", ex); }
            }));
        }
    }
}
