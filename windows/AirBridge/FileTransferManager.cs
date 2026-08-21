using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Diagnostics;
using System.IO;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;

namespace AirBridge
{
    public enum TransferTarget { Mac, Phone }

    public class FileTransferManager
    {
        private string _saveDirectory;
        private FileStream _currentFileStream;
        private bool _isReceivingFile = false;
        private string _currentReceivingFileName;

        private int _totalFilesExpected = 0;
        private int _filesReceived = 0;

        // --- NEW: Track bytes to auto-complete the batch if network drops text frames ---
        private long _totalBytesExpected = 0;
        private long _bytesReceived = 0;

        private StringCollection _receivedFilePaths;

        public FileTransferManager()
        {
            string downloadsPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            _saveDirectory = Path.Combine(downloadsPath, "AirBridge Transfers");

            if (!Directory.Exists(_saveDirectory))
            {
                Directory.CreateDirectory(_saveDirectory);
            }
        }

        public void HandleCommand(string command)
        {
            string[] parts = command.Split(':');
            if (parts.Length == 0) return;

            switch (parts[0])
            {
                case "BATCH_START":
                    if (parts.Length >= 3)
                    {
                        _totalFilesExpected = int.Parse(parts[1]);
                        _totalBytesExpected = long.Parse(parts[2]); // Grab total expected bytes
                        _bytesReceived = 0;
                        _filesReceived = 0;
                        _receivedFilePaths = new StringCollection();
                        Debug.WriteLine($"Starting batch download: {_totalFilesExpected} files, {_totalBytesExpected} bytes");
                    }
                    break;

                case "START_FILE":
                    if (parts.Length >= 2)
                    {
                        // 1. IMPLICIT END-OF-FILE: If already receiving, the previous END_FILE was dropped!
                        if (_isReceivingFile)
                        {
                            _isReceivingFile = false;
                            _currentFileStream?.Dispose();
                            _currentFileStream = null;
                            _filesReceived++;
                        }

                        string fileName = string.Join(":", parts, 1, parts.Length - 1);
                        fileName = Regex.Replace(fileName, "^[A-Fa-f0-9]{6}_", "");
                        fileName = Path.GetFileName(fileName);
                        _currentReceivingFileName = fileName;

                        string fullPath = Path.Combine(_saveDirectory, fileName);
                        fullPath = GetUniqueFilePath(fullPath);

                        _currentFileStream = new FileStream(fullPath, FileMode.Create, FileAccess.Write, FileShare.None);
                        _isReceivingFile = true;
                        _receivedFilePaths.Add(fullPath);
                    }
                    break;

                case "END_FILE":
                case "ERROR_FILE":
                    if (_isReceivingFile)
                    {
                        _isReceivingFile = false;
                        _currentFileStream?.Dispose();
                        _currentFileStream = null;

                        if (parts[0] == "ERROR_FILE" && _receivedFilePaths.Count > 0)
                        {
                            string failedPath = _receivedFilePaths[_receivedFilePaths.Count - 1];
                            _receivedFilePaths.RemoveAt(_receivedFilePaths.Count - 1);
                            if (File.Exists(failedPath)) File.Delete(failedPath);
                        }
                        else
                        {
                            _filesReceived++;
                        }

                        CheckBatchCompletion(force: false);
                    }
                    break;

                case "BATCH_COMPLETE":
                    CheckBatchCompletion(force: true);
                    break;
            }
        }

        private void CheckBatchCompletion(bool force)
        {
            if ((_filesReceived >= _totalFilesExpected && _totalFilesExpected > 0) || force)
            {
                Application.Current.Dispatcher.Invoke(() =>
                {
                    if (_receivedFilePaths != null && _receivedFilePaths.Count > 0)
                    {
                        App.ClipboardSync?.LockNetworkEcho(true);
                        Clipboard.SetFileDropList(_receivedFilePaths);
                        Task.Delay(500).ContinueWith(_ => App.ClipboardSync?.LockNetworkEcho(false));
                        System.Media.SystemSounds.Exclamation.Play();
                    }
                });
                _totalFilesExpected = 0;
                _filesReceived = 0;
                _totalBytesExpected = 0;
                _bytesReceived = 0;
            }
        }

        public void HandleBinaryChunk(byte[] data)
        {
            if (_isReceivingFile && _currentFileStream != null)
            {
                _currentFileStream.Write(data, 0, data.Length);
                _bytesReceived += data.Length;

                // 2. AUTO-COMPLETE BY BYTE COUNT: Force close the batch when all bytes arrive
                if (_bytesReceived >= _totalBytesExpected && _totalBytesExpected > 0)
                {
                    _isReceivingFile = false;
                    _currentFileStream?.Dispose();
                    _currentFileStream = null;

                    _filesReceived = _totalFilesExpected; // Force math to 100%
                    CheckBatchCompletion(force: true);
                }
            }
        }

        // Targeted network routing
        public async Task SendFilesAsync(string[] filePaths, TransferTarget target)
        {
            var validFiles = new List<string>();
            long totalBytes = 0;

            foreach (string path in filePaths)
            {
                if (File.Exists(path))
                {
                    validFiles.Add(path);
                    totalBytes += new FileInfo(path).Length;
                }
            }

            if (validFiles.Count == 0) return;

            await SendTextAsync($"BATCH_START:{validFiles.Count}:{totalBytes}", target);

            foreach (string path in validFiles)
            {
                string fileName = Path.GetFileName(path);
                await SendTextAsync($"START_FILE:{fileName}", target);

                try
                {
                    using (FileStream fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
                    {
                        byte[] buffer = new byte[64 * 1024];
                        int bytesRead;

                        while ((bytesRead = await fs.ReadAsync(buffer, 0, buffer.Length)) > 0)
                        {
                            byte[] exactChunk = new byte[bytesRead];
                            Buffer.BlockCopy(buffer, 0, exactChunk, 0, bytesRead);

                            // Send explicitly to the selected device
                            if (target == TransferTarget.Phone && App.Server != null)
                                await App.Server.SendBinaryAsync(exactChunk);

                            if (target == TransferTarget.Mac && App.Client != null)
                                await App.Client.SendBinaryAsync(exactChunk);
                        }
                    }
                    await SendTextAsync($"END_FILE:{fileName}", target);
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"Error reading {fileName}: {ex.Message}");
                    await SendTextAsync($"ERROR_FILE:{fileName}", target);
                }

                await Task.Delay(20);
            }

            await SendTextAsync("BATCH_COMPLETE", target);
        }

        private async Task SendTextAsync(string cmd, TransferTarget target)
        {
            if (target == TransferTarget.Phone && App.Server != null) await App.Server.SendAsync(cmd);
            if (target == TransferTarget.Mac && App.Client != null) await App.Client.SendAsync(cmd);
        }

        private string GetUniqueFilePath(string fullPath)
        {
            int count = 1;
            string fileNameOnly = Path.GetFileNameWithoutExtension(fullPath);
            string extension = Path.GetExtension(fullPath);
            string path = Path.GetDirectoryName(fullPath);
            string newFullPath = fullPath;

            while (File.Exists(newFullPath))
            {
                newFullPath = Path.Combine(path, $"{fileNameOnly} ({count++}){extension}");
            }
            return newFullPath;
        }
    }
}