package chat.mural.ondevice

import chat.mural.core.DownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The On-Device Model downloads in flight at once: one transfer, one progress and one cancel per
 * model file. Starting a download never waits for another, cancelling one leaves every other
 * untouched, and deleting a model stops only that model's transfer. Two writers into one `.part`
 * file would corrupt it, so the same file never flies twice while it downloads, imports or loads.
 * Progress arrives from transfer threads and is read on main, so it travels through one conflated
 * flow and [changed] runs on the scope's thread: conflation is the progress throttle.
 */
class OnDeviceDownloadFlights(private val scope: CoroutineScope, private val changed: () -> Unit = {}) {
    /** Main-confined like OnDeviceModelController. An ended flight stays until its file starts again. */
    private val jobs = mutableMapOf<String, Job>()
    private val progress = MutableStateFlow<List<DownloadProgress>>(emptyList())
    private var watcher: Job? = null

    /** Every transfer in flight, oldest first; a model row reads the entry for its own file. */
    fun downloads(): List<DownloadProgress> = progress.value

    /** Whether that file is downloading, importing or loading, so it never starts twice at once. */
    fun inFlight(fileName: String) = jobs[fileName]?.isActive == true

    /**
     * Starts [transfer] beside every download already in flight. [beginsAt] is its row's first
     * progress: a resumed transfer starts at the length of the `.part` file it continues.
     */
    fun start(fileName: String, beginsAt: DownloadProgress, transfer: suspend () -> Unit) {
        if (inFlight(fileName)) return
        watch()
        progress.update { it + beginsAt }
        jobs[fileName] = scope.launch { try { transfer() } finally { finished(fileName) } }
    }

    /** One transfer's progress, written from its own transfer thread. */
    fun report(fileName: String, receivedBytes: Long, totalBytes: Long) {
        progress.update { rows ->
            rows.map { if (it.fileName == fileName) it.copy(receivedBytes = receivedBytes, totalBytes = totalBytes) else it }
        }
    }

    /**
     * That transfer has stopped writing, whether it landed, was refused or was cancelled: its row
     * shows neither progress nor cancel any more. The flight still holds the file until its import
     * and load end, so the same model cannot start twice.
     */
    fun finished(fileName: String) { progress.update { it.filterNot { row -> row.fileName == fileName } } }

    /** Stops one transfer at once; its `.part` file stays, so the next attempt resumes it. */
    fun cancel(fileName: String) { jobs[fileName]?.takeIf(Job::isActive)?.cancel() }

    fun close() {
        watcher?.cancel()
        jobs.values.forEach(Job::cancel)
        progress.value = emptyList()
    }

    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch { progress.collect { changed() } }
    }
}
