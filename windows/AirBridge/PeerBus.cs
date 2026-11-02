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
    /// Implemented by the network half. Sends never block on the network.
    /// </summary>
    public interface IPeerTransport
    {
        /// <summary>Send to one peer. Returns false if that peer has no ready session.</summary>
        bool Send(string peerId, JsonObject msg);
        /// <summary>Send to every ready peer.</summary>
        void Broadcast(JsonObject msg);
        /// <summary>Send an inner binary frame (already starting with its kind byte, e.g. 0x03) to one peer.</summary>
        bool SendBinary(string peerId, ReadOnlyMemory<byte> frame);
        /// <summary>Snapshot of ready peers.</summary>
        IReadOnlyList<PeerInfo> Peers();
    }

    /// <summary>
    /// Meeting point between the network half (discovery, sessions, pairing, encryption, clipboard, file transfer)
    /// and the input half (low-level hooks, cursor routing, SendInput injection, display arrangement, screen-mirror
    /// source). See docs/protocol.md §12.
    ///
    /// Network half: assigns <see cref="Transport"/>; calls <see cref="DispatchControl"/> for control-type
    /// messages, <see cref="DispatchBinary"/> for inner binary frames of kind 0x03 (video), and
    /// <see cref="NotifyPeers"/> whenever the set of ready peers changes. Everything the input half sees is
    /// already decrypted.
    ///
    /// Input half: sets <see cref="ControlHandler"/> / <see cref="BinaryHandler"/> / <see cref="PeersHandler"/>;
    /// calls <see cref="Send"/> / <see cref="Broadcast"/> / <see cref="SendBinary"/> / <see cref="Peers"/>.
    ///
    /// Threading: handlers are invoked on a network thread; the input half marshals to the UI/STA thread itself
    /// where required.
    /// </summary>
    public static class PeerBus
    {
        /// <summary>Message types that belong to the input half (routed through <see cref="ControlHandler"/>).</summary>
        public static readonly IReadOnlySet<string> ControlTypes = new HashSet<string>
        {
            "ctl_enter", "ctl_leave", "ctl_busy",
            "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click", "ptr_abs",
            "key", "key_state", "action",
            "mirror_start", "mirror_info", "mirror_key", "mirror_ack", "mirror_stop",
        };

        /// <summary>Inner binary frame kinds routed to the input half (first byte of the inner frame).</summary>
        public const byte BinaryKindVideo = 0x03;

        public static volatile IPeerTransport? Transport;
        public static volatile Action<string, JsonObject>? ControlHandler;
        public static volatile Action<string, ReadOnlyMemory<byte>>? BinaryHandler;
        public static volatile Action<IReadOnlyList<PeerInfo>>? PeersHandler;

        public static bool Send(string peerId, JsonObject msg) => Transport?.Send(peerId, msg) ?? false;
        public static void Broadcast(JsonObject msg) => Transport?.Broadcast(msg);
        public static bool SendBinary(string peerId, ReadOnlyMemory<byte> frame) => Transport?.SendBinary(peerId, frame) ?? false;
        public static IReadOnlyList<PeerInfo> Peers() => Transport?.Peers() ?? Array.Empty<PeerInfo>();

        public static void DispatchControl(string peerId, JsonObject msg) => ControlHandler?.Invoke(peerId, msg);
        public static void DispatchBinary(string peerId, ReadOnlyMemory<byte> frame) => BinaryHandler?.Invoke(peerId, frame);
        public static void NotifyPeers(IReadOnlyList<PeerInfo> peers) => PeersHandler?.Invoke(peers);
    }
}
