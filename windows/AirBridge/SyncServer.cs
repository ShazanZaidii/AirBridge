using System;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using Fleck;
using Makaretu.Dns;

namespace AirBridge
{
    public class SyncServer
    {
        private WebSocketServer _server;
        private IWebSocketConnection _activeClient;
        public int Port { get; private set; } = 8080;

        public bool HasActiveClient => _activeClient != null && _activeClient.IsAvailable;
        public string ActiveClientIp => _activeClient?.ConnectionInfo.ClientIpAddress;

        public event Action<string> OnCommandReceived;
        public event Action<byte[]> OnBinaryReceived;
        public event Action<string> OnClientConnected;
        public event Action OnClientDisconnected;

        public Task StartAsync(int preferredPort = 8080)
        {
            string localIp = GetLocalIPAddress();
            if (localIp == null) return Task.CompletedTask;

            Port = preferredPort;
            bool isStarted = false;

            FleckLog.Level = LogLevel.Error; // Keep console clean

            while (!isStarted && Port <= 65535)
            {
                try
                {
                    _server = new WebSocketServer($"ws://0.0.0.0:{Port}");
                    _server.Start(socket =>
                    {
                        socket.OnOpen = () =>
                        {
                            _activeClient = socket;
                            OnClientConnected?.Invoke(socket.ConnectionInfo.ClientIpAddress);
                        };
                        socket.OnClose = () =>
                        {
                            if (_activeClient == socket) _activeClient = null;
                            OnClientDisconnected?.Invoke();
                        };
                        socket.OnMessage = message => OnCommandReceived?.Invoke(message);
                        socket.OnBinary = bytes => OnBinaryReceived?.Invoke(bytes);
                    });

                    isStarted = true;
                    Debug.WriteLine($"Server: Listening on {localIp}:{Port}");
                    BroadcastMdns(Port);
                }
                catch (Exception)
                {
                    Port++; // Port busy, try next
                }
            }
            return Task.CompletedTask;
        }

        private void BroadcastMdns(int port)
        {
            try
            {
                var mdns = new MulticastService();
                var sd = new ServiceDiscovery(mdns);
                string cleanName = Environment.MachineName.Replace(" ", "-");
                var profile = new ServiceProfile(cleanName, "_airbridge._tcp", (ushort)port);
                sd.Advertise(profile);
                mdns.Start();
            }
            catch { }
        }

        public void DisconnectClient()
        {
            _activeClient?.Close();
            _activeClient = null;
            OnClientDisconnected?.Invoke();
        }

        public Task SendAsync(string command)
        {
            if (_activeClient != null && _activeClient.IsAvailable)
                return _activeClient.Send(command);
            return Task.CompletedTask;
        }

        public Task SendBinaryAsync(byte[] data)
        {
            if (_activeClient != null && _activeClient.IsAvailable)
                return _activeClient.Send(data);
            return Task.CompletedTask;
        }

        private string GetLocalIPAddress()
        {
            var host = Dns.GetHostEntry(Dns.GetHostName());
            foreach (var ip in host.AddressList)
            {
                if (ip.AddressFamily == AddressFamily.InterNetwork && !ip.ToString().EndsWith(".1"))
                    return ip.ToString();
            }
            return null;
        }
    }
}