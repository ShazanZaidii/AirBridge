using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Security.Cryptography;
using System.Text;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;

namespace AirBridge.Net
{
    /// <summary>
    /// Clipboard protocol logic (§5, §5.1). Local changes come from <see cref="ClipboardManager"/> on the STA thread;
    /// remote clips arrive on network threads and are applied on the dispatcher. No relaying.
    /// </summary>
    public sealed class ClipboardSync
    {
        private readonly PeerService _svc;
        private readonly object _gate = new();
        private byte[]? _lastSetByUs;
        /// <summary>Hash of the picture or file list this PC last sent: the same copy is not sent twice in a row.</summary>
        private byte[]? _lastMediaSent;
        /// <summary>Until this tick, a change to the clipboard is our own doing (we just put received media on it).</summary>
        private long _ownChangeUntil;
        private void MarkOwnChange() => Interlocked.Exchange(ref _ownChangeUntil, Environment.TickCount64 + 2000);
        private bool IsOwnChange() => Environment.TickCount64 < Interlocked.Read(ref _ownChangeUntil);

        public ClipboardSync(PeerService svc) { _svc = svc; }

        private static byte[] Hash(string text) => SHA256.HashData(Encoding.UTF8.GetBytes(text));

        /// <summary>STA: the local clipboard now contains <paramref name="text"/>.</summary>
        public void OnLocalText(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            lock (_gate)
            {
                // Echo suppression: this is the content we just set from the network.
                if (_lastSetByUs != null && Protocol.MacEquals(_lastSetByUs, Hash(text))) return;
            }
            if (ClipboardManager.ShouldIgnore(text)) return;
            if (Encoding.UTF8.GetByteCount(text) > Protocol.MaxClipBytes)
            {
                App.Notify("Clipboard not synced", "The copied text is larger than 512 KiB.");
                return;
            }
            var msg = Protocol.Msg("clip");
            msg["kind"] = "text";
            msg["text"] = text;
            msg["ts"] = Protocol.NowMs();
            string json = Protocol.Serialize(msg);
            foreach (var s in _svc.ReadySessions())
                if (s.PeerCaps.Contains("clip")) s.EnqueueText(json);
        }

        /// <summary>Network thread: a peer sent a clip.</summary>
        public void OnRemoteClip(Session from, JsonObject o)
        {
            if (Protocol.Str(o, "kind") != "text") return; // image reserved
            string? text = Protocol.Str(o, "text");
            if (string.IsNullOrEmpty(text) || Encoding.UTF8.GetByteCount(text) > Protocol.MaxClipBytes) return;

            var app = Application.Current;
            if (app == null) return;
            app.Dispatcher.BeginInvoke(new Action(() =>
            {
                lock (_gate) _lastSetByUs = Hash(text);
                if (!ClipboardManager.TrySetText(text)) Log.Warn($"Could not set clipboard from {from.PeerName}");
            }));
        }

        // ---- images and files (docs/protocol.md 5.1)

        /// <summary>STA: the clipboard now holds files.</summary>
        public void OnLocalFiles(IReadOnlyList<string> paths)
        {
            if (IsOwnChange() || !HasMediaPeer()) return;
            var files = new List<string>(); var sizes = new List<long>(); int folders = 0;
            foreach (var p in paths)
            {
                try
                {
                    if (File.Exists(p)) { files.Add(p); sizes.Add(new FileInfo(p).Length); }
                    else if (Directory.Exists(p)) folders++;
                }
                catch { }
            }
            if (files.Count == 0)
            {
                if (folders > 0) App.Notify("Clipboard not synced", "Folders can't go through the clipboard yet. Send them with AirBridge instead.");
                return;
            }
            if (!ClipboardMedia.Allowed(sizes, image: false))
            {
                App.Notify("Clipboard not synced", "The copied files are too big to sync (over 100 MB or 20 files).");
                return;
            }
            var key = SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("\n", files.Select(f => $"{f}|{new FileInfo(f).Length}|{File.GetLastWriteTimeUtc(f).Ticks}"))));
            if (!NewMedia(key)) return;
            SendMedia(files, cleanup: null);
        }

        /// <summary>STA: the clipboard now holds a picture (PNG bytes).</summary>
        public void OnLocalImage(byte[] png)
        {
            if (IsOwnChange() || !HasMediaPeer()) return;
            if (!ClipboardMedia.Allowed(new List<long> { png.LongLength }, image: true))
            {
                App.Notify("Clipboard not synced", "That image is too big to sync (over 25 MB).");
                return;
            }
            if (!NewMedia(SHA256.HashData(png))) return;
            string? file = ClipboardInbox.WriteOutFile("clipboard.png", png);
            if (file == null) return;
            SendMedia(new List<string> { file }, cleanup: () => ClipboardInbox.Discard(file));
        }

        private bool HasMediaPeer() => _svc.ReadySessions().Any(s => s.PeerCaps.Contains("clip_media"));

        private bool NewMedia(byte[] key)
        {
            lock (_gate)
            {
                if (_lastMediaSent != null && Protocol.MacEquals(_lastMediaSent, key)) return false;
                _lastMediaSent = key;
                return true;
            }
        }

        /// <summary>Sends <paramref name="files"/> as a clipboard transfer to every ready peer that can take one; runs
        /// <paramref name="cleanup"/> once the last of those transfers has ended.</summary>
        private void SendMedia(IReadOnlyList<string> files, Action? cleanup)
        {
            var targets = _svc.ReadySessions().Where(s => s.PeerCaps.Contains("clip_media")).ToList();
            if (targets.Count == 0) { cleanup?.Invoke(); return; }
            int left = targets.Count;
            foreach (var s in targets)
            {
                _ = Task.Run(async () =>
                {
                    try { await s.Files.SendAsync(files, null, CancellationToken.None, clip: true).ConfigureAwait(false); }
                    catch (Exception ex) { Log.Warn($"Clipboard transfer to {s.PeerName} failed: {ex.Message}"); }
                    finally { if (Interlocked.Decrement(ref left) == 0) cleanup?.Invoke(); }
                });
            }
        }

        /// <summary>A clipboard transfer from another device finished (any thread): one picture becomes an image on the
        /// clipboard, anything else a file list. No notice, nothing in Downloads.</summary>
        public void OnReceivedMedia(IReadOnlyList<string> paths)
        {
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                try
                {
                    ClipboardInbox.PurgeOld();
                    MarkOwnChange();
                    if (paths.Count == 1 && ClipboardMedia.IsImageFile(paths[0]) && ClipboardManager.TrySetImageFile(paths[0])) return;
                    var sc = new StringCollection();
                    foreach (var p in paths) sc.Add(p);
                    ClipboardManager.TrySetFiles(sc);
                }
                catch (Exception ex) { Log.Warn($"Clipboard media failed: {ex.Message}"); }
            }));
        }

        /// <summary>Puts received files on the clipboard (any thread).</summary>
        public void SetFileDropList(IReadOnlyList<string> paths)
        {
            MarkOwnChange(); // a normal received file must not be sent straight back as a clipboard copy
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                var sc = new StringCollection();
                foreach (var p in paths) sc.Add(p);
                ClipboardManager.TrySetFiles(sc);
            }));
        }
    }
}
