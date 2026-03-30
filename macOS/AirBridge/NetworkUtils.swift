//
//  ContentView.swift
//  AirBridge
//
//  Created by Shazan Zaidi on 29/03/26.
//

import Foundation

struct NetworkUtils {
    static func getLocalIPAddress() -> String? {
        var address: String?
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
                        // Ignore virtual machine interfaces or docker bridges if possible
                        if !ipString.hasPrefix("169.254") && address == nil {
                            address = ipString
                        }
                    }
                }
            }
            freeifaddrs(ifaddr)
        }
        return address
    }
}
