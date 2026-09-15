package chat.mural

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.*
import chat.mural.network.APIClient
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real activity, caption taps and request construction, with offline HTTP responses in the isolated test app. */
@RunWith(AndroidJUnit4::class)
class CaptionParityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var original: String
    private lateinit var originalAPI: APIClient
    private var originalHasKey = false
    private val requests = LinkedBlockingQueue<String>()
    @Volatile private var responseGate: CountDownLatch? = null
    @Volatile private var responseCode = 200
    @Volatile private var response = "This word is explained in the context of the sentence."
    private val recording get() = InstrumentationRegistry.getArguments().getString("record") == "true"
    private var recorder: ParcelFileDescriptor? = null

    @Before fun prepare() {
        assertEquals("chat.mural.android.uitest", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            original = ArchiveCodec.encode(vm.archive); originalHasKey = vm.hasKey
            val field = MuralViewModel::class.java.getDeclaredField("api").apply { isAccessible = true }
            originalAPI = field.get(vm) as APIClient
            // An application interceptor returns before DNS or a socket can be used. No provider key or network is involved.
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                requests.add(body)
                val reply = response
                val status = responseCode
                responseGate?.await(15, TimeUnit.SECONDS)
                val payload = buildJsonObject {
                    put("status", "completed")
                    put("output", buildJsonArray { add(buildJsonObject {
                        put("type", "message"); put("content", buildJsonArray { add(buildJsonObject {
                            put("type", "output_text"); put("text", reply)
                        }) })
                    }) })
                    put("usage", buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) })
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message(if (status == 200) "OK" else "Fixture error")
                    .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
            }.build()
            field.set(vm, APIClient("fixture-only", client, "https://offline.invalid/v1/".toHttpUrl()))
            state("hasKey", true)
            vm.updatePreferences(vm.archive.preferences.copy(learningLanguageID = "zh", meaningLanguage = "English",
                meaningVisible = true, hasOnboarded = true, aiConsentVersion = 1))
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        val field = MuralViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    @After fun restore() {
        responseGate?.countDown()
        finishRecording()
        if (!::vm.isInitialized || !::original.isInitialized) return
        compose.runOnIdle {
            vm.clearLookup(); state("session", null); state("state", "idle"); state("meaning", "")
            MuralViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }.setBoolean(vm, false)
            MuralViewModel::class.java.getDeclaredField("api").apply { isAccessible = true }.set(vm, originalAPI)
            state("hasKey", originalHasKey)
            val restored = ArchiveCodec.decode(original)
            state("archive", restored); vm.updatePreferences(restored.preferences)
        }
    }
    private fun show(language: String, text: String, meaning: String) {
        compose.runOnIdle {
            vm.clearLookup(); state("session", null); state("state", "idle")
            vm.updatePreferences(vm.archive.preferences.copy(learningLanguageID = language))
            state("session", SessionRecord(languageID = language, title = "Caption verification", fragments = mutableListOf(
                Fragment(speaker = Speaker.assistant, text = text, startMS = 1500, endMS = 5000))))
            state("meaning", meaning)
            // Written fixture: the microphone is off throughout the recording.
            state("state", "active")
        }
        compose.waitForIdle()
        if (language == "zh") compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("pinyin-reading").fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val auto = InstrumentationRegistry.getInstrumentation().uiAutomation
        auto.waitForIdle(500, 5000)
        val dir = File(compose.activity.filesDir, "caption-parity").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { auto.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
    private fun assertTextVisible(tag: String, whole: Boolean) {
        val layout = textLayout(tag)
        val required = if (whole) layout.size.height.toFloat() else layout.getLineBottom(0) - layout.getLineTop(0)
        val visible = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
        assertTrue("$tag: $visible pixels visible, $required required", visible + 1 >= required)
    }
    private fun openWord(word: String, sentence: String) {
        val layout = textLayout("target-caption")
        val index = sentence.indexOf(word)
        assertTrue(index >= 0)
        val bounds = layout.getBoundingBox(index)
        compose.onNodeWithTag("target-caption").performTouchInput { click(Offset(bounds.center.x, bounds.center.y)) }
        compose.onNodeWithTag("word-lookup-word").assertTextEquals(word)
        compose.onNodeWithTag("word-lookup-sentence").assertTextEquals(sentence)
    }
    private fun tap(word: String, sentence: String) {
        openWord(word, sentence)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("word-lookup-explanation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("word-lookup-explanation").assertTextEquals(response)
        val request = Json.parseToJsonElement(checkNotNull(requests.poll(2, TimeUnit.SECONDS))).jsonObject
        assertTrue(request.toString(), request.toString().contains("Selected: $word"))
        assertTrue(request.toString(), request.toString().contains(sentence))
        assertTrue(request.toString(), request.toString().contains("context of its sentence"))
        assertFalse(request.getValue("store").jsonPrimitive.boolean)
        assertTrue(request.getValue("instructions").jsonPrimitive.content.contains(vm.language.name))
    }
    private fun pause() {
        compose.waitForIdle()
        if (recording) Thread.sleep(3500)
    }
    private fun startRecording(language: String) {
        if (!recording) return
        recorder = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screenrecord --size 720x1616 --bit-rate 4000000 --time-limit 90 /sdcard/Download/mural-$language.mp4")
        Thread.sleep(1500)
    }
    private fun finishRecording() {
        if (recorder == null) return
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pkill -INT screenrecord")).use { it.readBytes() }
        Thread.sleep(800); recorder?.close(); recorder = null
    }

    @Test fun mandarinCaptionToggleAndContextualLookupMatchIOS() {
        val sentence = "我想去银行，然后去旅行。"
        show("zh", sentence, "I want to go to the bank, then travel.")
        response = "银行 means ‘bank’ here: the place you want to visit. 我想去银行 means ‘I want to go to the bank’."
        assertTextVisible("target-caption", true); assertTextVisible("meaning-caption", true)
        startRecording("mandarin"); pause(); capture("mandarin-caption")
        compose.onNodeWithTag("pinyin-toggle").performScrollTo().performClick(); pause()
        compose.onNodeWithTag("pinyin-reading").assertDoesNotExist()
        compose.onNodeWithTag("pinyin-toggle").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pinyin-reading").fetchSemanticsNodes().isNotEmpty() }
        pause()
        compose.onNodeWithTag("target-caption").performScrollTo()
        tap("银行", sentence); pause(); assertTextVisible("word-lookup-explanation", true); capture("mandarin-lookup")
        compose.onNodeWithTag("word-lookup-close").performClick(); pause()
        compose.onNodeWithTag("word-lookup-sheet").assertDoesNotExist()
    }

    @Test fun spanishCaptionAndContextualLookupMatchIOS() {
        val sentence = "Quiero un café con leche."
        show("es", sentence, "I want a coffee with milk.")
        response = "Café means ‘coffee’ in this sentence. Un café con leche is a coffee with milk."
        compose.onNodeWithTag("pinyin-toggle").assertDoesNotExist()
        assertTextVisible("target-caption", true); assertTextVisible("meaning-caption", true)
        startRecording("spanish"); pause(); capture("spanish-caption")
        tap("café", sentence); pause(); assertTextVisible("word-lookup-explanation", true); capture("spanish-lookup")
        compose.onNodeWithTag("word-lookup-close").performClick(); pause()
    }

    @Test fun allEightLanguagesSendTheTappedWordWithItsOriginalSentence() {
        val samples = listOf(
            Triple("nb", "Jeg vil ha kaffe.", "kaffe"), Triple("es", "Quiero un café.", "café"),
            Triple("en", "I would like coffee.", "coffee"), Triple("fr", "Je voudrais du café.", "café"),
            Triple("de", "Ich möchte Kaffee.", "Kaffee"), Triple("it", "Vorrei un caffè.", "caffè"),
            Triple("pt", "Quero um café.", "café"), Triple("zh", "我想去银行。", "银行"))
        assertEquals(LanguageRegistry.all.map { it.id }.toSet(), samples.map { it.first }.toSet())
        for ((language, sentence, word) in samples) {
            show(language, sentence, "A short practice sentence.")
            compose.onNodeWithTag("target-caption").performScrollTo()
            tap(word, sentence)
            compose.onNodeWithTag("word-lookup-close").performClick()
        }
    }

    @Test fun lookupKeepsItsSentenceAndDismissalCancelsTheOldResult() {
        val sentence = "Quiero un café con leche."
        show("es", sentence, "I want a coffee with milk.")
        val gate = CountDownLatch(1); responseGate = gate; response = "Old coffee meaning"
        openWord("café", sentence)
        assertNotNull(requests.poll(2, TimeUnit.SECONDS))
        compose.runOnIdle {
            val current = vm.session!!
            state("session", current.copy(fragments = (current.fragments + Fragment(speaker = Speaker.assistant,
                text = "Tengo leche.", startMS = 6000, endMS = 7000)).toMutableList()))
        }
        compose.onNodeWithTag("word-lookup-sentence").assertTextEquals(sentence)
        compose.onNodeWithTag("word-lookup-close").performClick()
        compose.runOnIdle { assertFalse(vm.lookupLoading); assertNull(vm.lookupResult) }
        responseGate = null; response = "Milk in this sentence"
        show("es", "Tengo leche.", "I have milk.")
        tap("leche", "Tengo leche.")
        gate.countDown()
        compose.waitForIdle()
        compose.onNodeWithTag("word-lookup-explanation").assertTextEquals("Milk in this sentence")
        compose.onNodeWithTag("word-lookup-close").performClick()
    }

    @Test fun hiddenPinyinStaysHiddenWhenTheNextCaptionArrives() {
        show("zh", "我想去银行。", "I want to go to the bank.")
        compose.onNodeWithTag("pinyin-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("pinyin-reading").assertDoesNotExist()
        compose.runOnIdle {
            val current = vm.session!!
            state("session", current.copy(fragments = mutableListOf(Fragment(speaker = Speaker.assistant,
                text = "我喜欢喝咖啡。", startMS = 6000, endMS = 7000))))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pinyin-toggle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pinyin-reading").assertDoesNotExist()
        compose.onNodeWithTag("pinyin-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("pinyin-reading").assertTextEquals("wǒ xǐhuan hē kāfēi。")
    }

    @Test fun lookupCanCompleteWhileAnotherHelperIsWorking() {
        val sentence = "Quiero un café."
        show("es", sentence, "I want a coffee.")
        compose.runOnIdle { state("working", true) }
        try {
            tap("café", sentence)
            compose.onNodeWithTag("word-lookup-close").performClick()
            compose.runOnIdle { assertTrue(vm.working); assertFalse(vm.lookupLoading) }
        } finally { compose.runOnIdle { state("working", false) } }
    }

    @Test fun lookupFailureStaysInSheetAndRetappingCanRetry() {
        val sentence = "Quiero un café."
        show("es", sentence, "I want a coffee.")
        responseCode = 400
        openWord("café", sentence)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("word-lookup-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("word-lookup-error").assertIsDisplayed()
        compose.runOnIdle { assertNull(vm.error); assertFalse(vm.lookupLoading) }
        requests.clear()
        compose.onNodeWithTag("word-lookup-close").performClick()
        responseCode = 200
        tap("café", sentence)
        compose.onNodeWithTag("word-lookup-error").assertDoesNotExist()
        compose.onNodeWithTag("word-lookup-close").performClick()
    }

    // Run again with a 360 × 640 dp viewport and font_scale=2.0; the sheet is a separate native window.
    @Test fun captionsAndLookupRemainReachableWithLargeSystemText() {
        val sentence = "我想去银行，然后去旅行。"
        show("zh", sentence, "I want to go to the bank, then travel.")
        compose.onNodeWithTag("target-caption").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pinyin-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("pinyin-reading").assertDoesNotExist()
        compose.onNodeWithTag("meaning-caption").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("target-caption").performScrollTo()
        tap("银行", sentence)
        compose.onNodeWithTag("word-lookup-explanation").performScrollTo().assertIsDisplayed()
        capture("large-text-lookup")
        compose.onNodeWithTag("word-lookup-close").assertIsDisplayed().performClick()
        val microphone = compose.onNodeWithTag("start-conversation")
        if (!microphone.isDisplayed()) microphone.performScrollTo()
        microphone.assertIsDisplayed()
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
    }

    @Test fun longMandarinReplyKeepsBothCaptionsVisibleAndEveryReadingReachable() {
        val sentence = "你好！很高兴认识你。你的中文说得很好。今天我们可以聊一聊你的生活。你喜欢喝咖啡还是喝茶？如果你有时间，我们可以一起去附近的咖啡馆，然后去银行，再去商店买一点儿东西。你觉得怎么样？你也可以告诉我你最喜欢的食物，或者说说你明天想做什么。"
        show("zh", sentence, "Hello! Nice to meet you. Your Chinese is good. We can go to a café, then the bank, and buy a few things. What do you think?")
        assertTextVisible("target-caption", false); assertTextVisible("meaning-caption", false)
        capture("mandarin-long-caption")
        val target = compose.onNodeWithTag("target-passage-scroll")
        val meaning = compose.onNodeWithTag("meaning-passage-scroll").fetchSemanticsNode().boundsInRoot
        val range = target.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        target.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue()) }
        compose.onNodeWithTag("pinyin-reading").assertIsDisplayed()
        assertEquals(meaning, compose.onNodeWithTag("meaning-passage-scroll").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("pinyin-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("pinyin-reading").assertDoesNotExist()
        compose.onNodeWithTag("start-conversation").assertIsDisplayed()
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
    }
}
