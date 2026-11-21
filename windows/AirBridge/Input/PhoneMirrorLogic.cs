using System;
using System.Text.Json.Nodes;

namespace AirBridge.Input
{
    /// <summary>What the phone mirror window shows (docs/protocol.md §8.7, phone as source).</summary>
    public enum PhoneMirrorPhase { Connecting, WaitingForConsent, Streaming, Stopped }

    /// <summary>Snapshot of one phone mirror. Immutable; <see cref="PhoneMirrorReducer"/> produces the next one.</summary>
    public sealed record PhoneMirrorState
    {
        public PhoneMirrorPhase Phase { get; init; } = PhoneMirrorPhase.Connecting;
        public string? StopText { get; init; }
        public string Codec { get; init; } = "h264";
        /// <summary>Stream size (mirror_info w/h) and the phone screen in pixels (display), 0 until known.</summary>
        public int StreamW { get; init; }
        public int StreamH { get; init; }
        public int DisplayW { get; init; }
        public int DisplayH { get; init; }
        /// <summary>Phone screen corner radius in display pixels, 0 = unknown.</summary>
        public int Corner { get; init; }
        /// <summary>mirror_state: the phone takes input (Universal Control on), can type, is locked.</summary>
        public bool Control { get; init; }
        public bool Typing { get; init; }
        public bool Locked { get; init; }
        public bool HasPicture { get; init; }
    }

    /// <summary>Pure message handling for the sink side of phone mirroring. Platform-free (AirBridge.InputTests).</summary>
    public static class PhoneMirrorReducer
    {
        public static string StopText(string? reason) => reason switch
        {
            "user" => "Stopped on the phone",
            "locked" => "Phone is locked",
            "replaced" => "Another computer is showing this phone",
            "no ack" => "The connection was too slow",
            _ => "Stopped",
        };

        public static string ErrorText(string? code, string? reason) => code switch
        {
            "mirror_permission" => reason == "timeout" ? "Nobody allowed it on the phone" : "Not allowed on the phone",
            "mirror_codec" => "The phone can't encode video for this PC",
            _ => "The phone stopped sharing its screen",
        };

        /// <summary>Next state for a control message from the phone; returns the same instance when nothing changed.</summary>
        public static PhoneMirrorState Apply(PhoneMirrorState s, JsonObject m)
        {
            switch (Msg.Str(m, "t"))
            {
                case "mirror_wait":
                    return s.Phase == PhoneMirrorPhase.Stopped ? s : s with { Phase = PhoneMirrorPhase.WaitingForConsent };
                case "mirror_info":
                {
                    int w = (int)(Msg.Num(m, "w") ?? 0), h = (int)(Msg.Num(m, "h") ?? 0);
                    if (w <= 0 || h <= 0) return s;
                    var d = m["display"] as JsonObject;
                    return s with
                    {
                        Phase = PhoneMirrorPhase.Streaming,
                        Codec = Msg.Str(m, "codec") ?? "h264",
                        StreamW = w, StreamH = h,
                        DisplayW = d != null ? (int)(Msg.Num(d, "w") ?? w) : w,
                        DisplayH = d != null ? (int)(Msg.Num(d, "h") ?? h) : h,
                        Corner = d != null ? (int)(Msg.Num(d, "corner") ?? 0) : 0,
                    };
                }
                case "mirror_state":
                    return s with
                    {
                        Control = Msg.Bool(m, "control") ?? s.Control,
                        Typing = Msg.Bool(m, "typing") ?? s.Typing,
                        Locked = Msg.Bool(m, "locked") ?? s.Locked,
                    };
                case "mirror_stop":
                    // An `error` that came first already explained why.
                    return s.Phase == PhoneMirrorPhase.Stopped ? s : s with { Phase = PhoneMirrorPhase.Stopped, StopText = StopText(Msg.Str(m, "reason")) };
                case "error":
                {
                    string? code = Msg.Str(m, "code");
                    if (code == null || !code.StartsWith("mirror_", StringComparison.Ordinal)) return s;
                    return s with { Phase = PhoneMirrorPhase.Stopped, StopText = ErrorText(code, Msg.Str(m, "reason")) };
                }
                default:
                    return s;
            }
        }
    }

    /// <summary>The §8.7 video frame header: 0x03 | u64 BE pts (µs) | flags | Annex-B.</summary>
    public static class MirrorFrame
    {
        public static bool TryParse(ReadOnlySpan<byte> f, out ulong pts, out bool keyframe, out bool parameterSets)
        {
            pts = 0; keyframe = false; parameterSets = false;
            if (f.Length <= 10 || f[0] != PeerBus.BinaryKindVideo) return false;
            for (int i = 1; i <= 8; i++) pts = (pts << 8) | f[i];
            keyframe = (f[9] & 1) != 0;
            parameterSets = (f[9] & 2) != 0;
            return true;
        }
    }

    /// <summary>§8.7 sink flow control: ack every 10 received frames or every 500 ms, whichever comes first.</summary>
    public sealed class MirrorAckPolicy
    {
        public const int Every = 10;
        public const long GapMs = 500;
        private int _received;
        private long _lastAck = long.MinValue / 2;

        public bool OnFrame(long nowMs)
        {
            _received++;
            if (_received % Every == 0 || nowMs - _lastAck >= GapMs) { _lastAck = nowMs; return true; }
            return false;
        }

        public void Reset() { _received = 0; _lastAck = long.MinValue / 2; }
    }

    /// <summary>Window → phone mapping (§8.2 conventions).</summary>
    public static class PhoneMirrorGeometry
    {
        /// <summary>Gain the Android target applies to `ptr_scroll px:true`.</summary>
        public const double TargetGain = 1.6;

        /// <summary>Point in the picture (origin top-left) → ptr_abs x/y in 0…1, 4 decimals (§9).</summary>
        public static (double x, double y) Normalize(double px, double py, double width, double height)
        {
            if (width <= 0 || height <= 0) return (0.5, 0.5);
            double x = Math.Clamp(px / width, 0, 1), y = Math.Clamp(py / height, 0, 1);
            return (Math.Round(x, 4), Math.Round(y, 4));
        }

        /// <summary>
        /// Mouse wheel / precision touchpad delta (WPF: +120 per notch, positive = wheel away from the user = content
        /// down) → ptr_scroll. A notch becomes one line (px:false); smaller deltas from precision touchpads become
        /// pixels scaled so the phone's content follows the fingers as seen in the window.
        /// </summary>
        public static (double dx, double dy, bool px) Wheel(int deltaH, int deltaV, double viewWidth, double phoneWidth)
        {
            bool notches = deltaV % 120 == 0 && deltaH % 120 == 0;
            if (notches) return (Math.Round(deltaH / 120.0, 2), Math.Round(-deltaV / 120.0, 2), false);
            double k = viewWidth > 0 && phoneWidth > 0 ? phoneWidth / viewWidth / TargetGain : 1;
            // ~1 WPF unit per device pixel of finger travel on precision touchpads.
            return (Math.Round(deltaH * k, 2), Math.Round(-deltaV * k, 2), true);
        }
    }
}
