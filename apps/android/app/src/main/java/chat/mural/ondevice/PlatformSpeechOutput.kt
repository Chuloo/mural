package chat.mural.ondevice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import chat.mural.core.ApproximateVoicePolicy
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's built-in text-to-speech: the teacher's voice with zero extra downloads. Sentences are
 * queued as the model streams them, and every utterance in flight is counted, so [speaking] stays
 * true from the first queued sentence to the last — a gap between two sentences would let the
 * microphone hear the teacher's own voice.
 */
class PlatformSpeechOutput(context: Context) : OnDeviceSpeechOutput {
    private val lock = Any()
    private val pending = mutableListOf<Sentence>()
    private var initStatus: Int? = null
    private var ready = false
    private var unavailable = false
    private var appliedLocale: Locale? = null
    /** The tags this engine's voices report, cached once, so a wanted voice can fall back. */
    private var available: Set<String> = emptySet()
    private var sequence = 0
    private val inFlight = AtomicInteger(0)

    /** Every terminal path counts down, or [speaking] would stay true forever. */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = spoken()
        override fun onError(utteranceId: String?) = spoken()
        override fun onError(utteranceId: String?, errorCode: Int) = spoken()
        override fun onStop(utteranceId: String?, interrupted: Boolean) = spoken()
    }

    private var engine: TextToSpeech? = null

    init {
        synchronized(lock) {
            engine = TextToSpeech(context.applicationContext) { status ->
                synchronized(lock) { initStatus = status; activate() }
            }
            // An engine that answers during construction reports before the field above is set.
            activate()
        }
    }

    private data class Sentence(val text: String, val locale: String, val queueAdd: Boolean)

    override val speaking get() = inFlight.get() > 0

    // shortcut: the platform engine reports no output energy, so a speaking teacher shows a steady
    // level; upgrade when a voice synthesizes PCM Mural can measure itself.
    override val level get() = if (speaking) SPEAKING_LEVEL else 0.0

    override fun speak(sentence: String, locale: String, queueAdd: Boolean) {
        if (sentence.isBlank()) return
        synchronized(lock) {
            // A closed or failed engine speaks nothing, and never keeps a sentence waiting.
            if (unavailable) return
            val next = Sentence(sentence, locale, queueAdd)
            // An engine that never initializes must not swallow the teacher's reply silently.
            if (!ready) { pending += next; activate(); return }
            speakNow(next)
        }
    }

    override fun stop() {
        synchronized(lock) {
            pending.clear()
            inFlight.set(0)
            runCatching { engine?.stop() }
        }
    }

    override fun close() {
        stop()
        synchronized(lock) {
            unavailable = true
            runCatching { engine?.shutdown() }
        }
    }

    /** Runs on the engine's status report and again from [speak], in case that report came first. */
    private fun activate() {
        if (ready || unavailable) return
        val status = initStatus ?: return
        val created = engine ?: return
        if (status != TextToSpeech.SUCCESS) { unavailable = true; pending.clear(); return }
        created.setOnUtteranceProgressListener(progress)
        ready = true
        available = runCatching { created.voices?.map { it.locale.toLanguageTag() }.orEmpty() }
            .getOrDefault(emptyList()).toSet()
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach { speakNow(it) }
    }

    private fun speakNow(sentence: Sentence) {
        val created = engine ?: return
        // Resolve the wanted tag against this phone's voices; null means keep the sentence's own,
        // so a phone without the stand-in voice still speaks rather than erroring.
        val locale = localeFor(ApproximateVoicePolicy.localeTag(sentence.locale, available) ?: sentence.locale)
        // A missing language pack must not silence the teacher: the engine's own voice still speaks.
        if (locale != appliedLocale) { runCatching { created.setLanguage(locale) }; appliedLocale = locale }
        val utteranceID = "mural-${sequence++}"
        inFlight.incrementAndGet()
        val result = created.speak(sentence.text,
            if (sentence.queueAdd) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
            Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceID) }, utteranceID)
        if (result == TextToSpeech.ERROR) spoken()
    }

    /** A stop() and its own onStop callbacks both end an utterance; the count never goes negative. */
    private fun spoken() { inFlight.updateAndGet { (it - 1).coerceAtLeast(0) } }

    private fun localeFor(tag: String): Locale =
        runCatching { Locale.forLanguageTag(tag) }.getOrNull()?.takeIf { it.language.isNotEmpty() } ?: Locale.ENGLISH

    private companion object {
        /** The orb's steady energy while the platform engine speaks. */
        const val SPEAKING_LEVEL = 0.6
    }
}
