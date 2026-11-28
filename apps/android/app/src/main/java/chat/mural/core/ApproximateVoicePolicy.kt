package chat.mural.core

/**
 * The Approximate Voice: a stand-in platform voice that reads a Typed-only Module's teacher replies
 * aloud in a related accent. Opt-in only, and never offered as speech recognition for the learner —
 * the learner's side stays typed because [isTypedOnly] is unchanged, so no microphone is offered.
 *
 * No Patwa voice exists on any phone, so Jamaican Patois is read by an English (Jamaica) voice and
 * the UI says so plainly. Roman Konkani has no entry in [localeTags], which is what keeps it
 * unaffected: no Settings row, no spoken teacher.
 */
object ApproximateVoicePolicy {
    /** The stand-in voice tag per Language Module. Deliberately no `kok-Latn` entry. */
    val localeTags: Map<String, String> = mapOf("jam" to "en-JM")

    /** The Settings row exists only for a module that has a stand-in voice. */
    fun offered(languageID: String) = languageID in localeTags

    /** Teacher replies are read aloud only when the opt-in is on and the module has a voice. */
    fun speaks(enabled: Boolean, languageID: String) = enabled && offered(languageID)

    /**
     * The tag to hand the platform engine: [wanted] when this phone has it, else another variety of
     * the same language, else null. Null changes nothing, so a phone with no matching voice keeps
     * the engine's own and never errors.
     */
    fun localeTag(wanted: String, available: Collection<String>): String? {
        if (wanted in available) return wanted
        val language = wanted.substringBefore('-')
        return available.firstOrNull { it.substringBefore('-') == language }
    }
}
