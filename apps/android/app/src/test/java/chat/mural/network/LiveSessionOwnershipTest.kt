package chat.mural.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionOwnershipTest {
    private class Lease : LiveSessionLease {
        override val sessionID = "opaque"
        var closes = 0
        var throws = false
        override suspend fun requestClose() { closes++; if (throws) throw HostedFailure.Unconfirmed }
    }
    @Test fun gracefulCloseAndRepeatedDisposalRequestCutoffOnce() = runTest {
        val lease = Lease(); val owner = LiveSessionOwnership(this)
        owner.adopt(lease); owner.close(); owner.close(); runCurrent()
        assertEquals(1, lease.closes)
    }
    @Test fun lateCreateStillClosesAfterLocalDisposalAndCancelledUiScope() = runTest {
        val ui = Job(); ui.cancel()
        val independentCleanup = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val lease = Lease(); val owner = LiveSessionOwnership(independentCleanup)
        owner.close(); owner.adopt(lease); runCurrent()
        assertTrue(ui.isCancelled); assertEquals(1, lease.closes)
        independentCleanup.cancel()
    }
    @Test fun uncertainCloseIsNotRetriedAndByokNeedsNoServerCutoff() = runTest {
        val lease = Lease().apply { throws = true }; val owner = LiveSessionOwnership(this)
        owner.adopt(lease); owner.close(); runCurrent(); owner.close(); runCurrent()
        assertEquals(1, lease.closes)
        val byok = LiveSessionOwnership(this); byok.adopt(null); byok.close(); runCurrent()
    }
}
