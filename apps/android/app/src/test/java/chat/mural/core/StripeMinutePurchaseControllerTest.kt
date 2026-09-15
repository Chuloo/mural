package chat.mural.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StripeMinutePurchaseControllerTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val member = AccountSession(id, "a".repeat(43), 100_000)
    private val quote = AIValueQuote("usd", 2, 200, 1500, 30, 39, 2, 271, 1, "synthetic-usd", "synthetic-estimate")
    private val value = AIValueEntitlement("2000000000", 1200000, quote)
    private val product = MinuteProduct("synthetic-value", "price_synthetic", 20, "usd", 271, "test", value)
    private val url = StripeCheckoutURL.checked("https://checkout.stripe.com/c/pay/cs_test_synthetic#fragment")
    private fun order() = StripeMinuteOrder(id, "usd", 271, url, value)
    private fun status(state: String = "pending") = MinutePurchaseStatus(id, state, 0, 0, 0,
        state == "purchased", AIValueFulfillment(if (state == "purchased") "2000000000" else "0", "0", "0"))
    private class Memory : StripePurchaseAttemptStorage {
        val attempts = mutableMapOf<String, StripePurchaseAttempt>()
        var failWrites = false
        override suspend fun read(accountID: String) = attempts[accountID]
        override suspend fun save(attempt: StripePurchaseAttempt) { if (failWrites) throw MinuteCommerceFailure.Unavailable; attempts[attempt.accountID] = attempt }
        override suspend fun remove(accountID: String) { attempts.remove(accountID) }
    }
    private inner class API : StripeCommerceService {
        var catalogValue = MinuteCatalog(true, listOf(product))
        var response = status()
        var checkout = url
        var onCreate: suspend () -> Unit = {}
        var calls = 0
        var reads = 0
        var foundOrder: String? = null
        var lookups = 0
        var onLookup: suspend () -> Unit = {}
        val keys = mutableListOf<String>()
        override suspend fun catalog(): MinuteCatalog { calls++; return catalogValue }
        override suspend fun createStripe(session: AccountSession, sku: String, idempotencyKey: String): StripeMinuteOrder {
            calls++; keys += idempotencyKey; onCreate(); return StripeMinuteOrder(id, "usd", 271, checkout, value)
        }
        override suspend fun findStripeOrder(session: AccountSession, idempotencyKey: String): String? {
            lookups++; onLookup(); return foundOrder
        }
        override suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus { reads++; return response }
        override suspend fun balance(session: AccountSession) = MinuteBalance("milliseconds", "connected-conversation-time", 600000, 0, 600000)
    }
    private fun controller(api: API, memory: Memory, current: suspend () -> AccountSession? = { member }) =
        StripeMinutePurchaseController(api, memory, current, enabled = true, now = { 1000 }, pause = {})

    @Test fun disabledMakesNoRequestsAndGuestCannotCreate() = runTest {
        val api = API(); val memory = Memory()
        val disabled = StripeMinutePurchaseController(api, memory, { member })
        disabled.onForeground(); disabled.buy(product.sku) { error("disabled") }; assertEquals(0, api.calls)
        val guest = controller(api, memory, { null }); guest.onForeground()
        guest.buy(product.sku) { error("guest") }
        assertTrue(api.keys.isEmpty()); assertTrue(memory.attempts.isEmpty())
        assertEquals(MinutePurchaseNotice.SIGN_IN_REQUIRED, guest.state.value.notice)
    }
    @Test fun browserReturnDoesNotGrantAndRepeatedResumeOnlyUsesServerStatus() = runTest {
        val api = API(); val memory = Memory(); val controller = controller(api, memory)
        controller.onForeground()
        assertEquals(value, controller.state.value.packs.single().aiValue)
        controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
        assertEquals(MinutePurchaseNotice.PENDING, controller.state.value.notice)
        controller.onForeground(); assertEquals(6, api.reads)
        assertEquals(MinutePurchaseNotice.PENDING, controller.state.value.notice)
        assertNotNull(memory.attempts[id])
        api.response = status("purchased"); controller.onForeground()
        assertEquals(MinutePurchaseNotice.ADDED, controller.state.value.notice)
        assertNull(memory.attempts[id])
        controller.onForeground(); assertEquals(7, api.reads)
    }
    @Test fun interruptedCreateSurvivesRestartAndReusesSameKeyWithoutOpeningBrowser() = runTest {
        val api = API(); val memory = Memory(); var controller = controller(api, memory)
        controller.onForeground(); api.onCreate = { throw MinuteCommerceFailure.Unavailable }
        controller.buy(product.sku) { error("failed create") }
        val key = memory.attempts[id]!!.key
        assertNull(memory.attempts[id]!!.orderID)
        controller.close(); api.onCreate = {}; controller = controller(api, memory)
        controller.onForeground()
        assertEquals(listOf(key, key), api.keys)
        assertEquals(id, memory.attempts[id]!!.orderID)
        controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
        assertEquals(listOf(key, key, key), api.keys)
    }
    @Test fun lostResponseIsRecoveredByKeyEvenWhenExpiredCheckoutCannotReopen() = runTest {
        val api = API(); val memory = Memory(); var controller = controller(api, memory)
        controller.onForeground()
        api.onCreate = { throw MinuteCommerceFailure.Unavailable }
        controller.buy(product.sku) { error("lost checkout response") }
        assertNull(memory.attempts[id]!!.orderID)
        controller.close(); api.foundOrder = id; api.response = status("voided")
        controller = controller(api, memory); controller.onForeground()
        assertEquals(1, api.keys.size)
        assertEquals(1, api.lookups)
        assertNull(memory.attempts[id])
        assertEquals(MinutePurchaseNotice.REVERSED, controller.state.value.notice)
    }
    @Test fun unavailableLookupCannotCreateAnotherOrder() = runTest {
        val api = API(); val memory = Memory(); val controller = controller(api, memory)
        controller.onForeground(); api.onCreate = { throw MinuteCommerceFailure.Unavailable }
        controller.buy(product.sku) { error("failed") }
        api.onLookup = { throw MinuteCommerceFailure.Unavailable }
        controller.onForeground()
        controller.buy(product.sku) { error("lookup failed") }
        assertEquals(1, api.keys.size)
        assertNotNull(memory.attempts[id])
    }

    @Test fun removedProductAdmissionClearsUncreatedAttemptAndAllowsAnotherPack() = runTest {
        for (code in listOf("ai_value_product_unavailable", "minute_product_unavailable")) {
            val api = API(); val memory = Memory(); val controller = controller(api, memory)
            controller.onForeground(); api.onCreate = { throw MinuteCommerceFailure.Unavailable }
            controller.buy(product.sku) { error("lost response") }
            val oldKey = memory.attempts[id]!!.key
            val replacement = product.copy(sku = "replacement-value")
            api.catalogValue = MinuteCatalog(true, listOf(replacement))
            api.onCreate = { throw MinuteCommerceFailure.Http(503, code) }
            controller.onForeground()
            assertEquals(1, api.lookups)
            assertNull(memory.attempts[id])
            api.onCreate = {}
            controller.buy(replacement.sku) { MinuteStoreOutcome.OPENED }
            assertNotEquals(oldKey, memory.attempts[id]!!.key)
            assertEquals(replacement.sku, memory.attempts[id]!!.sku)
        }
    }
    @Test fun directAdmissionRejectionCanRetryButAmbiguousAndPostInsertFailuresKeepKey() = runTest {
        for (failure in listOf(
            MinuteCommerceFailure.Http(503, "ai_value_product_unavailable"),
            MinuteCommerceFailure.Http(503, "minute_purchases_unavailable"),
            MinuteCommerceFailure.Http(409, "checkout_reconciliation_required"),
            MinuteCommerceFailure.Http(409, "checkout_no_longer_open"),
            MinuteCommerceFailure.Http(409, "idempotency_conflict"),
            MinuteCommerceFailure.Http(401, "unauthorized"),
            MinuteCommerceFailure.Http(503, "stripe_minute_price_mismatch"),
            MinuteCommerceFailure.Http(502, "stripe_provider_unavailable"),
            MinuteCommerceFailure.InvalidResponse,
            MinuteCommerceFailure.Unavailable)) {
            val api = API(); val memory = Memory(); val controller = controller(api, memory)
            controller.onForeground(); api.onCreate = { throw failure }
            controller.buy(product.sku) { error("rejected") }
            if (failure is MinuteCommerceFailure.Http && failure.code == "ai_value_product_unavailable") {
                assertNull(memory.attempts[id])
            } else {
                val firstKey = memory.attempts[id]!!.key
                controller.onForeground()
                assertEquals(firstKey, memory.attempts[id]!!.key)
                assertEquals(listOf(firstKey, firstKey), api.keys)
            }
        }
    }
    @Test fun knownOrderIsNeverClearedByCatalogFailure() = runTest {
        val api = API(); val memory = Memory(); val controller = controller(api, memory)
        controller.onForeground(); controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
        val attempt = memory.attempts[id]
        api.onCreate = { throw MinuteCommerceFailure.Http(503, "ai_value_product_unavailable") }
        controller.buy(product.sku) { error("rejected") }
        assertEquals(attempt, memory.attempts[id])
    }

    @Test fun duplicateTapsAndAccountSwitchDuringCreateCannotLaunchForAnotherMember() = runTest {
        val api = API(); val memory = Memory(); var selected: AccountSession? = member
        val controller = controller(api, memory) { selected }; controller.onForeground()
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        api.onCreate = { entered.complete(Unit); finish.await() }
        val buy = launch { controller.buy(product.sku) { error("stale account") } }
        entered.await()
        controller.buy(product.sku) { error("duplicate") }
        selected = member.copy(accountID = "87654321-1234-1234-1234-123456789012")
        finish.complete(Unit); buy.join()
        assertEquals(1, api.keys.size)
        assertEquals(id, memory.attempts[id]!!.orderID)
        assertNull(controller.state.value.balance)
        assertEquals(MinutePurchaseNotice.SIGN_IN_REQUIRED, controller.state.value.notice)
    }
    @Test fun storageFailurePreventsOrderAndBrowserFailurePreservesRecoverableOrder() = runTest {
        val api = API(); val memory = Memory(); val controller = controller(api, memory); controller.onForeground()
        memory.failWrites = true; controller.buy(product.sku) { error("unsaved") }; assertTrue(api.keys.isEmpty())
        memory.failWrites = false; controller.buy(product.sku) { MinuteStoreOutcome.UNAVAILABLE }
        val key = memory.attempts[id]!!.key
        controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
        assertEquals(listOf(key, key), api.keys)
        assertNotEquals(MinutePurchaseNotice.ADDED, controller.state.value.notice)
    }
    @Test fun changedQuoteRequiresNewUserChoiceBeforeCreatingOrder() = runTest {
        val api = API(); val memory = Memory(); val controller = controller(api, memory); controller.onForeground()
        val changedValue = value.copy(estimatedMilliseconds = 600000)
        api.catalogValue = MinuteCatalog(true, listOf(product.copy(minutes = 10, aiValue = changedValue)))
        controller.buy(product.sku) { error("unconfirmed changed quote") }
        assertEquals(MinutePurchaseNotice.PRICE_CHANGED, controller.state.value.notice)
        assertTrue(api.keys.isEmpty())
    }
    @Test fun checkoutEnvironmentComesFromSessionPathAndNeverItsFragment() = runTest {
        for (expected in listOf("test", "live")) {
            val opposite = if (expected == "test") "live" else "test"
            for (pathEnvironment in listOf(expected, opposite)) {
                val fragmentEnvironment = if (pathEnvironment == "test") "live" else "test"
                val checkout = StripeCheckoutURL.checked("https://checkout.stripe.com/c/pay/cs_${pathEnvironment}_synthetic#/cs_${fragmentEnvironment}_fragment")
                assertEquals(pathEnvironment, checkout.environment)
                val api = API().apply {
                    catalogValue = MinuteCatalog(true, listOf(product.copy(environment = expected)))
                    this.checkout = checkout
                }
                val memory = Memory()
                val controller = StripeMinutePurchaseController(api, memory, { member }, enabled = true,
                    expectedEnvironment = expected, now = { 1000 }, pause = {})
                controller.onForeground()
                var launched = false
                controller.buy(product.sku) { launched = true; MinuteStoreOutcome.OPENED }
                assertEquals(pathEnvironment == expected, launched)
                assertEquals(if (pathEnvironment == expected) MinutePurchaseNotice.PENDING else MinutePurchaseNotice.UNAVAILABLE,
                    controller.state.value.notice)
                assertNotNull(memory.attempts[id])
            }
        }
    }

    @Test fun checkoutURLAllowlistRejectsCredentialLeaksAndLookalikeDestinations() {
        assertEquals("StripeCheckoutURL(redacted)", url.toString())
        for (bad in listOf("http://checkout.stripe.com/c/pay/cs_test_x", "https://checkout.stripe.com.evil.test/c/pay/cs_test_x",
            "https://user@checkout.stripe.com/c/pay/cs_test_x", "https://checkout.stripe.com:443/c/pay/cs_test_x",
            "https://checkout.stripe.com/c/pay/cs_test_x?token=secret", "https://checkout.stripe.com/redirect",
            "javascript:alert(1)", "https://checkout.stripe.com/c/pay/cs_test_x\n", "https://evil.test/#https://checkout.stripe.com/c/pay/cs_test_x")) {
            try { StripeCheckoutURL.checked(bad); fail(bad) } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        }
        assertEquals(PurchaseChannel.PLAY, PurchaseChannel.parse("play"))
        assertEquals(PurchaseChannel.STRIPE, PurchaseChannel.parse("stripe"))
        assertNull(PurchaseChannel.parse("installer-stripe"))
    }
}
