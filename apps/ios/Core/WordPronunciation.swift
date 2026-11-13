import Combine
import Foundation

/// Playback states reflecting on-device speech synthesis progress for a single word or phrase.
public enum WordPlaybackState: Equatable, Sendable {
    case idle
    case loading(String)
    case playing(String)
    case unavailable(String)
    case error(String)

    /// Word identifier associated with the active state, or `nil` when idle.
    public var activeWordID: String? {
        switch self {
        case .idle:
            return nil
        case .loading(let id), .playing(let id), .unavailable(let id), .error(let id):
            return id
        }
    }
}

/// Spoken playback audio-routing descriptor isolated from conversation microphone recording.
public struct WordAudioRoute: Equatable, Sendable {
    /// Audio session category name used for on-device word playback.
    public let category: String
    /// Audio session mode tuned for spoken vocabulary playback.
    public let mode: String
    /// Whether playback ducks background audio instead of taking exclusive communication focus.
    public let ducksOthers: Bool
    /// Guarantees word playback never activates or touches microphone capture.
    public let usesMicrophone: Bool

    /// Creates a spoken-audio playback route descriptor.
    public init(category: String, mode: String, ducksOthers: Bool, usesMicrophone: Bool) {
        self.category = category
        self.mode = mode
        self.ducksOthers = ducksOthers
        self.usesMicrophone = usesMicrophone
    }

    /// Standard non-microphone playback route for vocabulary pronunciation.
    public static let spokenPlayback = WordAudioRoute(
        category: "playback",
        mode: "spokenAudio",
        ducksOthers: true,
        usesMicrophone: false
    )
}

/// Platform audio session abstraction for configuring spoken word playback without mic capture.
@MainActor
public protocol WordSpeechAudioSession: AnyObject {
    /// Configures and activates playback audio routing before speaking.
    func activatePlayback(route: WordAudioRoute) throws
    /// Deactivates playback audio routing after speech completes or is interrupted.
    func deactivatePlayback()
}

/// Platform speech synthesis abstraction decoupled from `AVSpeechSynthesizer` for testability.
@MainActor
public protocol WordSpeechSynthesizing: AnyObject {
    /// Resolves the preferred system voice identifier for a Mural language ID, if installed.
    func resolveVoiceIdentifier(for languageID: String) -> String?
    /// Builds an utterance token to track delegate callbacks by reference identity.
    func makeUtterance(text: String, voiceIdentifier: String, rateScale: Float) -> AnyObject
    /// Submits a previously constructed utterance token to the synthesizer engine.
    func speak(_ utterance: AnyObject) -> Bool
    /// Immediately halts active synthesis without retaining stale state.
    func stopSpeaking()
}

/// Resolves preferred BCP-47 speech tags for each supported Mural learning language.
public enum WordSpeakerLocales {
    /// Returns prioritized BCP-47 tags for selecting an on-device voice for `languageID`.
    public static func candidateLanguageTags(for languageID: String) -> [String] {
        // Keep Brazilian Portuguese strict so synthesis never silently falls back to European Portuguese.
        if languageID == "pt" {
            return ["pt-BR"]
        }

        let moduleLocale = LanguageRegistry.module(for: languageID)?.locale
        let specific: [String] = switch languageID {
        case "nb": ["nb-NO", "no-NO", "nb", "no"]
        case "es": ["es-ES", "es"]
        case "en": ["en-US", "en", "en-GB"]
        case "fr": ["fr-FR", "fr"]
        case "de": ["de-DE", "de"]
        case "it": ["it-IT", "it"]
        case "zh": ["zh-CN", "zh-Hans", "zh"]
        case "sr": ["sr-Latn-RS", "sr-RS", "sr"]
        case "el": ["el-GR", "el"]
        case "tl": ["fil-PH", "tl-PH", "fil", "tl"]
        case "nl": ["nl-NL", "nl"]
        case "ru": ["ru-RU", "ru"]
        default: [languageID]
        }

        var ordered: [String] = []
        if let moduleLocale, languageID != "en" {
            ordered.append(moduleLocale)
        }
        for tag in specific where !ordered.contains(tag) {
            ordered.append(tag)
        }
        if !ordered.contains(languageID) {
            ordered.append(languageID)
        }
        return ordered
    }
}

/// Single source of truth for pronunciation accessibility labels, hints, and primary actions.
public enum WordSpeakerAccessibility {
    /// Returns the accessible VoiceOver label for `wordID` in the current playback state.
    public static func label(for state: WordPlaybackState, wordID: String) -> String {
        switch state {
        case .playing(let id) where id == wordID:
            return "Stop pronunciation"
        case .loading(let id) where id == wordID:
            return "Loading pronunciation"
        case .error(let id) where id == wordID:
            return "Retry pronunciation"
        case .unavailable(let id) where id == wordID:
            return "Pronunciation unavailable on this device"
        default:
            return "Play pronunciation"
        }
    }

    /// Returns a contextual VoiceOver hint explaining recovery or audio availability when needed.
    public static func hint(for state: WordPlaybackState, wordID: String) -> String? {
        switch state {
        case .playing(let id) where id == wordID:
            return "Stops spoken pronunciation."
        case .error(let id) where id == wordID:
            return "Pronunciation failed. Double tap to retry."
        case .unavailable(let id) where id == wordID:
            return "Audio is not available for this language on this device."
        default:
            return nil
        }
    }

    /// Executes the primary control action matching the visible button state for `wordID`.
    @MainActor
    public static func performPrimaryAction(
        speaker: WordSpeakerController,
        wordID: String,
        text: String,
        languageID: String
    ) {
        switch speaker.state {
        case .playing(let current) where current == wordID,
             .loading(let current) where current == wordID:
            speaker.stop()
        case .error(let current) where current == wordID:
            speaker.retry(wordID: wordID, text: text, languageID: languageID)
        default:
            speaker.speak(wordID: wordID, text: text, languageID: languageID)
        }
    }
}

/// Manages vocabulary speech playback state, audio-session isolation, error recovery, and cleanup.
@MainActor
public final class WordSpeakerController: ObservableObject {
    /// Current playback state observed by vocabulary controls.
    @Published public private(set) var state: WordPlaybackState = .idle

    private let synthesizer: any WordSpeechSynthesizing
    private let audioSession: any WordSpeechAudioSession
    private let isConversationMicActive: () -> Bool
    private let onAnnouncement: (String) -> Void
    private let rateScale: Float

    private var activeUtterance: AnyObject?
    private var activeWordID: String?
    private var ownsAudioSession = false

    /// Creates a pronunciation controller with injected synthesis, audio-routing, and announcement hooks.
    public init(
        synthesizer: any WordSpeechSynthesizing,
        audioSession: any WordSpeechAudioSession,
        isConversationMicActive: @escaping () -> Bool = { false },
        onAnnouncement: @escaping (String) -> Void = { _ in },
        rateScale: Float = 0.92
    ) {
        self.synthesizer = synthesizer
        self.audioSession = audioSession
        self.isConversationMicActive = isConversationMicActive
        self.onAnnouncement = onAnnouncement
        self.rateScale = rateScale
    }

    /// Speaks `text` for `wordID` in `languageID`, stopping any active word first or toggling off if repeated.
    public func speak(wordID: String, text: String, languageID: String) {
        switch state {
        case .playing(let current) where current == wordID,
             .loading(let current) where current == wordID:
            stop()
            return
        default:
            break
        }

        stop()

        guard !isConversationMicActive() else {
            state = .error(wordID)
            onAnnouncement("Pronunciation failed")
            return
        }

        guard let voiceIdentifier = synthesizer.resolveVoiceIdentifier(for: languageID) else {
            state = .unavailable(wordID)
            onAnnouncement("Pronunciation unavailable on this device")
            return
        }

        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            state = .error(wordID)
            onAnnouncement("Pronunciation failed")
            return
        }

        do {
            try audioSession.activatePlayback(route: .spokenPlayback)
            ownsAudioSession = true
        } catch {
            ownsAudioSession = false
            state = .error(wordID)
            onAnnouncement("Pronunciation failed")
            return
        }

        let utterance = synthesizer.makeUtterance(text: trimmed, voiceIdentifier: voiceIdentifier, rateScale: rateScale)
        activeUtterance = utterance
        activeWordID = wordID
        state = .loading(wordID)

        if !synthesizer.speak(utterance) {
            if activeUtterance === utterance {
                activeUtterance = nil
                activeWordID = nil
                deactivateAudioSessionIfOwned()
                state = .error(wordID)
                onAnnouncement("Pronunciation failed")
            }
        }
    }

    /// Clears a previous failure state and re-attempts pronunciation for `wordID`.
    public func retry(wordID: String, text: String, languageID: String) {
        if case .error(let current) = state, current == wordID {
            state = .idle
        }
        speak(wordID: wordID, text: text, languageID: languageID)
    }

    /// Immediately stops active speech and releases the playback audio session without touching the mic.
    public func stop() {
        // Clear tracking identity before stopping the synthesizer so intentional didCancel callbacks are ignored.
        activeUtterance = nil
        activeWordID = nil
        synthesizer.stopSpeaking()
        deactivateAudioSessionIfOwned()
        state = .idle
    }

    /// Releases playback resources when the application enters the background.
    public func handleAppDidEnterBackground() {
        stop()
    }

    /// Releases playback resources when the owning vocabulary modal or screen disappears.
    public func handleViewDisappeared() {
        stop()
    }

    /// Advances playback state to `.playing` when the matching utterance starts speaking.
    public func handleDidStart(utterance: AnyObject) {
        guard activeUtterance === utterance else { return }
        state = .playing(activeWordID ?? "")
    }

    /// Returns state to `.idle` and releases audio routing when the active utterance finishes.
    public func handleDidFinish(utterance: AnyObject) {
        guard activeUtterance === utterance else { return }
        activeUtterance = nil
        activeWordID = nil
        deactivateAudioSessionIfOwned()
        state = .idle
    }

    /// Transitions unexpected external cancellations to `.error` so the user can retry playback.
    public func handleDidCancel(utterance: AnyObject) {
        guard activeUtterance === utterance else { return }
        let failedWordID = activeWordID ?? ""
        activeUtterance = nil
        activeWordID = nil
        deactivateAudioSessionIfOwned()
        state = failedWordID.isEmpty ? .idle : .error(failedWordID)
        if !failedWordID.isEmpty {
            onAnnouncement("Pronunciation failed")
        }
    }

    /// Handles explicit speech engine failure notifications for an active utterance.
    public func handlePlaybackFailure(utterance: AnyObject? = nil) {
        if let utterance, activeUtterance !== utterance { return }
        guard let failedWordID = activeWordID, !failedWordID.isEmpty else { return }
        activeUtterance = nil
        activeWordID = nil
        deactivateAudioSessionIfOwned()
        state = .error(failedWordID)
        onAnnouncement("Pronunciation failed")
    }

    /// Deactivates only the playback audio session activated by this controller.
    private func deactivateAudioSessionIfOwned() {
        guard ownsAudioSession else { return }
        ownsAudioSession = false
        audioSession.deactivatePlayback()
    }
}
