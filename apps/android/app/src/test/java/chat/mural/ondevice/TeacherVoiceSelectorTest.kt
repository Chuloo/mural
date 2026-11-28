package chat.mural.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which voice an On-Device Model conversation's teacher speaks with: the learner's per-language
 * choice, or the phone's built-in voice it falls back to, and the packaged voice of each open-weight
 * model for each Language Module. The rule that a missing or uncovered voice never blocks a
 * conversation lives here, at the policy seam.
 */
class TeacherVoiceSelectorTest {
    private fun voice(engine: TeacherVoiceEngine, locale: String) = TeacherVoiceSelector.voice(engine, locale)

    @Test fun kokoroSpeaksEveryLanguageModuleItPackagesAVoiceFor() {
        // The packaged voice of each language, with the BCP-47 tag MediaPipe routes the sentence by.
        assertEquals(TeacherVoice("af_heart", "en-US"), voice(TeacherVoiceEngine.KOKORO_82M, "en"))
        assertEquals(TeacherVoice("ef_dora", "es-ES"), voice(TeacherVoiceEngine.KOKORO_82M, "es-ES"))
        assertEquals(TeacherVoice("ff_siwis", "fr-FR"), voice(TeacherVoiceEngine.KOKORO_82M, "fr-FR"))
        assertEquals(TeacherVoice("if_sara", "it-IT"), voice(TeacherVoiceEngine.KOKORO_82M, "it-IT"))
        assertEquals(TeacherVoice("pf_dora", "pt-BR"), voice(TeacherVoiceEngine.KOKORO_82M, "pt-BR"))
        assertEquals(TeacherVoice("jf_alpha", "ja-JP"), voice(TeacherVoiceEngine.KOKORO_82M, "ja-JP"))
        assertEquals(TeacherVoice("zf_xiaoxiao", "zh-CN"), voice(TeacherVoiceEngine.KOKORO_82M, "zh-CN"))
    }

    @Test fun pocketTtsSpeaksEveryLanguageModuleItPackagesAVoiceFor() {
        assertEquals(TeacherVoice("alba", "en-US"), voice(TeacherVoiceEngine.POCKET_TTS_100M, "en"))
        assertEquals(TeacherVoice("estelle", "fr-FR"), voice(TeacherVoiceEngine.POCKET_TTS_100M, "fr-FR"))
        assertEquals(TeacherVoice("juergen", "de-DE"), voice(TeacherVoiceEngine.POCKET_TTS_100M, "de-DE"))
        assertEquals(TeacherVoice("lola", "es-ES"), voice(TeacherVoiceEngine.POCKET_TTS_100M, "es-ES"))
        assertEquals(TeacherVoice("giovanni", "it-IT"), voice(TeacherVoiceEngine.POCKET_TTS_100M, "it-IT"))
        assertEquals(TeacherVoice("rafael", "pt-BR"), voice(TeacherVoiceEngine.POCKET_TTS_100M, "pt-BR"))
        assertEquals(TeacherVoice("daan", "nl-NL"), voice(TeacherVoiceEngine.POCKET_TTS_100M, "nl-NL"))
        // German and Dutch are PocketTTS's alone: Kokoro packages no voice for either.
        assertNull(voice(TeacherVoiceEngine.KOKORO_82M, "de-DE"))
        assertNull(voice(TeacherVoiceEngine.KOKORO_82M, "nl-NL"))
    }

    @Test fun noOpenWeightVoiceSpeaksEuropeanPortugueseSoThePickerSaysBrazilianInstead() {
        // Both models' Portuguese voices are Brazilian, so a Portugal module hears another variety.
        assertEquals(TeacherVoice("pf_dora", "pt-BR"), voice(TeacherVoiceEngine.KOKORO_82M, "pt-PT"))
        assertEquals(TeacherVoice("rafael", "pt-BR"), voice(TeacherVoiceEngine.POCKET_TTS_100M, "pt-PT"))
        assertTrue(TeacherVoiceSelector.speaksAnotherVariety("pt-PT"))
        // A Brazilian module hears its own variety, and so does every other covered language.
        assertFalse(TeacherVoiceSelector.speaksAnotherVariety("pt-BR"))
        assertFalse(TeacherVoiceSelector.speaksAnotherVariety("es-ES"))
        assertFalse(TeacherVoiceSelector.speaksAnotherVariety("en"))
        // A language no open-weight voice speaks has nothing to note either.
        assertFalse(TeacherVoiceSelector.speaksAnotherVariety("nb-NO"))
    }

    @Test fun aLanguageNoOpenWeightVoiceSpeaksHasNoVoiceAndThePlatformVoiceHasNoVoiceID() {
        for (locale in listOf("nb-NO", "sr-Latn-RS", "el-GR", "tl-PH", "ru-RU", "kok-Latn", "jam")) {
            assertNull(voice(TeacherVoiceEngine.KOKORO_82M, locale))
            assertNull(voice(TeacherVoiceEngine.POCKET_TTS_100M, locale))
        }
        // Mandarin and Japanese are Kokoro's alone.
        assertNull(voice(TeacherVoiceEngine.POCKET_TTS_100M, "zh-CN"))
        assertNull(voice(TeacherVoiceEngine.POCKET_TTS_100M, "ja-JP"))
        // The phone's own voice takes the module's locale itself, so it needs no voice id from Mural.
        assertNull(voice(TeacherVoiceEngine.PLATFORM, "es-ES"))
    }

    @Test fun theChosenOpenWeightVoiceSpeaksWhenItsModelIsOnThePhoneAndSpeaksTheLanguage() {
        assertEquals(TeacherVoiceEngine.KOKORO_82M,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.KOKORO_82M, voiceModelOnPhone = true, locale = "pt-PT"))
        assertEquals(TeacherVoiceEngine.POCKET_TTS_100M,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.POCKET_TTS_100M, voiceModelOnPhone = true, locale = "nl-NL"))
    }

    @Test fun thePlatformVoiceIsTheDefaultAndStaysTheChoiceOfALearnerWhoNeverPicked() {
        assertEquals(TeacherVoiceEngine.PLATFORM,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.PLATFORM, voiceModelOnPhone = true, locale = "pt-PT"))
        assertEquals(TeacherVoiceEngine.PLATFORM,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.PLATFORM, voiceModelOnPhone = false, locale = "es-ES"))
    }

    @Test fun aVoiceThatIsNotOnThePhoneOrDoesNotSpeakTheLanguageFallsBackToThePlatformVoice() {
        // Not downloaded, or deleted: the teacher still speaks, with the phone's own voice.
        assertEquals(TeacherVoiceEngine.PLATFORM,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.KOKORO_82M, voiceModelOnPhone = false, locale = "pt-PT"))
        // Downloaded, but Norwegian is a language no open-weight voice speaks.
        assertEquals(TeacherVoiceEngine.PLATFORM,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.KOKORO_82M, voiceModelOnPhone = true, locale = "nb-NO"))
        assertEquals(TeacherVoiceEngine.PLATFORM,
            TeacherVoiceSelector.resolve(TeacherVoiceEngine.POCKET_TTS_100M, voiceModelOnPhone = true, locale = "ja-JP"))
    }
}
