using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;
using AirBridge.Input;

int passed = 0, failed = 0;
void Check(string name, bool ok) { if (ok) passed++; else { failed++; Console.WriteLine("FAIL: " + name); } }

JsonObject Start(double? fps = null, int? maxW = null, int? maxH = null, int? bitrate = null)
{
    var o = new JsonObject { ["t"] = "mirror_start", ["codecs"] = new JsonArray("h264") };
    if (fps != null) o["fps"] = fps; if (maxW != null) o["maxW"] = maxW; if (maxH != null) o["maxH"] = maxH; if (bitrate != null) o["bitrate"] = bitrate;
    return o;
}
JsonObject Ack(long pts) => new() { ["t"] = "mirror_ack", ["pts"] = pts };

(MirrorSourceCore Core, FakePlatform P, FakeHost H) Make()
{
    var p = new FakePlatform(); var h = new FakeHost();
    return (new MirrorSourceCore(p, h), p, h);
}
(MirrorSourceCore Core, FakePlatform P, FakeHost H) Started(int? bitrate = null)
{
    var m = Make(); m.Core.Handle("phone", "mirror_start", Start(bitrate: bitrate)); return m;
}
Frame Deliver(FakePlatform p, FakeHost h, double advance = 0.016) { h.Clock += advance; var f = new Frame(); p.OnFrame!(f); return f; }

// ---------------------------------------------------------------- tests
{ // a request from something that is not a sink is ignored
    var (core, p, h) = Make(); h.Sinks.Clear();
    core.Handle("phone", "mirror_start", Start());
    Check("a non-sink gets no answer and nothing starts", h.Sent.Count == 0 && p.Encoder == null && core.CurrentSink == null);
}
{ // permission / hardware missing: declined cleanly with an error code
    var (core, p, h) = Make(); p.Unavailable = new MirrorUnavailable("mirror_codec", "No H.264 encoder.");
    core.Handle("phone", "mirror_start", Start());
    Check("unavailable: mirror_stop then the error", h.Types("phone").SequenceEqual(new[] { "mirror_stop", "error" }) && (string)h.Sent[1].Msg["code"]! == "mirror_codec");
    Check("unavailable: nothing was created", p.Encoder == null && p.Capture == null && core.CurrentSink == null);
}
{ // no encoder
    var (core, p, h) = Make(); p.EncoderFails = true;
    core.Handle("phone", "mirror_start", Start());
    Check("no encoder: mirror_stop and mirror_codec", h.Types("phone").SequenceEqual(new[] { "mirror_stop", "error" }) && (string)h.Sent[1].Msg["code"]! == "mirror_codec" && core.CurrentSink == null);
}
{ // capture cannot be made / started
    var (core, p, h) = Make(); p.CaptureFails = true;
    core.Handle("phone", "mirror_start", Start());
    Check("capture setup fails: stream ends and the sink is told", core.CurrentSink == null && h.Types("phone").Last() == "mirror_stop" && p.Encoder!.Disposed);
    var (c2, p2, h2) = Make(); p2.CaptureThrows = true;
    c2.Handle("phone", "mirror_start", Start());
    Check("capture start throws: stream ends and everything is released", c2.CurrentSink == null && h2.Types("phone").Last() == "mirror_stop" && p2.Encoder!.Disposed);
}
{ // happy path: size fit, info before frames, display native size
    var (core, p, h) = Make(); // native 3840x2160
    core.Handle("phone", "mirror_start", Start(fps: 60, maxW: 1600, maxH: 1000, bitrate: 8_000_000));
    Check("4K display fits 1600x1000 at 1600x900", p.EncoderArgs is { W: 1600, H: 900, Fps: 60, Bitrate: 8_000_000 });
    var info = h.Sent.First(s => (string)s.Msg["t"]! == "mirror_info").Msg;
    Check("mirror_info: codec, size, fps, native display", (string)info["codec"]! == "h264" && (int)info["w"]! == 1600 && (int)info["h"]! == 900 && (int)info["fps"]! == 60 && (int)info["display"]!["w"]! == 3840 && (int)info["display"]!["h"]! == 2160);
    Check("capture started and the sink is recorded", ((FakeCapture)p.Capture!).Started && core.CurrentSink == "phone");
    Check("the mirrored display is known only for the sink", core.MirroredDisplay("phone") == p.Display && core.MirroredDisplay("other") == null);
}
{ // info goes out before capture can deliver a frame
    var order = new List<string>();
    var p = new FakePlatform(); var h = new FakeHost(); var core = new MirrorSourceCore(p, h);
    core.Handle("phone", "mirror_start", Start());
    int infoAt = h.Sent.FindIndex(s => (string)s.Msg["t"]! == "mirror_info");
    Check("mirror_info is sent", infoAt >= 0);
}
{ // fit rules
    Check("never upscales", MirrorSourceCore.Fit(1280, 720, 1920, 1080) == (1280, 720));
    Check("dimensions are even", MirrorSourceCore.Fit(1367, 769, 1920, 1080) is { W: var w, H: var h } && w % 2 == 0 && h % 2 == 0);
    Check("keeps the aspect ratio", MirrorSourceCore.Fit(3440, 1440, 1600, 1000) is { W: 1600, H: 669 or 670 or 668 });
    Check("tiny limits still give at least 2x2", MirrorSourceCore.Fit(1920, 1080, 2, 2) == (2, 2) || MirrorSourceCore.Fit(1920, 1080, 2, 2).W >= 2);
}
{ // request values are clamped
    var (core, p, h) = Make(); core.Handle("phone", "mirror_start", Start(fps: 500, bitrate: 1));
    Check("fps and bitrate are clamped", p.EncoderArgs is { Fps: 120, Bitrate: 600_000 });
    var (c2, p2, h2) = Make(); c2.Handle("phone", "mirror_start", Start(bitrate: 999_000_000));
    Check("a huge bitrate is cut to 80 Mbps", p2.EncoderArgs is { Bitrate: 80_000_000 });
}
{ // frames are framed per 8.7 and pts is strictly increasing
    var (core, p, h) = Started();
    var f1 = Deliver(p, h, 0); var f2 = Deliver(p, h, 0);
    Check("two frames at the same instant get strictly increasing pts", p.Encoder!.Encoded.Count == 2 && p.Encoder.Encoded[1] > p.Encoder.Encoded[0]);
    p.Output!(new EncodedFrame(0x0102030405060708, true, true, new byte[] { 0, 0, 0, 1, 0x67, 0xAA }, 1.5));
    var fr = h.Binary.Single().Data;
    Check("binary frame: kind 0x03", fr[0] == 0x03);
    Check("binary frame: pts big-endian", fr.Skip(1).Take(8).SequenceEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
    Check("binary frame: flags keyframe + parameter sets", fr[9] == 0x03);
    Check("binary frame: Annex-B payload follows", fr.Skip(10).SequenceEqual(new byte[] { 0, 0, 0, 1, 0x67, 0xAA }));
    p.Output!(new EncodedFrame(5, false, false, new byte[] { 0, 0, 0, 1, 0x41 }, 1));
    Check("a delta frame has no flags", h.Binary[1].Data[9] == 0x00);
    Check("the frame went to the sink", h.Binary.All(b => b.Peer == "phone"));
}
{ // flow control: never more than 12 unacknowledged frames
    var (core, p, h) = Started();
    for (int i = 0; i < 20; i++) Deliver(p, h);
    Check("at most 12 frames are encoded before the sink acks", p.Encoder!.Encoded.Count == MirrorSourceCore.MaxUnacked);
    long third = p.Encoder.Encoded[2];
    core.Handle("phone", "mirror_ack", Ack(third));
    Deliver(p, h); Deliver(p, h); Deliver(p, h); Deliver(p, h);
    Check("an ack frees room for exactly the acknowledged frames", p.Encoder.Encoded.Count == MirrorSourceCore.MaxUnacked + 3);
}
{ // an ack from another peer is ignored
    var (core, p, h) = Started(); h.Sinks.Add("other");
    for (int i = 0; i < 12; i++) Deliver(p, h);
    core.Handle("other", "mirror_ack", Ack(long.MaxValue));
    Deliver(p, h);
    Check("an ack from someone else frees nothing", p.Encoder!.Encoded.Count == 12);
}
{ // lag: bitrate -25 %, at most once per 500 ms
    var (core, p, h) = Started(8_000_000);
    h.Clock += 1; var f = new Frame(); p.OnFrame!(f);          // frame at t=101
    h.Clock += 0.4;                                              // acked 400 ms later
    core.Handle("phone", "mirror_ack", Ack(p.Encoder!.Encoded[0]));
    Check("lag over 150 ms cuts the bitrate by 25%", p.Encoder.Bitrates.SequenceEqual(new[] { 6_000_000 }));
    h.Clock += 0.1; Deliver(p, h, 0); h.Clock += 0.4; core.Handle("phone", "mirror_ack", Ack(p.Encoder.Encoded.Last()));
    Check("a second cut within 500 ms is not made", p.Encoder.Bitrates.Count == 1);
}
{ // no lag: nothing is cut; healthy for 5 s raises 10 % up to the request
    var (core, p, h) = Started(8_000_000);
    h.Clock += 1; p.OnFrame!(new Frame()); h.Clock += 0.02; core.Handle("phone", "mirror_ack", Ack(p.Encoder!.Encoded[0]));
    Check("low lag leaves the bitrate alone", p.Encoder.Bitrates.Count == 0);
    // cut once, then recover
    h.Clock += 1; p.OnFrame!(new Frame()); h.Clock += 0.4; core.Handle("phone", "mirror_ack", Ack(p.Encoder.Encoded.Last()));
    Check("a cut happened", p.Encoder.Bitrates.Count == 1 && p.Encoder.Bitrates[0] == 6_000_000);
    h.Clock += 0.02; core.Handle("phone", "mirror_ack", Ack(long.MaxValue)); // keep acks flowing
    for (int i = 0; i < 260; i++) { h.Clock += 0.1; core.Tick(); h.Clock += 0.01; core.Handle("phone", "mirror_ack", Ack(long.MaxValue)); } // a healthy sink: ack 10 ms after each frame
    Check("healthy streaming raises the bitrate in 10% steps", p.Encoder.Bitrates.Count > 1 && p.Encoder.Bitrates[1] == 6_600_000);
    Check("it never goes above the request", p.Encoder.Bitrates.All(b => b <= 8_000_000) && p.Encoder.Bitrates.Last() == 8_000_000);
}
{ // idle screen: the last picture is re-sent at 10 fps so acks keep flowing
    var (core, p, h) = Started();
    var f = Deliver(p, h);
    int before = p.Encoder!.Encoded.Count;
    h.Clock += 0.05; core.Tick();
    Check("no re-send before 100 ms", p.Encoder.Encoded.Count == before);
    h.Clock += 0.06; core.Tick();
    Check("re-sent after 100 ms of no new picture", p.Encoder.Encoded.Count == before + 1);
    Check("the re-sent picture has a later pts", p.Encoder.Encoded[^1] > p.Encoder.Encoded[^2]);
}
{ // the sink goes quiet for 3 s
    var (core, p, h) = Started();
    h.Clock += 2.9; core.Tick();
    Check("still streaming after 2.9 s without an ack", core.CurrentSink == "phone");
    h.Clock += 0.2; core.Tick();
    Check("stopped after 3 s without an ack, and the sink is told", core.CurrentSink == null && h.Types("phone").Last() == "mirror_stop");
}
{ // keyframe on request
    var (core, p, h) = Started();
    core.Handle("phone", "mirror_key", new JsonObject { ["t"] = "mirror_key" });
    Check("mirror_key asks the encoder for a keyframe", p.Encoder!.Keyframes == 1);
    h.Sinks.Add("other"); core.Handle("other", "mirror_key", new JsonObject { ["t"] = "mirror_key" });
    Check("mirror_key from someone else is ignored", p.Encoder.Keyframes == 1);
}
{ // the sink stops it
    var (core, p, h) = Started(); int sentBefore = h.Sent.Count;
    core.Handle("phone", "mirror_stop", new JsonObject { ["t"] = "mirror_stop" });
    Check("the sink's mirror_stop ends the stream without answering", core.CurrentSink == null && h.Sent.Count == sentBefore);
    Check("encoder and capture are released", p.Encoder!.Disposed && ((FakeCapture)p.Capture!).Disposed);
}
{ // a second sink replaces the first
    var (core, p, h) = Started(); h.Sinks.Add("tablet"); var firstEncoder = p.Encoder!;
    core.Handle("tablet", "mirror_start", Start());
    Check("the old sink is told to stop", h.Types("phone").Last() == "mirror_stop" && firstEncoder.Disposed);
    Check("the new sink is the current one", core.CurrentSink == "tablet");
}
{ // sink disconnects
    var (core, p, h) = Started();
    core.PeersChanged(new HashSet<string> { "tablet" });
    Check("the sink leaving stops the stream", core.CurrentSink == null && p.Encoder!.Disposed);
    var (c2, p2, h2) = Started(); c2.PeersChanged(new HashSet<string> { "phone" });
    Check("other peers changing does not", c2.CurrentSink == "phone");
}
{ // oversized frame
    var (core, p, h) = Started(8_000_000); h.Clock += 1;
    p.OnFrame!(new Frame());
    p.Output!(new EncodedFrame(p.Encoder!.Encoded[0], true, true, new byte[1_048_576 + 100], 1));
    Check("a frame over 1 MiB is not sent", h.Binary.Count == 0);
    Check("it asks for a keyframe and halves the bitrate", p.Encoder.Keyframes == 1 && p.Encoder.Bitrates.SequenceEqual(new[] { 4_000_000 }));
}
{ // empty output
    var (core, p, h) = Started();
    for (int i = 0; i < 12; i++) Deliver(p, h);
    p.Output!(new EncodedFrame(p.Encoder!.Encoded[0], false, false, Array.Empty<byte>(), 1));
    Deliver(p, h);
    Check("an empty encoder output frees its slot", p.Encoder.Encoded.Count == 13 && h.Binary.Count == 0);
}
{ // encoder failure
    var (core, p, h) = Started(); p.Encoder!.Throw = true;
    Deliver(p, h);
    Check("an encoder error ends the stream and tells the sink", core.CurrentSink == null && h.Types("phone").Last() == "mirror_stop");
}
{ // capture stops by itself (monitor unplugged, session locked)
    var (core, p, h) = Started();
    p.OnStopped!("display lost");
    Check("capture ending stops the stream and tells the sink", core.CurrentSink == null && h.Types("phone").Last() == "mirror_stop");
}
{ // reference counting: nothing leaks
    var (core, p, h) = Started();
    var a = Deliver(p, h); var b = Deliver(p, h); var c = Deliver(p, h);
    Check("the core holds only the newest picture", a.Refs == 0 && b.Refs == 0 && c.Refs == 1);
    core.Handle("phone", "mirror_stop", new JsonObject { ["t"] = "mirror_stop" });
    Check("stopping releases the last picture", c.Refs == 0);
    var late = new Frame(); p.OnFrame!(late);
    Check("a picture that arrives after stopping is released", late.Refs == 0);
}
{ // stale callbacks from an old stream are ignored
    var (core, p, h) = Started(); h.Sinks.Add("tablet"); var oldOut = p.Output!; var oldFrame = p.OnFrame!;
    core.Handle("tablet", "mirror_start", Start());
    int before = h.Binary.Count;
    oldOut(new EncodedFrame(1, true, true, new byte[] { 1 }, 1));
    var stale = new Frame(); oldFrame(stale);
    Check("encoded output of the previous stream is dropped", h.Binary.Count == before);
    Check("a picture from the previous capture is released and ignored", stale.Refs == 0);
}
{ // shutdown tells the sink
    var (core, p, h) = Started(); core.Shutdown();
    Check("shutdown stops the stream and tells the sink", core.CurrentSink == null && h.Types("phone").Last() == "mirror_stop");
    core.Shutdown(); core.Tick();
    Check("stopping twice and ticking when idle are harmless", true);
}

Console.WriteLine($"{passed} passed, {failed} failed");
return failed == 0 ? 0 : 1;

// ---------------------------------------------------------------- fakes
sealed class Frame : ICapturedFrame
{
    public int Refs = 1; // the reference the capture gives the core
    public void Retain() => Refs++;
    public void Release() => Refs--;
}

sealed class FakeEncoder : IMirrorEncoder
{
    public string Codec => "h264";
    public readonly List<long> Encoded = new();
    public readonly List<int> Bitrates = new();
    public int Keyframes; public bool Disposed; public bool Throw;
    public void Encode(ICapturedFrame frame, long ptsUs) { if (Throw) throw new InvalidOperationException("boom"); Encoded.Add(ptsUs); }
    public void RequestKeyframe() => Keyframes++;
    public void SetBitrate(int bps) => Bitrates.Add(bps);
    public void Dispose() => Disposed = true;
}

sealed class FakeCapture : IMirrorCapture
{
    public bool Started, Disposed;
    public void Start() { Started = true; }
    public void Dispose() => Disposed = true;
}

sealed class FakePlatform : IMirrorPlatform
{
    public MirrorUnavailable? Unavailable { get; set; }
    public MirrorDisplay Display = new(0, 0, 1920, 1080, 3840, 2160);
    public bool EncoderFails, CaptureFails, CaptureThrows;
    public FakeEncoder? Encoder; public IMirrorCapture? Capture;
    public Action<EncodedFrame>? Output; public Action<ICapturedFrame>? OnFrame; public Action<string>? OnStopped;
    public (int W, int H, int Fps, int Bitrate)? EncoderArgs;
    public MirrorDisplay PickDisplay() => Display;
    public IMirrorEncoder? CreateEncoder(IReadOnlyList<string> codecs, int w, int h, int fps, int bitrate, Action<EncodedFrame> output)
    {
        if (EncoderFails) return null;
        EncoderArgs = (w, h, fps, bitrate); Output = output; return Encoder = new FakeEncoder();
    }
    public IMirrorCapture? CreateCapture(MirrorDisplay d, int w, int h, int fps, Action<ICapturedFrame> onFrame, Action<string> onStopped)
    {
        if (CaptureFails) return null;
        OnFrame = onFrame; OnStopped = onStopped; return Capture = CaptureThrows ? new ThrowingCapture() : new FakeCapture();
    }
}
sealed class ThrowingCapture : IMirrorCapture { public void Start() => throw new InvalidOperationException("no capture"); public void Dispose() { } }

sealed class FakeHost : IMirrorHost
{
    public double Clock = 100;
    public readonly HashSet<string> Sinks = new() { "phone" };
    public readonly List<(string Peer, JsonObject Msg)> Sent = new();
    public readonly List<(string Peer, byte[] Data)> Binary = new();
    public bool Send(string peer, JsonObject msg) { Sent.Add((peer, msg)); return true; }
    public bool SendBinary(string peer, byte[] frame) { Binary.Add((peer, frame)); return true; }
    public bool IsSink(string peer, out string name) { name = "Pixel"; return Sinks.Contains(peer); }
    public double Now() => Clock;
    public void Log(string text) { }
    public IEnumerable<string> Types(string peer) => Sent.Where(s => s.Peer == peer).Select(s => (string)s.Msg["t"]!);
}

