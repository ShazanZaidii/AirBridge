using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Text.Json.Nodes;

namespace AirBridge.Input
{
    /// <summary>A hardware H.264/HEVC decoder for the phone mirror. Frames come back through <see cref="FrameReady"/>
    /// as BGRA pixels (width, height, stride, buffer) on the decoder's thread.</summary>
    public interface IMirrorVideoDecoder : IDisposable
    {
        /// <summary>Decode one Annex-B access unit. Returns false when it needs a keyframe (start, loss, error).</summary>
        bool Decode(ReadOnlySpan<byte> annexB, ulong ptsUs, bool keyframe);
        event Action<int, int, int, byte[]>? FrameReady;
    }

    /// <summary>
    /// Decoder factory. TODO(windows-mirror-decode): implement with the Media Foundation H.264/HEVC decoder MFT
    /// (CLSID_MSH264DecoderMFT / HEVC video extension, NV12 out, converted to BGRA for a WriteableBitmap or shown
    /// through D3DImage). Until then <see cref="Available"/> is false, the PC does not advertise mirror_sink and the
    /// tray shows no "Mirror" button, so nobody meets a half-working window. The protocol, window and input
    /// handling below are complete and only wait for this.
    /// </summary>
    public static class MirrorDecoders
    {
        public static bool Available => false;
        public static IMirrorVideoDecoder? Create(string codec) => null;
    }

    /// <summary>
    /// Phone-as-source mirroring on Windows (§8.7, PC as mirror_sink): asks the phone for its screen, feeds the
    /// 0x03 frames to the decoder and acks them. One per open window. Network callbacks on any thread.
    /// </summary>
    public sealed class PhoneMirrorSession
    {
        public string Peer { get; }
        private readonly object _lock = new();
        private PhoneMirrorState _state = new();
        private readonly MirrorAckPolicy _acks = new();
        private IMirrorVideoDecoder? _decoder;
        private bool _active;
        private long _lastKeyReq;

        /// <summary>Raised (on any thread) whenever the state changes.</summary>
        public event Action<PhoneMirrorState>? StateChanged;
        /// <summary>Decoded BGRA frame: width, height, stride, pixels (decoder thread).</summary>
        public event Action<int, int, int, byte[]>? Frame;

        public PhoneMirrorSession(string peer) { Peer = peer; }

        public PhoneMirrorState State { get { lock (_lock) return _state; } }

        public void Start(int maxSide = 2400)
        {
            lock (_lock)
            {
                _active = true;
                _acks.Reset();
                DisposeDecoder();
                var p = PeerDirectory.Find(Peer);
                Set(new PhoneMirrorState { Control = p?.Caps.Contains("ctl_target") == true, Typing = p?.Caps.Contains("type_target") == true });
            }
            PeerBus.Send(Peer, new JsonObject
            {
                ["t"] = "mirror_start",
                ["codecs"] = new JsonArray("h264", "hevc"),
                ["maxW"] = maxSide, ["maxH"] = maxSide, ["fps"] = 60, ["bitrate"] = 12_000_000,
            });
        }

        public void Stop()
        {
            lock (_lock)
            {
                if (!_active) return;
                _active = false;
                DisposeDecoder();
            }
            PeerBus.Send(Peer, Msg.Of("mirror_stop"));
        }

        public void PeerGone()
        {
            lock (_lock)
            {
                if (!_active) return;
                _active = false;
                DisposeDecoder();
                Set(_state with { Phase = PhoneMirrorPhase.Stopped, StopText = "The phone disconnected" });
            }
        }

        public void Handle(JsonObject m)
        {
            bool needStop = false;
            lock (_lock)
            {
                string? t = Msg.Str(m, "t");
                if (!_active && t != "error") return;
                var next = PhoneMirrorReducer.Apply(_state, m);
                if (t == "mirror_stop") { _active = false; DisposeDecoder(); }
                if (t == "mirror_info" && next.Phase == PhoneMirrorPhase.Streaming)
                {
                    if (_decoder == null || next.Codec != _state.Codec)
                    {
                        DisposeDecoder();
                        _decoder = MirrorDecoders.Create(next.Codec);
                        if (_decoder != null) _decoder.FrameReady += OnDecoded;
                    }
                    if (_decoder == null)
                    {
                        next = next with { Phase = PhoneMirrorPhase.Stopped, StopText = "Showing a phone's screen isn't available on this PC yet" };
                        _active = false;
                        needStop = true;
                    }
                }
                Set(next);
            }
            if (needStop) PeerBus.Send(Peer, Msg.Of("mirror_stop"));
            else if (Msg.Str(m, "t") == "mirror_info") RequestKeyframe(force: true);
        }

        public void OnFrame(ReadOnlyMemory<byte> frame)
        {
            if (!MirrorFrame.TryParse(frame.Span, out ulong pts, out bool key, out _)) return;
            IMirrorVideoDecoder? dec;
            bool ack;
            lock (_lock)
            {
                if (!_active) return;
                dec = _decoder;
                ack = _acks.OnFrame(Environment.TickCount64);
            }
            // §8.7: acks are independent of decoding.
            if (ack) PeerBus.Send(Peer, new JsonObject { ["t"] = "mirror_ack", ["pts"] = (long)pts });
            if (dec == null) return;
            bool ok;
            try { ok = dec.Decode(frame.Span[10..], pts, key); }
            catch (Exception ex) { Debug.WriteLine($"mirror decode: {ex.Message}"); ok = false; }
            if (!ok) RequestKeyframe(force: false);
        }

        public void RequestKeyframe(bool force)
        {
            long now = Environment.TickCount64;
            lock (_lock)
            {
                if (!_active || (!force && now - _lastKeyReq < 500)) return;
                _lastKeyReq = now;
            }
            PeerBus.Send(Peer, Msg.Of("mirror_key"));
        }

        private void OnDecoded(int w, int h, int stride, byte[] px)
        {
            bool first;
            lock (_lock)
            {
                first = !_state.HasPicture;
                if (first) Set(_state with { HasPicture = true });
            }
            Frame?.Invoke(w, h, stride, px);
        }

        private void DisposeDecoder()
        {
            var d = _decoder;
            _decoder = null;
            if (d == null) return;
            d.FrameReady -= OnDecoded;
            try { d.Dispose(); } catch { }
        }

        // Under _lock.
        private void Set(PhoneMirrorState next)
        {
            if (ReferenceEquals(next, _state) || next == _state) return;
            _state = next;
            try { StateChanged?.Invoke(next); } catch (Exception ex) { Debug.WriteLine($"mirror state handler: {ex}"); }
        }
    }

    /// <summary>Routes phone-mirror traffic to the open sessions.</summary>
    public static class PhoneMirrorHub
    {
        private static readonly object Gate = new();
        private static readonly Dictionary<string, PhoneMirrorSession> Sessions = new();

        /// <summary>True when this PC can show a phone's screen (a decoder exists).</summary>
        public static bool Available => MirrorDecoders.Available;

        public static bool CanMirror(PeerInfo p) => Available && p.Platform == "android" && p.Caps.Contains("mirror_source");

        public static void Register(PhoneMirrorSession s) { lock (Gate) Sessions[s.Peer] = s; }
        public static void Unregister(PhoneMirrorSession s) { lock (Gate) if (Sessions.TryGetValue(s.Peer, out var cur) && cur == s) Sessions.Remove(s.Peer); }
        private static PhoneMirrorSession? Get(string peer) { lock (Gate) return Sessions.TryGetValue(peer, out var s) ? s : null; }

        public static bool IsMirroring(string peer) => Get(peer) != null;
        public static void Handle(string peer, JsonObject m) => Get(peer)?.Handle(m);
        public static void Frame(string peer, ReadOnlyMemory<byte> f) => Get(peer)?.OnFrame(f);

        public static void PeersChanged(IReadOnlyList<PeerInfo> peers)
        {
            var ready = new HashSet<string>();
            foreach (var p in peers) ready.Add(p.Id);
            List<PhoneMirrorSession> gone;
            lock (Gate) gone = Sessions.Values.Where(s => !ready.Contains(s.Peer)).ToList();
            foreach (var s in gone) s.PeerGone();
        }
    }
}
