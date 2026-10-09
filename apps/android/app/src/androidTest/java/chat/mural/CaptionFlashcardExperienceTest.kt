package chat.mural

import android.graphics.Bitmap
import java.io.File
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

internal fun SemanticsNodeInteraction.revealPageViewport(): SemanticsNodeInteraction {
    var ancestor = fetchSemanticsNode().parent
    while (ancestor != null) {
        if (ancestor.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) {
            performScrollTo()
            break
        }
        ancestor = ancestor.parent
    }
    return this
}

@RunWith(AndroidJUnit4::class)
class CaptionFlashcardExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var original: String
    @Before fun prepare() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            original = ArchiveCodec.encode(vm.archive)
            vm.updatePreferences(vm.archive.preferences.copy(hasOnboarded = true, aiConsentVersion = 1, learningLanguageID = "ru", meaningVisible = true))
        }
    }
    @Suppress("UNCHECKED_CAST") private fun state(name: String, value: Any?) {
        val field = MuralViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    @After fun restore() {
        if (!::original.isInitialized) return
        compose.runOnIdle { state("session", null); state("state", "idle"); state("meaning", ""); state("archive", ArchiveCodec.decode(original)) }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "feature-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun sample(id: String, lemma: String, meaning: String, day: Int = 0): SessionRecord {
        val date = nowSeconds() - day * 86400
        val record = SessionRecord(languageID = id, startedAt = date)
        record.append(Fragment(id = "word-$id-$lemma-$day", speaker = Speaker.user, text = lemma, startMS = 0, endMS = 1000))
        val p = record.passages.single()
        record.assessments += Assessment(p.id, p.revisionKey, Outcome.success, 1, "", "",
            listOf(WordProposal(lemma, meaning, lemma, EvidenceKind.independent, .95, p.fragments.map { it.id }, lemma, id)), createdAt = date)
        return record
    }
    private fun seedWords() {
        compose.runOnIdle {
            state("archive", vm.archive.copy(sessions = mutableListOf(sample("ru", "книга", "book"), sample("ru", "кофе", "coffee"), sample("nl", "fiets", "bicycle"))))
        }
        compose.onNodeWithTag("tab-words").performClick()
    }
    private fun position(value: String) {
        compose.onNodeWithTag("flashcard-pager").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }
    private fun accessibleMove(label: String) {
        val action = compose.onNodeWithTag("flashcard-pager").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.waitForIdle()
    }
    private fun presentForRecording() {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("experienceRecording") == "true") {
            compose.waitForIdle()
            Thread.sleep(1500)
        }
    }
    @Test fun flashcardsTriggerAlignsWithHeadingAndPracticePreservesVocabulary() {
        seedWords()
        val before = compose.runOnIdle { ArchiveCodec.encode(vm.archive) }
        val title = compose.onNodeWithText("Your words.").fetchSemanticsNode().boundsInRoot
        val trigger = compose.onNodeWithTag("open-flashcards").fetchSemanticsNode().boundsInRoot
        assertTrue(trigger.left >= title.right)
        assertEquals(title.center.y, trigger.center.y, 5f)
        compose.onNodeWithTag("open-flashcards").performClick()
        position("1 of 2")
        presentForRecording()
        compose.onNodeWithTag("flashcard-pager").performTouchInput {
            swipe(center, center.copy(x = center.x - width * .08f), durationMillis = 300)
        }
        position("1 of 2")
        compose.onNodeWithTag("flashcard-pager").performTouchInput { swipeRight() }
        position("1 of 2")
        compose.onNodeWithTag("flashcard-progress").assertDoesNotExist()
        compose.onNodeWithTag("flashcard-meaning", useUnmergedTree = true).assertDoesNotExist()
        val word = compose.onAllNodesWithTag("flashcard-word", useUnmergedTree = true).onFirst().fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val level = compose.onAllNodesWithTag("flashcard-proficiency", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue(level.height > 0)
        compose.onAllNodesWithTag("reveal-flashcard").onFirst().performClick()
        compose.onAllNodesWithTag("flashcard-meaning", useUnmergedTree = true).onFirst().assertIsDisplayed()
        capture("flashcards-revealed")
        presentForRecording()
        compose.onNodeWithTag("reveal-flashcard").performClick()
        compose.onNodeWithTag("flashcard-meaning", useUnmergedTree = true).assertDoesNotExist()
        presentForRecording()
        compose.onNodeWithTag("flashcard-pager").performTouchInput { swipeLeft() }
        position("2 of 2")
        presentForRecording()
        compose.onNodeWithTag("flashcard-pager").performTouchInput { swipeLeft() }
        position("2 of 2")
        compose.onNodeWithTag("flashcard-meaning", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("flashcard-pager").performTouchInput { swipeUp() }
        position("2 of 2")
        compose.onNodeWithTag("flashcard-pager").performTouchInput { swipeDown() }
        compose.onNodeWithTag("flashcards-modal").assertIsDisplayed()
        compose.onNodeWithTag("flashcard-pager").performTouchInput { swipeRight() }
        position("1 of 2")
        compose.onAllNodesWithTag("flashcard-word", useUnmergedTree = true).onFirst().assertTextEquals(word)
        compose.onNodeWithTag("flashcard-meaning", useUnmergedTree = true).assertDoesNotExist()
        accessibleMove("Next word")
        position("2 of 2")
        accessibleMove("Previous word")
        position("1 of 2")
        compose.onNodeWithTag("close-flashcards").performClick()
        compose.onNodeWithTag("words-screen").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, ArchiveCodec.encode(vm.archive)) }
    }
    @Test fun emptyWordsKeepPracticeDisabledAndSearchDoesNotShrinkTheDeck() {
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithTag("open-flashcards").assertIsNotEnabled()
        seedWords()
        compose.onNodeWithTag("open-flashcards").assertIsEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("кофе")
        compose.onNodeWithText("книга").assertDoesNotExist()
        compose.onNodeWithTag("open-flashcards").performClick()
        position("1 of 2")
    }
    @Test fun longMeaningScrollsWithoutChangingCards() {
        compose.runOnIdle {
            val meaning = "Used when a person wants to do something now, such as meeting a friend for coffee. It expresses a wish or preference in a relaxed conversation."
            state("archive", vm.archive.copy(sessions = mutableListOf(sample("ru", "книга", meaning), sample("ru", "кофе", "coffee"))))
        }
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithTag("open-flashcards").performClick()
        compose.onNodeWithTag("reveal-flashcard").performClick()
        val meaning = compose.onNodeWithTag("flashcard-meaning", useUnmergedTree = true)
        meaning.assertExists()
        compose.onNodeWithTag("flashcard-pager").performTouchInput { swipeUp() }
        position("1 of 2")
        compose.onNodeWithTag("flashcards-modal").assertIsDisplayed()
        compose.onNodeWithTag("close-flashcards").assertIsDisplayed()
    }
    private fun offset(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    @Test fun targetAndMeaningFollowPauseForStreamingAndResumeWithNextCaption() {
        val text = "Я люблю читать книги и разговаривать за чашкой кофе. ".repeat(10)
        fun show(fragment: Fragment) = compose.runOnIdle {
            state("session", SessionRecord(languageID = "ru", fragments = mutableListOf(fragment)))
            state("meaning", "I enjoy reading books and talking over a cup of coffee. ".repeat(10)); state("state", "active")
        }
        val first = Fragment(id = "caption-first", speaker = Speaker.assistant, text = text, startMS = 0, endMS = 1000)
        show(first)
        val mic = compose.onNodeWithTag("start-conversation").fetchSemanticsNode().boundsInRoot
        compose.waitUntil(5000) { offset("target-passage-scroll") > 20f && offset("meaning-passage-scroll") > 20f }
        capture("captions-following")
        compose.onNodeWithTag("target-passage-scroll").revealPageViewport()
        compose.onNodeWithTag("target-passage-scroll").performTouchInput { swipeDown() }
        var paused = offset("target-passage-scroll")
        compose.onNodeWithTag("meaning-passage-scroll").revealPageViewport()
        compose.onNodeWithTag("meaning-passage-scroll").performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue(vm.targetCaptionFollowing.interrupted); assertTrue(vm.meaningCaptionFollowing.interrupted) }
        compose.runOnIdle { vm.toggleMeaning() }
        compose.onNodeWithTag("meaning-passage-scroll").assertDoesNotExist()
        compose.runOnIdle { vm.toggleMeaning() }
        compose.runOnIdle { assertTrue(vm.meaningCaptionFollowing.interrupted) }
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithTag("tab-talk").performClick()
        compose.runOnIdle { assertTrue(vm.targetCaptionFollowing.interrupted); assertTrue(vm.meaningCaptionFollowing.interrupted) }
        paused = offset("target-passage-scroll")
        compose.runOnIdle { state("session", vm.session!!.copy(fragments = mutableListOf(first.copy(text = text + "Ещё немного.")))) }
        Thread.sleep(1000)
        assertEquals(paused, offset("target-passage-scroll"), 1f)
        assertEquals(mic.bottom, compose.onNodeWithTag("start-conversation").fetchSemanticsNode().boundsInRoot.bottom, 2f)
        show(first.copy(id = "caption-next", startMS = 5000))
        compose.waitUntil(5000) { offset("target-passage-scroll") > 20f }
    }
    @Test fun dutchAndRussianGreetingsAppearInTheActualTalkScreen() {
        for ((id, greeting) in listOf("nl" to "Hoi!", "ru" to "Привет!")) {
            compose.runOnIdle { state("session", null); state("state", "idle"); vm.updatePreferences(vm.archive.preferences.copy(learningLanguageID = id, meaningLanguage = if (id == "nl") "Russian" else "Dutch")) }
            compose.onNodeWithTag("target-caption").assertTextEquals(greeting)
            compose.onNodeWithTag("meaning-caption").assertTextEquals(if (id == "nl") "Привет!" else "Hoi!")
        }
    }
}
