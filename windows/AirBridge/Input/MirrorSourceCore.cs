using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;

namespace AirBridge.Input
{
    /// <summary>A display as the mirror sees it: where it sits on the virtual desktop and its real pixel size.</summary>
    public readonly record struct MirrorDisplay(int Left, int Top, int Width, int Height, int PixelW, int PixelH);

    /// <summary>One encoded video frame: Annex-B bytes (start-code prefixed NAL units), docs/protocol.md 8.7.</summary>
    public sealed record EncodedFrame(long PtsUs, bool Keyframe, bool HasParameterSets, byte[] AnnexB, double EncodeMs);

    /// <summary>A captured picture. Reference counted: the capture hands the core one reference; whoever keeps the picture
    /// past the call (the core for idle re-sends, an encoder while it works) calls <see cref="Retain"/> and later <see cref="Release"/>.</summary>
    public interface ICapturedFrame { void Retain(); void Release(); }

    public interface IMirrorEncoder : IDisposable
    {
        string Codec { get; }
        /// <summary>Starts encoding <paramref name="frame"/>. Output comes back through the callback given at creation.</summary>
        void Encode(ICapturedFrame frame, long ptsUs);
        void RequestKeyframe();
        void SetBitrate(int bitsPerSecond);
    }

    public interface IMirrorCapture : IDisposable { void Start(); }

    /// <summary>Why mirroring cannot work here: a protocol error code ("mirror_permission" or "mirror_codec") and a message.</summary>
    public sealed record MirrorUnavailable(string Code, string Message);

    /// <summary>The part of mirroring that needs Windows (screen capture and the H.264 encoder).</summary>
    public interface IMirrorPlatform
    {
        /// <summary>Null when a capture and an encoder can be made on this PC.</summary>
        MirrorUnavailable? Unavailable { get; }
        /// <summary>The most frames per second this platform can capture and encode. A request above it is cut down, and mirror_info says so.</summary>
        int MaxFps { get; }
        /// <summary>The display to mirror: the one under the cursor, else the primary.</summary>
        MirrorDisplay PickDisplay();
        IMirrorEncoder? CreateEncoder(IReadOnlyList<string> codecs, int w, int h, int fps, int bitrate, Action<EncodedFrame> output);
        /// <summary>Starts nothing yet. <paramref name="onFrame"/> gets one reference per picture; <paramref name="onStopped"/> says why capture ended.</summary>
        IMirrorCapture? CreateCapture(MirrorDisplay display, int w, int h, int fps, Action<ICapturedFrame> onFrame, Action<string> onStopped);
    }

    /// <summary>What the core needs from the network side.</summary>
    public interface IMirrorHost
    {
        bool Send(string peer, JsonObject msg);
        bool SendBinary(string peer, byte[] frame);
        /// <summary>True when <paramref name="peer"/> is ready and advertised <c>mirror_sink</c>.</summary>
        bool IsSink(string peer, out string name);
        /// <summary>Seconds on a steady clock.</summary>
        double Now();
        void Log(string text);
    }

    /// <summary>
    /// Screen-mirror source (docs/protocol.md 8.7): streams one display to one <c>mirror_sink</c> peer as low-latency H.264.
    /// This is the protocol side only, a port of the Mac's MirrorSource: negotiation, flow control from the sink's acks,
    /// bitrate adaptation, keyframes, re-sending the last picture while the screen is idle, and stopping when the sink goes
    /// quiet. Capture and encoding are behind <see cref="IMirrorPlatform"/>, so all of this is checked by
    /// AirBridge.MirrorTests on any OS. Thread safe: every entry point takes one lock (it may be re-entered by a
    /// synchronous encoder). The host calls <see cref="Tick"/> every 100 ms.
    /// </summary>
    public sealed class MirrorSourceCore
    {
        /// <summary>Frames the sink may leave unacknowledged: one ack window (10 frames) + 2 (8.7).</summary>
        public const int AckWindow = 10;
        public const int MaxUnacked = AckWindow + 2;
        public const int MinBitrate = 600_000;
        public const int MaxFrameBytes = 1_048_576 + 17;
        public const byte KindVideo = 0x03;

        private sealed class Active
        {
            public required string Sink;
            public required string SinkName;
            public required int Generation;
            public required int Fps;
            public required int MaxBitrate;
            public required int Bitrate;
            public required MirrorDisplay Display;
            public IMirrorEncoder? Encoder;
            public IMirrorCapture? Capture;
            public ICapturedFrame? LastFrame;
            public readonly List<(long Pts, double At)> Sent = new();
            public double LastAck, LastRaise, LastFrameAt, LagMs, StatSince;
            public long LastPts, LastAckPts;
            public int StatFrames, StatDropped, StatBytes;
        }

        private readonly IMirrorPlatform _platform;
        private readonly IMirrorHost _host;
        private readonly object _gate = new();
        private Active? _a;
        private int _generation;

        public MirrorSourceCore(IMirrorPlatform platform, IMirrorHost host) { _platform = platform; _host = host; }

        /// <summary>The peer being mirrored to, or null.</summary>
        public string? CurrentSink { get { lock (_gate) return _a?.Sink; } }

        /// <summary>The mirrored display if <paramref name="peer"/> is the current sink (for mapping its absolute pointer input).</summary>
        public MirrorDisplay? MirroredDisplay(string peer) { lock (_gate) return _a?.Sink == peer ? _a.Display : null; }

        // ------------------------------------------------------------------ entry points

        public void Handle(string peer, string type, JsonObject msg)
        {
            lock (_gate)
            {
                switch (type)
                {
                    case "mirror_start": Start(peer, msg); break;
                    case "mirror_stop": if (_a?.Sink == peer) StopLocked(false, "sink stopped"); break;
                    case "mirror_key": if (_a?.Sink == peer) _a.Encoder?.RequestKeyframe(); break;
                    case "mirror_ack": if (_a?.Sink == peer && Num(msg, "pts") is double p) Ack((long)Math.Max(0, p)); break;
                }
            }
        }

        /// <summary>The set of ready peers changed: stop if the sink went away.</summary>
        public void PeersChanged(ISet<string> ready)
        {
            lock (_gate) { if (_a != null && !ready.Contains(_a.Sink)) StopLocked(false, "sink disconnected"); }
        }

        /// <summary>This peer's session is ending: stop at once if it is the sink.</summary>
        public void PeerGone(string peer)
        {
            lock (_gate) { if (_a?.Sink == peer) StopLocked(false, "sink disconnected"); }
        }

        /// <summary>Ends mirroring (app exit). Tells the sink.</summary>
        public void Shutdown() { lock (_gate) StopLocked(true, "shutdown"); }

        // ------------------------------------------------------------------ start / stop

        private void Start(string peer, JsonObject m)
        {
            if (!_host.IsSink(peer, out string name)) { _host.Log($"mirror: ignoring mirror_start from non-sink {peer}"); return; }
            if (_platform.Unavailable is { } why)
            {
                _host.Send(peer, Msg("mirror_stop"));
                _host.Send(peer, new JsonObject { ["t"] = "error", ["code"] = why.Code, ["msg"] = why.Message });
                return;
            }
            if (_a != null)
            {
                // One sink at a time: a new request replaces the old stream.
                if (_a.Sink != peer) _host.Send(_a.Sink, Msg("mirror_stop"));
                StopLocked(false, "replaced");
            }

            var codecs = (m["codecs"] as JsonArray)?.Select(n => n?.GetValue<string>()).Where(s => s != null).Select(s => s!).ToList() ?? new List<string> { "h264" };
            if (codecs.Count == 0) codecs.Add("h264");
            int fps = Math.Min(Math.Clamp((int?)Num(m, "fps") ?? 60, 10, 120), Math.Max(10, _platform.MaxFps));
            int maxW = Math.Max(160, (int?)Num(m, "maxW") ?? 1920);
            int maxH = Math.Max(160, (int?)Num(m, "maxH") ?? 1080);
            int bitrate = Math.Clamp((int?)Num(m, "bitrate") ?? 8_000_000, MinBitrate, 80_000_000);

            var d = _platform.PickDisplay();
            int nativeW = Math.Max(2, d.PixelW), nativeH = Math.Max(2, d.PixelH);
            var (w, h) = Fit(nativeW, nativeH, maxW, maxH);

            int gen = ++_generation;
            double now = _host.Now();
            var enc = _platform.CreateEncoder(codecs, w, h, fps, bitrate, f => OnEncoded(f, gen));
            if (enc == null)
            {
                _host.Send(peer, Msg("mirror_stop"));
                _host.Send(peer, new JsonObject { ["t"] = "error", ["code"] = "mirror_codec", ["msg"] = "No H.264 encoder is available on this PC." });
                return;
            }
            var a = new Active
            {
                Sink = peer, SinkName = name, Generation = gen, Fps = fps, MaxBitrate = bitrate, Bitrate = bitrate, Display = d,
                Encoder = enc, LastAck = now, LastRaise = now, StatSince = now,
            };
            _a = a;
            _host.Log($"mirror: start -> {name} {enc.Codec} {w}x{h}@{fps} {bitrate} bps (display {nativeW}x{nativeH})");

            try
            {
                a.Capture = _platform.CreateCapture(d, w, h, fps, f => OnFrame(f, gen), reason => OnCaptureStopped(reason, gen));
                if (a.Capture == null) { StopLocked(true, "capture setup"); return; }
                // The sink must know the stream before the first frame can reach it, so the info goes out before capture starts.
                _host.Send(peer, new JsonObject
                {
                    ["t"] = "mirror_info", ["codec"] = enc.Codec, ["w"] = w, ["h"] = h, ["fps"] = fps,
                    ["display"] = new JsonObject { ["w"] = nativeW, ["h"] = nativeH },
                });
                a.Capture.Start();
            }
            catch (Exception ex)
            {
                _host.Log($"mirror: capture failed: {ex.Message}");
                StopLocked(true, "capture start");
            }
        }

        /// <summary>Fit maxW x maxH keeping the aspect, never upscale, even dimensions.</summary>
        public static (int W, int H) Fit(int nativeW, int nativeH, int maxW, int maxH)
        {
            double scale = Math.Min(1.0, Math.Min((double)maxW / nativeW, (double)maxH / nativeH));
            // The small epsilon keeps 3840 x (1600/3840) from landing on 1599.9999 and losing two pixels.
            return (Math.Max(2, (int)Math.Floor(nativeW * scale + 1e-6) & ~1), Math.Max(2, (int)Math.Floor(nativeH * scale + 1e-6) & ~1));
        }

        private void StopLocked(bool notify, string reason)
        {
            var a = _a;
            if (a == null) return;
            _a = null;
            try { a.Encoder?.Dispose(); } catch (Exception ex) { _host.Log($"mirror: encoder dispose: {ex.Message}"); }
            try { a.Capture?.Dispose(); } catch (Exception ex) { _host.Log($"mirror: capture dispose: {ex.Message}"); }
            a.LastFrame?.Release(); a.LastFrame = null;
            if (notify) _host.Send(a.Sink, Msg("mirror_stop"));
            _host.Log($"mirror: stopped ({reason})");
        }

        private void OnCaptureStopped(string reason, int gen)
        {
            lock (_gate) { if (_a?.Generation == gen) StopLocked(true, reason); }
        }

        // ------------------------------------------------------------------ capture -> encode

        private void OnFrame(ICapturedFrame frame, int gen)
        {
            try
            {
                lock (_gate)
                {
                    var a = _a;
                    if (a == null || a.Generation != gen) return;
                    frame.Retain();
                    a.LastFrame?.Release();
                    a.LastFrame = frame;
                    Submit(a, frame, _host.Now());
                }
            }
            finally { frame.Release(); } // the reference the capture gave us
        }

        private void Submit(Active a, ICapturedFrame frame, double nowSec)
        {
            long pts = (long)(Math.Max(0, nowSec) * 1_000_000);
            if (pts <= a.LastPts) pts = a.LastPts + 1; // strictly increasing (8.7)
            if (a.Sent.Count >= MaxUnacked)
            {
                // The sink is behind: skip this frame entirely (never encoded, so the reference chain stays intact).
                a.StatDropped++;
                return;
            }
            a.LastPts = pts;
            a.LastFrameAt = nowSec;
            a.Sent.Add((pts, nowSec));
            try { a.Encoder!.Encode(frame, pts); }
            catch (Exception ex)
            {
                _host.Log($"mirror: encode failed: {ex.Message}");
                StopLocked(true, "encoder error");
            }
        }

        private void OnEncoded(EncodedFrame f, int gen)
        {
            lock (_gate)
            {
                var a = _a;
                if (a == null || a.Generation != gen) return;
                if (f.AnnexB.Length == 0) { a.Sent.RemoveAll(s => s.Pts == f.PtsUs); a.StatDropped++; return; }
                var frame = new byte[10 + f.AnnexB.Length];
                frame[0] = KindVideo;
                for (int i = 0; i < 8; i++) frame[1 + i] = (byte)((ulong)f.PtsUs >> (56 - 8 * i));
                frame[9] = (byte)((f.Keyframe ? 0x01 : 0) | (f.HasParameterSets ? 0x02 : 0));
                Buffer.BlockCopy(f.AnnexB, 0, frame, 10, f.AnnexB.Length);
                if (frame.Length > MaxFrameBytes)
                {
                    _host.Log($"mirror: frame too large ({frame.Length} bytes), dropping");
                    a.Sent.RemoveAll(s => s.Pts == f.PtsUs);
                    a.Encoder?.RequestKeyframe();
                    LowerBitrate(a, 0.5);
                    return;
                }
                _host.SendBinary(a.Sink, frame);
                a.StatFrames++; a.StatBytes += frame.Length;
            }
        }

        // ------------------------------------------------------------------ flow control

        private void Ack(long pts)
        {
            var a = _a!;
            double now = _host.Now();
            a.LastAck = now;
            a.LastAckPts = Math.Max(a.LastAckPts, pts);
            for (int i = a.Sent.Count - 1; i >= 0; i--)
                if (a.Sent[i].Pts <= pts) { a.LagMs = (now - a.Sent[i].At) * 1000; break; }
            a.Sent.RemoveAll(s => s.Pts <= pts);
            if (a.LagMs > 150) LowerBitrate(a, 0.25);
        }

        private void LowerBitrate(Active a, double fraction)
        {
            double now = _host.Now();
            // At most one cut per 500 ms so a single slow burst doesn't collapse the bitrate.
            if (now - a.LastRaise <= 0.5) return;
            a.Bitrate = Math.Max(MinBitrate, (int)(a.Bitrate * (1 - fraction)));
            a.LastRaise = now;
            a.Encoder?.SetBitrate(a.Bitrate);
        }

        /// <summary>Call every 100 ms.</summary>
        public void Tick()
        {
            lock (_gate)
            {
                var a = _a;
                if (a == null) return;
                double now = _host.Now();
                // 8.7: stop when the sink stops acking for 3 s.
                if (now - a.LastAck > 3) { StopLocked(true, "no ack for 3 s"); return; }
                // Healthy (acks keep up, low lag): +10 % every 5 s up to the request.
                if (a.LagMs < 100 && a.Sent.Count < AckWindow && now - a.LastRaise >= 5 && a.Bitrate < a.MaxBitrate)
                {
                    a.Bitrate = Math.Min(a.MaxBitrate, (int)(a.Bitrate * 1.1));
                    a.LastRaise = now;
                    a.Encoder?.SetBitrate(a.Bitrate);
                }
                // Windows only delivers a frame when the screen changes. Re-encode the last one at 10 fps while idle so the
                // picture sharpens and the sink keeps acking.
                if (now - a.LastFrameAt >= 0.1 && a.LastFrame != null) Submit(a, a.LastFrame, now);
                if (_a != null && now - a.StatSince >= 5)
                {
                    double secs = now - a.StatSince;
                    _host.Log($"mirror: {a.StatFrames / secs:F1} fps, {a.StatBytes * 8 / 1000 / Math.Max(1, (int)secs)} kbps (target {a.Bitrate / 1000}), lag {a.LagMs:F0} ms, dropped {a.StatDropped}");
                    a.StatFrames = 0; a.StatDropped = 0; a.StatBytes = 0; a.StatSince = now;
                }
            }
        }

        // ------------------------------------------------------------------ helpers

        private static JsonObject Msg(string type) => new() { ["t"] = type };

        /// <summary>A JSON number as a double, whatever type it was built or parsed as; null if absent or not a finite number.</summary>
        private static double? Num(JsonObject o, string key)
        {
            if (o[key] is not JsonValue v || v.GetValueKind() != System.Text.Json.JsonValueKind.Number) return null;
            return double.TryParse(v.ToJsonString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var d)
                   && double.IsFinite(d) ? d : null;
        }
    }
}
