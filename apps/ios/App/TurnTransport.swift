import Foundation
import AVFoundation
import MuralCore

/// The conversation coordinator's view of a voice connection.
@MainActor protocol VoiceTransport: AnyObject {
    var onEvent: (([String: Any]) -> Void)? { get set }
    var onLevels: ((Double, Double) -> Void)? { get set }
    var onFailure: ((String) -> Void)? { get set }
    /// `respond` asks a turn-based engine to speak now; a realtime model decides that itself.
    func send(_ event: [String: Any], respond: Bool) -> Bool
    func mute(_ muted: Bool)
    func close()
    func disconnect()
}

extension LiveTransport: VoiceTransport {}

/// Half-duplex voice for OpenAI-compatible servers: listen, transcribe, reply, speak.
/// Emits the coordinator events LiveTransport emits. Mural can't be interrupted while it speaks.
@MainActor final class TurnTransport: VoiceTransport {
    var onEvent: (([String: Any]) -> Void)?
    var onLevels: ((Double, Double) -> Void)?
    var onFailure: ((String) -> Void)?
    /// A request failed. Fatal failures mean the endpoint settings need attention.
    var onError: ((Error, Bool) -> Void)?

    private enum Work { case heard(Data, start: Int, end: Int), reply, say(String) }
    private var engine: AVAudioEngine?
    private var capture: SpeechCapture?
    private var work: AsyncStream<Work>.Continuation?
    private var worker: Task<Void, Never>?
    private var player: AVAudioPlayer?
    private var guidance = TurnGuidance()
    private var startedAt = Date()
    private var ownsAudioActivation = false

    /// `language` is the learning language for transcription. `reply` receives app guidance for this turn and returns the words to speak.
    func connect(api: APIClient, language: String, reply: @escaping (String) async throws -> String) async throws {
        disconnect()
        guard await AVAudioApplication.requestRecordPermission() else { throw LiveTransport.TransportError.microphone }
        try Task.checkCancellation()
        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try audio.setActive(true)
        ownsAudioActivation = true

        let engine = AVAudioEngine()
        let input = engine.inputNode.outputFormat(forBus: 0)
        guard input.sampleRate > 0, let capture = SpeechCapture(input: input) else { disconnect(); throw LiveTransport.TransportError.microphone }
        let (stream, continuation) = AsyncStream.makeStream(of: Work.self)
        capture.onLevel = { [weak self] level in
            Task { @MainActor in if let self, self.player == nil { self.emitLevels(input: level) } } // Playback reports its own level.
        }
        capture.onUtterance = { [weak self] samples, trailingSilenceMS in
            let wav = Wav.encode(samples, sampleRate: SpeechCapture.sampleRate)
            let milliseconds = samples.count * 1000 / SpeechCapture.sampleRate
            Task { @MainActor in
                guard let self, self.work != nil else { return }
                let end = self.elapsedMS() - trailingSilenceMS
                continuation.yield(.heard(wav, start: max(0, end - milliseconds), end: max(0, end)))
            }
        }
        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: input) { buffer, _ in capture.consume(buffer) }
        do { try engine.start() } catch { disconnect(); throw LiveTransport.TransportError.microphone }
        self.engine = engine; self.capture = capture; work = continuation
        startedAt = Date()
        worker = Task { [weak self] in
            self?.onEvent?(["type": "session.started"])
            capture.hearing = true
            for await item in stream {
                guard let self, !Task.isCancelled else { return }
                await self.process(item, api: api, language: language, reply: reply)
            }
        }
    }

    func send(_ event: [String: Any], respond: Bool) -> Bool {
        guard let work, let content = event["content"] as? String else { return false }
        switch event["type"] as? String {
        case "session.commentary.append": work.yield(.say(content))
        case "session.instructions.append", "session.thinking.append":
            if guidance.add(content, respond: respond) { work.yield(.reply) }
        default: return false
        }
        return true
    }

    func mute(_ muted: Bool) { capture?.muted = muted }

    /// There is no server session to settle, so closing reports itself closed.
    func close() {
        guard work != nil else { return }
        capture?.hearing = false
        Task { @MainActor [weak self] in self?.onEvent?(["type": "session.closed"]) }
    }

    func disconnect() {
        worker?.cancel(); worker = nil
        work?.finish(); work = nil
        player?.stop(); player = nil
        capture?.hearing = false; capture = nil
        if let engine { engine.inputNode.removeTap(onBus: 0); engine.stop() }
        engine = nil
        guidance.clear()
        if ownsAudioActivation { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation); ownsAudioActivation = false }
        onLevels?(0, 0)
    }

    private func process(_ item: Work, api: APIClient, language: String, reply: (String) async throws -> String) async {
        if case .reply = item, !guidance.replyPending { return } // A heard turn already answered this request.
        capture?.hearing = false
        do {
            switch item {
            case .heard(let wav, let start, let end):
                let text = try await api.transcribe(wav: wav, language: language)
                guard !text.isEmpty, work != nil else { break }
                emitTranscript("session.input_transcript.delta", text, start: start, end: end)
                try await speak(try await respond(reply), api: api)
            case .reply: try await speak(try await respond(reply), api: api)
            case .say(let text): try await speak(text, api: api)
            }
        } catch is CancellationError { return }
        catch let error as URLError where error.code == .cancelled { return }
        catch { onError?(error, isFatal(error)) }
        // Lets the room's echo of Mural's voice fade before listening resumes.
        try? await Task.sleep(for: .milliseconds(300))
        guard work != nil else { return }
        capture?.hearing = true
    }

    private func respond(_ reply: (String) async throws -> String) async throws -> String {
        let used = guidance.begin()
        let text = try await reply(TurnGuidance.prompt(used))
        guidance.consumed(used)
        return text
    }

    private func speak(_ text: String, api: APIClient) async throws {
        let clean = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1_200))
        guard !clean.isEmpty else { return }
        let player = try AVAudioPlayer(data: try await api.speak(clean))
        try Task.checkCancellation()
        guard work != nil else { return }
        self.player = player
        player.isMeteringEnabled = true
        let start = elapsedMS()
        emitTranscript("session.output_transcript.delta", clean, start: start, end: start + Int(player.duration * 1000))
        guard player.play() else { throw APIClient.APIError.invalidResponse }
        while player.isPlaying {
            try await Task.sleep(for: .milliseconds(50))
            player.updateMeters()
            emitLevels(input: 0, output: min(1, pow(10, Double(player.averagePower(forChannel: 0)) / 20) * 2))
        }
        emitLevels(input: 0, output: 0)
        if self.player === player { self.player = nil }
    }

    private func emitTranscript(_ type: String, _ text: String, start: Int, end: Int) {
        onEvent?(["type": type, "event_id": UUID().uuidString, "delta": text, "start_ms": start, "end_ms": end])
    }
    private func emitLevels(input: Double, output: Double = 0) {
        guard work != nil else { return }
        onLevels?(input, output)
    }
    private func elapsedMS() -> Int { Int(Date().timeIntervalSince(startedAt) * 1000) }
    private func isFatal(_ error: Error) -> Bool {
        switch error as? APIClient.APIError {
        case .missingKey?: return true
        case .endpoint(let status)?: return [401, 403, 404].contains(status)
        default: return false
        }
    }
}

/// Converts microphone audio to 16 kHz mono and finds turns. Runs on the audio tap thread;
/// only the lock-guarded flags are touched from the main actor.
private final class SpeechCapture: @unchecked Sendable {
    static let sampleRate = 16_000
    private static let frameSamples = 320
    private let converter: AVAudioConverter
    private let output: AVAudioFormat
    private let lock = NSLock()
    private var _hearing = false, _muted = false
    var hearing: Bool { get { lock.withLock { _hearing } } set { lock.withLock { _hearing = newValue } } }
    var muted: Bool { get { lock.withLock { _muted } } set { lock.withLock { _muted = newValue } } }
    var onLevel: ((Double) -> Void)?
    /// A finished turn's samples and the trailing silence already trimmed from them.
    var onUtterance: (([Int16], Int) -> Void)?
    private var detector = SpeechDetector()
    private var pending: [Int16] = []
    private var preroll: [[Int16]] = []
    private var speech: [[Int16]] = []
    private var frames = 0

    init?(input: AVAudioFormat) {
        guard let output = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(Self.sampleRate), channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: input, to: output) else { return nil }
        self.output = output; self.converter = converter
    }

    func consume(_ buffer: AVAudioPCMBuffer) {
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * output.sampleRate / buffer.format.sampleRate) + 32
        guard let converted = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: capacity) else { return }
        var supplied = false
        var error: NSError?
        converter.convert(to: converted, error: &error) { _, status in
            if supplied { status.pointee = .noDataNow; return nil }
            supplied = true; status.pointee = .haveData; return buffer
        }
        guard error == nil, let channel = converted.int16ChannelData else { return }
        pending.append(contentsOf: UnsafeBufferPointer(start: channel[0], count: Int(converted.frameLength)))
        while pending.count >= Self.frameSamples {
            feed(Array(pending.prefix(Self.frameSamples)))
            pending.removeFirst(Self.frameSamples)
        }
    }

    private func feed(_ frame: [Int16]) {
        let level = Wav.level(frame)
        if !hearing || muted {
            detector.reset(); speech = []; preroll = []
        } else {
            switch detector.feed(level) {
            case .none:
                if detector.active { speech.append(frame) }
                else { preroll.append(frame); if preroll.count > 15 { preroll.removeFirst() } }
            case .start: speech = preroll + [frame]; preroll = []
            case .end:
                hearing = false
                speech.append(frame)
                let quiet = detector.silenceMS
                onUtterance?(speech.dropLast(quiet / 20).flatMap { $0 }, quiet)
                speech = []
            case .discard: speech = []
            }
        }
        frames += 1
        if frames % 5 == 0 { onLevel?(detector.active ? min(1, level * 8) : 0) }
    }
}
