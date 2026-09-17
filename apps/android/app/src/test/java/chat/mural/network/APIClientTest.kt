package chat.mural.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class APIClientTest {
    @Test fun providerErrorCategoriesAreBoundedAndNeverShowRawMessagesOrRetry() = runBlocking {
        for (body in listOf("""{"error":{"code":"insufficient_quota","message":"private billing data"}}""", "x".repeat(16_385), "not json")) {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("x-request-id", "req_support").setBody(body))
            try { api.post("responses", buildJsonObject {}); fail("accepted error") }
            catch (error: APIClient.APIException.Http) {
                assertEquals("req_support", error.reference)
                assertFalse(error.message.orEmpty().contains("private"))
                assertEquals(if (body.startsWith('{')) "insufficient_quota" else null, error.code)
            }
        }
        assertEquals(3, server.requestCount)
    }
    private lateinit var server: MockWebServer
    private lateinit var api: APIClient
    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = APIClient("sk-fake-test-only", OkHttpClient.Builder().followRedirects(false).build(), server.url("/v1/"))
    }
    @After fun teardown() { server.shutdown() }
    private fun response(text: String = "Hola") = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"$text"}]}],"usage":{"input_tokens":12,"output_tokens":7}}"""
    @Test fun streamsUnicodeBeforeCompletionAndKeepsFinalUsage() = runBlocking {
        val body = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\r\n\r\n" +
            "data: {\"type\":\"response.output_text.delta\",\n" + "data: \"delta\":\" café\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":${response("你好 café")}}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).setChunkedBody(body, 1))
        val seen = mutableListOf<String>()
        val result = api.streamMeaning("policy", "Hei") { seen += it }
        assertEquals(listOf("你好", "你好 café"), seen)
        assertEquals("你好 café", result.text); assertEquals(APIUsage(12, 7), result.usage)
        val sent = server.takeRequest()
        assertEquals("text/event-stream", sent.getHeader("Accept"))
        assertEquals(JsonPrimitive(true), Json.parseToJsonElement(sent.body.readUtf8()).jsonObject["stream"])
        assertEquals(1, server.requestCount)
    }
    @Test fun streamErrorsAndMissingCompletionNeverBecomeSuccessfulRepliesOrRetry() = runBlocking {
        val events = listOf("response.failed", "response.incomplete", "error", "response.refusal.delta")
        val bodies = events.map { "data: {\"type\":\"$it\"}\n\n" } + listOf(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n", "data: [DONE]\n\n", "data: " + "x".repeat(65_537))
        for (body in bodies) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            try { api.streamMeaning("policy", "Hei") {}; fail("Accepted incomplete stream") }
            catch (_: Exception) { }
        }
        assertEquals(bodies.size, server.requestCount)
    }
    @Test fun cancellationClosesAStalledStreamPromptly() = runBlocking {
        // Choose throttling after reading the request; enqueueing it would throttle the upload too.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n").throttleBody(1, 1, TimeUnit.SECONDS)
        }
        val job = launch { api.streamMeaning("policy", "Hei") {} }
        withTimeout(5000) { while (server.requestCount == 0) delay(10) }
        withTimeout(1500) { job.cancelAndJoin() }
        assertTrue(job.isCancelled); assertEquals(1, server.requestCount)
    }
    @Test fun usesExpectedEndpointModelConsentIndependentStoreFalseAndParsesUsage() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        val result = api.respond("policy", "hello")
        val request = server.takeRequest(2,TimeUnit.SECONDS)!!
        assertEquals("/v1/responses",request.path)
        val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-5.6-luna",body["model"]!!.jsonPrimitive.content)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertEquals("Hola",result.text); assertEquals(APIUsage(12,7,0),result.usage)
    }
    @Test fun rejectsRedirectWithoutFollowingOrLeakingCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other")))
        try { api.post("responses",buildJsonObject{}); fail("accepted redirect") } catch (e: APIClient.APIException.Http) { assertEquals(302,e.status) }
        assertEquals(1,server.requestCount)
    }
    @Test fun missingKeyAndUnsafePathsNeverSendRequests() = runBlocking {
        val missing=APIClient(null,OkHttpClient(),server.url("/v1/"))
        try { missing.post("responses",buildJsonObject{}); fail("missing key accepted") } catch (_: APIClient.APIException.MissingKey) { }
        for(path in listOf("https://example.com","../other","/responses")) {
            try { api.post(path,buildJsonObject{}); fail("unsafe path accepted") } catch (_: APIClient.APIException.InvalidResponse) { }
        }
        assertEquals(0,server.requestCount)
    }
    @Test fun incompleteRefusalAndMalformedResultsDoNotBecomeReplies() = runBlocking {
        for(body in listOf("{}","not json","""{"status":"incomplete"}""","""{"status":"completed","output":[{"content":[{"type":"refusal","refusal":"no"}]}]}""")) {
            server.enqueue(MockResponse().setBody(body))
            try { api.respond("p","q"); fail("bad result accepted") } catch (_: APIClient.APIException) { }
        }
    }
    @Test fun retainsOnlySafeDeduplicatedCitationsAndCountsSearches() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"web_search_call"},{"content":[{"type":"output_text","text":"news","annotations":[{"type":"url_citation","url":"http://bad.example"},{"type":"url_citation","url":"https://user@example.com"},{"type":"url_citation","url":"https://example.com","title":"Good"},{"type":"url_citation","url":"https://example.com","title":"Duplicate"}]}]}]}"""))
        val result=api.respond("p","q",search=true)
        assertEquals(1,result.sources.size); assertEquals("Good",result.sources.single().title); assertEquals(1,result.usage.searches)
        val body=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(1,body["max_tool_calls"]!!.jsonPrimitive.int)
    }
    @Test fun cancellationStopsAResponseThatStallsMidBody() = runBlocking {
        server.enqueue(MockResponse().setBody(response()).throttleBody(1, 1, TimeUnit.SECONDS))
        val job = launch { api.respond("p", "q") }
        delay(200)
        withTimeout(1500) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
    @Test fun largeResponsesAreBounded() = runBlocking {
        server.enqueue(MockResponse().setBody(" ".repeat(1_048_577)))
        try { api.post("responses",buildJsonObject{}); fail("large response accepted") } catch (_: APIClient.APIException.InvalidResponse) { }
    }
    @Test fun providerInterfaceKeepsByokVoiceModelAndTransportPayload() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"session":{"id":"provider-live"},"transport":{"type":"webrtc","sdp":"v=0\\r\\n"}}"""))
        val provider: LiveSessionProvider = api
        val result = provider.createLiveSession(LiveSessionRequest("v=0", "Teaching policy", language = "es-ES"))
        val request = server.takeRequest(); val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("/v1/live/sessions", request.path)
        assertEquals("Bearer sk-fake-test-only", request.getHeader("Authorization"))
        assertEquals("gpt-live-1", body["session"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), body["session"]!!.jsonObject["store"])
        assertEquals(JsonPrimitive("Teaching policy"), body["session"]!!.jsonObject["instructions"])
        assertEquals("provider-live", result.providerSessionID); assertNull(result.lease)
        assertNull(body["language"])
    }
}
