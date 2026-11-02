using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AirBridge.Net
{
    /// <summary>
    /// WebSocket host (§2.2): TcpListener on 47632…47641 plus a minimal RFC 6455 upgrade, then
    /// System.Net.WebSockets via WebSocket.CreateFromStream. Doing the upgrade ourselves lets us answer
    /// 404 for any path other than /v2, disable Nagle, and never negotiate permessage-deflate.
    /// </summary>
    public sealed class Server
    {
        private const int MaxHeaderBytes = 8192;
        private const string WsGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private readonly PeerService _svc;
        private TcpListener? _listener;
        private CancellationTokenSource? _cts;

        public int Port { get; private set; }
        public bool IsListening => _listener != null;

        public Server(PeerService svc) { _svc = svc; }

        /// <summary>Binds the first free port in the range. Returns false if all are busy.</summary>
        public bool Start()
        {
            for (int port = Protocol.BasePort; port < Protocol.BasePort + Protocol.PortCount; port++)
            {
                TcpListener? l = null;
                try
                {
                    l = CreateListener(port);
                    l.Start(64);
                    _listener = l;
                    Port = port;
                    _cts = new CancellationTokenSource();
                    _ = AcceptLoopAsync(l, _cts.Token);
                    Log.Info($"Listening on port {port}");
                    return true;
                }
                catch (SocketException ex)
                {
                    Log.Warn($"Port {port} unavailable: {ex.SocketErrorCode}");
                    try { l?.Stop(); } catch { }
                }
            }
            return false;
        }

        public void Stop()
        {
            _cts?.Cancel();
            try { _listener?.Stop(); } catch { }
            _listener = null;
        }

        private static TcpListener CreateListener(int port)
        {
            if (Socket.OSSupportsIPv6)
            {
                try
                {
                    var l6 = new TcpListener(IPAddress.IPv6Any, port) { ExclusiveAddressUse = true };
                    l6.Server.DualMode = true;
                    return l6;
                }
                catch (SocketException) { /* IPv6 disabled: fall back */ }
            }
            return new TcpListener(IPAddress.Any, port) { ExclusiveAddressUse = true };
        }

        private async Task AcceptLoopAsync(TcpListener l, CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                TcpClient client;
                try { client = await l.AcceptTcpClientAsync(ct).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
                catch (ObjectDisposedException) { return; }
                catch (SocketException ex)
                {
                    Log.Warn($"Accept failed: {ex.SocketErrorCode}");
                    await Task.Delay(200, CancellationToken.None).ConfigureAwait(false);
                    continue;
                }
                _ = Task.Run(() => HandleClientAsync(client));
            }
        }

        private async Task HandleClientAsync(TcpClient client)
        {
            string remote = "?";
            int remotePort = 0;
            try
            {
                if (client.Client.RemoteEndPoint is IPEndPoint ep)
                {
                    var addr = ep.Address.IsIPv4MappedToIPv6 ? ep.Address.MapToIPv4() : ep.Address;
                    remote = addr.ToString();
                    remotePort = ep.Port;
                }
                client.NoDelay = true;
                var stream = client.GetStream();

                if (!_svc.TryReservePendingSlot())
                {
                    await WriteStatusAsync(stream, "503 Service Unavailable").ConfigureAwait(false);
                    client.Dispose();
                    return;
                }

                bool handedOff = false;
                try
                {
                    using var timeout = new CancellationTokenSource(Protocol.HandshakeTimeout);
                    string? head = await ReadHeadAsync(stream, timeout.Token).ConfigureAwait(false);
                    if (head == null) { client.Dispose(); return; }

                    var lines = head.Split("\r\n");
                    var request = lines[0].Split(' ');
                    var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                    for (int i = 1; i < lines.Length; i++)
                    {
                        int c = lines[i].IndexOf(':');
                        if (c > 0) headers[lines[i][..c].Trim()] = lines[i][(c + 1)..].Trim();
                    }

                    if (request.Length < 3 || request[0] != "GET")
                    {
                        await WriteStatusAsync(stream, "405 Method Not Allowed").ConfigureAwait(false);
                        client.Dispose();
                        return;
                    }
                    string path = request[1].Split('?')[0];
                    if (path != Protocol.Path)
                    {
                        await WriteStatusAsync(stream, "404 Not Found").ConfigureAwait(false);
                        client.Dispose();
                        return;
                    }
                    headers.TryGetValue("Upgrade", out var upgrade);
                    headers.TryGetValue("Sec-WebSocket-Key", out var key);
                    headers.TryGetValue("Sec-WebSocket-Version", out var version);
                    if (upgrade == null || !upgrade.Contains("websocket", StringComparison.OrdinalIgnoreCase) || string.IsNullOrEmpty(key))
                    {
                        await WriteStatusAsync(stream, "400 Bad Request").ConfigureAwait(false);
                        client.Dispose();
                        return;
                    }
                    if (version != "13")
                    {
                        await WriteStatusAsync(stream, "426 Upgrade Required", "Sec-WebSocket-Version: 13\r\n").ConfigureAwait(false);
                        client.Dispose();
                        return;
                    }

                    string accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + WsGuid)));
                    string response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                      $"Sec-WebSocket-Accept: {accept}\r\n\r\n";
                    byte[] resp = Encoding.ASCII.GetBytes(response);
                    await stream.WriteAsync(resp, timeout.Token).ConfigureAwait(false);

                    var ws = WebSocket.CreateFromStream(stream, new WebSocketCreationOptions
                    {
                        IsServer = true,
                        KeepAliveInterval = TimeSpan.FromSeconds(30),
                    });
                    handedOff = true;
                    _svc.RunInbound(ws, remote, remotePort, client);
                }
                finally
                {
                    if (!handedOff) _svc.ReleasePendingSlot();
                }
            }
            catch (Exception ex)
            {
                Log.Warn($"Inbound connection from {remote} failed during upgrade: {ex.Message}");
                try { client.Dispose(); } catch { }
            }
        }

        /// <summary>Reads the HTTP request head byte by byte (the client sends nothing else before our 101).</summary>
        private static async Task<string?> ReadHeadAsync(Stream s, CancellationToken ct)
        {
            var buf = new byte[MaxHeaderBytes];
            int n = 0;
            while (n < buf.Length)
            {
                int r = await s.ReadAsync(buf.AsMemory(n, 1), ct).ConfigureAwait(false);
                if (r == 0) return null;
                n++;
                if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n' && buf[n - 2] == '\r' && buf[n - 1] == '\n')
                    return Encoding.ASCII.GetString(buf, 0, n - 4);
            }
            return null;
        }

        private static async Task WriteStatusAsync(Stream s, string status, string extraHeaders = "")
        {
            try
            {
                byte[] b = Encoding.ASCII.GetBytes($"HTTP/1.1 {status}\r\n{extraHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n");
                using var t = new CancellationTokenSource(2000);
                await s.WriteAsync(b, t.Token).ConfigureAwait(false);
            }
            catch { }
        }
    }
}
