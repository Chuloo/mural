package chat.mural.network

import android.content.Context

enum class AIProvider(
    val displayName: String,
    val helperModel: String,
    val liveModel: String,
    val keyUrl: String,
    val usageUrl: String,
    val dataControlsUrl: String,
) {
    OPENAI("OpenAI", "gpt-5.6-luna", "gpt-live-1", "https://platform.openai.com/api-keys", "https://platform.openai.com/usage", "https://developers.openai.com/api/docs/guides/your-data"),
    GOOGLE_AI_STUDIO("Google AI Studio", "gemma-4-31b-it", "gemini-3.8-live", "https://aistudio.google.com/app/apikey", "https://aistudio.google.com/app/usage", "https://ai.google.dev/gemini-api/docs/usage-policies"),
}

object AIProviderSelection {
    private const val PREFERENCES = "mural_ai_provider"
    private const val PROVIDER = "provider"

    fun read(context: Context): AIProvider {
        val raw = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(PROVIDER, null)
        return AIProvider.entries.firstOrNull { it.name == raw } ?: AIProvider.OPENAI
    }

    fun save(context: Context, provider: AIProvider) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(PROVIDER, provider.name).apply()
    }
}
