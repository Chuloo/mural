package chat.mural.ondevice

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The downloader seam against a mock model host: what lands in app storage, what the learner is
 * told, and what survives an interruption. Real Hugging Face downloads are never made here.
 */
class OnDeviceModelDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val client = OkHttpClient()
    private val body = "[PHONE]".repeat(100)
    private val name = "gemma-4-E2B-it.litertlm"
    private val partial get() = File(directory, "$name.part")

    @Before fun setup() {
        server = MockWebServer(); server.start()
        directory = Files.createTempDirectory("mural-models").toFile()
    }

    @After fun teardown() { server.shutdown(); directory.deleteRecursively() }

    private fun downloader(usableBytes: () -> Long = { Long.MAX_VALUE }) =
        OnDeviceModelDownloader(directory, client, usableBytes)

    @Test fun aCompletedTransferIsRenamedIntoPlaceAndReportsProgress() = runBlocking {
        server.enqueue(MockResponse().setBody(body).throttleBody(100, 50, TimeUnit.MILLISECONDS))
        val seen = mutableListOf<Pair<Long, Long>>()
        val file = downloader().download(server.url("/$name").toString(), name, body.length.toLong()) { received, total ->
            seen += received to total
        }
        assertEquals(File(directory, name), file)
        assertEquals(body, file.readText())
        assertFalse(partial.exists())
        assertTrue("progress arrives during the transfer", seen.size > 2)
        assertEquals(body.length.toLong(), seen.last().first)
        assertEquals(body.length.toLong(), seen.last().second)
        assertTrue(seen.first().first < seen.last().first)
        val request = server.takeRequest()
        // identity keeps the partial file the bytes of the file itself, so its length is a range start
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertNull(request.getHeader("Range"))
    }

    @Test fun aSignedRedirectIsFollowedToTheModelFile() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn/$name"))
        server.enqueue(MockResponse().setBody(body))
        assertEquals(body, downloader().download(server.url("/$name").toString(), name, body.length.toLong()).readText())
        assertEquals(2, server.requestCount)
        assertEquals("/$name", server.takeRequest().path)
        assertEquals("/cdn/$name", server.takeRequest().path)
    }

    @Test fun anInterruptedDownloadResumesWithARangeRequestAndAppendsTheRest() = runBlocking {
        partial.writeText(body.take(400))
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 400-${body.length - 1}/${body.length}")
            .setBody(body.drop(400)))
        val file = downloader().download(server.url("/$name").toString(), name, body.length.toLong())
        assertEquals(body, file.readText())
        assertFalse(partial.exists())
        assertEquals("bytes=400-", server.takeRequest().getHeader("Range"))
        assertEquals(1, server.requestCount)
    }

    @Test fun aHostThatIgnoresTheRangeRestartsTheFileInsteadOfAppending() = runBlocking {
        partial.writeText(body.take(400))
        server.enqueue(MockResponse().setBody(body))
        assertEquals(body, downloader().download(server.url("/$name").toString(), name, body.length.toLong()).readText())
        assertEquals("bytes=400-", server.takeRequest().getHeader("Range"))
    }

    @Test fun aPartialFileTheHostNoLongerServesIsDiscardedAndTheDownloadRestarts() = runBlocking {
        val tooLong = body + "a-longer-file-than-the-host-has"
        partial.writeText(tooLong)
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${body.length}"))
        server.enqueue(MockResponse().setBody(body))
        assertEquals(body, downloader().download(server.url("/$name").toString(), name, body.length.toLong()).readText())
        assertEquals(2, server.requestCount)
        assertEquals("bytes=${tooLong.length}-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun cancellingStopsTheTransferAtOnceAndKeepsThePartialFileForAResume() = runBlocking {
        server.enqueue(MockResponse().setBody(body).throttleBody(50, 200, TimeUnit.MILLISECONDS))
        val job = launch(Dispatchers.IO) { downloader().download(server.url("/$name").toString(), name, body.length.toLong()) }
        withTimeout(10_000) { while (partial.length() == 0L) delay(20) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(File(directory, name).exists())
        val stopped = partial.length()
        assertTrue("$stopped bytes landed before the cancel", stopped in 1 until body.length.toLong())
        delay(500)
        assertEquals("no byte arrives after a cancel", stopped, partial.length())
        assertEquals(body.take(stopped.toInt()), partial.readText())
    }

    @Test fun aTransferThatBreaksOffKeepsAResumablePartialFileAndNeverRenamesIt() = runBlocking {
        server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            downloader().download(server.url("/$name").toString(), name, body.length.toLong())
            fail("Accepted a truncated model file")
        } catch (error: ModelDownloadException) { assertEquals(ModelDownloadException.Kind.INTERRUPTED, error.kind) }
        assertFalse(File(directory, name).exists())
        val landed = partial.length()
        assertTrue("$landed bytes landed before the break", landed in 1 until body.length.toLong())
        assertEquals(body.take(landed.toInt()), partial.readText())
    }

    @Test fun aHostThatRefusesTheLinkIsReportedWithoutLeavingAModelBehind() = runBlocking {
        for (status in listOf(401, 403, 404)) {
            server.enqueue(MockResponse().setResponseCode(status))
            try {
                downloader().download(server.url("/$name").toString(), name, body.length.toLong())
                fail("Accepted HTTP $status")
            } catch (error: ModelDownloadException) { assertEquals(ModelDownloadException.Kind.REFUSED, error.kind) }
        }
        assertFalse(File(directory, name).exists())
        assertFalse(partial.exists())
    }

    @Test fun aDownloadThatWouldFillThePhoneIsRefusedBeforeItWritesAnything() = runBlocking {
        val headroom = OnDeviceDownloadPlanner.STORAGE_HEADROOM_BYTES
        // A curated size is known before the request, so the phone is never asked for the file.
        server.enqueue(MockResponse().setBody(body))
        try {
            downloader { body.length + headroom - 1 }.download(server.url("/$name").toString(), name, body.length.toLong())
            fail("Accepted a download with no free storage")
        } catch (error: ModelDownloadException) { assertEquals(ModelDownloadException.Kind.OUT_OF_STORAGE, error.kind) }
        assertEquals(0, server.requestCount)
        // A pasted link announces its size only in the response, so the check follows the headers.
        try {
            downloader { body.length + headroom - 1 }.download(server.url("/$name").toString(), name, 0L)
            fail("Accepted a download with no free storage")
        } catch (error: ModelDownloadException) { assertEquals(ModelDownloadException.Kind.OUT_OF_STORAGE, error.kind) }
        assertEquals(1, server.requestCount)
        assertFalse(File(directory, name).exists())
        assertFalse(partial.exists())
    }

    @Test fun aResumedDownloadNeedsOnlyTheStorageTheMissingBytesNeed() = runBlocking {
        partial.writeText(body.take(900))
        val headroom = OnDeviceDownloadPlanner.STORAGE_HEADROOM_BYTES
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 900-${body.length - 1}/${body.length}")
            .setBody(body.drop(900)))
        val file = downloader { 100 + headroom }.download(server.url("/$name").toString(), name, body.length.toLong())
        assertEquals(body, file.readText())
    }
}
