package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

/**
 * The Approximate Voice as an opt-in stand-in for Jamaican Patois alone: the Settings row and the
 * spoken teacher both come from one map, so a module with no entry — Roman Konkani — is provably
 * unaffected, and the learner's side stays typed because `typedOnlyIDs` never changes.
 */
class ApproximateVoicePolicyTest {
    @Test fun theApproximateVoiceIsOfferedForPatwaAloneAndNeverForAnotherModule() {
        // This map is the whole policy: no `kok-Latn` entry means Konkani is offered nothing.
        assertEquals(mapOf("jam" to "en-JM"), ApproximateVoicePolicy.localeTags)
        for (module in AndroidLanguages.all)
            assertEquals(module.id, module.id == "jam", ApproximateVoicePolicy.offered(module.id))
        // An Approximate Voice belongs to a Typed-only Module: it reads the teacher, never the learner.
        assertTrue(ApproximateVoicePolicy.localeTags.keys.all { AndroidLanguages.get(it)!!.isTypedOnly })
    }

    @Test fun teacherRepliesAreReadAloudOnlyWhenTheOptInIsOnAndTheModuleHasAVoice() {
        assertTrue(ApproximateVoicePolicy.speaks(enabled = true, languageID = "jam"))
        assertFalse(ApproximateVoicePolicy.speaks(enabled = false, languageID = "jam"))
        for (module in AndroidLanguages.all.filterNot { it.id == "jam" })
            for (enabled in listOf(false, true))
                assertFalse("${module.id}/$enabled", ApproximateVoicePolicy.speaks(enabled, module.id))
    }

    @Test fun thePlatformVoicePrefersEnglishJamaicaAndFallsBackToAnotherEnglishWithoutError() {
        assertEquals("en-JM", ApproximateVoicePolicy.localeTag("en-JM", listOf("en-JM", "en-US", "nb-NO")))
        assertEquals("en-US", ApproximateVoicePolicy.localeTag("en-JM", listOf("nb-NO", "en-US", "en-GB")))
        assertEquals("en-GB", ApproximateVoicePolicy.localeTag("en-JM", listOf("en-GB")))
        // Null changes nothing: a phone with no English voice keeps the engine's own and never errors.
        assertNull(ApproximateVoicePolicy.localeTag("en-JM", listOf("nb-NO", "es-ES")))
        assertNull(ApproximateVoicePolicy.localeTag("en-JM", emptyList()))
        // Existing languages resolve the same way: a regional variety answers a bare language tag…
        assertEquals("nb-NO", ApproximateVoicePolicy.localeTag("nb", listOf("nb-NO", "en-US")))
        // …but `nb` is not `no`, so a near-miss is never silently substituted for the teacher's voice.
        assertNull(ApproximateVoicePolicy.localeTag("nb", listOf("no-NO", "en-US")))
    }
}
