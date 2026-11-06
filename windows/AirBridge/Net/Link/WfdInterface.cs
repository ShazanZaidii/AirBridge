using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Win32;

namespace AirBridge.Net.Link
{
    /// <summary>The Wi-Fi Direct virtual adapter hosting the group, as found after <c>Started</c>.</summary>
    public sealed record WfdAdapterInfo(string Id, string Name, string Description, IPAddress IPv4, int PrefixLength,
                                        IReadOnlyList<IPAddress> V6LinkLocal, bool MatchedByDescription)
    {
        /// <summary>Candidates for link_offer.addr: IPv4 first, then IPv6 link-local (without the zone: it is ours, not the phone's).</summary>
        public IReadOnlyList<string> OfferAddresses()
        {
            var list = new List<string> { IPv4.ToString() };
            foreach (var a in V6LinkLocal)
            {
                var copy = new IPAddress(a.GetAddressBytes()); // ScopeId 0 → no "%12"
                list.Add(copy.ToString());
            }
            return list;
        }
    }

    /// <summary>The physical Wi-Fi adapter (for logs and the adapter matrix): description + driver from the registry.</summary>
    public sealed record WifiAdapterInfo(string Description, string? DriverVersion, string? Provider, string? DriverDate);

    /// <summary>
    /// Finds the WFD virtual adapter and its addresses (the design notes §4 "Addressing"). The IP appears late
    /// and there is no change notification, so <see cref="WaitForIpv4Async"/> polls (10 × 500 ms, as Google does). Never hard-codes
    /// 192.168.137.1.
    /// </summary>
    public static class WfdInterface
    {
        private static readonly string[] DescriptionMarkers = { "Wi-Fi Direct Virtual Adapter", "WiFi Direct Virtual Adapter", "Wi-Fi Direct" };
        private const string NetClassKey = @"SYSTEM\CurrentControlSet\Control\Class\{4d36e972-e325-11ce-bfc1-08002be10318}";

        private static NetworkInterface[] All()
        {
            try { return NetworkInterface.GetAllNetworkInterfaces(); }
            catch (NetworkInformationException) { return Array.Empty<NetworkInterface>(); }
        }

        public static bool IsWfdDescription(string? d) =>
            d != null && DescriptionMarkers.Any(m => d.Contains(m, StringComparison.OrdinalIgnoreCase));

        /// <summary>Ids of the interfaces that are Up right now (snapshot before Start(), for the fallback match).</summary>
        public static HashSet<string> SnapshotUpIds() =>
            All().Where(n => n.OperationalStatus == OperationalStatus.Up).Select(n => n.Id).ToHashSet(StringComparer.OrdinalIgnoreCase);

        /// <summary>
        /// The WFD adapter with an IPv4 address, or null. Preference: description "Microsoft Wi-Fi Direct Virtual Adapter…",
        /// Up, with IPv4. Fallback (localised/odd driver names): a Wireless80211 interface that came Up after
        /// <paramref name="upBefore"/> was taken and has IPv4.
        /// </summary>
        public static WfdAdapterInfo? Find(HashSet<string>? upBefore)
        {
            var all = All();
            foreach (var n in all.Where(n => IsWfdDescription(n.Description) && n.OperationalStatus == OperationalStatus.Up))
                if (Describe(n, byDescription: true) is { } info) return info;
            if (upBefore != null)
                foreach (var n in all.Where(n => n.OperationalStatus == OperationalStatus.Up && !upBefore.Contains(n.Id)
                                                 && n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211))
                    if (Describe(n, byDescription: false) is { } info) return info;
            return null;
        }

        private static WfdAdapterInfo? Describe(NetworkInterface n, bool byDescription)
        {
            try
            {
                var props = n.GetIPProperties();
                var v4 = props.UnicastAddresses.FirstOrDefault(u => u.Address.AddressFamily == AddressFamily.InterNetwork
                                                                    && !IPAddress.IsLoopback(u.Address)
                                                                    && !u.Address.ToString().StartsWith("169.254.", StringComparison.Ordinal));
                if (v4 == null) return null;
                var v6 = props.UnicastAddresses.Where(u => u.Address.AddressFamily == AddressFamily.InterNetworkV6 && u.Address.IsIPv6LinkLocal)
                                               .Select(u => u.Address).ToList();
                int prefix = 24;
                try { prefix = v4.PrefixLength; } catch { }
                return new WfdAdapterInfo(n.Id, n.Name, n.Description, v4.Address, prefix, v6, byDescription);
            }
            catch (NetworkInformationException) { return null; }
        }

        /// <summary>Polls for the WFD adapter's IPv4 (default 10 × 500 ms). Null → link_fail no_ip.</summary>
        public static async Task<WfdAdapterInfo?> WaitForIpv4Async(HashSet<string>? upBefore, CancellationToken ct, int tries = 10, int delayMs = 500)
        {
            for (int i = 0; i < tries; i++)
            {
                var info = Find(upBefore);
                if (info != null) return info;
                await Task.Delay(delayMs, ct).ConfigureAwait(false);
            }
            return Find(upBefore);
        }

        /// <summary>True if <paramref name="local"/> (the socket's local address) belongs to the WFD adapter.</summary>
        public static bool IsLocalAddressOn(WfdAdapterInfo info, IPAddress local)
        {
            if (local.IsIPv4MappedToIPv6) local = local.MapToIPv4();
            if (local.Equals(info.IPv4)) return true;
            var n = All().FirstOrDefault(x => string.Equals(x.Id, info.Id, StringComparison.OrdinalIgnoreCase));
            if (n == null) return false;
            try
            {
                foreach (var u in n.GetIPProperties().UnicastAddresses)
                {
                    var a = u.Address;
                    if (a.AddressFamily == local.AddressFamily && a.GetAddressBytes().AsSpan().SequenceEqual(local.GetAddressBytes())) return true;
                }
            }
            catch (NetworkInformationException) { }
            return false;
        }

        /// <summary>True if <paramref name="remote"/> is inside the WFD adapter's IPv4 subnet.</summary>
        public static bool IsInSubnet(WfdAdapterInfo info, IPAddress remote)
        {
            if (remote.IsIPv4MappedToIPv6) remote = remote.MapToIPv4();
            if (remote.AddressFamily != AddressFamily.InterNetwork) return false;
            uint mask = info.PrefixLength <= 0 ? 0 : info.PrefixLength >= 32 ? uint.MaxValue : uint.MaxValue << (32 - info.PrefixLength);
            uint a = BeU32(info.IPv4.GetAddressBytes()), b = BeU32(remote.GetAddressBytes());
            return (a & mask) == (b & mask);
        }

        private static uint BeU32(byte[] b) => (uint)(b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3]);

        /// <summary>Operational status of an adapter by id (null = it disappeared).</summary>
        public static OperationalStatus? StatusOf(string id) =>
            All().FirstOrDefault(n => string.Equals(n.Id, id, StringComparison.OrdinalIgnoreCase))?.OperationalStatus;

        // ------------------------------------------------------------------ diagnostics

        /// <summary>The physical Wi-Fi adapter (Up preferred) with driver data from the network class key; null if none.</summary>
        public static WifiAdapterInfo? PhysicalWifi()
        {
            var nic = All().Where(n => n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 && !IsWfdDescription(n.Description)
                                       && !n.Description.Contains("Virtual", StringComparison.OrdinalIgnoreCase))
                           .OrderByDescending(n => n.OperationalStatus == OperationalStatus.Up).FirstOrDefault();
            if (nic == null) return null;
            var (ver, prov, date) = DriverOf(nic.Id);
            return new WifiAdapterInfo(nic.Description, ver, prov, date);
        }

        /// <summary>Driver data (Get-NetAdapter's DriverVersion/DriverProvider/DriverDate) via HKLM\…\Class\{4d36e972…}\NNNN.</summary>
        public static (string? Version, string? Provider, string? Date) DriverOf(string interfaceId)
        {
            if (!OperatingSystem.IsWindows()) return (null, null, null);
            try
            {
                using var cls = Registry.LocalMachine.OpenSubKey(NetClassKey);
                if (cls == null) return (null, null, null);
                foreach (var sub in cls.GetSubKeyNames())
                {
                    if (sub.Length != 4 || !sub.All(char.IsDigit)) continue;
                    try
                    {
                        using var k = cls.OpenSubKey(sub);
                        if (k?.GetValue("NetCfgInstanceId") is string id && string.Equals(id, interfaceId, StringComparison.OrdinalIgnoreCase))
                            return (k.GetValue("DriverVersion") as string, k.GetValue("ProviderName") as string, k.GetValue("DriverDate") as string);
                    }
                    catch (Exception) { /* some subkeys are ACL'd */ }
                }
            }
            catch (Exception) { }
            return (null, null, null);
        }

        /// <summary>Raw <c>netsh wlan show interfaces</c> output (3 s timeout), or null. Diagnostic only (channel/band of the STA;
        /// the GO shares it, see S-W1).</summary>
        public static async Task<string?> NetshInterfacesAsync()
        {
            try
            {
                var psi = new ProcessStartInfo("netsh", "wlan show interfaces")
                {
                    RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true,
                };
                using var p = Process.Start(psi);
                if (p == null) return null;
                var read = p.StandardOutput.ReadToEndAsync();
                using var cts = new CancellationTokenSource(3000);
                try { await p.WaitForExitAsync(cts.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) { try { p.Kill(); } catch { } return null; }
                return await read.ConfigureAwait(false);
            }
            catch (Exception) { return null; }
        }

        /// <summary>The STA's channel/frequency/band from netsh (first connected interface), or (0, "unknown").</summary>
        public static async Task<(int Channel, int Mhz, string Band)> StaChannelAsync()
        {
            string? text = await NetshInterfacesAsync().ConfigureAwait(false);
            if (text == null) return (0, 0, "unknown");
            foreach (var block in LinkWire.ParseNetshInterfaces(text))
            {
                if (!block.TryGetValue("channel", out var ch) || !int.TryParse(ch, out int channel)) continue;
                block.TryGetValue("band", out var bandText);
                int mhz = LinkWire.ChannelToMhz(channel, bandText);
                return (channel, mhz, mhz > 0 ? LinkWire.BandOf(mhz) : "unknown");
            }
            return (0, 0, "unknown");
        }
    }

    /// <summary>
    /// 2 s watchdog on the WFD adapter's OperationalStatus: turning Wi-Fi off leaves the publisher "Started" with no event
    /// (§1.2), so this is the only reliable local signal besides the session socket. Raises <see cref="Lost"/> once.
    /// </summary>
    public sealed class WfdWatchdog : IDisposable
    {
        private readonly string _id;
        private readonly Timer _timer;
        private int _misses;
        private int _fired;

        public event Action<string>? Lost;

        public WfdWatchdog(string adapterId, TimeSpan? period = null)
        {
            _id = adapterId;
            var p = period ?? TimeSpan.FromSeconds(2);
            _timer = new Timer(_ => Tick(), null, p, p);
        }

        private void Tick()
        {
            if (Volatile.Read(ref _fired) != 0) return;
            var st = WfdInterface.StatusOf(_id);
            if (st == OperationalStatus.Up) { _misses = 0; return; }
            // Two consecutive misses (≈ 4 s) so a single odd enumeration doesn't kill a link.
            if (++_misses < 2) return;
            if (Interlocked.Exchange(ref _fired, 1) == 1) return;
            try { Lost?.Invoke(st == null ? "adapter_gone" : $"adapter_{st}"); } catch { }
        }

        public void Dispose() => _timer.Dispose();
    }
}
