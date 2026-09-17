import Foundation
import AVFoundation
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
    private var closing = false
    private var ownsAudioActivation = false
    private var lastInput = 0.0, lastOutput = 0.0
    private lazy var networkRecovery = makeNetworkRecovery()
    private func makeNetworkRecovery(timeout: Duration = .seconds(8)) -> VoiceConnectionRecovery {
        VoiceConnectionRecovery(timeout: timeout) { [weak self] in
            guard let self, self.peer != nil, !self.closing else { return }
            self.onFailure?("The network connection was lost. Tap to start a new conversation.")
        }
    }

    func connect(api: APIClient, instructions: String, history: [[String: Any]]) async throws {
        disconnect()
        closing = false
        let token = UUID(); attempt = token
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { throw TransportError.microphone }
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
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            peer.setLocalDescription(offer) { error in if let error { c.resume(throwing: error) } else { c.resume() } }
        }
        let deadline = Date().addingTimeInterval(10)
        while peer.iceGatheringState != .complete {
            try await Task.sleep(for: .milliseconds(100))
            guard attempt == token else { throw CancellationError() }
            guard Date() < deadline else { throw TransportError.timeout }
        }
        guard let sdp = peer.localDescription?.sdp else { throw TransportError.connection }
        let result = try await api.post("live/sessions", body: [
            "session": ["model": "gpt-live-1", "instructions": instructions, "input": history,
                        "store": false, "delegation": ["type": "client"], "audio": ["output": ["voice": "marin"]]],
            "transport": ["type": "webrtc", "sdp": sdp]
        ])
        guard attempt == token else { throw CancellationError() }
        guard let transport = result["transport"] as? [String: Any], let answer = transport["sdp"] as? String else { throw TransportError.connection }
        if let session = result["session"] as? [String: Any] { onEvent?(["type": "mural.session.created", "session": session]) }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            peer.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: answer)) { error in
                if let error { c.resume(throwing: error) } else { c.resume() }
            }
        }
        let readyDeadline = Date().addingTimeInterval(20)
        while !started {
            try await Task.sleep(for: .milliseconds(100))
            guard attempt == token else { throw CancellationError() }
            guard Date() < readyDeadline else { throw TransportError.timeout }
        }
        startMetering()
    }

    @discardableResult func send(_ event: [String: Any], respond: Bool = false) -> Bool {
        guard let channel, channel.readyState == .open, let data = try? JSONSerialization.data(withJSONObject: event) else { return false }
        return channel.sendData(RTCDataBuffer(data: data, isBinary: false))
    }
    func mute(_ muted: Bool) {
        isMuted = muted; localTrack?.isEnabled = !muted
        _ = send(["type": muted ? "session.input_audio.mute" : "session.input_audio.unmute", "event_id": UUID().uuidString])
    }
    func close() {
        networkRecovery.connected()
        closing = true; localTrack?.isEnabled = false; isMuted = true
        _ = send(["type": "session.close", "event_id": UUID().uuidString])
    }
    func disconnect() {
        networkRecovery.connected()
        attempt = UUID(); meterTask?.cancel(); meterTask = nil
        started = false; closing = true
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
    private func startMetering() {
        meterTask?.cancel()
        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, let peer = self.peer else { return }
                peer.statistics { [weak self] report in
                    var input = 0.0, output = 0.0
                    for stat in report.statistics.values {
                        let level = (stat.values["audioLevel"] as? NSNumber)?.doubleValue ?? 0
                        if stat.type == "inbound-rtp" { output = max(output, level) }
                        if stat.type == "media-source" { input = max(input, level) }
                    }
                    Task { @MainActor [weak self] in
                        guard let self, self.started else { return }
                        self.lastInput = self.lastInput * 0.35 + min(1, input * 4) * 0.65
                        self.lastOutput = self.lastOutput * 0.35 + min(1, output * 4) * 0.65
                        self.onLevels?(self.isMuted ? 0 : self.lastInput, self.lastOutput)
                    }
                }
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
    }
    #if DEBUG && targetEnvironment(simulator)
    // Offline lifecycle fixture drives the real WebRTC delegate and both teardown paths.
    // No microphone, network handshake or learner data is used.
    static func verifyRecoveryLifecycle() async -> Bool {
        let transport = LiveTransport()
        transport.networkRecovery = transport.makeNetworkRecovery(timeout: .milliseconds(80))
        var failures = 0
        transport.onFailure = { _ in failures += 1 }
        func installPeer() -> RTCPeerConnection? {
            let factory = RTCPeerConnectionFactory()
            transport.factory = factory
            let peer = factory.peerConnection(with: RTCConfiguration(), constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: transport)
            transport.peer = peer; transport.closing = false
            return peer
        }
        func deliver(_ peer: RTCPeerConnection, _ state: RTCIceConnectionState) async {
            transport.peerConnection(peer, didChange: state)
            try? await Task.sleep(for: .milliseconds(15))
        }
        defer { transport.disconnect() }
        for closeFirst in [true, false] {
            guard let old = installPeer() else { return false }
            await deliver(old, .disconnected)
            if closeFirst { transport.close() } else { transport.disconnect() }
            // Observe cancellation before the next connection's normal reset could mask it.
            try? await Task.sleep(for: .milliseconds(110))
            guard failures == 0 else { return false }
            transport.disconnect()
            guard let current = installPeer() else { return false }
            await deliver(old, .disconnected)
            await deliver(old, .failed)
            await deliver(current, .disconnected)
            await deliver(current, .connected)
            try? await Task.sleep(for: .milliseconds(110))
            guard failures == 0 else { return false }
            transport.disconnect()
        }
        guard let peer = installPeer() else { return false }
        await deliver(peer, .disconnected)
        await deliver(peer, .disconnected)
        try? await Task.sleep(for: .milliseconds(110))
        guard failures == 1 else { return false }
        await deliver(peer, .completed)
        await deliver(peer, .disconnected)
        try? await Task.sleep(for: .milliseconds(110))
        return failures == 2
    }
    #endif

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
            guard let self, dataChannel === self.channel, closed, !self.closing else { return }
            self.onFailure?("The voice connection ended unexpectedly. Your conversation has been saved.")
        }
    }
    nonisolated func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        let data = buffer.data
        Task { @MainActor [weak self] in
            guard let self, dataChannel === self.channel,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            if json["type"] as? String == "session.started" { self.started = true }
            self.onEvent?(json)
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Task { @MainActor [weak self] in
            guard let self, peerConnection === self.peer, !self.closing else { return }
            switch newState {
            case .disconnected: self.networkRecovery.disconnected()
            case .connected, .completed: self.networkRecovery.connected()
            case .failed:
                self.networkRecovery.connected()
                self.onFailure?("The network connection was lost. Tap to start a new conversation.")
            default: break
            }
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
