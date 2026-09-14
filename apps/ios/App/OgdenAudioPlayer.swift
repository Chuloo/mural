import Foundation
@preconcurrency import AVFoundation
import MuralCore

/// Short, bundled word recordings. Prewarming reads at most five words in both
/// accents and neither activates the microphone nor starts a network request.
@MainActor final class OgdenAudioPlayer: NSObject, AVAudioPlayerDelegate {
    var onPlaybackChanged: ((Bool) -> Void)?
    var onLevel: ((Double) -> Void)?
    var onError: ((String) -> Void)?
    private var player: AVAudioPlayer?
    private var cached: [URL: Data] = [:]
    private var warmTask: Task<Void, Never>?
    private var meterTask: Task<Void, Never>?
    private var warmGeneration = UUID()
    private var ownsAudioActivation = false

    func prewarm(words: [OgdenWord]) {
        warmTask?.cancel()
        let generation = UUID(); warmGeneration = generation
        let urls = Array(words.prefix(5)).flatMap { word in
            ["us", "uk"].compactMap { OgdenAudioResources.url(for: word, accent: $0) }
        }
        let retained = cached.filter { urls.contains($0.key) }
        cached = retained
        warmTask = Task { [weak self] in
            let loaded = await Task.detached(priority: .utility) {
                var data = retained
                for url in urls where data[url] == nil {
                    if let bytes = try? Data(contentsOf: url) { data[url] = bytes }
                }
                return data
            }.value
            guard !Task.isCancelled, let self, self.warmGeneration == generation else { return }
            self.cached = loaded
        }
    }

    func play(word: OgdenWord, accent: String, liveAudioActive: Bool) throws {
        stop()
        guard let url = OgdenAudioResources.url(for: word, accent: accent) else { throw PlaybackError.unavailable }
        let bytes: Data
        do { bytes = try cached[url] ?? Data(contentsOf: url) }
        catch { throw PlaybackError.unavailable }
        do {
            let next = try AVAudioPlayer(data: bytes)
            next.delegate = self
            next.isMeteringEnabled = true
            if !liveAudioActive {
                let audio = AVAudioSession.sharedInstance()
                try audio.setCategory(.playback, mode: .default)
                try audio.setActive(true)
                ownsAudioActivation = true
            }
            player = next
            onPlaybackChanged?(true)
            guard next.prepareToPlay(), next.play() else { throw PlaybackError.failed }
            startMetering(next)
        } catch {
            stop()
            throw PlaybackError.failed
        }
    }

    func stop() {
        meterTask?.cancel(); meterTask = nil
        let wasPlaying = player != nil
        player?.delegate = nil; player?.stop(); player = nil
        onLevel?(0)
        if ownsAudioActivation {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            ownsAudioActivation = false
        }
        if wasPlaying { onPlaybackChanged?(false) }
    }

    private func startMetering(_ current: AVAudioPlayer) {
        meterTask?.cancel()
        meterTask = Task { [weak self, weak current] in
            while !Task.isCancelled {
                guard let self, let current, self.player === current else { return }
                guard current.isPlaying else { self.onLevel?(0); return }
                current.updateMeters()
                let power = (0..<current.numberOfChannels).map { current.averagePower(forChannel: $0) }.max() ?? -160
                // Amplitude from the decoded recording, never a simulated pulse.
                let level = power.isFinite && power > -60 ? min(1, pow(10, Double(power) / 20) * 3) : 0
                self.onLevel?(level)
                do { try await Task.sleep(for: .milliseconds(50)) }
                catch { return }
            }
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            guard let self, self.player === player else { return }
            self.stop()
            if !flag { self.onError?(PlaybackError.failed.localizedDescription) }
        }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor [weak self] in
            guard let self, self.player === player else { return }
            self.stop(); self.onError?(PlaybackError.failed.localizedDescription)
        }
    }

    enum PlaybackError: LocalizedError {
        case unavailable, failed
        var errorDescription: String? {
            switch self {
            case .unavailable: "This word’s offline recording is unavailable."
            case .failed: "The offline recording could not be played. Please try again."
            }
        }
    }
}
