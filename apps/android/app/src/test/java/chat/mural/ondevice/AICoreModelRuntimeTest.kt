package chat.mural.ondevice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * The AICore Model Runtime, observed through the same [ModelRuntime] seam the LiteRT-LM adapter
 * offers: a fake [AICoreClient] stands in for the system service, so the zero-download On-Device
 * Model is tested with no Binder call and no system model on the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AICoreModelRuntimeTest {
    private val systemModel = OnDeviceModelCatalogue.GEMINI_NANO_AICORE.id
    /** How many clients the runtime asked the system service for; a probe or a load opens one. */
    private var opened = 0

    private fun runtime(client: FakeAICoreClient, sdkInt: Int = 35) =
        AICoreModelRuntime(sdkInt = sdkInt, open = { opened++; client })

    @Test fun theZeroDownloadOptionIsOfferedOnlyOnAndroid13AndUpWhenTheDeviceProbeReportsAICore() = runTest {
        val client = FakeAICoreClient(status = AICoreStatus.AVAILABLE)
        // Below AICore's API 33 floor the phone never sees the option, and the service is never asked.
        assertFalse(runtime(client, sdkInt = 32).available())
        assertEquals(0, opened)
        assertTrue(runtime(client, sdkInt = 33).available())
        assertTrue(runtime(client, sdkInt = 35).available())
        assertEquals(2, opened)
        // A model the service can still fetch counts as offered: selecting it starts that fetch.
        client.status = AICoreStatus.DOWNLOADABLE
        assertTrue(runtime(client).available())
        // A fetch already in flight, or no system model at all, is not offered.
        client.status = AICoreStatus.DOWNLOADING
        assertFalse(runtime(client).available())
        client.status = AICoreStatus.UNAVAILABLE
        assertFalse(runtime(client).available())
        // A phone that cannot reach the service refuses instead of offering a model that cannot start.
        client.statusFailure = "AICore service not reachable"
        assertFalse(runtime(client).available())
        // Every probe releases the client it opened.
        assertTrue(client.closed)
    }

    @Test fun loadingTheSystemModelRefusesOnAPhoneThatDoesNotOfferIt() = runTest {
        val client = FakeAICoreClient(status = AICoreStatus.UNAVAILABLE)
        try {
            runtime(client).load(systemModel)
            fail("A phone that reports no AICore loaded the system model")
        } catch (_: ModelRuntimeException) { }
        try {
            runtime(client, sdkInt = 32).load(systemModel)
            fail("Android 12 loaded the system model")
        } catch (_: ModelRuntimeException) { }
        client.statusFailure = "AICore service not reachable"
        try {
            runtime(client).load(systemModel)
            fail("An unreachable service loaded the system model")
        } catch (_: ModelRuntimeException) { }
        // A refused load releases the client it opened and never asks the service for a reply.
        assertTrue(client.closed)
        assertTrue(client.requests.isEmpty())
    }

    @Test fun aDownloadableSystemModelIsFetchedByTheSystemServiceBeforeTheSessionOpens() = runTest {
        val client = FakeAICoreClient(status = AICoreStatus.DOWNLOADABLE)
        client.reply("¡Hola!")
        val session = runtime(client).load(systemModel)
        assertEquals(1, client.downloads)
        assertEquals("¡Hola!", session.respond(GenerationRequest("Hola")))
        // A fetch that ends without an available model is a refusal, not a session that cannot reply.
        val stuck = FakeAICoreClient(status = AICoreStatus.DOWNLOADABLE, statusAfterDownload = AICoreStatus.UNAVAILABLE)
        try {
            runtime(stuck).load(systemModel)
            fail("A system fetch that never completed opened a session")
        } catch (_: ModelRuntimeException) { }
        assertTrue(stuck.closed)
    }

    @Test fun theSessionReplaysTheTurnHistoryBecauseTheSystemModelKeepsNoStateBetweenTurns() = runTest {
        val client = FakeAICoreClient()
        client.reply("¡Hola!")
        client.reply("Muy bien, ¿y tú?")
        client.reply("house")
        val session = runtime(client).load(systemModel)
        session.restart("You are Mural, a warm Spanish teacher.")
        assertEquals("¡Hola!", session.respond(GenerationRequest("Hola")))
        assertEquals("Muy bien, ¿y tú?", session.respond(GenerationRequest("¿Cómo estás?")))
        assertEquals(listOf("user: Hola\nmodel: ", "user: Hola\nmodel: ¡Hola!\nuser: ¿Cómo estás?\nmodel: "),
            client.requests.map { it.prompt })
        // The system prompt travels as the request's own instruction on every stateless turn.
        assertEquals(listOf("You are Mural, a warm Spanish teacher.", "You are Mural, a warm Spanish teacher."),
            client.requests.map { it.systemPrompt })
        // A new system prompt restarts the conversation: prior turns are dropped, never replayed.
        session.restart("Explain the selected word in English.")
        assertEquals("house", session.respond(GenerationRequest("la casa")))
        assertEquals("user: la casa\nmodel: ", client.requests.last().prompt)
        assertEquals("Explain the selected word in English.", client.requests.last().systemPrompt)
    }

    @Test fun aStreamedReplyArrivesInPartialsAndTheWholeTextIsTheReturn() = runTest {
        val client = FakeAICoreClient()
        client.reply("Olá", " ", "tudo bem?")
        val session = runtime(client).load(systemModel)
        val partials = mutableListOf<String>()
        assertEquals("Olá tudo bem?", session.respond(GenerationRequest("oi")) { partials += it })
        assertEquals(listOf("Olá", " ", "tudo bem?"), partials)
        // AICore rejects a request beyond its own decode budget, so the cap is enforced here.
        session.respond(GenerationRequest("oi", null, 9_000))
        assertEquals(4_096, client.requests.last().maxOutputTokens)
    }

    @Test fun cancellingTheCallerStopsTheAICoreReplyAndTheTurnNeverJoinsTheHistory() = runTest {
        val client = FakeAICoreClient()
        client.reply("a", "b", "c")
        client.hangMillis = 50
        val session = runtime(client).load(systemModel)
        val partials = mutableListOf<String>()
        val job = launch { session.respond(GenerationRequest("slow")) { partials += it } }
        // One delta arrives in virtual time; the caller cancels before the rest of the stream.
        testScheduler.advanceTimeBy(60)
        job.cancelAndJoin()
        assertEquals(listOf("a"), partials)
        client.hangMillis = 0
        client.reply("recovered")
        assertEquals("recovered", session.respond(GenerationRequest("again")))
        // The cancelled turn is not replayed as though the system model had answered it.
        assertEquals("user: again\nmodel: ", client.requests.last().prompt)
    }

    @Test fun aFailureMidReplySurfacesAsAModelRuntimeFailureAndLeavesTheTurnOut() = runTest {
        val client = FakeAICoreClient()
        client.reply("The system model ")
        client.failure = "AICore became unavailable"
        val session = runtime(client).load(systemModel)
        val partials = mutableListOf<String>()
        try {
            session.respond(GenerationRequest("hi")) { partials += it }
            fail("An AICore that stopped mid-reply answered")
        } catch (error: ModelRuntimeException) {
            assertTrue(error.message!!, error.message!!.contains("AICore became unavailable"))
        }
        // What already streamed stays streamed: the learner saw it before the failure arrived.
        assertEquals(listOf("The system model "), partials)
        client.failure = null
        client.reply("back")
        session.respond(GenerationRequest("again"))
        // The unfinished turn is not replayed as though the system model had answered it.
        assertEquals("user: again\nmodel: ", client.requests.last().prompt)
    }

    @Test fun aJSONSchemaRequestIsRefusedSoTheAssessmentRetryRunsUnconstrained() = runTest {
        val client = FakeAICoreClient()
        client.reply("""{"outcome":"success"}""")
        val session = runtime(client).load(systemModel)
        try {
            session.respond(GenerationRequest("assess", """{"type":"object"}""", 1_024))
            fail("AICore constrained a reply to a JSON Schema")
        } catch (_: ModelRuntimeException) { }
        // Refused before the system service is asked at all.
        assertTrue(client.requests.isEmpty())
        assertEquals("""{"outcome":"success"}""", session.respond(GenerationRequest("assess", null, 1_024)))
        assertEquals(1_024, client.requests.single().maxOutputTokens)
    }

    @Test fun closingTheSessionReleasesTheSystemModelClient() = runTest {
        val client = FakeAICoreClient()
        val session = runtime(client).load(systemModel)
        assertFalse(client.closed)
        session.close()
        assertTrue(client.closed)
    }
}
