using System;
using System.Runtime.InteropServices;

namespace AirBridge.Input
{
    /// <summary>
    /// Picture maths for the screen mirror, kept free of Windows so AirBridge.MirrorTests can check it on any OS:
    /// BGRA (what a screen grab gives) to NV12 (what the H.264 encoder takes), and a fingerprint to tell whether the screen changed.
    /// </summary>
    public static class VideoPixels
    {
        /// <summary>Bytes of an NV12 picture: a full-size luma plane, then interleaved half-size U and V.</summary>
        public static int Nv12Size(int w, int h) => w * h + (w / 2) * (h / 2) * 2;

        /// <summary>
        /// BGRA to NV12, BT.709 with limited range (black = 16, white = 235), chroma averaged over each 2x2 block.
        /// <paramref name="w"/> and <paramref name="h"/> must be even. <paramref name="stride"/> is the BGRA row length in bytes.
        /// </summary>
        public static void BgraToNv12(ReadOnlySpan<byte> bgra, int stride, int w, int h, Span<byte> nv12)
        {
            if (w < 2 || h < 2 || (w & 1) != 0 || (h & 1) != 0) throw new ArgumentException("size must be even");
            if (bgra.Length < stride * (h - 1) + w * 4) throw new ArgumentException("picture too small");
            if (nv12.Length < Nv12Size(w, h)) throw new ArgumentException("output too small");
            int uvBase = w * h;
            for (int y = 0; y < h; y += 2)
            {
                var row0 = bgra.Slice(y * stride, w * 4);
                var row1 = bgra.Slice((y + 1) * stride, w * 4);
                int y0 = y * w, y1 = (y + 1) * w, uv = uvBase + (y / 2) * w;
                for (int x = 0; x < w; x += 2)
                {
                    int i = x * 4;
                    int b00 = row0[i], g00 = row0[i + 1], r00 = row0[i + 2];
                    int b01 = row0[i + 4], g01 = row0[i + 5], r01 = row0[i + 6];
                    int b10 = row1[i], g10 = row1[i + 1], r10 = row1[i + 2];
                    int b11 = row1[i + 4], g11 = row1[i + 5], r11 = row1[i + 6];
                    nv12[y0 + x] = Luma(r00, g00, b00);
                    nv12[y0 + x + 1] = Luma(r01, g01, b01);
                    nv12[y1 + x] = Luma(r10, g10, b10);
                    nv12[y1 + x + 1] = Luma(r11, g11, b11);
                    int r = (r00 + r01 + r10 + r11 + 2) >> 2, g = (g00 + g01 + g10 + g11 + 2) >> 2, b = (b00 + b01 + b10 + b11 + 2) >> 2;
                    nv12[uv + x] = (byte)(((-26 * r - 86 * g + 112 * b + 128) >> 8) + 128);
                    nv12[uv + x + 1] = (byte)(((112 * r - 102 * g - 10 * b + 128) >> 8) + 128);
                }
            }
        }

        private static byte Luma(int r, int g, int b) => (byte)(((47 * r + 157 * g + 16 * b + 128) >> 8) + 16);

        /// <summary>
        /// A hash of every pixel (8 bytes at a time). Two pictures with a different pixel anywhere give a different value
        /// (apart from the usual 2^-64 chance), so an unchanged screen can be skipped and a typed letter never is.
        /// </summary>
        public static ulong Fingerprint(ReadOnlySpan<byte> bgra, int stride, int w, int h)
        {
            ulong hash = 14695981039346656037UL;
            int rowBytes = w * 4;
            for (int y = 0; y < h; y++)
            {
                var row = bgra.Slice(y * stride, rowBytes);
                var words = MemoryMarshal.Cast<byte, ulong>(row.Slice(0, rowBytes & ~7));
                foreach (var v in words) hash = (hash ^ v) * 1099511628211UL;
                for (int i = rowBytes & ~7; i < rowBytes; i++) hash = (hash ^ row[i]) * 1099511628211UL;
                hash ^= (ulong)y; // a row moved elsewhere is a change too
            }
            return hash;
        }
    }
}
