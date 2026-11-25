import Foundation
import Network

// The two WebSocket helpers Transport.swift uses, copied so the transport builds on its own.
enum WebSocket {
    static func tcpParameters() -> NWParameters { NWParameters(tls: nil, tcp: NWProtocolTCP.Options()) }
    static func hostString(_ host: NWEndpoint.Host) -> String { "\(host)" }
}
