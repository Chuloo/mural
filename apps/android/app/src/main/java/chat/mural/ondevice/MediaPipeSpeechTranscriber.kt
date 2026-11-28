package chat.mural.ondevice

import android.content.Context
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriber
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriberResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File

/**
 * The MediaPipe `AudioTranscriber` behind [ParakeetSpeechInput]: a downloaded `.litertlm` speech
 * recognition model fed one turn's 16 kHz mono float PCM in the reference app's 100 ms chunks. Only
 * a final segment is the learner's words, so interim results never reach a conversation. Everything
 * native lives here; the [SpeechTranscriber] seam above it is what Mural tests.
 */
class MediaPipeSpeechTranscriber(context: Context, modelFile: File) : SpeechTranscriber {
    private val transcriber = listOf(Delegate.GPU, Delegate.CPU).firstNotNullOfOrNull { delegate ->
        runCatching { AudioTranscriber.createFromOptions(context, options(context, modelFile, delegate)) }.getOrNull()
    } ?: throw ModelRuntimeException("The speech recognition model would not start: ${modelFile.name}")

    /** The turn's final segments so far, in the order the engine settled them. */
    private val transcript = StringBuilder()

    override fun transcribe(samples: FloatArray): String {
        transcript.setLength(0)
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + CHUNK_SAMPLES, samples.size)
            collect(transcriber.transcribe(samples.copyOfRange(offset, end), end >= samples.size))
            offset = end
        }
        return collect(transcriber.finishStream())
    }

    override fun close() = transcriber.close()

    private fun collect(result: AudioTranscriberResult): String {
        for (segment in result.segments()) {
            val text = segment.transcript().trim()
            if (text.isEmpty() || !segment.isFinal) continue
            if (transcript.isNotEmpty()) transcript.append(' ')
            transcript.append(text)
        }
        return transcript.toString()
    }

    private fun options(context: Context, modelFile: File, delegate: Delegate) =
        AudioTranscriber.AudioTranscriberOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelFile.absolutePath)
                .setDelegate(delegate).build())
            .setSampleRateHz(WavAudio.SAMPLE_RATE)
            .setEnableInterimResults(true)
            .setModelArchitecture(AudioTranscriber.ModelArchitecture.AUTO)
            .setCacheDir(File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }.absolutePath)
            .build()

    private companion object {
        /** One 100 ms chunk of 16 kHz mono float PCM: the reference app's streaming granularity. */
        const val CHUNK_SAMPLES = 1_600
        const val CACHE_DIRECTORY = "mural_speech_recognition_cache"
    }
}
