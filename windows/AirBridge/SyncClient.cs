using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Zeroconf;

namespace AirBridge
{
    public class SyncClient
    {
        private ClientWebSocket _webSocket;
        private CancellationTokenSource _cts;

        public event Action<string> OnCommandReceived;
        public event Action<byte[]> OnBinaryReceived;
        public event Action<string> OnConnected;
        public event Action OnDisconnected;

        public bool IsConnected => _webSocket != null && _webSocket.State == WebSocketState.Open;
        public string ConnectedHostUrl { get; private set; }

        public async Task DiscoverAndConnectAsync()
        {
            Debug.WriteLine("Client: Scanning for initial Host...");
            IReadOnlyList<IZeroconfHost> results = await ZeroconfResolver.ResolveAsync("_airbridge._tcp.local.", new TimeSpan(0, 0, 2));

            foreach (var host in results)
            {
                foreach (var service in host.Services)
                {
                    string wsUrl = $"ws://{host.IPAddress}:{service.Value.Port}?name={Uri.EscapeDataString(Environment.MachineName)}";
                    await ConnectAsync(wsUrl);
                    return;
                }
            }
        }

        public async Task<List<(string Name, string Url)>> ScanForHostsAsync()
        {
            var discoveredHosts = new List<(string Name, string Url)>();
            IReadOnlyList<IZeroconfHost> results = await ZeroconfResolver.ResolveAsync("_airbridge._tcp.local.", new TimeSpan(0, 0, 3));

            foreach (var host in results)
            {
                foreach (var service in host.Services)
                {
                    string wsUrl = $"ws://{host.IPAddress}:{service.Value.Port}?name={Uri.EscapeDataString(Environment.MachineName)}";
                    string displayName = !string.IsNullOrEmpty(host.DisplayName) ? host.DisplayName : host.IPAddress;
                    discoveredHosts.Add((displayName, wsUrl));
                }
            }
            return discoveredHosts;
        }

        public void Disconnect()
        {
            if (_webSocket != null && _webSocket.State == WebSocketState.Open)
            {
                _ = _webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "User disconnected", CancellationToken.None);
            }
        }

        public async Task ConnectAsync(string url)
        {
            if (_webSocket != null)
            {
                if (_webSocket.State == WebSocketState.Open)
                {
                    await _webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Switching hosts", CancellationToken.None);
                }
                _webSocket.Dispose();
            }

            _webSocket = new ClientWebSocket();
            _cts = new CancellationTokenSource();

            try
            {
                await _webSocket.ConnectAsync(new Uri(url), _cts.Token);
                ConnectedHostUrl = url;
                OnConnected?.Invoke(url);
                Debug.WriteLine($"Client: Successfully connected to Host at {url}");

                _ = ReceiveLoopAsync();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Client: Connection failed - {ex.Message}");
            }
        }

        private async Task ReceiveLoopAsync()
        {
            var buffer = new byte[256 * 1024];

            try
            {
                while (_webSocket.State == WebSocketState.Open)
                {
                    var result = await _webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), _cts.Token);

                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        await _webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Host closed", CancellationToken.None);
                        OnDisconnected?.Invoke();
                        break;
                    }

                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        string message = Encoding.UTF8.GetString(buffer, 0, result.Count);
                        OnCommandReceived?.Invoke(message);
                    }
                    else if (result.MessageType == WebSocketMessageType.Binary)
                    {
                        byte[] exactData = new byte[result.Count];
                        Buffer.BlockCopy(buffer, 0, exactData, 0, result.Count);
                        OnBinaryReceived?.Invoke(exactData);
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Client: Socket exception - {ex.Message}");
                OnDisconnected?.Invoke();
            }
        }

        public async Task SendBinaryAsync(byte[] data)
        {
            if (_webSocket != null && _webSocket.State == WebSocketState.Open)
            {
                await _webSocket.SendAsync(new ArraySegment<byte>(data), WebSocketMessageType.Binary, true, CancellationToken.None);
            }
        }

        public async Task SendAsync(string command)
        {
            if (_webSocket != null && _webSocket.State == WebSocketState.Open)
            {
                var bytes = Encoding.UTF8.GetBytes(command);
                await _webSocket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
            }
        }
    }
}