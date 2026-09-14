package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountMutationCoordinatorTest {
    private val timestamp = 1_700_000_000_000L
    private val session = AccountSession("12345678-1234-1234-1234-123456789012", "a".repeat(43), timestamp + 86_400_000)
    private inner class Store : AccountSessionStorage {
        var value: AccountSession? = session
        override suspend fun read() = value
        override suspend fun save(session: AccountSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private inner class Service(private val completed: CompletableDeferred<Unit>) : AccountService {
        var mutations = 0
        override suspend fun providers() = AccountProviders(true)
        override suspend fun challenge(): AccountChallenge = error("No sign-in")
        override suspend fun exchange(challenge: AccountChallenge, idToken: String, expectedAccountID: String?): AccountExchange = error("No sign-in")
        override suspend fun profile(session: AccountSession) = AccountProfile(session.accountID, "learner@example.test", listOf("google"), "2026-09-13")
        override suspend fun minutes(session: AccountSession) = MinuteBalance("milliseconds", "connected-conversation-time", 60_000, 0, 60_000)
        override suspend fun signOut(session: AccountSession) { mutations++; completed.await() }
        override suspend fun delete(session: AccountSession) { mutations++; completed.await() }
    }

    @Test fun signOutSurvivesTheActivityScopeAndClearsItsOldBalance() = runTest {
        val completed = CompletableDeferred<Unit>(); val storage = Store(); val service = Service(completed)
        val account = AccountController(service, storage) { timestamp }; account.restore()
        val changes = AccountMutationCoordinator(this)
        val activity = launch {
            assertTrue(changes.submit({ true }, account::signOut))
            awaitCancellation()
        }
        runCurrent(); assertTrue(changes.busy.value)
        activity.cancelAndJoin()
        assertTrue(changes.busy.value); assertNotNull(storage.value)
        assertFalse(changes.submit({ true }, account::delete))
        completed.complete(Unit); runCurrent()
        assertFalse(changes.busy.value); assertNull(storage.value)
        assertFalse(account.state.value.signedIn); assertNull(account.state.value.minutes)
        assertEquals(1, service.mutations)
    }

    @Test fun confirmedDeletionAlsoSurvivesAnActivityWaiterBeingCancelled() = runTest {
        val completed = CompletableDeferred<Unit>(); val storage = Store(); val service = Service(completed)
        val account = AccountController(service, storage) { timestamp }; account.restore()
        val changes = AccountMutationCoordinator(this)
        val activity = launch { changes.submit({ true }, account::delete); awaitCancellation() }
        runCurrent(); activity.cancelAndJoin(); completed.complete(Unit); runCurrent()
        assertNull(storage.value); assertEquals(AccountNotice.DELETED, account.state.value.notice)
        assertFalse(changes.busy.value)
    }

    @Test fun unresolvedConversationNeverAuthorizesAnAccountMutation() = runTest {
        var changed = false; var finished = false
        val changes = AccountMutationCoordinator(this)
        changes.submit({ false }, { changed = true }, { finished = true })
        assertFalse(changed); assertTrue(finished); assertFalse(changes.busy.value)
    }

    @Test fun CancelledSettlementCannotPerformMutationEvenIfItReturnsSuccessLate() = runTest {
        val parent = SupervisorJob(coroutineContext[Job])
        val retained = CoroutineScope(coroutineContext + parent)
        val completed = CompletableDeferred<Unit>(); var changed = false
        val changes = AccountMutationCoordinator(retained)
        changes.submit({ withContext(NonCancellable) { completed.await(); true } }, { changed = true })
        parent.cancel(); completed.complete(Unit); runCurrent()
        assertFalse(changed); assertFalse(changes.busy.value)
    }

    @Test fun interactiveChooserSharesTheGuardAndOldCompletionCannotUnlockAnotherOperation() = runTest {
        val changes = AccountMutationCoordinator(this)
        val first = changes.begin()!!
        assertTrue(changes.busy.value); assertNull(changes.begin())
        assertFalse(changes.submit({ true }, { error("Chooser is active") }))
        changes.end(first)
        val second = changes.begin()!!
        changes.end(first); assertTrue(changes.busy.value)
        changes.end(second); assertFalse(changes.busy.value)
    }
}
