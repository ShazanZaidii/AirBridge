using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using AirBridge.Net;

namespace AirBridge.Input
{
    /// <summary>
    /// Windows as a screen-mirror source (docs/protocol.md 8.7): the glue between the network (<see cref="PeerBus"/>) and
    /// <see cref="MirrorSourceCore"/>. The capture and encoder come from <see cref="MirrorPlatformFactory"/>. Windows
    /// advertises <c>mirror_source</c> only when <see cref="Available"/> is true, which means a self-test on this very PC
    /// managed to capture the screen and encode a frame. If it did not, the cap is simply absent and no phone ever asks.
    /// </summary>
    public static class MirrorSource
    {
        private static readonly object Gate = new();
        private static IMirrorPlatform? _platform;
        private static MirrorSourceCore? _core;
        private static Timer? _timer;

        /// <summary>Decided once, on first use (the first hello). The self-test runs on its own thread so COM sees a clean apartment.</summary>
        private static IMirrorPlatform Platform
        {
            get
            {
                lock (Gate)
                {
                    if (_platform != null) return _platform;
                    IMirrorPlatform? made = null;
                    try
                    {
                        var t = new Thread(() => { try { made = MirrorPlatformFactory.Create(); } catch (Exception ex) { Log.Warn($"mirror: self-test failed: {ex.Message}"); } }) { IsBackground = true, Name = "airbridge-mirror-selftest" };
                        t.SetApartmentState(ApartmentState.MTA);
                        t.Start();
                        if (!t.Join(TimeSpan.FromSeconds(8))) Log.Warn("mirror: self-test timed out");
                    }
                    catch (Exception ex) { Log.Warn($"mirror: self-test could not run: {ex.Message}"); }
                    _platform = made ?? new UnavailableMirrorPlatform("mirror_codec", "Screen mirroring is not available on this PC.");
                    Log.Info(_platform.Unavailable == null ? "mirror: this PC can mirror its screen" : $"mirror: not available ({_platform.Unavailable.Message})");
                    return _platform;
                }
            }
        }

        /// <summary>True when this PC can capture its screen and encode it. Also what decides the <c>mirror_source</c> cap.</summary>
        public static bool Available => Platform.Unavailable == null;

        private static MirrorSourceCore Core
        {
            get
            {
                lock (Gate)
                {
                    if (_core != null) return _core;
                    _core = new MirrorSourceCore(Platform, new Host());
                    _timer = new Timer(_ => { try { _core?.Tick(); } catch (Exception ex) { Log.Warn($"mirror tick: {ex.Message}"); } }, null, 100, 100);
                    return _core;
                }
            }
        }

        /// <summary>mirror_start / mirror_stop / mirror_key / mirror_ack from a peer (network thread).</summary>
        public static void Handle(string peer, string type, JsonObject msg)
        {
            try { Core.Handle(peer, type, msg); }
            catch (Exception ex) { Log.Error("mirror handler failed", ex); }
        }

        public static void PeersChanged(IReadOnlyList<PeerInfo> peers)
        {
            if (_core == null) return;
            try { _core.PeersChanged(peers.Select(p => p.Id).ToHashSet()); } catch (Exception ex) { Log.Warn($"mirror peers: {ex.Message}"); }
        }

        public static void PeerGone(string peer) { try { _core?.PeerGone(peer); } catch (Exception ex) { Log.Warn($"mirror peer gone: {ex.Message}"); } }

        /// <summary>The mirrored display if <paramref name="peer"/> is the current sink: its absolute pointer input maps onto it.</summary>
        public static MirrorDisplay? MirroredDisplay(string peer) => _core?.MirroredDisplay(peer);

        public static void Shutdown() { try { _core?.Shutdown(); _timer?.Dispose(); } catch { } }

        private sealed class Host : IMirrorHost
        {
            public bool Send(string peer, JsonObject msg) => PeerBus.Send(peer, msg);
            public bool SendBinary(string peer, byte[] frame) => PeerBus.SendBinary(peer, frame);
            public bool IsSink(string peer, out string name)
            {
                var p = PeerBus.Peers().FirstOrDefault(x => x.Id == peer && x.Caps.Contains("mirror_sink"));
                name = p?.Name ?? "";
                return p != null;
            }
            public double Now() => Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency;
            public void Log(string text) => AirBridge.Net.Log.Info(text);
        }
    }

    /// <summary>A platform that cannot mirror, with the reason. Used when the self-test fails.</summary>
    public sealed class UnavailableMirrorPlatform : IMirrorPlatform
    {
        public MirrorUnavailable? Unavailable { get; }
        public UnavailableMirrorPlatform(string code, string message) { Unavailable = new MirrorUnavailable(code, message); }
        public int MaxFps => 30;
        public MirrorDisplay PickDisplay() => new(0, 0, 0, 0, 0, 0);
        public IMirrorEncoder? CreateEncoder(IReadOnlyList<string> codecs, int w, int h, int fps, int bitrate, Action<EncodedFrame> output) => null;
        public IMirrorCapture? CreateCapture(MirrorDisplay display, int w, int h, int fps, Action<ICapturedFrame> onFrame, Action<string> onStopped) => null;
    }

    /// <summary>
    /// Makes the real Windows platform, or one that declines. First it runs <c>AirBridge.exe --mirror-selftest</c> as a separate
    /// process: it grabs the real screen and encodes it with the real encoder. If that crashes, hangs or fails, only the throwaway
    /// process is affected and mirror_source is simply not advertised.
    /// </summary>
    public static class MirrorPlatformFactory
    {
        public static IMirrorPlatform Create()
        {
            static IMirrorPlatform No(string why) => new UnavailableMirrorPlatform("mirror_codec", why);
            if (!OperatingSystem.IsWindows() || Environment.OSVersion.Version.Build < 17763) return No("Screen mirroring needs Windows 10 version 1809 or newer.");
            string? exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return No("Screen mirroring could not start its self-test.");
            try
            {
                var psi = new ProcessStartInfo(exe, "--mirror-selftest") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true };
                using var p = Process.Start(psi);
                if (p == null) return No("Screen mirroring could not start its self-test.");
                var read = System.Threading.Tasks.Task.Run(() => p.StandardOutput.ReadToEnd());
                if (!p.WaitForExit(8000))
                {
                    try { p.Kill(true); } catch { }
                    return No("The screen-mirroring self-test did not finish.");
                }
                string said = read.Wait(1000) ? read.Result.Trim() : "";
                if (p.ExitCode != 0) return No(said.Length > 0 ? said : $"The screen-mirroring self-test failed (exit {p.ExitCode}).");
                AirBridge.Net.Log.Info("mirror: self-test passed (" + said + ")");
            }
            catch (Exception ex) { return No("The screen-mirroring self-test could not run: " + ex.Message); }
            var start = Vortice.MediaFoundation.MediaFactory.MFStartup(false);
            if (start.Failure) return No($"Media Foundation did not start (0x{start.Code:X8}).");
            return new WindowsMirrorPlatform();
        }
    }
}
