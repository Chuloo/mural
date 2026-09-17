package chat.mural.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CustomEndpointTest {
    private lateinit var server: MockWebServer
    private lateinit var api: APIClient
    private var protocol = EndpointProtocol.CHAT_COMPLETIONS
    private var key: String? = null
    private var skipThinking = false

    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = APIClient({
            EndpointTarget(server.url("/v1/"), key, protocol, "local-model", "local-whisper", "local-tts", "ef_dora",
                skipThinking = skipThinking, custom = true)
        }, OkHttpClient.Builder().followRedirects(false).build())
    }
    @After fun teardown() { server.shutdown() }

    @Test fun baseUrlMustBeCredentialFreeHttpsAndGainsTrailingSlash() {
        assertEquals("https://example.com/v1/", CustomEndpoint.parseBaseUrl(" https://example.com/v1 ").toString())
        assertEquals("https://example.com/", CustomEndpoint.parseBaseUrl("https://example.com").toString())
        for (bad in listOf("http://example.com/v1", "https://user:pass@example.com/v1", "https://example.com/v1?x=1", "https://example.com/#x", "example.com", ""))
            assertNull(bad, CustomEndpoint.parseBaseUrl(bad))
        assertFalse(CustomEndpoint(baseUrl = "https://example.com/v1").textReady)
        assertTrue(CustomEndpoint(baseUrl = "https://example.com/v1", model = "m").textReady)
        assertFalse(CustomEndpoint(baseUrl = "https://example.com/v1", model = "m", transcriptionModel = "t", speechModel = "s").voiceReady)

        assertTrue("an empty, disabled endpoint can be saved", CustomEndpoint().canSave(keyEntered = false))
        assertFalse("a key alone couldn't be removed later", CustomEndpoint().canSave(keyEntered = true))
        assertFalse(CustomEndpoint(baseUrl = "http://example.com/v1").canSave(keyEntered = false))
        assertFalse(CustomEndpoint(enabled = true, baseUrl = "https://example.com/v1").canSave(keyEntered = false))
        val trimmed = CustomEndpoint(enabled = true, baseUrl = " https://example.com/v1 ", model = " m ", voice = " v ").trimmed()
        assertEquals(listOf("https://example.com/v1", "m", "v"), listOf(trimmed.baseUrl, trimmed.model, trimmed.voice))
        assertTrue(trimmed.canSave(keyEntered = true))
    }

    @Test fun guidanceQueuesOneReplyAndSurvivesAFailedReply() {
        val guidance = TurnGuidance(limit = 3)
        assertTrue(guidance.add("greet", respond = true))
        assertFalse("a second request while one is pending", guidance.add("check in", respond = true))
        assertFalse(guidance.add("context", respond = false))
        val used = guidance.begin()
        assertFalse(guidance.replyPending)
        assertEquals(listOf("greet", "check in", "context"), used)
        assertTrue(TurnGuidance.prompt(used).endsWith("greet\ncheck in\ncontext"))
        assertEquals("a failed reply consumes nothing", used, guidance.begin())
        guidance.add("newer", respond = false)
        guidance.consumed(used)
        assertEquals(listOf("newer"), guidance.begin())
        assertEquals("", TurnGuidance.prompt(emptyList()))

        val repeated = TurnGuidance()
        repeated.add("check in", respond = true)
        val sent = repeated.begin()
        repeated.add("check in", respond = true)
        repeated.consumed(sent)
        assertEquals("an identical note added during the reply survives", listOf("check in"), repeated.begin())
    }

    @Test fun turnCollectorKeepsTheOnsetAndDropsTrailingSilence() {
        val turn = TurnCollector(20)
        fun frame(value: Int) = ShortArray(320) { value.toShort() }
        var finished: List<ShortArray>? = null
        repeat(20) { assertNull(turn.feed(frame(1), 0.002)) }
        repeat(20) { turn.feed(frame(2_000), 0.08)?.let { finished = it } }
        assertTrue(turn.active)
        repeat(60) { turn.feed(frame(1), 0.002)?.let { finished = it } }
        val voiced = checkNotNull(finished)
        assertFalse(turn.active)
        assertEquals("audio from just before speech is kept", 1.toShort(), voiced.first()[0])
        assertEquals("trailing silence is dropped", 2_000.toShort(), voiced.last()[0])
        assertEquals(TurnCollector.PREROLL_FRAMES + 18, voiced.size)
        assertEquals(SpeechDetector.END_SILENCE_MS, turn.trailingSilenceMS)

        val limited = TurnCollector(20)
        var cut: List<ShortArray>? = null
        repeat(1_500) { limited.feed(frame(2_000), 0.08)?.let { cut = it } }
        assertEquals("a turn cut at the length limit keeps all its speech", 1_500, checkNotNull(cut).size)
        assertEquals(0, limited.trailingSilenceMS)
    }

    @Test fun chatCompletionsSendsSystemAndUserMessagesWithSchemaAndNoKeyWhenNoneSaved() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"<think>plan</think> Hola"}}],"usage":{"prompt_tokens":9,"completion_tokens":2}}"""))
        val schema = buildJsonObject { put("type", "object") }
        val result = api.respond("policy", "hello", schema, search = true)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("local-model", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system" to "policy", "user" to "hello"), messages.map { it["role"]!!.jsonPrimitive.content to it["content"]!!.jsonPrimitive.content })
        assertEquals(schema, body["response_format"]!!.jsonObject["json_schema"]!!.jsonObject["schema"])
        assertNull("compatible servers have no standard web search", body["tools"])
        assertNull("strict servers reject unknown fields, so thinking stays on by default", body["chat_template_kwargs"])
        assertEquals("Hola", result.text); assertEquals(APIUsage(9, 2), result.usage)

        skipThinking = true
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Hola"}}]}"""))
        api.respond("policy", "hello")
        val direct = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(false), direct["chat_template_kwargs"]!!.jsonObject["enable_thinking"])
    }

    @Test fun chatRefusalsTruncationAndEmptyRepliesAreNotReplies() = runBlocking {
        key = "gsk_local-test-only"
        for (body in listOf("""{"choices":[{"message":{"content":null,"refusal":"no"}}]}""",
            """{"choices":[{"finish_reason":"length","message":{"content":"Hol"}}]}""",
            """{"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}""",
            """{"choices":[{"message":{"content":"<think>only thinking</think>"}}]}""", """{"choices":[]}""")) {
            server.enqueue(MockResponse().setBody(body))
            try { api.respond("p", "q"); fail("accepted $body") } catch (_: APIClient.APIException) { }
        }
        assertEquals("Bearer gsk_local-test-only", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun responsesProtocolUsesTheConfiguredModel() = runBlocking {
        protocol = EndpointProtocol.RESPONSES
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"content":[{"type":"output_text","text":"<think>plan</think> Hola"}]}]}"""))
        assertEquals("reasoning is stripped on Responses too", "Hola", api.respond("p", "q").text)
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        assertEquals("local-model", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test fun audioUsesTranscriptionAndSpeechEndpoints() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":" Quiero un café "}"""))
        val wav = Wav.encode(listOf(ShortArray(160) { 900.toShort() }), 16_000)
        assertEquals("Quiero un café", api.transcribe(wav, "nb"))
        val transcription = server.takeRequest().body.readUtf8()
        assertTrue(transcription.contains("name=\"model\"") && transcription.contains("local-whisper"))
        assertTrue(transcription.contains("filename=\"speech.wav\""))
        assertTrue("the learning language is sent, in Whisper's code", Regex("name=\"language\"(\\r\\n[^\\r\\n]+)*\\r\\n\\r\\nno\\r\\n").containsMatchIn(transcription))

        server.enqueue(MockResponse().setBody(Buffer().write(wav)))
        assertArrayEquals(wav, api.speak("Hola"))
        val speech = server.takeRequest()
        assertEquals("/v1/audio/speech", speech.path)
        val body = Json.parseToJsonElement(speech.body.readUtf8()).jsonObject
        assertEquals(listOf("local-tts", "ef_dora", "Hola", "wav"), listOf("model", "voice", "input", "response_format").map { body[it]!!.jsonPrimitive.content })
    }

    @Test fun wavRoundTripsAndToleratesStreamingLengthsButRejectsOtherFormats() {
        val samples = ShortArray(3_200) { (it % 200 - 100).toShort() }
        val wav = Wav.encode(listOf(samples.copyOfRange(0, 1_600), samples.copyOfRange(1_600, 3_200)), 16_000)
        val decoded = Wav.decode(wav)!!
        assertEquals(16_000, decoded.sampleRate); assertEquals(1, decoded.channels); assertEquals(200, decoded.durationMS)
        assertArrayEquals(wav.copyOfRange(44, wav.size), decoded.data)

        val streamed = wav.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(40, -1) }
        assertEquals(6_400, Wav.decode(streamed)!!.data.size)
        val float = wav.copyOf().also { it[20] = 3 }
        assertNull(Wav.decode(float))
        assertNull(Wav.decode("ID3 not a wav".toByteArray()))
    }

    @Test fun speechDetectorWaitsForOnsetAndALearnersPauseAndDropsClicks() {
        val detector = SpeechDetector(20)
        fun feed(level: Double, frames: Int) = (1..frames).map { detector.feed(level) }
        feed(0.002, 50)
        assertEquals(SpeechDetector.Event.START, feed(0.08, 3).last())
        feed(0.08, 30)
        assertEquals("a one-second pause is still the same turn", SpeechDetector.Event.NONE, feed(0.002, 50).last())
        assertTrue(detector.active)
        feed(0.08, 10)
        assertEquals(SpeechDetector.Event.END, feed(0.002, 60).last())
        assertFalse(detector.active)

        feed(0.08, 3)
        assertEquals("a click is not a turn", SpeechDetector.Event.DISCARD, feed(0.002, 60).last())

        feed(0.08, 13)
        assertEquals("a 260 ms \"sí\" counts its onset and isn't discarded", SpeechDetector.Event.END, feed(0.002, 60).last())
    }
}
