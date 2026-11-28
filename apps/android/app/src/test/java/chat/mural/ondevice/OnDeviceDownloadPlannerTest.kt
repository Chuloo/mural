package chat.mural.ondevice

import chat.mural.core.DownloadProgress
import org.junit.Assert.*
import org.junit.Test

class OnDeviceDownloadPlannerTest {
    private val e4b = OnDeviceModelCatalogue.recommended()

    @Test fun aMultiGigabyteDownloadAsksTheLearnerFirstAndASmallOneDoesNot() {
        val plenty = plan(900_000_000L, 50_000_000_000L, 12_000_000_000L, e4b.minDeviceMemoryGb)
        assertFalse(plenty.largeDownload)
        assertFalse(plenty.refused)
        assertFalse(plenty.lowMemory)
        // The warning boundary is one gigabyte, inclusive.
        assertTrue(plan(1_000_000_000L, 50_000_000_000L, 12_000_000_000L, 0).largeDownload)
        assertTrue(plan(e4b.sizeBytes, 50_000_000_000L, 12_000_000_000L, e4b.minDeviceMemoryGb).largeDownload)
    }

    @Test fun littleFreeStorageRefusesTheDownloadAndNamesTheShortfall() {
        val headroom = OnDeviceDownloadPlanner.STORAGE_HEADROOM_BYTES
        val exact = plan(e4b.sizeBytes, e4b.sizeBytes + headroom, 12_000_000_000L, e4b.minDeviceMemoryGb)
        assertEquals(0L, exact.shortfallBytes)
        assertFalse(exact.refused)

        val short = plan(e4b.sizeBytes, 4_000_000_000L, 12_000_000_000L, e4b.minDeviceMemoryGb)
        assertEquals(e4b.sizeBytes + headroom - 4_000_000_000L, short.shortfallBytes)
        assertTrue(short.refused)
        // A refusal survives a phone that would still take the warning seriously.
        assertTrue(short.largeDownload)
    }

    @Test fun aResumedDownloadPlansOnlyTheBytesThatAreStillMissing() {
        val remaining = 400_000_000L
        val resumed = plan(remaining, 2_000_000_000L, 12_000_000_000L, e4b.minDeviceMemoryGb)
        assertFalse(resumed.largeDownload)
        assertFalse(resumed.refused)
        assertTrue(plan(remaining, 900_000_000L, 12_000_000_000L, 0).refused)
    }

    @Test fun aPhoneBelowTheModelMemoryFloorIsWarnedButNeverRefused() {
        val capable = plan(e4b.sizeBytes, 50_000_000_000L, 12_000_000_000L, e4b.minDeviceMemoryGb)
        assertFalse(capable.lowMemory)
        assertFalse(capable.refused)
        val small = plan(e4b.sizeBytes, 50_000_000_000L, 7_999_999_999L, e4b.minDeviceMemoryGb)
        assertTrue(small.lowMemory)
        assertFalse(small.refused)
        // A pasted link has no curated memory floor, so it never warns about memory.
        assertFalse(plan(e4b.sizeBytes, 50_000_000_000L, 1_000L, 0).lowMemory)
        val e2b = OnDeviceModelCatalogue.byID("gemma-4-e2b")!!
        assertFalse(plan(e2b.sizeBytes, 50_000_000_000L, 8_000_000_000L, e2b.minDeviceMemoryGb).lowMemory)
        assertTrue(plan(e2b.sizeBytes, 50_000_000_000L, 6_000_000_000L, e2b.minDeviceMemoryGb).lowMemory)
    }

    @Test fun aNewDownloadIsPlannedTogetherWithEveryDownloadAlreadyInFlight() {
        val e2b = OnDeviceModelCatalogue.byID("gemma-4-e2b")!!
        val qwen = OnDeviceModelCatalogue.byID("qwen2.5-1.5b")!!
        val headroom = OnDeviceDownloadPlanner.STORAGE_HEADROOM_BYTES
        // Gemma 4 E2B is 400 MB into its transfer; Qwen2.5 has written nothing yet.
        val inFlight = listOf(DownloadProgress(e2b.fileName!!, 400_000_000L, e2b.sizeBytes),
            DownloadProgress(qwen.fileName!!, 0L, qwen.sizeBytes))
        val stillToWrite = OnDeviceDownloadPlanner.bytesInFlight(inFlight)
        assertEquals(e2b.sizeBytes - 400_000_000L + qwen.sizeBytes, stillToWrite)

        // E4B fits beside both only when the phone has room for all three plus the one headroom.
        val roomForAll = stillToWrite + e4b.sizeBytes + headroom
        val together = plan(e4b.sizeBytes, roomForAll, 12_000_000_000L, e4b.minDeviceMemoryGb, stillToWrite)
        assertEquals(0L, together.shortfallBytes)
        assertFalse(together.refused)
        val short = plan(e4b.sizeBytes, roomForAll - 1, 12_000_000_000L, e4b.minDeviceMemoryGb, stillToWrite)
        assertEquals(1L, short.shortfallBytes)
        assertTrue(short.refused)
        // The same phone with nothing else in flight takes E4B, so the combination is what refuses.
        assertFalse(plan(e4b.sizeBytes, roomForAll - 1, 12_000_000_000L, e4b.minDeviceMemoryGb).refused)

        // A pasted link announces no size, so it plans as nothing until its response says how big it
        // is; from then on its own remaining bytes count towards the next download's plan.
        assertEquals(0L, OnDeviceDownloadPlanner.bytesInFlight(listOf(DownloadProgress("pasted.litertlm", 1_000_000L, 0L))))
        assertEquals(1_500_000L,
            OnDeviceDownloadPlanner.bytesInFlight(listOf(DownloadProgress("pasted.litertlm", 500_000L, 2_000_000L))))
    }

    private fun plan(requiredBytes: Long, usableBytes: Long, deviceMemoryBytes: Long, minDeviceMemoryGb: Int,
        inFlightBytes: Long = 0L) =
        OnDeviceDownloadPlanner.plan(requiredBytes, usableBytes, deviceMemoryBytes, minDeviceMemoryGb, inFlightBytes)
}
