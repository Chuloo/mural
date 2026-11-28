package chat.mural.ondevice

import chat.mural.core.ConversationHistory
import chat.mural.core.Fragment
import chat.mural.core.ON_DEVICE_PROVIDER_ID
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An On-Device Model voice conversation, observed through the events it emits and the sentences it
 * speaks: the same contract a cloud provider meets, driven here by a scripted Model Runtime with a
 * fake microphone and a fake voice. Strictly offline — nothing in this suite can reach a network.
 */
class OnDeviceLiveSessionTest {
    private val model = "models/gemma-4-e4b.litertlm"
    private val log = mutableListOf<String>()
    private val events = mutableListOf<JsonObject>()
    private val levels = mutableListOf<Pair<Double, Double>>()
    private val failures = mutableListOf<Throwable>()
    private val busyReports = mutableListOf<Boolean>()
    private var hungTurnsCancelled = 0
    private var clock = 1_000.0
    private lateinit var input: FakeSpeechInput
    private lateinit var output: FakeSpeechOutput

    private class FakeSpeechInput(
        turns: List<SpeechTurn> = emptyList(),
        private val onDeliver: (SpeechTurn) -> Unit = {},
    ) : OnDeviceSpeechInput {
        private val queue = ArrayDeque(turns)
        var energy = 0.0
        var muted = false
        var closed = false
        override val level get() = energy
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
        override fun speak(sentence: String, locale: String, queueAdd: Boolean) {
            spoken += sentence
            log += "speak:$sentence"
        }
        override fun stop() { stopped++; talking = false }
        override fun close() { closed = true }
    }

    /** One open-weight teacher voice that fails on a scripted sentence, to drive the fallback. */
    private class FakeVoiceModel(private val failsOn: Int) : VoiceSynthesizer {
        val sentences = mutableListOf<String>()
        var stopped = 0
        var closed = false
        override val level get() = 0.5
        override fun synthesize(sentence: String, voice: TeacherVoice): Boolean {
            sentences += sentence
            if (sentences.size == failsOn) throw ModelRuntimeException("The voice model failed")
            return true
        }
        override fun stop() { stopped++ }
        override fun close() { closed = true }
    }

    private fun TestScope.conversation(
        runtime: ScriptedModelRuntime,
        turns: List<SpeechTurn> = emptyList(),
        hangingTurns: Boolean = false,
        /** Transcribes every recorded clip, so the alternate drives the session instead of native audio input. */
        heardByAlternate: String? = null,
        /** Speaks every sentence with an open-weight voice instead of the platform voice alone. */
        teacherVoice: VoiceSynthesizer? = null,
        onDeliver: (SpeechTurn) -> Unit = {},
    ): OnDeviceLiveSession {
        input = FakeSpeechInput(turns, onDeliver)
        output = FakeSpeechOutput(log)
        // An open-weight teacher voice speaks over the same fake platform voice it falls back to.
        val voice: OnDeviceSpeechOutput = teacherVoice?.let { model ->
            OpenWeightSpeechOutput(output, TeacherVoice("pf_dora", "pt-BR"), open = { model },
                worker = Executor { task -> task.run() })
        } ?: output
        val microphone: OnDeviceSpeechInput = heardByAlternate?.let { text ->
            ParakeetSpeechInput(input, open = { object : SpeechTranscriber {
                override fun transcribe(samples: FloatArray) = text
                override fun close() {}
            } }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        } ?: input
        return OnDeviceLiveSession(
            scope = backgroundScope,
            turn = if (hangingTurns) ModelTurn { _, _, _ ->
                try { awaitCancellation() }
                catch (cancelled: CancellationException) { hungTurnsCancelled++; throw cancelled }
            } else ModelTurn { systemPrompt, request, onPartial ->
                val reply = scriptedTurn(runtime, model).run(systemPrompt, request, onPartial)
                log += "generation-complete"
                reply
            },
            input = microphone,
            output = voice,
            onEvent = { events += it },
            onLevels = { microphone, voice -> levels += microphone to voice },
            onFailure = { failures += it },
            onBusy = { busyReports += it },
            now = { clock },
        )
    }

    /** A level ticker runs forever, so tests advance a bounded window instead of idling. */
    private fun TestScope.settle() { advanceTimeBy(3_000); runCurrent() }

    private val JsonObject.type get() = this["type"]!!.jsonPrimitive.content
    private val JsonObject.delta get() = this["delta"]!!.jsonPrimitive.content
    private val JsonObject.sessionID get() = (this["session"] as JsonObject)["id"]!!.jsonPrimitive.content
    private val JsonObject.seconds get() = (this["usage"] as JsonObject)["seconds"]!!.jsonPrimitive.double
    private val JsonObject.startMS get() = this["start_ms"]!!.jsonPrimitive.int
    private val JsonObject.endMS get() = this["end_ms"]!!.jsonPrimitive.int
    private fun List<JsonObject>.ofType(type: String) = filter { it.type == type }
    private val List<JsonObject>.captions get() = ofType("session.output_transcript.delta").map { it.delta }
    private val List<JsonObject>.heard get() = ofType("session.input_transcript.delta").map { it.delta }

    private fun clip(milliseconds: Int) =
        SpeechTurn.AudioWav(milliseconds, WavAudio.wrapPcm16Mono(ByteArray(milliseconds * 32)))

    @Test fun anOnDeviceVoiceSessionOpensWithTheSameEventsAsACloudProvider() = runTest {
        val session = conversation(ScriptedModelRuntime())
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        assertEquals(listOf("mural.session.created", "session.started"), events.map { it.type })
        // Session Provenance survives both events: the archive keeps "on-device" as the provider.
        assertEquals(ON_DEVICE_PROVIDER_ID, events[0].sessionID)
        assertEquals(ON_DEVICE_PROVIDER_ID, events[1].sessionID)
        assertFalse(input.closed)
        assertTrue(failures.isEmpty())
    }

    @Test fun theGreetingCommandIsSpokenSentenceBySentenceAndCaptionedAsTheTeacher() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("¡Hola! ", "¿Cómo estás hoy?"))
        val session = conversation(runtime)
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        assertTrue(session.command("instructions", "Greet the learner in Spanish."))
        settle()

        assertEquals(listOf("¡Hola!", "¿Cómo estás hoy?"), output.spoken)
        assertEquals(listOf("¡Hola!", "¿Cómo estás hoy?"), events.captions)
        assertEquals("Greet the learner in Spanish.", runtime.requests.single().request.prompt)
        assertTrue(runtime.requests.single().systemPrompt!!.startsWith("Teach Spanish."))
        // Nothing was heard, so a greeting never invents a learner caption.
        assertTrue(events.heard.isEmpty())
    }

    @Test fun aNativeAudioTurnCaptionsTheHeardLearnerAndSpeaksTheCleanTeacherReply() = runTest {
        val recorded = clip(900)
        val runtime = ScriptedModelRuntime(
            ScriptedReply("<heard>Bue", "nos días</heard>¡Ho", "la! ", "¿Cómo estás hoy?"))
        val session = conversation(runtime, listOf(recorded)) { clock += it.durationMS / 1_000.0 }
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        val request = runtime.requests.single().request
        assertArrayEquals(recorded.wavBytes, request.audioWavBytes)
        assertEquals("", request.prompt)

        val heard = events.ofType("session.input_transcript.delta")
        assertEquals(listOf("Buenos días"), heard.map { it.delta })
        // The caption window is the clip the learner spoke, on the session's own timeline.
        assertEquals(0, heard.single().startMS)
        assertEquals(900, heard.single().endMS)

        assertEquals(listOf("¡Hola!", "¿Cómo estás hoy?"), output.spoken)
        assertEquals(listOf("¡Hola!", "¿Cómo estás hoy?"), events.captions)
        assertTrue(output.spoken.none { it.contains("heard") })
        assertTrue(events.any { it.type == "session.usage.updated" })
    }

    @Test fun theFirstSentenceIsSpokenBeforeGenerationFinishes() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("<heard>Hola</heard>", "¡Hola! ", "¿Cómo estás?"))
        val session = conversation(runtime, listOf(clip(400)))
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        val spoken = log.indexOf("speak:¡Hola!")
        val complete = log.indexOf("generation-complete")
        assertTrue(log.toString(), spoken in 0 until complete)
        assertEquals(listOf("¡Hola!", "¿Cómo estás?"), output.spoken)
    }

    @Test fun aSpeechRecognitionTurnUsesTheSameSeamWithTextAndNoHeardRule() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("¡Hola! ", "Amigo."))
        val session = conversation(runtime, listOf(SpeechTurn.Transcript(700, "Buenos días"))) {
            clock += it.durationMS / 1_000.0
        }
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = false)
        settle()

        val recorded = runtime.requests.single()
        assertEquals("Buenos días", recorded.request.prompt)
        assertNull(recorded.request.audioWavBytes)
        assertFalse(recorded.systemPrompt!!.contains("<heard>"))
        assertEquals(listOf("Buenos días"), events.heard)
        assertEquals(listOf("¡Hola!", "Amigo."), output.spoken)
    }

    @Test fun aSpeechRecognitionAlternateFeedsTheSameSessionAsNativeAudioInput() = runTest {
        val recorded = clip(700)
        val runtime = ScriptedModelRuntime(ScriptedReply("¡Hola! ", "Amigo."), ScriptedReply("Mucho gusto."))
        val session = conversation(runtime, listOf(recorded), heardByAlternate = "Buenos días") {
            clock += it.durationMS / 1_000.0
        }
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = false)
        settle()

        val first = runtime.requests.first()
        // The learner's words reach the model as text: no clip, and no <heard> rule overhead.
        assertEquals("Buenos días", first.request.prompt)
        assertNull(first.request.audioWavBytes)
        assertFalse(first.systemPrompt!!.contains("<heard>"))
        // Captioned on the clip's own window, exactly as a native audio turn captions what the model heard.
        val heard = events.ofType("session.input_transcript.delta")
        assertEquals(listOf("Buenos días"), heard.map { it.delta })
        assertEquals(0, heard.single().startMS)
        assertEquals(700, heard.single().endMS)
        assertEquals(listOf("¡Hola!", "Amigo."), output.spoken)
        assertEquals(listOf("¡Hola!", "Amigo."), events.captions)
        assertTrue(events.ofType("session.usage.updated").isNotEmpty())

        // The transcript is the session's record: the next turn re-primes the learner's own words,
        // which is what an assessment reads, so the alternate is indistinguishable downstream.
        assertTrue(session.command("instructions", "Ask how the learner is."))
        settle()
        assertTrue(runtime.requests.last().systemPrompt!!.contains("Learner: Buenos días"))
        assertEquals(listOf("¡Hola!", "Amigo.", "Mucho gusto."), output.spoken)
    }

    @Test fun commandsRunInOrderSoTheLearnerHearsOneTeacherAtATime() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("Primero."), ScriptedReply("Segundo."))
        val session = conversation(runtime)
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        session.command("instructions", "Ask how the learner is.")
        session.command("instructions", "Offer help in Spanish.")
        settle()

        assertEquals(listOf("Ask how the learner is.", "Offer help in Spanish."),
            runtime.requests.map { it.request.prompt })
        assertEquals(listOf("Primero.", "Segundo."), output.spoken)
    }

    @Test fun typedCommentaryIsSpokenAsItIsAndThinkingBecomesContextForTheNextTurn() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("De acuerdo."))
        val session = conversation(runtime)
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        assertTrue(session.command("commentary", "Claro que sí. Vamos a practicar."))
        settle()
        // A typed reply is already generated text: it is spoken, never sent to the model again.
        assertTrue(runtime.requests.isEmpty())
        assertEquals(listOf("Claro que sí.", "Vamos a practicar."), output.spoken)
        assertEquals(listOf("Claro que sí.", "Vamos a practicar."), events.captions)

        assertTrue(session.command("thinking", "The learner typed (data): hola"))
        assertTrue(session.command("instructions", "Reply to the typed message."))
        settle()
        assertEquals("The learner typed (data): hola\nReply to the typed message.",
            runtime.requests.single().request.prompt)
    }

    @Test fun everyVoiceTurnRePrimesTheConversationSoAHelperRestartCannotLoseIt() = runTest {
        val runtime = ScriptedModelRuntime(
            ScriptedReply("<heard>Buenos días</heard>¡Hola! Amigo."),
            ScriptedReply("<heard>Me llamo Ana</heard>Mucho gusto, Ana."))
        val session = conversation(runtime, listOf(clip(500), clip(500)))
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        assertEquals(2, runtime.requests.size)
        val second = runtime.requests[1].systemPrompt!!
        assertTrue(second, second.startsWith("Teach Spanish."))
        val carried = second.substringAfter("The conversation so far:")
        assertTrue(carried, carried.contains("Learner: Buenos días"))
        assertTrue(carried, carried.contains("You: ¡Hola! Amigo."))
        // The caption block is lifted out before it can re-enter the conversation.
        assertFalse(carried, carried.contains("<heard>"))
        assertFalse(carried, carried.contains("Me llamo Ana"))
    }

    @Test fun theMicrophoneIsMutedWhileTheTeacherSpeaksSoTheModelNeverHearsItself() = runTest {
        val session = conversation(ScriptedModelRuntime())
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()
        assertFalse(input.muted)

        output.talking = true
        settle()
        assertTrue(input.muted)

        output.talking = false
        settle()
        assertFalse(input.muted)

        session.mute(true)
        settle()
        assertTrue(input.muted)
        session.mute(false)
        settle()
        assertFalse(input.muted)
    }

    @Test fun theOrbLevelsFollowTheMicrophoneAndTheTeachersVoice() = runTest {
        val session = conversation(ScriptedModelRuntime())
        input.energy = 0.4
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()
        assertEquals(0.4 to 0.0, levels.last())

        output.talking = true
        settle()
        assertEquals(0.4 to 0.6, levels.last())
    }

    @Test fun aTeacherTurnReportsBusySoTheIdleTimerWaitsForTheReply() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("¡Hola!"))
        val session = conversation(runtime)
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()
        assertTrue(busyReports.isEmpty())

        session.command("instructions", "Greet the learner.")
        settle()
        assertEquals(listOf(true, false), busyReports)
    }

    @Test fun closeStopsSpeechClosesTheMicrophoneAndReportsTheFinalVoiceSeconds() = runTest {
        val session = conversation(ScriptedModelRuntime())
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        clock += 42.0
        session.close()
        val closed = events.last()
        assertEquals("session.closed", closed.type)
        assertEquals(42.0, closed.seconds, 0.001)
        assertTrue(input.closed)
        assertTrue(output.closed)
        assertEquals(1, output.stopped)
        assertEquals(0.0 to 0.0, levels.last())

        val before = events.size
        session.close()
        assertEquals(before, events.size)
        assertFalse(session.command("instructions", "Greet the learner."))
    }

    @Test fun closeEndsATurnStillGeneratingSoTheModelIsReleasedWithTheMicrophone() = runTest {
        val session = conversation(ScriptedModelRuntime(), hangingTurns = true)
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        // Asking for help sends two instructions back to back: the second waits for the first.
        session.command("instructions", "Ask how the learner is.")
        session.command("instructions", "Offer help in Spanish.")
        settle()
        assertEquals(0, hungTurnsCancelled)

        session.close()
        settle()
        assertEquals(1, hungTurnsCancelled)
        assertEquals(listOf(true, false), busyReports)
    }

    @Test fun aModelFailureIsReportedOnceAndEndsTheSession() = runTest {
        val runtime = ScriptedModelRuntime()
        val session = conversation(runtime, listOf(clip(500)))
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        settle()

        assertTrue(failures.toString(), failures.single() is ModelRuntimeException)
        assertEquals("session.closed", events.last().type)
        assertTrue(input.closed)
        assertTrue(output.closed)
    }

    @Test fun aContinuedConversationRendersItsHistoryIntoTheFirstTurn() = runTest {
        val previous = SessionRecord(languageID = "es", startedAt = 900_000_000.0).apply {
            append(Fragment(speaker = Speaker.user, text = "la radio", startMS = 0, endMS = 1_000))
            append(Fragment(speaker = Speaker.assistant, text = "¡Qué bien! ¿La escuchas por la mañana?",
                startMS = 1_000, endMS = 2_000))
        }
        val runtime = ScriptedModelRuntime(ScriptedReply("Seguimos."))
        val session = conversation(runtime)
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true,
            history = ConversationHistory.messages(previous))
        settle()

        session.command("instructions", "Continue the conversation.")
        settle()

        val system = runtime.requests.single().systemPrompt!!
        assertTrue(system, system.contains("Learner: la radio"))
        assertTrue(system, system.contains("You: ¡Qué bien! ¿La escuchas por la mañana?"))
        assertEquals(listOf("Seguimos."), output.spoken)
    }

    @Test fun anEmptyHistoryLeavesTheTeachingInstructionsAlone() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("¡Hola!"))
        val session = conversation(runtime)
        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true, history = JsonArray(emptyList()))
        settle()
        session.command("instructions", "Greet the learner.")
        settle()
        assertEquals("Teach Spanish.\n" + OnDeviceHeardCaption.rule, runtime.requests.single().systemPrompt)
    }

    @Test fun anOpenWeightTeacherVoiceSpeaksTheReplyAndAFailingSentenceFallsBackToThePlatformVoice() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("Olá! ", "Tudo bem? ", "Vamos falar."))
        val voiceModel = FakeVoiceModel(failsOn = 2)
        val session = conversation(runtime, teacherVoice = voiceModel)
        session.start("Teach Portuguese.", "pt-PT", nativeAudioInput = true)
        settle()

        session.command("instructions", "Greet the learner in Portuguese.")
        settle()

        // The open-weight voice speaks what it can, and the phone's own voice speaks the sentence it
        // failed on and every one after it: no error, no silence, and no caption lost to the fallback.
        assertEquals(listOf("Olá!", "Tudo bem?"), voiceModel.sentences)
        assertEquals(listOf("Tudo bem?", "Vamos falar."), output.spoken)
        assertEquals(listOf("Olá!", "Tudo bem?", "Vamos falar."), events.captions)
        assertTrue(failures.isEmpty())
        // A model that failed is released, and the conversation carries on without it.
        assertTrue(voiceModel.closed)
    }

    @Test fun closingASessionWithAnOpenWeightVoiceReleasesTheVoiceModelAndThePlatformVoice() = runTest {
        val voiceModel = FakeVoiceModel(failsOn = 0)
        val session = conversation(ScriptedModelRuntime(ScriptedReply("Olá!")), teacherVoice = voiceModel)
        session.start("Teach Portuguese.", "pt-PT", nativeAudioInput = true)
        settle()
        session.command("instructions", "Greet the learner.")
        settle()
        assertEquals(listOf("Olá!"), voiceModel.sentences)
        assertTrue(output.spoken.isEmpty())

        session.close()
        settle()

        // A session stops its voice and then closes it, and both reach the model and the platform voice.
        assertEquals(2, voiceModel.stopped)
        assertTrue(voiceModel.closed)
        assertTrue(output.closed)
        assertEquals(2, output.stopped)
    }
}
