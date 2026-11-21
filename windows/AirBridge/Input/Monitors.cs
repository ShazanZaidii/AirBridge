namespace AirBridge.Input
{
    /// <summary>Integer rectangle in physical (per-monitor-v2) virtual-screen pixels; Right/Bottom exclusive.</summary>
    public readonly record struct IRect(int Left, int Top, int Right, int Bottom)
    {
        public int Width => Right - Left;
        public int Height => Bottom - Top;
        public bool Contains(int x, int y) => x >= Left && x < Right && y >= Top && y < Bottom;
        public bool Contains(double x, double y) => x >= Left && x < Right && y >= Top && y < Bottom;
    }

    public sealed record MonitorInfo(IRect Bounds, bool Primary, double Scale, string Device);

    /// <summary>
    /// A piece of a monitor side that faces nothing (no other monitor anywhere beyond it).
    /// Fixed = x for Left/Right (Left: first column, Right: exclusive right), y for Top/Bottom.
    /// [From, To) is the range along the edge.
    /// </summary>
    public readonly record struct EdgeSegment(Edge Edge, int Fixed, int From, int To, IRect Monitor);

    public static class Monitors
    {
        /// <summary>Enumerates monitors in physical pixels (temporarily switches the calling thread to PMv2).</summary>
        public static List<MonitorInfo> Enumerate()
        {
            var list = new List<MonitorInfo>();
            IntPtr prev = DpiBootstrap.EnterThread();
            try
            {
                NativeMethods.MonitorEnumProc cb = (IntPtr hMon, IntPtr hdc, ref NativeMethods.RECT r, IntPtr data) =>
                {
                    var mi = new NativeMethods.MONITORINFOEX { cbSize = System.Runtime.InteropServices.Marshal.SizeOf<NativeMethods.MONITORINFOEX>(), szDevice = "" };
                    if (!NativeMethods.GetMonitorInfo(hMon, ref mi)) return true;
                    double scale = 1.0;
                    try
                    {
                        if (NativeMethods.GetDpiForMonitor(hMon, NativeMethods.MDT_EFFECTIVE_DPI, out uint dx, out _) == 0 && dx > 0)
                            scale = dx / 96.0;
                    }
                    catch { }
                    var b = mi.rcMonitor;
                    list.Add(new MonitorInfo(new IRect(b.left, b.top, b.right, b.bottom),
                        (mi.dwFlags & NativeMethods.MONITORINFOF_PRIMARY) != 0, scale, mi.szDevice ?? ""));
                    return true;
                };
                NativeMethods.EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, cb, IntPtr.Zero);
                GC.KeepAlive(cb);
            }
            finally { DpiBootstrap.RestoreThread(prev); }

            if (list.Count == 0)
            {
                int w = Math.Max(1, NativeMethods.GetSystemMetrics(0)), h = Math.Max(1, NativeMethods.GetSystemMetrics(1));
                list.Add(new MonitorInfo(new IRect(0, 0, w, h), true, 1.0, ""));
            }
            // Stable order: primary first, then left-to-right.
            list.Sort((a, b) => a.Primary != b.Primary ? (a.Primary ? -1 : 1) : a.Bounds.Left != b.Bounds.Left ? a.Bounds.Left.CompareTo(b.Bounds.Left) : a.Bounds.Top.CompareTo(b.Bounds.Top));
            return list;
        }

        public static IRect Union(IReadOnlyList<MonitorInfo> mons)
        {
            int l = int.MaxValue, t = int.MaxValue, r = int.MinValue, b = int.MinValue;
            foreach (var m in mons)
            {
                l = Math.Min(l, m.Bounds.Left); t = Math.Min(t, m.Bounds.Top);
                r = Math.Max(r, m.Bounds.Right); b = Math.Max(b, m.Bounds.Bottom);
            }
            return mons.Count == 0 ? new IRect(0, 0, 1, 1) : new IRect(l, t, r, b);
        }

        /// <summary>
        /// Outer segments for an edge: parts of a monitor side where a ray cast outward hits no other monitor.
        /// A monitor to the left of the primary therefore never counts as a "left edge" of the primary.
        /// </summary>
        public static List<EdgeSegment> OuterSegments(IReadOnlyList<MonitorInfo> mons, Edge edge)
        {
            var result = new List<EdgeSegment>();
            foreach (var m in mons)
            {
                var b = m.Bounds;
                bool vertical = edge.IsVertical();
                var free = new List<(int a, int z)> { vertical ? (b.Top, b.Bottom) : (b.Left, b.Right) };
                foreach (var n in mons)
                {
                    if (ReferenceEquals(n, m)) continue;
                    var o = n.Bounds;
                    bool beyond = edge switch
                    {
                        Edge.Left => o.Left < b.Left && o.Right <= b.Left,
                        Edge.Right => o.Right > b.Right && o.Left >= b.Right,
                        Edge.Top => o.Top < b.Top && o.Bottom <= b.Top,
                        _ => o.Bottom > b.Bottom && o.Top >= b.Bottom,
                    };
                    if (!beyond) continue;
                    int ca = vertical ? o.Top : o.Left, cz = vertical ? o.Bottom : o.Right;
                    free = Subtract(free, ca, cz);
                }
                int fixedCoord = edge switch
                {
                    Edge.Left => b.Left,
                    Edge.Right => b.Right,
                    Edge.Top => b.Top,
                    _ => b.Bottom,
                };
                foreach (var (a, z) in free)
                    if (z > a) result.Add(new EdgeSegment(edge, fixedCoord, a, z, b));
            }
            return result;
        }

        private static List<(int a, int z)> Subtract(List<(int a, int z)> spans, int ca, int cz)
        {
            var outList = new List<(int a, int z)>();
            foreach (var (a, z) in spans)
            {
                if (cz <= a || ca >= z) { outList.Add((a, z)); continue; }
                if (ca > a) outList.Add((a, ca));
                if (cz < z) outList.Add((cz, z));
            }
            return outList;
        }

        /// <summary>Closest point inside any monitor (used to clamp the cursor and to resolve gaps).</summary>
        public static (int x, int y, MonitorInfo mon) ClampToNearest(IReadOnlyList<MonitorInfo> mons, double x, double y)
        {
            MonitorInfo best = mons[0];
            double bestD = double.MaxValue;
            int bx = 0, by = 0;
            foreach (var m in mons)
            {
                var b = m.Bounds;
                int cx = (int)Math.Clamp(Math.Floor(x), b.Left, b.Right - 1);
                int cy = (int)Math.Clamp(Math.Floor(y), b.Top, b.Bottom - 1);
                double d = (cx - x) * (cx - x) + (cy - y) * (cy - y);
                if (d < bestD) { bestD = d; best = m; bx = cx; by = cy; }
            }
            return (bx, by, best);
        }

        public static MonitorInfo? At(IReadOnlyList<MonitorInfo> mons, double x, double y)
        {
            foreach (var m in mons) if (m.Bounds.Contains(x, y)) return m;
            return null;
        }
    }
}
