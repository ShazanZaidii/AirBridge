using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Windows.Devices.WiFiDirect;
using Windows.Security.Credentials;

namespace AirBridge.Net.Link
{
    /// <summary>Outcome of <see cref="WfdLegacyHost.StartAsync"/>.</summary>
    public sealed record WfdStartResult(bool Ok, string? FailReason, long Ms, int Attempts, string Detail);

    /// <summary>
    /// Windows hosts a Wi-Fi Direct <b>legacy</b> group owner (topology T1, PROTOCOL §10.1 <c>wfd_legacy</c>): the phone joins it
    /// as an ordinary Wi-Fi client with the SSID/passphrase derived from the pairing key.
    ///
    /// Robustness rules from the design notes §1.2 (all implemented here):
    /// - one NEW <see cref="WiFiDirectAdvertisementPublisher"/> (and listener) per attempt: a publisher can't be restarted;
    /// - <c>Started</c> must arrive within 5 s; one retry with a fresh publisher after 1 s (timeout, Aborted(Success), Start() throwing);
    ///   Aborted(RadioNotAvailable) → <c>radio_off</c>, Aborted(ResourceInUse) → <c>hotspot_active</c> (no retry);
    /// - a <see cref="WiFiDirectConnectionListener"/> whose ConnectionRequested calls <see cref="WiFiDirectDevice.FromIdAsync(string)"/>
    ///   per request on the WPF dispatcher (the MS sample: "FromIdAsync needs to be called from the UI thread"), times out at 5 s
    ///   and carries on (the socket may still work), and KEEPS every returned <see cref="WiFiDirectDevice"/> alive until teardown —
    ///   the documented fix for Windows dropping legacy clients every ~2 minutes;
    /// - publisher/listener/devices live in fields (no GC); <see cref="Dispose"/> stops and releases everything; idempotent.
    /// Publisher <c>StatusChanged</c> after Started is not trusted (Wi-Fi off leaves Status == Started with no event): the
    /// caller also runs <see cref="WfdWatchdog"/> on the adapter.
    /// </summary>
    public sealed class WfdLegacyHost : IDisposable
    {
        public static readonly TimeSpan StartTimeout = TimeSpan.FromSeconds(5);
        public static readonly TimeSpan FromIdTimeout = TimeSpan.FromSeconds(5);
        private static readonly TimeSpan RetryDelay = TimeSpan.FromSeconds(1);

        private sealed class Client
        {
            public required string Id;
            public required string Name;
            public WiFiDirectConnectionRequest? Request;
            public WiFiDirectDevice? Device;
            public long RequestedAt;
        }

        private readonly WfdCreds _creds;
        private readonly string _logLid;
        private readonly object _gate = new();
        private WiFiDirectAdvertisementPublisher? _publisher;
        private WiFiDirectConnectionListener? _listener;
        private readonly Dictionary<string, Client> _clients = new();
        private readonly List<Client> _retired = new(); // replaced/disconnected entries, released at teardown
        private TaskCompletionSource<(WiFiDirectAdvertisementPublisherStatus Status, WiFiDirectError Error)>? _startTcs;
        private volatile bool _started;
        private volatile bool _stopping;
        private int _disposed;

        /// <summary>Try <c>ListenStateDiscoverability = None</c> (no P2P listen beacons; S-W1 compares both).</summary>
        public bool DiscoverabilityNone { get; init; } = true;
        /// <summary>Call FromIdAsync per connection request and keep the device (the 2-minute drop fix). Off only for S-W3.</summary>
        public bool KeepDevices { get; init; } = true;

        /// <summary>The publisher aborted/stopped after it had started (reason: radio_off / hotspot_active / stopped). Any thread.</summary>
        public event Action<string>? Lost;
        /// <summary>A legacy client connected or disconnected (diagnostics). Any thread.</summary>
        public event Action? ClientsChanged;

        public WfdLegacyHost(WfdCreds creds, string logLid)
        {
            _creds = creds;
            _logLid = logLid;
        }

        public bool IsStarted => _started && !_stopping;

        public string StatusText
        {
            get
            {
                try { return _publisher?.Status.ToString() ?? "None"; } catch { return "?"; }
            }
        }

        /// <summary>
        /// Probe for the caps policy: can the WinRT publisher class be activated at all on this OS? Does not touch the radio
        /// (nothing is started). Returns false on any exception (class not registered, API missing, …).
        /// </summary>
        public static bool CanConstructPublisher()
        {
            try
            {
                var p = new WiFiDirectAdvertisementPublisher();
                _ = p.Advertisement.LegacySettings; // touch the projection
                return true;
            }
            catch (Exception ex)
            {
                LinkLog.Write($"AirBridge.Link probe publisher construct failed: {ex.GetType().Name} {ex.Message}");
                return false;
            }
        }

        // ------------------------------------------------------------------ start

        /// <summary>Starts the group owner (≤ 5 s per attempt, one retry). Never throws.</summary>
        public async Task<WfdStartResult> StartAsync(CancellationToken ct)
        {
            long t0 = Environment.TickCount64;
            string detail = "";
            for (int attempt = 1; attempt <= 2; attempt++)
            {
                if (Volatile.Read(ref _disposed) != 0) return new WfdStartResult(false, LinkWire.Reason.User, Ms(t0), attempt, "disposed");
                var (outcome, retry, d) = await AttemptAsync(attempt, ct).ConfigureAwait(false);
                detail = d;
                if (outcome == null) return new WfdStartResult(true, null, Ms(t0), attempt, d);
                if (!retry || attempt == 2 || ct.IsCancellationRequested)
                    return new WfdStartResult(false, ct.IsCancellationRequested ? LinkWire.Reason.User : outcome, Ms(t0), attempt, d);
                try { await Task.Delay(RetryDelay, ct).ConfigureAwait(false); }
                catch (OperationCanceledException) { return new WfdStartResult(false, LinkWire.Reason.User, Ms(t0), attempt, detail); }
            }
            return new WfdStartResult(false, LinkWire.Reason.NoGoSupport, Ms(t0), 2, detail);
        }

        private static long Ms(long t0) => Environment.TickCount64 - t0;

        /// <summary>One attempt with brand-new WinRT objects. Returns (null, _, detail) when Started, else (reason, retry?, detail).</summary>
        private async Task<(string? Reason, bool Retry, string Detail)> AttemptAsync(int attempt, CancellationToken ct)
        {
            ReleaseWinRt(); // previous attempt's objects are never reused
            var tcs = new TaskCompletionSource<(WiFiDirectAdvertisementPublisherStatus, WiFiDirectError)>(TaskCreationOptions.RunContinuationsAsynchronously);
            WiFiDirectAdvertisementPublisher pub;
            try
            {
                pub = new WiFiDirectAdvertisementPublisher();
                var adv = pub.Advertisement;
                adv.IsAutonomousGroupOwnerEnabled = true;
                adv.LegacySettings.IsEnabled = true;
                adv.LegacySettings.Ssid = _creds.Ssid;
                adv.LegacySettings.Passphrase = new PasswordCredential { Password = _creds.Psk };
                if (DiscoverabilityNone)
                {
                    try { adv.ListenStateDiscoverability = WiFiDirectAdvertisementListenStateDiscoverability.None; }
                    catch (Exception ex) { LinkLog.Ev(_logLid, "discoverability_none_failed", ("err", ex.Message)); }
                }

                // Listener first, so a phone that joins the instant the GO is up is not missed.
                var listener = new WiFiDirectConnectionListener();
                listener.ConnectionRequested += OnConnectionRequested;
                pub.StatusChanged += OnStatusChanged;
                lock (_gate)
                {
                    _publisher = pub;
                    _listener = listener;
                    _startTcs = tcs;
                    _started = false;
                }
                pub.Start();
            }
            catch (Exception ex)
            {
                string d = $"start threw {ex.GetType().Name} 0x{ex.HResult:X8} {ex.Message}";
                LinkLog.Ev(_logLid, "go_start_error", ("attempt", attempt), ("err", d));
                ReleaseWinRt();
                return (LinkWire.Reason.NoGoSupport, true, d);
            }

            Task winner;
            try { winner = await Task.WhenAny(tcs.Task, Task.Delay(StartTimeout, ct)).ConfigureAwait(false); }
            catch (OperationCanceledException) { winner = Task.CompletedTask; }
            if (winner != tcs.Task)
            {
                string d = ct.IsCancellationRequested ? "cancelled" : $"no Started within {StartTimeout.TotalSeconds:0} s (status {StatusText})";
                LinkLog.Ev(_logLid, "go_start_timeout", ("attempt", attempt), ("status", StatusText));
                ReleaseWinRt();
                return (ct.IsCancellationRequested ? LinkWire.Reason.User : LinkWire.Reason.NoGoSupport, !ct.IsCancellationRequested, d);
            }

            WiFiDirectAdvertisementPublisherStatus status;
            WiFiDirectError error;
            try { (status, error) = await tcs.Task.ConfigureAwait(false); }
            catch (OperationCanceledException) { return (LinkWire.Reason.User, false, "cancelled"); } // Stop()/Dispose() mid-start
            if (status == WiFiDirectAdvertisementPublisherStatus.Started) return (null, false, $"attempt {attempt}");

            ReleaseWinRt();
            string detail = $"{status} ({error})";
            LinkLog.Ev(_logLid, "go_aborted", ("attempt", attempt), ("status", status.ToString()), ("err", error.ToString()));
            return error switch
            {
                WiFiDirectError.RadioNotAvailable => (LinkWire.Reason.RadioOff, false, detail),
                WiFiDirectError.ResourceInUse => (LinkWire.Reason.HotspotActive, false, detail),
                _ => (LinkWire.Reason.NoGoSupport, true, detail), // transient "Aborted (Success)": retry once
            };
        }

        private void OnStatusChanged(WiFiDirectAdvertisementPublisher sender, WiFiDirectAdvertisementPublisherStatusChangedEventArgs args)
        {
            WiFiDirectAdvertisementPublisherStatus status;
            WiFiDirectError error;
            try { status = args.Status; error = args.Error; }
            catch { return; }
            TaskCompletionSource<(WiFiDirectAdvertisementPublisherStatus, WiFiDirectError)>? tcs;
            bool current;
            lock (_gate)
            {
                current = ReferenceEquals(sender, _publisher);
                tcs = current ? _startTcs : null;
            }
            if (!current) return; // event from an abandoned attempt
            LinkLog.Ev(_logLid, "go_status", ("status", status.ToString()), ("err", error.ToString()));
            switch (status)
            {
                case WiFiDirectAdvertisementPublisherStatus.Started:
                    _started = true;
                    tcs?.TrySetResult((status, error));
                    break;
                case WiFiDirectAdvertisementPublisherStatus.Aborted:
                case WiFiDirectAdvertisementPublisherStatus.Stopped:
                    bool wasStarted = _started;
                    _started = false;
                    if (tcs != null && tcs.TrySetResult((status, error))) return; // still in StartAsync
                    if (wasStarted && !_stopping)
                    {
                        string reason = error switch
                        {
                            WiFiDirectError.RadioNotAvailable => LinkWire.Reason.RadioOff,
                            WiFiDirectError.ResourceInUse => LinkWire.Reason.HotspotActive,
                            _ => "stopped",
                        };
                        try { Lost?.Invoke(reason); } catch (Exception ex) { LinkLog.Write($"AirBridge.Link Lost handler threw: {ex.Message}"); }
                    }
                    break;
            }
        }

        // ------------------------------------------------------------------ joined clients

        private void OnConnectionRequested(WiFiDirectConnectionListener sender, WiFiDirectConnectionRequestedEventArgs args)
        {
            WiFiDirectConnectionRequest? request = null;
            string id, name;
            try
            {
                request = args.GetConnectionRequest();
                id = request.DeviceInformation.Id;
                name = request.DeviceInformation.Name ?? "";
            }
            catch (Exception ex)
            {
                LinkLog.Ev(_logLid, "conn_request_error", ("err", ex.Message));
                try { request?.Dispose(); } catch { }
                return;
            }
            if (_stopping || Volatile.Read(ref _disposed) != 0 || !ReferenceEquals(sender, _listener))
            {
                try { request.Dispose(); } catch { } // declines it
                return;
            }
            var client = new Client { Id = id, Name = name, Request = request, RequestedAt = Environment.TickCount64 };
            lock (_gate)
            {
                // A re-join of the same device: keep the old objects alive until teardown (never release mid-link).
                if (_clients.Remove(id, out var old)) _retired.Add(old);
                _clients[id] = client;
            }
            LinkLog.Ev(_logLid, "client_request", ("dev", name), ("keep", KeepDevices));
            RaiseClients();
            if (!KeepDevices) return; // S-W3 control run: request held, no FromIdAsync
            _ = Task.Run(() => AttachDeviceAsync(client));
        }

        private async Task AttachDeviceAsync(Client client)
        {
            long t0 = Environment.TickCount64;
            Task<WiFiDirectDevice> op;
            try
            {
                var dispatcher = Application.Current?.Dispatcher;
                if (dispatcher != null && !dispatcher.HasShutdownStarted)
                    op = dispatcher.InvokeAsync(() => WiFiDirectDevice.FromIdAsync(client.Id).AsTask()).Task.Unwrap();
                else
                    op = WiFiDirectDevice.FromIdAsync(client.Id).AsTask();
            }
            catch (Exception ex)
            {
                LinkLog.Ev(_logLid, "fromid_error", ("dev", client.Name), ("err", $"0x{ex.HResult:X8} {ex.Message}"));
                return;
            }

            var winner = await Task.WhenAny(op, Task.Delay(FromIdTimeout)).ConfigureAwait(false);
            if (winner != op)
            {
                // Known hang (0x800705B4): carry on, the socket may still work. Keep the device if it shows up later.
                LinkLog.Ev(_logLid, "fromid_timeout", ("dev", client.Name), ("ms", Environment.TickCount64 - t0));
                _ = op.ContinueWith(t => { if (t.Status == TaskStatus.RanToCompletion) Adopt(client, t.Result, t0, late: true); },
                                    TaskScheduler.Default);
                return;
            }
            try { Adopt(client, await op.ConfigureAwait(false), t0, late: false); }
            catch (Exception ex) { LinkLog.Ev(_logLid, "fromid_error", ("dev", client.Name), ("err", $"0x{ex.HResult:X8} {ex.Message}")); }
        }

        private void Adopt(Client client, WiFiDirectDevice? device, long t0, bool late)
        {
            if (device == null) return;
            bool keep;
            lock (_gate)
            {
                keep = Volatile.Read(ref _disposed) == 0 && !_stopping;
                if (keep) client.Device = device;
            }
            if (!keep) { try { device.Dispose(); } catch { } return; }
            string eps = "";
            try
            {
                eps = string.Join(",", device.GetConnectionEndpointPairs()
                    .Select(p => $"{p.LocalHostName?.CanonicalName}<->{p.RemoteHostName?.CanonicalName}"));
            }
            catch { }
            try { device.ConnectionStatusChanged += OnDeviceStatusChanged; } catch { }
            LinkLog.Ev(_logLid, late ? "client_device_late" : "client_device", ("dev", client.Name), ("ms", Environment.TickCount64 - t0), ("ep", eps));
            RaiseClients();
        }

        private void OnDeviceStatusChanged(WiFiDirectDevice sender, object args)
        {
            WiFiDirectConnectionStatus st;
            try { st = sender.ConnectionStatus; } catch { return; }
            LinkLog.Ev(_logLid, "client_status", ("status", st.ToString()));
            if (st != WiFiDirectConnectionStatus.Disconnected) return;
            lock (_gate)
            {
                // Like the MS classic sample: forget the entry, but release the WinRT objects only at teardown.
                var hit = _clients.FirstOrDefault(kv => ReferenceEquals(kv.Value.Device, sender));
                if (hit.Key != null && _clients.Remove(hit.Key, out var c)) _retired.Add(c);
            }
            RaiseClients();
        }

        private void RaiseClients()
        {
            try { ClientsChanged?.Invoke(); } catch { }
        }

        /// <summary>Joined legacy clients: name and endpoint pairs (from GetConnectionEndpointPairs) for logs/Debug window.</summary>
        public IReadOnlyList<string> ClientSummaries()
        {
            List<Client> list;
            lock (_gate) list = _clients.Values.ToList();
            var res = new List<string>();
            foreach (var c in list)
            {
                string ep = "";
                try
                {
                    if (c.Device != null)
                        ep = string.Join(",", c.Device.GetConnectionEndpointPairs().Select(p => p.RemoteHostName?.CanonicalName));
                }
                catch { }
                res.Add($"{(c.Name.Length > 0 ? c.Name : c.Id)}{(c.Device != null ? " [device]" : " [request]")} {ep}".TrimEnd());
            }
            return res;
        }

        // ------------------------------------------------------------------ teardown

        /// <summary>Stops the publisher and releases every WinRT object (publisher, listener, requests, devices). Idempotent.</summary>
        public void Stop()
        {
            _stopping = true;
            ReleaseWinRt();
            List<Client> clients;
            lock (_gate)
            {
                clients = _clients.Values.Concat(_retired).ToList();
                _clients.Clear();
                _retired.Clear();
            }
            foreach (var c in clients)
            {
                try { if (c.Device != null) c.Device.ConnectionStatusChanged -= OnDeviceStatusChanged; } catch { }
                try { c.Device?.Dispose(); } catch { }
                try { c.Request?.Dispose(); } catch { }
                c.Device = null; c.Request = null;
            }
        }

        private void ReleaseWinRt()
        {
            WiFiDirectAdvertisementPublisher? pub;
            WiFiDirectConnectionListener? listener;
            lock (_gate)
            {
                pub = _publisher; listener = _listener;
                _publisher = null; _listener = null;
                _startTcs?.TrySetCanceled();
                _startTcs = null;
                _started = false;
            }
            if (pub != null)
            {
                try
                {
                    if (pub.Status is WiFiDirectAdvertisementPublisherStatus.Started or WiFiDirectAdvertisementPublisherStatus.Created)
                        pub.Stop();
                }
                catch (Exception ex) { LinkLog.Ev(_logLid, "go_stop_error", ("err", ex.Message)); }
                try { pub.StatusChanged -= OnStatusChanged; } catch { }
            }
            if (listener != null)
            {
                try { listener.ConnectionRequested -= OnConnectionRequested; } catch { }
            }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 1) return;
            Stop();
        }
    }
}
