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

class MinuteCommerceClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MinuteCommerceClient
    private val id = "12345678-1234-1234-1234-123456789012"
    private val session = AccountSession(id, "a".repeat(43), 100_000)
    private val status = """{"orderID":"$id","state":"purchased","grantedMilliseconds":1800000,"reversedMilliseconds":0,"reversalOutstandingMilliseconds":0,"fulfillmentRecorded":true}"""
    private val catalog = """{"available":true,"billingBasis":"connected-conversation-time","products":[{"sku":"test-30","providerProduct":"test_30","minutes":30,"currency":"usd","totalMinor":599,"environment":"test"}]}"""
    private val order = """{"orderID":"$id","minutes":30,"currency":"usd","totalMinor":599,"payment":{"orderID":"$id","obfuscatedAccountID":"${"b".repeat(64)}","obfuscatedProfileID":"${"c".repeat(64)}"}}"""
    private val balance = """{"unit":"milliseconds","billingBasis":"connected-conversation-time","balanceMilliseconds":1800000,"reservedMilliseconds":20000,"availableMilliseconds":1780000}"""
    @Before fun setup() { server = MockWebServer(); server.start(); api = MinuteCommerceClient(server.url("/"), OkHttpClient(), now = { 1_000 }) }
    @After fun teardown() { server.shutdown() }

    @Test fun actualValueCatalogOrderAndRefundKeepMoneySeparateFromTime() = runBlocking {
        val value = """"entitlementKind":"ai_value","billingBasis":"actual-ai-usage","estimate":true,"aiValueNanoUSD":"2000000000","estimatedMilliseconds":1200000,"quote":{"currency":"usd","currencyExponent":2,"aiValueMinor":200,"serviceFeeBasisPoints":1500,"serviceFeeMinor":30,"processingEstimateMinor":39,"processingBufferMinor":2,"totalMinor":271,"policyVersion":1,"exchangeRateVersion":"synthetic-usd","estimateRateVersion":"synthetic-estimate"}"""
        val product = """{"sku":"synthetic-value","providerProduct":"synthetic_value","currency":"usd","totalMinor":271,"environment":"test",$value}"""
        server.enqueue(MockResponse().setBody("""{"available":true,"billingBasis":"actual-ai-usage","products":[$product]}"""))
        val selected = api.catalog().products.single()
        assertEquals(20, selected.minutes)
        assertEquals("2000000000", selected.aiValue!!.aiValueNanoUSD)
        assertEquals(39, selected.aiValue!!.quote.processingEstimateMinor)
        server.enqueue(MockResponse().setBody("""{"orderID":"$id","currency":"usd","totalMinor":271,"payment":{"orderID":"$id","obfuscatedAccountID":"${"b".repeat(64)}","obfuscatedProfileID":"${"c".repeat(64)}"},$value}"""))
        assertTrue(api.create(session, selected.sku, "actual-value-order").matches(selected))
        // A purchased AI balance can cover more than one day; fixed-minute pack caps do not apply.
        val largerValue = value.replace("\"estimatedMilliseconds\":1200000", "\"estimatedMilliseconds\":120000000")
        val largerProduct = product.replace("\"estimatedMilliseconds\":1200000", "\"estimatedMilliseconds\":120000000")
        server.enqueue(MockResponse().setBody("""{"available":true,"billingBasis":"actual-ai-usage","products":[$largerProduct]}"""))
        val larger = api.catalog().products.single()
        assertEquals(2000, larger.minutes)
        server.enqueue(MockResponse().setBody("""{"orderID":"$id","currency":"usd","totalMinor":271,"payment":{"orderID":"$id","obfuscatedAccountID":"${"b".repeat(64)}","obfuscatedProfileID":"${"c".repeat(64)}"},$largerValue}"""))
        assertTrue(api.create(session, larger.sku, "larger-value-order").matches(larger))
        server.enqueue(MockResponse().setBody("""{"orderID":"$id","state":"purchased","entitlementKind":"ai_value","grantedNanoUSD":"2000000000","reversedNanoUSD":"1000000000","reversalOutstandingNanoUSD":"0","fulfillmentRecorded":true}"""))
        val refunded = api.status(session, id)
        assertTrue(refunded.aiValue!!.reversed)
        assertEquals(0, refunded.grantedMilliseconds)
        assertTrue(refunded.fulfillmentRecorded)
        server.enqueue(MockResponse().setBody("""{"available":true,"billingBasis":"actual-ai-usage","products":[${product.replace("\"serviceFeeMinor\":30", "\"serviceFeeMinor\":0")}]}"""))
        try { api.catalog(); fail("dishonest fee breakdown accepted") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
    }

    @Test fun configurationRejectsUnsafeOrAmbiguousOrigins() {
        assertNotNull(MinuteCommerceConfiguration.parse("https://api.mural.chat"))
        for (origin in listOf("http://api.example.test", "https://user@api.example.test", "https://api.example.test/v1/",
            "https://api.example.test:444", "https://api.example.test?x=1", "https://api.example.test#x", "https://api.openai.com"))
            assertNull(MinuteCommerceConfiguration.parse(origin))
    }
    @Test fun allRequestsUseFixedRoutesAndOnlyRequiredIdentifiers() = runBlocking {
        for (body in listOf(catalog, order, status, status, status, balance)) server.enqueue(MockResponse().setBody(body))
        assertTrue(api.catalog().available)
        assertEquals(30, api.create(session, "test-30", "idempotent-001").minutes)
        assertTrue(api.status(session, id).fulfillmentRecorded)
        assertTrue(api.verify(session, id, "transient-token").fulfillmentRecorded)
        assertTrue(api.recover(session, "transient-token").fulfillmentRecorded)
        assertEquals(1_780_000L, api.balance(session).availableMilliseconds)
        val catalogRequest = server.takeRequest(); assertEquals("/v1/minutes/products?provider=play", catalogRequest.path)
        assertNull(catalogRequest.getHeader("Authorization"))
        val create = server.takeRequest(); assertEquals("/v1/minutes/orders", create.path); assertEquals("POST", create.method)
        assertEquals("idempotent-001", create.getHeader("Idempotency-Key"))
        assertEquals(setOf("provider", "sku"), Json.parseToJsonElement(create.body.readUtf8()).jsonObject.keys)
        val read = server.takeRequest(); assertEquals("/v1/minutes/orders/$id", read.path); assertEquals("GET", read.method)
        val verify = server.takeRequest(); assertEquals("/v1/minutes/orders/$id/play", verify.path)
        val recover = server.takeRequest(); assertEquals("/v1/minutes/play/recover", recover.path)
        for (request in listOf(verify, recover)) {
            assertEquals("POST", request.method)
            assertEquals(setOf("purchaseToken"), Json.parseToJsonElement(request.body.readUtf8()).jsonObject.keys)
        }
        val wallet = server.takeRequest(); assertEquals("/v1/minutes", wallet.path)
        for (request in listOf(create, read, verify, recover, wallet)) {
            assertEquals("Bearer ${session.accessToken}", request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control")); assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun expiredSessionsAndMalformedInputsNeverReachTransport() = runBlocking {
        try { api.balance(session.copy(expiresAtMilliseconds = 900)); fail("expired session") } catch (_: MinuteCommerceFailure.SignInRequired) { }
        for (token in listOf("", "line\nbreak", "white space", "é", "a".repeat(4097))) {
            try { api.recover(session, token); fail("bad token") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        }
        try { api.status(session, "../account"); fail("path injection") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        try { api.create(session, "bad sku", "idempotent-001"); fail("invalid SKU") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        try { api.create(session, "test-30", "bad\nheader"); fail("invalid header") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun pricesAndMinutesMustBeIntegersAndCatalogMustBeConsistent() = runBlocking {
        for (body in listOf(catalog.replace("\"minutes\":30", "\"minutes\":\"30\""),
            catalog.replace("\"totalMinor\":599", "\"totalMinor\":599.5"), catalog.replace("\"minutes\":30", "\"minutes\":0"),
            catalog.replace("\"available\":true", "\"available\":false"), catalog.replace("\"totalMinor\":599", "\"totalMinor\":100000001"))) {
            server.enqueue(MockResponse().setBody(body))
            try { api.catalog(); fail("bad catalog") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        }
    }
    @Test fun orderAndStatusCannotSwitchRequestedOrderOrTrustInconsistentFulfillment() = runBlocking {
        val other = "87654321-1234-1234-1234-123456789012"
        for (body in listOf(status.replace(id, other), status.replace("\"state\":\"purchased\"", "\"state\":\"pending\""),
            status.replace("\"fulfillmentRecorded\":true", "\"fulfillmentRecorded\":false"),
            status.replace("\"reversedMilliseconds\":0", "\"reversedMilliseconds\":1800001"))) {
            server.enqueue(MockResponse().setBody(body))
            try { api.status(session, id); fail("bad status") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        }
        server.enqueue(MockResponse().setBody(order.replace("\"payment\":{\"orderID\":\"$id\"", "\"payment\":{\"orderID\":\"$other\"")))
        try { api.create(session, "test-30", "idempotent-001"); fail("cross order binding") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
    }
    @Test fun malformedOversizedAndInvalidWalletResponsesFailClosed() = runBlocking {
        for (body in listOf("not-json", " ".repeat(131_073), balance.replace("1780000", "1780001"),
            balance.replace("\"balanceMilliseconds\":1800000", "\"balanceMilliseconds\":9007199254740992"),
            balance.replace("\"reservedMilliseconds\":20000", "\"reservedMilliseconds\":-1"))) {
            server.enqueue(MockResponse().setBody(body))
            try { api.balance(session); fail("bad balance") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        }
        server.enqueue(MockResponse().setChunkedBody(" ".repeat(131_073), 1024))
        try { api.balance(session); fail("oversized chunked body") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
    }
    @Test fun redirectsAndServerMessagesCannotLeakTokens() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")).setBody("{}"))
        try { api.recover(session, "receipt-token"); fail("followed redirect") } catch (error: MinuteCommerceFailure.Http) {
            assertEquals(307, error.status)
        }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"purchase_verification_failed","message":"receipt-token ${session.accessToken}"}}"""))
        try { api.recover(session, "receipt-token"); fail("ignored error") } catch (error: MinuteCommerceFailure.Http) {
            assertEquals("purchase_verification_failed", error.code)
            assertFalse(error.toString().contains("receipt-token")); assertFalse(error.toString().contains(session.accessToken))
        }
    }
    @Test fun cancellationStopsAStalledResponse() = runBlocking {
        server.enqueue(MockResponse().setBody(catalog).throttleBody(1, 1, TimeUnit.SECONDS))
        val job = launch { api.catalog() }; delay(150)
        withTimeout(1500) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
    }
}
