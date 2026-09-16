package chat.mural.core

/** Shared caps for learner-authored text. Both clients must refuse or stop accepting
 *  input past these lengths instead of silently truncating on save/send. */
object TextLimits {
    const val TYPED_REPLY_CHARACTERS = 2_000
    const val CORRECTION_CHARACTERS = 10_000

    fun clampTypedReply(text: String): String = text.take(TYPED_REPLY_CHARACTERS)
    fun clampCorrection(text: String): String = text.take(CORRECTION_CHARACTERS)
    fun typedReplyExceedsLimit(text: String): Boolean = text.length > TYPED_REPLY_CHARACTERS
    fun correctionExceedsLimit(text: String): Boolean = text.length > CORRECTION_CHARACTERS
}
