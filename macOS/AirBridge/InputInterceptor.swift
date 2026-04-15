import Cocoa
import CoreGraphics

enum DeviceEdge: String {
    case left, right, top, bottom
}

class InputInterceptor {
    static let shared = InputInterceptor()
    
    private var eventTap: CFMachPort?
    private var isCursorOnPhone = false
    private weak var syncServer: SyncServer?
    
    var phoneEdge: DeviceEdge = .right
    var phoneEdgeRange: ClosedRange<CGFloat> = -5000...5000
    
    private let inputQueue = DispatchQueue(label: "com.airbridge.input", qos: .userInteractive)
    
    private var lastSystemSwipeTime: Date = Date.distantPast
    
    // ADDED: Cooldown tracker to prevent warp-bouncing
    private var lastTransitionTime: Date = Date.distantPast
    
    private var commandHandled = false
    private var isLeftCmdDown = false
    private var isRightCmdDown = false
    private var isEscDown = false
    
    private var swipeMonitorLocal: Any?
    private var swipeMonitorGlobal: Any?
    
    func setup(server: SyncServer) {
        self.syncServer = server
    }
    
    func start() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        guard AXIsProcessTrustedWithOptions(options) else { return }
        
        swipeMonitorLocal = NSEvent.addLocalMonitorForEvents(matching: .swipe) { [weak self] event in
            self?.handleSwipe(event)
            return event
        }
        swipeMonitorGlobal = NSEvent.addGlobalMonitorForEvents(matching: .swipe) { [weak self] event in
            self?.handleSwipe(event)
        }
        
        let eventMask = (1 << CGEventType.mouseMoved.rawValue) |
                        (1 << CGEventType.leftMouseDown.rawValue) |
                        (1 << CGEventType.leftMouseUp.rawValue) |
                        (1 << CGEventType.rightMouseDown.rawValue) |
                        (1 << CGEventType.rightMouseUp.rawValue) |
                        (1 << CGEventType.leftMouseDragged.rawValue) |
                        (1 << CGEventType.rightMouseDragged.rawValue) |
                        (1 << CGEventType.scrollWheel.rawValue) |
                        (1 << CGEventType.keyDown.rawValue) |
                        (1 << CGEventType.keyUp.rawValue) |
                        (1 << CGEventType.flagsChanged.rawValue) |
                        (1 << 14)
        
        let callback: CGEventTapCallBack = { (proxy, type, event, refcon) in
            let interceptor = Unmanaged<InputInterceptor>.fromOpaque(refcon!).takeUnretainedValue()
            return interceptor.handleEvent(event: event, type: type)
        }
        
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
            eventsOfInterest: CGEventMask(eventMask), callback: callback,
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else { return }
        
        self.eventTap = tap
        let runLoopSource = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetCurrent(), runLoopSource, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
    }
    
    private func handleSwipe(_ event: NSEvent) {
        if !isCursorOnPhone { return }
        
        let now = Date()
        if now.timeIntervalSince(lastSystemSwipeTime) < 0.3 { return }
        lastSystemSwipeTime = now
        
        if abs(event.deltaY) > abs(event.deltaX) {
            if event.deltaY > 0 {
                inputQueue.async { self.syncServer?.broadcastInput(command: "INP:SYS:RECENTS") }
            } else {
                inputQueue.async { self.syncServer?.broadcastInput(command: "INP:SYS:HOME") }
            }
        } else {
            if event.deltaX != 0 {
                inputQueue.async { self.syncServer?.broadcastInput(command: "INP:SYS:LAST_APP") }
            }
        }
    }
    
    func forceReturnToMac() {
        let action = {
            if !self.isCursorOnPhone { return }
            self.isCursorOnPhone = false
            
            CGAssociateMouseAndMouseCursorPosition(1)
            DispatchQueue.main.async { NSCursor.unhide() }
            
            let screenBounds = CGDisplayBounds(CGMainDisplayID())
            var returnPoint = CGEvent(source: nil)?.location ?? CGPoint(x: screenBounds.midX, y: screenBounds.midY)
            
            let inset: CGFloat = 20
            switch self.phoneEdge {
            case .right:  returnPoint.x = screenBounds.maxX - inset
            case .left:   returnPoint.x = screenBounds.minX + inset
            case .top:    returnPoint.y = screenBounds.minY + inset
            case .bottom: returnPoint.y = screenBounds.maxY - inset
            }
            
            switch self.phoneEdge {
            case .right, .left: returnPoint.y = max(self.phoneEdgeRange.lowerBound, min(self.phoneEdgeRange.upperBound, returnPoint.y))
            case .top, .bottom: returnPoint.x = max(self.phoneEdgeRange.lowerBound, min(self.phoneEdgeRange.upperBound, returnPoint.x))
            }
            CGWarpMouseCursorPosition(returnPoint)
        }
        if Thread.isMainThread { action() } else { DispatchQueue.main.async { action() } }
    }
    
    private func handleEvent(event: CGEvent, type: CGEventType) -> Unmanaged<CGEvent>? {
        let screenBounds = CGDisplayBounds(CGMainDisplayID())
        let location = event.location
        
        if !isCursorOnPhone {
            var hitPortal = false
            let threshold: CGFloat = 2.0
            
            switch phoneEdge {
            case .right: hitPortal = (location.x >= screenBounds.maxX - threshold) && phoneEdgeRange.contains(location.y)
            case .left: hitPortal = (location.x <= screenBounds.minX + threshold) && phoneEdgeRange.contains(location.y)
            case .top: hitPortal = (location.y <= screenBounds.minY + threshold) && phoneEdgeRange.contains(location.x)
            case .bottom: hitPortal = (location.y >= screenBounds.maxY - threshold) && phoneEdgeRange.contains(location.x)
            }
            
            if hitPortal && (syncServer?.connectedClients ?? 0) > 0 {
                isCursorOnPhone = true
                
                // Track the exact moment of transition to ignore warp deltas
                lastTransitionTime = Date()
                
                // Warp cursor to absolute bottom-right corner to hide it, then detach hardware mouse
                let hiddenPoint = CGPoint(x: screenBounds.maxX, y: screenBounds.maxY)
                CGWarpMouseCursorPosition(hiddenPoint)
                CGAssociateMouseAndMouseCursorPosition(0)
                DispatchQueue.main.async { NSCursor.hide() }
                
                let relX: CGFloat
                let relY: CGFloat
                if phoneEdge == .right || phoneEdge == .left {
                    let rangeSize = phoneEdgeRange.upperBound - phoneEdgeRange.lowerBound
                    relY = (location.y - phoneEdgeRange.lowerBound) / max(1, rangeSize)
                    relX = location.x / screenBounds.width
                } else {
                    let rangeSize = phoneEdgeRange.upperBound - phoneEdgeRange.lowerBound
                    relX = (location.x - phoneEdgeRange.lowerBound) / max(1, rangeSize)
                    relY = location.y / screenBounds.height
                }
                
                let clampedRelX = max(0.0, min(1.0, relX))
                let clampedRelY = max(0.0, min(1.0, relY))
                inputQueue.async { self.syncServer?.broadcastInput(command: "INP:TRANSITION:PHONE:\(self.phoneEdge.rawValue):\(clampedRelX):\(clampedRelY)") }
                return nil
            }
            return Unmanaged.passRetained(event)
        } else {
            if type.rawValue == 14 {
                if let nsEvent = NSEvent(cgEvent: event), nsEvent.subtype.rawValue == 8 {
                    let data = nsEvent.data1
                    let keyCode = (data & 0xFFFF0000) >> 16
                    let keyState = (data & 0xFF00) >> 8
                    
                    if keyState == 10 {
                        if keyCode == 0 { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:VOL:UP") }; return nil }
                        else if keyCode == 1 { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:VOL:DOWN") }; return nil }
                        else if keyCode == 7 { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:VOL:MUTE") }; return nil }
                    }
                }
            }

            switch type {
            case .mouseMoved, .leftMouseDragged, .rightMouseDragged:
                let dx = event.getIntegerValueField(.mouseEventDeltaX)
                let dy = event.getIntegerValueField(.mouseEventDeltaY)
                
                // CRITICAL FIX: Ignore massive synthetic deltas caused by CGWarpMouseCursorPosition
                // and give a 100ms cooldown so the warp doesn't instantly trigger the escape velocity.
                if abs(dx) > 500 || abs(dy) > 500 || Date().timeIntervalSince(lastTransitionTime) < 0.1 {
                    return nil
                }
                
                var escapeTriggered = false
                let escapeVelocity: Int64 = 60
                
                switch phoneEdge {
                case .right:  escapeTriggered = dx < -escapeVelocity
                case .left:   escapeTriggered = dx > escapeVelocity
                case .top:    escapeTriggered = dy > escapeVelocity
                case .bottom: escapeTriggered = dy < -escapeVelocity
                }
                
                if escapeTriggered {
                    forceReturnToMac()
                    inputQueue.async { self.syncServer?.broadcastInput(command: "INP:TRANSITION:MAC") }
                    return nil
                }
                
                inputQueue.async { self.syncServer?.broadcastInput(command: "INP:M:\(dx):\(dy):0:0") }
                
            case .scrollWheel:
                let scrollY = event.getIntegerValueField(.scrollWheelEventDeltaAxis1)
                let scrollX = event.getIntegerValueField(.scrollWheelEventDeltaAxis2)
                inputQueue.async { self.syncServer?.broadcastInput(command: "INP:M:0:0:\(scrollY):\(scrollX)") }
                
            case .flagsChanged:
                let keycode = event.getIntegerValueField(.keyboardEventKeycode)
                if keycode == 55 {
                    isRightCmdDown.toggle()
                    if isRightCmdDown { commandHandled = false }
                    else { if !commandHandled { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:SYS:HOME") } } }
                    return nil
                }
                if keycode == 54 {
                    isLeftCmdDown.toggle()
                    if isLeftCmdDown { commandHandled = false }
                    else { if !commandHandled { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:CMD:LEFT:UP") } } }
                    return nil
                }
                
            case .leftMouseDown: inputQueue.async { self.syncServer?.broadcastInput(command: "INP:CD") }
            case .leftMouseUp: inputQueue.async { self.syncServer?.broadcastInput(command: "INP:CU") }
            case .rightMouseDown: inputQueue.async { self.syncServer?.broadcastInput(command: "INP:RCD") }
            case .rightMouseUp: inputQueue.async { self.syncServer?.broadcastInput(command: "INP:RCU") }
            
            case .keyDown:
                let keycode = event.getIntegerValueField(.keyboardEventKeycode)
                let flags = event.flags
                let isRepeat = event.getIntegerValueField(.keyboardEventAutorepeat) != 0
                
                if keycode == 4 {
                    if !isRepeat { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:HOLD") } }
                    return nil
                }
                if keycode == 2 {
                    if !isRepeat { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:DRAG") } }
                    return nil
                }
                
                if flags.contains(.maskCommand) {
                    commandHandled = true
                    if keycode == 49 { if !isRepeat { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:SYS:DRAWER") } }; return nil }
                    if keycode == 48 { if !isRepeat { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:SYS:LAST_APP") } }; return nil }
                    if keycode == 8 { if !isRepeat { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:CMD:C") } }; return nil }
                    if keycode == 9 { if !isRepeat { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:CMD:V") } }; return nil }
                }
                
                if keycode == 51 {
                    if !isRepeat { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:SYS:RECENTS") } }
                    return nil
                }
                
                if keycode == 53 {
                    if !isEscDown {
                        isEscDown = true
                        inputQueue.async { self.syncServer?.broadcastInput(command: "INP:KD:\(keycode)") }
                    }
                    return nil
                }
                
                inputQueue.async { self.syncServer?.broadcastInput(command: "INP:KD:\(keycode)") }
                
            case .keyUp:
                let keycode = event.getIntegerValueField(.keyboardEventKeycode)
                
                if keycode == 4 { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:HOLD_RELEASE") }; return nil }
                if keycode == 2 { inputQueue.async { self.syncServer?.broadcastInput(command: "INP:DRAG_RELEASE") }; return nil }
                
                if keycode == 53 {
                    isEscDown = false
                    inputQueue.async { self.syncServer?.broadcastInput(command: "INP:KU:\(keycode)") }
                    return nil
                }
                
                inputQueue.async { self.syncServer?.broadcastInput(command: "INP:KU:\(keycode)") }
                
            default: break
            }
            return nil
        }
    }
}
