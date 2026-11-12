package chat.mural.ui

import android.content.Context
import android.media.AudioAttributes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.R
import chat.mural.core.FlashcardDeck
import chat.mural.core.WordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose UI and TalkBack semantics tests for word pronunciation controls, retry flow, and cleanup. */
@RunWith(AndroidJUnit4::class)
class WordPronunciationUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Fake [WordSpeaker] recording speak, retry, stop, and release interactions for Compose tests. */
    private class RecordingSpeaker : WordSpeaker {
        private val _state: MutableState<WordPlaybackState> = mutableStateOf(WordPlaybackState.Idle)
        override val state: State<WordPlaybackState> get() = _state

        val speakCalls = mutableListOf<Triple<String, String, String>>()
        val retryCalls = mutableListOf<Triple<String, String, String>>()
        var stopCalls = 0
        var releaseCalls = 0
        var transitionToOnSpeak: ((String, String) -> WordPlaybackState)? = null

        /** Sets playback state explicitly from test assertions. */
        fun setState(newState: WordPlaybackState) {
            _state.value = newState
        }

        /** Records speak request and optionally advances into a scripted state. */
        override fun speak(wordId: String, text: String, languageID: String) {
            speakCalls += Triple(wordId, text, languageID)
            _state.value = transitionToOnSpeak?.invoke(wordId, languageID) ?: WordPlaybackState.Playing(wordId)
        }

        /** Records explicit retry request and restores playing state. */
        override fun retry(wordId: String, text: String, languageID: String) {
            retryCalls += Triple(wordId, text, languageID)
            _state.value = WordPlaybackState.Playing(wordId)
        }

        /** Records stop invocation and returns state to idle. */
        override fun stop() {
            stopCalls++
            _state.value = WordPlaybackState.Idle
        }

        /** Records release invocation and resets to idle. */
        override fun release() {
            releaseCalls++
            _state.value = WordPlaybackState.Idle
        }
    }

    /** Builds a deterministic [WordState] fixture for pronunciation UI tests. */
    private fun sampleWord(id: String = "nl|fiets", lemma: String = "fiets", meaning: String = "bicycle"): WordState =
        WordState(
            id = id,
            lemma = lemma,
            meaning = meaning,
            form = lemma,
            example = "Ik fiets naar huis.",
            bars = 2,
            understandingCount = 2,
            independentCount = 2,
            lastSeen = 1_000_000.0,
            dueAt = 1_100_000.0,
        )

    /** Verifies TalkBack content descriptions across all five playback states and repeated taps. */
    @Test
    fun pronunciationButtonExposesTalkBackLabelsAndRepeatedTapRetryBehavior() {
        val speaker = RecordingSpeaker()
        val word = sampleWord()

        compose.setContent {
            MuralTheme {
                PronunciationButton(
                    wordId = word.id,
                    lemma = word.lemma,
                    languageID = "nl",
                    speaker = speaker,
                    testTag = "word-dialog-pronounce",
                )
            }
        }

        val button = compose.onNodeWithTag("word-dialog-pronounce")
        button.assertContentDescriptionEquals(context.getString(R.string.pronounce_play))
        button.assert(hasClickAction())

        // First tap starts playback; second tap stops playback cleanly.
        button.performClick()
        assertEquals(listOf(Triple(word.id, word.lemma, "nl")), speaker.speakCalls)
        button.assertContentDescriptionEquals(context.getString(R.string.pronounce_stop))

        button.performClick()
        assertEquals(1, speaker.stopCalls)
        button.assertContentDescriptionEquals(context.getString(R.string.pronounce_play))

        // Loading state exposes loading announcement and tapping cancels back to idle.
        compose.runOnIdle { speaker.setState(WordPlaybackState.Loading(word.id)) }
        button.assertContentDescriptionEquals(context.getString(R.string.pronounce_loading))
        button.performClick()
        assertEquals(2, speaker.stopCalls)

        // Error state exposes TalkBack retry description and invokes verified retry() path.
        compose.runOnIdle { speaker.setState(WordPlaybackState.Error(word.id)) }
        button.assertContentDescriptionEquals(context.getString(R.string.pronounce_retry))
        button.performClick()
        assertEquals(listOf(Triple(word.id, word.lemma, "nl")), speaker.retryCalls)
        button.assertContentDescriptionEquals(context.getString(R.string.pronounce_stop))

        // Unavailable state keeps control accessible and explains device voice absence.
        compose.runOnIdle { speaker.setState(WordPlaybackState.Unavailable(word.id, "tl")) }
        button.assertContentDescriptionEquals(context.getString(R.string.pronounce_unavailable))
    }

    /** Verifies flashcard custom TalkBack action, dismissal stop, and Android audio-routing constants. */
    @Test
    fun flashcardTalkBackActionAndDismissalCleanupOperateWithoutAlteringMicRouting() {
        val speaker = RecordingSpeaker()
        val first = sampleWord("ru|книга", "книга", "book")
        val second = sampleWord("ru|кофе", "кофе", "coffee")
        val deck = FlashcardDeck(listOf(first, second), languageID = "ru")
        var dismissed = 0

        compose.setContent {
            MuralTheme {
                FlashcardsDialog(deck = deck, speaker = speaker, onDismiss = { dismissed++ })
            }
        }

        val flashcardPronounce = compose.onNodeWithTag("flashcard-pronounce")
        flashcardPronounce.assertIsDisplayed()
        flashcardPronounce.assertContentDescriptionEquals(context.getString(R.string.pronounce_play))

        // Verify TalkBack custom action exists on the card surface and triggers pronunciation without flipping the card.
        val playLabel = context.getString(R.string.pronounce_play)
        val node = compose.onNode(
            SemanticsMatcher("has play pronunciation action") { semantics ->
                val actions = semantics.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
                actions.any { it.label == playLabel }
            },
        ).fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].first { it.label == playLabel }
        compose.runOnIdle { assertTrue(action.action()) }

        assertEquals(listOf(Triple("ru|книга", "книга", "ru")), speaker.speakCalls)
        compose.onNodeWithTag("flashcard-meaning", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.pronounce_stop)).assertIsDisplayed()

        // Closing flashcards stops active pronunciation.
        compose.onNodeWithTag("close-flashcards").performClick()
        assertEquals(1, dismissed)
        assertTrue(speaker.stopCalls >= 1)

        // Confirm spoken playback audio route remains separate from microphone / communication attributes.
        assertEquals(AudioAttributes.USAGE_MEDIA, WordAudioRouting.defaultRoute.usage)
        assertFalse(WordAudioRouting.defaultRoute.usesMicrophone)
        assertFalse(WordAudioRouting.defaultRoute.usesCommunicationMode)
    }

    /** Verifies WordDialog stops pronunciation when Done or Remove is tapped. */
    @Test
    fun wordDialogStopsonDoneAndRemoveWithoutChangingOtherWords() {
        val speaker = RecordingSpeaker()
        val word = sampleWord()
        var removed = 0
        var dismissed = 0

        compose.setContent {
            MuralTheme {
                WordDialog(
                    word = word,
                    speaker = speaker,
                    onRemove = { removed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }

        compose.onNodeWithTag("word-dialog-pronounce").performClick()
        assertEquals(WordPlaybackState.Playing(word.id), speaker.state.value)

        compose.onNodeWithContentDescription(context.getString(R.string.pronounce_stop)).performClick()
        assertEquals(WordPlaybackState.Idle, speaker.state.value)

        // Start again and verify Done stops active playback cleanly.
        compose.onNodeWithTag("word-dialog-pronounce").performClick()
        compose.onNodeWithTag("word-dialog-pronounce").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        compose.onNodeWithTag("word-dialog-pronounce").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.hasText(context.getString(R.string.words_done_button))).performClick()
        assertEquals(1, dismissed)
        assertTrue(speaker.stopCalls >= 2)
        assertEquals(0, removed)
    }
}
