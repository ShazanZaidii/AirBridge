using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading;
using Makaretu.Dns;

namespace AirBridge.Net
{
    public sealed record DiscoveredHost(string Id, string Name, string Plat, int Port, IReadOnlyList<IPAddress> Addresses, DateTime LastSeen);

    /// <summary>
    /// mDNS advertise + browse for _airbridge._tcp (§2.3) using one Makaretu MulticastService.
    /// The service is recreated on network changes so advertised A records track the current addresses.
    /// </summary>
    public sealed class Discovery : IDisposable
    {
        private static readonly string[] ServiceLabels = { "_airbridge", "_tcp", "local" };
        private static readonly TimeSpan Expiry = TimeSpan.FromSeconds(150);

        private sealed class Instance
        {
            public string? Key;             // this instance's own key
            public string? Target;          // key of the SRV target
            public DomainName? TargetName;
            public int Port;
            public Dictionary<string, string> Txt = new();
            public DateTime LastSeen;
        }

        private readonly object _gate = new();
        private readonly object _restartGate = new();
        private readonly Identity _identity;
        private readonly Dictionary<string, Instance> _instances = new();             // instance FQN (lower) -> data
        private readonly Dictionary<string, HashSet<IPAddress>> _hostAddrs = new();    // host name (lower) -> addrs
        private readonly Dictionary<string, DateTime> _lastAddrQuery = new();
        private MulticastService? _mdns;
        private ServiceDiscovery? _sd;
        private Timer? _queryTimer;
        private int _port;
        private int _queryRound;
        // Hosts found by a network scan (no multicast needed), and where each mDNS answer came from.
        private readonly Dictionary<string, DiscoveredHost> _swept = new();
        private readonly Dictionary<string, IPAddress> _sourceOf = new();                 // instance key -> sender of its answer

        /// <summary>How many mDNS answers have been seen (for the network check).</summary>
        public int AnswersSeen { get; private set; }

        /// <summary>Raised (any thread) when the set of discovered hosts changes.</summary>
        public event Action? Changed;

        public Discovery(Identity identity) { _identity = identity; }

        public void Start(int advertisePort)
        {
            _port = advertisePort;
            Restart();
            // Ask often at first (the first answers decide whether a phone shows up in the list at all), then settle down.
            _queryTimer = new Timer(_ => QueryRound(), null, TimeSpan.FromSeconds(1), Timeout.InfiniteTimeSpan);
        }

        /// <summary>Starts advertising on a (new) port.</summary>
        public void SetPort(int port)
        {
            _port = port;
            Restart();
        }

        /// <summary>(Re)creates the multicast service, re-advertises and re-queries.</summary>
        public void Restart()
        {
            lock (_restartGate)
            {
                MulticastService? oldMdns;
                ServiceDiscovery? oldSd;
                lock (_gate) { oldMdns = _mdns; oldSd = _sd; _mdns = null; _sd = null; }
                Shutdown(oldSd, oldMdns, goodbye: false); // outside _gate: Makaretu callbacks take _gate

                MulticastService? mdns = null;
                ServiceDiscovery? sd = null;
                try
                {
                    mdns = new MulticastService(FilterInterfaces) { UseIpv6 = false };
                    sd = new ServiceDiscovery(mdns);
                    mdns.AnswerReceived += OnAnswer;
                    ServiceProfile? profile = _port > 0 ? BuildProfile() : null;
                    if (profile != null) sd.Advertise(profile);
                    mdns.Start();
                    if (profile != null) sd.Announce(profile); // unsolicited announcement so browsers update now
                    lock (_gate) { _mdns = mdns; _sd = sd; }
                    Log.Info($"mDNS started (advertising port {_port})");
                }
                catch (Exception ex)
                {
                    Log.Error("mDNS start failed", ex);
                    Shutdown(sd, mdns, goodbye: false);
                }
            }
            Query();
        }

        private static readonly int[] QueryDelaysSec = { 2, 3, 5, 8, 12, 20, 30 };

        private void QueryRound()
        {
            try { Query(); Prune(); } catch { }
            int next = _queryRound < QueryDelaysSec.Length ? QueryDelaysSec[_queryRound] : 45;
            _queryRound++;
            try { _queryTimer?.Change(TimeSpan.FromSeconds(next), Timeout.InfiniteTimeSpan); } catch (ObjectDisposedException) { }
        }

        /// <summary>The network changed or the connect window opened: ask quickly again for a while.</summary>
        public void Boost()
        {
            _queryRound = 0;
            try { _queryTimer?.Change(TimeSpan.FromMilliseconds(300), Timeout.InfiniteTimeSpan); } catch (ObjectDisposedException) { }
        }

        /// <summary>Adds devices a network scan found; they are listed until they have not been seen for a while.</summary>
        public void AddSwept(IEnumerable<DiscoveredHost> hosts)
        {
            bool changed = false;
            lock (_gate)
                foreach (var h in hosts)
                {
                    if (!_swept.TryGetValue(h.Id, out var old) || old.Port != h.Port || !old.Addresses.SequenceEqual(h.Addresses)) changed = true;
                    _swept[h.Id] = h;
                }
            if (changed) Changed?.Invoke();
        }

        public void Query()
        {
            try
            {
                ServiceDiscovery? sd;
                lock (_gate) sd = _sd;
                sd?.QueryServiceInstances(Protocol.ServiceType);
            }
            catch (Exception ex) { Log.Warn($"mDNS query failed: {ex.Message}"); }
        }

        public IReadOnlyList<DiscoveredHost> Hosts()
        {
            lock (_gate)
            {
                var result = new Dictionary<string, DiscoveredHost>();
                foreach (var inst in _instances.Values)
                {
                    if (!inst.Txt.TryGetValue("id", out var id) || inst.Port == 0) continue;
                    id = id.ToLowerInvariant();
                    if (id == _identity.Id) continue;
                    if (inst.Txt.TryGetValue("v", out var v) && v != "2") continue;
                    var addrs = inst.Target != null && _hostAddrs.TryGetValue(inst.Target, out var set)
                        ? RankAddresses(set) : new List<IPAddress>();
                    // No address record came (some responders skip it): the sender of the answer is where it is.
                    if (addrs.Count == 0 && inst.Key != null && _sourceOf.TryGetValue(inst.Key, out var src)) addrs.Add(src);
                    var host = new DiscoveredHost(id, inst.Txt.GetValueOrDefault("name") ?? id,
                        inst.Txt.GetValueOrDefault("plat") ?? "", inst.Port, addrs, inst.LastSeen);
                    if (!result.TryGetValue(id, out var existing) || existing.LastSeen < host.LastSeen) result[id] = host;
                }
                foreach (var sw in _swept.Values)
                {
                    if (sw.Id == _identity.Id) continue;
                    if (!result.TryGetValue(sw.Id, out var existing) || existing.Addresses.Count == 0) result[sw.Id] = sw;
                }
                return result.Values.OrderBy(h => h.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
            }
        }

        public DiscoveredHost? Find(string id) => Hosts().FirstOrDefault(h => h.Id == id);

        // ------------------------------------------------------------------ advertise

        private ServiceProfile BuildProfile()
        {
            // Build the profile by hand: the stock constructor derives the SRV target from the instance name,
            // which breaks for names with spaces/dots. Instance label = device name, target = sanitised host.
            string hostLabel = new string(Environment.MachineName.Select(c => char.IsLetterOrDigit(c) ? c : '-').ToArray()).Trim('-');
            if (hostLabel.Length == 0) hostLabel = "airbridge";
            hostLabel += "-" + _identity.Id[..8];

            var serviceName = new DomainName(Protocol.ServiceType);
            var instanceName = new DomainName(new[] { _identity.Name });
            var hostName = new DomainName(new[] { hostLabel, "local" });
            var profile = new ServiceProfile
            {
                InstanceName = instanceName,
                ServiceName = serviceName,
                HostName = hostName,
            };
            var fqn = profile.FullyQualifiedName;
            profile.Resources.Add(new SRVRecord { Name = fqn, Port = (ushort)_port, Target = hostName });
            var txt = new TXTRecord { Name = fqn };
            txt.Strings.Add("v=2");
            txt.Strings.Add("id=" + _identity.Id);
            txt.Strings.Add("name=" + _identity.Name);
            txt.Strings.Add("plat=" + Protocol.Platform);
            profile.Resources.Add(txt);
            // Only addresses another device can actually reach: never the Hyper-V / WSL / VM / VPN adapters' (a phone that
            // picks the first record would otherwise try 172.x.x.x of a virtual switch and fail to connect).
            var reachable = NetUtil.LocalIPv4Addresses(includeVirtual: false);
            foreach (var addr in reachable.Count > 0 ? reachable : NetUtil.LocalIPv4Addresses())
                profile.Resources.Add(AddressRecord.Create(hostName, addr));
            return profile;
        }

        private static IEnumerable<NetworkInterface> FilterInterfaces(IEnumerable<NetworkInterface> nics) =>
            nics.Where(n => n.NetworkInterfaceType != NetworkInterfaceType.Loopback && !NetUtil.IsVirtualAdapter(n));

        // ------------------------------------------------------------------ browse

        private void OnAnswer(object? sender, MessageEventArgs e)
        {
            try
            {
                var msg = e.Message;
                AnswersSeen++;
                var from = (e.RemoteEndPoint as IPEndPoint)?.Address;
                var records = msg.Answers.Concat(msg.AdditionalRecords).Concat(msg.AuthorityRecords).ToList();
                bool changed = false;
                var needSrv = new List<DomainName>();
                var needAddr = new List<DomainName>();

                lock (_gate)
                {
                    var now = DateTime.UtcNow;
                    foreach (var r in records)
                    {
                        switch (r)
                        {
                            case PTRRecord ptr when IsService(ptr.Name.Labels):
                            {
                                string key = Key(ptr.DomainName);
                                if (ptr.TTL == TimeSpan.Zero) { changed |= _instances.Remove(key); break; }
                                if (!_instances.TryGetValue(key, out var inst))
                                {
                                    _instances[key] = inst = new Instance { Key = key };
                                    needSrv.Add(ptr.DomainName);
                                }
                                inst.LastSeen = now;
                                break;
                            }
                            case SRVRecord srv when IsInstance(srv.Name.Labels):
                            {
                                var inst = GetInstance(Key(srv.Name), now);
                                if (from != null && from.AddressFamily == AddressFamily.InterNetwork && inst.Key != null) _sourceOf[inst.Key] = from;
                                string target = Key(srv.Target);
                                changed |= inst.Target != target || inst.Port != srv.Port;
                                inst.Target = target;
                                inst.TargetName = srv.Target;
                                inst.Port = srv.Port;
                                break;
                            }
                            case TXTRecord txt when IsInstance(txt.Name.Labels):
                            {
                                var inst = GetInstance(Key(txt.Name), now);
                                var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                                foreach (var s in txt.Strings)
                                {
                                    int eq = s.IndexOf('=');
                                    if (eq > 0) dict[s[..eq]] = s[(eq + 1)..];
                                }
                                changed |= !dict.OrderBy(k => k.Key).SequenceEqual(inst.Txt.OrderBy(k => k.Key));
                                inst.Txt = dict;
                                break;
                            }
                            case AddressRecord a:
                            {
                                string host = Key(a.Name);
                                if (!_hostAddrs.TryGetValue(host, out var set)) _hostAddrs[host] = set = new HashSet<IPAddress>();
                                if (a.TTL == TimeSpan.Zero) changed |= set.Remove(a.Address);
                                else changed |= set.Add(a.Address);
                                break;
                            }
                        }
                    }

                    foreach (var inst in _instances.Values)
                    {
                        if (inst.Target != null && inst.TargetName != null && (!_hostAddrs.TryGetValue(inst.Target, out var s) || s.Count == 0)
                            && (!_lastAddrQuery.TryGetValue(inst.Target, out var last) || now - last > TimeSpan.FromSeconds(5)))
                        {
                            _lastAddrQuery[inst.Target] = now;
                            needAddr.Add(inst.TargetName);
                        }
                    }
                }

                MulticastService? mdns;
                lock (_gate) mdns = _mdns;
                if (mdns != null)
                {
                    foreach (var n in needSrv) mdns.SendQuery(n, DnsClass.IN, DnsType.ANY);
                    foreach (var n in needAddr.Distinct()) mdns.SendQuery(n, DnsClass.IN, DnsType.A);
                }
                if (changed) Changed?.Invoke();
            }
            catch (Exception ex) { Log.Warn($"mDNS answer parse failed: {ex.Message}"); }
        }

        private Instance GetInstance(string key, DateTime now)
        {
            if (!_instances.TryGetValue(key, out var inst)) _instances[key] = inst = new Instance { Key = key };
            inst.LastSeen = now;
            return inst;
        }

        private void Prune()
        {
            bool changed = false;
            lock (_gate)
            {
                var cutoff = DateTime.UtcNow - Expiry;
                foreach (var key in _instances.Where(kv => kv.Value.LastSeen < cutoff).Select(kv => kv.Key).ToList())
                { changed |= _instances.Remove(key); _sourceOf.Remove(key); }
                foreach (var id in _swept.Where(kv => kv.Value.LastSeen < DateTime.UtcNow - TimeSpan.FromMinutes(10)).Select(kv => kv.Key).ToList())
                    changed |= _swept.Remove(id);
            }
            if (changed) Changed?.Invoke();
        }

        private static bool IsService(IReadOnlyList<string> labels) =>
            labels.Count == 3 && Enumerable.Range(0, 3).All(i => string.Equals(labels[i], ServiceLabels[i], StringComparison.OrdinalIgnoreCase));

        private static bool IsInstance(IReadOnlyList<string> labels) =>
            labels.Count >= 4 && IsService(labels.Skip(labels.Count - 3).ToList());

        // Raw labels joined with a separator that cannot occur in DNS labels' wire meaning (keys only).
        private static string Key(DomainName n) => string.Join("\u0001", n.Labels).ToLowerInvariant();

        private static List<IPAddress> RankAddresses(IEnumerable<IPAddress> addrs) =>
            addrs.Where(a => a.AddressFamily == AddressFamily.InterNetwork || (a.AddressFamily == AddressFamily.InterNetworkV6 && !a.IsIPv6LinkLocal))
                 .OrderBy(a => a.AddressFamily == AddressFamily.InterNetwork ? (NetUtil.IsOnLocalSubnet(a) ? 0 : 1) : 2)
                 .ToList();

        private void Shutdown(ServiceDiscovery? sd, MulticastService? mdns, bool goodbye)
        {
            if (goodbye) try { sd?.Unadvertise(); } catch { }
            try { sd?.Dispose(); } catch { }
            try { if (mdns != null) { mdns.AnswerReceived -= OnAnswer; mdns.Stop(); mdns.Dispose(); } } catch { }
        }

        public void Dispose()
        {
            _queryTimer?.Dispose();
            lock (_restartGate)
            {
                MulticastService? m;
                ServiceDiscovery? sd;
                lock (_gate) { m = _mdns; sd = _sd; _mdns = null; _sd = null; }
                Shutdown(sd, m, goodbye: true);
            }
        }
    }

    /// <summary>Local interface/address helpers.</summary>
    public static class NetUtil
    {
        private static readonly string[] VirtualMarkers = { "hyper-v", "vethernet", "wsl", "virtualbox", "vmware", "vmnet", "docker", "loopback", "tap-windows", "wireguard", "tailscale", "zerotier", "vpn", "nordlynx", "anyconnect", "bluetooth" };

        /// <summary>Hyper-V/WSL/VM/VPN adapters (the hotspot "Wi-Fi Direct Virtual Adapter" is intentionally NOT excluded).</summary>
        public static bool IsVirtualAdapter(NetworkInterface n)
        {
            string s = (n.Name + " " + n.Description).ToLowerInvariant();
            return VirtualMarkers.Any(s.Contains);
        }

        private static IEnumerable<(NetworkInterface Nic, UnicastIPAddressInformation Addr, bool Gateway)> Candidates()
        {
            NetworkInterface[] nics;
            try { nics = NetworkInterface.GetAllNetworkInterfaces(); } catch { yield break; }
            foreach (var n in nics)
            {
                if (n.OperationalStatus != OperationalStatus.Up || n.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                    continue;
                IPInterfaceProperties props;
                try { props = n.GetIPProperties(); } catch { continue; }
                bool gw = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                foreach (var ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    var b = ua.Address.GetAddressBytes();
                    if (b[0] == 169 && b[1] == 254) continue; // APIPA
                    yield return (n, ua, gw);
                }
            }
        }

        /// <summary>Usable local IPv4 addresses, best first: default-gateway adapter, then physical, then others.</summary>
        public static List<IPAddress> LocalIPv4Addresses(bool includeVirtual = true) =>
            Candidates()
                .Where(c => includeVirtual || !IsVirtualAdapter(c.Nic))
                .OrderBy(c => IsVirtualAdapter(c.Nic) ? 2 : 0)
                .ThenBy(c => c.Gateway ? 0 : 1)
                .ThenBy(c => c.Nic.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211 ? 0 : 1)
                .Select(c => c.Addr.Address)
                .Distinct()
                .ToList();

        public static IPAddress? BestAddress() => LocalIPv4Addresses().FirstOrDefault();

        public static bool IsOnLocalSubnet(IPAddress remote)
        {
            if (remote.AddressFamily != AddressFamily.InterNetwork) return false;
            var r = remote.GetAddressBytes();
            foreach (var c in Candidates())
            {
                var mask = c.Addr.IPv4Mask?.GetAddressBytes();
                var local = c.Addr.Address.GetAddressBytes();
                if (mask == null || mask.Length != 4) continue;
                bool same = true;
                for (int i = 0; i < 4; i++) same &= (r[i] & mask[i]) == (local[i] & mask[i]);
                if (same) return true;
            }
            return false;
        }
    }
}
