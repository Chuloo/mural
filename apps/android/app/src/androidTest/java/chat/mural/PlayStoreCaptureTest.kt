package chat.mural

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.*
import chat.mural.ui.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/** English store UI with Spanish learning fixtures, plus a Spanish layout regression. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class PlayStoreCaptureTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val captureLocale = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val manager = instrumentation.targetContext.getSystemService(LocaleManager::class.java)
            val previous = manager.applicationLocales
            try {
                val tag = if (description.methodName.startsWith("spanish")) "es-ES" else "en-US"
                instrumentation.runOnMainSync { manager.applicationLocales = LocaleList.forLanguageTags(tag) }
                instrumentation.waitForIdleSync()
                base.evaluate()
            } finally {
                instrumentation.runOnMainSync { manager.applicationLocales = previous }
                instrumentation.waitForIdleSync()
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(captureLocale).around(compose)
    private lateinit var vm: MuralViewModel
    private lateinit var original: Preferences
    private val records = vocabularyFixtures()

    @Before fun prepare() {
        assertEquals("chat.mural.android.uitest", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        assertTrue(BuildConfig.MANAGED_API_ORIGIN.isBlank())
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            original = vm.archive.preferences.copy()
            assertTrue(vm.importData(ArchiveCodec.encode(Archive(sessions = records.toMutableList()))))
            vm.updatePreferences(original.copy(learningLanguageID = "es", meaningLanguage = "English",
                meaningVisible = true, hasOnboarded = true, aiConsentVersion = null))
        }
    }
    @After fun restore() {
        if (!::vm.isInitialized) return
        compose.runOnIdle {
            fixtureState("session", null)
            fixtureState("state", "idle")
            fixtureState("meaning", "")
            MuralViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }.setBoolean(vm, false)
            vm.chooseTheme(null)
            records.forEach { vm.deleteSession(it.id) }
            vm.updatePreferences(original)
        }
    }

    // No production setter or provider call is added to stage the conversation. These are
    // synthetic render inputs, not evidence of a completed microphone or billing session.
    @Suppress("UNCHECKED_CAST")
    private fun fixtureState(name: String, value: Any?) {
        val field = MuralViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(600, 5_000)
        val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
        savePlayBitmap(bitmap, name)
    }

    private fun assertCaptionFullyVisible(tag: String) {
        val node = compose.onNodeWithTag(tag)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout for $tag", layouts.isNotEmpty())
        val visible = node.fetchSemanticsNode().boundsInRoot.height
        assertTrue("$tag clipped: $visible visible pixels for ${layouts.first().size.height} text pixels",
            visible + 1 >= layouts.first().size.height)
    }

    private fun assertControlFullyVisible(tag: String) {
        val node = compose.onNodeWithTag(tag)
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        val fullHeight = (bounds.bottom - bounds.top).value * compose.density.density
        assertTrue("$tag is clipped by its scroll viewport",
            node.fetchSemanticsNode().boundsInRoot.height + 1 >= fullHeight)
    }

    @Test fun englishStoreScreensForSpanishLearning() {
        compose.onNodeWithTag("target-caption").assertTextEquals("¡Hola!")
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
        assertCaptionFullyVisible("target-caption")
        assertCaptionFullyVisible("meaning-caption")
        capture("01-greeting.png")

        showConversationFixture()
        compose.onNodeWithTag("target-caption").assertTextEquals("Claro. ¿Lo quieres con leche?")
        compose.onNodeWithTag("meaning-caption").assertTextEquals("Of course. Would you like it with milk?")
        assertCaptionFullyVisible("target-caption")
        assertCaptionFullyVisible("meaning-caption")
        capture("02-conversation.png")
        compose.runOnIdle {
            fixtureState("session", null)
            fixtureState("state", "idle")
            fixtureState("meaning", "")
            fixtureState("outputLevel", 0.0)
            MuralViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }.setBoolean(vm, false)
            vm.chooseTheme(null)
        }

        compose.onNodeWithTag("tab-topics").performClick()
        compose.onNodeWithTag("topics-screen").assertIsDisplayed()
        capture("03-themes.png")
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithText("café", substring = false).assertExists()
        capture("04-words.png")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        capture("06-settings.png")
        compose.onNodeWithTag("settings-done").performClick()
        compose.runOnIdle { vm.updatePreferences(vm.archive.preferences.copy(hasOnboarded = false)) }
        assertControlFullyVisible("onboarding-language-picker")
        capture("05-languages.png")
        compose.onNodeWithTag("onboarding-continue").performClick()
        assertControlFullyVisible("onboarding-meaning-picker")
        assertCaptionFullyVisible("onboarding-meaning-example")
    }

    @Test fun spanishCompactCaptionsRemainReadable() {
        assertCaptionFullyVisible("target-caption")
        assertCaptionFullyVisible("meaning-caption")
        showConversationFixture()
        compose.onNodeWithTag("conversation-status").assertTextEquals("Te escucho")
        assertCaptionFullyVisible("target-caption")
        assertCaptionFullyVisible("meaning-caption")
    }

    @Test fun longSpanishReplyKeepsMeaningVisibleAndBothPassagesCanScroll() {
        capture("long-reply-before-greeting.png")
        val reply = "¡Hola, Alex! Me alegra saber que estás bien. Tu frase está muy bien; también podrías decir: «Hola, me llamo Alex y estoy bien». ¿De dónde eres?"
        val meaning = "Hello, Alex! I'm glad you're well. Your sentence is very good; you could also say: “Hi, my name is Alex and I'm well.” Where are you from?"
        showConversationFixture(reply, meaning)

        fun assertFirstLineVisible(tag: String) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val firstLineHeight = layouts.single().let { it.getLineBottom(0) - it.getLineTop(0) }
            assertTrue("$tag must show at least one complete line",
                node.fetchSemanticsNode().boundsInRoot.height + 1 >= firstLineHeight)
        }
        fun assertWholePassageReachable(scrollTag: String, captionTag: String) {
            val passage = compose.onNodeWithTag(scrollTag)
            val range = passage.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.maxValue() == 0f) {
                // Wider/taller devices can show the entire translation without scrolling.
                assertCaptionFullyVisible(captionTag)
            } else {
                passage.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue()) }
                compose.waitForIdle()
                val after = passage.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue("The end of $captionTag must be reachable", after.value() + 1f >= after.maxValue())
            }
        }
        assertFirstLineVisible("target-caption")
        assertFirstLineVisible("meaning-caption")
        val target = compose.onNodeWithTag("target-passage-scroll")
        val translated = compose.onNodeWithTag("meaning-passage-scroll")
        val targetBounds = target.fetchSemanticsNode().boundsInRoot
        val meaningBounds = translated.fetchSemanticsNode().boundsInRoot
        val nav = compose.onNodeWithTag("floating-navigation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Target and meaning must have separate visible regions", targetBounds.bottom < meaningBounds.top)
        assertTrue("Meaning must stay above the floating navigation", meaningBounds.bottom < nav.top)
        capture("long-reply-with-meaning.png")

        assertWholePassageReachable("target-passage-scroll", "target-caption")
        assertEquals("Scrolling the target must leave meaning in place", meaningBounds, translated.fetchSemanticsNode().boundsInRoot)
        assertFirstLineVisible("meaning-caption")

        assertWholePassageReachable("meaning-passage-scroll", "meaning-caption")
        assertEquals(nav, compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot)
        assertControlFullyVisible("start-conversation")
        capture("long-reply-scrolled.png")
    }

    private fun showConversationFixture(
        reply: String = "Claro. ¿Lo quieres con leche?",
        meaning: String = "Of course. Would you like it with milk?",
    ) {
        val conversation = SessionRecord(languageID = "es", title = "Un café", fragments = mutableListOf(
            Fragment(speaker = Speaker.user, text = "Un café, por favor.", startMS = 0, endMS = 1800),
            Fragment(speaker = Speaker.assistant, text = reply, startMS = 2400, endMS = 5100)))
        compose.runOnIdle {
            vm.chooseTheme(vm.language.themes.first { it.id == "coffee" })
            fixtureState("session", conversation)
            fixtureState("meaning", meaning)
            MuralViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }.setBoolean(vm, true)
            fixtureState("state", "active")
            fixtureState("outputLevel", .16)
        }
    }
}

/** Listing artwork rendered from the same native Brand and MuralOrb as the app. */
@RunWith(AndroidJUnit4::class)
class PlayFeatureGraphicTest {
    @get:Rule val compose = createComposeRule()
    @Test fun renderFeatureGraphic() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MuralTheme {
                    Box(Modifier.requiredSize(1024.dp, 500.dp).background(MuralColors.Cream).testTag("play-feature")) {
                        Column(Modifier.offset(80.dp, 96.dp)) {
                            Brand()
                            Spacer(Modifier.height(35.dp))
                            Text("It starts with\na hello.", color = MuralColors.Ink,
                                style = TextStyle(fontFamily = MuralRounded, fontSize = 57.sp, lineHeight = 65.sp,
                                    fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp))
                            Spacer(Modifier.height(22.dp))
                            Text("Learn by talking.", color = MuralColors.Secondary,
                                style = TextStyle(fontFamily = MuralRounded, fontSize = 23.sp))
                        }
                        MuralOrb(modifier = Modifier.offset(634.dp, 110.dp).size(280.dp))
                    }
                }
            }
        }
        val bitmap = compose.onNodeWithTag("play-feature").captureToImage().asAndroidBitmap()
        assertEquals(1024, bitmap.width)
        assertEquals(500, bitmap.height)
        savePlayBitmap(bitmap, "feature-graphic.png")
    }
}

private fun savePlayBitmap(bitmap: Bitmap, name: String) {
    // The full canvas is opaque; encode its existing RGB pixels as Play's 24-bit PNG.
    bitmap.setHasAlpha(false)
    val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "play-store").apply { mkdirs() }
    File(dir, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}

private fun vocabularyFixtures(): List<SessionRecord> {
    val words = listOf("café" to "coffee", "leche" to "milk", "pan" to "bread", "gracias" to "thank you",
        "mañana" to "tomorrow", "viajar" to "to travel")
    val line = "Un café con leche y pan, por favor. Muchas gracias. Mañana quiero viajar."
    return listOf(8 to 2, 2 to 4, 0 to 6).mapIndexed { index, (days, count) ->
        val whenSpoken = nowSeconds() - days * 86400 - 600
        val fragment = Fragment(speaker = Speaker.user, text = line, startMS = 0, endMS = 6000, receivedAt = whenSpoken)
        SessionRecord(languageID = "es", startedAt = whenSpoken, endedAt = whenSpoken + 60,
            themeID = if (index == 1) "groceries" else "coffee", title = "Práctica de español",
            fragments = mutableListOf(fragment), usageFinal = true).apply {
            assessments += Assessment(fragment.id, "${fragment.id}:0", Outcome.success, 1,
                "Keep practising familiar everyday phrases.", "Everyday conversation",
                words.take(count).map { (form, meaning) -> WordProposal(form, meaning, form, EvidenceKind.independent,
                    .98, listOf(fragment.id), line, "es") }, createdAt = whenSpoken,
                context = if (index == 1) "groceries" else "coffee")
        }
    }
}
