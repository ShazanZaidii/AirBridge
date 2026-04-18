using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Threading.Tasks;
using System.Timers;
using System.Windows;

namespace AirBridge
{
    public static class IpcManager
    {
        private const string PipeName = "AirBridge_File_Pipe";
        private static List<string> _fileBuffer = new List<string>();
        private static System.Timers.Timer _batchTimer;

        static IpcManager()
        {
            // 200ms window to catch all incoming files from multi-process context menus
            _batchTimer = new System.Timers.Timer(200);
            _batchTimer.AutoReset = false;
            _batchTimer.Elapsed += FlushBuffer;
        }

        private static void FlushBuffer(object sender, ElapsedEventArgs e)
        {
            string[] filesToProcess;
            lock (_fileBuffer)
            {
                filesToProcess = _fileBuffer.Distinct().ToArray();
                _fileBuffer.Clear();
            }

            if (filesToProcess.Length > 0)
            {
                Application.Current.Dispatcher.BeginInvoke(new Action(() =>
                {
                    var sendWindow = new SendWindow(filesToProcess);
                    sendWindow.Show();
                    sendWindow.Activate();
                }));
            }
        }

        public static async Task StartServerAsync()
        {
            while (true)
            {
                try
                {
                    using (var server = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Message, PipeOptions.Asynchronous))
                    {
                        await server.WaitForConnectionAsync();
                        using (var reader = new StreamReader(server))
                        {
                            string payload = await reader.ReadLineAsync();

                            if (!string.IsNullOrEmpty(payload))
                            {
                                string[] filePaths = payload.Split('|');

                                // Toss into the buffer and reset the countdown timer
                                lock (_fileBuffer)
                                {
                                    _fileBuffer.AddRange(filePaths);
                                }

                                _batchTimer.Stop();
                                _batchTimer.Start();
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"IPC Server Error: {ex.Message}");
                }
            }
        }

        public static bool TrySendToBackgroundInstance(string payload)
        {
            try
            {
                using (var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out))
                {
                    client.Connect(500);
                    using (var writer = new StreamWriter(client))
                    {
                        writer.WriteLine(payload);
                        writer.Flush();
                    }
                    return true;
                }
            }
            catch
            {
                return false;
            }
        }
    }
}