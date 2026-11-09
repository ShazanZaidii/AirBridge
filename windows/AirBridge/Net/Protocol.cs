using System;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace AirBridge.Net
{
    /// <summary>Protocol v2 constants and JSON/crypto helpers (docs/protocol.md).</summary>
    public static class Protocol
    {
        public const int Version = 2;
        public const string AppVersion = "2.0.0";
        public const string Platform = "windows";
        public const string ServiceType = "_airbridge._tcp";
        public const string Path = "/v2";
        public const int BasePort = 47632;
        public const int PortCount = 10; // 47632 … 47641

        /// <summary>
        /// The capabilities this PC advertises in hello (§3.1). This is the ONE place caps are assembled.
        /// No "mirror_source" yet: the input half adds it here once the Windows screen-mirror source exists
        /// (and only when capture is actually available, §8.7).
        /// </summary>
        /// "drag_files": as a target we show a drag carried onto us and take the files (§8.8).
        /// "mirror_sink" (showing a phone's screen, §8.7) only once a video decoder exists (Input/PhoneMirrorSession.cs).
        /// "notif_sink" / "call_sink": shows a phone's notifications and calls and sends replies/controls back (§13).
        /// "call_ring": decides where calls ring from the phone's presence and beacon (§13.5).
        public static readonly string[] Caps = Input.PhoneMirrorHub.Available
            ? new[] { "clip", "xfer", "ctl_source", "ctl_target", "drag_files", "mirror_sink", "notif_sink", "call_sink", "call_ring" }
            : new[] { "clip", "xfer", "ctl_source", "ctl_target", "drag_files", "notif_sink", "call_sink", "call_ring" };

        public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(10);
        public static readonly TimeSpan PingAfterIdle = TimeSpan.FromSeconds(5);
        public static readonly TimeSpan DeadAfter = TimeSpan.FromSeconds(15);

        public const int MaxClipBytes = 512 * 1024;
        public const int MaxTextFrame = 1024 * 1024;
        /// <summary>Receive cap (lenient: peers may escape JSON heavily).</summary>
        public const int MaxReceiveFrame = 4 * 1024 * 1024;

        public const int ChunkSize = 512 * 1024;
        public const int ChunkHeader = 17;
        public const byte KindFileChunk = 0x01;
        public const byte KindVideo = 0x03;
        /// <summary>Largest inner binary frame (§9): 1 MiB payload + 17 header bytes.</summary>
        public const int MaxBinaryFrame = 1024 * 1024 + 17;
        public const long DefaultWindow = 8L * 1024 * 1024;
        public const long AckEvery = 1024 * 1024;

        public static readonly JsonSerializerOptions JsonOptions = new()
        {
            // Emit UTF-8 directly instead of \uXXXX so clip text stays within the frame limit.
            Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
            WriteIndented = false,
        };

        public static JsonObject Msg(string type) => new() { ["t"] = type };

        public static string Serialize(JsonObject msg) => msg.ToJsonString(JsonOptions);

        public static JsonObject? Parse(string text)
        {
            try { return JsonNode.Parse(text) as JsonObject; }
            catch (JsonException) { return null; }
        }

        public static string? Str(JsonObject o, string key)
        {
            try { return o[key] is JsonValue v && v.TryGetValue(out string? s) ? s : null; }
            catch { return null; }
        }

        public static long? Long(JsonObject o, string key)
        {
            if (o[key] is not JsonValue v) return null;
            try
            {
                if (v.TryGetValue(out long l)) return l;
                if (v.TryGetValue(out double d) && !double.IsNaN(d) && !double.IsInfinity(d)) return (long)d;
            }
            catch { }
            return null;
        }

        public static bool? Bool(JsonObject o, string key)
        {
            try { return o[key] is JsonValue v && v.TryGetValue(out bool b) ? b : null; }
            catch { return null; }
        }

        public static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        // --- crypto ---

        public static byte[] RandomBytes(int n) => RandomNumberGenerator.GetBytes(n);

        public static string B64(byte[] b) => Convert.ToBase64String(b);

        public static byte[]? FromB64(string? s)
        {
            if (string.IsNullOrEmpty(s)) return null;
            try { return Convert.FromBase64String(s); } catch { return null; }
        }

        public static string B64Url(byte[] b) =>
            Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

        /// <summary>Concatenates UTF-8 strings and raw byte arrays (the spec's "|" operator).</summary>
        public static byte[] Cat(params object[] parts)
        {
            var list = new List<byte>();
            foreach (var p in parts)
            {
                if (p is byte[] b) list.AddRange(b);
                else list.AddRange(Encoding.UTF8.GetBytes(p.ToString() ?? ""));
            }
            return list.ToArray();
        }

        public static byte[] Hmac(byte[] key, byte[] data)
        {
            using var h = new HMACSHA256(key);
            return h.ComputeHash(data);
        }

        // Handshake MACs/key derivations live in HandshakeCrypto (SecureChannel.cs).

        public static bool MacEquals(byte[]? a, byte[]? b) =>
            a != null && b != null && a.Length == b.Length && CryptographicOperations.FixedTimeEquals(a, b);

        // --- binary chunk frame (§6.2) ---

        public static void WriteChunkHeader(byte[] buf, uint transferId, uint fileIndex, ulong offset)
        {
            buf[0] = KindFileChunk;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(buf.AsSpan(1, 4), transferId);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(buf.AsSpan(5, 4), fileIndex);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt64BigEndian(buf.AsSpan(9, 8), offset);
        }
    }
}
