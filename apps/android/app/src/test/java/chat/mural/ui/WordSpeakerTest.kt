package chat.mural.ui

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import chat.mural.core.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Unit tests for word pronunciation locale resolution, audio routing, playback transitions, and error recovery. */
class WordSpeakerTest {
    /** Controllable fake speech synthesis engine for deterministic JVM playback testing. */
    private class FakeTtsEngine(
        private val onInit: (Int) -> Unit,
    ) : WordTtsEngine {
        val availableLanguages = mutableSetOf("en-US", "es-ES", "pt-BR", "nl-NL", "ru-RU")
        val voices = mutableListOf(
            TtsVoiceInfo("en-us-local", Locale.US, isNetworkRequired = false),
            TtsVoiceInfo("es-es-local", Locale.forLanguageTag("es-ES"), isNetworkRequired = false),
            TtsVoiceInfo("pt-br-local", Locale.forLanguageTag("pt-BR"), isNetworkRequired = false),
            TtsVoiceInfo("nl-nl-local", Locale.forLanguageTag("nl-NL"), isNetworkRequired = false),
            TtsVoiceInfo("ru-ru-local", Locale.forLanguageTag("ru-RU"), isNetworkRequired = false),
        )
        var returnNullVoices = false
        val recordedUsages = mutableListOf<Int>()
        val recordedContentTypes = mutableListOf<Int>()
        val assignedLanguages = mutableListOf<Locale>()
        val assignedVoices = mutableListOf<String>()
        val spokenUtterances = mutableListOf<Pair<String, String>>()
        var nextSpeakResult: Int = TextToSpeech.SUCCESS
        var stopCalls = 0
        var shutdownCalls = 0
        var callbacks: TtsProgressCallbacks? = null

        /** Invokes engine initialization with [status]. */
        fun completeInit(status: Int = TextToSpeech.SUCCESS) = onInit(status)

        /** Records audio attribute usage and content type configured on the engine. */
        override fun setAudioAttributes(usage: Int, contentType: Int) {
            recordedUsages += usage
            recordedContentTypes += contentType
        }

        /** Stores listener callbacks for manual test event injection. */
        override fun setProgressCallbacks(callbacks: TtsProgressCallbacks) {
            this.callbacks = callbacks
        }

        /** Reports voice availability against [availableLanguages]. */
        override fun isLanguageAvailable(locale: Locale): Int {
            val tag = locale.toLanguageTag()
            return if (availableLanguages.contains(tag) || availableLanguages.contains(locale.language)) {
                TextToSpeech.LANG_AVAILABLE
            } else {
                TextToSpeech.LANG_MISSING_DATA
            }
        }

        /** Returns configured voices or null when simulating unsupported metadata. */
        override fun availableVoices(): List<TtsVoiceInfo>? = if (returnNullVoices) null else voices.toList()

        /** Records selected synthesis locale. */
        override fun setLanguage(locale: Locale) {
            assignedLanguages += locale
        }

        /** Binds synthesis to the matching configured voice [name]. */
        override fun selectVoice(name: String): Boolean {
            if (voices.none { it.name == name }) return false
            assignedVoices += name
            return true
        }

        /** Ignores rate updates in fake engine. */
        override fun setSpeechRate(rate: Float) = Unit

        /** Records or rejects speech enqueue calls according to [nextSpeakResult]. */
        override fun speak(text: String, utteranceId: String): Int {
            if (nextSpeakResult == TextToSpeech.SUCCESS) {
                spokenUtterances += text to utteranceId
            }
            return nextSpeakResult
        }

        /** Counts explicit stop calls. */
        override fun stop() {
            stopCalls++
        }

        /** Counts permanent shutdown calls. */
        override fun shutdown() {
            shutdownCalls++
        }
    }

    /** Creates an [AndroidWordSpeaker] wired to an inspectable [FakeTtsEngine]. */
    private fun createHarness(
        isConversationActive: () -> Boolean = { false },
        autoInitSuccess: Boolean = true,
    ): Triple<AndroidWordSpeaker, FakeTtsEngine, MutableList<Int>> {
        lateinit var engine: FakeTtsEngine
        val unavailableAlerts = mutableListOf<Int>()
        val speaker = AndroidWordSpeaker(
            isConversationActive = isConversationActive,
            dispatchMain = { it.run() },
            onUnavailableFeedback = { unavailableAlerts += 1 },
            engineFactory = { onInit ->
                engine = FakeTtsEngine(onInit)
                engine
            },
        )
        if (autoInitSuccess) {
            engine.completeInit(TextToSpeech.SUCCESS)
        }
        return Triple(speaker, engine, unavailableAlerts)
    }

    /** Verifies every registered learning language produces distinct, non-empty candidate locales. */
    @Test
    fun allRegisteredLanguagesHaveCandidateLocales() {
        for (language in LanguageRegistry.all) {
            val candidates = WordSpeakerLocales.candidateLocales(language.id)
            assertFalse("Expected candidates for language ${language.id}", candidates.isEmpty())
            assertEquals("Expected candidate locales to be distinct", candidates.size, candidates.distinct().size)
        }
    }

    /** Verifies Dutch and Russian varieties include nl-NL and ru-RU regional locales. */
    @Test
    fun dutchAndRussianLocalesAreAccuratelyMapped() {
        val dutch = WordSpeakerLocales.candidateLocales("nl")
        assertTrue(dutch.any { it.language == "nl" })
        assertTrue(dutch.contains(Locale.forLanguageTag("nl-NL")))

        val russian = WordSpeakerLocales.candidateLocales("ru")
        assertTrue(russian.any { it.language == "ru" })
        assertTrue(russian.contains(Locale.forLanguageTag("ru-RU")))
    }

    /** Verifies Norwegian and Tagalog include expected regional fallbacks. */
    @Test
    fun norwegianAndTagalogProvideRegionalFallbacks() {
        val norwegian = WordSpeakerLocales.candidateLocales("nb")
        assertTrue(norwegian.any { it.language == "no" || it.language == "nb" })

        val tagalog = WordSpeakerLocales.candidateLocales("tl")
        assertTrue(tagalog.any { it.language == "fil" || it.language == "tl" })
    }

    /** Verifies Portuguese prioritizes pt-BR and excludes European Portuguese fallbacks. */
    @Test
    fun portuguesePrioritizesBrazilianLocale() {
        val candidates = WordSpeakerLocales.candidateLocales("pt")
        assertEquals(Locale.forLanguageTag("pt-BR"), candidates.first())
        assertFalse("Should not include European Portuguese fallback", candidates.contains(Locale.forLanguageTag("pt-PT")))
    }

    /** Verifies unknown language identifiers fall back cleanly to their tag. */
    @Test
    fun unknownLanguageFallsBackGracefully() {
        val candidates = WordSpeakerLocales.candidateLocales("xyz")
        assertEquals(listOf(Locale.forLanguageTag("xyz")), candidates)
    }

    /** Verifies WordPlaybackState data integrity across all states. */
    @Test
    fun playbackStateIntegrity() {
        val idle = WordPlaybackState.Idle
        val loading = WordPlaybackState.Loading("en|travel")
        val playing = WordPlaybackState.Playing("en|travel")
        val unavailable = WordPlaybackState.Unavailable("tl|salamat", "tl")
        val error = WordPlaybackState.Error("ru|книга")

        assertEquals("en|travel", loading.wordId)
        assertEquals("en|travel", playing.wordId)
        assertEquals("tl|salamat", unavailable.wordId)
        assertEquals("tl", unavailable.languageID)
        assertEquals("ru|книга", error.wordId)
        assertNotEquals(idle, playing)
    }

    /** Verifies spoken playback configures media speech routing without engaging microphone or voice-call mode. */
    @Test
    fun audioRoutingUsesMediaSpeechAndStaysSeparateFromConversationMic() {
        val route = WordAudioRouting.defaultRoute
        assertEquals(AudioAttributes.USAGE_MEDIA, route.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, route.contentType)
        assertNotEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, route.usage)
        assertFalse(route.usesMicrophone)
        assertFalse(route.usesCommunicationMode)

        var micActive = false
        val (speaker, engine, _) = createHarness(isConversationActive = { micActive })

        speaker.speak("nl|fiets", "fiets", "nl")
        assertEquals(listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_MEDIA), engine.recordedUsages)
        assertEquals(listOf(AudioAttributes.CONTENT_TYPE_SPEECH, AudioAttributes.CONTENT_TYPE_SPEECH), engine.recordedContentTypes)
        assertEquals(Locale.forLanguageTag("nl-NL"), engine.assignedLanguages.single())

        val firstToken = engine.spokenUtterances.single().second
        engine.callbacks?.onStart(firstToken)
        assertEquals(WordPlaybackState.Playing("nl|fiets"), speaker.state.value)
        engine.callbacks?.onDone(firstToken)
        assertEquals(WordPlaybackState.Idle, speaker.state.value)

        // When the conversation microphone is active, pronunciation does not enqueue speech on the mic route.
        micActive = true
        speaker.speak("ru|книга", "книга", "ru")
        assertEquals(WordPlaybackState.Error("ru|книга"), speaker.state.value)
        assertEquals(1, engine.spokenUtterances.size)
    }

    /** Verifies repeated taps toggle playback off and delayed callbacks from stopped utterances are ignored. */
    @Test
    fun repeatedTapsStopPlaybackAndIgnoreStaleCallbacks() {
        val (speaker, engine, _) = createHarness()

        speaker.speak("es|café", "café", "es")
        val firstToken = engine.spokenUtterances[0].second
        assertEquals(WordPlaybackState.Loading("es|café"), speaker.state.value)

        // Repeated tap while loading stops cleanly.
        speaker.performPrimaryAction("es|café", "café", "es")
        assertEquals(WordPlaybackState.Idle, speaker.state.value)

        // Delayed error or start callback for canceled utterance cannot clobber idle state.
        engine.callbacks?.onStart(firstToken)
        engine.callbacks?.onError(firstToken)
        assertEquals(WordPlaybackState.Idle, speaker.state.value)

        // Play -> tap again while playing -> stops and resets to idle.
        speaker.speak("es|café", "café", "es")
        val secondToken = engine.spokenUtterances[1].second
        engine.callbacks?.onStart(secondToken)
        assertEquals(WordPlaybackState.Playing("es|café"), speaker.state.value)

        speaker.performPrimaryAction("es|café", "café", "es")
        assertEquals(WordPlaybackState.Idle, speaker.state.value)
        engine.callbacks?.onStop(secondToken, interrupted = true)
        assertEquals(WordPlaybackState.Idle, speaker.state.value)
    }

    /** Verifies tapping a second word interrupts the first and rejects stale callbacks from the prior word. */
    @Test
    fun switchingWordsStopsPreviousAndBlocksStaleUtteranceEvents() {
        val (speaker, engine, _) = createHarness()

        speaker.speak("en|travel", "travel", "en")
        val tokenA = engine.spokenUtterances[0].second
        engine.callbacks?.onStart(tokenA)
        assertEquals(WordPlaybackState.Playing("en|travel"), speaker.state.value)

        speaker.speak("pt|pão", "pão", "pt")
        val tokenB = engine.spokenUtterances[1].second
        assertEquals(WordPlaybackState.Loading("pt|pão"), speaker.state.value)

        // Stale callbacks for word A must not disturb word B.
        engine.callbacks?.onDone(tokenA)
        engine.callbacks?.onError(tokenA)
        assertEquals(WordPlaybackState.Loading("pt|pão"), speaker.state.value)

        engine.callbacks?.onStart(tokenB)
        assertEquals(WordPlaybackState.Playing("pt|pão"), speaker.state.value)
        engine.callbacks?.onDone(tokenB)
        assertEquals(WordPlaybackState.Idle, speaker.state.value)
    }

    /** Verifies enqueue failures, synthesis errors, and unexpected interruptions transition to Error and recover on retry. */
    @Test
    fun errorsAndInterruptionsExposeWorkingRetryPath() {
        val (speaker, engine, _) = createHarness()

        engine.nextSpeakResult = TextToSpeech.ERROR
        speaker.speak("ru|книга", "книга", "ru")
        assertEquals(WordPlaybackState.Error("ru|книга"), speaker.state.value)

        engine.nextSpeakResult = TextToSpeech.SUCCESS
        speaker.performPrimaryAction("ru|книга", "книга", "ru")
        assertEquals(WordPlaybackState.Loading("ru|книга"), speaker.state.value)
        val retryToken = engine.spokenUtterances[0].second
        engine.callbacks?.onStart(retryToken)
        assertEquals(WordPlaybackState.Playing("ru|книга"), speaker.state.value)

        // Synthesis failure moves to Error, and explicit retry recovers playback.
        engine.callbacks?.onError(retryToken)
        assertEquals(WordPlaybackState.Error("ru|книга"), speaker.state.value)

        speaker.retry("ru|книга", "книга", "ru")
        val recoveredToken = engine.spokenUtterances[1].second
        engine.callbacks?.onStart(recoveredToken)
        assertEquals(WordPlaybackState.Playing("ru|книга"), speaker.state.value)

        // Unexpected external interruption transitions to Error rather than dropping failure feedback.
        engine.callbacks?.onStop(recoveredToken, interrupted = true)
        assertEquals(WordPlaybackState.Error("ru|книга"), speaker.state.value)
    }

    /** Verifies deferred async initialization, missing voices, blank text, and resource release cleanup. */
    @Test
    fun asyncInitMissingVoicesAndReleaseCleanupBehaveDeterministically() {
        val (speaker, engine, alerts) = createHarness(autoInitSuccess = false)

        // Request arriving before init stays queued in Loading until init succeeds.
        speaker.speak("ru|книга", "книга", "ru")
        assertEquals(WordPlaybackState.Loading("ru|книга"), speaker.state.value)
        assertTrue(engine.spokenUtterances.isEmpty())

        engine.completeInit(TextToSpeech.SUCCESS)
        assertEquals(1, engine.spokenUtterances.size)
        val token = engine.spokenUtterances.single().second
        engine.callbacks?.onStart(token)
        assertEquals(WordPlaybackState.Playing("ru|книга"), speaker.state.value)

        // Release stops engine, shuts down TTS, and clears playback state.
        speaker.release()
        assertEquals(WordPlaybackState.Idle, speaker.state.value)
        assertTrue(engine.stopCalls >= 1)
        assertEquals(1, engine.shutdownCalls)

        // Subsequent speak after release is ignored safely.
        speaker.speak("es|café", "café", "es")
        assertEquals(WordPlaybackState.Idle, speaker.state.value)

        // Missing voice and engine init failure both produce Unavailable without blocking.
        val (unavailSpeaker, unavailEngine, unavailAlerts) = createHarness(autoInitSuccess = true)
        unavailSpeaker.speak("tl|salamat", "salamat", "tl")
        assertEquals(WordPlaybackState.Unavailable("tl|salamat", "tl"), unavailSpeaker.state.value)
        assertEquals(1, unavailAlerts.size)

        val (failedInitSpeaker, failedEngine, failedAlerts) = createHarness(autoInitSuccess = false)
        failedEngine.completeInit(TextToSpeech.ERROR)
        failedInitSpeaker.speak("en|hello", "hello", "en")
        assertEquals(WordPlaybackState.Unavailable("en|hello", "en"), failedInitSpeaker.state.value)
        assertEquals(1, failedAlerts.size)
        assertTrue(alerts.isEmpty())
    }

    /** Verifies network-only, uninstalled, and disallowed-variety voices are rejected while offline voices bind explicitly. */
    @Test
    fun networkOnlyAndNotInstalledVoicesAreRejectedAsUnavailable() {
        val (speaker, engine, alerts) = createHarness()

        // Network-only voice returns LANG_AVAILABLE yet must produce Unavailable without enqueuing speech.
        engine.voices.removeAll { it.locale.language == "ru" }
        engine.voices += TtsVoiceInfo("ru-ru-network", Locale.forLanguageTag("ru-RU"), isNetworkRequired = true)
        engine.voices += TtsVoiceInfo("de-de-not-installed", Locale.GERMANY, isNetworkRequired = false, isInstalled = false)
        engine.availableLanguages += "de-DE"

        speaker.speak("ru|книга", "книга", "ru")
        assertEquals(WordPlaybackState.Unavailable("ru|книга", "ru"), speaker.state.value)
        assertEquals(1, alerts.size)
        assertTrue(engine.spokenUtterances.isEmpty())
        assertTrue(engine.assignedVoices.isEmpty())

        speaker.speak("de|buch", "Buch", "de")
        assertEquals(WordPlaybackState.Unavailable("de|buch", "de"), speaker.state.value)
        assertEquals(2, alerts.size)

        // Portuguese must never fall back to offline pt-PT when pt-BR only has a network voice.
        engine.voices.removeAll { it.locale.language == "pt" }
        engine.voices += TtsVoiceInfo("pt-br-network", Locale.forLanguageTag("pt-BR"), isNetworkRequired = true)
        engine.voices += TtsVoiceInfo("pt-pt-local", Locale.forLanguageTag("pt-PT"), isNetworkRequired = false)
        engine.availableLanguages += "pt-PT"
        speaker.speak("pt|pão", "pão", "pt")
        assertEquals(WordPlaybackState.Unavailable("pt|pão", "pt"), speaker.state.value)
        assertEquals(3, alerts.size)

        // When both network and offline Russian voices exist, playback explicitly binds the offline voice.
        engine.voices += TtsVoiceInfo("ru-ru-local", Locale.forLanguageTag("ru-RU"), isNetworkRequired = false)
        speaker.speak("ru|книга", "книга", "ru")
        assertEquals(WordPlaybackState.Loading("ru|книга"), speaker.state.value)
        assertEquals(listOf("ru-ru-local"), engine.assignedVoices)
        assertEquals(1, engine.spokenUtterances.size)

        // Null voice metadata fails closed as Unavailable rather than allowing unverified network playback.
        engine.returnNullVoices = true
        speaker.speak("en|travel", "travel", "en")
        assertEquals(WordPlaybackState.Unavailable("en|travel", "en"), speaker.state.value)
    }
}
