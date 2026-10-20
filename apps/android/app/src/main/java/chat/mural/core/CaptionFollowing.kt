package chat.mural.core

/** A touch pauses this passage, including later streaming revisions. */
data class CaptionFollowing(val passageID: String? = null, val interrupted: Boolean = false) {
    fun receive(id: String?) = if (id == passageID) this else CaptionFollowing(id)
    fun interrupt() = copy(interrupted = true)
    fun nextOffset(current: Double, maximum: Double, elapsed: Double, reducedMotion: Boolean = false): Double {
        if (passageID == null || interrupted) return current
        val end = maximum.coerceAtLeast(0.0)
        if (reducedMotion) return end
        return minOf(end, current.coerceAtLeast(0.0) + 24 * elapsed.coerceIn(0.0, 0.1))
    }
}
