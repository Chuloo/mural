package chat.mural.core

import java.util.Locale

data class UsageSummary(val voiceTime: String, val voiceEstimate: String, val searchCalls: Int) {
    companion object {
        const val VOICE_USD_PER_MINUTE = 0.05

        fun of(sessions: List<SessionRecord>): UsageSummary {
            val seconds = sessions.sumOf { it.voiceSeconds }
            return UsageSummary(
                voiceTime = "${(seconds / 60).toInt()} min ${seconds.toInt() % 60} s",
                voiceEstimate = String.format(Locale.US, "$%.2f USD", seconds / 60 * VOICE_USD_PER_MINUTE),
                searchCalls = sessions.sumOf { it.searchCalls },
            )
        }
    }
}
