using System;
using System.Collections.Generic;
using System.Text.Json.Nodes;

namespace AirBridge
{
    /// <summary>A peer with a ready (authenticated) session.</summary>
    public sealed record PeerInfo(
        string Id,
        string Name,
        string Platform,
        IReadOnlySet<string> Caps,
        int ScreenW = 0,
        int ScreenH = 0);

    /// <summary>
    /// Implemented by the network half. Send never blocks on the network.
    /// </summary>
    public interface IPeerTransport
    {
        /// <summary>Send to one peer. Returns false if that peer has no ready session.</summary>
        bool Send(string peerId, JsonObject msg);
        /// <summary>Send to every ready peer.</summary>
        void Broadcast(JsonObject msg);
        /// <summary>Snapshot of ready peers.</summary>
        IReadOnlyList<PeerInfo> Peers();
    }

    /// <summary>
    /// Meeting point between the network half (discovery, sessions, pairing, clipboard, file transfer)
    /// and the input half (low-level hooks, cursor routing, SendInput injection, display arrangement).
    /// See docs/protocol.md §12.
    ///
    /// Network half: assigns <see cref="Transport"/>; calls <see cref="DispatchControl"/> for control-type
    /// messages and <see cref="NotifyPeers"/> whenever the set of ready peers changes.
    ///
    /// Input half: sets <see cref="ControlHandler"/> and <see cref="PeersHandler"/>; calls
    /// <see cref="Send"/> / <see cref="Broadcast"/> / <see cref="Peers"/>.
    ///
    /// Threading: <see cref="ControlHandler"/> is invoked on a network thread; the input half marshals to the
    /// UI/STA thread itself where required.
    /// </summary>
    public static class PeerBus
    {
        /// <summary>Message types that belong to the input half (routed through <see cref="ControlHandler"/>).</summary>
        public static readonly IReadOnlySet<string> ControlTypes = new HashSet<string>
        {
            "ctl_enter", "ctl_leave",
            "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click",
            "key", "key_state", "action",
        };

        public static volatile IPeerTransport? Transport;
        public static volatile Action<string, JsonObject>? ControlHandler;
        public static volatile Action<IReadOnlyList<PeerInfo>>? PeersHandler;

        public static bool Send(string peerId, JsonObject msg) => Transport?.Send(peerId, msg) ?? false;
        public static void Broadcast(JsonObject msg) => Transport?.Broadcast(msg);
        public static IReadOnlyList<PeerInfo> Peers() => Transport?.Peers() ?? Array.Empty<PeerInfo>();

        public static void DispatchControl(string peerId, JsonObject msg) => ControlHandler?.Invoke(peerId, msg);
        public static void NotifyPeers(IReadOnlyList<PeerInfo> peers) => PeersHandler?.Invoke(peers);
    }
}
