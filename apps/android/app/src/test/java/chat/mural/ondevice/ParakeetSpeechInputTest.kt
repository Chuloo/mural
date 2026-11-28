package chat.mural.ondevice

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The speech recognition alternate, observed at the [OnDeviceSpeechInput] seam: what a conversation
 * receives for one recorded clip, and what happens when the alternate cannot run. Driven by a fake
 * microphone and a fake [SpeechTranscriber], so no native model and no microphone are involved.
 */
class ParakeetSpeechInputTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val opened = mutableListOf<FakeTranscriber>()

    /** One recorded clip: 16 kHz mono 16-bit PCM in the WAV wrapper the microphone hands over. */
    private fun clip(vararg samples: Short, durationMS: Int = 900) = SpeechTurn.AudioWav(durationMS,
        WavAudio.wrapPcm16Mono(ByteArray(samples.size * 2) { index ->
            val sample = samples[index / 2].toInt()
            if (index % 2 == 0) (sample and 0xff).toByte() else ((sample shr 8) and 0xff).toByte()
        }))

    private class FakeMicrophone(turns: List<SpeechTurn>) : OnDeviceSpeechInput {
        private val queue = ArrayDeque(turns)
        var energy = 0.42
        var muted: Boolean? = null
        var closed = false
        override val level get() = energy
        override suspend fun awaitTurn(): SpeechTurn? = queue.removeFirstOrNull()
        override fun mute(muted: Boolean) { this.muted = muted }
        override fun close() { closed = true }
    }

    private class FakeTranscriber(private val texts: List<String>) : SpeechTranscriber {
        val heard = mutableListOf<FloatArray>()
        var closed = false
        override fun transcribe(samples: FloatArray): String {
            heard += samples
            return texts.getOrElse(heard.size - 1) { texts.last() }
        }
        override fun close() { closed = true }
    }

    /** The alternate over a fake microphone, opening one fake speech recognition model per call. */
    private fun alternate(turns: List<SpeechTurn>, texts: List<String> = listOf("Buenos días"),
        audioFallback: Boolean = false, cannotOpen: Boolean = false): OnDeviceSpeechInput {
        opened.clear()
        return ParakeetSpeechInput(FakeMicrophone(turns), open = {
            if (cannotOpen) throw ModelRuntimeException("The speech recognition model would not start")
            FakeTranscriber(texts).also { opened += it }
        }, audioFallback = audioFallback, dispatcher = dispatcher)
    }

    @Test fun aRecordedClipReachesTheConversationAsTheLearnerSWordsOnTheClipSOwnWindow() = runTest(dispatcher) {
        val recorded = clip(0, 16_384, Short.MIN_VALUE, Short.MAX_VALUE)
        val microphone = FakeMicrophone(listOf(recorded))
        val input = ParakeetSpeechInput(microphone, open = { FakeTranscriber(listOf("Buenos días")).also { opened += it } },
            dispatcher = dispatcher)

        assertEquals(SpeechTurn.Transcript(900, "Buenos días"), input.awaitTurn())

        // The alternate hears the clip's own PCM normalized to float, never the WAV wrapper.
        val heard = opened.single().heard.single()
        assertEquals(listOf(0f, 0.5f, -1f, 32_767f / 32_768f), heard.toList())
        assertFalse(microphone.closed)
    }

    @Test fun oneSpeechRecognitionModelServesEveryTurnOfAConversation() = runTest(dispatcher) {
        val input = alternate(listOf(clip(0, durationMS = 400), clip(0, durationMS = 600)),
            texts = listOf("Hola", "Me llamo Ana"))

        assertEquals(SpeechTurn.Transcript(400, "Hola"), input.awaitTurn())
        assertEquals(SpeechTurn.Transcript(600, "Me llamo Ana"), input.awaitTurn())

        // Loaded once, not per turn: a 0.6 GB engine is never reopened mid-conversation.
        assertEquals(1, opened.size)
        assertEquals(2, opened.single().heard.size)
    }

    @Test fun aTurnTheAlternateHearsAsSilenceIsDroppedInsteadOfReachingTheTeacher() = runTest(dispatcher) {
        val input = alternate(listOf(clip(0, durationMS = 300), clip(0, durationMS = 500)),
            texts = listOf("   ", "Buenos días"))

        assertEquals(SpeechTurn.Transcript(500, "Buenos días"), input.awaitTurn())
        assertEquals(2, opened.single().heard.size)
    }

    @Test fun anAlternateThatCannotRunHandsTheClipToTheModelSNativeAudioInput() = runTest(dispatcher) {
        val first = clip(0, 1_000)
        val second = clip(0, durationMS = 500)
        val input = alternate(listOf(first, second), cannotOpen = true, audioFallback = true)

        // The active model hears audio, so the clip reaches it exactly as native audio input would.
        assertSame(first, input.awaitTurn())
        assertSame(second, input.awaitTurn())
        assertTrue(opened.isEmpty())
    }

    @Test fun anAlternateThatCannotRunIsHonestWhenTheActiveModelCannotHearAudio() = runTest(dispatcher) {
        val input = alternate(listOf(clip(0)), cannotOpen = true, audioFallback = false)

        try { input.awaitTurn(); fail("A text-only model was handed an audio clip") }
        catch (error: ModelRuntimeException) { assertTrue(error.message!!.contains("speech recognition")) }
    }

    @Test fun muteAndLevelsReachTheMicrophoneAndCloseReleasesTheSpeechRecognitionModel() = runTest(dispatcher) {
        val microphone = FakeMicrophone(listOf(clip(0)))
        val input = ParakeetSpeechInput(microphone, open = { FakeTranscriber(listOf("Hola")).also { opened += it } },
            dispatcher = dispatcher)
        input.awaitTurn()

        assertEquals(0.42, input.level, 0.0)
        input.mute(true)
        assertEquals(true, microphone.muted)

        input.close()
        assertTrue(microphone.closed)
        assertTrue(opened.single().closed)
        // A closed microphone has no conversation left, so the alternate reports that too.
        assertNull(input.awaitTurn())
    }

    @Test fun anAlternateOverAnEmptyMicrophoneReportsNoTurnLeft() = runTest(dispatcher) {
        assertNull(alternate(emptyList()).awaitTurn())
        assertTrue(opened.isEmpty())
    }
}
