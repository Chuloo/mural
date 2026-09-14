package chat.mural

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.Archive
import chat.mural.core.ArchiveCodec
import chat.mural.core.FinalAssessmentRecovery
import chat.mural.core.Fragment
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.core.nowSeconds
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningRepositoryTest {
    private fun withRepository(test: (LearningRepository, Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName.endsWith(".uitest"))
        val directory = File(target.cacheDir, "archive-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(target) { override fun getFilesDir(): File = directory }
        try { test(LearningRepository(isolated), isolated) }
        finally { directory.deleteRecursively() }
    }

    @Test fun interruptedSessionsRecoverOnceWithTheRetryTicketInTheSameAtomicFile() = withRepository { repository, context ->
        val record = SessionRecord(languageID = "es").apply {
            append(Fragment(speaker = Speaker.user, text = "la radio", startMS = 0, endMS = 1_000))
        }
        repository.save(LearningSnapshot(Archive(sessions = mutableListOf(record))))
        val recovered = LearningRepository(context).load()
        assertNotNull(recovered.archive.sessions.single().endedAt)
        assertEquals(1, recovered.finalAssessments.size)
        assertEquals(0, recovered.finalAssessments.single().attempts)
        val restarted = LearningRepository(context).load()
        assertEquals(recovered, restarted)
        val publicExport = ArchiveCodec.encode(restarted.archive)
        assertFalse(publicExport.contains("androidFinalAssessments"))
        assertEquals(restarted.archive, ArchiveCodec.decode(publicExport))
    }

    @Test fun retryCountsSurviveRestartAndOldArchivesRemainReadable() = withRepository { repository, context ->
        val record = SessionRecord(languageID = "fr", endedAt = nowSeconds()).apply {
            append(Fragment(speaker = Speaker.user, text = "la radio", startMS = 0, endMS = 1_000))
        }
        val archive = Archive(sessions = mutableListOf(record))
        File(context.filesDir, "learning.json").writeText(ArchiveCodec.encode(archive))
        assertTrue(repository.load().finalAssessments.isEmpty())
        val tickets = FinalAssessmentRecovery.enqueue(record, emptyList()).map {
            it.copy(attempts = 3, lastAttemptAt = nowSeconds())
        }
        repository.save(LearningSnapshot(archive, tickets))
        val restarted = LearningRepository(context).load()
        assertEquals(3, restarted.finalAssessments.single().attempts)
        assertTrue(FinalAssessmentRecovery.recover(restarted.archive.sessions, restarted.finalAssessments, nowSeconds() + 300).isEmpty())
        repository.save(LearningSnapshot(Archive()))
        assertTrue(LearningRepository(context).load().finalAssessments.isEmpty())
        assertTrue(LearningRepository(context).load().archive.sessions.isEmpty())
    }
}
