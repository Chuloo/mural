package chat.mural.ondevice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An open-weight teacher voice, observed at the [OnDeviceSpeechOutput] seam: the sentences a
 * conversation hands it, what the voice model is asked to speak, and what the learner hears when the
 * model is missing, will not start, or fails mid-conversation. Driven by a fake voice model and a
 * fake platform voice, so no native model and no speaker are involved.
 */
class OpenWeightSpeechOutputTest {
    private val voice = TeacherVoice("pf_dora", "pt-BR")
    /** Runs a queued sentence on the calling thread, so a test observes one sentence at a time. */
    private val inline = InlineVoiceWorker()
    /** One real worker thread, so a test can stop or replace the queue while a sentence is spoken. */
    private val threaded = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mural-test-teacher-voice").apply { isDaemon = true } }
    private val platform = FakePlatformVoice()
    private val opened = mutableListOf<FakeVoiceSynthesizer>()
    private lateinit var output: OnDeviceSpeechOutput

    private class FakePlatformVoice : OnDeviceSpeechOutput {
        val spoken = mutableListOf<String>()
        val locales = mutableListOf<String>()
        var talking = false
        var stopped = 0
        var closed = false
        override val speaking get() = talking
        override val level get() = if (talking) 0.6 else 0.0
        override fun speak(sentence: String, locale: String, queueAdd: Boolean) {
            spoken += sentence; locales += locale
        }
        override fun stop() { stopped++; talking = false }
        override fun close() { closed = true }
    }

    /**
     * One scripted open-weight voice model. [failsOn] is the 1-based sentence whose synthesis
     * throws, [silentOn] the one it produces no audio for; [started] and [release] hold a real
     * worker thread inside one sentence so a test can stop or replace the queue meanwhile.
     */
    private class FakeVoiceSynthesizer(
        private val failsOn: Int = 0,
        private val silentOn: Int = 0,
        private val started: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
        private val speakingNow: () -> Boolean = { false },
        private val levelNow: () -> Double = { 0.0 },
        var energy: Double = 0.5,
    ) : VoiceSynthesizer {
        val sentences = mutableListOf<String>()
        val voices = mutableListOf<TeacherVoice>()
        val speaking = mutableListOf<Boolean>()
        val levels = mutableListOf<Double>()
        var stopped = 0
        var closed = false
        override val level get() = energy
        override fun synthesize(sentence: String, voice: TeacherVoice): Boolean {
            sentences += sentence; voices += voice
            speaking += speakingNow(); levels += levelNow()
            if (sentences.size == failsOn) throw ModelRuntimeException("The voice model failed")
            started?.countDown()
            release?.await(2, TimeUnit.SECONDS)
            // A stopped synthesis never finishes its audio, exactly as a real one does not.
            return stopped == 0 && sentences.size != silentOn
        }
        override fun stop() { stopped++ }
        override fun close() { closed = true }
    }

    private fun openWeight(failsOn: Int = 0, silentOn: Int = 0, cannotOpen: Boolean = false,
        worker: ExecutorService = inline, started: CountDownLatch? = null, release: CountDownLatch? = null)
        : OnDeviceSpeechOutput {
        opened.clear()
        output = OpenWeightSpeechOutput(platform, voice, open = {
            if (cannotOpen) throw ModelRuntimeException("The voice model would not start")
            FakeVoiceSynthesizer(failsOn, silentOn, started, release,
                speakingNow = { output.speaking }, levelNow = { output.level }).also { opened += it }
        }, worker = worker)
        return output
    }

    /** Waits for a real worker thread to finish the queue, bounded so a bug cannot hang the suite. */
    private fun quiescent(output: OnDeviceSpeechOutput) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (output.speaking && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(output.speaking)
    }

    /** Waits for the voice worker to stop, bounded so a leaked thread cannot hang the suite. */
    private fun stopped(worker: ExecutorService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!worker.isShutdown && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(worker.isShutdown)
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun closeStopsTheVoiceWorkerSoAConversationNeverLeaksItsThread() {
        val output = openWeight(worker = threaded)
        output.speak("Olá!", "pt-PT", queueAdd = true)
        quiescent(output)

        output.close()
        // The engine is released on the thread that created it, and that thread then stops: one
        // conversation is one worker thread, and a closed conversation holds none.
        stopped(threaded)
        assertTrue(opened.single().closed)

        output.speak("Tudo bem?", "pt-PT", queueAdd = true)
        assertEquals(listOf("Olá!"), opened.single().sentences)
        assertTrue(platform.spoken.isEmpty())
    }

    @Test fun sentencesAreSpokenOneByOneInTheVoiceOfTheLanguageAndNeverByThePlatformVoice() {
        val output = openWeight()
        output.speak("Olá!", "pt-PT", queueAdd = true)
        output.speak("Tudo bem?", "pt-PT", queueAdd = true)

        val synthesizer = opened.single()
        assertEquals(listOf("Olá!", "Tudo bem?"), synthesizer.sentences)
        // One packaged voice and its BCP-47 tag serve every sentence of the conversation.
        assertEquals(listOf(voice, voice), synthesizer.voices)
        assertTrue(platform.spoken.isEmpty())
        // One model serves the whole conversation: a 281 MB voice is never reopened per sentence.
        assertEquals(1, opened.size)
        // The microphone stays off while the teacher speaks, and the orb sees the model's own energy.
        assertEquals(listOf(true, true), synthesizer.speaking)
        assertEquals(listOf(0.5, 0.5), synthesizer.levels)
        assertFalse(output.speaking)
        assertEquals(0.0, output.level, 0.0)
    }

    @Test fun aVoiceModelThatWillNotStartSpeaksEverySentenceWithThePlatformVoice() {
        val output = openWeight(cannotOpen = true)
        output.speak("Olá!", "pt-PT", queueAdd = true)
        output.speak("Tudo bem?", "pt-PT", queueAdd = true)

        // No error and no silence: the learner hears the phone's own voice from the first sentence.
        assertEquals(listOf("Olá!", "Tudo bem?"), platform.spoken)
        // The module's own locale, so a Portugal learner keeps a European voice on the fallback.
        assertEquals(listOf("pt-PT", "pt-PT"), platform.locales)
        assertTrue(opened.isEmpty())
        // A model that would not start is not retried sentence by sentence.
        output.speak("Vamos falar.", "pt-PT", queueAdd = true)
        assertEquals(listOf("Olá!", "Tudo bem?", "Vamos falar."), platform.spoken)
        assertTrue(opened.isEmpty())
    }

    @Test fun aSentenceTheVoiceModelCannotSynthesizeIsSpokenByThePlatformVoiceAndSoIsEveryOneAfterIt() {
        val output = openWeight(failsOn = 2)
        output.speak("Olá!", "pt-PT", queueAdd = true)
        output.speak("Tudo bem?", "pt-PT", queueAdd = true)
        output.speak("Vamos falar.", "pt-PT", queueAdd = true)

        // The failing sentence and the rest are spoken by the platform voice; nothing is dropped.
        assertEquals(listOf("Olá!", "Tudo bem?"), opened.single().sentences)
        assertEquals(listOf("Tudo bem?", "Vamos falar."), platform.spoken)
        // A model that failed is released and never retried, exactly as a failed speech recognition
        // model is not retried turn by turn.
        assertTrue(opened.single().closed)
        assertEquals(1, opened.size)
    }

    @Test fun aSentenceTheVoiceModelSpeaksAsSilenceIsSpokenByThePlatformVoiceAlone() {
        val output = openWeight(silentOn = 2)
        output.speak("Olá!", "pt-PT", queueAdd = true)
        output.speak("…", "pt-PT", queueAdd = true)
        output.speak("Vamos falar.", "pt-PT", queueAdd = true)

        assertEquals(listOf("Olá!", "…", "Vamos falar."), opened.single().sentences)
        assertEquals(listOf("…"), platform.spoken)
        // A sentence with no audio is not a broken model, so the voice keeps speaking the next one.
        assertFalse(opened.single().closed)
    }

    @Test fun theOrbSeesThePlatformVoiceSEnergyWhileItSpeaksAFallback() {
        val output = openWeight(cannotOpen = true)
        platform.talking = true
        output.speak("Olá!", "pt-PT", queueAdd = true)

        assertTrue(output.speaking)
        assertEquals(0.6, output.level, 0.0)
    }

    @Test fun stopAndCloseReachTheVoiceModelAndThePlatformVoiceAndNothingIsSpokenAfterwards() {
        val output = openWeight()
        output.speak("Olá!", "pt-PT", queueAdd = true)

        output.stop()
        assertEquals(1, opened.single().stopped)
        assertEquals(1, platform.stopped)

        output.close()
        assertTrue(opened.single().closed)
        assertTrue(platform.closed)

        output.speak("Tudo bem?", "pt-PT", queueAdd = true)
        assertEquals(listOf("Olá!"), opened.single().sentences)
        assertTrue(platform.spoken.isEmpty())
    }

    @Test fun aSentenceThatReplacesTheQueueIsSpokenAloneWhileTheQueueKeepsTheMicrophoneOff() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = openWeight(worker = threaded, started = started, release = release)
        output.speak("Primero.", "es-ES", queueAdd = true)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        output.speak("Segundo.", "es-ES", queueAdd = true)
        // A flush replaces what is waiting, exactly as the platform voice's own flush does.
        output.speak("Tercero.", "es-ES", queueAdd = false)
        assertTrue(output.speaking)

        release.countDown()
        quiescent(output)
        assertEquals(listOf("Primero.", "Tercero."), opened.single().sentences)
        assertTrue(platform.spoken.isEmpty())
        output.close()
    }

    @Test fun aStopMidSentenceNeverHandsThatSentenceToThePlatformVoice() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = openWeight(worker = threaded, started = started, release = release)
        output.speak("Primero.", "es-ES", queueAdd = true)
        output.speak("Segundo.", "es-ES", queueAdd = true)
        assertTrue(started.await(2, TimeUnit.SECONDS))

        output.stop()
        release.countDown()
        quiescent(output)
        // The interrupted sentence is not spoken twice, and the waiting one is gone.
        assertEquals(listOf("Primero."), opened.single().sentences)
        assertTrue(platform.spoken.isEmpty())
        assertEquals(1, opened.single().stopped)
        output.close()
    }
}

/**
 * An [ExecutorService] that runs each queued task on the calling thread, so a voice test observes one
 * sentence at a time — and reports itself shut down the moment a closed voice stops its worker.
 */
internal class InlineVoiceWorker : AbstractExecutorService() {
    private var stopped = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
