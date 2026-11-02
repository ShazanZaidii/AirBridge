using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Windows;

namespace AirBridge.Net
{
    /// <summary>
    /// Clipboard protocol logic (§5). Local changes come from <see cref="ClipboardManager"/> on the STA thread;
    /// remote clips arrive on network threads and are applied on the dispatcher. No relaying.
    /// </summary>
    public sealed class ClipboardSync
    {
        private readonly PeerService _svc;
        private readonly object _gate = new();
        private byte[]? _lastSetByUs;

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

        /// <summary>Puts received files on the clipboard (any thread).</summary>
        public void SetFileDropList(IReadOnlyList<string> paths)
        {
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                var sc = new StringCollection();
                foreach (var p in paths) sc.Add(p);
                ClipboardManager.TrySetFiles(sc);
            }));
        }
    }
}
