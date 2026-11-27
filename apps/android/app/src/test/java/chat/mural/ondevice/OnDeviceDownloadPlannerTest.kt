package chat.mural.ondevice

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

    private fun plan(requiredBytes: Long, usableBytes: Long, deviceMemoryBytes: Long, minDeviceMemoryGb: Int) =
        OnDeviceDownloadPlanner.plan(requiredBytes, usableBytes, deviceMemoryBytes, minDeviceMemoryGb)
}
