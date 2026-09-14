package chat.mural.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class HostedHelperDiagnosticsTest {
    @Test fun diagnosticContainsOnlyPurposeStatusAndRecognizedCode() {
        assertEquals("purpose=meaning http=429 code=helper_session_limit",
            HostedHelperDiagnostics.describe(HelperPurpose.MEANING, HostedFailure.Http(429, "helper_session_limit")))
        assertEquals("purpose=assessment http=409 code=helper_session_window_closed",
            HostedHelperDiagnostics.describe(HelperPurpose.ASSESSMENT, HostedFailure.Http(409, "helper_session_window_closed")))
    }

    @Test fun unknownErrorTextAndCodesCannotExposeSensitiveData() {
        val sensitive = "Bearer test-secret email@example.invalid https://example.invalid/private synthetic transcript"
        for (failure in listOf(IOException(sensitive), CancellationException(sensitive), HostedFailure.Http(500, sensitive))) {
            val output = HostedHelperDiagnostics.describe(HelperPurpose.MEANING, failure)
            assertFalse(output.contains(sensitive))
            assertFalse(output.contains("Bearer")); assertFalse(output.contains("@")); assertFalse(output.contains("https:"))
        }
        assertEquals("purpose=meaning http=0 code=unrecognized",
            HostedHelperDiagnostics.describe(HelperPurpose.MEANING, HostedFailure.Http(Int.MAX_VALUE, sensitive)))
    }

    @Test fun nonHttpFailuresUseFixedCategoriesWithoutExceptionMessages() {
        assertEquals("purpose=meaning failure=unconfirmed", HostedHelperDiagnostics.describe(HelperPurpose.MEANING, HostedFailure.Unconfirmed))
        assertEquals("purpose=meaning failure=invalid_response", HostedHelperDiagnostics.describe(HelperPurpose.MEANING, HostedFailure.InvalidResponse))
        assertEquals("purpose=meaning failure=sign_in_required", HostedHelperDiagnostics.describe(HelperPurpose.MEANING, HostedFailure.SignInRequired))
    }
}
