using System;
using System.Collections.Generic;

namespace AirBridge.Input
{
    /// <summary>What an Annex-B access unit holds (docs/protocol.md 8.7).</summary>
    public readonly record struct H264Summary(bool HasSps, bool HasPps, bool HasIdr, int NalCount);

    /// <summary>
    /// Annex-B H.264 handling for the screen mirror, free of Windows so it is checked on any OS. The sink wants 4-byte start
    /// codes, and every keyframe must carry its parameter sets (SPS, PPS) in front of the IDR slice, which not every encoder does.
    /// </summary>
    public static class H264Stream
    {
        /// <summary>Start of each NAL unit: (offset of the start code, start code length).</summary>
        private static List<(int Start, int CodeLen)> StartCodes(ReadOnlySpan<byte> d)
        {
            var list = new List<(int, int)>();
            for (int i = 0; i + 2 < d.Length; i++)
            {
                if (d[i] != 0 || d[i + 1] != 0) continue;
                if (d[i + 2] == 1) { list.Add((i, 3)); i += 2; }
                else if (d[i + 2] == 0 && i + 3 < d.Length && d[i + 3] == 1) { list.Add((i, 4)); i += 3; }
            }
            return list;
        }

        public static H264Summary Analyze(ReadOnlySpan<byte> d)
        {
            bool sps = false, pps = false, idr = false; int n = 0;
            foreach (var (start, len) in StartCodes(d))
            {
                int at = start + len;
                if (at >= d.Length) continue;
                int type = d[at] & 0x1F;
                n++;
                if (type == 7) sps = true; else if (type == 8) pps = true; else if (type == 5) idr = true;
            }
            return new H264Summary(sps, pps, idr, n);
        }

        /// <summary>The same stream with every NAL unit preceded by a 4-byte start code (00 00 00 01).</summary>
        public static byte[] NormalizeStartCodes(ReadOnlySpan<byte> d)
        {
            var codes = StartCodes(d);
            if (codes.Count == 0) return d.ToArray();
            var o = new List<byte>(d.Length + 8);
            for (int k = 0; k < codes.Count; k++)
            {
                int bodyStart = codes[k].Start + codes[k].CodeLen;
                int bodyEnd = k + 1 < codes.Count ? codes[k + 1].Start : d.Length;
                // A 3-byte start code that follows a NAL leaves a trailing zero that belongs to a 4-byte code: already handled above.
                o.AddRange(new byte[] { 0, 0, 0, 1 });
                for (int i = bodyStart; i < bodyEnd; i++) o.Add(d[i]);
            }
            return o.ToArray();
        }

        /// <summary>
        /// Makes a keyframe self-contained: when the access unit has an IDR slice but no SPS and PPS, puts
        /// <paramref name="parameterSets"/> (an Annex-B SPS+PPS blob, e.g. the encoder's sequence header) in front.
        /// Returns the stream with 4-byte start codes. <paramref name="added"/> says whether anything was put in front.
        /// </summary>
        public static byte[] EnsureParameterSets(ReadOnlySpan<byte> d, ReadOnlySpan<byte> parameterSets, out bool added)
        {
            added = false;
            var s = Analyze(d);
            var body = NormalizeStartCodes(d);
            if (!s.HasIdr || (s.HasSps && s.HasPps) || parameterSets.IsEmpty) return body;
            var ps = Analyze(parameterSets);
            if (!ps.HasSps || !ps.HasPps) return body;
            var head = NormalizeStartCodes(parameterSets);
            var o = new byte[head.Length + body.Length];
            Buffer.BlockCopy(head, 0, o, 0, head.Length);
            Buffer.BlockCopy(body, 0, o, head.Length, body.Length);
            added = true;
            return o;
        }
    }
}
