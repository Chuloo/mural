package chat.mural.ondevice

import chat.mural.core.AndroidLanguages
import chat.mural.network.APIUsage
import chat.mural.network.HelperPurpose
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The Helpers of an On-Device Model session, observed through the existing TeachingClient seam. */
class OnDeviceTeachingClientTest {
    private val spanish = AndroidLanguages.get("es")!!
    private val model = "models/gemma-4-e4b.litertlm"
    private fun helpers(runtime: ModelRuntime) =
        OnDeviceTeachingClient({ runtime.load(model) }) { spanish.lookupUnavailableReply }

    /** The zero-download AICore Model Runtime, over one fake backend. */
    private fun aicore(client: FakeAICoreClient) = AICoreModelRuntime(sdkInt = 35) { client }

    @Test fun onDeviceHelpersAnswerThroughTheModelRuntimeWithoutWebSourcesOrInventedUsage() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("¡Hola", " amigo!"), ScriptedReply("Segundo"))
        val client = helpers(runtime)
        val result = client.respond("Reply only in Spanish.", "TARGET LANGUAGE: es\nUSER: Hola",
            purpose = HelperPurpose.TYPED_REPLY)
        assertEquals("¡Hola amigo!", result.text)
        assertTrue(result.sources.isEmpty())
        assertEquals(APIUsage(), result.usage)
        val first = runtime.requests.first()
        assertTrue(first.systemPrompt!!.startsWith("Reply only in Spanish.\n"))
        assertEquals("TARGET LANGUAGE: es\nUSER: Hola", first.request.prompt)
        assertNull(first.request.jsonSchema)
        // Each Helper is one single turn: the next request carries its own instructions, never history.
        client.respond("Explain the selected word.", "Selected: hola", purpose = HelperPurpose.LOOKUP)
        assertEquals(2, runtime.requests.size)
        assertTrue(runtime.requests[1].systemPrompt!!.startsWith("Explain the selected word.\n"))
    }

    @Test fun wordLookupTypedRepliesAndHelpExplainOfflineAndRefuseLiveFactsHonestly() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("uno"), ScriptedReply("dos"), ScriptedReply("tres"))
        val client = helpers(runtime)
        for (purpose in listOf(HelperPurpose.LOOKUP, HelperPurpose.TYPED_REPLY, HelperPurpose.HELP)) {
            client.respond("Explain the selected word in English.", "Selected: hola", purpose = purpose)
        }
        assertEquals(3, runtime.requests.size)
        for (request in runtime.requests) {
            val system = request.systemPrompt!!
            assertTrue(system, system.startsWith("Explain the selected word in English.\n"))
            assertTrue(system, system.contains("no internet"))
            assertTrue(system, system.contains("never invent"))
            // The Language Module's existing unavailable reply is the honest answer for a live fact.
            assertTrue(system, system.contains(spanish.lookupUnavailableReply))
        }
    }

    @Test fun meaningSubtitlesStreamCumulativeTextAndABlankReplyRendersNothing() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply(" ", "Ho", "la"), ScriptedReply("   "))
        val client = helpers(runtime)
        val partials = mutableListOf<String>()
        val meaning = client.streamMeaning("Translate the supplied Spanish transcript faithfully into English.", "Hola") { partials += it }
        // The subtitle contract the cloud TeachingClients use: every partial is the whole text so far.
        assertEquals(listOf(" Ho", " Hola"), partials)
        assertEquals("Hola", meaning.text)
        assertTrue(meaning.sources.isEmpty())
        val blank = mutableListOf<String>()
        val whitespace = client.streamMeaning("Translate the supplied Spanish transcript faithfully into English.", "…") { blank += it }
        assertTrue(blank.isEmpty())
        assertTrue(whitespace.text.isBlank())
        // A translation is not a web request, so it carries no offline rule of its own.
        assertEquals("Translate the supplied Spanish transcript faithfully into English.",
            runtime.requests.first().systemPrompt)
    }

    @Test fun aWebSearchRequestStaysOfflineAndUnsourcedWithoutAModelCall() = runTest {
        val runtime = ScriptedModelRuntime()
        val client = helpers(runtime)
        val topic = client.respond("Find a current angle.", "noticias", search = true, purpose = HelperPurpose.TOPIC)
        // No sources exist offline, so the caller reports its unsourced topic instead of invented prose.
        assertEquals("", topic.text)
        assertTrue(topic.sources.isEmpty())
        assertEquals(0, topic.usage.searches)
        val delegation = client.respond("Support the conversation.", "el contexto", search = true,
            purpose = HelperPurpose.DELEGATION)
        assertEquals(spanish.lookupUnavailableReply, delegation.text)
        assertTrue(delegation.sources.isEmpty())
        assertTrue(runtime.requests.isEmpty())
    }

    @Test fun theSameTypedReplyHelperRunsOverTheZeroDownloadAICoreModelRuntime() = runTest {
        val client = FakeAICoreClient()
        client.reply("¡Hola", " amigo!")
        val result = helpers(aicore(client)).respond("Reply only in Spanish.", "TARGET LANGUAGE: es\nUSER: Hola",
            purpose = HelperPurpose.TYPED_REPLY)
        // A Conversation Provider cannot tell the two Model Runtimes apart: same text, no sources, no usage.
        assertEquals("¡Hola amigo!", result.text)
        assertTrue(result.sources.isEmpty())
        assertEquals(APIUsage(), result.usage)
        val request = client.requests.single()
        assertEquals("user: TARGET LANGUAGE: es\nUSER: Hola\nmodel: ", request.prompt)
        assertTrue(request.systemPrompt!!.startsWith("Reply only in Spanish.\n"))
        assertTrue(request.systemPrompt!!.contains(spanish.lookupUnavailableReply))
    }

    @Test fun meaningSubtitlesStreamOverAICoreExactlyAsOverADownloadedModel() = runTest {
        val client = FakeAICoreClient()
        client.reply(" ", "Ho", "la")
        val partials = mutableListOf<String>()
        val meaning = helpers(aicore(client))
            .streamMeaning("Translate the supplied Spanish transcript faithfully into English.", "Hola") { partials += it }
        assertEquals(listOf(" Ho", " Hola"), partials)
        assertEquals("Hola", meaning.text)
        assertTrue(meaning.sources.isEmpty())
        assertEquals("Translate the supplied Spanish transcript faithfully into English.",
            client.requests.single().systemPrompt)
    }

    @Test fun aFailedModelReplySurfacesAsAModelRuntimeFailure() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply(failure = "engine unavailable"))
        val client = helpers(runtime)
        try {
            client.respond("Reply only in Spanish.", "Hola", purpose = HelperPurpose.TYPED_REPLY)
            fail("A failed On-Device Model answered a Helper")
        } catch (_: ModelRuntimeException) { }
    }
}
