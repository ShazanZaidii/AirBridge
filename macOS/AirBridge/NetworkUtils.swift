import Foundation

/// Primary LAN IPv4 address, used for the pairing QR `addr` hint and the menu header.
nonisolated enum NetworkUtils {
    static func getLocalIPAddress() -> String? {
        var address: String?
        var fallbackAddress: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>? = nil
        
        if getifaddrs(&ifaddr) == 0 {
            var ptr = ifaddr
            while ptr != nil {
                defer { ptr = ptr?.pointee.ifa_next }
                
                guard let interface = ptr?.pointee else { continue }
                let addrFamily = interface.ifa_addr.pointee.sa_family
                
                // Check for IPv4
                if addrFamily == UInt8(AF_INET) {
                    let name = String(cString: interface.ifa_name)
                    let flags = Int32(interface.ifa_flags)
                    
                    // Ensure it's active (IFF_UP) and NOT a loopback (IFF_LOOPBACK)
                    if (flags & IFF_UP) == IFF_UP && (flags & IFF_LOOPBACK) == 0 {
                        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                        getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                                    &hostname, socklen_t(hostname.count),
                                    nil, socklen_t(0), NI_NUMERICHOST)
                        
                        let ipString = String(cString: hostname)
                        
                        // 1. Blacklist internal virtual networks, Docker bridges, and VPN tunnels
                        if ipString.hasPrefix("169.254") || name.hasPrefix("utun") || name.hasPrefix("bridge") || name.hasPrefix("vmnet") || name.hasPrefix("vnic") {
                            continue
                        }
                        
                        // 2. The Golden Standard: 'en0' is primary Wi-Fi, 'en1' is physical Ethernet
                        if name == "en0" || name == "en1" {
                            address = ipString
                            break // We found the true local router IP, stop searching
                        } else if fallbackAddress == nil {
                            // 3. Keep a fallback just in case they are using a weird USB-C adapter (like en3)
                            fallbackAddress = ipString
                        }
                    }
                }
            }
            freeifaddrs(ifaddr)
        }
        
        return address ?? fallbackAddress
    }
}
