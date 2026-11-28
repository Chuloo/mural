package chat.mural.ondevice

import chat.mural.core.DownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several On-Device Model downloads in flight at once, observed at the seam the model picker reads:
 * one progress and one cancel per model file, so starting, cancelling or deleting one never touches
 * another. No transfer here is real; each one waits on a gate the test releases.
 */
class OnDeviceDownloadFlightsTest {
    private val e2b = OnDeviceModelCatalogue.GEMMA_4_E2B.fileName!!
    private val qwen = OnDeviceModelCatalogue.QWEN2_5_1_5B.fileName!!
    /** Unconfined: a transfer resumes on the thread that releases its gate, so every assertion sees
     * a settled state without a test scheduler. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var published = 0
    private val flights = OnDeviceDownloadFlights(scope) { published++ }

    @After fun teardown() { scope.cancel() }

    /** A transfer that only ends when the test releases it, recording its own cancellation. */
    private fun transfer(gate: CompletableDeferred<Unit>, cancelled: MutableList<String>, name: String): suspend () -> Unit = {
        try { gate.await() }
        catch (cancel: CancellationException) { cancelled += name; throw cancel }
    }

    @Test fun twoModelsDownloadAtOnceAndEachRowCarriesItsOwnProgress() {
        val landed = CompletableDeferred<Unit>()
        flights.start(e2b, DownloadProgress(e2b, 0L, 2_588_147_712L)) { landed.await() }
        flights.start(qwen, DownloadProgress(qwen, 0L, 1_597_922_856L)) { landed.await() }
        assertTrue("the second download never waits for the first", flights.inFlight(e2b) && flights.inFlight(qwen))

        val before = published
        flights.report(e2b, 400_000_000L, 2_588_147_712L)
        flights.report(qwen, 50_000_000L, 1_597_922_856L)
        assertTrue("every progress move reaches the model picker", published > before)
        assertEquals(listOf(DownloadProgress(e2b, 400_000_000L, 2_588_147_712L),
            DownloadProgress(qwen, 50_000_000L, 1_597_922_856L)), flights.downloads())

        landed.complete(Unit)
        assertEquals(emptyList<DownloadProgress>(), flights.downloads())
        assertFalse(flights.inFlight(e2b) || flights.inFlight(qwen))
    }

    @Test fun theSameModelNeverDownloadsTwiceAtOnce() {
        val first = CompletableDeferred<Unit>()
        var started = 0
        flights.start(e2b, DownloadProgress(e2b, 0L, 1_000L)) { started++; first.await() }
        // Two writers into one `.part` file would corrupt it, so the second attempt never starts.
        flights.start(e2b, DownloadProgress(e2b, 0L, 1_000L)) { started++ }
        assertEquals(1, started)
        assertEquals(1, flights.downloads().size)

        first.complete(Unit)
        assertFalse(flights.inFlight(e2b))
        // Once the attempt is over the same model downloads again, which is how its `.part` resumes.
        val resumed = CompletableDeferred<Unit>()
        flights.start(e2b, DownloadProgress(e2b, 400L, 1_000L)) { started++; resumed.await() }
        assertEquals(2, started)
        assertEquals(listOf(DownloadProgress(e2b, 400L, 1_000L)), flights.downloads())
        resumed.complete(Unit)
    }

    @Test fun cancellingOrDeletingOneModelLeavesEveryOtherDownloadUntouched() {
        val cancelled = mutableListOf<String>()
        val gates = mapOf(e2b to CompletableDeferred<Unit>(), qwen to CompletableDeferred<Unit>())
        flights.start(e2b, DownloadProgress(e2b, 0L, 1_000L), transfer(gates.getValue(e2b), cancelled, e2b))
        flights.start(qwen, DownloadProgress(qwen, 0L, 1_000L), transfer(gates.getValue(qwen), cancelled, qwen))
        flights.report(qwen, 700L, 1_000L)

        // Deleting a stored model nobody is downloading stops no transfer at all.
        flights.cancel("an-older-model.litertlm")
        assertEquals(emptyList<String>(), cancelled)
        assertTrue(flights.inFlight(e2b) && flights.inFlight(qwen))

        // Cancelling one download — or deleting the model it writes — stops only that one.
        flights.cancel(e2b)
        assertEquals(listOf(e2b), cancelled)
        assertFalse(flights.inFlight(e2b))
        assertTrue(flights.inFlight(qwen))
        assertEquals(listOf(DownloadProgress(qwen, 700L, 1_000L)), flights.downloads())

        gates.getValue(qwen).complete(Unit)
        assertEquals(listOf(e2b), cancelled)
        assertEquals(emptyList<DownloadProgress>(), flights.downloads())
    }

    @Test fun aLandedTransferStopsShowingProgressWhileItsModelIsStillImported() {
        val transferred = CompletableDeferred<Unit>()
        val imported = CompletableDeferred<Unit>()
        var started = 0
        flights.start(e2b, DownloadProgress(e2b, 0L, 1_000L)) {
            started++
            transferred.await()
            // The bytes are all here: the row stops offering a cancel while the model is imported.
            flights.finished(e2b)
            imported.await()
        }

        transferred.complete(Unit)
        assertEquals(emptyList<DownloadProgress>(), flights.downloads())
        // The import and load still belong to the flight, so the same model cannot start again yet.
        assertTrue(flights.inFlight(e2b))
        flights.start(e2b, DownloadProgress(e2b, 0L, 1_000L)) { started++ }
        assertEquals(1, started)

        imported.complete(Unit)
        assertFalse(flights.inFlight(e2b))
    }
}
