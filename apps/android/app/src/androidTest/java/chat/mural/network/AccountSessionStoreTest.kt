package chat.mural.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.AccountSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountSessionStoreTest {
    @Test fun concurrentViewModelsCannotMixEncryptedRecordsOrEraseARefreshedLogin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("chat.mural.android.uitest", context.packageName)
        val origin = "https://account.example.test/"
        val stores = List(5) { AccountSessionStore(context, origin) }
        val sessions = List(48) { index -> AccountSession("12345678-1234-1234-1234-123456789012",
            ('a' + index % 26).toString().repeat(43), System.currentTimeMillis() + 80_000_000 + index) }
        try {
            stores.first().save(sessions.first())
            val start = CompletableDeferred<Unit>()
            val writer = launch(Dispatchers.Default) {
                start.await()
                for (session in sessions) { stores.first().save(session); yield() }
            }
            val readers = stores.drop(1).map { store -> launch(Dispatchers.Default) {
                start.await()
                repeat(64) {
                    assertTrue("A concurrent read lost or corrupted the saved account", store.read() in sessions)
                    yield()
                }
            } }
            start.complete(Unit)
            writer.join(); readers.joinAll()
            stores.forEach { assertEquals(sessions.last(), it.read()) }
            stores.last().clear()
            stores.forEach { assertNull(it.read()) }
        } finally { withContext(NonCancellable) { stores.first().clear() } }
    }
    @Test fun bearerSurvivesRecreationButCannotBeReadFromAnotherOriginAndClearsIndependentlyOfOpenAIKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("chat.mural.android.uitest", context.packageName)
        val origin = "https://account.example.test/"
        val store = AccountSessionStore(context, origin)
        val apiKey = CredentialStore(context, "mural_account_test_openai", "chat.mural.account.test.openai")
        val session = AccountSession("12345678-1234-1234-1234-123456789012", "a".repeat(43), System.currentTimeMillis() + 86_400_000)
        try {
            apiKey.save("sk-offline-account-test-credential")
            store.save(session)
            val raw = context.getSharedPreferences("mural_account_session", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(raw.contains(session.accessToken)); assertFalse(raw.contains(session.accountID))
            assertEquals(session, AccountSessionStore(context, origin).read())
            assertNull(AccountSessionStore(context, "https://different.example.test/").read())
            assertNull(store.read()); assertTrue(apiKey.hasKey)
            store.save(session); store.clear(); assertNull(store.read()); assertTrue(apiKey.hasKey)
        } finally { store.clear(); apiKey.delete() }
    }
}
