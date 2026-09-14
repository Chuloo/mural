package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountControllerTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val timestamp = 1_700_000_000_000L
    private val validSession = AccountSession(id, "a".repeat(43), timestamp + 86_400_000)
    private inner class Store : AccountSessionStorage {
        var value: AccountSession? = null
        var failSave = false
        override suspend fun read() = value
        override suspend fun save(session: AccountSession) { if (failSave) throw AccountFailure.SecureStorage; value = session }
        override suspend fun clear() { value = null }
    }
    private inner class Service : AccountService {
        var exchanges = 0
        var available = true
        var profileFailure: AccountFailure? = null
        var deletionFailure: AccountFailure? = null
        var signOutFailure: AccountFailure? = null
        var signOuts = 0
        override suspend fun providers() = AccountProviders(available)
        override suspend fun challenge() = AccountChallenge(id, "b".repeat(64), 300)
        override suspend fun exchange(challenge: AccountChallenge, idToken: String, expectedAccountID: String?): AccountExchange {
            if (expectedAccountID != null && expectedAccountID != id) throw AccountFailure.Http(409, "same_account_required")
            exchanges++; return AccountExchange(id, validSession.accessToken, 86_400)
        }
        override suspend fun profile(session: AccountSession): AccountProfile {
            profileFailure?.let { throw it }; return AccountProfile(id, "me@example.test", listOf("google"), "2026-09-13")
        }
        override suspend fun minutes(session: AccountSession) = MinuteBalance("milliseconds", "connected-conversation-time", 581_234, 60_000, 521_234)
        override suspend fun signOut(session: AccountSession) { signOuts++; signOutFailure?.let { throw it } }
        override suspend fun delete(session: AccountSession) { deletionFailure?.let { throw it } }
    }
    @Test fun signInPreservesExactServerBalanceAndStoresNoProviderToken() = runTest {
        val store = Store(); val api = Service(); val controller = AccountController(api, store) { timestamp }
        controller.signIn { nonce -> assertEquals("b".repeat(64), nonce); "provider-token" }
        assertEquals(521_234, controller.state.value.minutes!!.availableMilliseconds)
        assertEquals("me@example.test", controller.state.value.email); assertEquals(validSession, store.value)
        assertFalse(controller.state.value.toString().contains(validSession.accessToken))
        controller.signIn { error("signed-in user must not start another exchange") }
        assertEquals(1, api.exchanges)
    }
    @Test fun expiredSessionIsRemovedWithoutPretendingUserIsSignedIn() = runTest {
        val store = Store().apply { value = validSession.copy(expiresAtMilliseconds = timestamp) }
        val controller = AccountController(Service(), store) { timestamp }; controller.restore()
        assertNull(store.value); assertFalse(controller.state.value.signedIn)
    }
    @Test fun recoveryRejectsDifferentAccountBeforeSavingButAllowsOriginalOwner() = runTest {
        val store = Store(); val api = Service(); val controller = AccountController(api, store) { timestamp }
        controller.signIn(expectedAccountID = "87654321-4321-4321-4321-210987654321") { "provider-token" }
        assertNull(store.value); assertFalse(controller.state.value.signedIn)
        assertEquals(AccountNotice.SAME_ACCOUNT_REQUIRED, controller.state.value.notice); assertEquals(0, api.signOuts)
        assertEquals(0, api.exchanges)
        controller.signIn(expectedAccountID = id) { "provider-token" }
        assertEquals(validSession, store.value); assertTrue(controller.state.value.signedIn)
    }
    @Test fun cancelledChooserAllowsRetryWithoutExchangingOrSaving() = runTest {
        val store = Store(); val api = Service(); val controller = AccountController(api, store) { timestamp }
        val started = CompletableDeferred<Unit>()
        val first = launch { controller.signIn { started.complete(Unit); awaitCancellation() } }
        started.await(); first.cancelAndJoin()
        assertFalse(controller.state.value.busy); assertNull(store.value); assertEquals(0, api.exchanges)
        controller.signIn { "retry" }; assertEquals(1, api.exchanges); assertTrue(controller.state.value.signedIn)
    }
    @Test fun simultaneousTapCannotStartSecondCredentialChooser() = runTest {
        val store = Store(); val api = Service(); val controller = AccountController(api, store) { timestamp }
        val started = CompletableDeferred<Unit>(); val result = CompletableDeferred<String>()
        val first = launch { controller.signIn { started.complete(Unit); result.await() } }
        started.await(); controller.signIn { error("second chooser") }; result.complete("token"); first.join()
        assertEquals(1, api.exchanges)
    }
    @Test fun disabledProviderOrExpiredChallengeNeverExchanges() = runTest {
        val store = Store(); val api = Service().apply { available = false }; var current = timestamp
        val controller = AccountController(api, store) { current }
        controller.signIn { error("disabled provider") }; assertEquals(0, api.exchanges)
        api.available = true
        controller.signIn { current += 300_000; "too-late" }; assertEquals(0, api.exchanges)
        assertEquals(AccountNotice.GOOGLE, controller.state.value.notice)
    }
    @Test fun storageFailureDoesNotAdvertiseSignedInAccount() = runTest {
        val store = Store().apply { failSave = true }; val controller = AccountController(Service(), store) { timestamp }
        controller.signIn { "token" }
        assertFalse(controller.state.value.signedIn); assertEquals(AccountNotice.SECURE_STORAGE, controller.state.value.notice)
    }
    @Test fun temporaryProfileFailureKeepsSessionButRevocationRemovesIt() = runTest {
        val store = Store().apply { value = validSession }; val api = Service().apply { profileFailure = AccountFailure.Unavailable }
        val controller = AccountController(api, store) { timestamp }; controller.restore()
        assertTrue(controller.state.value.signedIn); assertNull(controller.state.value.minutes); assertEquals(validSession, store.value)
        api.profileFailure = AccountFailure.Http(401, "sign_in_required"); controller.refresh()
        assertNull(store.value); assertFalse(controller.state.value.signedIn); assertEquals(AccountNotice.SIGN_IN_AGAIN, controller.state.value.notice)
    }
    @Test fun offlineSignOutClearsLocalSessionAndExplainsRemoteStatus() = runTest {
        val store = Store().apply { value = validSession }; val api = Service().apply { signOutFailure = AccountFailure.Unavailable }
        val controller = AccountController(api, store) { timestamp }; controller.restore(); controller.signOut()
        assertNull(store.value); assertFalse(controller.state.value.signedIn); assertEquals(AccountNotice.SIGNED_OUT_LOCALLY, controller.state.value.notice)
    }
    @Test fun blockedDeletionRetainsAccountAndSuccessfulDeletionClearsIt() = runTest {
        val store = Store().apply { value = validSession }; val api = Service().apply { deletionFailure = AccountFailure.Http(409, "unresolved_billing") }
        val controller = AccountController(api, store) { timestamp }; controller.restore(); controller.delete()
        assertEquals(validSession, store.value); assertEquals(AccountNotice.BILLING_UNRESOLVED, controller.state.value.notice)
        api.deletionFailure = null; controller.delete()
        assertNull(store.value); assertEquals(AccountNotice.DELETED, controller.state.value.notice)
    }
}
