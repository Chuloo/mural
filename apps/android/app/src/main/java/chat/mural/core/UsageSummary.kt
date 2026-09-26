package chat.mural.core

data class UsageSummary(val voiceTime: String, val searchCalls: Int) {
    companion object {
        fun of(sessions: List<SessionRecord>): UsageSummary {
            val seconds = sessions.sumOf { it.voiceSeconds }
            return UsageSummary(
                voiceTime = "${(seconds / 60).toInt()} min ${seconds.toInt() % 60} s",
                searchCalls = sessions.sumOf { it.searchCalls },
            )
        }
    }
}
