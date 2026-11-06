using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace AirBridge.Input
{
    /// <summary>Where one peer sits relative to this PC's desktop. Offset/Size are fractions of the desktop edge.</summary>
    public sealed class PeerPlacement
    {
        public string Name { get; set; } = "";
        /// <summary>"left" | "right" | "top" | "bottom" | null (not arranged).</summary>
        public string? Edge { get; set; }
        public double Offset { get; set; } = 0.25;
        public double Size { get; set; } = 0.5;

        public PeerPlacement Clone() => (PeerPlacement)MemberwiseClone();
    }

    public sealed class LayoutData
    {
        /// <summary>2: firm default push, no automatic full top/bottom placements (migrated once from 1).</summary>
        public int Version { get; set; } = CurrentVersion;
        public const int CurrentVersion = 2;
        public Dictionary<string, PeerPlacement> Peers { get; set; } = new();
        /// <summary>Target side: when a macOS peer controls this PC, Cmd acts as Ctrl and Ctrl as Win.</summary>
        public bool SwapCmdCtrlForMac { get; set; } = false;
        /// <summary>Target side: extra multiplier on incoming ptr_move (on top of the monitor DPI scale).</summary>
        public double TargetPointerGain { get; set; } = 1.0;
        /// <summary>Target side: pixels of px:true scroll that make one wheel notch (120 units).</summary>
        public double TargetScrollPixelsPerNotch { get; set; } = 50.0;

        /// <summary>Controller side (§8.2 Capture intent): "easy" | "normal" | "firm" push-through strength.</summary>
        public string CaptureStrength { get; set; } = "firm";
        /// <summary>Controller side: crossing additionally requires holding Alt while pushing.</summary>
        public bool CaptureRequireAlt { get; set; } = false;
        /// <summary>Controller side: edges ("left"/"right"/"top"/"bottom") that never capture.</summary>
        public List<string> CaptureDisabledEdges { get; set; } = new();

        /// <summary>Outward push (logical px at 100 % scaling) needed within 500 ms to cross.</summary>
        public static double ThresholdFor(string? strength) => strength switch
        {
            "easy" => 60,
            "firm" => 160,
            _ => 100,
        };

        public bool EdgeCaptureEnabled(Edge e) => !(CaptureDisabledEdges?.Contains(e.ToWire()) ?? false);
    }

    /// <summary>
    /// Per-peer edge + span persisted in %LOCALAPPDATA%\AirBridge\layout.json (keyed by peer id).
    /// Thread-safe; <see cref="Changed"/> fires on the thread that made the change.
    /// </summary>
    public static class Layout
    {
        private static readonly object Gate = new();
        private static LayoutData _data = new();
        private static bool _loaded;

        public static event Action? Changed;

        public static string FilePath => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AirBridge", "layout.json");

        private static readonly JsonSerializerOptions Json = new()
        {
            WriteIndented = true,
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        };

        private static void EnsureLoaded()
        {
            if (_loaded) return;
            _loaded = true;
            try
            {
                if (File.Exists(FilePath))
                    _data = JsonSerializer.Deserialize<LayoutData>(File.ReadAllText(FilePath), Json) ?? new LayoutData();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"layout.json unreadable, starting fresh: {ex.Message}");
                _data = new LayoutData();
            }
            _data.Peers ??= new();
            _data.CaptureDisabledEdges = (_data.CaptureDisabledEdges ?? new())
                .Where(e => EdgeExt.Parse(e) != null).Select(e => e.ToLowerInvariant()).Distinct().ToList();
            if (_data.CaptureStrength is not ("easy" or "normal" or "firm")) _data.CaptureStrength = "firm";
            foreach (var p in _data.Peers.Values) Sanitize(p);
            if (_data.Version < LayoutData.CurrentVersion)
            {
                // v1 → v2 (§8.1 "Edge assignment is explicit"): a peer on a full top/bottom edge sat on the taskbar, and
                // "normal" was only ever the old default; the new default push-through is firm (160).
                AutoPlacement.ClearFullTopBottom(_data.Peers);
                if (_data.CaptureStrength == "normal") _data.CaptureStrength = "firm";
                _data.Version = LayoutData.CurrentVersion;
                SaveLocked();
            }
        }

        private static void Sanitize(PeerPlacement p)
        {
            if (EdgeExt.Parse(p.Edge) is null) p.Edge = null;
            if (double.IsNaN(p.Size)) p.Size = 0.5;
            if (double.IsNaN(p.Offset)) p.Offset = 0.25;
            p.Size = Math.Clamp(p.Size, 0.1, 1.0);
            p.Offset = Math.Clamp(p.Offset, 0.0, 1.0 - p.Size);
        }

        private static void SaveLocked()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
                string tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(_data, Json));
                File.Move(tmp, FilePath, true);
            }
            catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"layout.json save failed: {ex.Message}"); }
        }

        /// <summary>Deep copy of all placements.</summary>
        public static Dictionary<string, PeerPlacement> Snapshot()
        {
            lock (Gate)
            {
                EnsureLoaded();
                return _data.Peers.ToDictionary(kv => kv.Key, kv => kv.Value.Clone());
            }
        }

        public static LayoutData Settings()
        {
            lock (Gate)
            {
                EnsureLoaded();
                return new LayoutData
                {
                    Version = _data.Version,
                    SwapCmdCtrlForMac = _data.SwapCmdCtrlForMac,
                    TargetPointerGain = _data.TargetPointerGain,
                    TargetScrollPixelsPerNotch = _data.TargetScrollPixelsPerNotch,
                    CaptureStrength = _data.CaptureStrength,
                    CaptureRequireAlt = _data.CaptureRequireAlt,
                    CaptureDisabledEdges = new List<string>(_data.CaptureDisabledEdges),
                };
            }
        }

        public static void SetSwapCmdCtrlForMac(bool on)
        {
            lock (Gate) { EnsureLoaded(); _data.SwapCmdCtrlForMac = on; SaveLocked(); }
            Changed?.Invoke();
        }

        public static void SetCaptureStrength(string strength)
        {
            if (strength is not ("easy" or "normal" or "firm")) return;
            lock (Gate) { EnsureLoaded(); _data.CaptureStrength = strength; SaveLocked(); }
            Changed?.Invoke();
        }

        public static void SetCaptureRequireAlt(bool on)
        {
            lock (Gate) { EnsureLoaded(); _data.CaptureRequireAlt = on; SaveLocked(); }
            Changed?.Invoke();
        }

        public static void SetEdgeCaptureEnabled(Edge edge, bool enabled)
        {
            lock (Gate)
            {
                EnsureLoaded();
                _data.CaptureDisabledEdges.Remove(edge.ToWire());
                if (!enabled) _data.CaptureDisabledEdges.Add(edge.ToWire());
                SaveLocked();
            }
            Changed?.Invoke();
        }

        /// <summary>Assign a peer to an edge (null = unassign). Any other peer on that edge is unassigned (§8.1).</summary>
        public static void Assign(string peerId, string name, Edge? edge, double offset, double size)
        {
            lock (Gate)
            {
                EnsureLoaded();
                if (!_data.Peers.TryGetValue(peerId, out var p)) _data.Peers[peerId] = p = new PeerPlacement();
                if (!string.IsNullOrEmpty(name)) p.Name = name;
                p.Edge = edge?.ToWire();
                p.Size = size;
                p.Offset = offset;
                Sanitize(p);
                if (edge != null)
                    foreach (var (id, other) in _data.Peers)
                        if (id != peerId && other.Edge == p.Edge) other.Edge = null;
                SaveLocked();
            }
            Changed?.Invoke();
        }

        /// <summary>
        /// Newly seen controllable peers get a free left/right edge with the middle span (<see cref="AutoPlacement"/>);
        /// never the top or bottom edge, never a full-length edge.
        /// </summary>
        public static void AutoAssign(IEnumerable<(string Id, string Name)> peers)
        {
            bool changed;
            lock (Gate)
            {
                EnsureLoaded();
                changed = AutoPlacement.AssignNew(_data.Peers, peers);
                if (changed) SaveLocked();
            }
            if (changed) Changed?.Invoke();
        }

        /// <summary>Remember the latest display name of a peer that already has a placement.</summary>
        public static void UpdateName(string peerId, string name)
        {
            bool changed = false;
            lock (Gate)
            {
                EnsureLoaded();
                if (_data.Peers.TryGetValue(peerId, out var p) && p.Name != name && !string.IsNullOrEmpty(name))
                {
                    p.Name = name; changed = true; SaveLocked();
                }
            }
            if (changed) Changed?.Invoke();
        }
    }

    /// <summary>Latest set of ready peers as reported by the network half.</summary>
    public static class PeerDirectory
    {
        private static volatile IReadOnlyList<PeerInfo> _current = Array.Empty<PeerInfo>();
        public static event Action? Changed;

        public static IReadOnlyList<PeerInfo> Current => _current;

        public static void Update(IReadOnlyList<PeerInfo> peers)
        {
            _current = peers ?? Array.Empty<PeerInfo>();
            Layout.AutoAssign(_current.Where(p => p.Caps.Contains("ctl_target")).Select(p => (p.Id, p.Name)));
            foreach (var p in _current) Layout.UpdateName(p.Id, p.Name);
            Changed?.Invoke();
        }

        public static PeerInfo? Find(string id)
        {
            foreach (var p in _current) if (p.Id == id) return p;
            return null;
        }
    }
}
