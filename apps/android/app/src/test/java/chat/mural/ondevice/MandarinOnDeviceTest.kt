package chat.mural.ondevice

import chat.mural.core.Assessment
import chat.mural.core.CaptionSegment
import chat.mural.core.CaptionWords
import chat.mural.core.ConversationProviderPolicy
import chat.mural.core.EvidenceKind
import chat.mural.core.FlashcardDeck
import chat.mural.core.Fragment
import chat.mural.core.HanReader
import chat.mural.core.LearningEngine
import chat.mural.core.MandarinPinyin
import chat.mural.core.ON_DEVICE_PROVIDER_ID
import chat.mural.core.Outcome
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import java.util.concurrent.Executor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mainland Mandarin (`普通话 · Mainland China`, `zh-CN`) and Japanese (`ja-JP`) across the On-Device
 * Model Conversation Provider, observed at the same seams the rest of the suite uses: the speech-input
 * and teacher-voice policy selectors, the open-weight voice fallback, the sentence chunker and heard
 * caption of a streamed voice conversation, the Han reading and caption segmentation, and the
 * defensive assessment parse projecting into flashcards. Strictly offline — nothing here reaches a
 * network, and no native model or speaker is involved.
 */
class MandarinOnDeviceTest {
    @After fun resetGlobalReader() { MandarinPinyin.reader = null }

    // ---------------------------------------------------------------------
    // Speech input: Parakeet covers 25 European languages, never zh-CN or ja-JP.
    // ---------------------------------------------------------------------

    private fun resolveInput(chosen: SpeechInputEngine, onPhone: Boolean, hearsAudio: Boolean, locale: String) =
        OnDeviceSpeechInputSelector.resolve(chosen, onPhone, hearsAudio, locale)

    @Test fun aLearnerWhoPickedParakeetAndSwitchesToMandarinOrJapaneseFallsBackToNativeAudioInput() {
        // The critical real-world case: Parakeet was chosen for a language it hears, then the active
        // Language Module changes to one it does not. Feeding Mandarin or Japanese speech into a
        // European-only transcriber would caption silence, so the native audio input takes over.
        assertEquals(SpeechInputEngine.NATIVE_AUDIO,
            resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = true, locale = "zh-CN"))
        assertEquals(SpeechInputEngine.NATIVE_AUDIO,
            resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = true, locale = "ja-JP"))
        // Brazilian Portuguese is not covered either (Parakeet publishes pt-PT only), so it falls back too.
        assertEquals(SpeechInputEngine.NATIVE_AUDIO,
            resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = true, locale = "pt-BR"))
    }

    @Test fun parakeetStillRunsForTheEuropeanLanguagesItActuallyHears() {
        // The control: a covered module keeps the learner's explicit alternate, including a text-only
        // conversation model that hears nothing on its own, and a region-less module (English -> en-US).
        assertEquals(SpeechInputEngine.PARAKEET,
            resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = true, locale = "pt-PT"))
        assertEquals(SpeechInputEngine.PARAKEET,
            resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = false, locale = "fr-FR"))
        assertEquals(SpeechInputEngine.PARAKEET,
            resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = true, locale = "en"))
        assertEquals(SpeechInputEngine.PARAKEET,
            resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = true, locale = "es-ES"))
    }

    @Test fun noSpeechInputIsHonestWhenTheOnlyAlternateDoesNotHearTheLanguage() {
        // A text-only conversation model (Qwen) with Parakeet chosen for Mandarin: the alternate does
        // not hear zh-CN and the model has no native audio input, so the caller owes an honest notice.
        assertNull(resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = false, locale = "zh-CN"))
        assertNull(resolveInput(SpeechInputEngine.PARAKEET, onPhone = true, hearsAudio = false, locale = "ja-JP"))
        // Native audio input is unaffected by language coverage: the active model hears any language.
        assertEquals(SpeechInputEngine.NATIVE_AUDIO,
            resolveInput(SpeechInputEngine.NATIVE_AUDIO, onPhone = true, hearsAudio = true, locale = "zh-CN"))
    }

    // ---------------------------------------------------------------------
    // Teacher voice: Kokoro speaks zh-CN and ja-JP; PocketTTS speaks neither.
    // ---------------------------------------------------------------------

    @Test fun kokoroResolvesBothMandarinAndJapaneseVoicesPerLanguageModule() {
        assertEquals(TeacherVoice("zf_xiaoxiao", "zh-CN"),
            TeacherVoiceSelector.voice(TeacherVoiceEngine.KOKORO_82M, "zh-CN"))
        assertEquals(TeacherVoice("jf_alpha", "ja-JP"),
            TeacherVoiceSelector.voice(TeacherVoiceEngine.KOKORO_82M, "ja-JP"))
        // A base tag with no variety resolves the same packaged voice.
        assertEquals(TeacherVoice("zf_xiaoxiao", "zh-CN"),
            TeacherVoiceSelector.voice(TeacherVoiceEngine.KOKORO_82M, "zh"))
        // Downloaded, the learner's Kokoro pick speaks the module; not downloaded, the platform voice does.
        assertEquals(TeacherVoiceEngine.KOKORO_82M,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.KOKORO_82M, voiceModelOnPhone = true, locale = "zh-CN"))
        assertEquals(TeacherVoiceEngine.KOKORO_82M,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.KOKORO_82M, voiceModelOnPhone = true, locale = "ja-JP"))
        assertEquals(TeacherVoiceEngine.PLATFORM,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.KOKORO_82M, voiceModelOnPhone = false, locale = "zh-CN"))
    }

    @Test fun pocketTtsDoesNotSpeakMandarinOrJapaneseSoItFallsBackToThePlatformVoice() {
        // PocketTTS packages no zh or ja voice: a Mandarin or Japanese learner who picked it is never
        // misled into hearing it, and the conversation speaks with the phone's built-in voice instead.
        assertNull(TeacherVoiceSelector.voice(TeacherVoiceEngine.POCKET_TTS_100M, "zh-CN"))
        assertNull(TeacherVoiceSelector.voice(TeacherVoiceEngine.POCKET_TTS_100M, "ja-JP"))
        assertEquals(TeacherVoiceEngine.PLATFORM,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.POCKET_TTS_100M, voiceModelOnPhone = true, locale = "zh-CN"))
        assertEquals(TeacherVoiceEngine.PLATFORM,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.POCKET_TTS_100M, voiceModelOnPhone = true, locale = "ja-JP"))
        // Neither module hears "another variety" of its own language, so the picker adds no variety note.
        assertFalse(TeacherVoiceSelector.speaksAnotherVariety("zh-CN"))
        assertFalse(TeacherVoiceSelector.speaksAnotherVariety("ja-JP"))
    }

    // ---------------------------------------------------------------------
    // Open-weight voice fallback mid-conversation, at the OnDeviceSpeechOutput seam.
    // ---------------------------------------------------------------------

    /** Runs a queued sentence on the calling thread, so a test observes one sentence at a time. */
    private val inline = Executor { task -> task.run() }

    private class FakePlatformVoice : OnDeviceSpeechOutput {
        val spoken = mutableListOf<String>()
        val locales = mutableListOf<String>()
        var talking = false
        var stopped = 0
        var closed = false
        override val speaking get() = talking
        override val level get() = if (talking) 0.6 else 0.0
        override fun speak(sentence: String, locale: String, queueAdd: Boolean) { spoken += sentence; locales += locale }
        override fun stop() { stopped++; talking = false }
        override fun close() { closed = true }
    }

    /** One scripted Kokoro voice: [failsOn] is the 1-based sentence whose synthesis throws. */
    private class FakeKokoro(private val failsOn: Int) : VoiceSynthesizer {
        val sentences = mutableListOf<String>()
        val voices = mutableListOf<TeacherVoice>()
        var closed = false
        override val level get() = 0.5
        override fun synthesize(sentence: String, voice: TeacherVoice): Boolean {
            sentences += sentence; voices += voice
            if (sentences.size == failsOn) throw ModelRuntimeException("The voice model failed")
            return true
        }
        override fun stop() {}
        override fun close() { closed = true }
    }

    @Test fun aMandarinSentenceKokoroFailsOnFallsBackToThePlatformVoiceWithoutErrorOrSilence() {
        val platform = FakePlatformVoice()
        val kokoro = FakeKokoro(failsOn = 2)
        val output = OpenWeightSpeechOutput(platform, TeacherVoice("zf_xiaoxiao", "zh-CN"),
            open = { kokoro }, worker = inline)

        output.speak("你好！", "zh-CN", queueAdd = true)
        output.speak("今天想聊什么？", "zh-CN", queueAdd = true)

        // The first Mandarin sentence reaches Kokoro; the one it fails on is spoken by the phone's own
        // voice, routed by the zh-CN locale, and the broken model is released rather than retried.
        assertEquals(listOf("你好！", "今天想聊什么？"), kokoro.sentences)
        assertEquals(listOf("zf_xiaoxiao", "zf_xiaoxiao"), kokoro.voices.map { it.voiceID })
        assertEquals(listOf("今天想聊什么？"), platform.spoken)
        assertEquals(listOf("zh-CN"), platform.locales)
        assertTrue(kokoro.closed)
        output.close()
        assertTrue(platform.closed)
    }

    // ---------------------------------------------------------------------
    // Sentence chunker: CJK endings and Chinese quotes with no trailing whitespace.
    // ---------------------------------------------------------------------

    @Test fun chineseSentenceEndingsAndQuotesReleaseASentenceWithNoTrailingWhitespace() {
        // The ticket's example: a closing Chinese double quote belongs to the sentence it ends, and the
        // next sentence is released the moment its own 。！？ arrives — no space between them.
        assertEquals(listOf("“你好！”", "今天想聊什么？"),
            SentenceChunker.segment("“你好！”今天想聊什么？", isComplete = false))
        // Corner quotes 「」 behave the same way.
        assertEquals(listOf("「你好！」", "今天想聊什么？"),
            SentenceChunker.segment("「你好！」今天想聊什么？", isComplete = false))
        // A sentence ending releases at once, so the teacher speaks without waiting; a closing
        // quote that has not streamed yet is simply not attached to the sentence spoken so far.
        assertEquals(emptyList<String>(), SentenceChunker.segment("“你好", isComplete = false))
        assertEquals(listOf("“你好！"), SentenceChunker.segment("“你好！", isComplete = false))
        assertEquals(listOf("“你好！”"), SentenceChunker.segment("“你好！”", isComplete = false))
        // Plain 。！？ boundaries stream too.
        assertEquals(listOf("我想去银行。", "然后去旅行。"),
            SentenceChunker.segment("我想去银行。然后去旅行。", isComplete = false))
    }

    @Test fun everyStreamedMandarinPrefixKeepsTheSentencesItAlreadyReleased() {
        val reply = "你好！今天想聊什么？我想去银行。"
        val released = SentenceChunker.segment(reply, isComplete = false)
        assertEquals(listOf("你好！", "今天想聊什么？", "我想去银行。"), released)
        for (length in 1..reply.length) {
            val partial = SentenceChunker.segment(reply.substring(0, length), isComplete = false)
            assertEquals(reply.substring(0, length), released.take(partial.size), partial)
        }
    }

    // ---------------------------------------------------------------------
    // Heard caption: the learner's Han words lifted out, the tags never spoken.
    // ---------------------------------------------------------------------

    @Test fun aHeardBlockOfSimplifiedChineseIsLiftedOutAndTheTeacherTextStaysClean() {
        assertEquals("我想喝咖啡。" to "", OnDeviceHeardCaption.split("<heard>我想喝咖啡。</heard>"))
        assertEquals("我想喝咖啡。" to "你好！", OnDeviceHeardCaption.split("<heard>我想喝咖啡。</heard>你好！"))
        assertEquals("我想喝咖啡。" to "“你好！”今天想聊什么？",
            OnDeviceHeardCaption.split("<heard>我想喝咖啡。</heard>“你好！”今天想聊什么？"))
        // An open block is held back: a half-received Han caption is never spoken or recorded.
        assertEquals(null to "", OnDeviceHeardCaption.split("<heard>我想喝咖啡。"))
        // Traditional input is lifted out exactly as the learner's own words, unchanged.
        assertEquals("我想喝咖啡。" to "好！", OnDeviceHeardCaption.split("<heard>我想喝咖啡。</heard>好！"))
    }

    // ---------------------------------------------------------------------
    // Han reading and caption segmentation on streamed on-device fragments.
    // ---------------------------------------------------------------------

    /** Segments Han text longest-match-first against a small dictionary, as a platform reader would. */
    private class FakeHanReader : HanReader {
        private val readings = mapOf(
            "我" to "wǒ", "想" to "xiǎng", "喝" to "hē", "咖啡" to "kāfēi", "你好" to "nǐhǎo",
            "旅行" to "lǚxíng", "洗澡" to "xǐzǎo", "今天" to "jīntiān", "聊" to "liáo",
        )
        private val known = readings.keys.sortedByDescending { it.length }
        override fun words(text: String): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            val other = StringBuilder()
            while (i < text.length) {
                val word = known.firstOrNull { text.startsWith(it, i) }
                if (word != null) {
                    if (other.isNotEmpty()) { result += other.toString(); other.clear() }
                    result += word; i += word.length
                } else {
                    val cp = text.codePointAt(i); val chars = Character.charCount(cp)
                    val piece = text.substring(i, i + chars)
                    if (MandarinPinyin.containsHan(piece)) {
                        if (other.isNotEmpty()) { result += other.toString(); other.clear() }
                        result += piece
                    } else other.append(piece)
                    i += chars
                }
            }
            if (other.isNotEmpty()) result += other.toString()
            return result
        }
        override fun reading(word: String): String? = readings[word]
    }

    private val reader = FakeHanReader()

    @Test fun pinyinReadingAndHanWordLookupWorkOnStreamedOnDeviceFragments() {
        // A sentence the teacher streamed: the reading toggle renders pinyin, punctuation kept as-is.
        assertEquals("wǒ xiǎng hē kāfēi。", MandarinPinyin.reading("我想喝咖啡。", reader))
        assertEquals("nǐhǎo", MandarinPinyin.reading("你好", reader))
        // Caption segments link every Han word, never the punctuation, and keep every source character.
        val caption = "我想喝咖啡。"
        val segments = CaptionWords.segments(caption, "zh", reader)
        assertEquals(caption, segments.joinToString("") { it.text })
        assertEquals(listOf("我", "想", "喝", "咖啡"), segments.mapNotNull { it.lookup })
        // The trailing full stop is kept as a segment but never linked.
        assertEquals("。", segments.last { it.lookup == null }.text)
        // A multi-character lemma segments as one tap target, not two characters.
        assertTrue(CaptionWords.segments("然后去旅行。", "zh", reader).mapNotNull { it.lookup }.contains("旅行"))
    }

    @Test fun hanCaptionsWithoutAReaderFallBackSafelyAndTheGlobalReaderDrivesTheCaptionPath() {
        // reader == null (no platform segmentation wired): the whole fragment stays, unlinked, never dropped.
        assertNull(MandarinPinyin.words("我想喝咖啡。", null))
        assertNull(MandarinPinyin.reading("我想喝咖啡。", null))
        assertEquals(listOf(CaptionSegment("我想喝咖啡。", null)), CaptionWords.segments("我想喝咖啡。", "zh", null))
        // MainActivity wires MandarinPinyin.reader to IcuHanReader; the caption path uses that global
        // reader when a fragment carries no explicit one, exactly as an on-device caption renders it.
        MandarinPinyin.reader = reader
        assertEquals(listOf("我", "想", "喝", "咖啡"),
            CaptionWords.segments("我想喝咖啡。", "zh").mapNotNull { it.lookup })
        assertEquals("wǒ xiǎng hē kāfēi。", MandarinPinyin.reading("我想喝咖啡。"))
        // A token list always reconstructs the source, so a caption never loses a character.
        assertEquals("我想喝咖啡。", MandarinPinyin.tokens("我想喝咖啡。", reader).joinToString("") { it.text })
    }

    // ---------------------------------------------------------------------
    // Streamed Mandarin voice Talk, at the OnDeviceLiveSession seam.
    // ---------------------------------------------------------------------

    private val log = mutableListOf<String>()
    private val events = mutableListOf<JsonObject>()
    private var clock = 1_000.0
    private lateinit var input: FakeSpeechInput
    private lateinit var output: FakeSpeechOutput

    private class FakeSpeechInput(turns: List<SpeechTurn>, private val onDeliver: (SpeechTurn) -> Unit) :
        OnDeviceSpeechInput {
        private val queue = ArrayDeque(turns)
        var muted = false
        var closed = false
        override val level get() = 0.0
        override suspend fun awaitTurn(): SpeechTurn? {
            val next = queue.removeFirstOrNull() ?: awaitCancellation()
            onDeliver(next)
            return next
        }
        override fun mute(muted: Boolean) { this.muted = muted }
        override fun close() { closed = true }
    }

    private class FakeSpeechOutput(private val log: MutableList<String>) : OnDeviceSpeechOutput {
        val spoken = mutableListOf<String>()
        var talking = false
        var stopped = 0
        var closed = false
        override val speaking get() = talking
        override val level get() = if (talking) 0.6 else 0.0
        override fun speak(sentence: String, locale: String, queueAdd: Boolean) { spoken += sentence; log += "speak:$sentence" }
        override fun stop() { stopped++; talking = false }
        override fun close() { closed = true }
    }

    private fun TestScope.mandarinConversation(runtime: ScriptedModelRuntime, turns: List<SpeechTurn>): OnDeviceLiveSession {
        input = FakeSpeechInput(turns) { clock += it.durationMS / 1_000.0 }
        output = FakeSpeechOutput(log)
        return OnDeviceLiveSession(
            scope = backgroundScope,
            turn = ModelTurn { systemPrompt, request, onPartial ->
                val reply = scriptedTurn(runtime, "models/gemma-4-e4b.litertlm").run(systemPrompt, request, onPartial)
                log += "generation-complete"
                reply
            },
            input = input,
            output = output,
            onEvent = { events += it },
            now = { clock },
        )
    }

    private fun TestScope.settle() { advanceTimeBy(3_000); runCurrent() }
    private val JsonObject.type get() = this["type"]!!.jsonPrimitive.content
    private val JsonObject.delta get() = this["delta"]!!.jsonPrimitive.content
    private val JsonObject.startMS get() = this["start_ms"]!!.jsonPrimitive.int
    private val JsonObject.endMS get() = this["end_ms"]!!.jsonPrimitive.int
    private fun List<JsonObject>.ofType(type: String) = filter { it.type == type }
    private val List<JsonObject>.captions get() = ofType("session.output_transcript.delta").map { it.delta }
    private val List<JsonObject>.heard get() = ofType("session.input_transcript.delta").map { it.delta }
    private fun clip(milliseconds: Int) = SpeechTurn.AudioWav(milliseconds, WavAudio.wrapPcm16Mono(ByteArray(milliseconds * 32)))

    @Test fun aMandarinVoiceTurnSpeaksStreamedSentencesBeforeGenerationFinishesAndCaptionsTheHeardLearner() = runTest {
        val recorded = clip(900)
        val runtime = ScriptedModelRuntime(
            ScriptedReply("<heard>我想喝咖啡。</heard>", "“你好！”", "今天想聊什么？"))
        val session = mandarinConversation(runtime, listOf(recorded))
        session.start("Teach Mandarin.", "zh-CN", nativeAudioInput = true)
        settle()

        // The learner's Han words are lifted out of the <heard> block as their own transcript delta,
        // on the clip's window, and the tags never reach the teacher's speech or captions.
        val heard = events.ofType("session.input_transcript.delta")
        assertEquals(listOf("我想喝咖啡。"), heard.map { it.delta })
        assertEquals(0, heard.single().startMS)
        assertEquals(900, heard.single().endMS)

        // The Chinese-quote sentence and the question are each spoken and captioned as they finish.
        assertEquals(listOf("“你好！”", "今天想聊什么？"), output.spoken)
        assertEquals(listOf("“你好！”", "今天想聊什么？"), events.captions)
        assertTrue(output.spoken.none { it.contains("heard") })

        // The first sentence is spoken while the reply is still streaming, not after it completes.
        val spoken = log.indexOf("speak:“你好！”")
        val complete = log.indexOf("generation-complete")
        assertTrue(log.toString(), spoken in 0 until complete)

        // The model heard the clip directly: no transcribed prompt, and the heard rule is primed.
        val request = runtime.requests.single()
        assertEquals("", request.request.prompt)
        assertArrayEquals(recorded.wavBytes, request.request.audioWavBytes)
        assertTrue(request.systemPrompt!!.contains(OnDeviceHeardCaption.rule))
        assertTrue(request.systemPrompt!!.startsWith("Teach Mandarin."))
    }

    // ---------------------------------------------------------------------
    // On-device Mandarin assessment -> flashcards, with On-Device Model provenance.
    // ---------------------------------------------------------------------

    @Test fun anOnDeviceMandarinAssessmentProjectsSimplifiedLemmasAndTraditionalQuotesIntoFlashcards() {
        // The learner's passage as an on-device voice session captions it: two streamed Han fragments.
        val coffee = Fragment(id = "frag-coffee", speaker = Speaker.user, text = "我想喝咖啡，然后去旅行，", startMS = 0, endMS = 1_200)
        val computer = Fragment(id = "frag-computer", speaker = Speaker.user, text = "还要洗澡。我用電腦學習。", startMS = 1_200, endMS = 2_600)
        val session = SessionRecord(languageID = "zh", providerID = ON_DEVICE_PROVIDER_ID, startedAt = 1_000.0).apply {
            fragments += coffee
            fragments += computer
        }
        val passage = session.passages.single { it.speaker == Speaker.user }
        assertEquals("我想喝咖啡，然后去旅行，还要洗澡。我用電腦學習。", passage.text)

        // A local model wraps its JSON in fences and prose; multi-character Simplified lemmas, a
        // Traditional observed form, and full-width punctuation around the quotes all survive parsing.
        val raw = """
            Here is the assessment you asked for:
            ```json
            {"outcome":"success","suggestedLevel":3,"nextGoal":"练习用中文点咖啡。","capability":"Talks about everyday plans","words":[
              {"lemma":"咖啡","meaning":"coffee","form":"咖啡","quote":"咖啡","language":"zh","kind":"independent","confidence":0.95,"sourceIDs":["frag-coffee","frag-computer"]},
              {"lemma":"旅行","meaning":"travel","form":"旅行","quote":"旅行","language":"zh","kind":"independent","confidence":0.9,"sourceIDs":["frag-coffee","frag-computer"]},
              {"lemma":"洗澡","meaning":"to bathe","form":"洗澡","quote":"洗澡","language":"zh","kind":"understanding","confidence":0.85,"sourceIDs":["frag-coffee","frag-computer"]},
              {"lemma":"电脑","meaning":"computer","form":"電腦","quote":"電腦","language":"zh","kind":"independent","confidence":0.92,"sourceIDs":["frag-coffee","frag-computer"]}
            ]}
            ```
            I hope that helps.
        """.trimIndent()
        val proposal = OnDeviceAssessmentExtractor.proposal(raw)!!
        assertEquals(Outcome.success, proposal.outcome)
        assertEquals(3, proposal.suggestedLevel)
        assertEquals(listOf("咖啡", "旅行", "洗澡", "电脑"), proposal.words.map { it.lemma })
        // The Traditional observed form and quote are kept exactly; only the lemma is Simplified.
        assertEquals("電腦", proposal.words.last().quote)
        assertEquals("電腦", proposal.words.last().form)
        assertEquals("电脑", proposal.words.last().lemma)
        assertTrue(proposal.words.all { it.language == "zh" })

        // Session Provenance rides inside the unchanged assessment context, never a new field.
        val assessment = Assessment(passage.id, passage.revisionKey, proposal.outcome, proposal.suggestedLevel,
            proposal.nextGoal, proposal.capability, proposal.words, createdAt = 1_000.0,
            context = ConversationProviderPolicy.assessmentContext(onDevice = true, themeID = null),
            textAssemblyVersion = 2)
        assertEquals("on-device:free", assessment.context)

        val validated = LearningEngine.validate(assessment, session)!!
        assertEquals(listOf("咖啡", "旅行", "洗澡", "电脑"), validated.words.map { it.lemma })
        assertEquals(listOf(EvidenceKind.independent, EvidenceKind.independent, EvidenceKind.understanding,
            EvidenceKind.independent), validated.words.map { it.kind })

        session.assessments += validated
        val state = LearningEngine.project(listOf(session), "zh", now = 1_000.0)
        val deck = FlashcardDeck(state.words, "zh")
        assertEquals(setOf("咖啡", "旅行", "洗澡", "电脑"), deck.words.map { it.lemma }.toSet())
        // Every card carries the Mandarin module's own key, so the deck never mixes in another language.
        assertTrue(deck.words.all { it.id.startsWith("zh|") })
        assertEquals("咖啡", deck.words.first { it.lemma == "咖啡" }.lemma)
        // A different Language Module's deck holds none of these words.
        assertTrue(FlashcardDeck(state.words, "ja").words.isEmpty())
    }
}
