package chat.mural.ondevice

import chat.mural.core.ON_DEVICE_PROVIDER_ID
import chat.mural.core.Speaker
import chat.mural.core.nowSeconds
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * One On-Device Model voice conversation. The learner's speech goes to the active model, the reply
 * streams into sentences that the phone's voice speaks as they finish, and the session emits exactly
 * the events a cloud provider emits — creation, start, transcript deltas, usage, close — so captions,
 * assessments, subtitles, check-ins, mute, the idle end and the timed end all behave as they do
 * today. Strictly offline: nothing here can reach a network.
 */
class OnDeviceLiveSession(
    private val scope: CoroutineScope,
    private val turn: ModelTurn,
    private val input: OnDeviceSpeechInput,
    private val output: OnDeviceSpeechOutput,
    private val onEvent: (JsonObject) -> Unit,
    private val onLevels: (Double, Double) -> Unit = { _, _ -> },
    private val onFailure: (Throwable) -> Unit = {},
    /** Reports a teacher turn being generated, so the idle timer waits for it as for a cloud reply. */
    private val onBusy: (Boolean) -> Unit = {},
    private val now: () -> Double = ::nowSeconds,
) {
    private val lock = Any()
    private val turns = Mutex()
    private val transcript = mutableListOf<Pair<Speaker, String>>()
    private val context = mutableListOf<String>()
    private var startedAt = 0.0
    private var instructions = ""
    private var speechLocale = "en"
    private var hearsAudio = false
    private var opening: List<Pair<Speaker, String>> = emptyList()
    @Volatile private var busy = false
    @Volatile private var learnerMuted = false
    @Volatile private var closed = false
    private var listenJob: Job? = null
    private var levelJob: Job? = null
    /** Every teacher turn in flight or queued, so a close releases the model at once. */
    private val turnJobs = mutableListOf<Job>()

    /** Opens the conversation. No network exists to wait for, so it is ready at once. */
    fun start(instructions: String, speechLocale: String, nativeAudioInput: Boolean,
        history: JsonArray = JsonArray(emptyList())) {
        startedAt = now()
        this.instructions = instructions
        this.speechLocale = speechLocale
        this.hearsAudio = nativeAudioInput
        this.opening = render(history)
        emit(sessionEvent("mural.session.created"))
        emit(sessionEvent("session.started"))
        listenJob = scope.launch { listen() }
        levelJob = scope.launch { meter() }
    }

    /**
     * The cloud providers' data channel, on this phone: an instruction becomes a teacher turn (greeting,
     * check-in, theme, redirect, help), commentary is already generated text and is spoken as it is,
     * and anything else is context for the next turn.
     */
    fun command(kind: String, content: String): Boolean {
        if (closed) return false
        when (kind) {
            "instructions" -> launchTurn { generate(content, null, null) }
            "commentary" -> launchTurn { speakAsTeacher(content) }
            else -> synchronized(lock) { context += content }
        }
        return true
    }

    /** The learner's mute, exactly as the cloud transport takes it. */
    fun mute(muted: Boolean) {
        learnerMuted = muted
        input.mute(muted || busy || output.speaking)
    }

    /** A busy turn keeps the microphone off, so the model never hears its own teacher. */
    private fun setBusy(next: Boolean) {
        if (busy == next) return
        busy = next
        input.mute(learnerMuted || busy || output.speaking)
        onBusy(next)
    }

    /**
     * Ends the conversation: speech stops, the microphone closes, and the close event carries the
     * final voice seconds. Idempotent, because every end path closes the session.
     */
    fun close() {
        val pending = synchronized(lock) { if (closed) return; closed = true; turnJobs.toList() }
        setBusy(false)
        listenJob?.cancel()
        levelJob?.cancel()
        pending.forEach { it.cancel() }
        input.close()
        output.stop()
        output.close()
        onLevels(0.0, 0.0)
        emit(buildJsonObject {
            put("type", "session.closed")
            put("event_id", UUID.randomUUID().toString())
            putJsonObject("usage") { put("seconds", elapsedSeconds()) }
        })
    }

    private suspend fun listen() {
        try {
            while (!closed && coroutineContext.isActive) {
                awaitSpeechFinished()
                if (closed) break
                val heard = input.awaitTurn() ?: break
                if (closed) break
                answer(heard)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onFailure(error) }
        finally { close() }
    }

    /** No barge-in on-device: the microphone waits for the teacher instead of hearing her. */
    private suspend fun awaitSpeechFinished() {
        while (!closed && output.speaking) delay(SPEECH_POLL_MS)
    }

    /** The orb's levels, and the rule that keeps the model from hearing its own teacher. */
    private suspend fun meter() {
        while (!closed && coroutineContext.isActive) {
            delay(LEVEL_INTERVAL_MS)
            onLevels(input.level, output.level)
            input.mute(learnerMuted || busy || output.speaking)
        }
    }

    private suspend fun answer(turn: SpeechTurn) {
        val endedMS = elapsedMS()
        val startedMS = (endedMS - turn.durationMS).coerceAtLeast(0)
        when (turn) {
            // Nobody transcribed the clip: the model hears it and reports what it heard.
            is SpeechTurn.AudioWav -> generate("", turn.wavBytes, startedMS..endedMS)
            is SpeechTurn.Transcript -> {
                caption(Speaker.user, turn.text, startedMS, endedMS)
                record(Speaker.user, turn.text)
                generate(turn.text, null, null)
            }
        }
    }

    /** One teacher turn: the reply streams into sentences, each spoken and captioned as it finishes. */
    private suspend fun generate(prompt: String, audio: ByteArray?, heardWindow: IntRange?) {
        turns.withLock {
            if (closed) return
            setBusy(true)
            val stream = Stream(heardWindow, elapsedMS())
            try {
                val reply = turn.run(systemPrompt(), GenerationRequest(promptWith(prompt), audioWavBytes = audio)) { delta ->
                    synchronized(lock) { stream.raw += delta; publish(stream, false) }
                }
                // The returned reply is the whole turn, so a dropped delta cannot lose a sentence.
                synchronized(lock) {
                    if (reply.length > stream.raw.length) stream.raw = reply
                    publish(stream, true)
                    record(Speaker.assistant, stream.teacher)
                }
            } finally { setBusy(false) }
            emit(usage())
        }
    }

    /** Already generated text — a typed reply during a voice conversation — is spoken as it is. */
    private suspend fun speakAsTeacher(text: String) {
        turns.withLock {
            if (closed) return
            setBusy(true)
            try {
                val startMS = elapsedMS()
                SentenceChunker.segment(text, isComplete = true).forEach { sentence ->
                    output.speak(sentence, speechLocale, queueAdd = true)
                    caption(Speaker.assistant, sentence, startMS, maxOf(startMS + 1, elapsedMS()))
                }
                record(Speaker.assistant, text)
            } finally { setBusy(false) }
            emit(usage())
        }
    }

    /** Lifts the learner's heard words out and releases every sentence that has finished streaming. */
    private fun publish(stream: Stream, complete: Boolean) {
        val (heard, teacher) = if (hearsAudio) OnDeviceHeardCaption.split(stream.raw) else null to stream.raw
        stream.teacher = teacher
        // A command turn heard no learner, so a stray caption block is dropped rather than recorded.
        if (heard != null && stream.window != null && !stream.heardCaptioned) {
            stream.heardCaptioned = true
            caption(Speaker.user, heard, stream.window.first, stream.window.last)
            record(Speaker.user, heard)
        }
        val sentences = SentenceChunker.segment(teacher, complete)
        while (stream.spoken < sentences.size) {
            val sentence = sentences[stream.spoken++]
            output.speak(sentence, speechLocale, queueAdd = true)
            caption(Speaker.assistant, sentence, stream.teacherMS, maxOf(stream.teacherMS + 1, elapsedMS()))
        }
    }

    private fun launchTurn(block: suspend () -> Unit) {
        if (closed) return
        val job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onFailure(error); close() }
        }
        // A close between the check above and here must not leave a turn running.
        synchronized(lock) { if (closed) job.cancel() else turnJobs += job }
        job.invokeOnCompletion { synchronized(lock) { turnJobs -= job } }
    }

    /**
     * The teaching instructions, the heard-caption rule and the conversation so far. Helpers share
     * the one loaded model and restart it, so a voice turn re-primes its own history instead of
     * relying on the engine's — and the caption tags never re-enter the conversation.
     */
    private fun systemPrompt(): String = listOfNotNull(
        instructions,
        OnDeviceHeardCaption.rule.takeIf { hearsAudio },
        conversation().takeIf { it.isNotEmpty() },
    ).joinToString("\n")

    private fun conversation(): String {
        val entries = synchronized(lock) { (opening + transcript).takeLast(MAXIMUM_HISTORY_TURNS) }
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n", prefix = "The conversation so far:\n") { (speaker, text) ->
            "${if (speaker == Speaker.user) "Learner" else "You"}: ${text.take(MAXIMUM_TURN_CHARS)}"
        }
    }

    private fun record(speaker: Speaker, text: String) {
        if (text.isBlank()) return
        synchronized(lock) { transcript += speaker to text.trim() }
    }

    /** A provider's context injection becomes text in front of the next turn's prompt. */
    private fun promptWith(prompt: String): String {
        val injected = synchronized(lock) { val all = context.toList(); context.clear(); all }
        return if (injected.isEmpty()) prompt else injected.joinToString("\n", postfix = "\n") + prompt
    }

    /** The provider-neutral history every live session receives, rendered as conversation lines. */
    private fun render(history: JsonArray): List<Pair<Speaker, String>> = history.mapNotNull { item ->
        val message = item as? JsonObject ?: return@mapNotNull null
        val speaker = runCatching { Speaker.valueOf(message["role"]?.jsonPrimitive?.content.orEmpty()) }.getOrNull()
            ?: return@mapNotNull null
        val text = runCatching {
            (message["content"] as? JsonArray)?.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }?.joinToString("").orEmpty().trim()
        }.getOrNull().orEmpty()
        if (text.isEmpty()) null else speaker to text
    }

    private fun caption(speaker: Speaker, text: String, startMS: Int, endMS: Int) {
        if (text.isBlank()) return
        emit(buildJsonObject {
            put("type", if (speaker == Speaker.user) "session.input_transcript.delta"
                else "session.output_transcript.delta")
            put("event_id", UUID.randomUUID().toString())
            put("delta", text)
            put("start_ms", startMS)
            put("end_ms", endMS)
        })
    }

    private fun sessionEvent(type: String) = buildJsonObject {
        put("type", type)
        put("event_id", UUID.randomUUID().toString())
        putJsonObject("session") { put("id", ON_DEVICE_PROVIDER_ID) }
    }

    private fun usage() = buildJsonObject {
        put("type", "session.usage.updated")
        put("event_id", UUID.randomUUID().toString())
        putJsonObject("usage") { put("seconds", elapsedSeconds()) }
    }

    private fun emit(event: JsonObject) = onEvent(event)

    private fun elapsedMS() = ((now() - startedAt) * 1_000).roundToInt().coerceAtLeast(0)

    private fun elapsedSeconds() = (now() - startedAt).coerceAtLeast(0.0)

    /** One turn's streamed state, confined to [lock]: the engine calls back on its own thread. */
    private class Stream(val window: IntRange?, val teacherMS: Int) {
        var raw = ""
        var teacher = ""
        var spoken = 0
        var heardCaptioned = false
    }

    private companion object {
        const val LEVEL_INTERVAL_MS = 150L
        const val SPEECH_POLL_MS = 100L
        /** The bound the cloud providers carry a conversation over with. */
        const val MAXIMUM_HISTORY_TURNS = 40
        const val MAXIMUM_TURN_CHARS = 1_000
    }
}
