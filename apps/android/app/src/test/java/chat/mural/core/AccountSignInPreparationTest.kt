package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountSignInPreparationTest {
    @Test fun slowLocalStartupExplainsTimeoutWithoutInvokingOAuthOrCancellingThePendingWrite() = runTest {
        val write = Job(); var notices = 0; var accountChecks = 0
        val preparation = AccountSignInPreparation({ awaitLocalSignInStartup(write) { notices++ } },
            { accountChecks++; "guest-owner" }, { true }, { true })
        assertFalse(preparation.prepare()); assertEquals(1, notices); assertEquals(0, accountChecks)
        assertTrue(write.isActive)
        write.complete()
        assertTrue(preparation.prepare()); assertEquals(1, notices); assertEquals(1, accountChecks)
    }

    @Test fun cancellingStartupWaitDoesNotReportTimeoutOrAuthorizeSignIn() = runTest {
        val write = Job(); var notices = 0
        val waiting = async { awaitLocalSignInStartup(write) { notices++ } }
        yield(); waiting.cancelAndJoin()
        assertEquals(0, notices); assertTrue(write.isActive)
        write.cancel()
        assertTrue(awaitLocalSignInStartup(null) { error("Nothing to wait for") })
    }

    @Test fun exhaustedGuestCanReachOAuthWhileRemoteSettlementRemainsUnconfirmed() = runTest {
        var localClosed = false; var identityAdded = false
        val pending: String? = "guest-owner"
        val preparation = AccountSignInPreparation(
            { localClosed = true; true }, { pending },
            { assertTrue(localClosed); it == "guest-owner" },
            { error("Adding identity must not await the unsettled guest's remote accounting") })
        if (preparation.prepare()) identityAdded = true
        assertTrue(identityAdded); assertTrue(localClosed)
        assertEquals("guest-owner", pending)
    }
    @Test fun unknownOrMemberOwnerStillRequiresAccountChangeSettlement() = runTest {
        for (owner in listOf(null, "member-owner", "unknown-owner")) {
            var settled = false
            val preparation = AccountSignInPreparation({ true }, { owner }, { false }, { settled = true; false })
            assertFalse(preparation.prepare()); assertTrue(settled)
        }
    }
    @Test fun localPersistenceFailureAndCancellationNeverAuthorizeSignIn() = runTest {
        assertFalse(AccountSignInPreparation({ false }, { error("not locally ready") },
            { error("not locally ready") }, { error("not locally ready") }).prepare())
        try {
            AccountSignInPreparation({ throw CancellationException("Activity ended") }, { "guest" },
                { true }, { true }).prepare()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun ownerIsReadAfterLocalStartupFinishes() = runTest {
        var owner: String? = null
        val preparation = AccountSignInPreparation({ owner = "persisted-guest"; true }, { owner },
            { it == "persisted-guest" }, { error("Guest identity is now retained") })
        assertTrue(preparation.prepare())
    }
}
