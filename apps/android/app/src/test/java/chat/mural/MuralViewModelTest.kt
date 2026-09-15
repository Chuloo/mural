package chat.mural

import chat.mural.core.AccountFailure
import chat.mural.network.HostedFailure
import chat.mural.core.Archive
import chat.mural.core.ArchiveCodec
import chat.mural.core.Fragment
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.network.APIClient
import chat.mural.network.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuralViewModelTest {
    @Test fun importedUnfinishedHistoryCannotLookLikeALocalInterruptedSession() {
        val partial = SessionRecord(languageID = "es", startedAt = 800_000_000.0).apply {
            append(Fragment(speaker = Speaker.user, text = "la radio", startMS = 0, endMS = 1_000))
        }
        val finished = SessionRecord(languageID = "fr", startedAt = 800_000_000.0, endedAt = 800_000_010.0,
            usageFinal = true, endReason = "Ended by you")
        val imported = prepareImportedArchive(ArchiveCodec.encode(Archive(sessions = mutableListOf(partial, finished))),
            importedAt = 800_000_020.0)
        assertEquals(800_000_020.0, imported.sessions[0].endedAt!!, 0.0)
        assertEquals(partial.fragments, imported.sessions[0].fragments)
        assertFalse(imported.sessions[0].usageFinal)
        assertEquals(finished, imported.sessions[1])
        assertEquals(imported, ArchiveCodec.decode(ArchiveCodec.encode(imported)))
    }

    @Test fun everyApiAndCredentialReasonMapsToANonZeroResource() {
        val reasons = listOf(
            APIClient.APIException.MissingKey,
            APIClient.APIException.Refused,
            APIClient.APIException.InvalidResponse,
            APIClient.APIException.Http(401),
            APIClient.APIException.Http(403),
            APIClient.APIException.Http(429),
            APIClient.APIException.Http(500),
            CredentialStore.CredentialException.Invalid,
            CredentialStore.CredentialException.Save,
            CredentialStore.CredentialException.Remove,
        )
        reasons.forEach { assertTrue("expected a resource for $it", errorMessageRes(it) != 0) }
    }

    @Test fun incompleteResponseSharesTheSameResourceAsInvalidResponse() {
        assertEquals(errorMessageRes(APIClient.APIException.InvalidResponse), errorMessageRes(APIClient.APIException.Incomplete))
    }

    @Test fun httpReasonsWithTheSameMeaningShareAResourceButOthersDiffer() {
        assertEquals(errorMessageRes(APIClient.APIException.Http(403)), errorMessageRes(APIClient.APIException.Http(404)))
        val distinctReasons = listOf(
            APIClient.APIException.MissingKey,
            APIClient.APIException.Refused,
            APIClient.APIException.InvalidResponse,
            APIClient.APIException.Http(401),
            APIClient.APIException.Http(403),
            APIClient.APIException.Http(429),
            APIClient.APIException.Http(500),
            CredentialStore.CredentialException.Invalid,
            CredentialStore.CredentialException.Save,
            CredentialStore.CredentialException.Remove,
        )
        val ids = distinctReasons.map { errorMessageRes(it) }
        assertEquals("distinct reasons must map to distinct resources", ids.size, ids.toSet().size)
    }

    @Test fun unmappedThrowableHasNoResource() {
        assertEquals(0, errorMessageRes(IllegalStateException("unexpected")))
    }

    @Test fun onlyMissingKeyAndUnauthorizedNeedKeySetup() {
        assertTrue(errorNeedsKeySetup(APIClient.APIException.MissingKey))
        assertTrue(errorNeedsKeySetup(APIClient.APIException.Http(401)))
        assertFalse(errorNeedsKeySetup(APIClient.APIException.Http(403)))
        assertFalse(errorNeedsKeySetup(APIClient.APIException.Refused))
        assertFalse(errorNeedsKeySetup(CredentialStore.CredentialException.Invalid))
    }
    @Test fun hostedSignInFailuresRecoverThroughAccountAndNeverPersonalKeySetup() {
        for (error in listOf(HostedFailure.SignInRequired, HostedFailure.Http(401, "sign_in_required"),
            HostedFailure.Http(403, "sign_in_to_continue"), AccountFailure.Http(401, "sign_in_required"))) {
            assertEquals(R.string.hosted_sign_in_again, errorMessageRes(error))
            assertTrue(needsAccountRecovery(error))
            assertFalse(errorNeedsKeySetup(error))
        }
        assertFalse(needsAccountRecovery(HostedFailure.Http(503, "internal")))
        assertEquals(R.string.hosted_rate_limit, errorMessageRes(HostedFailure.Http(429, "rate_limit")))
        assertEquals(R.string.hosted_start_rejected, errorMessageRes(HostedFailure.Http(502, "provider_create_rejected")))
    }

    @Test fun onlySafeOpaqueReferencesReachTheErrorMessage() {
        assertEquals("0123abcdef45", requestErrorReference(HostedFailure.Http(500, "internal", reference = "0123abcdef45")))
        assertEquals("0123abcdef45", requestErrorReference(AccountFailure.Http(500, "internal", "0123abcdef45")))
        for (value in listOf("person@example.com", "0123ABCDEF45", "0123abcdef45\n", "", "123")) {
            assertEquals(null, requestErrorReference(HostedFailure.Http(500, "internal", reference = value)))
            assertEquals(null, requestErrorReference(AccountFailure.Http(500, "internal", value)))
        }
    }

}
