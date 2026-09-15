package chat.mural.network

import chat.mural.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GuestMinuteClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: GuestMinuteClient
    private val now = 1_800_000_000_000L
    private val id = "11111111-1111-4111-8111-111111111111"
    private val installation = "i".repeat(43)
    private val guest = AccountSession(id, "g".repeat(43), now + 86_400_000)
    @Before fun setup() { server = MockWebServer(); server.start(); api = GuestMinuteClient(server.url("/"), OkHttpClient(), { now }) }
    @After fun teardown() { server.shutdown() }
    @Test fun guestGrantIsAnonymousAndUsesOnlyBodyInstallationSecret() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"available":true,"guestID":"$id","accessToken":"${guest.accessToken}","expiresInSeconds":86400,"remainingMilliseconds":480000,"resumed":true}"""))
        val grant = api.start(installation) as GuestGrant.Available
        assertEquals(480_000L, grant.remainingMilliseconds); assertEquals(guest, grant.session); assertTrue(grant.resumed)
        val request = server.takeRequest()
        assertEquals("/v1/guest/minutes", request.path); assertNull(request.getHeader("Authorization"))
        assertEquals(buildJsonObject { put("installationToken", installation) }, Json.parseToJsonElement(request.body.readUtf8()))
    }
    @Test fun expectedUnavailabilityIsDistinctFromAdmissionAndNetworkErrors() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"available":false,"reason":"temporarily_unavailable","remainingMilliseconds":0}"""))
        server.enqueue(MockResponse().setBody("""{"available":false,"reason":"sign_in_required","remainingMilliseconds":0}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"rate_limited"}}"""))
        assertEquals(GuestGrant.TemporarilyUnavailable, api.start(installation))
        assertEquals(GuestGrant.SignInRequired, api.start(installation))
        try { api.start(installation); fail("admission must remain an error") } catch (error: AccountFailure.Http) { assertEquals(429, error.status) }
    }
    @Test fun invalidInstallationProofDoesNotMakeARequest() = runBlocking {
        for (proof in listOf("short", "i".repeat(44), "i".repeat(42) + "\n")) {
            try { api.start(proof); fail("accepted malformed proof") } catch (_: AccountFailure.InvalidResponse) { }
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun malformedOrInflatedGrantFailsClosed() = runBlocking {
        for (body in listOf(
            """{"available":true,"guestID":"$id","accessToken":"${guest.accessToken}","expiresInSeconds":86401,"remainingMilliseconds":600000,"resumed":false}""",
            """{"available":true,"guestID":"$id","accessToken":"${guest.accessToken}","expiresInSeconds":86400,"remainingMilliseconds":-1,"resumed":false}""",
            """{"available":false,"reason":"temporarily_unavailable","remainingMilliseconds":600000}""",
            """{"available":false,"reason":"unrecognized","remainingMilliseconds":0}""", " ".repeat(65_537))) {
            server.enqueue(MockResponse().setBody(body))
            try { api.start(installation); fail("accepted invalid grant") } catch (_: AccountFailure.InvalidResponse) { }
        }
    }
    @Test fun transferUsesMemberBearerAndGuestBodyWithNoClientAmountOrAccountOverride() = runBlocking {
        val member = guest.copy(accountID = "22222222-2222-4222-8222-222222222222", accessToken = "m".repeat(43))
        server.enqueue(MockResponse().setBody("""{"transferredMilliseconds":480000,"alreadyLinked":true,"outcome":"transferred"}"""))
        api.link(member, guest.accessToken)
        val request = server.takeRequest()
        assertEquals("/v1/minutes/link-guest", request.path); assertEquals("Bearer ${member.accessToken}", request.getHeader("Authorization"))
        assertEquals(buildJsonObject { put("guestAccessToken", guest.accessToken) }, Json.parseToJsonElement(request.body.readUtf8()))
    }
    @Test fun deferredLinkUsesBothProofsInitiallyThenOnlyMemberBearerForRecovery() = runBlocking {
        val member = guest.copy(accountID = "22222222-2222-4222-8222-222222222222", accessToken = "m".repeat(43))
        repeat(2) { server.enqueue(MockResponse().setBody("""{"transferredMilliseconds":0,"alreadyLinked":false,"outcome":"pending","pending":true}""")) }
        assertTrue(api.deferLink(member, guest.accessToken, guest.accountID).pending)
        assertTrue(api.deferLink(member, null, guest.accountID).pending)
        val initial = server.takeRequest(); val recovery = server.takeRequest()
        assertEquals("Bearer ${member.accessToken}", initial.getHeader("Authorization"))
        assertEquals("Bearer ${member.accessToken}", recovery.getHeader("Authorization"))
        assertEquals(buildJsonObject { put("guestAccessToken", guest.accessToken); put("deferPending", true); put("guestAccountID", guest.accountID) }, Json.parseToJsonElement(initial.body.readUtf8()))
        assertEquals(buildJsonObject { put("deferPending", true); put("guestAccountID", guest.accountID) }, Json.parseToJsonElement(recovery.body.readUtf8()))
        assertEquals("/v1/minutes/link-guest", recovery.path)
    }
    @Test fun deferredRecoveryExplicitlyScopesEachDeviceAndRejectsMalformedGuestID() = runBlocking {
        val otherGuest = "33333333-3333-4333-8333-333333333333"
        repeat(2) { server.enqueue(MockResponse().setBody("""{"transferredMilliseconds":0,"alreadyLinked":false,"outcome":"pending","pending":true}""")) }
        api.deferLink(guest, null, id); api.deferLink(guest, null, otherGuest)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(id, first.getValue("guestAccountID").jsonPrimitive.content)
        assertEquals(otherGuest, second.getValue("guestAccountID").jsonPrimitive.content)
        try { api.deferLink(guest, null, "not-an-account"); fail("Invalid scope") } catch (_: AccountFailure.InvalidResponse) { }
        assertEquals(2, server.requestCount)
    }
    @Test fun legacyCallCannotSilentlyAcceptDeferredOwnership() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"transferredMilliseconds":0,"alreadyLinked":false,"outcome":"pending","pending":true}"""))
        try { api.link(guest, "x".repeat(43)); fail("Legacy call accepted pending") } catch (_: AccountFailure.InvalidResponse) { }
    }
    @Test fun redirectCannotReceiveInstallationSecretOrGuestBearer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")).setBody("{}"))
        try { api.start(installation); fail("followed redirect") } catch (error: AccountFailure.Http) { assertEquals(307, error.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun guestBalanceRejectsInconsistentWalletTotals() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"unit":"milliseconds","billingBasis":"connected-conversation-time","balanceMilliseconds":600000,"reservedMilliseconds":15000,"availableMilliseconds":600000}"""))
        try { api.balance(guest); fail("accepted inconsistent wallet") } catch (_: AccountFailure.InvalidResponse) { }
        val request = server.takeRequest(); assertEquals("Bearer ${guest.accessToken}", request.getHeader("Authorization"))
    }
    @Test fun existingMembersDuplicateTrialRequiresExplicitZeroTransferOutcome() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"transferredMilliseconds":0,"alreadyLinked":true,"outcome":"member_trial_already_claimed"}"""))
        assertEquals("member_trial_already_claimed", api.link(guest, "x".repeat(43)).outcome)
        for (body in listOf(
            """{"transferredMilliseconds":600000,"alreadyLinked":false,"outcome":"member_trial_already_claimed"}""",
            """{"transferredMilliseconds":0,"alreadyLinked":false}""",
            """{"transferredMilliseconds":0,"alreadyLinked":false,"outcome":"unknown"}""")) {
            server.enqueue(MockResponse().setBody(body))
            try { api.link(guest, "x".repeat(43)); fail("accepted unconfirmed transfer outcome") }
            catch (_: AccountFailure.InvalidResponse) { }
        }
    }
    @Test fun rejectedBalancePreservesSafeReferenceWithoutRetrying() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Mural-Error-Reference", "0123abcdef45")
            .setBody("""{"error":{"code":"sign_in_required"}}"""))
        try { api.balance(guest); fail("expected sign-in rejection") }
        catch (error: AccountFailure.Http) {
            assertEquals(401, error.status)
            assertEquals("0123abcdef45", error.reference)
        }
        assertEquals(1, server.requestCount)
    }

}
