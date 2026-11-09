// Copy of the `Msg` readers from AirBridge/Input/Injector.swift so the check builds without the app.
import Foundation
import CoreGraphics

nonisolated enum Msg {
    static func num(_ v: Any?) -> Double? {
        switch v {
        case let d as Double: return d
        case let i as Int: return Double(i)
        case let n as NSNumber: return n.doubleValue
        case let f as Float: return Double(f)
        case let c as CGFloat: return Double(c)
        default: return nil
        }
    }
    static func int(_ v: Any?) -> Int? { num(v).map { Int($0) } }
}
