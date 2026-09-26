package chat.mural.core

enum class ProviderFailureKind {
    authentication, modelAccess, creditExhausted, spendLimit, usageLimit, quota, rateLimit, unavailable, invalidRequest, unknown;

    companion object {
        fun realtimeCode(value: String?): ProviderFailureKind? = when (safeCode(value)) {
            "invalid_api_key" -> authentication
            "credit_balance_exhausted" -> creditExhausted
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded" -> spendLimit
            "organization_usage_limit_exceeded" -> usageLimit
            "insufficient_quota" -> quota
            else -> null
        }
        fun classify(status: Int, code: String?): ProviderFailureKind = when {
            status == 401 -> authentication
            status == 403 || status == 404 -> modelAccess
            status == 429 -> when (code) {
                "credit_balance_exhausted" -> creditExhausted
                "organization_spend_limit_exceeded", "project_spend_limit_exceeded" -> spendLimit
                "organization_usage_limit_exceeded" -> usageLimit
                "insufficient_quota" -> quota
                else -> rateLimit
            }
            status == 408 || status >= 500 -> unavailable
            status == 400 || status == 422 -> invalidRequest
            else -> unknown
        }
        fun safeCode(value: String?): String? = value?.takeIf { it in setOf(
            "invalid_api_key", "insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded", "organization_usage_limit_exceeded", "rate_limit_exceeded", "slow_down",
            "model_not_found", "permission_denied", "server_error") }
        fun safeReference(value: String?): String? = value?.takeIf { Regex("[A-Za-z0-9_-]{1,128}").matches(it) }
    }
}
