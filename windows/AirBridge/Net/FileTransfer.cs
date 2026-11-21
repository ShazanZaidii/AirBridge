using System;
using System.Buffers;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;

namespace AirBridge.Net
{
    public sealed record TransferResult(bool Ok, string? Error, IReadOnlyList<string> Saved);

    /// <summary>
    /// Per-session file transfer state (§6). Sending uses receiver-driven window flow control; receiving
    /// writes on a dedicated task per transfer through a bounded queue so the receive loop never touches disk.
    /// </summary>
    public sealed class FileTransferSession
    {
        public static readonly string SaveDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "AirBridge Transfers");

        private readonly Session _session;
        private readonly PeerService _svc;
        private readonly ConcurrentDictionary<uint, Incoming> _incoming = new();
        private readonly ConcurrentDictionary<uint, Outgoing> _outgoing = new();
        private int _nextId;
        private readonly string _keyPrefix = Guid.NewGuid().ToString("N")[..8];

        private string InKey(uint id) => $"{_keyPrefix}:in:{id}";
        private string OutKey(uint id) => $"{_keyPrefix}:out:{id}";
        private static string Label(IReadOnlyList<string> names) => names.Count == 1 ? names[0] : $"{names.Count} files";

        public FileTransferSession(Session session, PeerService svc) { _session = session; _svc = svc; }

        /// <summary>Any transfer (either direction) still running on this session.</summary>
        public bool HasActiveTransfers => !_incoming.IsEmpty || !_outgoing.IsEmpty;

        // ================================================================== receiving

        private sealed class InFile
        {
            public required long Size;
            public required string Path;
            public long Written;
            public FileStream? Stream;
            public bool Complete;
        }

        private readonly record struct Work(int FileIndex, long Offset, byte[]? Buffer, int Start, int Length, bool Done);

        private sealed class Incoming
        {
            public required uint Id;
            public required List<InFile> Files;
            public required long Total;
            public readonly Channel<Work> Queue = Channel.CreateBounded<Work>(new BoundedChannelOptions(64) { SingleReader = true });
            public readonly CancellationTokenSource Cts = new();
            public long Received;
            public long LastAck;
            /// <summary>A clipboard transfer (§5.1): kept in the cache folder, quiet, and put on the clipboard when done.</summary>
            public bool Clip;
        }

        public void OnText(string t, JsonObject o)
        {
            long? idL = Protocol.Long(o, "id");
            if (idL is not long idv || idv < 0 || idv > uint.MaxValue) return;
            uint id = (uint)idv;
            switch (t)
            {
                case "xfer_offer": OnOffer(id, o); break;
                case "xfer_done":
                    // Queued behind this transfer's chunks (bounded channel keeps blocked writers FIFO).
                    if (_incoming.TryGetValue(id, out var inc))
                        _ = inc.Queue.Writer.WriteAsync(new Work(0, 0, null, 0, 0, true), inc.Cts.Token).AsTask()
                            .ContinueWith(t => _ = t.Exception, TaskContinuationOptions.OnlyOnFaulted);
                    break;
                case "xfer_cancel":
                    // `dir` is from the peer's point of view: its "out" is our incoming transfer.
                    string? dir = Protocol.Str(o, "dir");
                    if (dir != "in" && _incoming.TryRemove(id, out var c)) CancelIncoming(c, sendCancel: null, message: "Cancelled by the sender");
                    if (dir != "out" && _outgoing.TryGetValue(id, out var og)) og.Fail($"cancelled by peer ({Protocol.Str(o, "reason")})");
                    break;
                case "xfer_accept":
                    if (_outgoing.TryGetValue(id, out var a))
                    {
                        long w = Protocol.Long(o, "window") ?? Protocol.DefaultWindow;
                        a.Window = Math.Clamp(w, Protocol.ChunkSize, 256L * 1024 * 1024);
                        a.Accepted.TrySetResult(null);
                    }
                    break;
                case "xfer_reject":
                    if (_outgoing.TryGetValue(id, out var rj)) rj.Accepted.TrySetResult(Protocol.Str(o, "reason") ?? "rejected");
                    break;
                case "xfer_ack":
                    if (_outgoing.TryGetValue(id, out var ak))
                    {
                        long rcv = Protocol.Long(o, "received") ?? 0;
                        if (rcv > Interlocked.Read(ref ak.Acked)) Interlocked.Exchange(ref ak.Acked, rcv);
                        ak.LastAckAt = Environment.TickCount64;
                        ak.Progress?.Report(ak.Total == 0 ? 1 : Math.Min(1.0, (double)rcv / ak.Total));
                        _svc.Transfers.Progress(OutKey(id), rcv);
                        ak.Signal();
                    }
                    break;
                case "xfer_complete":
                    if (_outgoing.TryGetValue(id, out var cp))
                    {
                        var saved = (o["saved"] as JsonArray)?.Select(n => n?.ToString() ?? "").ToList() ?? new List<string>();
                        cp.Completed.TrySetResult(new TransferResult(Protocol.Bool(o, "ok") ?? false, null, saved));
                    }
                    break;
            }
        }

        private void OnOffer(uint id, JsonObject o)
        {
            if (_incoming.ContainsKey(id)) return;
            var files = new List<(int Index, string Name, long Size)>();
            if (o["files"] is JsonArray arr)
            {
                foreach (var n in arr)
                {
                    if (n is not JsonObject f) continue;
                    long size = Protocol.Long(f, "size") ?? -1;
                    int idx = (int)(Protocol.Long(f, "i") ?? files.Count);
                    if (size < 0) { Reject(id, "user"); return; }
                    files.Add((idx, Protocol.Str(f, "name") ?? "file", size));
                }
            }
            files.Sort((a, b) => a.Index.CompareTo(b.Index));
            if (files.Count == 0 || files.Where((f, i) => f.Index != i).Any()) { Reject(id, "user"); return; }
            long total = files.Sum(f => f.Size);

            bool clip = Protocol.Bool(o, "clip") == true;
            string saveDir = clip ? ClipboardInbox.In : SaveDirectory;
            var reserved = new List<InFile>();
            try
            {
                Directory.CreateDirectory(saveDir);
                try
                {
                    var drive = new DriveInfo(Path.GetPathRoot(saveDir)!);
                    if (drive.AvailableFreeSpace < total + 64L * 1024 * 1024) { Reject(id, "disk"); return; }
                }
                catch { /* unknown free space: try anyway */ }

                foreach (var f in files)
                {
                    string path = ReserveUniquePath(saveDir, SanitizeFileName(f.Name));
                    reserved.Add(new InFile { Size = f.Size, Path = path, Complete = f.Size == 0 });
                }
            }
            catch (Exception ex)
            {
                Log.Error("Cannot prepare incoming transfer", ex);
                foreach (var r in reserved) TryDelete(r.Path);
                Reject(id, "disk");
                return;
            }

            var inc = new Incoming { Id = id, Files = reserved, Total = total, Clip = clip };
            _incoming[id] = inc;
            _ = Task.Run(() => WriterLoopAsync(inc));

            var accept = Protocol.Msg("xfer_accept");
            accept["id"] = id;
            accept["window"] = Protocol.DefaultWindow;
            _session.EnqueueJson(accept);
            if (!clip) // a clipboard transfer is quiet: no card on the board
            {
                _svc.Transfers.Start(InKey(id), _session.PeerName, incoming: true, Label(files.Select(f => SanitizeFileName(f.Name)).ToList()), total,
                                     peerId: _session.PeerId ?? "", fileCount: files.Count);
                _svc.Transfers.RegisterCancel(InKey(id), () =>
                {
                    if (_incoming.TryRemove(id, out var x)) CancelIncoming(x, sendCancel: "user", message: TransferView.CancelledMessage);
                });
            }
            Log.Info($"Receiving {files.Count} file(s), {total} bytes from {_session.PeerName} (id {id})");
        }

        private void Reject(uint id, string reason)
        {
            var m = Protocol.Msg("xfer_reject"); m["id"] = id; m["reason"] = reason;
            _session.EnqueueJson(m);
        }

        /// <summary>Called from the receive loop with a decrypted 0x01 frame at data[start .. start+n);
        /// takes ownership of <paramref name="data"/> (pooled).</summary>
        public async Task OnChunkAsync(byte[] data, int start, int n)
        {
            if (n < Protocol.ChunkHeader) { ArrayPool<byte>.Shared.Return(data); return; }
            uint id = BinaryPrimitives.ReadUInt32BigEndian(data.AsSpan(start + 1, 4));
            uint fi = BinaryPrimitives.ReadUInt32BigEndian(data.AsSpan(start + 5, 4));
            ulong off = BinaryPrimitives.ReadUInt64BigEndian(data.AsSpan(start + 9, 8));
            if (!_incoming.TryGetValue(id, out var inc) || fi >= inc.Files.Count || off > long.MaxValue)
            {
                ArrayPool<byte>.Shared.Return(data); // unknown/cancelled id: ignore
                return;
            }
            try
            {
                await inc.Queue.Writer.WriteAsync(new Work((int)fi, (long)off, data, start + Protocol.ChunkHeader, n - Protocol.ChunkHeader, false), inc.Cts.Token)
                    .ConfigureAwait(false);
            }
            catch
            {
                ArrayPool<byte>.Shared.Return(data);
            }
        }

        private async Task WriterLoopAsync(Incoming inc)
        {
            try
            {
                await foreach (var w in inc.Queue.Reader.ReadAllAsync(inc.Cts.Token).ConfigureAwait(false))
                {
                    if (w.Done) { FinishIncoming(inc); return; }
                    try
                    {
                        var f = inc.Files[w.FileIndex];
                        if (f.Complete || w.Offset + w.Length > f.Size) throw new InvalidDataException("chunk beyond declared size");
                        f.Stream ??= new FileStream(f.Path, FileMode.Open, FileAccess.Write, FileShare.Read, 1 << 16, FileOptions.Asynchronous);
                        if (f.Stream.Position != w.Offset) f.Stream.Position = w.Offset;
                        await f.Stream.WriteAsync(w.Buffer.AsMemory(w.Start, w.Length), inc.Cts.Token).ConfigureAwait(false);
                        f.Written += w.Length;
                        inc.Received += w.Length;
                        _svc.Transfers.Progress(InKey(inc.Id), inc.Received);
                        bool fileDone = w.Offset + w.Length == f.Size;
                        if (fileDone)
                        {
                            await f.Stream.FlushAsync().ConfigureAwait(false);
                            await f.Stream.DisposeAsync().ConfigureAwait(false);
                            f.Stream = null;
                            f.Complete = true;
                        }
                        if (fileDone || inc.Received - inc.LastAck >= Protocol.AckEvery)
                        {
                            inc.LastAck = inc.Received;
                            var ack = Protocol.Msg("xfer_ack"); ack["id"] = inc.Id; ack["received"] = inc.Received;
                            _session.EnqueueJson(ack);
                        }
                    }
                    finally
                    {
                        if (w.Buffer != null) ArrayPool<byte>.Shared.Return(w.Buffer);
                    }
                }
            }
            catch (OperationCanceledException) { /* cancelled: cleanup done by canceller */ }
            catch (Exception ex)
            {
                Log.Error($"Writing transfer {inc.Id} failed", ex);
                if (_incoming.TryRemove(inc.Id, out _)) CancelIncoming(inc, sendCancel: "error");
            }
        }

        private void FinishIncoming(Incoming inc)
        {
            _incoming.TryRemove(inc.Id, out _);
            bool ok = inc.Files.All(f => f.Complete);
            if (!ok)
            {
                Log.Warn($"Transfer {inc.Id} done but incomplete; deleting partial files");
                CleanupFiles(inc, onlyIncomplete: true);
            }
            var complete = Protocol.Msg("xfer_complete");
            complete["id"] = inc.Id;
            complete["ok"] = ok;
            var saved = new JsonArray();
            foreach (var f in inc.Files.Where(f => f.Complete)) saved.Add(Path.GetFileName(f.Path));
            complete["saved"] = saved;
            _session.EnqueueJson(complete);
            inc.Queue.Writer.TryComplete();
            var paths = inc.Files.Where(f => f.Complete).Select(f => f.Path).ToList();
            _svc.Transfers.Finish(InKey(inc.Id), ok, ok ? null : "Incomplete", saved: paths);

            if (paths.Count > 0)
            {
                if (inc.Clip) { if (ok) _svc.OnClipboardReceived(paths); }
                else _svc.OnFilesReceived(_session.PeerName, paths);
            }
        }

        private void CancelIncoming(Incoming inc, string? sendCancel, string? message = null)
        {
            inc.Queue.Writer.TryComplete();
            try { inc.Cts.Cancel(); } catch { }
            _svc.Transfers.Finish(InKey(inc.Id), false, message ?? (sendCancel == null ? "Cancelled" : "Failed"));
            // Drain queued chunks back to the pool.
            while (inc.Queue.Reader.TryRead(out var w)) if (w.Buffer != null) ArrayPool<byte>.Shared.Return(w.Buffer);
            // The writer may still hold a stream briefly; delete after it observes cancellation.
            _ = Task.Run(async () =>
            {
                await Task.Delay(500).ConfigureAwait(false);
                CleanupFiles(inc, onlyIncomplete: true);
            });
            if (sendCancel != null)
            {
                var m = Protocol.Msg("xfer_cancel"); m["id"] = inc.Id; m["dir"] = "in"; m["reason"] = sendCancel;
                _session.EnqueueJson(m);
            }
            Log.Info($"Incoming transfer {inc.Id} cancelled");
        }

        private static void CleanupFiles(Incoming inc, bool onlyIncomplete)
        {
            foreach (var f in inc.Files)
            {
                if (onlyIncomplete && f.Complete) continue;
                try { f.Stream?.Dispose(); } catch { }
                f.Stream = null;
                TryDelete(f.Path);
            }
        }

        private static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch (Exception ex) { Log.Warn($"Could not delete {path}: {ex.Message}"); }
        }

        // ================================================================== sending

        private sealed class Outgoing
        {
            public required uint Id;
            public required long Total;
            public IProgress<double>? Progress;
            public long Window = Protocol.DefaultWindow;
            public long Acked;
            public long LastAckAt = Environment.TickCount64;
            public readonly TaskCompletionSource<string?> Accepted = new(TaskCreationOptions.RunContinuationsAsynchronously);
            public readonly TaskCompletionSource<TransferResult> Completed = new(TaskCreationOptions.RunContinuationsAsynchronously);
            public readonly CancellationTokenSource Cts = new();
            private readonly SemaphoreSlim _ackSignal = new(0);
            public string? FailReason;

            public void Signal() { if (_ackSignal.CurrentCount == 0) _ackSignal.Release(); }
            public Task<bool> WaitAckAsync(TimeSpan t, CancellationToken ct) => _ackSignal.WaitAsync(t, ct);

            public void Fail(string reason)
            {
                FailReason ??= reason;
                Accepted.TrySetResult(reason);
                Completed.TrySetResult(new TransferResult(false, reason, Array.Empty<string>()));
                try { Cts.Cancel(); } catch { }
            }
        }

        public async Task<TransferResult> SendAsync(IReadOnlyList<string> paths, IProgress<double>? progress, CancellationToken callerCt, bool clip = false)
        {
            // The caller's token, plus Cancel on a transfer card.
            using var userCts = CancellationTokenSource.CreateLinkedTokenSource(callerCt);
            var userCt = userCts.Token;
            var files = new List<(string Path, string Name, long Size)>();
            foreach (var p in paths)
            {
                try
                {
                    var fi = new FileInfo(p);
                    if (fi.Exists) files.Add((fi.FullName, fi.Name, fi.Length));
                }
                catch { }
            }
            if (files.Count == 0) return new TransferResult(false, "No readable files", Array.Empty<string>());

            uint id = (uint)Interlocked.Increment(ref _nextId);
            var og = new Outgoing { Id = id, Total = files.Sum(f => f.Size), Progress = progress };
            _outgoing[id] = og;
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(userCt, og.Cts.Token);
            var ct = linked.Token;
            try
            {
                var offer = Protocol.Msg("xfer_offer");
                offer["id"] = id;
                var arr = new JsonArray();
                for (int i = 0; i < files.Count; i++)
                    arr.Add(new JsonObject { ["i"] = i, ["name"] = files[i].Name, ["size"] = files[i].Size, ["mime"] = MimeFor(files[i].Name) });
                offer["files"] = arr;
                offer["total"] = og.Total;
                if (clip) offer["clip"] = true; // §5.1
                if (!_session.EnqueueJson(offer)) return new TransferResult(false, "Disconnected", Array.Empty<string>());
                if (!clip) // a clipboard transfer is quiet: no card on the board
                {
                    _svc.Transfers.Start(OutKey(id), _session.PeerName, incoming: false, Label(files.Select(f => f.Name).ToList()), og.Total,
                                         peerId: _session.PeerId ?? "", sources: files.Select(f => f.Path).ToList(), fileCount: files.Count, waiting: true);
                    _svc.Transfers.RegisterCancel(OutKey(id), () => { try { userCts.Cancel(); } catch (ObjectDisposedException) { } });
                }

                var acceptTask = og.Accepted.Task;
                if (await Task.WhenAny(acceptTask, Task.Delay(TimeSpan.FromSeconds(30), ct)).ConfigureAwait(false) != acceptTask)
                    throw new TimeoutException("Receiver did not answer the offer");
                string? rejected = await acceptTask.ConfigureAwait(false);
                if (rejected != null)
                {
                    _svc.Transfers.Finish(OutKey(id), false, "Rejected");
                    return new TransferResult(false, og.FailReason ?? $"Rejected ({rejected})", Array.Empty<string>());
                }
                _svc.Transfers.MarkAccepted(OutKey(id));

                long sent = 0;
                for (int i = 0; i < files.Count; i++)
                {
                    var (path, _, size) = files[i];
                    if (size == 0) continue;
                    await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 16, FileOptions.Asynchronous | FileOptions.SequentialScan);
                    long offset = 0;
                    while (offset < size)
                    {
                        int want = (int)Math.Min(Protocol.ChunkSize, size - offset);
                        // Flow control: never more than `window` unacknowledged bytes in flight.
                        while (sent + want - Interlocked.Read(ref og.Acked) > og.Window)
                        {
                            ct.ThrowIfCancellationRequested();
                            if (!await og.WaitAckAsync(TimeSpan.FromSeconds(1), ct).ConfigureAwait(false)
                                && Environment.TickCount64 - og.LastAckAt > 30_000)
                                throw new TimeoutException("Receiver stopped acknowledging");
                        }
                        byte[] buf = ArrayPool<byte>.Shared.Rent(Protocol.ChunkHeader + want);
                        int read = 0;
                        try
                        {
                            while (read < want)
                            {
                                int r = await fs.ReadAsync(buf.AsMemory(Protocol.ChunkHeader + read, want - read), ct).ConfigureAwait(false);
                                if (r == 0) throw new IOException($"{path} shrank while sending");
                                read += r;
                            }
                            Protocol.WriteChunkHeader(buf, id, (uint)i, (ulong)offset);
                        }
                        catch
                        {
                            ArrayPool<byte>.Shared.Return(buf);
                            throw;
                        }
                        if (sent == 0) og.LastAckAt = Environment.TickCount64;
                        await _session.SendBinaryAsync(buf, Protocol.ChunkHeader + want, pooled: true, ct).ConfigureAwait(false);
                        offset += want;
                        sent += want;
                    }
                }

                var done = Protocol.Msg("xfer_done"); done["id"] = id;
                _session.EnqueueJson(done);

                // Wait for xfer_complete; keep waiting while acks are still progressing.
                while (true)
                {
                    var ctask = og.Completed.Task;
                    if (await Task.WhenAny(ctask, Task.Delay(TimeSpan.FromSeconds(5), ct)).ConfigureAwait(false) == ctask)
                    {
                        var res = await ctask.ConfigureAwait(false);
                        if (res.Ok) progress?.Report(1.0);
                        _svc.Transfers.Finish(OutKey(id), res.Ok, res.Error);
                        return res;
                    }
                    if (Environment.TickCount64 - og.LastAckAt > 60_000) throw new TimeoutException("No completion from receiver");
                }
            }
            catch (Exception ex) when (ex is OperationCanceledException or TimeoutException or IOException or UnauthorizedAccessException
                                           or System.Net.WebSockets.WebSocketException)
            {
                string reason = og.FailReason ?? (userCt.IsCancellationRequested ? "Cancelled" : ex.Message);
                if (og.FailReason == null && _session.IsReady)
                {
                    var c = Protocol.Msg("xfer_cancel"); c["id"] = id; c["dir"] = "out";
                    c["reason"] = userCt.IsCancellationRequested ? "user" : "error";
                    _session.EnqueueJson(c);
                }
                Log.Warn($"Send {id} to {_session.PeerName} failed: {reason}");
                _svc.Transfers.Finish(OutKey(id), false, reason);
                return new TransferResult(false, reason, Array.Empty<string>());
            }
            finally
            {
                _outgoing.TryRemove(id, out _);
                _svc.Transfers.Finish(OutKey(id), false, "Failed"); // no-op if already finished
            }
        }

        /// <summary>Session gone: delete partial incoming files, fail outgoing sends.</summary>
        public void OnSessionLost()
        {
            foreach (var id in _incoming.Keys.ToList())
                if (_incoming.TryRemove(id, out var inc)) CancelIncoming(inc, sendCancel: null, message: "Disconnected");
            foreach (var og in _outgoing.Values) og.Fail("Disconnected");
        }

        // ================================================================== helpers

        private static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
        {
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        };

        /// <summary>§6.3 sanitisation, plus characters Windows forbids in file names.</summary>
        public static string SanitizeFileName(string? raw)
        {
            string name = raw ?? "";
            int slash = Math.Max(name.LastIndexOf('/'), name.LastIndexOf('\\'));
            if (slash >= 0) name = name[(slash + 1)..];

            var sb = new StringBuilder(name.Length);
            foreach (char c in name)
            {
                if (c == '\0' || c == '/' || c == '\\' || char.IsControl(c)) continue;
                sb.Append(c is '<' or '>' or ':' or '"' or '|' or '?' or '*' ? '_' : c);
            }
            name = sb.ToString().TrimStart('.', ' ').TrimEnd('.', ' ');

            string stem = Path.GetFileNameWithoutExtension(name);
            if (Reserved.Contains(stem) || Reserved.Contains(name)) name = "_" + name;

            if (Encoding.UTF8.GetByteCount(name) > 200)
            {
                string ext = Path.GetExtension(name);
                if (Encoding.UTF8.GetByteCount(ext) > 20) ext = "";
                string baseName = name[..^ext.Length];
                while (baseName.Length > 0 && Encoding.UTF8.GetByteCount(baseName + ext) > 200)
                    baseName = baseName[..^1];
                if (baseName.Length > 0 && char.IsHighSurrogate(baseName[^1])) baseName = baseName[..^1];
                name = baseName.TrimEnd('.', ' ') + ext;
            }
            return string.IsNullOrWhiteSpace(name) ? "file" : name;
        }

        /// <summary>Atomically creates an empty file with a unique name ("x (2).ext" …) and returns its path.</summary>
        private static string ReserveUniquePath(string dir, string name)
        {
            string stem = Path.GetFileNameWithoutExtension(name);
            string ext = Path.GetExtension(name);
            for (int n = 1; n < 10_000; n++)
            {
                string candidate = Path.Combine(dir, n == 1 ? name : $"{stem} ({n}){ext}");
                try
                {
                    using (new FileStream(candidate, FileMode.CreateNew, FileAccess.Write, FileShare.None)) { }
                    return candidate;
                }
                catch (IOException) when (File.Exists(candidate) || Directory.Exists(candidate)) { }
            }
            throw new IOException("Could not find a free file name");
        }

        private static string MimeFor(string name) => Path.GetExtension(name).ToLowerInvariant() switch
        {
            ".jpg" or ".jpeg" => "image/jpeg",
            ".png" => "image/png",
            ".gif" => "image/gif",
            ".heic" => "image/heic",
            ".webp" => "image/webp",
            ".mp4" => "video/mp4",
            ".mov" => "video/quicktime",
            ".mp3" => "audio/mpeg",
            ".pdf" => "application/pdf",
            ".txt" => "text/plain",
            ".zip" => "application/zip",
            ".apk" => "application/vnd.android.package-archive",
            _ => "application/octet-stream",
        };
    }
}
