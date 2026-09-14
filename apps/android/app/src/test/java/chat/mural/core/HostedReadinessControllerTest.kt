package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostedReadinessControllerTest {
    private val timestamp = 1_700_000_000_000L
    private val first = AccountSession("12345678-1234-1234-1234-123456789012", "a".repeat(43), timestamp + 86_400_000)
    private val second = AccountSession("22345678-1234-1234-1234-123456789012", "b".repeat(43), timestamp + 86_400_000)

    @Test fun signedOutAccountCannotBeRestoredByALateResponse() = runTest {
        var session: AccountSession? = first
        val response = CompletableDeferred<HostedReadiness>()
        val values = mutableListOf<HostedReadiness>()
        val readiness = HostedReadinessController(this, { session }, {
            withContext(NonCancellable) { response.await() }
        }, values::add) { timestamp }
        readiness.selectAccount(first.accountID, false); runCurrent()
        session = null; readiness.selectAccount(null, false)
        val signOutIndex = values.size
        response.complete(HostedReadiness(first.accountID, 60_000, true)); runCurrent()
        assertEquals(HostedReadiness(), readiness.value)
        assertTrue(values.drop(signOutIndex).none { it.enabled || it.accountID != null })
    }

    @Test fun refreshDuringAnExistingRequestChecksAgainAndRejectsTheOldResponse() = runTest {
        val response = CompletableDeferred<HostedReadiness>(); var calls = 0
        val readiness = HostedReadinessController(this, { first }, {
            if (++calls == 1) withContext(NonCancellable) { response.await() }
            else HostedReadiness(first.accountID, 45_000, true)
        }, {}) { timestamp }
        readiness.refresh(); runCurrent()
        readiness.refresh(); runCurrent()
        assertEquals(45_000, readiness.value.availableMilliseconds)
        response.complete(HostedReadiness(first.accountID, 60_000, true)); runCurrent()
        assertEquals(45_000, readiness.value.availableMilliseconds); assertEquals(2, calls)
    }

    @Test fun switchingAccountsCannotPublishThePreviousBalance() = runTest {
        var session = first; val response = CompletableDeferred<HostedReadiness>()
        val readiness = HostedReadinessController(this, { session }, { owner ->
            if (owner == first) withContext(NonCancellable) { response.await() }
            else HostedReadiness(second.accountID, 1_000, true)
        }, {}) { timestamp }
        readiness.selectAccount(first.accountID, false); runCurrent()
        session = second; readiness.selectAccount(second.accountID, false); runCurrent()
        response.complete(HostedReadiness(first.accountID, 60_000, true)); runCurrent()
        assertEquals(second.accountID, readiness.value.accountID)
        assertEquals(1_000, readiness.value.availableMilliseconds)
    }

    @Test fun reauthenticationOfTheSameAccountRejectsResultsFromItsOldBearer() = runTest {
        var session = first; val response = CompletableDeferred<HostedReadiness>()
        val readiness = HostedReadinessController(this, { session }, { response.await() }, {}) { timestamp }
        readiness.selectAccount(first.accountID, false); runCurrent()
        session = first.copy(accessToken = "b".repeat(43))
        response.complete(HostedReadiness(first.accountID, 60_000, true)); runCurrent()
        assertEquals(HostedReadiness(), readiness.value)
    }

    @Test fun accountTransitionClearsReadinessImmediatelyAndBlocksChecksUntilItFinishes() = runTest {
        var calls = 0
        val readiness = HostedReadinessController(this, { first }, {
            calls++; HostedReadiness(first.accountID, 60_000, true)
        }, {}) { timestamp }
        readiness.selectAccount(first.accountID, false); runCurrent(); assertTrue(readiness.value.ready)
        readiness.selectAccount(first.accountID, true); assertFalse(readiness.value.ready)
        readiness.refresh(); runCurrent(); assertEquals(1, calls)
        readiness.selectAccount(first.accountID, false); runCurrent()
        assertTrue(readiness.value.ready); assertEquals(2, calls)
    }

    @Test fun invalidOwnerAndExpiredSessionsCannotEnableHostedMinutes() = runTest {
        var session = first.copy(expiresAtMilliseconds = timestamp); var calls = 0
        val readiness = HostedReadinessController(this, { session }, {
            calls++; HostedReadiness(second.accountID, 60_000, true)
        }, {}) { timestamp }
        readiness.refresh(); runCurrent(); assertEquals(0, calls); assertFalse(readiness.value.ready)
        session = first; readiness.refresh(); runCurrent()
        assertFalse(readiness.value.ready); assertNull(readiness.value.accountID)
    }
}
