package chat.mural.ondevice

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** A model download refused or interrupted. The controller chooses the learner-facing reason. */
class ModelDownloadException(val kind: Kind, message: String, val shortfallBytes: Long = 0L,
    cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Kind { OUT_OF_STORAGE, REFUSED, INTERRUPTED }
}

/**
 * The in-app model downloader: one HTTPS file streamed into app storage that device backups skip.
 * A `.part` file from an interrupted attempt resumes with a `Range` request, and the finished model
 * appears through an atomic rename, so the Model Runtime never loads half a file. Cancelling the
 * coroutine cancels the HTTP call, which closes the socket at once and leaves the `.part` to resume.
 * The signed CDN URL a Hugging Face redirect lands on is never cached: this client installs no cache.
 */
class OnDeviceModelDownloader(
    private val directory: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val usableBytes: () -> Long = { directory.usableSpace },
) {
    /**
     * Downloads one model file and returns it. [expectedBytes] is the curated size when it is known;
     * a pasted link announces its size only in the response. [onProgress] is called from the
     * transfer thread, so its receiver must be thread safe.
     */
    suspend fun download(url: String, fileName: String, expectedBytes: Long = 0L,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }): File = coroutineScope {
        val target = File(directory, fileName)
        val partial = File(directory, fileName + PARTIAL_SUFFIX)
        withContext(Dispatchers.IO) { directory.mkdirs() }
        if (expectedBytes > 0) refuseIfShort((expectedBytes - partial.length()).coerceAtLeast(0L))
        var attempts = 0
        while (true) {
            val existing = partial.length()
            val request = Request.Builder().url(url)
                // identity, so the partial file holds the bytes of the file itself and its length is the range start
                .header("Accept-Encoding", "identity")
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            val call = client.newCall(request)
            coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            val complete = try { withContext(Dispatchers.IO) { transfer(call, partial, onProgress) } }
                catch (error: IOException) { currentCoroutineContext().ensureActive(); throw interrupted(error) }
            if (complete) break
            // 416: the host no longer serves that range, so the stale partial file is worthless.
            if (++attempts >= MAXIMUM_RANGE_ATTEMPTS)
                throw ModelDownloadException(ModelDownloadException.Kind.REFUSED, "Range refused for $fileName")
            withContext(Dispatchers.IO) { partial.delete() }
        }
        withContext(Dispatchers.IO) { rename(partial, target) }
    }

    /** Writes one response body into the partial file. False means the host refused the range. */
    private suspend fun transfer(call: Call, partial: File, onProgress: (Long, Long) -> Unit): Boolean {
        call.execute().use { response ->
            if (response.code == 416) return false
            if (!response.isSuccessful) throw ModelDownloadException(ModelDownloadException.Kind.REFUSED,
                "HTTP ${response.code} for ${call.request().url}")
            val body = response.body ?: throw ModelDownloadException(ModelDownloadException.Kind.REFUSED,
                "No body for ${call.request().url}")
            // A host can ignore the range and send the whole file with HTTP 200: then start over.
            val append = response.code == 206 && partial.length() > 0
            val announced = body.contentLength()
            val existing = if (append) partial.length() else 0L
            val total = if (announced < 0) 0L else existing + announced
            if (announced >= 0) refuseIfShort(announced)
            var received = existing
            onProgress(received, total)
            body.byteStream().use { source ->
                FileOutputStream(partial, append).use { sink ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        received += read
                        onProgress(received, total)
                    }
                }
            }
            // A short body never becomes a model file; it stays a resumable partial file.
            if (total > 0 && received != total) throw interrupted(IOException("Closed after $received of $total bytes"))
            return true
        }
    }

    private fun refuseIfShort(requiredBytes: Long) {
        val shortfall = requiredBytes + OnDeviceDownloadPlanner.STORAGE_HEADROOM_BYTES - usableBytes()
        if (shortfall > 0) throw ModelDownloadException(ModelDownloadException.Kind.OUT_OF_STORAGE,
            "Needs $shortfall more free bytes", shortfall)
    }

    private fun rename(partial: File, target: File): File {
        if (!partial.renameTo(target)) throw interrupted(IOException("Could not move ${partial.name} into place"))
        return target
    }

    private fun interrupted(cause: Throwable) = ModelDownloadException(ModelDownloadException.Kind.INTERRUPTED,
        "The transfer stopped: ${cause.message}", cause = cause)

    companion object {
        /** An unfinished download, kept to resume and never loadable as a model. */
        const val PARTIAL_SUFFIX = ".part"
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAXIMUM_RANGE_ATTEMPTS = 2
    }
}
