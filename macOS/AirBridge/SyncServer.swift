import Foundation
import Network
import AppKit
import Combine
import UserNotifications

struct ActiveConnection: Identifiable {
    let id: String
    let name: String
    let connection: NWConnection
}

class SyncServer: ObservableObject {
    @Published var localIP: String = "127.0.0.1"
    @Published var connectedClients: Int = 0
    @Published var port: UInt16 = 0
    @Published var macName: String = Host.current().localizedName ?? "Mac"
    
    private var listener: NWListener?
    private var browser: NWBrowser?
    
    @Published var activeConnectionsInfo: [ActiveConnection] = []
    @Published var discoveredHosts: [NWBrowser.Result] = []
    @Published var pendingTransferURLs: [URL]? = nil
    
    private var connectionDict: [String: NWConnection] = [:]
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
    private var batchReceivedURLs: [URL] = []
    
    @Published var isSendingBatch = false
    @Published var sendProgress: Double = 0.0
    @Published var sendStatus: String = ""
    
    init() {
        self.localIP = NetworkUtils.getLocalIPAddress() ?? "127.0.0.1"
        startServer()
        startBrowser()
        startClipboardMonitor()
        
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), nil, darwinNotificationCallback, "com.shazan.airbridge.filesReady" as CFString, nil, .deliverImmediately)
        
        NotificationCenter.default.addObserver(forName: Notification.Name("AirBridgeFilesReady"), object: nil, queue: .main) { [weak self] _ in
            self?.processPendingTransfers()
        }
    }
    
    private func startServer() {
        listener?.cancel()
        
        let parameters = NWParameters.tcp
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true
        wsOptions.maximumMessageSize = 1024 * 1024 * 500
        
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)
        
        do {
            listener = try NWListener(using: parameters)
            listener?.service = NWListener.Service(name: self.macName, type: "_airbridge._tcp", domain: "local.")
            
            listener?.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            listener?.stateUpdateHandler = { [weak self] state in
                if case .ready = state, let actualPort = self?.listener?.port?.rawValue {
                    DispatchQueue.main.async { self?.port = actualPort }
                }
            }
            listener?.start(queue: .global(qos: .userInitiated))
        } catch {
            print("Failed to start server: \(error)")
        }
    }
    
    private func startBrowser() {
        let parameters = NWParameters.tcp
        browser = NWBrowser(for: .bonjour(type: "_airbridge._tcp", domain: "local."), using: parameters)
        
        browser?.browseResultsChangedHandler = { [weak self] results, changes in
            guard let self = self else { return }
            DispatchQueue.main.async {
                self.discoveredHosts = Array(results).filter { result in
                    if case let .service(name, _, _, _) = result.endpoint {
                        return name != self.macName
                    }
                    return true
                }
            }
        }
        browser?.start(queue: .global(qos: .userInitiated))
    }

    private func handleNewConnection(_ connection: NWConnection) {
        guard case .hostPort(let host, _) = connection.endpoint else {
            connection.cancel()
            return
        }
        let clientId = host.debugDescription
        
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let conn = connection else { return }
            switch state {
            case .failed(_), .cancelled:
                self?.removeConnection(id: clientId, connection: conn)
            default:
                break
            }
        }
        
        connectionsLock.lock()
        if let existing = connectionDict[clientId] {
            existing.cancel()
        }
        connectionDict[clientId] = connection
        connectionsLock.unlock()
        
        connection.start(queue: .global(qos: .userInitiated))
        updateConnectionUI()
        receive(on: connection, id: clientId)
    }
    
    private func handleIncomingFile(data: Data) {
        let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        
        let targetDir = downloadsURL.appendingPathComponent("AirBridge Transfers")
        try? FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true, attributes: nil)
        
        let fileName = "AirBridge_Transfer_\(Int(Date().timeIntervalSince1970)).dat"
        let fileURL = targetDir.appendingPathComponent(fileName)
        
        do {
            try data.write(to: fileURL)
            DispatchQueue.main.async {
                let pb = NSPasteboard.general
                pb.clearContents()
                pb.writeObjects([fileURL as NSURL])
                self.lastChangeCount = pb.changeCount
            }
            triggerTransferNotification(fileName: fileName, fileURL: fileURL)
            
        } catch {
            print("Failed to save file: \(error)")
        }
    }

    private func handleIncomingData(_ data: Data, from connection: NWConnection) {
        if let text = String(data: data, encoding: .utf8) {
            if text.hasPrefix("INP:") {
                return
            }
            
            let parts = text.components(separatedBy: ":")
            
            if parts[0] == "BATCH_START" && parts.count >= 3 {
                DispatchQueue.main.async {
                    self.isReceivingBatch = true
                    self.totalIncomingFiles = Int(parts[1]) ?? 0
                    self.totalBatchBytes = Int(parts[2]) ?? 0
                    self.receivedFilesCount = 0
                    self.receivedBatchBytes = 0
                    self.batchProgress = 0.0
                    self.batchStartTime = Date()
                    self.batchReceivedURLs.removeAll()
                    self.updateProgressNotification(total: self.totalIncomingFiles)
                }
                return
            }
            
            if parts[0] == "START_FILE" && parts.count >= 2 {
                self.currentFileName = parts.dropFirst().joined(separator: ":")
                let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
                
                let targetDir = downloadsURL.appendingPathComponent("AirBridge Transfers")
                try? FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true, attributes: nil)
                
                let fileURL = targetDir.appendingPathComponent(self.currentFileName)
                self.currentFileURL = fileURL
                
                FileManager.default.createFile(atPath: fileURL.path, contents: nil, attributes: nil)
                self.currentFileHandle = try? FileHandle(forWritingTo: fileURL)
                self.isReceivingFile = true
                
                DispatchQueue.main.async {
                    self.batchStatus = "Receiving: \(self.currentFileName)"
                }
                return
            }
            
            if parts[0] == "END_FILE" {
                self.currentFileHandle?.closeFile()
                self.currentFileHandle = nil
                self.isReceivingFile = false
                
                if let url = self.currentFileURL {
                    self.batchReceivedURLs.append(url)
                }
                
                DispatchQueue.main.async {
                    self.receivedFilesCount += 1
                    if self.receivedFilesCount >= self.totalIncomingFiles {
                        self.isReceivingBatch = false
                        
                        let pb = NSPasteboard.general
                        pb.clearContents()
                        pb.writeObjects(self.batchReceivedURLs as [NSURL])
                        self.lastChangeCount = pb.changeCount
                        
                        self.triggerTransferNotification(fileName: "Batch of \(self.receivedFilesCount) files", fileURL: self.currentFileURL ?? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0])
                        self.batchStatus = "Complete!"
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.batchStatus = "" }
                    }
                }
                return
            }
            
            DispatchQueue.main.async {
                let pb = NSPasteboard.general
                pb.clearContents()
                pb.setString(text, forType: .string)
                self.lastChangeCount = pb.changeCount
            }
            return
        }
        
        if isReceivingFile {
            if #available(macOS 10.15.4, *) {
                let _ = try? currentFileHandle?.seekToEnd()
            } else {
                currentFileHandle?.seekToEndOfFile()
            }
            currentFileHandle?.write(data)
            
            DispatchQueue.main.async {
                self.receivedBatchBytes += data.count
                if self.totalBatchBytes > 0 {
                    self.batchProgress = Double(self.receivedBatchBytes) / Double(self.totalBatchBytes)
                }
                
                if let startTime = self.batchStartTime {
                    let elapsed = Date().timeIntervalSince(startTime)
                    if elapsed > 0.5 {
                        let speed = Double(self.receivedBatchBytes) / elapsed
                        self.batchSpeed = String(format: "%.1f MB/s", speed / (1024 * 1024))
                        
                        let remainingBytes = Double(self.totalBatchBytes - self.receivedBatchBytes)
                        let etaSeconds = speed > 0 ? remainingBytes / speed : 0
                        self.batchETA = self.formatETA(seconds: etaSeconds)
                    }
                }
            }
        } else {
            handleIncomingFile(data: data)
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

    private func removeConnection(id: String, connection: NWConnection) {
        connectionsLock.lock()
        if let current = connectionDict[id], current === connection {
            connectionDict.removeValue(forKey: id)
            current.cancel()
        } else {
            connection.cancel()
        }
        connectionsLock.unlock()
        
        updateConnectionUI()
        
        DispatchQueue.main.async {
            if self.connectedClients == 0 {
                InputInterceptor.shared.forceReturnToMac()
            }
        }
    }

    func disconnect(id: String) {
        connectionsLock.lock()
        if let conn = connectionDict[id] {
            connectionDict.removeValue(forKey: id)
            conn.cancel()
        }
        connectionsLock.unlock()
        updateConnectionUI()
    }
    
    func stopServer() {
        listener?.cancel()
        listener = nil
        browser?.cancel()
        browser = nil
        
        connectionsLock.lock()
        for (_, conn) in connectionDict {
            conn.cancel()
        }
        connectionDict.removeAll()
        connectionsLock.unlock()
    }

    private func updateConnectionUI() {
        connectionsLock.lock()
        let currentConns = connectionDict
        connectionsLock.unlock()
        
        DispatchQueue.main.async {
            self.activeConnectionsInfo = currentConns.map {
                ActiveConnection(id: $0.key, name: "Device (\($0.key.prefix(8)))", connection: $0.value)
            }
            self.connectedClients = currentConns.count
        }
    }
    
    private func formatETA(seconds: Double) -> String {
        if seconds < 60 { return "\(Int(seconds))s remaining" }
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return "\(mins)m \(secs)s remaining"
    }
    
    private func startClipboardMonitor() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let pb = NSPasteboard.general
            if pb.changeCount != self.lastChangeCount {
                self.lastChangeCount = pb.changeCount
                if let newString = pb.string(forType: .string) {
                    if self.shouldBroadcast(text: newString) {
                        self.broadcast(text: newString)
                    }
                }
            }
        }
    }
    
    private func shouldBroadcast(text: String) -> Bool {
        if let data = UserDefaults.standard.data(forKey: "ClipboardFilters"),
           let filters = try? JSONDecoder().decode([ClipboardFilter].self, from: data) {
            for filter in filters {
                switch filter.type {
                case .startsWith:
                    if text.hasPrefix(filter.keyword) { return false }
                case .endsWith:
                    if text.hasSuffix(filter.keyword) { return false }
                case .contains:
                    if text.contains(filter.keyword) { return false }
                }
            }
        }
        return true
    }
    
    private func broadcast(text: String) {
        guard let data = text.data(using: .utf8) else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "textContext", metadata: [metadata])
        
        connectionsLock.lock()
        let activeConnections = Array(connectionDict.values)
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

    private func receive(on connection: NWConnection, id: String) {
        connection.receiveMessage { [weak self] (content, context, isComplete, error) in
            if let _ = error {
                self?.removeConnection(id: id, connection: connection)
                return
            }
            
            if let data = content, !data.isEmpty {
                self?.handleIncomingData(data, from: connection)
            }
            
            self?.connectionsLock.lock()
            let current = self?.connectionDict[id]
            let isActive = (current === connection)
            self?.connectionsLock.unlock()
            
            if isActive {
                self?.receive(on: connection, id: id)
            }
        }
    }
    
    func broadcastInput(command: String) {
        guard let data = command.data(using: .utf8) else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "inputContext", metadata: [metadata])
        
        connectionsLock.lock()
        let activeConnections = Array(connectionDict.values)
        connectionsLock.unlock()
        
        for connection in activeConnections {
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed({ _ in }))
        }
    }
    
    func connectToOutbound(endpoint: NWEndpoint, name: String) {
        let parameters = NWParameters.tcp
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        let connection = NWConnection(to: endpoint, using: parameters)

        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .ready:
                let newId = UUID().uuidString
                let activeConn = ActiveConnection(id: newId, name: name, connection: connection)
                
                self.connectionsLock.lock()
                self.connectionDict[newId] = connection
                self.connectionsLock.unlock()

                DispatchQueue.main.async {
                    self.activeConnectionsInfo.append(activeConn)
                }
                self.receive(on: connection, id: newId)

            case .failed(let error):
                print("Outbound connection failed: \(error)")
                self.disconnect(id: name)
            case .cancelled:
                self.disconnect(id: name)
            default:
                break
            }
        }
        connection.start(queue: .global(qos: .userInitiated))
    }

    func sendCommand(_ command: String, to targetId: String) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "textContext", metadata: [metadata])
        if let connection = connectionDict[targetId], let data = command.data(using: .utf8) {
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed({ _ in }))
        }
    }

    func sendBinary(data: Data, to targetId: String) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binaryContext", metadata: [metadata])
        if let connection = connectionDict[targetId] {
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed({ _ in }))
        }
    }

    func sendFilesToTarget(urls: [URL], to targetId: String) {
        DispatchQueue.global(qos: .userInitiated).async {
            let totalFiles = urls.count
            var totalBytes = 0
            
            for url in urls {
                if let attr = try? FileManager.default.attributesOfItem(atPath: url.path),
                   let size = attr[.size] as? Int { totalBytes += size }
            }

            self.sendCommand("BATCH_START:\(totalFiles):\(totalBytes)", to: targetId)

            for url in urls {
                let fileName = url.lastPathComponent
                self.sendCommand("START_FILE:\(fileName)", to: targetId)

                if let stream = InputStream(url: url) {
                    stream.open()
                    let bufferSize = 256 * 1024
                    let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)

                    while stream.hasBytesAvailable {
                        let read = stream.read(buffer, maxLength: bufferSize)
                        if read > 0 {
                            let data = Data(bytes: buffer, count: read)
                            self.sendBinary(data: data, to: targetId)
                            Thread.sleep(forTimeInterval: 0.005)
                        } else { break }
                    }
                    buffer.deallocate()
                    stream.close()
                }
                self.sendCommand("END_FILE:\(fileName)", to: targetId)
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    func processPendingTransfers() {
            let userHome = FileManager.default.homeDirectoryForCurrentUser
            let containersURL = userHome.appendingPathComponent("Library/Containers")
            
            var validFilesToSend: [URL] = []
            var seenFileNames = Set<String>()
            
            if let enumerator = FileManager.default.enumerator(at: containersURL, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsSubdirectoryDescendants, .skipsHiddenFiles]) {
                for case let fileURL as URL in enumerator {
                    if fileURL.lastPathComponent.localizedCaseInsensitiveContains("AirBridge") {
                        let pendingDir = fileURL.appendingPathComponent("Data/Documents/AirBridgePending")
                        
                        if let deepEnumerator = FileManager.default.enumerator(at: pendingDir, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) {
                            for case let deepURL as URL in deepEnumerator {
                                if let resourceValues = try? deepURL.resourceValues(forKeys: [.isDirectoryKey]),
                                   let isDirectory = resourceValues.isDirectory, !isDirectory {
                                    
                                    let name = deepURL.lastPathComponent
                                    if !seenFileNames.contains(name) {
                                        validFilesToSend.append(deepURL)
                                        seenFileNames.insert(name)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
        DispatchQueue.main.async {
                    self.pendingTransferURLs = validFilesToSend.isEmpty ? nil : validFilesToSend
                    
                    // FIX: Use the shared static instance instead of the failing cast
                    AppDelegate.shared?.showTargetSelection(server: self)
                    
                    // Log an error in the menu bar if it opened empty
                    if validFilesToSend.isEmpty {
                        self.sendStatus = "Debug: Woke up, but no files found."
                        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { self.sendStatus = "" }
                    }
                }
            }

    func sendFiles(urls: [URL], fromSharedDir: Bool = false) {
        guard connectedClients > 0 else { return }
        
        DispatchQueue.global(qos: .userInitiated).async {
            let totalFiles = urls.count
            var totalBytes: Int = 0
            for url in urls {
                if let attr = try? FileManager.default.attributesOfItem(atPath: url.path), let size = attr[.size] as? Int {
                    totalBytes += size
                }
            }
            
            DispatchQueue.main.async {
                self.isSendingBatch = true
                self.sendProgress = 0.0
                self.sendStatus = "Sending \(totalFiles) file(s)..."
            }
            
            self.broadcastInput(command: "BATCH_START:\(totalFiles):\(totalBytes)")
            var sentBytes: Int = 0
            let startTime = Date()
            var lastUpdate = Date()
            
            for url in urls {
                let fileName = url.lastPathComponent
                self.broadcastInput(command: "START_FILE:\(fileName)")
                if let stream = InputStream(url: url) {
                    stream.open()
                    let bufferSize = 256 * 1024
                    let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
                    while stream.hasBytesAvailable {
                        let read = stream.read(buffer, maxLength: bufferSize)
                        if read > 0 {
                            let data = Data(bytes: buffer, count: read)
                            self.broadcastBinary(data: data)
                            sentBytes += read
                            let now = Date()
                            if now.timeIntervalSince(lastUpdate) > 0.5 {
                                lastUpdate = now
                                let speed = Double(sentBytes) / now.timeIntervalSince(startTime)
                                let speedStr = String(format: "%.1f MB/s", speed / (1024 * 1024))
                                DispatchQueue.main.async {
                                    self.sendProgress = Double(sentBytes) / Double(totalBytes)
                                    self.sendStatus = "\(speedStr)"
                                }
                            }
                        } else { break }
                        Thread.sleep(forTimeInterval: 0.005)
                    }
                    buffer.deallocate()
                    stream.close()
                }
                self.broadcastInput(command: "END_FILE:\(fileName)")
                if fromSharedDir { try? FileManager.default.removeItem(at: url) }
            }
            DispatchQueue.main.async {
                self.isSendingBatch = false
                self.sendStatus = "Sent Successfully!"
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.sendStatus = "" }
            }
        }
    }
    
    private func broadcastBinary(data: Data) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binaryContext", metadata: [metadata])
        connectionsLock.lock()
        let activeConnections = Array(connectionDict.values)
        connectionsLock.unlock()
        for connection in activeConnections {
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed({ _ in }))
        }
    }
}

func darwinNotificationCallback(center: CFNotificationCenter?, observer: UnsafeMutableRawPointer?, name: CFNotificationName?, object: UnsafeRawPointer?, userInfo: CFDictionary?) {
    NotificationCenter.default.post(name: Notification.Name("AirBridgeFilesReady"), object: nil)
}
