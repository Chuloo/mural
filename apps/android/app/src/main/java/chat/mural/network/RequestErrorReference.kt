package chat.mural.network

/** A server-generated diagnostic reference, never arbitrary response text or an account ID. */
internal fun safeRequestErrorReference(value: String?): String? =
    value?.takeIf { Regex("[a-f0-9]{12}").matches(it) }
