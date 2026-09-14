package chat.mural.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local provenance is separate from exported learning content and contains no credentials.
 * A hosted session cannot be recovered later using a personal API key. */
internal class ConversationProviderStore(context: Context) {
    private val preferences = context.getSharedPreferences("mural_conversation_providers", Context.MODE_PRIVATE)
    data class Snapshot(val hostedIDs: Set<String>, val pendingOwnerID: String?, val selection: ConversationProvider)
    suspend fun read(defaultSelection: ConversationProvider = ConversationProvider.PERSONAL_KEY) = withContext(Dispatchers.IO) {
        Snapshot(preferences.getStringSet("hosted", emptySet())?.toSet().orEmpty(),
            preferences.getString("pending_owner", null),
            runCatching { ConversationProvider.valueOf(preferences.getString("selection", null).orEmpty()) }
                .getOrDefault(defaultSelection))
    }
    suspend fun markHosted(localID: String, ownerID: String) = withContext(Dispatchers.IO) {
        val ids = preferences.getStringSet("hosted", emptySet()).orEmpty() + localID
        check(preferences.edit().putStringSet("hosted", ids).putString("pending_owner", ownerID).commit())
    }
    suspend fun clearPending() = withContext(Dispatchers.IO) { check(preferences.edit().remove("pending_owner").commit()) }
    suspend fun select(provider: ConversationProvider) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("selection", provider.name).commit())
    }
}
