using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace AirBridge.Drop
{
    // Pure helpers for the drop shelf and the transfer cards (no WPF), so they can be checked on any OS
    // (project AirBridge.DropTests).

    /// <summary>One round drop target in the dock. Coordinates: dock body, top-left origin, y down.</summary>
    public sealed record DockSlot(string PeerId, double X, double Y, double Diameter)
    {
        public double Radius => Diameter / 2;
    }

    public enum DockMode { None, Single, Several }

    /// <summary>
    /// Deterministic geometry of the dock, shared by the view (to place bubbles) and the drop code (to hit-test),
    /// so what you see is exactly what you hit.
    /// </summary>
    public sealed class DockLayout
    {
        public const double Padding = 14, Header = 20, NameHeight = 18, Gap = 6;

        public DockMode Mode { get; }
        public double Width { get; }
        public double Height { get; }
        public IReadOnlyList<DockSlot> Slots { get; }

        public DockLayout(IReadOnlyList<string> devices)
        {
            double p = Padding, top = Padding + Header + Gap;
            var slots = new List<DockSlot>();
            switch (devices.Count)
            {
                case 0:
                    Mode = DockMode.None;
                    Width = 250;
                    Height = top + 56 + p;
                    break;
                case 1:
                {
                    Mode = DockMode.Single;
                    const double big = 76, slot = 150;
                    slots.Add(new DockSlot(devices[0], p + slot / 2, top + big / 2, big));
                    Width = p * 2 + slot;
                    Height = top + big + Gap + NameHeight + p;
                    break;
                }
                default:
                {
                    Mode = DockMode.Several;
                    const double d = 54, slot = 92;
                    double row = devices.Count * slot;
                    Width = Math.Max(250, p * 2 + row);
                    double x0 = (Width - row) / 2;
                    for (int i = 0; i < devices.Count; i++) slots.Add(new DockSlot(devices[i], x0 + slot * (i + 0.5), top + d / 2, d));
                    Height = top + d + Gap + NameHeight + p;
                    break;
                }
            }
            Slots = slots;
        }

        /// <summary>The target under (x, y). A drop a little outside a bubble or on its name still counts, and
        /// between two bubbles the nearer one wins.</summary>
        public DockSlot? Hit(double x, double y, double slack = 18)
        {
            DockSlot? best = null;
            double bestEdge = double.MaxValue;
            foreach (var s in Slots)
            {
                double dy = y - s.Y;
                if (dy > 0) dy = Math.Max(0, dy - (Gap + NameHeight)); // the name under the bubble
                double d = Math.Sqrt((x - s.X) * (x - s.X) + dy * dy);
                if (d > s.Radius + slack) continue;
                if (d - s.Radius < bestEdge) { best = s; bestEdge = d - s.Radius; }
            }
            return best;
        }

        /// <summary>How strongly the cursor pulls a bubble: 1 over it, falling to 0 at <paramref name="reach"/> from its edge.</summary>
        public static double Pull(DockSlot s, double? x, double? y, double reach = 90)
        {
            if (x is not double cx || y is not double cy) return 0;
            double edge = Math.Max(0, Math.Sqrt((cx - s.X) * (cx - s.X) + (cy - s.Y) * (cy - s.Y)) - s.Radius);
            return Math.Clamp(1 - edge / reach, 0, 1);
        }

        /// <summary>Small shift of a bubble towards the cursor (the "magnet"), at most <paramref name="maxShift"/>.</summary>
        public static (double Dx, double Dy) Lean(DockSlot s, double? x, double? y, double maxShift = 7)
        {
            if (x is not double cx || y is not double cy) return (0, 0);
            double dx = cx - s.X, dy = cy - s.Y, dist = Math.Sqrt(dx * dx + dy * dy);
            if (dist < 0.5) return (0, 0);
            double k = Pull(s, cx, cy) * Math.Min(1, dist / 40) * maxShift;
            return (dx / dist * k, dy / dist * k);
        }
    }

    public static class DropFiles
    {
        /// <summary>Most files one transfer offers (the protocol allows 10 000).</summary>
        public const int MaxFiles = 10_000;

        /// <summary>Regular files in <paramref name="paths"/>, with folders walked recursively (hidden and system
        /// entries and reparse points skipped), in a stable order, without duplicates.</summary>
        public static List<string> Expand(IEnumerable<string> paths, int limit = MaxFiles)
        {
            var output = new List<string>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            void Add(string f)
            {
                if (output.Count < limit && seen.Add(Path.GetFullPath(f))) output.Add(f);
            }
            foreach (var p in paths)
            {
                if (output.Count >= limit) break;
                try
                {
                    if (File.Exists(p)) { Add(p); continue; }
                    if (!Directory.Exists(p)) continue;
                    var opts = new EnumerationOptions
                    {
                        RecurseSubdirectories = true, IgnoreInaccessible = true,
                        AttributesToSkip = FileAttributes.Hidden | FileAttributes.System | FileAttributes.ReparsePoint,
                    };
                    var files = Directory.EnumerateFiles(p, "*", opts).Take(limit).ToList();
                    files.Sort(StringComparer.OrdinalIgnoreCase);
                    foreach (var f in files) Add(f);
                }
                catch { /* unreadable: skip */ }
            }
            return output;
        }

        /// <summary>"Report.pdf" or "3 files".</summary>
        public static string Title(IReadOnlyList<string> files) =>
            files.Count == 1 ? Path.GetFileName(files[0]) : $"{files.Count} files";
    }

    public static class TransferText
    {
        private static readonly string[] Units = { "B", "KB", "MB", "GB", "TB" };

        public static string Bytes(long n)
        {
            double v = n;
            int u = 0;
            while (v >= 1000 && u < Units.Length - 1) { v /= 1000; u++; }
            return u == 0 ? $"{n} B" : v >= 100 ? $"{v:0} {Units[u]}" : $"{v:0.#} {Units[u]}";
        }

        public static string Speed(double bps) => Bytes((long)bps) + "/s";

        /// <summary>"4 s left", "2 min left", "1 h 5 min left"; null when unknown or under a second.</summary>
        public static string? TimeLeft(long total, long done, double bytesPerSec)
        {
            if (bytesPerSec <= 0 || total <= done) return null;
            double s = (total - done) / bytesPerSec;
            if (double.IsNaN(s) || double.IsInfinity(s) || s < 1) return null;
            int secs = (int)Math.Ceiling(s);
            if (secs < 60) return $"{secs} s left";
            int mins = (int)Math.Round(secs / 60.0);
            if (mins < 60) return $"{mins} min left";
            return $"{mins / 60} h {mins % 60} min left";
        }
    }
}
