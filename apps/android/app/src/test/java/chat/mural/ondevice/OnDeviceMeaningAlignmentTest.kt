package chat.mural.ondevice

import chat.mural.core.AndroidLanguages
import chat.mural.core.Fragment
import chat.mural.core.MeaningController
import chat.mural.core.MeaningRequest
import chat.mural.core.MeaningResult
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.core.TeachingPolicy
import chat.mural.network.HelperPurpose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meaning subtitle of an On-Device Model voice reply keeps pace with the teacher's speech: each
 * sentence chunk that enters text-to-speech is translated on the same serialized Model Runtime while
 * it is spoken, so its meaning is on screen before that sentence ends and earlier meanings stay
 * visible. A scripted runtime and a fake speech clock fix generation, speech and translation costs in
 * virtual time. Strictly offline — the meaning goes through the same local Helper seam as the reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceMeaningAlignmentTest {
    private val model = "models/gemma-4-e4b.litertlm"
    /** Streaming one sentence, speaking one sentence, and translating one short sentence. */
    private val generationMillis = 200L
    private val speechMillis = 1_500L
    private val translationMillis = 150L

    private val sentences = listOf("¡Hola!", "¿Cómo estás hoy?", "Me llamo Mural.")
    private val meanings = listOf("Hello!", "How are you today?", "My name is Mural.")

    /** The one engine lock [OnDeviceModelController] holds: two turns never reach the runtime at once. */
    private val engine = Mutex()
    private val record = SessionRecord(languageID = "es")
    private val shownAt = mutableMapOf<String, Long>()
    private val requestedAt = mutableListOf<Long>()

    /** A fake speech clock: a spoken sentence occupies the voice for [sentenceMillis] of the queue. */
    private class TimedSpeechOutput(private val sentenceMillis: Long, private val now: () -> Long) : OnDeviceSpeechOutput {
        val spoken = mutableListOf<String>()
        val endsAt = mutableMapOf<String, Long>()
        private var freeAt = 0L
        override val speaking get() = now() < freeAt
        override val level get() = if (speaking) 0.6 else 0.0
        override fun speak(sentence: String, locale: String, queueAdd: Boolean) {
            spoken += sentence
            freeAt = maxOf(now(), freeAt) + sentenceMillis
            endsAt[sentence] = freeAt
        }
        override fun stop() { freeAt = now() }
        override fun close() { freeAt = now() }
    }

    private class FakeSpeechInput(turns: List<SpeechTurn>) : OnDeviceSpeechInput {
        private val queue = ArrayDeque(turns)
        override val level = 0.0
        override suspend fun awaitTurn(): SpeechTurn? = queue.removeFirstOrNull() ?: awaitCancellation()
        override fun mute(muted: Boolean) {}
        override fun close() {}
    }

    /** The captions MuralViewModel takes from the provider, and the meaning request each one schedules. */
    private fun TestScope.caption(event: JsonObject, subtitle: MeaningController) {
        val type = event["type"]?.jsonPrimitive?.content ?: return
        if (type != "session.input_transcript.delta" && type != "session.output_transcript.delta") return
        val speaker = if (type == "session.input_transcript.delta") Speaker.user else Speaker.assistant
        record.append(Fragment(speaker = speaker, text = event["delta"]!!.jsonPrimitive.content,
            startMS = event["start_ms"]!!.jsonPrimitive.int, endMS = event["end_ms"]!!.jsonPrimitive.int,
            meaningVisible = true))
        if (speaker != Speaker.assistant) return
        val passage = record.passages.last { it.speaker == Speaker.assistant }
        subtitle.update(MeaningRequest(record.id, passage, record.languageID, "English"),
            sentences = passage.fragments.map { it.text })
    }

    @Test fun eachSpokenSentenceHasItsMeaningOnScreenBeforeThatSentenceEnds() = runTest {
        val runtime = ScriptedModelRuntime(
            ScriptedReply("¡Hola! ", "¿Cómo estás hoy? ", "Me llamo Mural.", delayMillis = generationMillis),
            // A model's own line break must not break the one subtitle line the meanings join into.
            ScriptedReply(meanings[0], "\n", delayMillis = translationMillis),
            ScriptedReply(meanings[1], delayMillis = translationMillis),
            ScriptedReply(meanings[2], delayMillis = translationMillis))
        val turn = ModelTurn { systemPrompt, request, onPartial ->
            engine.withLock { scriptedTurn(runtime, model).run(systemPrompt, request, onPartial) }
        }
        val helpers = OnDeviceTeachingClient(turn) { "I cannot look that up offline." }
        val speech = TimedSpeechOutput(speechMillis) { testScheduler.currentTime }
        val subtitle = MeaningController(backgroundScope, now = { testScheduler.currentTime }, translate = { request ->
            requestedAt += testScheduler.currentTime
            val module = AndroidLanguages.get(request.learningLanguageID)!!
            val result = helpers.respond(TeachingPolicy.translation(module, request.meaningLanguage),
                request.translationInput, null, false, HelperPurpose.MEANING)
            MeaningResult(result.text)
        })
        subtitle.onChange = {
            meanings.forEach { meaning ->
                if (shownAt[meaning] == null && subtitle.text.contains(meaning)) shownAt[meaning] = testScheduler.currentTime
            }
        }
        val session = OnDeviceLiveSession(backgroundScope, turn,
            FakeSpeechInput(listOf(SpeechTurn.Transcript(700, "Buenos días"))), speech,
            onEvent = { event -> caption(event, subtitle) },
            now = { 1_000.0 + testScheduler.currentTime / 1_000.0 })
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = false)
        advanceTimeBy(20_000); runCurrent()

        assertEquals(sentences, speech.spoken)
        // Meaning work starts the moment a sentence enters speech, with no whole-passage quiet period.
        assertEquals(listOf(generationMillis), requestedAt.take(1))
        // The unit of work is the spoken sentence chunk, in speech order, on the one local runtime.
        assertEquals(sentences, runtime.requests.drop(1).map { it.request.prompt })
        assertEquals(TeachingPolicy.translation(AndroidLanguages.get("es")!!, "English"),
            runtime.requests[1].systemPrompt)
        sentences.forEachIndexed { index, sentence ->
            val shown = shownAt[meanings[index]]
            assertNotNull("the meaning of '$sentence' was never displayed", shown)
            assertTrue("the meaning of '$sentence' arrived at $shown, after its speech ended at ${speech.endsAt[sentence]}",
                shown!! <= speech.endsAt[sentence]!!)
        }
        // The demo: the first sentence's meaning is on screen while the teacher is still saying it.
        assertTrue(shownAt[meanings.first()]!! < speech.endsAt[sentences.first()]!!)
        // Meanings arrive in speech order and the earlier ones stay visible as the reply continues.
        assertEquals(meanings, meanings.sortedBy { shownAt[it] })
        assertEquals(meanings.joinToString(" "), subtitle.text)
        assertNull(subtitle.error)
        // The transcript is the evidence record: sentence-aligned display never rewrites a fragment,
        // so the Learning Engine's assisted/independent reading is unchanged by the timing.
        assertTrue(record.fragments.all { it.meaningVisible && it.revision == 0 })
    }
}
