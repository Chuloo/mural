package chat.mural.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The learner's caption of a native audio input turn: the model hears the speech and writes what it
 * heard in a `<heard>` block, which Mural lifts out as the learner's transcript and never speaks.
 */
class OnDeviceHeardCaptionTest {
    @Test fun anOpenBlockHoldsTheTeachersSpeechBack() {
        assertEquals(null to "", OnDeviceHeardCaption.split(""))
        assertEquals(null to "", OnDeviceHeardCaption.split("<he"))
        assertEquals(null to "", OnDeviceHeardCaption.split("<heard>Buenos d"))
        assertEquals(null to "", OnDeviceHeardCaption.split("<heard>Buenos días</heard"))
    }

    @Test fun aClosedBlockYieldsTheLearnersWordsAndTheCleanTeacherText() {
        assertEquals("Buenos días" to "", OnDeviceHeardCaption.split("<heard>Buenos días</heard>"))
        assertEquals("Buenos días" to "¡Hola!", OnDeviceHeardCaption.split("<heard>Buenos días</heard>¡Hola!"))
        assertEquals("Buenos días" to "¡Hola!", OnDeviceHeardCaption.split("<heard> Buenos días </heard> ¡Hola!"))
    }

    @Test fun aReplyWithoutTheBlockIsAllTeacherSpeech() {
        assertEquals(null to "¡Hola!", OnDeviceHeardCaption.split("¡Hola!"))
        assertEquals(null to "¡Hola! ¿Cómo", OnDeviceHeardCaption.split("¡Hola! ¿Cómo"))
    }

    @Test fun theRuleTellsTheModelToCaptionTheLearnerAndKeepTheTagsOutOfItsSpeech() {
        assertTrue(OnDeviceHeardCaption.rule, OnDeviceHeardCaption.rule.contains("<heard></heard>"))
        assertTrue(OnDeviceHeardCaption.rule, OnDeviceHeardCaption.rule.contains("never"))
    }
}
