using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Net.NetworkInformation;

namespace AirBridge
{
    public partial class App : Application
    {
        private static Mutex _singleInstanceMutex;

        public static SyncClient Client { get; private set; }
        public static SyncServer Server { get; private set; }
        public static InputInterceptor Interceptor { get; private set; }
        public static ClipboardManager ClipboardSync { get; private set; }
        public static FileTransferManager FileTransfer { get; private set; }

        private Hardcodet.Wpf.TaskbarNotification.TaskbarIcon _trayIcon;
        private Window _hiddenClipboardWindow;

        protected override async void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            // 1. Intercept Windows 11 Share Target Activation
            var activationArgs = global::Windows.ApplicationModel.AppInstance.GetActivatedEventArgs();

            if (activationArgs != null && activationArgs.Kind == global::Windows.ApplicationModel.Activation.ActivationKind.ShareTarget)
            {
                var shareArgs = activationArgs as global::Windows.ApplicationModel.Activation.ShareTargetActivatedEventArgs;
                if (shareArgs != null)
                {
                    var shareOperation = shareArgs.ShareOperation;

                    // Instantly tell Windows "I have it" so it drops the UI loading spinner
                    shareOperation.ReportStarted();

                    // Push the heavy file brokering to a background thread
                    Task.Run(async () =>
                    {
                        try
                        {
                            if (shareOperation.Data.Contains(global::Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems))
                            {
                                var items = await shareOperation.Data.GetStorageItemsAsync();

                                // HARD LIMIT: Cap at 100 to prevent network choking
                                int limit = Math.Min(items.Count, 100);
                                string[] filePaths = new string[limit];

                                for (int i = 0; i < limit; i++)
                                {
                                    filePaths[i] = items[i].Path;
                                }

                                string droppedFiles = string.Join("|", filePaths);
                                IpcManager.TrySendToBackgroundInstance(droppedFiles);
                            }
                        }
                        finally
                        {
                            // Cleanly terminate the Share Pane and kill the proxy app
                            shareOperation.ReportCompleted();
                            System.Environment.Exit(0);
                        }
                    });
                }

                // CRITICAL: Stop standard WPF initialization for this temporary instance
                return;
            }

            // 2. Standard Single Instance logic for Right-Click Context Menus
            if (e.Args.Length > 0)
            {
                var filesToSend = e.Args;

                if (filesToSend.Length > 100)
                {
                    MessageBox.Show("AirBridge limits transfers to 100 files per batch to maintain network stability. Only the first 100 files will be sent.", "Transfer Limit Reached", MessageBoxButton.OK, MessageBoxImage.Warning);
                    filesToSend = filesToSend.Take(100).ToArray();
                }

                string droppedFiles = string.Join("|", filesToSend);
                if (IpcManager.TrySendToBackgroundInstance(droppedFiles))
                {
                    System.Environment.Exit(0);
                    return;
                }
            }

            _singleInstanceMutex = new Mutex(true, "AirBridge_Single_Instance_Mutex", out bool createdNew);
            if (!createdNew)
            {
                System.Environment.Exit(0);
                return;
            }

            // 3. Start the continuous Background Pipe listener
            _ = IpcManager.StartServerAsync();

            FileTransfer = new FileTransferManager();

            //Testing:
            // These fire instantly the millisecond your Wi-Fi or Hotspot drops
            NetworkChange.NetworkAvailabilityChanged += (s, ev) => Application.Current.Dispatcher.Invoke(() => Interceptor?.ForceReturnToWindows());
            NetworkChange.NetworkAddressChanged += (s, ev) => Application.Current.Dispatcher.Invoke(() => Interceptor?.ForceReturnToWindows());


            Client = new SyncClient();
            Client.OnCommandReceived += HandleIncomingCommand;
            Client.OnBinaryReceived += FileTransfer.HandleBinaryChunk;
            Client.OnDisconnected += () => Application.Current.Dispatcher.Invoke(() => Interceptor?.ForceReturnToWindows());
            await Client.DiscoverAndConnectAsync();

            // 4. Initialize Backend First
            Server = new SyncServer();
            Server.OnCommandReceived += HandleIncomingCommand;
            Server.OnBinaryReceived += FileTransfer.HandleBinaryChunk;
            
            Server.OnClientDisconnected += () => Application.Current.Dispatcher.Invoke(() => Interceptor?.ForceReturnToWindows());
            await Server.StartAsync(8080);

            Interceptor = new InputInterceptor();
            Interceptor.Start();

            // 5. Initialize Clipboard hooks
            _hiddenClipboardWindow = new Window
            {
                Width = 0,
                Height = 0,
                WindowStyle = WindowStyle.None,
                ShowInTaskbar = false,
                ShowActivated = false
            };
            _hiddenClipboardWindow.Show();
            _hiddenClipboardWindow.Hide();

            ClipboardSync = new ClipboardManager();
            ClipboardSync.Start(_hiddenClipboardWindow);

            // 6. Register Classic Context Menus
            RegisterContextMenu();

            // 7. Build the UI Second
            _trayIcon = new Hardcodet.Wpf.TaskbarNotification.TaskbarIcon();
            _trayIcon.Icon = System.Drawing.Icon.ExtractAssociatedIcon(System.Reflection.Assembly.GetExecutingAssembly().Location);
            _trayIcon.ToolTipText = "AirBridge";
            _trayIcon.PopupActivation = Hardcodet.Wpf.TaskbarNotification.PopupActivationMode.LeftOrRightClick;

            _trayIcon.TrayPopup = new TrayPopupControl();
        }

        protected override void OnExit(ExitEventArgs e)
        {
            Interceptor?.Stop();
            ClipboardSync?.Stop();
            _hiddenClipboardWindow?.Close();
            _trayIcon?.Dispose();
            _singleInstanceMutex?.ReleaseMutex();
            base.OnExit(e);
        }

        private void RegisterContextMenu()
        {
            try
            {
                string exePath = Environment.ProcessPath;
                bool registryUpdated = false;

                // Construct the absolute path to the MSIX App Execution Alias
                string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                string aliasPath = System.IO.Path.Combine(localAppData, "Microsoft", "WindowsApps", "AirBridgeRun.exe");

                // Register for Files
                using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\AirBridge"))
                {
                    key.SetValue("", "Send via AirBridge");
                    key.SetValue("Icon", $"\"{exePath}\"");
                    key.SetValue("MultiSelectModel", "Player");

                    using (var commandKey = key.CreateSubKey("command"))
                    {
                        // Write the absolute path to the Alias
                        commandKey.SetValue("", $"\"{aliasPath}\" \"%1\"");
                    }
                    registryUpdated = true;
                }

                // Register for Folders
                using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(@"Software\Classes\Directory\shell\AirBridge"))
                {
                    key.SetValue("", "Send via AirBridge");
                    key.SetValue("Icon", $"\"{exePath}\",0");
                    key.SetValue("MultiSelectModel", "Player");

                    using (var commandKey = key.CreateSubKey("command"))
                    {
                        // Write the absolute path to the Alias
                        commandKey.SetValue("", $"\"{aliasPath}\" \"%1\"");
                    }
                    registryUpdated = true;
                }

                // SendTo Shortcut creation (Keep using exePath for shortcuts)
                string sendToPath = Environment.GetFolderPath(Environment.SpecialFolder.SendTo);
                string shortcutPath = System.IO.Path.Combine(sendToPath, "AirBridge.lnk");

                if (!System.IO.File.Exists(shortcutPath))
                {
                    string psCommand = $"$s=(New-Object -COM WScript.Shell).CreateShortcut('{shortcutPath}');$s.TargetPath='{exePath}';$s.Save()";
                    var processInfo = new System.Diagnostics.ProcessStartInfo("powershell.exe", $"-NoProfile -WindowStyle Hidden -Command \"{psCommand}\"")
                    {
                        CreateNoWindow = true,
                        UseShellExecute = false
                    };
                    System.Diagnostics.Process.Start(processInfo);
                }

                if (registryUpdated)
                {
                    NativeMethods.SHChangeNotify(0x08000000, 0x0000, IntPtr.Zero, IntPtr.Zero);
                }
            }
            catch (System.Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to register menus: {ex.Message}");
            }
        }

        private void HandleIncomingCommand(string command)
        {
            if (command.StartsWith("BATCH_START:") || command.StartsWith("START_FILE:") || command.StartsWith("END_FILE:"))
            {
                FileTransfer.HandleCommand(command);
            }
            else if (command.StartsWith("INP:"))
            {
                // Actually release the cursor back to Windows!
                if (command == "INP:TRANSITION:MAC")
                {
                    Application.Current.Dispatcher.Invoke(() => Interceptor?.ForceReturnToWindows());
                }
            }
            else
            {
                ClipboardSync?.SetClipboardFromNetwork(command);
            }
        }
    }
}