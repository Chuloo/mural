package chat.mural.network

import chat.mural.core.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReportClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ReportClient
    private val report = AIReportSubmission("e3c1d862-2d0f-4bf0-a44f-404e9c559581", "es", "incorrect", "Selected excerpt.", REPORT_CONSENT_VERSION)
    @Before fun setup() { server = MockWebServer(); server.start(); api = ReportClient(server.url("/"), OkHttpClient()) }
    @After fun teardown() { server.shutdown() }

    @Test fun acceptsOnlyConfiguredHttpsRootOrigin() {
        assertNotNull(ReportConfiguration.parse("https://api.example.test"))
        for (value in listOf("", "http://api.example.test", "https://user:pass@api.example.test", "https://api.example.test/v1",
            "https://api.example.test?destination=elsewhere", "https://api.example.test#fragment", "https://api.example.test:444"))
            assertNull(ReportConfiguration.parse(value))
    }

    @Test fun fixedPathsSendOnlyExplicitReportFieldsAndNeverCredentialsOrCookies() = runBlocking {
        val injected = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
            override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("session").value("private").domain(url.host).build())
        }).addInterceptor { it.proceed(it.request().newBuilder().header("Authorization", "Bearer private").build()) }.build()
        api = ReportClient(server.url("/"), injected)
        server.enqueue(MockResponse().setBody("{\"aiReports\":true}"))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"accepted":true,"reportID":"${report.reportID}"}"""))
        assertTrue(api.available()); api.submit(report)
        val capability = server.takeRequest(); val submission = server.takeRequest()
        assertEquals("GET", capability.method); assertEquals("/v1/feedback/capabilities", capability.path)
        assertEquals(0L, capability.bodySize)
        assertEquals("POST", submission.method); assertEquals("/v1/feedback/ai", submission.path)
        for (request in listOf(capability, submission)) {
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
        }
        val body = Json.parseToJsonElement(submission.body.readUtf8()).jsonObject
        assertEquals(setOf("reportID", "languageID", "reason", "excerpt", "consentVersion"), body.keys)
        assertEquals(JsonPrimitive(report.excerpt), body["excerpt"])
        assertEquals(JsonPrimitive(REPORT_CONSENT_VERSION), body["consentVersion"])
    }

    @Test fun redirectsAndIncorrectAcknowledgementsCannotPretendSuccess() = runBlocking {
        for (response in listOf(
            MockResponse().setResponseCode(307).setHeader("Location", server.url("/leak")),
            MockResponse().setResponseCode(200).setBody("""{"accepted":true,"reportID":"${report.reportID}"}"""),
            MockResponse().setResponseCode(202).setBody("""{"accepted":true,"reportID":"another"}"""),
            MockResponse().setResponseCode(202).setBody("""{"accepted":"true","reportID":"${report.reportID}"}"""),
            MockResponse().setResponseCode(202).setBody("not-json"),
            MockResponse().setResponseCode(202).setBody(" ".repeat(16_385)),
        )) {
            val before = server.requestCount
            server.enqueue(response)
            try { api.submit(report); fail("accepted invalid response") } catch (_: ReportFailure.Invalid) {}
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun unavailableRateLimitedAndServerFailuresAreSanitizedWithoutRetry() = runBlocking {
        for (status in listOf(503, 429, 500)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status).setBody("private server text").setHeader("Retry-After", "60"))
            try { api.submit(report); fail("accepted failure") } catch (error: ReportFailure) {
                if (status == 503) assertTrue(error is ReportFailure.Unavailable) else assertTrue(error is ReportFailure.Retry)
                assertFalse(error.toString().contains("private server text"))
            }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun capabilityMustBeBooleanAndInvalidPayloadCannotReachNetwork() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"aiReports\":false}")); assertFalse(api.available())
        for (body in listOf("{\"aiReports\":\"true\"}", "{}")) {
            server.enqueue(MockResponse().setBody(body))
            try { api.available(); fail("accepted malformed capability") } catch (_: ReportFailure.Invalid) {}
        }
        val before = server.requestCount
        try { api.submit(report.copy(excerpt = "🙂".repeat(1001))); fail("accepted oversized excerpt") } catch (_: ReportFailure.Invalid) {}
        assertEquals(before, server.requestCount)
    }

    @Test fun cancellationClosesStalledRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"aiReports\":true}").throttleBody(1, 1, TimeUnit.SECONDS))
        val job = launch { api.available() }
        delay(150); withTimeout(1500) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
    }
}
