//
//  SyncServer.swift
//  AirBridge
//
//  Created by Shazan Zaidi on 29/03/26.
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
    
    private var lastChangeCount: Int = 0
    private var timer: Timer?
    
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
        
        // CRITICAL FIX: Increase default 1MB limit to 500MB for media files
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
            print("File saved to Downloads: \(fileName)")
            
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
        
        // Use a distinct Apple system sound (e.g., Glass, Tink, Pop, or Submarine)
        content.sound = UNNotificationSound(named: UNNotificationSoundName("Glass.aiff"))
        
        // Elevate interruption level so it bypasses silent delivery
        if #available(macOS 12.0, *) {
            content.interruptionLevel = .timeSensitive
        }
        
        content.categoryIdentifier = "FILE_TRANSFER"
        content.userInfo = ["filePath": fileURL.path]

        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Failed to show notification: \(error)")
            }
        }
    }

    private func handleNewConnection(_ connection: NWConnection) {
        connection.start(queue: .global(qos: .userInitiated))
        DispatchQueue.main.async {
            self.connections.append(connection)
            self.connectedClients = self.connections.count
        }
        receive(on: connection)
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
                        
                        if text.hasPrefix("START_FILE:") {
                            self.isReceivingFile = true
                            self.currentFileName = String(text.dropFirst("START_FILE:".count))
                            
                            // Create dedicated AirBridge folder
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
                            
                            if let fileURL = self.currentFileURL {
                                self.triggerTransferNotification(fileName: self.currentFileName, fileURL: fileURL)
                                
                                // Automatically copy the file to macOS clipboard
                                DispatchQueue.main.async {
                                    let pb = NSPasteboard.general
                                    pb.clearContents()
                                    pb.writeObjects([fileURL as NSURL])
                                    self.lastChangeCount = pb.changeCount
                                }
                            }
                        } else {
                            // Standard clipboard text sync
                            DispatchQueue.main.async {
                                let pb = NSPasteboard.general
                                pb.clearContents()
                                pb.setString(text, forType: .string)
                                self.lastChangeCount = pb.changeCount
                            }
                        }
                        
                    } else if metadata.opcode == .binary, let data = content {
                        if self.isReceivingFile {
                            // Append chunk to the currently open file
                            if #available(macOS 10.15.4, *) {
                                try? self.currentFileHandle?.seekToEnd()
                            } else {
                                self.currentFileHandle?.seekToEndOfFile()
                            }
                            self.currentFileHandle?.write(data)
                        } else {
                            // Fallback for standalone binary frames
                            self.handleIncomingFile(data: data)
                        }
                    }
                }
                self.receive(on: connection)
            }
        }
    
    private func removeConnection(_ connection: NWConnection) {
        connection.cancel()
        DispatchQueue.main.async {
            self.connections.removeAll(where: { $0 === connection })
            self.connectedClients = self.connections.count
        }
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
        guard !connections.isEmpty, let data = text.data(using: .utf8) else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "textContext", metadata: [metadata])
        
        for connection in connections {
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed({ _ in }))
        }
    }
}
