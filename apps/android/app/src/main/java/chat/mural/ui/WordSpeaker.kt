package chat.mural.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import chat.mural.R
import chat.mural.core.LanguageRegistry
import java.util.Locale

sealed class WordPlaybackState {
    object Idle : WordPlaybackState()
    data class Loading(val wordId: String) : WordPlaybackState()
    data class Playing(val wordId: String) : WordPlaybackState()
    data class Unavailable(val wordId: String, val languageID: String) : WordPlaybackState()
    data class Error(val wordId: String) : WordPlaybackState()
}

interface WordSpeaker {
    val state: State<WordPlaybackState>
    fun speak(wordId: String, text: String, languageID: String)
    fun stop()
    fun release()
}

object WordSpeakerLocales {
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
            "pt" -> listOf(Locale.forLanguageTag("pt-PT"), Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("pt"))
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

class AndroidWordSpeaker(private val context: Context) : WordSpeaker {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = mutableStateOf<WordPlaybackState>(WordPlaybackState.Idle)
    override val state: State<WordPlaybackState> get() = _state

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isShutDown = false
    private var pendingRequest: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post {
                if (isShutDown) return@post
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    setupUtteranceListener()
                    pendingRequest?.invoke()
                    pendingRequest = null
                } else {
                    isInitialized = false
                    _state.value = WordPlaybackState.Idle
                    pendingRequest = null
                }
            }
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId ?: return
                mainHandler.post {
                    if (!isShutDown) {
                        _state.value = WordPlaybackState.Playing(utteranceId)
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (!isShutDown && _state.value is WordPlaybackState.Playing) {
                        val current = (_state.value as WordPlaybackState.Playing).wordId
                        if (current == utteranceId) {
                            _state.value = WordPlaybackState.Idle
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId ?: return
                mainHandler.post {
                    if (!isShutDown) {
                        _state.value = WordPlaybackState.Error(utteranceId)
                    }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                mainHandler.post {
                    if (!isShutDown && _state.value is WordPlaybackState.Playing) {
                        val current = (_state.value as WordPlaybackState.Playing).wordId
                        if (current == utteranceId) {
                            _state.value = WordPlaybackState.Idle
                        }
                    }
                }
            }
        })
    }

    override fun speak(wordId: String, text: String, languageID: String) {
        if (isShutDown) return

        // Tapping the currently playing word stops playback
        val current = _state.value
        if (current is WordPlaybackState.Playing && current.wordId == wordId) {
            stop()
            return
        }

        // Interrupt any ongoing utterance
        stop()

        if (!isInitialized) {
            _state.value = WordPlaybackState.Loading(wordId)
            pendingRequest = { speak(wordId, text, languageID) }
            return
        }

        val speechEngine = tts ?: return
        val candidates = WordSpeakerLocales.candidateLocales(languageID)
        var supportedLocale: Locale? = null

        for (candidate in candidates) {
            val availability = speechEngine.isLanguageAvailable(candidate)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                supportedLocale = candidate
                break
            }
        }

        if (supportedLocale == null) {
            _state.value = WordPlaybackState.Unavailable(wordId, languageID)
            Toast.makeText(context, context.getString(R.string.pronounce_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        speechEngine.language = supportedLocale
        speechEngine.setSpeechRate(0.92f)

        _state.value = WordPlaybackState.Loading(wordId)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, wordId)
        }
        val result = speechEngine.speak(text, TextToSpeech.QUEUE_FLUSH, params, wordId)
        if (result != TextToSpeech.SUCCESS) {
            _state.value = WordPlaybackState.Error(wordId)
        }
    }

    override fun stop() {
        if (isShutDown) return
        pendingRequest = null
        try {
            tts?.stop()
        } catch (_: Exception) {}
        _state.value = WordPlaybackState.Idle
    }

    override fun release() {
        if (isShutDown) return
        isShutDown = true
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
    }
}

@Composable
fun rememberWordSpeaker(): WordSpeaker {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val speaker = remember { AndroidWordSpeaker(context) }

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

@Composable
fun PronunciationButton(
    wordId: String,
    lemma: String,
    languageID: String,
    speaker: WordSpeaker,
    modifier: Modifier = Modifier,
    diameter: Dp = 40.dp
) {
    val playbackState by speaker.state
    val isPlaying = playbackState is WordPlaybackState.Playing && (playbackState as WordPlaybackState.Playing).wordId == wordId
    val isLoading = playbackState is WordPlaybackState.Loading && (playbackState as WordPlaybackState.Loading).wordId == wordId
    val isError = playbackState is WordPlaybackState.Error && (playbackState as WordPlaybackState.Error).wordId == wordId

    val description = when {
        isPlaying -> stringResource(R.string.pronounce_stop)
        isLoading -> stringResource(R.string.pronounce_loading)
        isError -> stringResource(R.string.pronounce_retry)
        else -> stringResource(R.string.pronounce_play)
    }

    val iconColor = when {
        isPlaying -> MuralColors.Orange
        isError -> MuralColors.Red
        else -> MuralColors.Ink
    }

    val tintColor = when {
        isPlaying -> MuralColors.Peach.copy(alpha = 0.85f)
        isLoading -> MuralColors.Butter.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.78f)
    }

    Box(contentAlignment = Alignment.Center) {
        SoftRoundButton(
            symbol = MuralSymbol.Wave,
            description = description,
            onClick = {
                if (isPlaying) {
                    speaker.stop()
                } else {
                    speaker.speak(wordId, lemma, languageID)
                }
            },
            modifier = modifier.testTag("pronounce-button"),
            diameter = diameter,
            tint = tintColor
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(diameter * 0.55f),
                color = MuralColors.Orange,
                strokeWidth = 2.dp
            )
        }
    }
}
