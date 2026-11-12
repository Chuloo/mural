package chat.mural.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import chat.mural.R
import chat.mural.core.LanguageRegistry
import java.util.Locale

/** Represents playback progress for a specific vocabulary word or phrase. */
sealed class WordPlaybackState {
    /** No pronunciation is active or pending. */
    object Idle : WordPlaybackState()

    /** Audio routing or speech synthesis initialization is underway for [wordId]. */
    data class Loading(val wordId: String) : WordPlaybackState()

    /** Speech audio is actively playing for [wordId]. */
    data class Playing(val wordId: String) : WordPlaybackState()

    /** No compatible offline voice is available on the device for [languageID]. */
    data class Unavailable(val wordId: String, val languageID: String) : WordPlaybackState()

    /** Pronunciation failed or was unexpectedly interrupted for [wordId] and can be retried. */
    data class Error(val wordId: String) : WordPlaybackState()
}

/** Describes non-microphone audio routing parameters for spoken vocabulary playback. */
data class WordAudioRouteConfig(
    val usage: Int,
    val contentType: Int,
    val usesMicrophone: Boolean,
    val usesCommunicationMode: Boolean,
)

/** Defines spoken media audio routing isolated from the conversation microphone. */
object WordAudioRouting {
    /** Spoken playback routes as media speech so it never engages call or microphone routing. */
    val defaultRoute = WordAudioRouteConfig(
        usage = AudioAttributes.USAGE_MEDIA,
        contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
        usesMicrophone = false,
        usesCommunicationMode = false,
    )

    /** Builds platform [AudioAttributes] matching [defaultRoute] for `TextToSpeech`. */
    fun playbackAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(defaultRoute.usage)
        .setContentType(defaultRoute.contentType)
        .build()
}

/** Controls on-device word pronunciation and playback state. */
interface WordSpeaker {
    /** Current playback progress across registered words. */
    val state: State<WordPlaybackState>

    /** Initiates spoken audio for the specified word. */
    fun speak(wordId: String, text: String, languageID: String)

    /** Clears an active error state and retries pronunciation for the specified word. */
    fun retry(wordId: String, text: String, languageID: String)

    /** Immediately interrupts and halts any active pronunciation audio. */
    fun stop()

    /** Cleans up underlying audio synthesizer resources. */
    fun release()
}

/** Dispatches the primary pronunciation control action matching the current [WordSpeaker.state]. */
fun WordSpeaker.performPrimaryAction(wordId: String, text: String, languageID: String) {
    when (val current = state.value) {
        is WordPlaybackState.Playing -> if (current.wordId == wordId) stop() else speak(wordId, text, languageID)
        is WordPlaybackState.Loading -> if (current.wordId == wordId) stop() else speak(wordId, text, languageID)
        is WordPlaybackState.Error -> if (current.wordId == wordId) retry(wordId, text, languageID) else speak(wordId, text, languageID)
        else -> speak(wordId, text, languageID)
    }
}

/** Resolves candidate locales for each learning language in order of regional preference. */
object WordSpeakerLocales {
    /** Returns prioritized locales to query with TextToSpeech for the given language identifier. */
    fun candidateLocales(languageID: String): List<Locale> {
        val module = LanguageRegistry.get(languageID)
        val tagLocale = module?.locale?.let { Locale.forLanguageTag(it) }
        val specific = when (languageID) {
            "nb" -> listOf(Locale.forLanguageTag("no-NO"), Locale.forLanguageTag("nb-NO"), Locale.forLanguageTag("no"), Locale.forLanguageTag("nb"))
            "es" -> listOf(Locale.forLanguageTag("es-ES"), Locale.forLanguageTag("es"))
            "en" -> listOf(Locale.US, Locale.ENGLISH, Locale.UK)
            "nl" -> listOf(Locale.forLanguageTag("nl-NL"), Locale.forLanguageTag("nl"))
            "ru" -> listOf(Locale.forLanguageTag("ru-RU"), Locale.forLanguageTag("ru"))
            "zh" -> listOf(Locale.SIMPLIFIED_CHINESE, Locale.forLanguageTag("zh-CN"), Locale.CHINESE)
            "de" -> listOf(Locale.GERMANY, Locale.GERMAN)
            "fr" -> listOf(Locale.FRANCE, Locale.FRENCH)
            "it" -> listOf(Locale.ITALY, Locale.ITALIAN)
            "pt" -> listOf(Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("pt"))
            "sr" -> listOf(Locale.forLanguageTag("sr-RS"), Locale.forLanguageTag("sr"))
            "el" -> listOf(Locale.forLanguageTag("el-GR"), Locale.forLanguageTag("el"))
            "tl" -> listOf(Locale.forLanguageTag("fil-PH"), Locale.forLanguageTag("tl-PH"), Locale.forLanguageTag("fil"), Locale.forLanguageTag("tl"))
            else -> emptyList()
        }
        return buildList {
            tagLocale?.let { add(it) }
            addAll(specific)
            if (isEmpty()) add(Locale.forLanguageTag(languageID))
        }.distinct()
    }
}

/** Callback bridge receiving engine utterance progress updates. */
internal interface TtsProgressCallbacks {
    /** Invoked when synthesis playback begins for [utteranceId]. */
    fun onStart(utteranceId: String?)

    /** Invoked when synthesis playback finishes naturally for [utteranceId]. */
    fun onDone(utteranceId: String?)

    /** Invoked when synthesis encounters an error for [utteranceId]. */
    fun onError(utteranceId: String?)

    /** Invoked when synthesis stops prematurely for [utteranceId]. */
    fun onStop(utteranceId: String?, interrupted: Boolean)
}

/** Testable speech synthesizer adapter decoupling playback state from Android `TextToSpeech`. */
internal interface WordTtsEngine {
    /** Applies spoken media audio attributes without engaging microphone capture. */
    fun setAudioAttributes(usage: Int, contentType: Int)

    /** Attaches progress callbacks to track playback start, completion, errors, and interruption. */
    fun setProgressCallbacks(callbacks: TtsProgressCallbacks)

    /** Checks offline voice availability for [locale] using `TextToSpeech.LANG_*` codes. */
    fun isLanguageAvailable(locale: Locale): Int

    /** Assigns the resolved [locale] before speaking. */
    fun setLanguage(locale: Locale)

    /** Configures speech rate scaling for clear learner pronunciation. */
    fun setSpeechRate(rate: Float)

    /** Enqueues [text] with unique [utteranceId] and returns `TextToSpeech.SUCCESS` or error code. */
    fun speak(text: String, utteranceId: String): Int

    /** Halts any queued or speaking utterance immediately. */
    fun stop()

    /** Releases underlying native TTS resources permanently. */
    fun shutdown()
}

/** Production [WordTtsEngine] backed by `android.speech.tts.TextToSpeech`. */
private class AndroidTextToSpeechEngine(
    context: Context,
    onInitStatus: (Int) -> Unit,
) : WordTtsEngine {
    private val tts = TextToSpeech(context.applicationContext, onInitStatus)

    /** Configures `TextToSpeech` to stream as spoken media instead of voice communication. */
    override fun setAudioAttributes(usage: Int, contentType: Int) {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .build()
        )
    }

    /** Wires `UtteranceProgressListener` events to [callbacks]. */
    override fun setProgressCallbacks(callbacks: TtsProgressCallbacks) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            /** Forwards synthesis start events. */
            override fun onStart(utteranceId: String?) = callbacks.onStart(utteranceId)

            /** Forwards natural utterance completion events. */
            override fun onDone(utteranceId: String?) = callbacks.onDone(utteranceId)

            /** Forwards legacy synthesis failure callbacks. */
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = callbacks.onError(utteranceId)

            /** Forwards API 21+ synthesis failure callbacks. */
            override fun onError(utteranceId: String?, errorCode: Int) = callbacks.onError(utteranceId)

            /** Forwards interruption/cancellation callbacks. */
            override fun onStop(utteranceId: String?, interrupted: Boolean) = callbacks.onStop(utteranceId, interrupted)
        })
    }

    /** Queries installed TTS voice readiness for [locale]. */
    override fun isLanguageAvailable(locale: Locale): Int = tts.isLanguageAvailable(locale)

    /** Sets the active synthesis language on `TextToSpeech`. */
    override fun setLanguage(locale: Locale) {
        tts.language = locale
    }

    /** Applies speech playback speed multiplier. */
    override fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    /** Queues [text] under `QUEUE_FLUSH` with an explicit utterance token. */
    override fun speak(text: String, utteranceId: String): Int {
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /** Stops active TTS synthesis. */
    override fun stop() {
        tts.stop()
    }

    /** Shuts down `TextToSpeech` cleanly. */
    override fun shutdown() {
        tts.shutdown()
    }
}

/** Android TextToSpeech implementation managing engine lifecycle, audio routing, and utterance progression. */
class AndroidWordSpeaker internal constructor(
    private val isConversationActive: () -> Boolean,
    private val dispatchMain: (Runnable) -> Unit,
    private val onUnavailableFeedback: () -> Unit,
    engineFactory: ((Int) -> Unit) -> WordTtsEngine,
) : WordSpeaker {
    /** Creates an [AndroidWordSpeaker] bound to Android `TextToSpeech` and application [context]. */
    constructor(
        context: Context,
        isConversationActive: () -> Boolean = { false },
    ) : this(
        isConversationActive = isConversationActive,
        dispatchMain = Handler(Looper.getMainLooper())::post,
        onUnavailableFeedback = {
            Toast.makeText(context, context.getString(R.string.pronounce_unavailable), Toast.LENGTH_SHORT).show()
        },
        engineFactory = { onInit -> AndroidTextToSpeechEngine(context, onInit) },
    )

    private val _state = mutableStateOf<WordPlaybackState>(WordPlaybackState.Idle)
    override val state: State<WordPlaybackState> get() = _state

    private var tts: WordTtsEngine? = null
    private var isInitialized = false
    private var initFailed = false
    private var isShutDown = false
    private var pendingRequest: (() -> Unit)? = null
    private var utteranceSequence = 0L
    private var activeUtteranceId: String? = null
    private var activeWordId: String? = null

    init {
        tts = engineFactory { status ->
            dispatchMain {
                if (isShutDown) return@dispatchMain
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    initFailed = false
                    val route = WordAudioRouting.defaultRoute
                    tts?.setAudioAttributes(route.usage, route.contentType)
                    setupUtteranceListener()
                    val queued = pendingRequest
                    pendingRequest = null
                    queued?.invoke()
                } else {
                    isInitialized = false
                    initFailed = true
                    val queued = pendingRequest
                    pendingRequest = null
                    _state.value = WordPlaybackState.Idle
                    queued?.invoke()
                }
            }
        }
    }

    /** Attaches thread-safe utterance progress listeners filtered by [activeUtteranceId]. */
    private fun setupUtteranceListener() {
        tts?.setProgressCallbacks(object : TtsProgressCallbacks {
            /** Moves matching active utterance to [WordPlaybackState.Playing]. */
            override fun onStart(utteranceId: String?) {
                dispatchMain {
                    if (!isShutDown && utteranceId != null && utteranceId == activeUtteranceId) {
                        val wordId = activeWordId ?: return@dispatchMain
                        _state.value = WordPlaybackState.Playing(wordId)
                    }
                }
            }

            /** Resets matching active utterance to [WordPlaybackState.Idle] upon completion. */
            override fun onDone(utteranceId: String?) {
                dispatchMain {
                    if (!isShutDown && utteranceId != null && utteranceId == activeUtteranceId) {
                        activeUtteranceId = null
                        activeWordId = null
                        _state.value = WordPlaybackState.Idle
                    }
                }
            }

            /** Surfaces [WordPlaybackState.Error] when the active utterance fails during synthesis. */
            override fun onError(utteranceId: String?) {
                dispatchMain {
                    if (!isShutDown && utteranceId != null && utteranceId == activeUtteranceId) {
                        val wordId = activeWordId
                        activeUtteranceId = null
                        activeWordId = null
                        if (wordId != null) {
                            _state.value = WordPlaybackState.Error(wordId)
                        }
                    }
                }
            }

            /** Clears state or marks unexpected external interruption as retryable error. */
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                dispatchMain {
                    if (!isShutDown && utteranceId != null && utteranceId == activeUtteranceId) {
                        val wordId = activeWordId
                        activeUtteranceId = null
                        activeWordId = null
                        _state.value = if (interrupted && wordId != null) {
                            WordPlaybackState.Error(wordId)
                        } else {
                            WordPlaybackState.Idle
                        }
                    }
                }
            }
        })
    }

    /** Speaks [text] for [wordId] using regional voices in [languageID], toggling off on repeat tap. */
    override fun speak(wordId: String, text: String, languageID: String) {
        if (isShutDown) return

        // Tapping the currently playing or loading word stops playback cleanly.
        val current = _state.value
        if ((current is WordPlaybackState.Playing && current.wordId == wordId) ||
            (current is WordPlaybackState.Loading && current.wordId == wordId)
        ) {
            stop()
            return
        }

        stop()
        enqueueOrStartSpeech(wordId, text, languageID)
    }

    /** Executes validation, locale selection, and synthesis after repeat-tap toggling completes. */
    private fun enqueueOrStartSpeech(wordId: String, text: String, languageID: String) {
        if (isShutDown) return

        if (isConversationActive()) {
            _state.value = WordPlaybackState.Error(wordId)
            return
        }

        if (text.isBlank()) {
            _state.value = WordPlaybackState.Error(wordId)
            return
        }

        if (initFailed) {
            _state.value = WordPlaybackState.Unavailable(wordId, languageID)
            onUnavailableFeedback()
            return
        }

        if (!isInitialized) {
            _state.value = WordPlaybackState.Loading(wordId)
            pendingRequest = { enqueueOrStartSpeech(wordId, text, languageID) }
            return
        }

        val speechEngine = tts ?: return
        val supportedLocale = WordSpeakerLocales.candidateLocales(languageID).firstOrNull { candidate ->
            speechEngine.isLanguageAvailable(candidate) >= TextToSpeech.LANG_AVAILABLE
        }

        if (supportedLocale == null) {
            _state.value = WordPlaybackState.Unavailable(wordId, languageID)
            onUnavailableFeedback()
            return
        }

        val route = WordAudioRouting.defaultRoute
        speechEngine.setAudioAttributes(route.usage, route.contentType)
        speechEngine.setLanguage(supportedLocale)
        speechEngine.setSpeechRate(0.92f)

        val token = "${++utteranceSequence}:$wordId"
        activeUtteranceId = token
        activeWordId = wordId
        _state.value = WordPlaybackState.Loading(wordId)

        val result = speechEngine.speak(text, token)
        if (result != TextToSpeech.SUCCESS && activeUtteranceId == token) {
            activeUtteranceId = null
            activeWordId = null
            _state.value = WordPlaybackState.Error(wordId)
        }
    }

    /** Clears an error state and invokes playback recovery for [wordId]. */
    override fun retry(wordId: String, text: String, languageID: String) {
        if (isShutDown) return
        val current = _state.value
        if (current is WordPlaybackState.Error && current.wordId == wordId) {
            _state.value = WordPlaybackState.Idle
        }
        speak(wordId, text, languageID)
    }

    /** Interrupts active speech, clears tracked utterance identity, and resets to [WordPlaybackState.Idle]. */
    override fun stop() {
        if (isShutDown) return
        pendingRequest = null
        activeUtteranceId = null
        activeWordId = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        _state.value = WordPlaybackState.Idle
    }

    /** Permanently stops playback and releases underlying speech synthesizer resources. */
    override fun release() {
        if (isShutDown) return
        isShutDown = true
        pendingRequest = null
        activeUtteranceId = null
        activeWordId = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        _state.value = WordPlaybackState.Idle
        tts = null
    }
}

/** Remembers and manages the lifecycle of an on-device [WordSpeaker]. */
@Composable
fun rememberWordSpeaker(isConversationActive: () -> Boolean = { false }): WordSpeaker {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeMicState by rememberUpdatedState(isConversationActive)
    val speaker = remember { AndroidWordSpeaker(context, isConversationActive = { activeMicState() }) }

    DisposableEffect(lifecycleOwner, speaker) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                speaker.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            speaker.release()
        }
    }

    return speaker
}

/** Returns the localized TalkBack action and content description for [wordId]'s current pronunciation state. */
@Composable
fun pronunciationDescription(wordId: String, speaker: WordSpeaker): String {
    val playbackState by speaker.state
    return when {
        playbackState is WordPlaybackState.Playing && (playbackState as WordPlaybackState.Playing).wordId == wordId ->
            stringResource(R.string.pronounce_stop)
        playbackState is WordPlaybackState.Loading && (playbackState as WordPlaybackState.Loading).wordId == wordId ->
            stringResource(R.string.pronounce_loading)
        playbackState is WordPlaybackState.Error && (playbackState as WordPlaybackState.Error).wordId == wordId ->
            stringResource(R.string.pronounce_retry)
        playbackState is WordPlaybackState.Unavailable && (playbackState as WordPlaybackState.Unavailable).wordId == wordId ->
            stringResource(R.string.pronounce_unavailable)
        else -> stringResource(R.string.pronounce_play)
    }
}

/** Accessible audio playback control for spoken word pronunciation. */
@Composable
fun PronunciationButton(
    wordId: String,
    lemma: String,
    languageID: String,
    speaker: WordSpeaker,
    modifier: Modifier = Modifier,
    diameter: Dp = 40.dp,
    testTag: String = "pronounce-button",
) {
    val playbackState by speaker.state
    val isPlaying = playbackState is WordPlaybackState.Playing && (playbackState as WordPlaybackState.Playing).wordId == wordId
    val isLoading = playbackState is WordPlaybackState.Loading && (playbackState as WordPlaybackState.Loading).wordId == wordId
    val isError = playbackState is WordPlaybackState.Error && (playbackState as WordPlaybackState.Error).wordId == wordId
    val isUnavailable = playbackState is WordPlaybackState.Unavailable && (playbackState as WordPlaybackState.Unavailable).wordId == wordId

    val description = pronunciationDescription(wordId, speaker)

    val symbol = when {
        isError -> MuralSymbol.Retry
        isUnavailable -> MuralSymbol.SpeakerOff
        else -> MuralSymbol.Wave
    }

    val iconColor = when {
        isPlaying -> MuralColors.Orange
        isError -> MuralColors.Red
        isUnavailable -> MuralColors.Secondary.copy(alpha = 0.5f)
        else -> MuralColors.Ink
    }

    val tintColor = when {
        isPlaying -> MuralColors.Peach.copy(alpha = 0.85f)
        isLoading -> MuralColors.Butter.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.78f)
    }

    Box(contentAlignment = Alignment.Center) {
        SoftRoundButton(
            symbol = symbol,
            description = description,
            onClick = { speaker.performPrimaryAction(wordId, lemma, languageID) },
            modifier = modifier.testTag(testTag),
            diameter = diameter,
            tint = tintColor,
            iconColor = iconColor,
            showIcon = !isLoading,
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(diameter * 0.55f),
                color = MuralColors.Orange,
                strokeWidth = 2.dp,
            )
        }
    }
}
