using System;
using System.Buffers;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using Vortice.MediaFoundation;

namespace AirBridge.Input
{
    // The Windows half of the screen mirror (docs/protocol.md 8.7): a GDI screen grab and the Microsoft H.264 encoder from
    // Media Foundation. The protocol logic is MirrorSourceCore; the picture maths is VideoPixels and H264Stream (all of
    // those are checked on any OS). This file needs Windows, so the only proof it works is the self-test below, which
    // MirrorPlatformFactory runs on the real PC (in a separate process) before mirror_source is ever advertised.

    /// <summary>One captured picture as NV12, from a pool. Counted so the core can keep the last one for idle re-sends.</summary>
    internal sealed class Nv12Frame : ICapturedFrame
    {
        public byte[] Data { get; }
        public int Length { get; }
        private int _refs = 1;
        public Nv12Frame(int length) { Length = length; Data = ArrayPool<byte>.Shared.Rent(length); }
        public void Retain() => Interlocked.Increment(ref _refs);
        public void Release() { if (Interlocked.Decrement(ref _refs) == 0) ArrayPool<byte>.Shared.Return(Data); }
    }

    internal static class Gdi
    {
        public const int SRCCOPY = 0x00CC0020, CAPTUREBLT = 0x40000000, HALFTONE = 4, DI_NORMAL = 3, CURSOR_SHOWING = 1;

        [StructLayout(LayoutKind.Sequential)]
        public struct BITMAPINFOHEADER
        {
            public uint biSize; public int biWidth, biHeight; public ushort biPlanes, biBitCount; public uint biCompression, biSizeImage;
            public int biXPelsPerMeter, biYPelsPerMeter; public uint biClrUsed, biClrImportant;
        }
        [StructLayout(LayoutKind.Sequential)] public struct BITMAPINFO { public BITMAPINFOHEADER bmiHeader; public uint bmiColors; }
        [StructLayout(LayoutKind.Sequential)] public struct POINT { public int x, y; }
        [StructLayout(LayoutKind.Sequential)] public struct CURSORINFO { public int cbSize, flags; public IntPtr hCursor; public POINT ptScreenPos; }
        [StructLayout(LayoutKind.Sequential)] public struct ICONINFO { public bool fIcon; public int xHotspot, yHotspot; public IntPtr hbmMask, hbmColor; }

        [DllImport("user32.dll")] public static extern IntPtr GetDC(IntPtr hwnd);
        [DllImport("user32.dll")] public static extern int ReleaseDC(IntPtr hwnd, IntPtr hdc);
        [DllImport("user32.dll")] public static extern bool GetCursorInfo(ref CURSORINFO ci);
        [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
        [DllImport("user32.dll")] public static extern bool GetIconInfo(IntPtr hIcon, out ICONINFO info);
        [DllImport("user32.dll")] public static extern bool DrawIconEx(IntPtr hdc, int x, int y, IntPtr hIcon, int cx, int cy, int step, IntPtr brush, int flags);
        [DllImport("gdi32.dll")] public static extern IntPtr CreateCompatibleDC(IntPtr hdc);
        [DllImport("gdi32.dll")] public static extern bool DeleteDC(IntPtr hdc);
        [DllImport("gdi32.dll")] public static extern IntPtr CreateDIBSection(IntPtr hdc, ref BITMAPINFO bmi, uint usage, out IntPtr bits, IntPtr section, uint offset);
        [DllImport("gdi32.dll")] public static extern IntPtr SelectObject(IntPtr hdc, IntPtr obj);
        [DllImport("gdi32.dll")] public static extern bool DeleteObject(IntPtr obj);
        [DllImport("gdi32.dll")] public static extern int SetStretchBltMode(IntPtr hdc, int mode);
        [DllImport("gdi32.dll")] public static extern bool SetBrushOrgEx(IntPtr hdc, int x, int y, IntPtr old);
        [DllImport("gdi32.dll")] public static extern bool StretchBlt(IntPtr dst, int x, int y, int w, int h, IntPtr src, int sx, int sy, int sw, int sh, int rop);
        [DllImport("gdi32.dll")] public static extern bool GdiFlush();
    }

    /// <summary>Grabs one display, scaled to w x h, as NV12. Not thread safe: use it from one thread.</summary>
    internal sealed class GdiGrabber : IDisposable
    {
        private readonly MirrorDisplay _d;
        private readonly int _w, _h, _stride;
        private readonly IntPtr _screen, _mem, _dib, _old, _bits;
        private readonly byte[] _bgra;
        private ulong _lastPrint;
        private bool _havePrint;
        public int Failures { get; private set; }

        public GdiGrabber(MirrorDisplay d, int w, int h)
        {
            _d = d; _w = w; _h = h; _stride = w * 4;
            _screen = Gdi.GetDC(IntPtr.Zero);
            if (_screen == IntPtr.Zero) throw new InvalidOperationException("no screen device context");
            _mem = Gdi.CreateCompatibleDC(_screen);
            var bmi = new Gdi.BITMAPINFO { bmiHeader = new Gdi.BITMAPINFOHEADER { biSize = (uint)Marshal.SizeOf<Gdi.BITMAPINFOHEADER>(), biWidth = w, biHeight = -h, biPlanes = 1, biBitCount = 32, biCompression = 0 } };
            _dib = Gdi.CreateDIBSection(_mem, ref bmi, 0, out _bits, IntPtr.Zero, 0);
            if (_dib == IntPtr.Zero || _bits == IntPtr.Zero) { Dispose(); throw new InvalidOperationException("cannot create a bitmap"); }
            _old = Gdi.SelectObject(_mem, _dib);
            Gdi.SetStretchBltMode(_mem, Gdi.HALFTONE);
            Gdi.SetBrushOrgEx(_mem, 0, 0, IntPtr.Zero);
            _bgra = new byte[_stride * h];
        }

        /// <summary>The picture as NV12, or null when the screen has not changed since the last call (the core re-sends the last picture while idle).</summary>
        public Nv12Frame? Grab(bool force = false)
        {
            if (!Gdi.StretchBlt(_mem, 0, 0, _w, _h, _screen, _d.Left, _d.Top, _d.Width, _d.Height, Gdi.SRCCOPY | Gdi.CAPTUREBLT)) { Failures++; return null; }
            Failures = 0;
            DrawCursor();
            Gdi.GdiFlush();
            Marshal.Copy(_bits, _bgra, 0, _bgra.Length);
            ulong print = VideoPixels.Fingerprint(_bgra, _stride, _w, _h);
            if (!force && _havePrint && print == _lastPrint) return null;
            _lastPrint = print; _havePrint = true;
            var f = new Nv12Frame(VideoPixels.Nv12Size(_w, _h));
            VideoPixels.BgraToNv12(_bgra, _stride, _w, _h, f.Data);
            return f;
        }

        private void DrawCursor()
        {
            var ci = new Gdi.CURSORINFO { cbSize = Marshal.SizeOf<Gdi.CURSORINFO>() };
            if (!Gdi.GetCursorInfo(ref ci) || (ci.flags & Gdi.CURSOR_SHOWING) == 0 || ci.hCursor == IntPtr.Zero) return;
            int hx = 0, hy = 0;
            if (Gdi.GetIconInfo(ci.hCursor, out var ii))
            {
                hx = ii.xHotspot; hy = ii.yHotspot;
                if (ii.hbmMask != IntPtr.Zero) Gdi.DeleteObject(ii.hbmMask);
                if (ii.hbmColor != IntPtr.Zero) Gdi.DeleteObject(ii.hbmColor);
            }
            double sx = (double)_w / Math.Max(1, _d.Width), sy = (double)_h / Math.Max(1, _d.Height);
            int x = (int)Math.Round((ci.ptScreenPos.x - _d.Left - hx) * sx), y = (int)Math.Round((ci.ptScreenPos.y - _d.Top - hy) * sy);
            if (x < -64 || y < -64 || x >= _w || y >= _h) return;
            Gdi.DrawIconEx(_mem, x, y, ci.hCursor, 0, 0, 0, IntPtr.Zero, Gdi.DI_NORMAL);
        }

        public void Dispose()
        {
            if (_mem != IntPtr.Zero && _old != IntPtr.Zero) Gdi.SelectObject(_mem, _old);
            if (_dib != IntPtr.Zero) Gdi.DeleteObject(_dib);
            if (_mem != IntPtr.Zero) Gdi.DeleteDC(_mem);
            if (_screen != IntPtr.Zero) Gdi.ReleaseDC(IntPtr.Zero, _screen);
        }
    }

    /// <summary>Delivers a picture whenever the screen changes, up to <c>fps</c> a second, until disposed.</summary>
    internal sealed class GdiCapture : IMirrorCapture
    {
        private readonly MirrorDisplay _d;
        private readonly int _w, _h, _fps;
        private readonly Action<ICapturedFrame> _onFrame;
        private readonly Action<string> _onStopped;
        private Thread? _thread;
        private volatile bool _stop;

        public GdiCapture(MirrorDisplay d, int w, int h, int fps, Action<ICapturedFrame> onFrame, Action<string> onStopped)
        { _d = d; _w = w; _h = h; _fps = Math.Max(1, fps); _onFrame = onFrame; _onStopped = onStopped; }

        public void Start()
        {
            _thread = new Thread(Run) { IsBackground = true, Name = "airbridge-mirror-capture", Priority = ThreadPriority.AboveNormal };
            _thread.Start();
        }

        private void Run()
        {
            string? why = null;
            try
            {
                DpiBootstrap.EnterThread(); // physical pixels, like the monitor list
                using var g = new GdiGrabber(_d, _w, _h);
                var sw = Stopwatch.StartNew();
                long interval = Stopwatch.Frequency / _fps, next = 0;
                bool first = true;
                while (!_stop)
                {
                    var f = g.Grab(force: first);
                    first = false;
                    if (f != null) _onFrame(f);
                    // Many failures in a row: the screen is not available (locked, secure desktop, monitor gone).
                    if (g.Failures >= 30) { why = "screen not available"; break; }
                    next += interval;
                    long wait = next - sw.ElapsedTicks;
                    if (wait > 0) Thread.Sleep((int)Math.Max(1, wait * 1000 / Stopwatch.Frequency)); else next = sw.ElapsedTicks;
                }
            }
            catch (Exception ex) { why = "capture error: " + ex.Message; }
            if (!_stop && why != null) _onStopped(why);
        }

        public void Dispose()
        {
            _stop = true;
            var t = _thread;
            if (t != null && t != Thread.CurrentThread) t.Join(2000);
        }
    }

    /// <summary>The encoder control interface (codecapi.h): force a keyframe, change the bitrate. Declared here because the library does not wrap it.</summary>
    [ComImport, Guid("901db4c7-31ce-41a2-85dc-8fa0bf41b8da"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface ICodecApi
    {
        [PreserveSig] int IsSupported(ref Guid api);
        [PreserveSig] int IsModifiable(ref Guid api);
        [PreserveSig] int GetParameterRange(ref Guid api, [MarshalAs(UnmanagedType.Struct)] out object min, [MarshalAs(UnmanagedType.Struct)] out object max, [MarshalAs(UnmanagedType.Struct)] out object step);
        [PreserveSig] int GetParameterValues(ref Guid api, out IntPtr values, out uint count);
        [PreserveSig] int GetDefaultValue(ref Guid api, [MarshalAs(UnmanagedType.Struct)] out object value);
        [PreserveSig] int GetValue(ref Guid api, [MarshalAs(UnmanagedType.Struct)] out object value);
        [PreserveSig] int SetValue(ref Guid api, [In, MarshalAs(UnmanagedType.Struct)] ref object value);
    }

    /// <summary>The Microsoft H.264 encoder (a Media Foundation transform): NV12 in, Annex-B out, low latency, no B-frames.</summary>
    internal sealed class MfH264Encoder : IMirrorEncoder
    {
        private static readonly Guid ApiLowLatency = new("9c27891a-ed7a-40e1-88e8-b22727a024ee");
        private static readonly Guid ApiRateControl = new("1c0608e9-370c-4710-8a58-cb6181c42423");
        private static readonly Guid ApiMeanBitrate = new("f7222374-2144-4815-b550-a37f8e12ee52");
        private static readonly Guid ApiGopSize = new("95f31b26-95a4-41aa-9303-246a7fc6eef1");
        private static readonly Guid ApiBFrames = new("8d390aac-dc5c-4200-b57f-814d04babab2");
        private static readonly Guid ApiForceKeyFrame = new("398c1b98-8353-475a-9ef2-8f265d260345");
        private const int NeedMoreInput = unchecked((int)0xC00D6D72), StreamChange = unchecked((int)0xC00D6D61);
        private const int ProvidesSamples = 0x100;

        public string Codec => "h264";
        private readonly IMFTransform _mft;
        private readonly Action<EncodedFrame> _output;
        private readonly int _w, _h, _fps;
        private readonly ICodecApi? _api;
        private readonly bool _providesSamples;
        private readonly int _outBufSize;
        private byte[]? _header;
        private bool _headerRead, _disposed;

        private MfH264Encoder(IMFTransform mft, int w, int h, int fps, Action<EncodedFrame> output)
        {
            _mft = mft; _w = w; _h = h; _fps = fps; _output = output;
            try { _api = Marshal.GetObjectForIUnknown(mft.NativePointer) as ICodecApi; } catch { _api = null; }
            var info = mft.GetOutputStreamInfo(0);
            _providesSamples = (info.Flags & ProvidesSamples) != 0;
            _outBufSize = Math.Max(1 << 20, info.Size);
        }

        /// <summary>Makes an encoder, or returns null with the reason in <paramref name="error"/>.</summary>
        public static MfH264Encoder? Create(int w, int h, int fps, int bitrate, Action<EncodedFrame> output, out string? error)
        {
            error = null;
            try
            {
                using var found = MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoEncoder,
                    (uint)(EnumFlag.EnumFlagSyncmft | EnumFlag.EnumFlagLocalmft | EnumFlag.EnumFlagSortandfilter), null,
                    new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.H264 });
                var activate = found.FirstOrDefault();
                if (activate == null) { error = "no H.264 encoder on this PC"; return null; }
                IMFTransform mft = activate.ActivateObject<IMFTransform>();

                using (var o = MediaFactory.MFCreateMediaType())
                {
                    o.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                    o.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.H264);
                    o.Set(MediaTypeAttributeKeys.AvgBitrate, (uint)bitrate);
                    MediaFactory.MFSetAttributeSize(o, MediaTypeAttributeKeys.FrameSize, (uint)w, (uint)h);
                    MediaFactory.MFSetAttributeRatio(o, MediaTypeAttributeKeys.FrameRate, (uint)fps, 1);
                    MediaFactory.MFSetAttributeRatio(o, MediaTypeAttributeKeys.PixelAspectRatio, 1, 1);
                    o.Set(MediaTypeAttributeKeys.InterlaceMode, 2u);   // progressive
                    o.Set(MediaTypeAttributeKeys.Mpeg2Profile, 66u);   // Baseline: no B-frames, decodes everywhere
                    mft.SetOutputType(0, o, 0);
                }
                using (var i = MediaFactory.MFCreateMediaType())
                {
                    i.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                    i.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.NV12);
                    MediaFactory.MFSetAttributeSize(i, MediaTypeAttributeKeys.FrameSize, (uint)w, (uint)h);
                    MediaFactory.MFSetAttributeRatio(i, MediaTypeAttributeKeys.FrameRate, (uint)fps, 1);
                    MediaFactory.MFSetAttributeRatio(i, MediaTypeAttributeKeys.PixelAspectRatio, 1, 1);
                    i.Set(MediaTypeAttributeKeys.InterlaceMode, 2u);
                    mft.SetInputType(0, i, 0);
                }
                var enc = new MfH264Encoder(mft, w, h, fps, output);
                // Low latency, constant bitrate, a keyframe at least every 2 s, no B-frames. Each is optional: if one is refused
                // the encoder still works, just with the encoder's own default for it.
                enc.Set(ApiLowLatency, true);
                enc.Set(ApiRateControl, 0u);                    // CBR
                enc.Set(ApiMeanBitrate, (uint)bitrate);
                enc.Set(ApiGopSize, (uint)(fps * 2));
                enc.Set(ApiBFrames, 0u);
                mft.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
                mft.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
                return enc;
            }
            catch (Exception ex) { error = "encoder setup failed: " + ex.Message; return null; }
        }

        private bool Set(Guid api, object value)
        {
            if (_api == null) return false;
            try { return _api.SetValue(ref api, ref value) >= 0; } catch { return false; }
        }

        public void RequestKeyframe() => Set(ApiForceKeyFrame, 1u);
        public void SetBitrate(int bps) => Set(ApiMeanBitrate, (uint)Math.Max(100_000, bps));

        public void Encode(ICapturedFrame frame, long ptsUs)
        {
            if (_disposed) return;
            var f = (Nv12Frame)frame;
            var sw = Stopwatch.StartNew();
            using (var buf = MediaFactory.MFCreateMemoryBuffer(f.Length))
            {
                buf.Lock(out IntPtr ptr, out _, out _);
                try { Marshal.Copy(f.Data, 0, ptr, f.Length); } finally { buf.Unlock(); }
                buf.CurrentLength = f.Length;
                using var sample = MediaFactory.MFCreateSample();
                sample.AddBuffer(buf);
                sample.SampleTime = ptsUs * 10;                  // 100 ns units
                sample.SampleDuration = 10_000_000L / _fps;
                _mft.ProcessInput(0, sample, 0);
            }
            Drain(sw.Elapsed.TotalMilliseconds);
        }

        /// <summary>Takes every finished frame out of the encoder.</summary>
        private void Drain(double encodeMs)
        {
            while (!_disposed)
            {
                IMFSample? made = null;
                var odb = new OutputDataBuffer { StreamID = 0 };
                if (!_providesSamples)
                {
                    made = MediaFactory.MFCreateSample();
                    using var ob = MediaFactory.MFCreateMemoryBuffer(_outBufSize);
                    made.AddBuffer(ob);
                    odb.Sample = made;
                }
                try
                {
                    var res = _mft.ProcessOutput(ProcessOutputFlags.None, 1, ref odb, out _);
                    if (res.Code == NeedMoreInput) return;
                    if (res.Code == StreamChange) { _mft.SetOutputType(0, _mft.GetOutputAvailableType(0, 0), 0); continue; }
                    if (res.Failure) throw new InvalidOperationException($"encoder error 0x{res.Code:X8}");
                    var sample = odb.Sample ?? made!;
                    Emit(sample, encodeMs);
                    if (odb.Sample != null && !ReferenceEquals(odb.Sample, made)) odb.Sample.Dispose();
                }
                finally { made?.Dispose(); }
            }
        }

        private void Emit(IMFSample sample, double encodeMs)
        {
            using var contiguous = sample.ConvertToContiguousBuffer();
            contiguous.Lock(out IntPtr ptr, out _, out int len);
            var bytes = new byte[len];
            try { Marshal.Copy(ptr, bytes, 0, len); } finally { contiguous.Unlock(); }
            if (len == 0) { _output(new EncodedFrame(sample.SampleTime / 10, false, false, Array.Empty<byte>(), encodeMs)); return; }
            var annexB = H264Stream.EnsureParameterSets(bytes, SequenceHeader(), out _);
            var s = H264Stream.Analyze(annexB);
            _output(new EncodedFrame(sample.SampleTime / 10, s.HasIdr, s.HasSps && s.HasPps, annexB, encodeMs));
        }

        /// <summary>The encoder's SPS+PPS, for keyframes that come without them.</summary>
        private byte[] SequenceHeader()
        {
            if (_headerRead) return _header ?? Array.Empty<byte>();
            try { using var t = _mft.GetOutputCurrentType(0); _header = t.GetBlob(MediaTypeAttributeKeys.MpegSequenceHeader); }
            catch { _header = null; }
            _headerRead = _header != null; // keep asking until the encoder has produced it
            return _header ?? Array.Empty<byte>();
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            try { _mft.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero); _mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero); } catch { }
            try { if (_api != null) Marshal.ReleaseComObject(_api); } catch { }
            try { _mft.Dispose(); } catch { }
        }
    }

    /// <summary>Capture and encoding for this PC. Only made after the self-test passed.</summary>
    internal sealed class WindowsMirrorPlatform : IMirrorPlatform
    {
        public MirrorUnavailable? Unavailable => null;
        public int MaxFps => 30; // CPU capture and conversion: 30 is what it can keep up with

        public MirrorDisplay PickDisplay()
        {
            var mons = Monitors.Enumerate();
            Gdi.GetCursorPos(out var p);
            var m = mons.FirstOrDefault(x => x.Bounds.Contains(p.x, p.y)) ?? mons.FirstOrDefault(x => x.Primary) ?? mons[0];
            var b = m.Bounds;
            return new MirrorDisplay(b.Left, b.Top, b.Width, b.Height, b.Width, b.Height);
        }

        public IMirrorEncoder? CreateEncoder(IReadOnlyList<string> codecs, int w, int h, int fps, int bitrate, Action<EncodedFrame> output)
        {
            if (!codecs.Contains("h264")) return null; // only H.264 here
            var e = MfH264Encoder.Create(w, h, fps, bitrate, output, out var err);
            if (e == null) AirBridge.Net.Log.Warn("mirror: " + err);
            return e;
        }

        public IMirrorCapture? CreateCapture(MirrorDisplay display, int w, int h, int fps, Action<ICapturedFrame> onFrame, Action<string> onStopped)
            => new GdiCapture(display, w, h, fps, onFrame, onStopped);
    }

    /// <summary>
    /// What <c>AirBridge.exe --mirror-selftest</c> does, in a child process: start Media Foundation, grab the real screen once,
    /// encode it, and check the result is a proper keyframe (SPS, PPS and IDR). Exit code 0 means this PC can mirror.
    /// The reason for a failure is written to standard output.
    /// </summary>
    public static class WindowsMirrorSelfTest
    {
        public static int Run()
        {
            try
            {
                DpiBootstrap.EnterThread();
                var start = MediaFactory.MFStartup(false);
                if (start.Failure) return Fail($"Media Foundation did not start (0x{start.Code:X8})");
                var platform = new WindowsMirrorPlatform();
                var d = platform.PickDisplay();
                if (d.Width < 2 || d.Height < 2) return Fail("no display found");
                var (w, h) = MirrorSourceCore.Fit(d.PixelW, d.PixelH, 640, 360);
                var got = new List<EncodedFrame>();
                using var enc = MfH264Encoder.Create(w, h, 30, 1_000_000, got.Add, out var err);
                if (enc == null) return Fail(err ?? "no encoder");
                using var grab = new GdiGrabber(d, w, h);
                for (int i = 0; i < 12 && !got.Any(f => f.AnnexB.Length > 0); i++)
                {
                    var f = grab.Grab(force: true);
                    if (f == null) return Fail("the screen could not be captured");
                    try { enc.Encode(f, 33_333L * (i + 1)); } finally { f.Release(); }
                }
                var key = got.FirstOrDefault(f => f.AnnexB.Length > 0);
                if (key == null) return Fail("the encoder produced no frame");
                if (!key.Keyframe) return Fail("the first frame is not a keyframe");
                if (!key.HasParameterSets) return Fail("the keyframe has no SPS and PPS");
                if (key.AnnexB.Length < 200) return Fail("the keyframe is suspiciously small");
                Console.WriteLine($"ok {w}x{h} {key.AnnexB.Length} bytes");
                return 0;
            }
            catch (Exception ex) { return Fail(ex.Message); }
        }

        private static int Fail(string why) { Console.WriteLine(why); return 1; }
    }
}
