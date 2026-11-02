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
        private Window? _hiddenClipboardWindow;
        private readonly CancellationTokenSource _ipcCts = new();

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            InstallExceptionHandlers();
            try
            {
                if (TryHandleShareActivation()) return;

                string[] files = e.Args.Where(a => !string.IsNullOrWhiteSpace(a)).ToArray();
                _singleInstanceMutex = new Mutex(true, IpcManager.MutexName, out bool createdNew);
                if (!createdNew)
                {
                    _singleInstanceMutex.Dispose();
                    _singleInstanceMutex = null;
                    if (files.Length > 0) IpcManager.HandOffLaunchingIfNeeded(files);
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

            _hiddenClipboardWindow = new Window
            {
                Width = 0, Height = 0, WindowStyle = WindowStyle.None, ShowInTaskbar = false, ShowActivated = false,
            };
            ClipboardMonitor = new ClipboardManager();
            ClipboardMonitor.LocalTextChanged += text => Peers?.Clipboard.OnLocalText(text);
            ClipboardMonitor.Start(_hiddenClipboardWindow);

            _trayIcon = new TaskbarIcon
            {
                ToolTipText = "AirBridge",
                PopupActivation = PopupActivationMode.LeftOrRightClick,
            };
            try
            {
                if (Environment.ProcessPath is string exe) _trayIcon.Icon = System.Drawing.Icon.ExtractAssociatedIcon(exe);
            }
            catch (Exception ex) { Log.Warn($"Tray icon load failed: {ex.Message}"); }
            _trayIcon.TrayPopup = new TrayPopupControl();

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

            if (pendingFiles.Length > 0) ShowSendWindow(pendingFiles);
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
