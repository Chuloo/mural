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
    private var connectionGeneration = 0
    private var sessionStarted = false
    private var usageReported = false

    func connect(key: String, instructions: String, history: [[String: Any]]) async throws {
        disconnect()
        connectionGeneration &+= 1
        let generation = connectionGeneration
        closing = false; muted = false; connectedAt = .now; pendingHistory = history; sessionStarted = false; usageReported = false
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { throw LiveTransport.TransportError.microphone }
        try Task.checkCancellation()

        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try audio.setActive(true)
        ownsAudioActivation = true

        guard var components = URLComponents(string: "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent") else {
            throw LiveTransport.TransportError.connection
        }
        components.queryItems = [URLQueryItem(name: "key", value: key)]
        guard let url = components.url else { throw LiveTransport.TransportError.connection }
        let urlSession = URLSession(configuration: .ephemeral)
        session = urlSession
        let socket = urlSession.webSocketTask(with: url)
        self.socket = socket
        socket.resume()
        let historyTurns = Self.historyTurns(history)
        var setupBody: [String: Any] = [
            "model": "models/\(AIProvider.googleAIStudio.liveModel)",
            "generationConfig": [
                "responseModalities": ["AUDIO"],
                "speechConfig": ["voiceConfig": ["prebuiltVoiceConfig": ["voiceName": "Kore"]]]
            ],
            "systemInstruction": ["parts": [["text": instructions]]],
            "inputAudioTranscription": [:],
            "outputAudioTranscription": [:]
        ]
        if !historyTurns.isEmpty { setupBody["historyConfig"] = ["initialHistoryInClientContent": true] }
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
        return sendJSONImmediately(["realtimeInput": ["text": text]])
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
        receiveTask?.cancel(); receiveTask = nil
        socket?.cancel(with: .normalClosure, reason: nil); socket = nil
        session?.invalidateAndCancel(); session = nil
        stopAudio()
        onLevels?(0, 0)
    }

    private func startAudio(generation: Int) throws {
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
            Task { @MainActor [weak self] in self?.sendAudio(data, generation: generation) }
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

    private func sendAudio(_ data: Data, generation: Int) {
        guard !muted, !closing, self.connectionGeneration == generation, socket != nil else { return }
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
        socket.send(.string(text)) { [weak self] error in
            guard let self, error != nil else { return }
            Task { @MainActor in
                if !self.closing { self.onFailure?("The Google AI Studio voice connection couldn’t send an update.") }
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
                if self.socket === socket, self.connectionGeneration == generation, !closing { onFailure?("The Google AI Studio voice connection ended. Check your key and connection, then try again.") }
                return
            }
        }
    }

    private func handle(_ json: [String: Any], generation: Int) {
        guard self.connectionGeneration == generation, !closing else { return }
        if json["setupComplete"] != nil {
            guard !sessionStarted else { return }
            do {
                if audioEngine == nil { try startAudio(generation: generation) }
            } catch {
                onFailure?("The Google AI Studio microphone couldn’t start.")
                return
            }
            if !pendingHistory.isEmpty, let socket { sendHistory(pendingHistory, socket: socket, generation: generation) }
            sessionStarted = true
            let session: [String: Any] = ["id": "gemini-live", "model": AIProvider.googleAIStudio.liveModel]
            onEvent?(["type": "mural.session.created", "session": session])
            onEvent?(["type": "session.started", "session": session])
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
