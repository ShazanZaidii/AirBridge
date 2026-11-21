using System;
using System.Collections.Generic;
using System.Linq;

namespace AirBridge.Net
{
    /// <summary>
    /// Old entries for a device that was reinstalled. A device gets a new id on every install, so the paired list would keep
    /// one dead entry per reinstall. Names can't tell them apart (a person can own two devices with the same name), but the
    /// hardware fingerprint can: it is the same for one physical device and different for any other. Entries from before
    /// fingerprints existed have none, so those are only ever offered for removal, never removed without a yes.
    /// </summary>
    public static class StaleDevices
    {
        public sealed record Entry(string Id, string Name, string Plat, string? Hw);

        /// <summary>Entries that are the same physical device as <paramref name="current"/> (just connected, fingerprint
        /// <paramref name="hw"/>) under an older install. Anything connected right now is left alone (two installs can run on
        /// one device at once).</summary>
        public static List<Entry> Superseded(string current, string? hw, IEnumerable<Entry> among, ISet<string> connected)
        {
            if (string.IsNullOrEmpty(hw)) return new List<Entry>();
            return among.Where(e => e.Id != current && e.Hw == hw && !connected.Contains(e.Id)).ToList();
        }

        /// <summary>Entries with no fingerprint that look like earlier installs of <paramref name="current"/> (same kind of
        /// device and same name). Only to ask the user about: two real devices can share a name. Not offered again once the
        /// user said keep.</summary>
        public static List<Entry> Candidates(Entry current, IEnumerable<Entry> among, ISet<string> connected, ISet<string> dismissed)
        {
            return among.Where(e => e.Id != current.Id && e.Hw == null && e.Plat == current.Plat && e.Name == current.Name
                                    && !connected.Contains(e.Id) && !dismissed.Contains(e.Id)).ToList();
        }
    }
}
