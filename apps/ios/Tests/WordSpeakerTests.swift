import XCTest
@testable import MuralCore

/// Unit tests covering word pronunciation locale resolution, state machine, audio routing, error recovery, and accessibility.
@MainActor
final class WordSpeakerTests: XCTestCase {
    /// Captures spoken-playback audio session activations and deactivations without touching device hardware.
    private final class MockAudioSession: WordSpeechAudioSession {
        struct ActivationError: Error { }

        var activatedRoutes: [WordAudioRoute] = []
        var deactivationCount = 0
        var shouldThrowOnActivate = false

        /// Records the requested audio route or throws when simulating an audio-routing failure.
        func activatePlayback(route: WordAudioRoute) throws {
            if shouldThrowOnActivate {
                throw ActivationError()
            }
            activatedRoutes.append(route)
        }

        /// Increments deactivation count when playback releases the audio session.
        func deactivatePlayback() {
            deactivationCount += 1
        }
    }

    /// Minimal utterance token whose identity tracks delegate callbacks across rapid re-taps.
    private final class FakeUtterance {
        let text: String
        let voiceIdentifier: String
        let rateScale: Float

        init(text: String, voiceIdentifier: String, rateScale: Float) {
            self.text = text
            self.voiceIdentifier = voiceIdentifier
            self.rateScale = rateScale
        }
    }

    /// Fake speech synthesizer recording requested voice IDs, spoken utterances, and stop calls.
    private final class MockSynthesizer: WordSpeechSynthesizing {
        var availableVoices: [String: String] = [
            "en-US": "voice.en-US",
            "es-ES": "voice.es-ES",
            "pt-BR": "voice.pt-BR",
            "nl-NL": "voice.nl-NL",
            "ru-RU": "voice.ru-RU",
        ]
        var shouldFailEnqueue = false
        var resolvedTags: [String] = []
        var spokenUtterances: [FakeUtterance] = []
        var stopCount = 0

        /// Resolves the first configured candidate language tag in priority order.
        func resolveVoiceIdentifier(for languageID: String) -> String? {
            for tag in WordSpeakerLocales.candidateLanguageTags(for: languageID) {
                resolvedTags.append(tag)
                if let voiceID = availableVoices[tag] {
                    return voiceID
                }
            }
            return nil
        }

        /// Allocates a distinct reference token per utterance request.
        func makeUtterance(text: String, voiceIdentifier: String, rateScale: Float) -> AnyObject {
            FakeUtterance(text: text, voiceIdentifier: voiceIdentifier, rateScale: rateScale)
        }

        /// Records or rejects the submitted utterance based on `shouldFailEnqueue`.
        func speak(_ utterance: AnyObject) -> Bool {
            guard !shouldFailEnqueue, let fake = utterance as? FakeUtterance else {
                return false
            }
            spokenUtterances.append(fake)
            return true
        }

        /// Tracks explicit speech stop invocations.
        func stopSpeaking() {
            stopCount += 1
        }
    }

    /// Verifies every registered learning language produces distinct, non-empty candidate speech tags.
    func testAllRegisteredLanguagesHaveDistinctCandidateTags() {
        for language in LanguageRegistry.all {
            let candidates = WordSpeakerLocales.candidateLanguageTags(for: language.id)
            XCTAssertFalse(candidates.isEmpty, "Expected candidate tags for \(language.id)")
            XCTAssertEqual(candidates.count, Set(candidates).count, "Expected distinct tags for \(language.id)")
        }

        XCTAssertTrue(WordSpeakerLocales.candidateLanguageTags(for: "nl").contains("nl-NL"))
        XCTAssertTrue(WordSpeakerLocales.candidateLanguageTags(for: "ru").contains("ru-RU"))
        XCTAssertTrue(WordSpeakerLocales.candidateLanguageTags(for: "nb").contains("nb-NO"))
        XCTAssertTrue(WordSpeakerLocales.candidateLanguageTags(for: "nb").contains("no-NO"))
        XCTAssertTrue(WordSpeakerLocales.candidateLanguageTags(for: "tl").contains("fil-PH"))
    }

    /// Verifies Portuguese strictly targets Brazilian Portuguese without falling back to European locales.
    func testPortugueseRestrictsToBrazilianVariety() {
        let candidates = WordSpeakerLocales.candidateLanguageTags(for: "pt")
        XCTAssertEqual(candidates, ["pt-BR"])
        XCTAssertFalse(candidates.contains("pt-PT"))
    }

    /// Verifies unknown language identifiers degrade to returning themselves without crashing.
    func testUnknownLanguageFallsBackToRawIdentifier() {
        XCTAssertEqual(WordSpeakerLocales.candidateLanguageTags(for: "xyz"), ["xyz"])
    }

    /// Verifies playback configures spoken non-microphone audio routing and refuses to override an active call mic.
    func testAudioRoutingIsolatesPlaybackFromConversationMicrophone() {
        let synth = MockSynthesizer()
        let audio = MockAudioSession()
        var micActive = false
        var announcements: [String] = []
        let controller = WordSpeakerController(
            synthesizer: synth,
            audioSession: audio,
            isConversationMicActive: { micActive },
            onAnnouncement: { announcements.append($0) }
        )

        controller.speak(wordID: "nl|fiets", text: "fiets", languageID: "nl")
        XCTAssertEqual(controller.state, .loading("nl|fiets"))
        XCTAssertEqual(audio.activatedRoutes, [WordAudioRoute.spokenPlayback])
        XCTAssertFalse(audio.activatedRoutes[0].usesMicrophone)
        XCTAssertEqual(audio.activatedRoutes[0].category, "playback")
        XCTAssertEqual(audio.activatedRoutes[0].mode, "spokenAudio")

        let utterance = tryUnwrapLast(synth.spokenUtterances)
        controller.handleDidStart(utterance: utterance)
        XCTAssertEqual(controller.state, .playing("nl|fiets"))
        controller.handleDidFinish(utterance: utterance)
        XCTAssertEqual(controller.state, .idle)
        XCTAssertEqual(audio.deactivationCount, 1)

        // When a live voice conversation owns the microphone, playback stays isolated and never alters mic routing.
        micActive = true
        controller.speak(wordID: "ru|книга", text: "книга", languageID: "ru")
        XCTAssertEqual(controller.state, .error("ru|книга"))
        XCTAssertEqual(audio.activatedRoutes.count, 1)
        XCTAssertEqual(synth.spokenUtterances.count, 1)
        XCTAssertEqual(announcements, ["Pronunciation failed"])

        // Once the live call ends, retry invokes playback cleanly.
        micActive = false
        controller.retry(wordID: "ru|книга", text: "книга", languageID: "ru")
        XCTAssertEqual(controller.state, .loading("ru|книга"))
        XCTAssertEqual(audio.activatedRoutes.count, 2)
    }

    /// Verifies repeated taps on the same word stop active or loading playback and ignore stale delegate callbacks.
    func testRepeatedTapsTogglePlaybackAndIgnoreStaleCallbacks() {
        let synth = MockSynthesizer()
        let audio = MockAudioSession()
        let controller = WordSpeakerController(synthesizer: synth, audioSession: audio)

        controller.speak(wordID: "es|café", text: "café", languageID: "es")
        let firstUtterance = synth.spokenUtterances[0]
        controller.handleDidStart(utterance: firstUtterance)
        XCTAssertEqual(controller.state, .playing("es|café"))

        // Second tap while playing halts playback and resets to idle.
        WordSpeakerAccessibility.performPrimaryAction(speaker: controller, wordID: "es|café", text: "café", languageID: "es")
        XCTAssertEqual(controller.state, .idle)
        XCTAssertEqual(audio.deactivationCount, 1)

        // Delayed cancellation callback from the intentionally stopped utterance must not enter error state.
        controller.handleDidCancel(utterance: firstUtterance)
        XCTAssertEqual(controller.state, .idle)

        // Repeated tap while loading cancels back to idle.
        controller.speak(wordID: "es|café", text: "café", languageID: "es")
        XCTAssertEqual(controller.state, .loading("es|café"))
        WordSpeakerAccessibility.performPrimaryAction(speaker: controller, wordID: "es|café", text: "café", languageID: "es")
        XCTAssertEqual(controller.state, .idle)
    }

    /// Verifies tapping a second word interrupts the first and drops late callbacks from the prior utterance.
    func testTappingAnotherWordStopsPreviousAndDropsStaleEvents() {
        let synth = MockSynthesizer()
        let audio = MockAudioSession()
        let controller = WordSpeakerController(synthesizer: synth, audioSession: audio)

        controller.speak(wordID: "en|travel", text: "travel", languageID: "en")
        let firstUtterance = synth.spokenUtterances[0]
        controller.handleDidStart(utterance: firstUtterance)
        XCTAssertEqual(controller.state, .playing("en|travel"))

        controller.speak(wordID: "pt|pão", text: "pão", languageID: "pt")
        let secondUtterance = synth.spokenUtterances[1]
        XCTAssertEqual(controller.state, .loading("pt|pão"))

        // Late events for the interrupted utterance cannot override the newly active word.
        controller.handleDidFinish(utterance: firstUtterance)
        controller.handleDidCancel(utterance: firstUtterance)
        XCTAssertEqual(controller.state, .loading("pt|pão"))

        controller.handleDidStart(utterance: secondUtterance)
        XCTAssertEqual(controller.state, .playing("pt|pão"))
        controller.handleDidFinish(utterance: secondUtterance)
        XCTAssertEqual(controller.state, .idle)
    }

    /// Verifies audio session errors, enqueue errors, and unexpected cancellations surface `.error` and recover via `retry`.
    func testSpeechErrorsAndUnexpectedCancellationsRecoverViaRetryPath() {
        let synth = MockSynthesizer()
        let audio = MockAudioSession()
        var announcements: [String] = []
        let controller = WordSpeakerController(
            synthesizer: synth,
            audioSession: audio,
            onAnnouncement: { announcements.append($0) }
        )

        // Audio session failure produces an explicit error state with a working retry path.
        audio.shouldThrowOnActivate = true
        controller.speak(wordID: "ru|книга", text: "книга", languageID: "ru")
        XCTAssertEqual(controller.state, .error("ru|книга"))
        XCTAssertEqual(announcements.last, "Pronunciation failed")

        audio.shouldThrowOnActivate = false
        WordSpeakerAccessibility.performPrimaryAction(speaker: controller, wordID: "ru|книга", text: "книга", languageID: "ru")
        XCTAssertEqual(controller.state, .loading("ru|книга"))
        controller.handleDidFinish(utterance: synth.spokenUtterances[0])
        XCTAssertEqual(controller.state, .idle)

        // Synthesizer enqueue failure produces `.error` and releases the audio session.
        synth.shouldFailEnqueue = true
        controller.speak(wordID: "nl|fiets", text: "fiets", languageID: "nl")
        XCTAssertEqual(controller.state, .error("nl|fiets"))
        XCTAssertEqual(audio.deactivationCount, 2)

        // Retry after synthesizer recovers starts playback cleanly.
        synth.shouldFailEnqueue = false
        controller.retry(wordID: "nl|fiets", text: "fiets", languageID: "nl")
        let retried = synth.spokenUtterances[1]
        controller.handleDidStart(utterance: retried)
        XCTAssertEqual(controller.state, .playing("nl|fiets"))

        // Unexpected external cancellation during active playback produces `.error` instead of silently resetting.
        controller.handleDidCancel(utterance: retried)
        XCTAssertEqual(controller.state, .error("nl|fiets"))
        XCTAssertEqual(audio.deactivationCount, 3)

        // Explicit retry clears the failure and resumes synthesis.
        WordSpeakerAccessibility.performPrimaryAction(speaker: controller, wordID: "nl|fiets", text: "fiets", languageID: "nl")
        XCTAssertEqual(controller.state, .loading("nl|fiets"))
    }

    /// Verifies unavailable offline voices and blank inputs never activate audio resources and announce cleanly.
    func testUnavailableVoiceAndBlankTextDoNotActivateAudioSession() {
        let synth = MockSynthesizer()
        let audio = MockAudioSession()
        var announcements: [String] = []
        let controller = WordSpeakerController(
            synthesizer: synth,
            audioSession: audio,
            onAnnouncement: { announcements.append($0) }
        )

        controller.speak(wordID: "tl|salamat", text: "salamat", languageID: "tl")
        XCTAssertEqual(controller.state, .unavailable("tl|salamat"))
        XCTAssertEqual(announcements, ["Pronunciation unavailable on this device"])
        XCTAssertTrue(audio.activatedRoutes.isEmpty)

        controller.speak(wordID: "en|empty", text: "   ", languageID: "en")
        XCTAssertEqual(controller.state, .error("en|empty"))
        XCTAssertTrue(audio.activatedRoutes.isEmpty)
    }

    /// Verifies backgrounding and view dismissal both halt playback and release audio resources.
    func testBackgroundingAndDismissalStopPlaybackAndDeactivateAudioSession() {
        let synth = MockSynthesizer()
        let audio = MockAudioSession()
        let controller = WordSpeakerController(synthesizer: synth, audioSession: audio)

        controller.speak(wordID: "es|café", text: "café", languageID: "es")
        controller.handleDidStart(utterance: synth.spokenUtterances[0])
        XCTAssertEqual(controller.state, .playing("es|café"))

        controller.handleAppDidEnterBackground()
        XCTAssertEqual(controller.state, .idle)
        XCTAssertEqual(audio.deactivationCount, 1)

        controller.speak(wordID: "es|té", text: "té", languageID: "es")
        XCTAssertEqual(controller.state, .loading("es|té"))

        controller.handleViewDisappeared()
        XCTAssertEqual(controller.state, .idle)
        XCTAssertEqual(audio.deactivationCount, 2)
    }

    /// Verifies VoiceOver labels and hints reflect every playback state scoped strictly to the matching word ID.
    func testVoiceOverLabelsAndHintsCoverAllStatesPerWord() {
        let wordA = "es|café"
        let wordB = "es|té"

        XCTAssertEqual(WordSpeakerAccessibility.label(for: .idle, wordID: wordA), "Play pronunciation")
        XCTAssertEqual(WordSpeakerAccessibility.label(for: .loading(wordA), wordID: wordA), "Loading pronunciation")
        XCTAssertEqual(WordSpeakerAccessibility.label(for: .playing(wordA), wordID: wordA), "Stop pronunciation")
        XCTAssertEqual(WordSpeakerAccessibility.label(for: .error(wordA), wordID: wordA), "Retry pronunciation")
        XCTAssertEqual(WordSpeakerAccessibility.label(for: .unavailable(wordA), wordID: wordA), "Pronunciation unavailable on this device")

        // Another word card stays labeled for play while wordA owns loading/playing/error.
        XCTAssertEqual(WordSpeakerAccessibility.label(for: .playing(wordA), wordID: wordB), "Play pronunciation")
        XCTAssertEqual(WordSpeakerAccessibility.hint(for: .error(wordA), wordID: wordA), "Pronunciation failed. Double tap to retry.")
        XCTAssertEqual(WordSpeakerAccessibility.hint(for: .unavailable(wordA), wordID: wordA), "Audio is not available for this language on this device.")
        XCTAssertNil(WordSpeakerAccessibility.hint(for: .idle, wordID: wordA))
    }

    /// Extracts the last fake utterance safely for state-machine assertions.
    private func tryUnwrapLast(_ utterances: [FakeUtterance]) -> FakeUtterance {
        guard let last = utterances.last else {
            XCTFail("Expected an enqueued fake utterance")
            return FakeUtterance(text: "", voiceIdentifier: "", rateScale: 1)
        }
        return last
    }
}
