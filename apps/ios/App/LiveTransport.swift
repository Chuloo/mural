import Foundation
import AVFoundation
import os
import MuralCore
@preconcurrency import WebRTC

enum ConnectionState: Equatable { case idle, connecting, active, closing, ended, failed }

@MainActor final class LiveTransport: NSObject {
    var onEvent: (([String: Any]) -> Void)?
    var onLevels: ((Double, Double) -> Void)?
    var onFailure: ((String) -> Void)?
    private var factory: RTCPeerConnectionFactory?
    private var peer: RTCPeerConnection?
    private var channel: RTCDataChannel?
    private var localTrack: RTCAudioTrack?
    private var meterTask: Task<Void, Never>?
    private var attempt = UUID()
    private(set) var started = false
    private(set) var isMuted = false
    private var localPlaybackActive = false
    private var closing = false
    private var ownsAudioActivation = false
    private var lastInput = 0.0, lastOutput = 0.0
    private var subscription: SubscriptionConnection?
    private var subscriptionAPI: APIClient?
    private var remoteSessionID: String?
    private var pollTask: Task<Void, Never>?
    private var commandTask: Task<Void, Never>?
    private var transcript = SubscriptionTranscript()
    private var connectedUptime: TimeInterval?
    private let stageLogger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Mural", category: "LiveTransport")
    private var connectUptime: TimeInterval?
    private var loggedStages = Set<String>()
    private var loggedTurnEvents = Set<String>()
    var recordedSubscriptionSeconds: Double? { subscription != nil && started ? subscriptionSeconds : nil }

    private func logStage(_ name: String, detail: String? = nil, token: UUID? = nil) {
        if let token, attempt != token { return }
        guard loggedStages.insert(name).inserted else { return }
        let elapsed = connectUptime.map { max(0, (ProcessInfo.processInfo.systemUptime - $0) * 1000) } ?? 0
        if let detail {
            stageLogger.info("live_stage=\(name, privacy: .public) elapsed_ms=\(elapsed, format: .fixed(precision: 0), privacy: .public) \(detail, privacy: .public)")
        } else {
            stageLogger.info("live_stage=\(name, privacy: .public) elapsed_ms=\(elapsed, format: .fixed(precision: 0), privacy: .public)")
        }
    }

    private func logTurnEvent(_ event: [String: Any], token: UUID? = nil) {
        if let token, attempt != token { return }
        guard let type = event["type"] as? String,
              ["turn.created", "turn.delta", "turn.done"].contains(type) else { return }
        let rawRole = (event["turn"] as? [String: Any])?["role"] as? String
            ?? event["role"] as? String ?? "unknown"
        let role = ["user", "assistant"].contains(rawRole) ? rawRole : "unknown"
        let key = type + "|" + role
        guard loggedTurnEvents.insert(key).inserted else { return }
        let elapsed = connectUptime.map { max(0, (ProcessInfo.processInfo.systemUptime - $0) * 1000) } ?? 0
        stageLogger.info("live_turn_event=\(type, privacy: .public) role=\(role, privacy: .public) elapsed_ms=\(elapsed, format: .fixed(precision: 0), privacy: .public)")
    }

    func connect(api: APIClient, instructions: String, history: [[String: Any]]) async throws {
        disconnect()
        closing = false
        let token = UUID(); attempt = token
        connectUptime = ProcessInfo.processInfo.systemUptime
        loggedStages.removeAll(); loggedTurnEvents.removeAll()
        logStage("connect_start", token: token)
        let connection = try SubscriptionStore.connectionForRequest()
        subscription = connection; subscriptionAPI = api
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { throw TransportError.microphone }
        logStage("microphone_granted", token: token)
        try Task.checkCancellation()
        guard attempt == token else { throw CancellationError() }
        // WebRTC reapplies this configuration when its audio unit starts.
        // Setting AVAudioSession alone loses the speaker preference at that point.
        let audioConfiguration = RTCAudioSessionConfiguration()
        audioConfiguration.category = AVAudioSession.Category.playAndRecord.rawValue
        audioConfiguration.mode = AVAudioSession.Mode.voiceChat.rawValue
        audioConfiguration.categoryOptions = [.defaultToSpeaker, .allowBluetoothHFP]
        RTCAudioSessionConfiguration.setWebRTC(audioConfiguration)
        let audio = RTCAudioSession.sharedInstance()
        audio.lockForConfiguration()
        do {
            try audio.setCategory(.playAndRecord, mode: .voiceChat, options: audioConfiguration.categoryOptions)
            try audio.setActive(true)
            ownsAudioActivation = true
            logStage("audio_ready", token: token)
            audio.unlockForConfiguration()
        } catch { audio.unlockForConfiguration(); throw error }
        RTCInitializeSSL()
        let factory = RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
        self.factory = factory
        let config = RTCConfiguration(); config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherOnce
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: ["DtlsSrtpKeyAgreement": "true"])
        guard let peer = factory.peerConnection(with: config, constraints: constraints, delegate: self) else { throw TransportError.connection }
        self.peer = peer
        let source = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: ["googEchoCancellation": "true", "googNoiseSuppression": "true", "googAutoGainControl": "true"]))
        let track = factory.audioTrack(with: source, trackId: "mural-microphone")
        localTrack = track; isMuted = false
        peer.add(track, streamIds: ["mural-audio"])
        let dataConfig = RTCDataChannelConfiguration(); dataConfig.isOrdered = true
        guard let channel = peer.dataChannel(forLabel: "oai-events", configuration: dataConfig) else { throw TransportError.connection }
        self.channel = channel; channel.delegate = self
        let offer: RTCSessionDescription = try await withCheckedThrowingContinuation { c in
            peer.offer(for: RTCMediaConstraints(mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "false"], optionalConstraints: nil)) { sdp, error in
                if let error { c.resume(throwing: error) } else if let sdp { c.resume(returning: sdp) } else { c.resume(throwing: TransportError.connection) }
            }
        }
        logStage("offer_ready", token: token)
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            peer.setLocalDescription(offer) { error in if let error { c.resume(throwing: error) } else { c.resume() } }
        }
        logStage("local_sdp_set", token: token)
        let deadline = Date().addingTimeInterval(10)
        while peer.iceGatheringState != .complete {
            try await Task.sleep(for: .milliseconds(100))
            guard attempt == token else { throw CancellationError() }
            guard Date() < deadline else { throw TransportError.timeout }
        }
        guard let sdp = peer.localDescription?.sdp else { throw TransportError.connection }
        try Task.checkCancellation()
        guard attempt == token else { throw CancellationError() }
        let candidateCount = sdp.components(separatedBy: "a=candidate:").count - 1
        logStage("signaling_offer_ready", detail: "candidate_count=\(max(0, candidateCount))", token: token)
        let outputVoice = connection?.voice ?? "marin"
        let body: [String: Any] = [
            "session": ["model": "gpt-live-1", "instructions": instructions, "input": history,
                        "store": false, "delegation": ["type": "client"], "audio": ["output": ["voice": outputVoice]]],
            "transport": ["type": "webrtc", "sdp": sdp]
        ]
        let result: [String: Any]
        logStage("live_http_start", token: token)
        do {
            if let connection { result = try await api.subscriptionRequest(connection, path: "live/sessions", method: "POST", body: body) }
            else { result = try await api.post("live/sessions", body: body) }
        } catch {
            logStage("live_http_return_error", token: token)
            throw error
        }
        logStage("live_http_return", token: token)
        if let connection, let session = result["session"] as? [String: Any], let id = session["id"] as? String, UUID(uuidString: id) != nil {
            guard attempt == token else {
                Task { _ = try? await api.subscriptionRequest(connection, path: "live/sessions/" + id, method: "DELETE") }
                throw CancellationError()
            }
            remoteSessionID = id
        }
        guard attempt == token else { throw CancellationError() }
        if connection != nil && remoteSessionID == nil { throw TransportError.connection }
        guard let transport = result["transport"] as? [String: Any], let answer = transport["sdp"] as? String else { throw TransportError.connection }
        if let session = result["session"] as? [String: Any] { onEvent?(["type": "mural.session.created", "session": session]) }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            peer.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: answer)) { error in
                if let error { c.resume(throwing: error) } else { c.resume() }
            }
        }
        logStage("remote_sdp_set", token: token)
        subscriptionReady()
        let readyDeadline = Date().addingTimeInterval(20)
        while !started {
            try await Task.sleep(for: .milliseconds(100))
            guard attempt == token else { throw CancellationError() }
            guard Date() < readyDeadline else { throw TransportError.timeout }
        }
        startMetering()
    }

    @discardableResult func send(_ event: [String: Any]) -> Bool {
        if let subscription {
            guard started, !closing, let api = subscriptionAPI, let id = remoteSessionID,
                  let type = event["type"] as? String,
                  ["session.instructions.append", "session.thinking.append", "session.commentary.append"].contains(type) else { return false }
            let token = attempt, previous = commandTask
            commandTask = Task { [weak self] in
                await previous?.value
                guard let self, !Task.isCancelled, self.attempt == token, !self.closing else { return }
                do {
                    let result = try await api.subscriptionRequest(subscription, path: "live/sessions/" + id + "/events", method: "POST", body: event)
                    guard self.attempt == token, !self.closing else { return }
                    if let ack = result["event"] as? [String: Any] { self.onEvent?(ack) }
                } catch is CancellationError { }
                catch { if self.attempt == token && !self.closing { self.onFailure?("Your subscription connection could not send a conversation update. Please reconnect.") } }
            }
            return true
        }
        guard let channel, channel.readyState == .open, let data = try? JSONSerialization.data(withJSONObject: event) else { return false }
        return channel.sendData(RTCDataBuffer(data: data, isBinary: false))
    }
    func mute(_ muted: Bool) {
        isMuted = muted; localTrack?.isEnabled = !muted && !localPlaybackActive && !closing
        if subscription == nil { _ = send(["type": muted ? "session.input_audio.mute" : "session.input_audio.unmute", "event_id": UUID().uuidString]) }
    }
    /// Keep the same call while preventing a local recording from feeding back
    /// into the teacher. The user's mute preference is never changed here.
    func setLocalPlaybackActive(_ active: Bool) {
        localPlaybackActive = active
        localTrack?.isEnabled = !isMuted && !active && !closing
        for receiver in peer?.receivers ?? [] {
            if let audio = receiver.track as? RTCAudioTrack { audio.isEnabled = !active && !closing }
        }
    }
    func close() {
        closing = true; localTrack?.isEnabled = false; isMuted = true
        if let subscription, let api = subscriptionAPI, let id = remoteSessionID {
            let token = attempt
            commandTask?.cancel(); pollTask?.cancel()
            Task { [weak self] in
                do {
                    _ = try await api.subscriptionRequest(subscription, path: "live/sessions/" + id, method: "DELETE")
                    guard let self, self.attempt == token else { return }
                    self.remoteSessionID = nil
                    self.onEvent?(["type": "session.closed", "reason": "Conversation ended", "usage": ["seconds": self.subscriptionSeconds]])
                } catch {
                    // The coordinator's existing close deadline always releases the phone's media.
                }
            }
        } else { _ = send(["type": "session.close", "event_id": UUID().uuidString]) }
    }
    func disconnect() {
        if let subscription, let api = subscriptionAPI, let id = remoteSessionID {
            Task { _ = try? await api.subscriptionRequest(subscription, path: "live/sessions/" + id, method: "DELETE") }
        }
        remoteSessionID = nil; subscription = nil; subscriptionAPI = nil
        pollTask?.cancel(); pollTask = nil; commandTask?.cancel(); commandTask = nil
        transcript = SubscriptionTranscript(); connectedUptime = nil
        attempt = UUID(); meterTask?.cancel(); meterTask = nil
        started = false; closing = true; localPlaybackActive = false
        localTrack?.isEnabled = false; localTrack = nil
        channel?.delegate = nil; channel?.close(); channel = nil
        peer?.delegate = nil; peer?.close(); peer = nil; factory = nil
        if ownsAudioActivation {
            let audio = RTCAudioSession.sharedInstance(); audio.lockForConfiguration()
            try? audio.setActive(false); audio.unlockForConfiguration()
            ownsAudioActivation = false
        }
        lastInput = 0; lastOutput = 0; onLevels?(0, 0)
    }
    private var subscriptionSeconds: Double { connectedUptime.map { max(0, ProcessInfo.processInfo.systemUptime - $0) } ?? 0 }
    private func subscriptionReady() {
        let token = attempt
        if let peer, channel?.readyState == .open,
           peer.iceConnectionState == .connected || peer.iceConnectionState == .completed {
            logStage("datachannel_ice_ready", token: token)
        }
        guard subscription != nil, !started, !closing, let peer, let id = remoteSessionID,
              channel?.readyState == .open, peer.iceConnectionState == .connected || peer.iceConnectionState == .completed else { return }
        started = true; connectedUptime = ProcessInfo.processInfo.systemUptime
        onEvent?(["type": "session.started", "session": ["id": id]])
        guard let connection = subscription, let api = subscriptionAPI else { return }
        pollTask = Task { [weak self] in
            var cursor = 0
            while !Task.isCancelled {
                guard let self, self.attempt == token, !self.closing else { return }
                do {
                    let result = try await api.subscriptionRequest(connection, path: "live/sessions/" + id + "/events", method: "GET", after: cursor)
                    guard !Task.isCancelled, self.attempt == token, !self.closing else { return }
                    guard let next = result["cursor"] as? Int, next >= cursor, let events = result["events"] as? [[String: Any]] else { throw APIClient.APIError.invalidResponse }
                    cursor = next
                    for event in events {
                        // The bridge emits this only for its registered, session-bound teaching tool.
                        if event["type"] as? String == "session.delegation.created" {
                            self.onEvent?(event); continue
                        }
                        if event["type"] as? String == "session.closed" {
                            self.remoteSessionID = nil
                            self.onEvent?(["type": "session.closed", "reason": "Subscription session ended", "usage": ["seconds": self.subscriptionSeconds]])
                            return
                        }
                        if event["type"] as? String == "error" { self.onFailure?("Your subscription voice service reported an error. Please reconnect."); return }
                    }
                } catch is CancellationError { return }
                catch {
                    guard !Task.isCancelled, self.attempt == token, !self.closing else { return }
                    self.onFailure?("Your subscription service is no longer reachable. Your conversation has been saved."); return
                }
            }
        }
    }
    private func startMetering() {
        meterTask?.cancel()
        let token = attempt
        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.attempt == token, let peer = self.peer else { return }
                peer.statistics { [weak self] report in
                    var input = 0.0, output = 0.0
                    for stat in report.statistics.values {
                        let level = (stat.values["audioLevel"] as? NSNumber)?.doubleValue ?? 0
                        if stat.type == "inbound-rtp" { output = max(output, level) }
                        if stat.type == "media-source" { input = max(input, level) }
                    }
                    Task { @MainActor [weak self] in
                        guard let self, self.attempt == token, self.started else { return }
                        if output > 0 { self.logStage("first_remote_audio_level", token: token) }
                        self.lastInput = self.lastInput * 0.35 + min(1, input * 4) * 0.65
                        self.lastOutput = self.lastOutput * 0.35 + min(1, output * 4) * 0.65
                        self.onLevels?(self.isMuted ? 0 : self.lastInput, self.lastOutput)
                    }
                }
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
    }
    enum TransportError: LocalizedError {
        case microphone, connection, timeout
        var errorDescription: String? {
            switch self {
            case .microphone: "Allow microphone access in iPhone Settings → Mural to start a conversation."
            case .connection: "The voice connection couldn’t be established. Check your connection and try again."
            case .timeout: "The voice connection took too long. Please try again."
            }
        }
    }
}

extension LiveTransport: RTCDataChannelDelegate, RTCPeerConnectionDelegate {
    nonisolated func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        let closed = dataChannel.readyState == .closed
        Task { @MainActor [weak self] in
            guard let self, dataChannel === self.channel, !self.closing else { return }
            if closed { self.onFailure?("The voice connection ended unexpectedly. Your conversation has been saved.") }
            else { self.subscriptionReady() }
        }
    }
    nonisolated func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        let data = buffer.data
        Task { @MainActor [weak self] in
            guard let self, dataChannel === self.channel,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            let token = self.attempt
            self.logTurnEvent(json, token: token)
            if self.subscription != nil {
                if json["type"] as? String == "error" || json["type"] as? String == "session.error" {
                    self.onFailure?("Your subscription voice connection reported an error. Please reconnect."); return
                }
                if let id = ClassroomTranscriptScope.startedTurnID(json) {
                    self.onEvent?(["type": "mural.subscription.turn.started", "fragment_id": id])
                }
                if let fragment = self.transcript.consume(json, elapsedMS: Int(self.subscriptionSeconds * 1000)) {
                    // Navigation commands must only be driven by a completed user
                    // turn.  The transcript assembler still emits deltas for the
                    // live caption, but this flag lets the coordinator distinguish
                    // a final user utterance from an interim update.
                    let isFinal = (json["type"] as? String) == "turn.done"
                    self.onEvent?(["type": "mural.subscription.transcript", "fragment": fragment,
                                   "is_final": isFinal])
                }
                return
            }
            if json["type"] as? String == "session.started" { self.started = true }
            self.onEvent?(json)
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        Task { @MainActor [weak self] in
            guard let self, peerConnection === self.peer else { return }
            self.setLocalPlaybackActive(self.localPlaybackActive)
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        Task { @MainActor [weak self] in
            guard let self, peerConnection === self.peer else { return }
            self.setLocalPlaybackActive(self.localPlaybackActive)
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Task { @MainActor [weak self] in
            guard let self, peerConnection === self.peer, !self.closing else { return }
            if newState == .failed { self.onFailure?("The network connection was lost. Tap to start a new conversation.") }
            else { self.subscriptionReady() }
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
