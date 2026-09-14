package chat.mural.network

import chat.mural.core.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ManagedAccountClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ManagedAccountClient
    private val id = "12345678-1234-1234-1234-123456789012"
    private val session = AccountSession(id, "a".repeat(43), 1_700_000_000_000)
    @Before fun setup() { server = MockWebServer(); server.start(); api = ManagedAccountClient(server.url("/"), OkHttpClient()) }
    @After fun teardown() { server.shutdown() }

    @Test fun configurationRequiresUnambiguousHttpsOriginAndServerClientID() {
        val client = "123-abc.apps.googleusercontent.com"
        assertNotNull(ManagedAccountConfiguration.parse("https://api.example.test", client))
        for (url in listOf("http://api.example.test", "https://user@api.example.test", "https://api.example.test/v1/",
            "https://api.example.test?other=1", "https://api.example.test#fragment", "https://api.example.test:444"))
            assertNull(ManagedAccountConfiguration.parse(url, client))
        assertNull(ManagedAccountConfiguration.parse("https://api.example.test", "not-a-client"))
    }
    @Test fun exchangesServerNonceAndSendsBearerOnlyToAuthenticatedRoutes() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"challengeID":"$id","nonce":"${"b".repeat(64)}","expiresInSeconds":300}"""))
        server.enqueue(MockResponse().setBody("""{"accountID":"$id","accessToken":"${session.accessToken}","expiresInSeconds":86400}"""))
        server.enqueue(MockResponse().setBody("""{"accountID":"$id","email":"me@example.test","providers":["google"],"createdAt":"2026-09-13T12:00:00.000Z"}"""))
        val challenge = api.challenge(); val exchange = api.exchange(challenge, "synthetic-google-token")
        assertEquals(id, exchange.accountID); assertEquals(id, api.profile(session).accountID)
        val first = server.takeRequest(); assertEquals("/v1/auth/challenge", first.path); assertNull(first.getHeader("Authorization"))
        val second = server.takeRequest(); assertNull(second.getHeader("Authorization"))
        val body = Json.parseToJsonElement(second.body.readUtf8()).jsonObject
        assertEquals(id, body["challengeID"]!!.jsonPrimitive.content); assertEquals("google", body["provider"]!!.jsonPrimitive.content)
        val third = server.takeRequest(); assertEquals("Bearer ${session.accessToken}", third.getHeader("Authorization"))
        assertEquals("no-store", third.getHeader("Cache-Control")); assertEquals("/v1/account", third.path)
        assertFalse(exchange.toString().contains(session.accessToken)); assertFalse(session.toString().contains(session.accessToken))
    }
    @Test fun redirectCannotReceiveAccountTokensEvenIfInjectedClientFollowsRedirects() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")).setBody("{}"))
        try { api.profile(session); fail("followed redirect") } catch (error: AccountFailure.Http) { assertEquals(307, error.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun recoveryBindsTheExpectedOwnerBeforeTheServerCanIssueAnotherAccountToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"same_account_required"}}"""))
        try {
            api.exchange(AccountChallenge(id, "b".repeat(64), 300), "synthetic-google-token", id)
            fail("accepted another account")
        } catch (failure: AccountFailure.Http) { assertEquals("same_account_required", failure.code) }
        val request = server.takeRequest()
        assertEquals(id, Json.parseToJsonElement(request.body.readUtf8()).jsonObject["expectedAccountID"]!!.jsonPrimitive.content)
        assertNull(request.getHeader("Authorization")); assertEquals(1, server.requestCount)
    }
    @Test fun malformedOrOversizedResponseAndCrossAccountProfileAreRejected() = runBlocking {
        for (body in listOf("not-json", " ".repeat(65_537),
            """{"accountID":"87654321-1234-1234-1234-123456789012","email":null,"providers":["google"],"createdAt":"2026-09-13"}""")) {
            server.enqueue(MockResponse().setBody(body))
            try { api.profile(session); fail("accepted invalid profile") } catch (_: AccountFailure.InvalidResponse) { }
        }
    }
    @Test fun balanceRejectsNegativeOverflowAndInconsistentTotals() = runBlocking {
        for ((total, held, available) in listOf(Triple("-1", "0", "-1"), Triple("9007199254740992", "0", "9007199254740992"),
            Triple("60000", "70000", "-10000"), Triple("60000", "1", "60000"), Triple("0.5", "0", "0.5"))) {
            server.enqueue(MockResponse().setBody("""{"unit":"milliseconds","billingBasis":"connected-conversation-time","balanceMilliseconds":$total,"reservedMilliseconds":$held,"availableMilliseconds":$available}"""))
            try { api.minutes(session); fail("accepted invalid balance") } catch (_: AccountFailure.InvalidResponse) { }
        }
        server.enqueue(MockResponse().setBody("""{"unit":"milliseconds","billingBasis":"connected-conversation-time","balanceMilliseconds":60000,"reservedMilliseconds":1234,"availableMilliseconds":58766}"""))
        assertEquals(58_766, api.minutes(session).availableMilliseconds)
    }
    @Test fun errorMessagesNeverReflectServerContentOrTokens() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"unresolved_billing","message":"secret ${session.accessToken}"}}"""))
        try { api.delete(session); fail("deleted") } catch (error: AccountFailure.Http) {
            assertEquals("unresolved_billing", error.code); assertFalse(error.toString().contains(session.accessToken))
        }
    }
    @Test fun cancellationClosesStalledResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"googleAndroid\":true}").throttleBody(1, 1, TimeUnit.SECONDS))
        val job = launch { api.providers() }
        delay(150); withTimeout(1500) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
    }
}
