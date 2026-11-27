package chat.mural.ondevice

import chat.mural.network.APIUsage
import chat.mural.network.HelperPurpose
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class OnDeviceTeachingClientTest {
    private val schema = buildJsonObject { put("type", "object"); put("additionalProperties", false) }

    @Test fun onDeviceHelpersAnswerThroughTheModelRuntimeWithoutWebSourcesOrInventedUsage() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("¡Hola", " amigo!"), ScriptedReply("Segundo"))
        val session = runtime.load("models/gemma-4-e4b.litertlm")
        val client = OnDeviceTeachingClient { session }
        val result = client.respond("Reply only in Spanish.", "TARGET LANGUAGE: es\nUSER: Hola",
            purpose = HelperPurpose.TYPED_REPLY)
        assertEquals("¡Hola amigo!", result.text)
        assertTrue(result.sources.isEmpty())
        assertEquals(APIUsage(), result.usage)
        val first = runtime.requests.first()
        assertEquals("Reply only in Spanish.", first.systemPrompt)
        assertEquals("TARGET LANGUAGE: es\nUSER: Hola", first.request.prompt)
        assertNull(first.request.jsonSchema)
        // Each Helper is one single turn: the next request carries its own instructions, never history.
        client.respond("Explain the selected word.", "Selected: hola", purpose = HelperPurpose.LOOKUP)
        assertEquals(listOf("Reply only in Spanish.", "Explain the selected word."), runtime.requests.map { it.systemPrompt })
    }

    @Test fun assessmentSchemasReachConstrainedDecodingAndMeaningsStreamPartials() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("{\"outcome\":\"supported\"}"), ScriptedReply("Ho", "la"))
        val session = runtime.load("models/gemma-4-e4b.litertlm")
        val client = OnDeviceTeachingClient { session }
        client.respond("Assess only this passage.", "TARGET LANGUAGE: es", schema, purpose = HelperPurpose.ASSESSMENT)
        assertEquals(schema.toString(), runtime.requests.first().request.jsonSchema)
        val partials = mutableListOf<String>()
        val meaning = client.streamMeaning("Translate the transcript faithfully.", "Hola") { partials += it }
        assertEquals("Hola", meaning.text)
        assertEquals(listOf("Ho", "la"), partials)
        assertEquals("Translate the transcript faithfully.", runtime.requests[1].systemPrompt)
        assertNull(runtime.requests[1].request.jsonSchema)
        assertTrue(meaning.sources.isEmpty())
    }

    @Test fun aWebSearchRequestStaysOfflineAndUnsourced() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("No puedo comprobarlo ahora."))
        val client = OnDeviceTeachingClient { runtime.load("models/gemma-4-e4b.litertlm") }
        val result = client.respond("Find a current angle.", "noticias", search = true, purpose = HelperPurpose.TOPIC)
        // The caller degrades through its existing unsourced-topic reply; nothing reaches the web.
        assertTrue(result.sources.isEmpty())
        assertEquals(0, result.usage.searches)
    }

    @Test fun aFailedModelReplySurfacesAsAModelRuntimeFailure() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply(failure = "engine unavailable"))
        val client = OnDeviceTeachingClient { runtime.load("models/gemma-4-e4b.litertlm") }
        try {
            client.respond("Reply only in Spanish.", "Hola", purpose = HelperPurpose.TYPED_REPLY)
            fail("A failed On-Device Model answered a Helper")
        } catch (_: ModelRuntimeException) { }
    }
}
