//
//  SyncServer.swift
//  AirBridge
//

import Foundation
import Network
import AppKit
import Combine
import UserNotifications

class SyncServer: ObservableObject {
    @Published var localIP: String = "127.0.0.1"
    @Published var connectedClients: Int = 0
    @Published var port: UInt16 = 0
    @Published var macName: String = Host.current().localizedName ?? "Mac"
    
    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private let connectionsLock = NSLock()

    private var lastChangeCount: Int = 0
    private var timer: Timer?
    
    @Published var isReceivingBatch = false
    @Published var batchProgress: Double = 0.0
    @Published var batchStatus: String = ""
    @Published var batchSpeed: String = ""
    @Published var batchETA: String = ""
    
    private var totalIncomingFiles: Int = 0
    private var receivedFilesCount: Int = 0
    private var totalBatchBytes: Int = 0
    private var receivedBatchBytes: Int = 0
    private var batchStartTime: Date?
    private var lastNotifTime: Date?
    
    private var isReceivingFile = false
    private var currentFileHandle: FileHandle?
    private var currentFileName: String = ""
    private var currentFileURL: URL?
    
    
    init() {
        self.localIP = NetworkUtils.getLocalIPAddress() ?? "127.0.0.1"
        startServer()
        startClipboardMonitor()
    }
    
    private func startServer() {
        let parameters = NWParameters.tcp
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true
        wsOptions.maximumMessageSize = 1024 * 1024 * 500
        
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)
        
        do {
            listener = try NWListener(using: parameters)
            listener?.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            listener?.stateUpdateHandler = { [weak self] state in
                if case .ready = state, let actualPort = self?.listener?.port?.rawValue {
                    DispatchQueue.main.async {
                        self?.port = actualPort
                    }
                }
            }
            listener?.start(queue: .global(qos: .userInitiated))
        } catch {
            print("Failed to start server: \(error)")
        }
    }
    
    private func handleIncomingFile(data: Data) {
        let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        let fileName = "AirBridge_Transfer_\(Int(Date().timeIntervalSince1970)).dat"
        let fileURL = downloadsURL.appendingPathComponent(fileName)
        
        do {
            try data.write(to: fileURL)
            if let image = NSImage(data: data) {
                DispatchQueue.main.async {
                    let pb = NSPasteboard.general
                    pb.clearContents()
                    pb.writeObjects([image])
                    self.lastChangeCount = pb.changeCount
                }
            }
            triggerTransferNotification(fileName: fileName, fileURL: fileURL)
            
        } catch {
            print("Failed to save file: \(error)")
        }
    }
    
    private func triggerTransferNotification(fileName: String, fileURL: URL) {
        let content = UNMutableNotificationContent()
        content.title = "AirBridge"
        content.subtitle = "Transfer Complete"
        content.body = "Received: \(fileName)"
        content.sound = UNNotificationSound(named: UNNotificationSoundName("Glass.aiff"))
        
        if #available(macOS 12.0, *) {
            content.interruptionLevel = .timeSensitive
        }
        
        content.categoryIdentifier = "FILE_TRANSFER"
        content.userInfo = ["filePath": fileURL.path]
        
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
    
    private func handleNewConnection(_ connection: NWConnection) {
            connection.start(queue: .global(qos: .userInitiated))
            
            connectionsLock.lock()
            self.connections.append(connection)
            let activeCount = self.connections.count
            connectionsLock.unlock()
            
            DispatchQueue.main.async { self.connectedClients = activeCount }
            receive(on: connection)
        }
    
    private func formatETA(seconds: Double) -> String {
        if seconds < 60 { return "\(Int(seconds))s remaining" }
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return "\(mins)m \(secs)s remaining"
    }
    
    private func removeConnection(_ connection: NWConnection) {
            connection.cancel()
            
            connectionsLock.lock()
            self.connections.removeAll(where: { $0 === connection })
            let activeCount = self.connections.count
            connectionsLock.unlock()
            
            DispatchQueue.main.async { self.connectedClients = activeCount }
        }
    
    private func startClipboardMonitor() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let pb = NSPasteboard.general
            if pb.changeCount != self.lastChangeCount {
                self.lastChangeCount = pb.changeCount
                if let newString = pb.string(forType: .string) {
                    self.broadcast(text: newString)
                }
            }
        }
    }
    
    private func broadcast(text: String) {
            guard let data = text.data(using: .utf8) else { return }
            let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
            let context = NWConnection.ContentContext(identifier: "textContext", metadata: [metadata])
            
            connectionsLock.lock()
            let activeConnections = connections
            connectionsLock.unlock()
            
            for connection in activeConnections {
                connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed({ _ in }))
            }
        }
    
    private let progressNotificationID = "AIRBRIDGE_PROGRESS_ID"

    private func updateProgressNotification(total: Int) {
        let content = UNMutableNotificationContent()
        content.title = "AirBridge Transfer"
        content.subtitle = "Receiving \(total) files..."
        content.body = "Click the AirBridge menu bar icon for live progress."
        
        if #available(macOS 12.0, *) {
            content.interruptionLevel = .passive
        }

        let request = UNNotificationRequest(identifier: progressNotificationID, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    private func receive(on connection: NWConnection) {
        connection.receiveMessage { [weak self] (content, context, isComplete, error) in
            guard let self = self else { return }
            
            if error != nil {
                self.removeConnection(connection)
                return
            }
            
            if let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata {
                if metadata.opcode == .text, let data = content, let text = String(data: data, encoding: .utf8) {
                    
                    if text.hasPrefix("BATCH_START:") {
                        let components = text.replacingOccurrences(of: "BATCH_START:", with: "").components(separatedBy: ":")
                        self.totalIncomingFiles = Int(components.first ?? "1") ?? 1
                        self.totalBatchBytes = components.count > 1 ? (Int(components[1]) ?? 0) : 0
                        
                        self.receivedFilesCount = 0
                        self.receivedBatchBytes = 0
                        self.batchStartTime = Date()
                        self.lastNotifTime = Date()
                        
                        DispatchQueue.main.async {
                            self.isReceivingBatch = true
                            self.batchProgress = 0.0
                            self.batchStatus = "Receiving 0 of \(self.totalIncomingFiles)"
                            self.batchSpeed = "Calculating..."
                            self.batchETA = "..."
                            self.updateProgressNotification(total: self.totalIncomingFiles)
                        }
                    } else if text.hasPrefix("START_FILE:") {
                        self.isReceivingFile = true
                        self.currentFileName = String(text.dropFirst("START_FILE:".count))
                        
                        let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
                        let airBridgeDir = downloadsURL.appendingPathComponent("AirBridge Transfers")
                        try? FileManager.default.createDirectory(at: airBridgeDir, withIntermediateDirectories: true)
                        
                        self.currentFileURL = airBridgeDir.appendingPathComponent(self.currentFileName)
                        
                        if let fileURL = self.currentFileURL {
                            FileManager.default.createFile(atPath: fileURL.path, contents: nil, attributes: nil)
                            self.currentFileHandle = try? FileHandle(forWritingTo: fileURL)
                        }
                    } else if text.hasPrefix("END_FILE:") {
                        self.isReceivingFile = false
                        self.currentFileHandle?.closeFile()
                        self.currentFileHandle = nil
                        
                        self.receivedFilesCount += 1
                        
                        DispatchQueue.main.async {
                            self.batchStatus = "Receiving \(self.receivedFilesCount) of \(self.totalIncomingFiles)"
                            
                            if let fileURL = self.currentFileURL {
                                let pb = NSPasteboard.general
                                pb.clearContents()
                                pb.writeObjects([fileURL as NSURL])
                                self.lastChangeCount = pb.changeCount
                            }
                            
                            if self.receivedFilesCount >= self.totalIncomingFiles {
                                self.isReceivingBatch = false
                                UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [self.progressNotificationID])
                                self.triggerTransferNotification(fileName: "\(self.totalIncomingFiles) files", fileURL: self.currentFileURL!)
                            }
                        }
                    } else if text == "INP:TRANSITION:MAC" {
                        InputInterceptor.shared.forceReturnToMac()
                   } else if text.hasPrefix("INP:") {
                        // Ignore other input commands
                   } else {
                        DispatchQueue.main.async {
                            let pb = NSPasteboard.general
                            pb.clearContents()
                            pb.setString(text, forType: .string)
                            self.lastChangeCount = pb.changeCount
                        }
                    }
                    
                } else if metadata.opcode == .binary, let data = content {
                    if self.isReceivingFile {
                        if #available(macOS 10.15.4, *) {
                           _ = try? self.currentFileHandle?.seekToEnd()
                        } else {
                            self.currentFileHandle?.seekToEndOfFile()
                        }
                        self.currentFileHandle?.write(data)
                        
                        self.receivedBatchBytes += data.count
                        let now = Date()
                        if let startTime = self.batchStartTime, now.timeIntervalSince(self.lastNotifTime ?? startTime) > 1.0 {
                            self.lastNotifTime = now
                            let elapsed = now.timeIntervalSince(startTime)
                            let speed = Double(self.receivedBatchBytes) / elapsed
                            let remainingBytes = Double(self.totalBatchBytes - self.receivedBatchBytes)
                            let etaSeconds = speed > 0 ? remainingBytes / speed : 0
                            
                            let speedStr = String(format: "%.1f MB/s", speed / (1024 * 1024))
                            let etaStr = self.formatETA(seconds: etaSeconds)
                            
                            DispatchQueue.main.async {
                                self.batchProgress = self.totalBatchBytes > 0 ? Double(self.receivedBatchBytes) / Double(self.totalBatchBytes) : 0
                                self.batchSpeed = speedStr
                                self.batchETA = etaStr
                            }
                        }
                    } else {
                        self.handleIncomingFile(data: data)
                    }
                }
            }
            self.receive(on: connection)
        }
    }
    
    func broadcastInput(command: String) {
            guard let data = command.data(using: .utf8) else { return }
            let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
            let context = NWConnection.ContentContext(identifier: "inputContext", metadata: [metadata])
            
            connectionsLock.lock()
            let activeConnections = connections
            connectionsLock.unlock()
            
            for connection in activeConnections {
                connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed({ _ in }))
            }
        }
}
