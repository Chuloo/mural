package chat.mural.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which microphone an On-Device Model voice conversation uses: the learner's explicit choice, or
 * the native audio input it falls back to. The rule that an unavailable alternate never blocks a
 * conversation the active model could otherwise hold lives here, at the policy seam. These cases use
 * European Portuguese (`pt-PT`), a language Parakeet covers; the language-coverage fallback for an
 * uncovered module (Mandarin, Japanese) lives in `MandarinOnDeviceTest`.
 */
class OnDeviceSpeechInputSelectorTest {
    private fun resolve(chosen: SpeechInputEngine, speechModelOnPhone: Boolean, hearsAudio: Boolean,
        locale: String = "pt-PT") =
        OnDeviceSpeechInputSelector.resolve(chosen, speechModelOnPhone, hearsAudio, locale)

    @Test fun theChosenSpeechRecognitionAlternateRunsWhenItsModelIsOnThePhone() {
        // Even a text-only conversation model hears the learner once the alternate transcribes.
        assertEquals(SpeechInputEngine.PARAKEET,
            resolve(SpeechInputEngine.PARAKEET, speechModelOnPhone = true, hearsAudio = true))
        assertEquals(SpeechInputEngine.PARAKEET,
            resolve(SpeechInputEngine.PARAKEET, speechModelOnPhone = true, hearsAudio = false))
    }

    @Test fun nativeAudioInputIsTheDefaultAndStaysTheChoiceOfALearnerWhoNeverPickedAnAlternate() {
        assertEquals(SpeechInputEngine.NATIVE_AUDIO,
            resolve(SpeechInputEngine.NATIVE_AUDIO, speechModelOnPhone = true, hearsAudio = true))
        assertEquals(SpeechInputEngine.NATIVE_AUDIO,
            resolve(SpeechInputEngine.NATIVE_AUDIO, speechModelOnPhone = false, hearsAudio = true))
    }

    @Test fun anAlternateThatIsNotOnThePhoneNeverBlocksTheNativeAudioInput() {
        // Not downloaded, deleted, or missing: the microphone the active model already hears with.
        assertEquals(SpeechInputEngine.NATIVE_AUDIO,
            resolve(SpeechInputEngine.PARAKEET, speechModelOnPhone = false, hearsAudio = true))
    }

    @Test fun noSpeechInputIsHonestWhenNeitherEngineCanHearTheLearner() {
        // A text-only conversation model with no alternate on the phone: the caller owes the
        // learner a notice and a typed conversation, never a silent voice session.
        assertNull(resolve(SpeechInputEngine.PARAKEET, speechModelOnPhone = false, hearsAudio = false))
        assertNull(resolve(SpeechInputEngine.NATIVE_AUDIO, speechModelOnPhone = true, hearsAudio = false))
        assertNull(resolve(SpeechInputEngine.NATIVE_AUDIO, speechModelOnPhone = false, hearsAudio = false))
    }
}
