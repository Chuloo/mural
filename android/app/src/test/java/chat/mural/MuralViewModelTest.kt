package chat.mural

import chat.mural.network.APIClient
import chat.mural.network.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuralViewModelTest {
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
}
