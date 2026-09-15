package chat.mural.network

import chat.mural.core.AccountSession
import chat.mural.core.LanguageRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HostedAPIClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HostedAPIClient
    private val now = 1_700_000_000_000L
    private val sessionID = "e3c1d862-2d0f-4bf0-a44f-404e9c559581"
    private val account = AccountSession("a3c1d862-2d0f-4bf0-a44f-404e9c559581", "a".repeat(43), now + 86_400_000)
    private var stored: AccountSession? = account
    private val create = LiveSessionRequest("v=0\r\n", "Teaching policy", language = "es-ES")
    private fun created() = """{"sessionID":"$sessionID","providerSessionID":"provider-opaque","sdp":"v=0\r\n","deadline":"2023-11-14T22:23:20Z","reservedMilliseconds":600000,"billingBasis":"connected-conversation-time","experimental":true}"""
    private fun status(state: String = "closing") = """{"sessionID":"$sessionID","state":"$state","deadline":"2023-11-14T22:23:20Z","observedMilliseconds":1000,"reservedMilliseconds":600000,"chargedMilliseconds":null,"billingBasis":"connected-conversation-time","providerCostNanoUSD":null}"""
    @Before fun setup() { server = MockWebServer(); server.start(); api = HostedAPIClient(server.url("/"), { stored }, OkHttpClient(), { now }) }
    @After fun teardown() { server.shutdown() }

    @Test fun paidSessionKeepsTheRequestedDurationAndActualCostBilling() = runBlocking {
        val metadata = """"fundingMode":"ai-value","billingBasis":"actual-ai-usage","limitMilliseconds":1800000,"reservedNanoUSD":"3005000000","minimumChargeMilliseconds":15000,"billingPolicy":"actual-ai-usage-15s-minimum-v1""""
        server.enqueue(MockResponse().setBody("""{"sessionID":"$sessionID","providerSessionID":"provider-opaque","sdp":"v=0\r\n","deadline":"2023-11-14T22:43:20Z","experimental":true,$metadata}"""))
        val connection = api.createLiveSession(create.copy(requestedMilliseconds = 1_800_000))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(1_800_000), body["requestedMilliseconds"])
        val lease = connection.lease as HostedAPIClient.HostedLease
        assertEquals(1_800_000, lease.reservedMilliseconds)
        server.enqueue(MockResponse().setBody("""{"sessionID":"$sessionID","state":"closed","deadline":"2023-11-14T22:43:20Z","observedMilliseconds":120000,"chargedNanoUSD":"101240000",$metadata}"""))
        val result = lease.status()
        assertNull(result.chargedMilliseconds)
        assertEquals("101240000", result.chargedNanoUSD)
        assertEquals("actual-ai-usage", result.billingBasis)
    }

    @Test fun invalidRequestedDurationDoesNotContactProvider() = runBlocking {
        for (duration in listOf(0L, 59_999L, 3_600_001L)) {
            try { api.createLiveSession(create.copy(requestedMilliseconds = duration)); fail("duration accepted") }
            catch (_: HostedFailure.InvalidRequest) { }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun productionOriginCannotBeAmbiguousOrAnOpenAIEndpoint() {
        assertNotNull(HostedConfiguration.parse("https://api.example.test"))
        for (value in listOf("http://api.example.test", "https://user:pass@api.example.test", "https://api.example.test/v1",
            "https://api.example.test:444", "https://api.example.test?other=1", "https://api.example.test#x", "https://api.openai.com"))
            assertNull(HostedConfiguration.parse(value))
    }

    @Test fun capabilityRequiresValidStoredIdentityAndExplicitExperimentalReadiness() = runBlocking {
        stored = null
        try { api.available(); fail("missing identity accepted") } catch (_: HostedFailure.SignInRequired) {}
        stored = account.copy(expiresAtMilliseconds = now - 1)
        try { api.available(); fail("expired identity accepted") } catch (_: HostedFailure.SignInRequired) {}
        assertEquals(0, server.requestCount)
        stored = account
        for ((body, expected) in listOf("{\"hostedMinutes\":false}" to false, "{\"hostedMinutes\":true,\"experimental\":true}" to true)) {
            server.enqueue(MockResponse().setBody(body)); assertEquals(expected, api.available())
            val request = server.takeRequest(); assertEquals("/v1/live/capabilities", request.path)
            assertEquals("Bearer ${account.accessToken}", request.getHeader("Authorization"))
        }
        server.enqueue(MockResponse().setBody("{\"hostedMinutes\":true}"))
        try { api.available(); fail("unchecked readiness") } catch (_: HostedFailure.InvalidResponse) {}
    }

    @Test fun createMapsAllEightLocalesAndOnlyFixedFieldsWithoutCrossProviderHeaders() = runBlocking {
        api = HostedAPIClient(server.url("/"), { stored }, OkHttpClient.Builder().addInterceptor {
            it.proceed(it.request().newBuilder().header("OpenAI-Organization", "private").header("Authorization", "Bearer sk-private").build())
        }.build(), { now })
        for (language in LanguageRegistry.all) {
            server.enqueue(MockResponse().setBody(created()))
            val result = api.createLiveSession(create.copy(language = language.locale))
            val request = server.takeRequest(); assertEquals("/v1/live/sessions", request.path)
            assertEquals(create.requestID, request.getHeader("Idempotency-Key"))
            assertEquals("Bearer ${account.accessToken}", request.getHeader("Authorization")); assertNull(request.getHeader("OpenAI-Organization"))
            assertNull(request.getHeader("Cookie")); assertFalse(request.path!!.contains(account.accessToken))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("sdp", "language", "instructions", "history"), body.keys)
            assertEquals(JsonPrimitive(language.locale), body["language"])
            assertEquals("provider-opaque", result.providerSessionID)
            assertEquals(sessionID, result.lease?.sessionID)
            assertFalse(result.toString().contains("v=0")); assertFalse(result.lease.toString().contains(account.accessToken))
        }
    }

    @Test fun rejectsOversizedInstructionsAndMalformedOrOversizedHistoryBeforeCallingServer() = runBlocking {
        val valid = buildJsonObject {
            put("type", "message"); put("role", "user"); put("content", buildJsonArray {
                add(buildJsonObject { put("type", "input_text"); put("text", "hola") })
            })
        }
        for (request in listOf(create.copy(language = "es"), create.copy(requestID = "bad"),
            create.copy(instructions = "界".repeat(4001)), create.copy(history = JsonArray(List(41) { valid })),
            create.copy(history = JsonArray(listOf(buildJsonObject { put("role", "tool") }))),
            create.copy(history = JsonArray(listOf(buildJsonObject {
                put("type", "message"); put("role", "assistant"); put("content", valid["content"]!!)
            }))), create.copy(history = JsonArray(listOf(buildJsonObject {
                put("type", "message"); put("role", "user"); put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "input_text"); put("text", "界".repeat(2100)) })
                })
            }))) )) {
            try { api.createLiveSession(request); fail("invalid context accepted") } catch (_: HostedFailure.InvalidRequest) {}
        }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody(created()))
        api.createLiveSession(create.copy(history = JsonArray(listOf(valid))))
        assertEquals(JsonArray(listOf(valid)), Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["history"])
    }

    @Test fun statusAndHelpersStayWithCreatingAccountButCleanupUsesOriginalIdentityOnce() = runBlocking {
        server.enqueue(MockResponse().setBody(created()))
        val lease = api.createLiveSession(create).lease as HostedAPIClient.HostedLease
        server.takeRequest()
        server.enqueue(MockResponse().setBody(status("active")))
        assertEquals("active", lease.status().state); assertEquals("/v1/live/sessions/$sessionID", server.takeRequest().path)
        stored = account.copy(accountID = "b3c1d862-2d0f-4bf0-a44f-404e9c559581", accessToken = "b".repeat(43))
        try { lease.status(); fail("cross-account status") } catch (_: HostedFailure.SignInRequired) {}
        try { lease.teaching.respond("policy", "input", purpose = HelperPurpose.MEANING); fail("cross-account helper") } catch (_: HostedFailure.SignInRequired) {}
        server.enqueue(MockResponse().setBody(status()))
        lease.requestClose(); lease.requestClose()
        val closed = server.takeRequest(); assertEquals("/v1/live/sessions/$sessionID/close", closed.path)
        assertEquals("{}", closed.body.readUtf8()); assertEquals("Bearer ${account.accessToken}", closed.getHeader("Authorization"))
        assertEquals(3, server.requestCount)
    }

    @Test fun malformedKnownCreateRequestsCutoffWithoutRepeatingCreate() = runBlocking {
        server.enqueue(MockResponse().setBody(created().replace("\"experimental\":true", "\"experimental\":false")))
        server.enqueue(MockResponse().setBody(status()))
        try { api.createLiveSession(create); fail("invalid create accepted") } catch (_: HostedFailure.Unconfirmed) {}
        assertEquals("/v1/live/sessions", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals("/v1/live/sessions/$sessionID/close", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals(2, server.requestCount)
    }

    @Test fun uncertainOrRedirectedCreateNeverRepeatsAndErrorsDoNotReflectPrivateText() = runBlocking {
        for (status in listOf(307, 502, 429)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/leak"))
                .setBody("""{"error":{"code":"provider_session_unconfirmed","message":"${account.accessToken}"}}"""))
            try { api.createLiveSession(create); fail("failure accepted") } catch (failure: HostedFailure.Http) {
                assertEquals(status, failure.status); assertFalse(failure.toString().contains(account.accessToken))
            }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun helperMapsNormalizedEnvelopeAndHasOneExplicitPurposeWithoutModelOrKeyFields() = runBlocking {
        server.enqueue(MockResponse().setBody(created()))
        val lease = api.createLiveSession(create).lease as HostedAPIClient.HostedLease
        server.takeRequest()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals("/v1/live/sessions/$sessionID/helpers", request.path)
                assertEquals(setOf("requestID", "purpose", "instructions", "input", "search"), body.keys)
                assertEquals(JsonPrimitive("topic"), body["purpose"])
                return MockResponse().setBody("""{"requestID":${body["requestID"]},"text":"Hola","sources":[{"title":"Good","url":"https://example.test"},{"title":"Unsafe","url":"http://example.test"}],"usage":{"inputTokens":12,"cachedInputTokens":2,"cacheWriteTokens":1,"outputTokens":7,"searchCalls":1},"costNanoUSD":"100","rateVersion":"test"}""")
            }
        }
        val result = lease.teaching.respond("policy", "input", search = true, purpose = HelperPurpose.TOPIC)
        assertEquals("Hola", result.text); assertEquals(APIUsage(12, 7, 1), result.usage)
        assertEquals(listOf("https://example.test"), result.sources.map { it.url })
    }

    @Test fun helperNeedsPurposeAndValidSearchSchemaCombinationAndDoesNotRetryUncertainReplies() = runBlocking {
        server.enqueue(MockResponse().setBody(created()))
        val lease = api.createLiveSession(create).lease as HostedAPIClient.HostedLease
        for (call in listOf<suspend () -> Unit>(
            { lease.teaching.respond("policy", "input") },
            { lease.teaching.respond("policy", "input", purpose = HelperPurpose.ASSESSMENT) },
            { lease.teaching.respond("policy", "input", search = true, purpose = HelperPurpose.MEANING) },
        )) try { call(); fail("invalid helper request") } catch (_: HostedFailure.InvalidRequest) {}
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(502).setBody("{\"error\":{\"code\":\"helper_response_uncertain\"}}"))
        try { lease.teaching.respond("policy", "input", purpose = HelperPurpose.HELP); fail("uncertain helper accepted") }
        catch (failure: HostedFailure.Http) { assertEquals("helper_response_uncertain", failure.code) }
        assertEquals(2, server.requestCount)
    }

    @Test fun malformedHelperEnvelopeAndCrossSessionStatusCannotBecomeTrustedResults() = runBlocking {
        server.enqueue(MockResponse().setBody(created()))
        val lease = api.createLiveSession(create).lease as HostedAPIClient.HostedLease
        server.enqueue(MockResponse().setBody(status().replace(sessionID, "a3c1d862-2d0f-4bf0-a44f-404e9c559581")))
        try { lease.status(); fail("cross-session status accepted") } catch (_: HostedFailure.InvalidResponse) {}
        for (body in listOf("{}", "{\"requestID\":\"other\",\"text\":\"private provider text\"}", " ".repeat(1_048_577))) {
            val before = server.requestCount
            server.enqueue(MockResponse().setBody(body))
            try { lease.teaching.respond("policy", "input", purpose = HelperPurpose.MEANING); fail("malformed helper accepted") }
            catch (error: HostedFailure.InvalidResponse) { assertFalse(error.toString().contains("private provider text")) }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun cancellationDoesNotCreateAnotherPaidCall() = runBlocking {
        server.enqueue(MockResponse().setBody(created()).setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch { api.createLiveSession(create) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
        withTimeout(1500) { job.cancelAndJoin() }; assertEquals(1, server.requestCount)
    }

    @Test fun interruptedCreateCanDiscoverAndCloseCurrentSessionWithoutCreatingAnother() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"session\":${status("incomplete")}}"))
        val lease = api.currentSession()!!
        assertEquals(sessionID, lease.sessionID)
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse().setBody(status("closed")))
        lease.requestClose()
        assertEquals("/v1/live/sessions/$sessionID/close", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("{\"session\":null}"))
        assertNull(api.currentSession())
        assertEquals("/v1/live/sessions/current", server.takeRequest().path)
        assertEquals(3, server.requestCount)
    }

    @Test fun currentSessionRejectsMalformedOrMissingSessionWithoutAttemptingCreate() = runBlocking {
        for (body in listOf("{}", "{\"session\":{}}", "{\"session\":${status().replace(sessionID, "not-a-session")}}")) {
            server.enqueue(MockResponse().setBody(body))
            try { api.currentSession(); fail("invalid current session accepted") } catch (_: HostedFailure.InvalidResponse) {}
            assertEquals("/v1/live/sessions/current", server.takeRequest().path)
        }
    }

    @Test fun billingMinimumMetadataPreservesLegacyPolicyAndAuthoritativeResidualCharge() = runBlocking {
        for ((minimum, policy) in listOf(0 to "connected-time-only-v1", 15000 to "connected-time-15s-minimum-v1")) {
            val fields = "\"minimumChargeMilliseconds\":$minimum,\"billingPolicy\":\"$policy\"," 
            server.enqueue(MockResponse().setBody(created().replaceFirst("{", "{$fields")))
            val lease = api.createLiveSession(create).lease as HostedAPIClient.HostedLease
            assertEquals(minimum.toLong(), lease.minimumChargeMilliseconds)
            assertEquals(policy, lease.billingPolicy)
            // A residual balance can be smaller than the policy minimum. The server's charge wins.
            val final = status("closed").replace("\"reservedMilliseconds\":600000", "\"reservedMilliseconds\":5000")
                .replace("\"chargedMilliseconds\":null", "\"chargedMilliseconds\":5000")
                .replaceFirst("{", "{$fields")
            server.enqueue(MockResponse().setBody(final))
            val result = lease.status()
            assertEquals(5000L, result.chargedMilliseconds)
            assertEquals(minimum.toLong(), result.minimumChargeMilliseconds)
        }
    }

    @Test fun inconsistentBillingMinimumMetadataCannotBecomeTrustedStatus() = runBlocking {
        server.enqueue(MockResponse().setBody(created()))
        val lease = api.createLiveSession(create).lease as HostedAPIClient.HostedLease
        assertNull(lease.minimumChargeMilliseconds)
        for (fields in listOf("\"minimumChargeMilliseconds\":15000,",
            "\"minimumChargeMilliseconds\":1000,\"billingPolicy\":\"connected-time-15s-minimum-v1\",",
            "\"minimumChargeMilliseconds\":0,\"billingPolicy\":\"connected-time-15s-minimum-v1\",")) {
            server.enqueue(MockResponse().setBody(status().replaceFirst("{", "{$fields")))
            try { lease.status(); fail("inconsistent billing policy accepted") } catch (_: HostedFailure.InvalidResponse) {}
        }
    }

    @Test fun oversizedCreateResponseIsBoundedWithoutRepeatingRequest() = runBlocking {
        server.enqueue(MockResponse().setBody(" ".repeat(1_048_577)))
        try { api.createLiveSession(create); fail("oversized accepted") } catch (_: HostedFailure.InvalidResponse) {}
        assertEquals(1, server.requestCount)
    }
    @Test fun safeRetryMetadataIsParsedWithoutRetryingTheHttpRequest() = runBlocking {
        server.enqueue(MockResponse().setBody(created()))
        val lease = api.createLiveSession(create).lease as HostedAPIClient.HostedLease
        server.takeRequest()
        val cases = listOf(
            Triple("\"retryable\":true,\"retryAfterMilliseconds\":3000", true, 3000L),
            Triple("\"retryable\":false", false, null),
            Triple("\"retryable\":\"true\",\"retryAfterMilliseconds\":\"3000\"", null, null),
            Triple("\"retryable\":true,\"retryAfterMilliseconds\":60001", true, null),
            Triple("\"retryable\":true,\"retryAfterMilliseconds\":0", true, null)
        )
        for ((fields, retryable, wait) in cases) {
            server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":{\"code\":\"helper_session_limit\",$fields}}"))
            try { lease.teaching.respond("policy", "input", purpose = HelperPurpose.MEANING); fail("denial accepted") }
            catch (failure: HostedFailure.Http) {
                assertEquals(429, failure.status); assertEquals("helper_session_limit", failure.code)
                assertEquals(retryable, failure.retryable); assertEquals(wait, failure.retryAfterMilliseconds)
            }
            assertEquals("/v1/live/sessions/$sessionID/helpers", server.takeRequest().path)
        }
        assertEquals(1 + cases.size, server.requestCount)
    }

    @Test fun failedStartCarriesOnlySafeErrorReferenceAndNeverRetries() = runBlocking {
        for ((index, reference) in listOf("0123abcdef45", "person@example.com", "0123ABCDEF45").withIndex()) {
            server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Mural-Error-Reference", reference)
                .setBody("""{"error":{"code":"sign_in_required"}}"""))
            try { api.createLiveSession(create); fail("expected rejected start") }
            catch (error: HostedFailure.Http) {
                assertEquals(401, error.status)
                assertEquals("sign_in_required", error.code)
                assertEquals(if (index == 0) reference else null, error.reference)
            }
            assertEquals(index + 1, server.requestCount)
        }
    }

}
