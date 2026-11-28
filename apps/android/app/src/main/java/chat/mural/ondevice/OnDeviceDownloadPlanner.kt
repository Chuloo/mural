package chat.mural.ondevice

import chat.mural.core.DownloadProgress

/** What the learner is told before a model download starts: pure policy, no device access. */
data class DownloadPlan(val largeDownload: Boolean, val shortfallBytes: Long, val lowMemory: Boolean) {
    /** Only free storage refuses a download; a big file or a small phone stays the learner's choice. */
    val refused get() = shortfallBytes > 0
}

/**
 * Download planning for the On-Device Model catalogue: a multi-gigabyte transfer and a nearly full
 * phone are the learner's explicit decision, so both are named before one byte is fetched.
 */
object OnDeviceDownloadPlanner {
    /** A download of one gigabyte or more is confirmed first. */
    const val LARGE_DOWNLOAD_BYTES = 1_000_000_000L
    /** Free space kept back, so a model download never fills the phone. */
    const val STORAGE_HEADROOM_BYTES = 1_073_741_824L

    /** [requiredBytes] is what is still missing, so a resumed download plans only its remainder.
     * [inFlightBytes] is what every download already in flight still has to write into the same
     * free space, so several transfers together are refused before any of them fills the phone. */
    fun plan(requiredBytes: Long, usableBytes: Long, deviceMemoryBytes: Long, minDeviceMemoryGb: Int,
        inFlightBytes: Long = 0L) = DownloadPlan(
        largeDownload = requiredBytes >= LARGE_DOWNLOAD_BYTES,
        shortfallBytes = (requiredBytes + inFlightBytes + STORAGE_HEADROOM_BYTES - usableBytes).coerceAtLeast(0L),
        // A floor of 0 means nobody curated this file, so nothing is claimed about its memory need.
        lowMemory = minDeviceMemoryGb > 0 && deviceMemoryBytes < minDeviceMemoryGb * 1_000_000_000L)

    /**
     * The bytes the in-flight downloads still have to write. A pasted link announces no size, so
     * until its response says how big the file is it plans as nothing.
     */
    fun bytesInFlight(downloads: Collection<DownloadProgress>): Long =
        downloads.sumOf { (it.totalBytes - it.receivedBytes).coerceAtLeast(0L) }
}
