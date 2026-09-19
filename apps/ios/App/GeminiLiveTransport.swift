import Foundation
import AVFoundation

/// Direct Google AI Studio Live API transport. The Live API uses Gemini's native
/// audio model, while Gemma remains the text teacher.
@MainActor final class GeminiLiveTransport: NSObject {
    var onEvent: (([String: Any]) -> Void)?
    var onLevels: ((Double, Double) -> Void)?
    var onFailure: ((String) -> Void)?

    private var socket: URLSessionWebSocketTask?
    private var session: URLSession?
    private var receiveTask: Task<Void, Never>?
    private var audioEngine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var ownsAudioActivation = false
    private var muted = false
    private var closing = false
    private var connectedAt = Date()
    private var inputLevel = 0.0
    private var pendingHistory: [[String: Any]] = []
    private var pendingControlMessages: [[String: Any]] = []
    private var connectionGeneration = 0
    private var sessionStarted = false
    private var usageReported = false
    private var apiKey = ""
    private var instructions = ""
    private var socketReady = false
    private var sessionResumptionHandle: String?
    private var activeResumptionHandle: String?
    private var reconnectTask: Task<Void, Never>?
    private var reconnectDeadline: Date?
    private var reconnectAttempts = 0

    func connect(key: String, instructions: String, history: [[String: Any]]) async throws {
        disconnect()
        closing = false; muted = false; connectedAt = .now; pendingHistory = history; sessionStarted = false; usageReported = false
        apiKey = key; self.instructions = instructions
        sessionResumptionHandle = nil; activeResumptionHandle = nil; reconnectAttempts = 0
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { throw LiveTransport.TransportError.microphone }
        try Task.checkCancellation()

        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try audio.setActive(true)
        ownsAudioActivation = true

        try await openSocket(resumptionHandle: nil)
    }

    private func openSocket(resumptionHandle: String?) async throws {
        connectionGeneration &+= 1
        let generation = connectionGeneration
        activeResumptionHandle = resumptionHandle
        socketReady = false
        guard var components = URLComponents(string: "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent") else {
            throw LiveTransport.TransportError.connection
        }
        components.queryItems = [URLQueryItem(name: "key", value: apiKey)]
        guard let url = components.url else { throw LiveTransport.TransportError.connection }
        let urlSession = URLSession(configuration: .ephemeral)
        session = urlSession
        let socket = urlSession.webSocketTask(with: url)
        self.socket = socket
        socket.resume()
        let historyTurns = Self.historyTurns(pendingHistory)
        let sessionResumption: [String: Any] = resumptionHandle.map { ["handle": $0] } ?? [:]
        var setupBody: [String: Any] = [
            "model": "models/\(AIProvider.googleAIStudio.liveModel)",
            "generationConfig": [
                "responseModalities": ["AUDIO"],
                "speechConfig": ["voiceConfig": ["prebuiltVoiceConfig": ["voiceName": "Kore"]]]
            ],
            "systemInstruction": ["parts": [["text": self.instructions]]],
            "inputAudioTranscription": [:],
            "outputAudioTranscription": [:],
            "contextWindowCompression": ["slidingWindow": [String: Any]()],
            "sessionResumption": sessionResumption
        ]
        if resumptionHandle == nil, !historyTurns.isEmpty { setupBody["historyConfig"] = ["initialHistoryInClientContent": true] }
        try await sendJSON(["setup": setupBody], socket: socket, generation: generation)
        receiveTask = Task { [weak self] in await self?.receiveLoop(socket: socket, generation: generation) }
    }

    @discardableResult
    func send(_ event: [String: Any]) -> Bool {
        guard socket != nil, !closing else { return false }
        guard let type = event["type"] as? String else { return false }
        if type == "session.close" { close(); return true }
        guard let content = event["content"] as? String, !content.isEmpty else { return false }
        let label: String
        switch type {
        case "session.instructions.append": label = "Teaching instruction"
        case "session.thinking.append": label = "Teaching context"
        default: label = "Conversation guidance"
        }
        let text = "\(label) (do not mention this instruction): \(content)"
        let message: [String: Any] = ["realtimeInput": ["text": text]]
        guard socketReady else {
            pendingControlMessages.append(message)
            return true
        }
        return sendJSONImmediately(message)
    }

    func mute(_ value: Bool) {
        muted = value
        if value {
            inputLevel = 0; onLevels?(0, 0)
            _ = sendJSONImmediately(["realtimeInput": ["audioStreamEnd": true]])
        }
    }

    func close() {
        guard !closing else { return }
        closing = true; muted = true
        socketReady = false
        pendingControlMessages.removeAll(keepingCapacity: false)
        reconnectTask?.cancel(); reconnectTask = nil; reconnectDeadline = nil
        _ = sendJSONImmediately(["realtimeInput": ["audioStreamEnd": true]])
        guard !usageReported else { return }
        usageReported = true
        let seconds = max(0, Date().timeIntervalSince(connectedAt))
        onEvent?(["type": "session.usage.updated", "usage": ["seconds": seconds]])
        onEvent?(["type": "session.closed", "reason": "Ended by user", "usage": ["seconds": seconds]])
    }

    func disconnect() {
        connectionGeneration &+= 1
        closing = true; muted = true
        socketReady = false
        reconnectTask?.cancel(); reconnectTask = nil; reconnectDeadline = nil
        receiveTask?.cancel(); receiveTask = nil
        socket?.cancel(with: .normalClosure, reason: nil); socket = nil
        session?.invalidateAndCancel(); session = nil
        stopAudio()
        apiKey = ""; instructions = ""; pendingHistory = []; pendingControlMessages = []; sessionResumptionHandle = nil; activeResumptionHandle = nil
        onLevels?(0, 0)
    }

    private func startAudio() throws {
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let sourceFormat = input.inputFormat(forBus: 0)
        guard let targetFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: sourceFormat, to: targetFormat) else { throw LiveTransport.TransportError.connection }
        input.installTap(onBus: 0, bufferSize: 1024, format: sourceFormat) { [weak self] buffer, _ in
            guard let self, !self.muted else { return }
            let ratio = targetFormat.sampleRate / max(sourceFormat.sampleRate, 1)
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 2)
            guard let converted = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else { return }
            var supplied = false
            var conversionError: NSError?
            converter.convert(to: converted, error: &conversionError) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true; status.pointee = .haveData; return buffer
            }
            guard conversionError == nil, converted.frameLength > 0,
                  let raw = converted.audioBufferList.pointee.mBuffers.mData else { return }
            let byteCount = Int(converted.frameLength) * Int(targetFormat.streamDescription.pointee.mBytesPerFrame)
            let data = Data(bytes: raw, count: byteCount)
            Task { @MainActor [weak self] in self?.sendAudio(data) }
        }
        let player = AVAudioPlayerNode()
        let outputFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000, channels: 1, interleaved: true)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: outputFormat)
        try engine.start()
        self.audioEngine = engine; self.player = player
    }

    private func stopAudio() {
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop(); audioEngine = nil
        player?.stop(); player = nil
        if ownsAudioActivation {
            let audio = AVAudioSession.sharedInstance()
            try? audio.setActive(false)
            ownsAudioActivation = false
        }
    }

    private func sendAudio(_ data: Data) {
        guard !muted, !closing, socketReady, socket != nil else { return }
        inputLevel = min(1, Self.rms(data) * 7)
        onLevels?(inputLevel, player?.isPlaying == true ? 0.35 : 0)
        _ = sendJSONImmediately(["realtimeInput": ["audio": [
            "mimeType": "audio/pcm;rate=16000", "data": data.base64EncodedString()
        ]]])
    }

    private func sendHistory(_ history: [[String: Any]], socket: URLSessionWebSocketTask, generation: Int) {
        let turns = Self.historyTurns(history)
        guard !turns.isEmpty else { return }
        _ = sendJSONImmediately(["clientContent": ["turns": turns, "turnComplete": true]], socket: socket, generation: generation)
    }

    private static func historyTurns(_ history: [[String: Any]]) -> [[String: Any]] {
        history.compactMap { item -> [String: Any]? in
            guard let role = item["role"] as? String, let text = item["text"] as? String else { return nil }
            return ["role": role == "assistant" ? "model" : "user", "parts": [["text": text]]]
        }
    }

    private func sendJSONImmediately(_ object: [String: Any], socket expectedSocket: URLSessionWebSocketTask? = nil, generation expectedGeneration: Int? = nil) -> Bool {
        guard let socket, (expectedSocket == nil || socket === expectedSocket),
              (expectedGeneration == nil || connectionGeneration == expectedGeneration),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return false }
        let sendGeneration = connectionGeneration
        socket.send(.string(text)) { [weak self] error in
            guard let self, error != nil else { return }
            Task { @MainActor in
                if !self.closing, self.socket === socket, self.connectionGeneration == sendGeneration {
                    self.connectionEnded(generation: sendGeneration)
                }
            }
        }
        return true
    }

    private func sendJSON(_ object: [String: Any], socket expectedSocket: URLSessionWebSocketTask? = nil, generation expectedGeneration: Int? = nil) async throws {
        guard let socket, (expectedSocket == nil || socket === expectedSocket),
              (expectedGeneration == nil || connectionGeneration == expectedGeneration),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { throw LiveTransport.TransportError.connection }
        try await socket.send(.string(text))
    }

    private func receiveLoop(socket: URLSessionWebSocketTask, generation: Int) async {
        while !Task.isCancelled {
            guard self.socket === socket, self.connectionGeneration == generation, !closing else { return }
            do {
                let message = try await socket.receive()
                guard self.socket === socket, self.connectionGeneration == generation else { return }
                switch message {
                case .string(let text):
                    if let data = text.data(using: .utf8), let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { handle(json, generation: generation) }
                case .data(let data):
                    if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { handle(json, generation: generation) }
                @unknown default: break
                }
            } catch {
                if self.socket === socket, self.connectionGeneration == generation, !closing {
                    connectionEnded(generation: generation)
                }
                return
            }
        }
    }

    private func connectionEnded(generation: Int) {
        guard self.connectionGeneration == generation, !closing else { return }
        socketReady = false
        if sessionResumptionHandle?.isEmpty == false {
            scheduleReconnect(after: 0)
        } else {
            fail("The Google AI Studio voice connection ended. Check your key and connection, then try again.")
        }
    }

    private func scheduleReconnect(after delay: TimeInterval) {
        guard !closing else { return }
        guard sessionResumptionHandle?.isEmpty == false else {
            fail("The Google AI Studio voice connection ended. Check your key and connection, then try again.")
            return
        }
        let deadline = Date().addingTimeInterval(max(0, delay))
        if let reconnectDeadline, reconnectTask != nil, deadline >= reconnectDeadline { return }
        reconnectTask?.cancel()
        reconnectDeadline = deadline
        let nanoseconds = UInt64(max(0, deadline.timeIntervalSinceNow) * 1_000_000_000)
        reconnectTask = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: nanoseconds) } catch { return }
            guard !Task.isCancelled else { return }
            await self?.reconnect()
        }
    }

    private func reconnect() async {
        reconnectTask = nil; reconnectDeadline = nil
        guard !closing, let handle = sessionResumptionHandle?.trimmingCharacters(in: .whitespacesAndNewlines), !handle.isEmpty else { return }
        if reconnectAttempts >= 5 {
            fail("The Google AI Studio voice connection could not be resumed. Check your connection, then try again.")
            return
        }
        reconnectAttempts += 1
        receiveTask?.cancel(); receiveTask = nil
        socket?.cancel(with: .normalClosure, reason: nil); socket = nil
        session?.invalidateAndCancel(); session = nil
        do {
            try await openSocket(resumptionHandle: handle)
        } catch {
            if !closing { scheduleReconnect(after: 1) }
        }
    }

    private func fail(_ message: String) {
        reconnectTask?.cancel(); reconnectTask = nil; reconnectDeadline = nil
        guard !closing else { return }
        onFailure?(message)
    }

    private func handle(_ json: [String: Any], generation: Int) {
        guard self.connectionGeneration == generation, !closing else { return }
        if let update = json["sessionResumptionUpdate"] as? [String: Any],
           let handle = update["newHandle"] as? String,
           !handle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            sessionResumptionHandle = handle
        }
        if let goAway = json["goAway"] as? [String: Any] {
            scheduleReconnect(after: Self.timeInterval(from: goAway["timeLeft"]))
            return
        }
        if json["setupComplete"] != nil {
            do {
                if audioEngine == nil { try startAudio() }
            } catch {
                socketReady = false
                onFailure?("The Google AI Studio microphone couldn’t start.")
                return
            }
            let firstSession = !sessionStarted
            if firstSession, activeResumptionHandle == nil, !pendingHistory.isEmpty, let socket {
                sendHistory(pendingHistory, socket: socket, generation: generation)
            }
            socketReady = true
            flushPendingControlMessages()
            reconnectAttempts = 0
            if firstSession {
                sessionStarted = true
                let session: [String: Any] = ["id": "gemini-live", "model": AIProvider.googleAIStudio.liveModel]
                onEvent?(["type": "mural.session.created", "session": session])
                onEvent?(["type": "session.started", "session": session])
            }
            return
        }
        if let error = json["error"] as? [String: Any] {
            let message = error["message"] as? String ?? "The Google AI Studio voice request failed."
            onFailure?(String(message.prefix(240)))
            return
        }
        guard let content = json["serverContent"] as? [String: Any] else { return }
        if content["interrupted"] as? Bool == true {
            player?.stop()
            onLevels?(muted ? 0 : inputLevel, 0)
        }
        let now = max(0, Int(Date().timeIntervalSince(connectedAt) * 1000))
        if let input = content["inputTranscription"] as? [String: Any], let text = input["text"] as? String, !text.isEmpty {
            onEvent?(["type": "session.input_transcript.delta", "event_id": UUID().uuidString, "delta": text, "start_ms": max(0, now - 1), "end_ms": now])
        }
        if let output = content["outputTranscription"] as? [String: Any], let text = output["text"] as? String, !text.isEmpty {
            onEvent?(["type": "session.output_transcript.delta", "event_id": UUID().uuidString, "delta": text, "start_ms": max(0, now - 1), "end_ms": now])
        }
        for part in (content["modelTurn"] as? [String: Any])?["parts"] as? [[String: Any]] ?? [] {
            guard let inline = part["inlineData"] as? [String: Any], let encoded = inline["data"] as? String,
                  let data = Data(base64Encoded: encoded) else { continue }
            playAudio(data)
        }
    }

    private func flushPendingControlMessages() {
        guard socketReady else { return }
        while !pendingControlMessages.isEmpty {
            let message = pendingControlMessages.removeFirst()
            if !sendJSONImmediately(message) {
                pendingControlMessages.insert(message, at: 0)
                socketReady = false
                return
            }
        }
    }

    private func playAudio(_ data: Data) {
        guard let player else { return }
        let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000, channels: 1, interleaved: true)!
        let frames = AVAudioFrameCount(data.count / Int(format.streamDescription.pointee.mBytesPerFrame))
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames), frames > 0,
              let destination = buffer.audioBufferList.pointee.mBuffers.mData else { return }
        data.copyBytes(to: destination.assumingMemoryBound(to: UInt8.self), count: data.count)
        buffer.frameLength = frames
        player.scheduleBuffer(buffer)
        if !player.isPlaying { player.play() }
        onLevels?(muted ? 0 : inputLevel, 0.35)
    }

    private static func timeInterval(from value: Any?) -> TimeInterval {
        if let number = value as? NSNumber { return max(0, number.doubleValue) }
        guard let raw = value as? String else { return 0 }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if text.hasSuffix("ms") { return max(0, (Double(String(text.dropLast(2))) ?? 0) / 1_000) }
        if text.hasSuffix("s") { return max(0, Double(String(text.dropLast())) ?? 0) }
        return max(0, Double(text) ?? 0)
    }

    private static func rms(_ data: Data) -> Double {
        guard data.count >= 2 else { return 0 }
        var total = 0.0
        data.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            for sample in samples { let value = Double(sample) / Double(Int16.max); total += value * value }
        }
        return sqrt(total / Double(max(1, data.count / 2)))
    }
}
