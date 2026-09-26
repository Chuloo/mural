package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class ProviderFailureTest {
    @Test fun creditAndTemporaryLimitsRequireDifferentActions() {
        assertEquals(ProviderFailureKind.quota, ProviderFailureKind.classify(429, "insufficient_quota"))
        assertEquals(ProviderFailureKind.rateLimit, ProviderFailureKind.classify(429, "rate_limit_exceeded"))
        assertEquals(ProviderFailureKind.authentication, ProviderFailureKind.classify(401, "insufficient_quota"))
        assertEquals(ProviderFailureKind.unavailable, ProviderFailureKind.classify(503, null))
    }
    @Test fun arbitraryProviderDetailsCannotBecomeVisibleReferencesOrCategories() {
        assertNull(ProviderFailureKind.safeCode("private_value"))
        assertNull(ProviderFailureKind.safeReference("private\nheader"))
        assertNull(ProviderFailureKind.safeReference("x".repeat(129)))
        assertEquals("req_support", ProviderFailureKind.safeReference("req_support"))
    }
    @Test fun specificBillingCodesNeverBecomeTemporaryRateLimits() {
        val cases = mapOf(
            "credit_balance_exhausted" to ProviderFailureKind.creditExhausted,
            "organization_spend_limit_exceeded" to ProviderFailureKind.spendLimit,
            "project_spend_limit_exceeded" to ProviderFailureKind.spendLimit,
            "organization_usage_limit_exceeded" to ProviderFailureKind.usageLimit,
            "insufficient_quota" to ProviderFailureKind.quota,
            "slow_down" to ProviderFailureKind.rateLimit,
        )
        cases.forEach { (code, kind) ->
            assertEquals(code, kind, ProviderFailureKind.classify(429, ProviderFailureKind.safeCode(code)))
            assertEquals(ProviderFailureKind.authentication, ProviderFailureKind.classify(401, code))
        }
    }
    @Test fun realtimeErrorsStopOnlyForKnownKeyOrBillingFailures() {
        assertEquals(ProviderFailureKind.creditExhausted, ProviderFailureKind.realtimeCode("credit_balance_exhausted"))
        assertEquals(ProviderFailureKind.spendLimit, ProviderFailureKind.realtimeCode("project_spend_limit_exceeded"))
        assertEquals(ProviderFailureKind.authentication, ProviderFailureKind.realtimeCode("invalid_api_key"))
        assertNull(ProviderFailureKind.realtimeCode("slow_down"))
        assertNull(ProviderFailureKind.realtimeCode("private_api_key_here"))
    }
}
