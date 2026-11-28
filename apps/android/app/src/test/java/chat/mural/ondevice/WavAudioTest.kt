package chat.mural.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clip an On-Device Model hears: 16 kHz mono 16-bit PCM in a 44-byte RIFF/WAVE wrapper, the
 * format the model's native audio input takes.
 */
class WavAudioTest {
    private fun ascii(wav: ByteArray, offset: Int, length: Int) = String(wav, offset, length, Charsets.US_ASCII)
    private fun short(wav: ByteArray, offset: Int) =
        (wav[offset].toInt() and 0xff) or ((wav[offset + 1].toInt() and 0xff) shl 8)
    private fun int(wav: ByteArray, offset: Int) =
        short(wav, offset) or (short(wav, offset + 2) shl 16)

    @Test fun wrapPcm16MonoWritesTheRiffWaveHeaderAroundTheRecordedClip() {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 4, 5, 6)
        val wav = WavAudio.wrapPcm16Mono(pcm, sampleRate = 16_000)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(wav.size - 8, int(wav, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, int(wav, 16))          // the PCM format chunk is 16 bytes
        assertEquals(1, short(wav, 20))         // 1 = uncompressed PCM
        assertEquals(1, short(wav, 22))         // mono
        assertEquals(16_000, int(wav, 24))
        assertEquals(32_000, int(wav, 28))      // byteRate = sampleRate * channels * bits / 8
        assertEquals(2, short(wav, 32))         // blockAlign = channels * bits / 8
        assertEquals(16, short(wav, 34))
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(pcm.size, int(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test fun anEmptyClipIsStillAValidHeaderAndAnotherSampleRateChangesTheByteRate() {
        val silent = WavAudio.wrapPcm16Mono(ByteArray(0))
        assertEquals(44, silent.size)
        assertEquals(0, int(silent, 40))
        assertEquals(36, int(silent, 4))

        val narrow = WavAudio.wrapPcm16Mono(ByteArray(20), sampleRate = 8_000)
        assertEquals(8_000, int(narrow, 24))
        assertEquals(16_000, int(narrow, 28))
    }

    @Test fun floatSamplesAreTheClipSPcmNormalizedForASpeechRecognitionModel() {
        val pcm = byteArrayOf(0, 0, 0, 64, 0, -128, -1, 127)
        val samples = WavAudio.floatSamples(WavAudio.wrapPcm16Mono(pcm))

        // Little-endian 16-bit PCM scaled into [-1.0, 1.0], the float MediaPipe's task takes.
        assertEquals(4, samples.size)
        assertEquals(listOf(0f, 0.5f, -1f, 32_767f / 32_768f), samples.toList())
        assertEquals(0, WavAudio.floatSamples(WavAudio.wrapPcm16Mono(ByteArray(0))).size)
        assertEquals(0, WavAudio.floatSamples(ByteArray(44)).size)
    }

    @Test fun aThirtySecondClipStaysInsideTheReferenceLimit() {
        // The reference app caps one audio clip at 30 seconds of 16 kHz mono 16-bit PCM.
        val clip = WavAudio.wrapPcm16Mono(ByteArray(30 * 16_000 * 2))
        assertEquals(44 + 960_000, clip.size)
    }
}
