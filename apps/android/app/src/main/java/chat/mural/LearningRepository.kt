package chat.mural

import android.content.Context
import android.util.AtomicFile
import chat.mural.core.Archive
import chat.mural.core.ArchiveCodec
import chat.mural.core.nowSeconds
import chat.mural.core.FinalAssessmentRecovery
import chat.mural.core.FinalAssessmentTicket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.ByteArrayOutputStream

/** App-private archive; API credentials are deliberately kept elsewhere. */
data class LearningSnapshot(val archive: Archive, val finalAssessments: List<FinalAssessmentTicket> = emptyList())

class LearningRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(context.filesDir, "learning.json"))
    fun load(): LearningSnapshot {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return LearningSnapshot(Archive())
        val snapshot = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { "El respaldo local es demasiado grande." }
                output.write(buffer, 0, count)
            }
            val encoded = output.toString(Charsets.UTF_8.name())
            val archive = ArchiveCodec.decode(encoded)
            val metadata = json.parseToJsonElement(encoded).jsonObject["androidFinalAssessments"]
            val tickets = metadata?.let { json.decodeFromJsonElement<List<FinalAssessmentTicket>>(it) } ?: emptyList()
            require(tickets.size <= 10_000 && tickets.map { it.sessionID }.distinct().size == tickets.size)
            require(tickets.all { it.attempts in 0..FinalAssessmentRecovery.MAX_ATTEMPTS &&
                (it.lastAttemptAt == null || it.lastAttemptAt.isFinite()) })
            LearningSnapshot(archive, tickets)
        }
        val archive = snapshot.archive
        var tickets = snapshot.finalAssessments
        val unfinished = archive.sessions.filter { it.endedAt == null }
        val recoveredAt = nowSeconds()
        unfinished.forEach {
            it.endedAt = recoveredAt; it.endReason = "App closed before finalization"
            tickets = FinalAssessmentRecovery.enqueue(it, tickets)
        }
        val recovered = LearningSnapshot(archive, tickets)
        if (unfinished.isNotEmpty()) save(recovered)
        return recovered
    }
    fun save(snapshot: LearningSnapshot) {
        // The archive and retry ledger share one AtomicFile commit. Public exports omit this
        // Android-only metadata and remain compatible with the iOS archive format.
        val archive = json.parseToJsonElement(ArchiveCodec.encode(snapshot.archive)).jsonObject
        val stored = JsonObject(archive + ("androidFinalAssessments" to json.encodeToJsonElement(snapshot.finalAssessments)))
        val bytes = stored.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { "The learning archive reached its storage limit; export a backup." }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
