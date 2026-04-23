//
//  DisplayArrangementView.swift
//  AirBridge
//
//  Created by Shazan Zaidi on 08/04/26.
//

import SwiftUI
import CoreGraphics

struct DisplayArrangementView: View {
    let macWidth: CGFloat = 400
    let macHeight: CGFloat = 250
    let phoneWidth: CGFloat = 70
    let phoneHeight: CGFloat = 140
    
    // NEW: Persistent Storage for the UI Position
    @AppStorage("PhonePosX") private var phonePosX: Double = -70.0
    @AppStorage("PhonePosY") private var phonePosY: Double = 55.0
    
    @State private var position: CGPoint = CGPoint(x: -70, y: 55)
    @State private var dragOffset: CGSize = .zero
    @State private var isDragging: Bool = false
    
    var body: some View {
        VStack(spacing: 20) {
            Text("Arrange Displays")
                .font(.headline)
            Text("Drag your phone to position it relative to your Mac.")
                .font(.subheadline)
                .foregroundColor(.secondary)
            
            ZStack(alignment: .topLeading) {
                Color(NSColor.controlBackgroundColor)
                    .frame(width: 600, height: 450)
                
                // --- Mac Representation ---
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.blue.opacity(0.8))
                    .frame(width: macWidth, height: macHeight)
                    .overlay(Text("Mac").foregroundColor(.white).font(.title2).bold())
                    .offset(x: 100, y: 100) // Center Mac in the ZStack
                
                // --- Phone Representation ---
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.green.opacity(0.9))
                    .frame(width: phoneWidth, height: phoneHeight)
                    .overlay(Text("Phone").foregroundColor(.white).font(.caption).bold())
                    .shadow(color: .black.opacity(isDragging ? 0.3 : 0), radius: 5, x: 0, y: 5)
                    .scaleEffect(isDragging ? 1.05 : 1.0)
                    .offset(
                        x: 100 + position.x + dragOffset.width,
                        y: 100 + position.y + dragOffset.height
                    )
                    .gesture(
                                            DragGesture()
                                                .onChanged { value in
                                                    isDragging = true
                                                    let rawX = position.x + value.translation.width
                                                    let rawY = position.y + value.translation.height
                                                    let snapped = calculateSnap(x: rawX, y: rawY)
                                                    
                                                    dragOffset = CGSize(width: snapped.x - position.x, height: snapped.y - position.y)
                                                }
                                                .onEnded { _ in
                                                    isDragging = false
                                                    position.x += dragOffset.width
                                                    position.y += dragOffset.height
                                                    dragOffset = .zero
                                                    
                                                    // NEW: Save the visual position when drag ends
                                                    phonePosX = Double(position.x)
                                                    phonePosY = Double(position.y)
                                                    
                                                    applySettingsToInterceptor()
                                                }
                                        )
                                        .animation(.interactiveSpring(), value: dragOffset)
                                }
                                .clipShape(RoundedRectangle(cornerRadius: 16))
                                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.gray.opacity(0.2), lineWidth: 1))
                            }
                            .padding(30)
                            .onAppear {
                                // NEW: Load visual position on UI open
                                position = CGPoint(x: phonePosX, y: phonePosY)
                                applySettingsToInterceptor()
                            }
                        }
    
    // MARK: - Snapping Logic
    private func calculateSnap(x: CGFloat, y: CGFloat) -> CGPoint {
        // Distances to the 4 edges of the Mac
        let distRight = abs(x - macWidth)
        let distLeft = abs(x + phoneWidth)
        let distBottom = abs(y - macHeight)
        let distTop = abs(y + phoneHeight)
        
        let minDist = min(distRight, distLeft, distBottom, distTop)
        
        var newX = x
        var newY = y
        let padding: CGFloat = 10 // How far it can slide past the corners before clamping
        
        if minDist == distRight {
            newX = macWidth
            newY = max(-padding, min(y, macHeight - phoneHeight + padding))
        } else if minDist == distLeft {
            newX = -phoneWidth
            newY = max(-padding, min(y, macHeight - phoneHeight + padding))
        } else if minDist == distBottom {
            newY = macHeight
            newX = max(-padding, min(x, macWidth - phoneWidth + padding))
        } else {
            newY = -phoneHeight
            newX = max(-padding, min(x, macWidth - phoneWidth + padding))
        }
        
        return CGPoint(x: newX, y: newY)
    }
    
    // MARK: - Apply Math to Interceptor
    private func applySettingsToInterceptor() {
            let screenBounds = CGDisplayBounds(CGMainDisplayID())
            let scaleX = screenBounds.width / macWidth
            let scaleY = screenBounds.height / macHeight
            
            let edge: DeviceEdge
            var range: ClosedRange<CGFloat>
            
            if position.x >= macWidth - 0.5 {
                edge = .right
                let startY = position.y * scaleY
                let endY = (position.y + phoneHeight) * scaleY
                range = startY...endY
            } else if position.x <= -phoneWidth + 0.5 {
                edge = .left
                let startY = position.y * scaleY
                let endY = (position.y + phoneHeight) * scaleY
                range = startY...endY
            } else if position.y >= macHeight - 0.5 {
                edge = .bottom
                let startX = position.x * scaleX
                let endX = (position.x + phoneWidth) * scaleX
                range = startX...endX
            } else {
                edge = .top
                let startX = position.x * scaleX
                let endX = (position.x + phoneWidth) * scaleX
                range = startX...endX
            }
            
            InputInterceptor.shared.phoneEdge = edge
            InputInterceptor.shared.phoneEdgeRange = range
            
            // NEW: Save the actual bounds math for the Interceptor to read on app launch
            UserDefaults.standard.set(edge.rawValue, forKey: "PhoneEdge")
            UserDefaults.standard.set(Double(range.lowerBound), forKey: "PhoneEdgeLower")
            UserDefaults.standard.set(Double(range.upperBound), forKey: "PhoneEdgeUpper")
        }
}
