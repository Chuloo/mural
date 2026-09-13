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
    private lateinit var server: MockWebServer
    private lateinit var api: APIClient
    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = APIClient("sk-fake-test-only", OkHttpClient.Builder().followRedirects(false).build(), server.url("/v1/"))
    }
    @After fun teardown() { server.shutdown() }
    private fun response(text: String = "Hola") = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"$text"}]}],"usage":{"input_tokens":12,"output_tokens":7}}"""
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
}
