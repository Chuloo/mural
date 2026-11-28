package chat.mural.ondevice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The clip format an On-Device Model's native audio input takes: 16 kHz mono 16-bit PCM inside a
 * 44-byte RIFF/WAVE wrapper. The Kotlin engine API carries no sample rate or channel count, so the
 * header is the whole description of the audio. The same clip unwraps into the float PCM a speech
 * recognition model takes, so one recording serves both.
 */
object WavAudio {
    const val SAMPLE_RATE = 16_000
    private const val HEADER_BYTES = 44
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val PCM_FORMAT: Short = 1
    /** 16-bit PCM scaled into the [-1.0, 1.0] float range a speech recognition model takes. */
    private const val SHORT_SCALE = 32_768f

    fun wrapPcm16Mono(pcmBytes: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(HEADER_BYTES - 8 + pcmBytes.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                       // the PCM format chunk is always 16 bytes
        header.putShort(PCM_FORMAT)
        header.putShort(CHANNELS.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmBytes.size)
        return header.array() + pcmBytes
    }

    /** A wrapped clip's PCM as mono float samples, the form MediaPipe's audio tasks consume. */
    fun floatSamples(wavBytes: ByteArray): FloatArray {
        val count = (wavBytes.size - HEADER_BYTES) / (BITS_PER_SAMPLE / 8)
        if (count <= 0) return FloatArray(0)
        val pcm = ByteBuffer.wrap(wavBytes, HEADER_BYTES, count * BITS_PER_SAMPLE / 8)
            .order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { pcm.short / SHORT_SCALE }
    }
}
