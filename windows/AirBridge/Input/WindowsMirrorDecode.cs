using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using Vortice.MediaFoundation;

namespace AirBridge.Input
{
    /// <summary>
    /// The receiving half of a screen mirror on Windows (docs/protocol.md 8.7, issue #18): the Media Foundation H.264
    /// decoder, NV12 out, turned into BGRA for the mirror window. Lets this PC show a phone's or a Mac's screen.
    /// Like the encoder, this needs Windows, so the only proof it works is the self-test (<c>--decode-selftest</c>), which
    /// encodes one grab of the real screen and decodes it again; without a pass the PC doesn't advertise mirror_sink.
    /// </summary>
    internal sealed class MfH264Decoder : IMirrorVideoDecoder
    {
        private const int NeedMoreInput = unchecked((int)0xC00D6D72), StreamChange = unchecked((int)0xC00D6D61);
        private const int ProvidesSamples = 0x100;

        private readonly IMFTransform _mft;
        private readonly object _gate = new();
        private bool _providesSamples;
        private int _outSize;
        private int _w, _h, _stride;
        private bool _gotKey, _disposed;
        public event Action<int, int, int, byte[]>? FrameReady;

        private MfH264Decoder(IMFTransform mft) { _mft = mft; }

        public static MfH264Decoder? Create(out string? error)
        {
            error = null;
            try
            {
                using var found = MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoDecoder,
                    (uint)(EnumFlag.EnumFlagSyncmft | EnumFlag.EnumFlagLocalmft | EnumFlag.EnumFlagSortandfilter),
                    new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.H264 }, null);
                var activate = found.FirstOrDefault();
                if (activate == null) { error = "no H.264 decoder on this PC"; return null; }
                var mft = activate.ActivateObject<IMFTransform>();
                using (var i = MediaFactory.MFCreateMediaType())
                {
                    i.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                    i.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.H264);
                    i.Set(MediaTypeAttributeKeys.InterlaceMode, 2u); // progressive
                    mft.SetInputType(0, i, 0);
                }
                var d = new MfH264Decoder(mft);
                if (!d.PickNv12()) { error = "the decoder offers no NV12 output"; mft.Dispose(); return null; }
                mft.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
                mft.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
                return d;
            }
            catch (Exception ex) { error = "decoder setup failed: " + ex.Message; return null; }
        }

        /// <summary>Chooses NV12 among the decoder's outputs and reads the picture size from it.</summary>
        private bool PickNv12()
        {
            for (int i = 0; ; i++)
            {
                IMFMediaType t;
                try { t = _mft.GetOutputAvailableType(0, i); } catch { return false; }
                using (t)
                {
                    if (t.GetGUID(MediaTypeAttributeKeys.Subtype) != VideoFormatGuids.NV12) continue;
                    _mft.SetOutputType(0, t, 0);
                    ReadFormat(t);
                    return true;
                }
            }
        }

        private void ReadFormat(IMFMediaType t)
        {
            try
            {
                MediaFactory.MFGetAttributeSize(t, MediaTypeAttributeKeys.FrameSize, out uint w, out uint h);
                _w = (int)w; _h = (int)h;
            }
            catch { _w = _h = 0; }
            _stride = _w;
            try { _stride = (int)t.GetUInt32(MediaTypeAttributeKeys.DefaultStride); } catch { }
            if (_stride < _w) _stride = _w;
            var info = _mft.GetOutputStreamInfo(0);
            _providesSamples = (info.Flags & ProvidesSamples) != 0;
            _outSize = Math.Max(info.Size, _stride * Math.Max(_h, 16) * 3 / 2);
        }

        public bool Decode(ReadOnlySpan<byte> annexB, ulong ptsUs, bool keyframe)
        {
            lock (_gate)
            {
                if (_disposed) return false;
                if (!_gotKey && !keyframe) return false; // nothing to build on until a keyframe
                if (keyframe) _gotKey = true;
                try
                {
                    using (var buf = MediaFactory.MFCreateMemoryBuffer(annexB.Length))
                    {
                        buf.Lock(out IntPtr ptr, out _, out _);
                        try { Marshal.Copy(annexB.ToArray(), 0, ptr, annexB.Length); } finally { buf.Unlock(); }
                        buf.CurrentLength = annexB.Length;
                        using var sample = MediaFactory.MFCreateSample();
                        sample.AddBuffer(buf);
                        sample.SampleTime = (long)ptsUs * 10;
                        _mft.ProcessInput(0, sample, 0);
                    }
                    Drain();
                    return true;
                }
                catch (Exception ex)
                {
                    AirBridge.Net.Log.Warn("mirror decode: " + ex.Message);
                    _gotKey = false; // start again from the next keyframe
                    try { _mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero); } catch { }
                    return false;
                }
            }
        }

        private void Drain()
        {
            while (!_disposed)
            {
                IMFSample? made = null;
                var odb = new OutputDataBuffer { StreamID = 0 };
                if (!_providesSamples)
                {
                    made = MediaFactory.MFCreateSample();
                    using var ob = MediaFactory.MFCreateMemoryBuffer(_outSize);
                    made.AddBuffer(ob);
                    odb.Sample = made;
                }
                try
                {
                    var res = _mft.ProcessOutput(ProcessOutputFlags.None, 1, ref odb, out _);
                    if (res.Code == NeedMoreInput) return;
                    if (res.Code == StreamChange) { if (!PickNv12()) throw new InvalidOperationException("no NV12 after a format change"); continue; }
                    if (res.Failure) throw new InvalidOperationException($"decoder error 0x{res.Code:X8}");
                    var sample = odb.Sample ?? made!;
                    Emit(sample);
                    if (odb.Sample != null && !ReferenceEquals(odb.Sample, made)) odb.Sample.Dispose();
                }
                finally { made?.Dispose(); }
            }
        }

        private void Emit(IMFSample sample)
        {
            if (_w < 2 || _h < 2) return;
            using var buf = sample.ConvertToContiguousBuffer();
            buf.Lock(out IntPtr ptr, out _, out int len);
            try
            {
                int need = _stride * _h * 3 / 2;
                if (len < need) return;
                var nv12 = new byte[need];
                Marshal.Copy(ptr, nv12, 0, need);
                int outStride = _w * 4;
                var bgra = new byte[outStride * _h];
                Nv12ToBgra(nv12, _stride, _w, _h, bgra, outStride);
                FrameReady?.Invoke(_w, _h, outStride, bgra);
            }
            finally { buf.Unlock(); }
        }

        /// <summary>BT.601 limited range, like the encoder's input.</summary>
        internal static void Nv12ToBgra(byte[] nv12, int stride, int w, int h, byte[] bgra, int outStride)
        {
            int uvBase = stride * h;
            for (int y = 0; y < h; y++)
            {
                int yRow = y * stride, uvRow = uvBase + (y >> 1) * stride, o = y * outStride;
                for (int x = 0; x < w; x++)
                {
                    int c = nv12[yRow + x] - 16;
                    int uv = uvRow + (x & ~1);
                    int d = nv12[uv] - 128, e = nv12[uv + 1] - 128;
                    int r = (298 * c + 409 * e + 128) >> 8;
                    int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                    int b = (298 * c + 516 * d + 128) >> 8;
                    bgra[o++] = (byte)Math.Clamp(b, 0, 255);
                    bgra[o++] = (byte)Math.Clamp(g, 0, 255);
                    bgra[o++] = (byte)Math.Clamp(r, 0, 255);
                    bgra[o++] = 255;
                }
            }
        }

        public void Dispose()
        {
            lock (_gate)
            {
                if (_disposed) return;
                _disposed = true;
                try { _mft.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero); _mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero); } catch { }
                try { _mft.Dispose(); } catch { }
            }
        }
    }

    /// <summary>
    /// <c>AirBridge.exe --decode-selftest</c>, in a child process: grab the screen once, encode it with this PC's encoder,
    /// decode it with its decoder, and check a picture of the right size comes out. Exit code 0 means this PC can show
    /// another device's screen.
    /// </summary>
    public static class WindowsDecodeSelfTest
    {
        public static int Run()
        {
            try
            {
                DpiBootstrap.EnterThread();
                var start = MediaFactory.MFStartup(false);
                if (start.Failure) return Fail($"Media Foundation did not start (0x{start.Code:X8})");
                var platform = new WindowsMirrorPlatform();
                var disp = platform.PickDisplay();
                var (w, h) = MirrorSourceCore.Fit(disp.PixelW, disp.PixelH, 640, 360);
                var frames = new List<EncodedFrame>();
                using var enc = MfH264Encoder.Create(w, h, 30, 1_000_000, frames.Add, out var err);
                if (enc == null) return Fail(err ?? "no encoder to make a test picture");
                using var grab = new GdiGrabber(disp, w, h);
                for (int i = 0; i < 12 && !frames.Any(f => f.AnnexB.Length > 0); i++)
                {
                    var f = grab.Grab(force: true);
                    if (f == null) return Fail("the screen could not be captured");
                    try { enc.Encode(f, 33_333L * (i + 1)); } finally { f.Release(); }
                }
                var key = frames.FirstOrDefault(f => f.AnnexB.Length > 0 && f.Keyframe);
                if (key == null) return Fail("no test picture");
                using var dec = MfH264Decoder.Create(out var derr);
                if (dec == null) return Fail(derr ?? "no decoder");
                int gotW = 0, gotH = 0;
                dec.FrameReady += (fw, fh, _, _) => { gotW = fw; gotH = fh; };
                dec.Decode(key.AnnexB, (ulong)key.PtsUs, true);
                // Some decoders hold the first picture until more data comes: feed the rest, then the same keyframe again.
                foreach (var f in frames.Where(f => f.AnnexB.Length > 0 && !ReferenceEquals(f, key))) { if (gotW > 0) break; dec.Decode(f.AnnexB, (ulong)f.PtsUs, f.Keyframe); }
                for (int i = 0; i < 4 && gotW == 0; i++) dec.Decode(key.AnnexB, (ulong)(key.PtsUs + 33_333 * (i + 1)), true);
                if (gotW == 0) return Fail("the decoder produced no picture");
                if (Math.Abs(gotW - w) > 16 || Math.Abs(gotH - h) > 16) return Fail($"the decoded picture is {gotW}x{gotH}, expected {w}x{h}");
                Console.WriteLine($"ok {gotW}x{gotH}");
                return 0;
            }
            catch (Exception ex) { return Fail(ex.Message); }
        }

        private static int Fail(string why) { Console.WriteLine(why); return 1; }
    }
}
