package chat.mural

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.*
import chat.mural.ondevice.OnDeviceSpeechOutput
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * A Typed-only Module (Roman Konkani) offers the learner no voice on any Conversation Provider: no
 * microphone, no listening or mute state, and typing is the learner's whole conversation. Choosing
 * another Language Module brings voice back. The teacher's side may be read aloud through an
 * opt-in Approximate Voice where a module has one — Jamaican Patois does, Roman Konkani does not.
 */
@RunWith(AndroidJUnit4::class)
class TypedOnlyModuleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var original: String
    private lateinit var originalProvider: ConversationProvider

    @Before fun prepare() {
        assertEquals("chat.mural.android.uitest", compose.activity.packageName)
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            original = ArchiveCodec.encode(vm.archive)
            originalProvider = vm.conversationProvider
            vm.updatePreferences(vm.archive.preferences.copy(hasOnboarded = true, aiConsentVersion = 1,
                learningLanguageID = "kok-Latn"))
        }
    }

    @After fun restore() {
        if (!::original.isInitialized) return
        compose.runOnIdle {
            state("session", null); state("state", "idle")
            vm.resetConversation(); vm.clearTypedReplyError(); vm.dismissError()
            state("conversationProvider", originalProvider)
            val restored = ArchiveCodec.decode(original); state("archive", restored)
            vm.updatePreferences(restored.preferences)
        }
        // The Approximate Voice opt-in is persisted, so it must not leak into the next test's VM.
        compose.activity.getSharedPreferences("mural_conversation_providers", Context.MODE_PRIVATE)
            .edit().putBoolean("approximate_voice", false).commit()
    }

    /** Stands in for the phone's engine, so a spoken teacher is silent and deterministic here. */
    private class FakeApproximateVoice : OnDeviceSpeechOutput {
        val spoken = mutableListOf<Pair<String, String>>()
        override val speaking = false
        override val level = 0.0
        override fun speak(sentence: String, locale: String, queueAdd: Boolean) { spoken += sentence to locale }
        override fun stop() = Unit
        override fun close() = Unit
    }

    /** Sets a plain VM field — [state] targets the `by mutableStateOf` delegates instead. */
    private fun field(name: String, value: Any?) {
        MuralViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    @Suppress("UNCHECKED_CAST") private fun state(name: String, value: Any?) {
        val field = MuralViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }

    private fun tapType() {
        val typeButton = compose.onNodeWithTag("talk-type-button")
        if (!typeButton.isDisplayed()) typeButton.performScrollTo()
        typeButton.performClick()
    }

    @Test fun noProviderOffersAMicrophoneForATypedOnlyModuleAndStartRefusesVoice() {
        for (provider in ConversationProvider.entries) {
            compose.runOnIdle { state("conversationProvider", provider); vm.start() }
            compose.onNodeWithTag("start-conversation").assertDoesNotExist()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.talk_mic_start_desc)).assertDoesNotExist()
            compose.onNodeWithTag("talk-orb").assertIsDisplayed()
            compose.onNodeWithTag("conversation-status").assertTextEquals(compose.activity.getString(R.string.talk_status_idle))
            tapType()
            compose.onNodeWithTag("typed-reply-input").assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_close)).performClick()
            compose.runOnIdle {
                assertEquals(provider.name, provider, vm.conversationProvider)
                assertFalse(vm.isVoiceSession)
                assertEquals(provider.name, "idle", vm.state)
                assertNull(provider.name, vm.session)
                assertNull(provider.name, vm.error)
            }
        }
    }

    @Test fun muralMinutesExplainsThatATypedOnlyModuleNeedsAnotherProviderWithoutSwitchingIt() {
        compose.runOnIdle { state("conversationProvider", ConversationProvider.HOSTED_MINUTES) }
        tapType()
        compose.onNodeWithTag("typed-reply-input").performTextInput("Mhaka ek kafe zai.")
        compose.onNodeWithTag("typed-reply-send").performClick()
        compose.onNodeWithTag("typed-reply-error")
            .assertTextEquals(compose.activity.getString(R.string.hosted_typed_only_provider)).assertIsDisplayed()
        // The learner's draft survives, and Mural never picks a provider for them.
        compose.onNodeWithTag("typed-reply-input").assertTextContains("Mhaka ek kafe zai.")
        compose.runOnIdle {
            assertEquals(ConversationProvider.HOSTED_MINUTES, vm.conversationProvider)
            assertNull(vm.session); assertNull(vm.error); assertEquals("idle", vm.state)
        }
    }

    @Test fun anActiveTypedConversationReadsAsWrittenAndEndsManually() {
        compose.runOnIdle {
            state("conversationProvider", ConversationProvider.PERSONAL_KEY)
            state("session", SessionRecord(languageID = "kok-Latn"))
            state("state", "active")
        }
        compose.onNodeWithTag("conversation-status").assertTextEquals(compose.activity.getString(R.string.talk_status_written))
        compose.onNodeWithTag("start-conversation").assertDoesNotExist()
        compose.onNodeWithTag("talk-type-button").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.talk_end_label)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("ended", vm.state); assertFalse(vm.isVoiceSession); assertNull(vm.error)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.talk_transcript_label)).assertIsDisplayed()
        // A new typed conversation stays one tap away from the ended one.
        compose.onNodeWithTag("talk-type-button").assertIsDisplayed()
    }

    @Test fun choosingAnotherLanguageModuleBringsTheMicrophoneBackImmediately() {
        compose.onNodeWithTag("start-conversation").assertDoesNotExist()
        compose.runOnIdle { vm.selectLanguage("nb") }
        compose.onNodeWithTag("start-conversation").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.talk_mic_start_desc)).assertExists()
        compose.runOnIdle { vm.selectLanguage("kok-Latn") }
        compose.onNodeWithTag("start-conversation").assertDoesNotExist()
        compose.onNodeWithTag("talk-type-button").assertIsDisplayed()
    }

    /** Jamaican Patois joins Roman Konkani on the same mechanism: no per-module voice code exists. */
    @Test fun everyTypedOnlyModuleIsTypedOnEveryProviderWithTheTypeButtonReachableFromIdle() {
        assertEquals(setOf("kok-Latn", "jam"), AndroidLanguages.typedOnlyIDs)
        for (module in AndroidLanguages.all.filter { it.isTypedOnly }) {
            compose.runOnIdle { vm.selectLanguage(module.id) }
            compose.runOnIdle { assertEquals(module.id, vm.language.id) }
            for (provider in ConversationProvider.entries) {
                compose.runOnIdle { state("conversationProvider", provider); vm.start() }
                compose.onNodeWithTag("start-conversation").assertDoesNotExist()
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.talk_mic_start_desc)).assertDoesNotExist()
                compose.onNodeWithTag("talk-orb").assertIsDisplayed()
                compose.onNodeWithTag("conversation-status").assertTextEquals(compose.activity.getString(R.string.talk_status_idle))
                tapType()
                compose.onNodeWithTag("typed-reply-input").assertIsDisplayed()
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_close)).performClick()
                compose.runOnIdle {
                    assertEquals("${module.id}/${provider.name}", provider, vm.conversationProvider)
                    assertFalse("${module.id}/${provider.name}", vm.isVoiceSession)
                    assertEquals("${module.id}/${provider.name}", "idle", vm.state)
                    assertNull("${module.id}/${provider.name}", vm.session)
                    assertNull("${module.id}/${provider.name}", vm.error)
                }
            }
        }
    }

    /** The Approximate Voice reads the teacher, never the learner: the microphone stays absent. */
    @Test fun anApproximateVoiceReadsThePatwaTeacherAloudAndStillOffersNoMicrophoneOnAnyProvider() {
        compose.runOnIdle { vm.selectLanguage("jam"); vm.setApproximateVoice(true) }
        for (provider in ConversationProvider.entries) {
            compose.runOnIdle { state("conversationProvider", provider); vm.start() }
            compose.onNodeWithTag("start-conversation").assertDoesNotExist()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.talk_mic_start_desc)).assertDoesNotExist()
            compose.onNodeWithTag("talk-type-button").assertIsDisplayed()
            // Talk keeps its idle home screen: the approximation is named once a conversation runs.
            compose.onNodeWithTag("conversation-status").assertTextEquals(compose.activity.getString(R.string.talk_status_idle))
            compose.runOnIdle {
                assertTrue(provider.name, vm.speaksApproximateVoice)
                assertFalse(provider.name, vm.isVoiceSession)
                assertEquals(provider.name, "idle", vm.state)
                assertNull(provider.name, vm.session)
                assertNull(provider.name, vm.error)
            }
        }
        compose.runOnIdle {
            state("conversationProvider", ConversationProvider.PERSONAL_KEY)
            state("session", SessionRecord(languageID = "jam"))
            state("state", "active")
        }
        compose.onNodeWithTag("conversation-status")
            .assertTextEquals(compose.activity.getString(R.string.talk_status_written_approximate))
        compose.onNodeWithTag("start-conversation").assertDoesNotExist()
        compose.onNodeWithTag("talk-type-button").assertIsDisplayed()
        // Injected before anything is spoken, so no real TextToSpeech engine is ever constructed.
        val patwa = FakeApproximateVoice()
        compose.runOnIdle {
            field("approximateOutput", patwa)
            vm.speakApproximateVoice("Wah gwaan! Mi a-go a di market. Di pikni dem a play.")
        }
        assertEquals(listOf("Wah gwaan!", "Mi a-go a di market.", "Di pikni dem a play."), patwa.spoken.map { it.first })
        assertEquals(listOf("en-JM", "en-JM", "en-JM"), patwa.spoken.map { it.second })
        // Roman Konkani is unchanged: the opt-in persists, but no stand-in voice exists for it.
        compose.runOnIdle { state("state", "idle"); vm.resetConversation(); vm.selectLanguage("kok-Latn") }
        compose.runOnIdle {
            assertTrue(vm.approximateVoice)
            assertFalse(vm.speaksApproximateVoice)
            state("session", SessionRecord(languageID = "kok-Latn")); state("state", "active")
        }
        compose.onNodeWithTag("conversation-status").assertTextEquals(compose.activity.getString(R.string.talk_status_written))
        compose.onNodeWithTag("start-conversation").assertDoesNotExist()
        val konkani = FakeApproximateVoice()
        compose.runOnIdle {
            field("approximateOutput", konkani)
            vm.speakApproximateVoice("Mhaka ek kafe zai.")
        }
        assertTrue(konkani.spoken.toString(), konkani.spoken.isEmpty())
    }
}
