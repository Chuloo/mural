package chat.mural.core

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationProviderStoreTest {
    @Test fun onDeviceSelectionAndProvenanceSurviveARestartBesideHostedRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("chat.mural.android.uitest", context.packageName)
        val preferences = context.getSharedPreferences("mural_conversation_providers", Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().commit()
            val store = ConversationProviderStore(context)
            // Nothing stored yet: the caller's default decides, and no provenance exists.
            assertEquals(ConversationProvider.HOSTED_MINUTES, store.read(ConversationProvider.HOSTED_MINUTES).selection)
            assertTrue(store.read().onDeviceIDs.isEmpty())
            store.select(ConversationProvider.ON_DEVICE)
            store.markOnDevice("local-on-device")
            store.markHosted("local-hosted", "owner")
            // A restart reads the same file: the explicit choice persists, and each Conversation
            // Provider keeps its own provenance set.
            val reopened = ConversationProviderStore(context).read()
            assertEquals(ConversationProvider.ON_DEVICE, reopened.selection)
            assertEquals(setOf("local-on-device"), reopened.onDeviceIDs)
            assertEquals(setOf("local-hosted"), reopened.hostedIDs)
            assertEquals("owner", reopened.pendingOwnerID)
            store.clearPending()
            assertNull(ConversationProviderStore(context).read().pendingOwnerID)
            // An Untimed Conversation is opt-in: nothing stored means the timed default.
            assertFalse(ConversationProviderStore(context).read().untimedOnDevice)
            store.setUntimedOnDevice(true)
            assertTrue(ConversationProviderStore(context).read().untimedOnDevice)
            store.setUntimedOnDevice(false)
            assertFalse(ConversationProviderStore(context).read().untimedOnDevice)
            // An Approximate Voice is opt-in too: nothing stored means a silent teacher.
            assertFalse(ConversationProviderStore(context).read().approximateVoice)
            store.setApproximateVoice(true)
            assertTrue(ConversationProviderStore(context).read().approximateVoice)
            store.setApproximateVoice(false)
            assertFalse(ConversationProviderStore(context).read().approximateVoice)
        } finally { preferences.edit().clear().commit() }
    }
}
