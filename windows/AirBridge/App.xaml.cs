using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using AirBridge.Net;
using Hardcodet.Wpf.TaskbarNotification;

namespace AirBridge
{
    public partial class App : Application
    {
        private static Mutex? _singleInstanceMutex;

        /// <summary>The network half (sessions, pairing, discovery, clipboard, files).</summary>
        public static PeerService? Peers { get; private set; }
        public static InputInterceptor? Interceptor { get; private set; }
        public static ClipboardManager? ClipboardMonitor { get; private set; }

        private TaskbarIcon? _trayIcon;
        private Timer? _quietTrim;
        private Window? _hiddenClipboardWindow;
        private readonly CancellationTokenSource _ipcCts = new();

        protected override void OnStartup(StartupEventArgs e)
        {
            // A throwaway child process: can this PC capture its screen and encode H.264? (docs/protocol.md 8.7, see MirrorPlatformFactory)
            // It runs before anything else, so a crash in graphics or Media Foundation code never touches the real app.
            if (e.Args.Contains("--mirror-selftest")) { Environment.Exit(Input.WindowsMirrorSelfTest.Run()); return; }
            base.OnStartup(e);
            InstallExceptionHandlers();
            try
            {
                if (TryHandleShareActivation()) return;

                string[] files = e.Args.Where(a => !string.IsNullOrWhiteSpace(a) && !a.StartsWith("--", StringComparison.Ordinal)).ToArray();
                _singleInstanceMutex = new Mutex(true, IpcManager.MutexName, out bool createdNew);
                if (!createdNew)
                {
                    _singleInstanceMutex.Dispose();
                    _singleInstanceMutex = null;
                    if (files.Length > 0) IpcManager.HandOffLaunchingIfNeeded(files);
                    else IpcManager.TrySend(new[] { IpcManager.ShowMainToken }, 1000); // started again from the Start menu: show the window
                    Shutdown();
                    return;
                }
                StartMainInstance(files);
            }
            catch (Exception ex)
            {
                Log.Error("Startup failed", ex);
                MessageBox.Show($"AirBridge failed to start:\n{ex.Message}", "AirBridge", MessageBoxButton.OK, MessageBoxImage.Error);
                Shutdown();
            }
        }

        private void StartMainInstance(string[] pendingFiles)
        {
            Log.Info($"AirBridge {Protocol.AppVersion} starting (packaged: {Win32.IsPackaged})");

            // 1. Local services first: IPC, clipboard hook, tray. Nothing here touches the network.
            IpcManager.StartServer(_ipcCts.Token);
            Peers = new PeerService();
            try { Phone.PhoneNotifHub.Start(Peers); } catch (Exception ex) { Log.Error("Phone notifications failed to start", ex); }
            ApplyControlStatus(); // in case the input half reported a status before the network half existed

            _hiddenClipboardWindow = new Window
            {
                Width = 0, Height = 0, WindowStyle = WindowStyle.None, ShowInTaskbar = false, ShowActivated = false,
            };
            ClipboardMonitor = new ClipboardManager();
            ClipboardMonitor.LocalTextChanged += text => Peers?.Clipboard.OnLocalText(text);
            ClipboardMonitor.LocalFilesChanged += files => Peers?.Clipboard.OnLocalFiles(files);
            ClipboardMonitor.LocalImageChanged += png => Peers?.Clipboard.OnLocalImage(png);
            ClipboardMonitor.Start(_hiddenClipboardWindow);

            _trayIcon = new TaskbarIcon { ToolTipText = "AirBridge" };
            _trayIcon.TrayLeftMouseUp += (_, _) => TrayPopupWindow.Toggle();
            _trayIcon.TrayRightMouseUp += (_, _) => TrayPopupWindow.Toggle();
            _trayIcon.TrayMouseDoubleClick += (_, _) => { TrayPopupWindow.CloseIfOpen(); ShowMainWindow(); };
            try
            {
                if (Environment.ProcessPath is string exe) _trayIcon.Icon = System.Drawing.Icon.ExtractAssociatedIcon(exe);
            }
            catch (Exception ex) { Log.Warn($"Tray icon load failed: {ex.Message}"); }

            // 2. Input half.
            try
            {
                Interceptor = new InputInterceptor();
                Interceptor.Start();
            }
            catch (Exception ex) { Log.Error("Input interceptor failed to start", ex); }

            // 3. Shell integration and network: off the UI thread, never blocking startup.
            Task.Run(() =>
            {
                try { ShellIntegration.RegisterIfUnpackaged(); }
                catch (Exception ex) { Log.Error("Shell integration failed", ex); }
            });
            Task.Run(() =>
            {
                try { Peers.Start(); }
                catch (Exception ex)
                {
                    Log.Error("Network start failed", ex);
                    Notify("AirBridge", "Networking failed to start. See %LOCALAPPDATA%\\AirBridge\\airbridge.log");
                }
            });

            // 4. Drop shelf and transfer cards (UI only; they read the network half's transfer board).
            try
            {
                AirBridge.Drop.TransferHud.Start();
                AirBridge.Drop.DropShelf.Start();
            }
            catch (Exception ex) { Log.Error("Drop shelf / transfer cards failed to start", ex); }

            // A tray app sits all day: every few minutes, when nothing is moving, give the memory back.
            _quietTrim = new Timer(_ =>
            {
                try
                {
                    if (Peers == null || Peers.Transfers.Snapshot().Count > 0 || AirBridge.AirWeb.AirWebHost.Session is { ActiveTransfers: > 0 }) return;
                    UiKit.TrimMemory();
                }
                catch { }
            }, null, TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(5));

            if (pendingFiles.Length > 0) ShowSendWindow(pendingFiles);
            else if (!Environment.GetCommandLineArgs().Contains("--background")) ShowMainWindow(); // opened by hand: show it; at login: stay in the tray
        }

        /// <summary>Opens the AirBridge window (any thread), or brings the open one forward.</summary>
        public static void ShowMainWindow()
        {
            Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                var w = Current.Windows.OfType<MainWindow>().FirstOrDefault();
                if (w == null) { w = new MainWindow(); w.Show(); }
                if (w.WindowState == WindowState.Minimized) w.WindowState = WindowState.Normal;
                w.Activate();
            }));
        }

        /// <summary>Packaged share-target activation: hand the files to the main instance and exit.</summary>
        private bool TryHandleShareActivation()
        {
            if (!Win32.IsPackaged) return false;
            global::Windows.ApplicationModel.Activation.IActivatedEventArgs? args;
            try { args = global::Windows.ApplicationModel.AppInstance.GetActivatedEventArgs(); }
            catch (Exception ex) { Log.Warn($"GetActivatedEventArgs failed: {ex.Message}"); return false; }

            if (args?.Kind != global::Windows.ApplicationModel.Activation.ActivationKind.ShareTarget ||
                args is not global::Windows.ApplicationModel.Activation.ShareTargetActivatedEventArgs share)
                return false;

            var op = share.ShareOperation;
            op.ReportStarted();
            Task.Run(async () =>
            {
                try
                {
                    if (op.Data.Contains(global::Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems))
                    {
                        var items = await op.Data.GetStorageItemsAsync();
                        var paths = items.Select(i => i.Path).Where(p => !string.IsNullOrEmpty(p)).ToArray();
                        if (paths.Length > 0 && !IpcManager.HandOffLaunchingIfNeeded(paths))
                            Log.Warn("Share target could not deliver files to the main instance");
                    }
                }
                catch (Exception ex) { Log.Error("Share target failed", ex); }
                finally
                {
                    try { op.ReportCompleted(); } catch { }
                    Environment.Exit(0);
                }
            });
            return true;
        }

        private void InstallExceptionHandlers()
        {
            DispatcherUnhandledException += (_, e) =>
            {
                Log.Error("Unhandled UI exception", e.Exception);
                e.Handled = true; // a tray app should survive a bad click
            };
            AppDomain.CurrentDomain.UnhandledException += (_, e) =>
                Log.Error($"Unhandled exception (terminating: {e.IsTerminating})", e.ExceptionObject as Exception);
            TaskScheduler.UnobservedTaskException += (_, e) =>
            {
                Log.Error("Unobserved task exception", e.Exception);
                e.SetObserved();
            };
        }

        protected override void OnExit(ExitEventArgs e)
        {
            _quietTrim?.Dispose();
            try { AirBridge.AirWeb.AirWebHost.Stop(); } catch { }
            try { Peers?.Dispose(); } catch (Exception ex) { Log.Error("Network shutdown failed", ex); }
            try { Interceptor?.Stop(); } catch (Exception ex) { Log.Error("Interceptor stop failed", ex); }
            ClipboardMonitor?.Stop();
            _ipcCts.Cancel();
            _hiddenClipboardWindow?.Close();
            _trayIcon?.Dispose();
            _singleInstanceMutex?.Dispose();
            base.OnExit(e);
        }

        // ------------------------------------------------------------------ helpers usable from any thread

        /// <summary>Shows a tray balloon (any thread).</summary>
        public static void Notify(string title, string message)
        {
            Log.Info($"Notify: {title} — {message}");
            var app = Current as App;
            app?.Dispatcher.BeginInvoke(new Action(() =>
            {
                try { app._trayIcon?.ShowBalloonTip(title, message, BalloonIcon.Info); } catch { }
            }));
        }

        // ------------------------------------------------------------------ control status (input half → UI)

        private static readonly object ControlGate = new();
        private static string? _controlText;
        private static string? _controlPeer;

        /// <summary>
        /// Sets the one-line control/lease status shown in the tray popup and tray tooltip, e.g.
        /// "Controlling Pixel 9 · Ctrl+Alt+Esc to return", "Controlled by Shazan's MacBook",
        /// "Being controlled by Shazan's MacBook" (after ctl_busy). Pass null (or empty) to clear.
        /// Thread-safe: callable from any thread (hook, network, timer); never blocks; the UI update is
        /// marshalled to the dispatcher. The last call wins. Calls made before startup are kept and applied.
        /// </summary>
        public static void SetControlStatus(string? text) => SetControlStatus(text, null);

        /// <summary>As <see cref="SetControlStatus(string?)"/>, additionally naming the peer id the status refers to
        /// so its row in the device list gets a "control" badge. Optional.</summary>
        public static void SetControlStatus(string? text, string? peerId)
        {
            lock (ControlGate)
            {
                _controlText = string.IsNullOrWhiteSpace(text) ? null : text.Trim();
                _controlPeer = _controlText == null ? null : peerId;
            }
            ApplyControlStatus();
        }

        private static void ApplyControlStatus()
        {
            string? text, peer;
            lock (ControlGate) { text = _controlText; peer = _controlPeer; }
            Peers?.SetControlStatus(text, peer); // raises StateChanged; the tray re-renders on its dispatcher
            var app = Current as App;
            app?.Dispatcher.BeginInvoke(new Action(() =>
            {
                if (app._trayIcon == null) return;
                string tip = text == null ? "AirBridge" : $"AirBridge — {text}";
                app._trayIcon.ToolTipText = tip.Length > 120 ? tip[..120] : tip;
            }));
        }

        // ------------------------------------------------------------------ pairing prompt (host, mode confirm)

        /// <summary>Shows the topmost "wants to pair" prompt for a confirm pairing (any thread).</summary>
        public static void ShowPairPrompt(PairApproval approval)
        {
            var app = Current;
            if (app == null) { approval.Decide(false); return; }
            app.Dispatcher.BeginInvoke(new Action(() =>
            {
                if (approval.Ended.IsCancellationRequested) return;
                var w = new PairPromptWindow(approval);
                w.Show();
                w.Activate();
            }));
        }

        /// <summary>Opens the target picker for files (any thread).</summary>
        public static void ShowSendWindow(string[] files)
        {
            Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                var w = new SendWindow(files);
                w.Show();
                w.Activate();
            }));
        }
    }
}
