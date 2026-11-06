using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Security.Principal;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using AirBridge.Net;

namespace AirBridge
{
    /// <summary>
    /// Single-instance plumbing: a multi-instance named pipe receives file paths from short-lived
    /// context-menu / share-target processes and opens one SendWindow per 200 ms batch.
    /// </summary>
    public static class IpcManager
    {
        private static readonly string UserTag = SafeUserSid();
        public static readonly string MutexName = @"Local\AirBridge_v2_Single_Instance_" + UserTag;
        private static readonly string PipeName = "AirBridge_v2_Files_" + UserTag;

        private static readonly List<string> Buffer = new();
        private static readonly System.Timers.Timer BatchTimer = new(200) { AutoReset = false };

        static IpcManager()
        {
            BatchTimer.Elapsed += (_, _) => Flush();
        }

        private static string SafeUserSid()
        {
            try { return WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName; }
            catch { return Environment.UserName; }
        }

        public static bool IsMainInstanceRunning()
        {
            try
            {
                if (Mutex.TryOpenExisting(MutexName, out var m)) { m.Dispose(); return true; }
            }
            catch (UnauthorizedAccessException) { return true; }
            return false;
        }

        // ------------------------------------------------------------------ server (main instance)

        public static void StartServer(CancellationToken ct) => _ = Task.Run(() => AcceptLoopAsync(ct));

        private static async Task AcceptLoopAsync(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                NamedPipeServerStream? server = null;
                try
                {
                    server = new NamedPipeServerStream(PipeName, PipeDirection.In,
                        NamedPipeServerStream.MaxAllowedServerInstances, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                    await server.WaitForConnectionAsync(ct).ConfigureAwait(false);
                    var connected = server;
                    server = null;
                    _ = Task.Run(() => HandleClientAsync(connected)); // next instance listens immediately
                }
                catch (OperationCanceledException) { server?.Dispose(); return; }
                catch (Exception ex)
                {
                    server?.Dispose();
                    Log.Error("IPC pipe error", ex);
                    await Task.Delay(500, CancellationToken.None).ConfigureAwait(false);
                }
            }
        }

        private static async Task HandleClientAsync(NamedPipeServerStream pipe)
        {
            try
            {
                using (pipe)
                using (var reader = new StreamReader(pipe))
                {
                    using var timeout = new CancellationTokenSource(5000);
                    string? payload = await reader.ReadLineAsync(timeout.Token).ConfigureAwait(false);
                    if (string.IsNullOrEmpty(payload)) return;
                    lock (Buffer) Buffer.AddRange(payload.Split('|', StringSplitOptions.RemoveEmptyEntries));
                    BatchTimer.Stop();
                    BatchTimer.Start();
                }
            }
            catch (Exception ex) { Log.Warn($"IPC client read failed: {ex.Message}"); }
        }

        private static void Flush()
        {
            string[] files;
            lock (Buffer)
            {
                files = Buffer.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
                Buffer.Clear();
            }
            if (files.Length > 0) App.ShowSendWindow(files);
        }

        // ------------------------------------------------------------------ client (helper processes)

        public static bool TrySend(IEnumerable<string> files, int timeoutMs)
        {
            try
            {
                using var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                client.Connect(timeoutMs);
                using var writer = new StreamWriter(client);
                writer.WriteLine(string.Join("|", files));
                writer.Flush();
                return true;
            }
            catch { return false; }
        }

        /// <summary>Starts the main (tray) instance. Packaged: through its AppsFolder entry; unpackaged: our exe.</summary>
        public static void LaunchMainInstance()
        {
            try
            {
                if (Win32.IsPackaged)
                {
                    string family = global::Windows.ApplicationModel.Package.Current.Id.FamilyName;
                    Process.Start(new ProcessStartInfo("explorer.exe", $"shell:AppsFolder\\{family}!App") { UseShellExecute = true });
                }
                else if (Environment.ProcessPath is string exe)
                {
                    Process.Start(new ProcessStartInfo(exe) { UseShellExecute = false });
                }
            }
            catch (Exception ex) { Log.Error("Could not launch main instance", ex); }
        }

        /// <summary>Delivers files to the main instance, launching it if needed. Blocks up to ~20 s.</summary>
        public static bool HandOffLaunchingIfNeeded(string[] files)
        {
            if (TrySend(files, 500)) return true;
            if (!IsMainInstanceRunning()) LaunchMainInstance();
            var sw = Stopwatch.StartNew();
            while (sw.Elapsed < TimeSpan.FromSeconds(20))
            {
                if (TrySend(files, 1000)) return true;
                Thread.Sleep(300);
            }
            Log.Warn("Main instance never accepted the files");
            return false;
        }
    }
}
