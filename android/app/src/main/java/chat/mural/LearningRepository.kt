package chat.mural

import android.content.Context
import android.util.AtomicFile
import chat.mural.core.Archive
import chat.mural.core.ArchiveCodec
import chat.mural.core.nowSeconds
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** App-private archive; API credentials are deliberately kept elsewhere. */
class LearningRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "learning.json"))
    private val writer = writerFor(file)

    /** Called off the main thread when a queued write fails. */
    var onSaveFailed: ((Exception) -> Unit)?
        get() = writer.onFailure
        set(value) { writer.onFailure = value }

    /** Queues a snapshot for a serialized write that continues after the caller is gone; a newer snapshot replaces an unwritten one. */
    fun enqueue(snapshot: Archive) { writer.pending.trySend(snapshot) }
    fun load(): Archive {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Archive()
        val archive = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { "El respaldo local es demasiado grande." }
                output.write(buffer, 0, count)
            }
            ArchiveCodec.decode(output.toString(Charsets.UTF_8.name()))
        }
        val unfinished = archive.sessions.filter { it.endedAt == null }
        if (unfinished.isNotEmpty()) {
            val now = nowSeconds()
            unfinished.forEach { it.endedAt = now; it.endReason = "App closed before finalization" }
            save(archive)
        }
        return archive
    }
    fun save(archive: Archive) = write(file, archive)

    private class Writer(val pending: Channel<Archive>) { @Volatile var onFailure: ((Exception) -> Unit)? = null }

    private companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writers = mutableMapOf<String, Writer>()

        /** One consumer per archive file for the life of the process, so queued writes survive a cleared view model. */
        fun writerFor(file: AtomicFile): Writer = synchronized(writers) {
            writers.getOrPut(file.baseFile.path) {
                Writer(Channel(Channel.CONFLATED)).also { writer ->
                    scope.launch {
                        for (snapshot in writer.pending) {
                            try { write(file, snapshot) } catch (e: Exception) { writer.onFailure?.invoke(e) }
                        }
                    }
                }
            }
        }

        fun write(file: AtomicFile, archive: Archive) {
            val bytes = ArchiveCodec.encode(archive).toByteArray(Charsets.UTF_8)
            require(bytes.size <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { "The learning archive reached its storage limit; export a backup." }
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
}
