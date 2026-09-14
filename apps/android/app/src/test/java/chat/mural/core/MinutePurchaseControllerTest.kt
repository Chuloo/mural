package chat.mural.core

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MinutePurchaseControllerTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val member = AccountSession(id, "a".repeat(43), 100_000)
    private val product = MinuteProduct("test-30", "test_30", 30, "usd", 599, "test")
    private fun offer(product: MinuteProduct = this.product) = MinuteStoreOffer(UUID.randomUUID().toString(), product.providerProduct,
        product.currency.uppercase(), product.expectedMicros()!!, "$5.99")
    private fun order(product: MinuteProduct = this.product) = MinuteOrder(id, product.minutes, product.currency, product.totalMinor,
        PlayOrderBinding(id, "b".repeat(64), "c".repeat(64)))
    private fun balance(value: Long) = MinuteBalance("milliseconds", "connected-conversation-time", value, 0, value)
    private fun status(state: String = "purchased", reversed: Long = 0) = MinutePurchaseStatus(id, state,
        if (state in listOf("pending", "created")) 0 else 1_800_000, reversed, 0, state !in listOf("pending", "created"))
    private inner class API : MinuteCommerceService {
        var calls = 0
        var catalogValue = MinuteCatalog(true, listOf(product))
        var orderValue = order()
        var balanceValue = balance(600_000)
        var statusValue = status()
        val keys = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        var onCreate: suspend () -> Unit = {}
        var onCatalog: suspend () -> Unit = {}
        var onRecover: suspend (String) -> Unit = {}
        override suspend fun catalog(): MinuteCatalog { calls++; onCatalog(); return catalogValue }
        override suspend fun create(session: AccountSession, sku: String, idempotencyKey: String): MinuteOrder {
            calls++; keys += idempotencyKey; onCreate(); return orderValue
        }
        override suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus { calls++; return statusValue }
        override suspend fun verify(session: AccountSession, orderID: String, token: String): MinutePurchaseStatus = error("use reinstall-safe recovery")
        override suspend fun recover(session: AccountSession, token: String): MinutePurchaseStatus {
            calls++; tokens += token; onRecover(token); return statusValue
        }
        override suspend fun balance(session: AccountSession): MinuteBalance { calls++; return balanceValue }
    }
    private inner class Store : MinuteStoreGateway {
        override val events = MutableSharedFlow<MinuteStoreEvent>(extraBufferCapacity = 10)
        var calls = 0
        var closed = false
        var currentOffers = listOf(offer())
        var owned = emptyList<MinuteStorePurchase>()
        override suspend fun connect() { calls++ }
        override suspend fun offers(productIDs: List<String>): List<MinuteStoreOffer> { calls++; return currentOffers }
        override suspend fun purchases(): List<MinuteStorePurchase> { calls++; return owned }
        override fun close() { closed = true }
    }
    private fun TestScope.controller(api: API, store: Store, enabled: Boolean = true,
        current: suspend () -> AccountSession? = { member }) = MinutePurchaseController(backgroundScope, api, store,
        current, enabled, now = { 1_000 })

    @Test fun defaultsMakeNoServerOrStoreRequests() = runTest {
        val api = API(); val store = Store()
        val controller = MinutePurchaseController(backgroundScope, api, store, { member }, now = { 1_000 })
        controller.onForeground(); controller.buy(product.sku) { error("must stay disabled") }; runCurrent()
        store.events.emit(MinuteStoreEvent(MinuteStoreOutcome.PURCHASES_UPDATED)); runCurrent()
        assertEquals(0, api.calls); assertEquals(0, store.calls); assertEquals(MinutePurchaseState(), controller.state.value)
        controller.close(); assertTrue(store.closed)
    }
    @Test fun guestsCanSeeLocalizedPacksButCannotCreateOrders() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store, current = { null })
        controller.refresh(); assertTrue(controller.state.value.available)
        assertEquals("$5.99", controller.state.value.packs.single().formattedPrice)
        controller.buy(product.sku) { error("guest purchase") }
        assertTrue(api.keys.isEmpty()); assertEquals(MinutePurchaseNotice.SIGN_IN_REQUIRED, controller.state.value.notice)
    }
    @Test fun unavailableMismatchedAmbiguousAndWrongEnvironmentCatalogsCannotBeBought() = runTest {
        for (case in 0..4) {
            val api = API(); val store = Store()
            when (case) {
                0 -> api.catalogValue = MinuteCatalog(false, emptyList())
                1 -> store.currentOffers = listOf(offer().copy(priceMicros = 5_980_000))
                2 -> store.currentOffers = listOf(offer().copy(currency = "EUR"))
                3 -> store.currentOffers = listOf(offer(), offer())
                4 -> api.catalogValue = MinuteCatalog(true, listOf(product.copy(environment = "live")))
            }
            val controller = controller(api, store); controller.refresh()
            assertFalse(controller.state.value.available); controller.buy(product.sku) { error("unapproved purchase") }
            assertTrue(api.keys.isEmpty()); controller.close()
        }
    }
    @Test fun changedCatalogAfterDisplayRequiresAnotherTapAndNeverCreatesOldQuote() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        controller.refresh(); val changed = product.copy(totalMinor = 699)
        api.catalogValue = MinuteCatalog(true, listOf(changed)); store.currentOffers = listOf(offer(changed)); api.orderValue = order(changed)
        controller.buy(product.sku) { error("must ask for new tap") }
        assertTrue(api.keys.isEmpty()); assertEquals(MinutePurchaseNotice.PRICE_CHANGED, controller.state.value.notice)
        controller.buy(product.sku) { assertEquals(changed, it.product); MinuteStoreOutcome.OPENED }
        assertEquals(1, api.keys.size); assertTrue(controller.state.value.purchaseInProgress)
    }
    @Test fun orderQuoteMismatchDoesNotLaunchBillingAndRetryReusesIdempotencyKey() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        controller.refresh(); api.orderValue = order(product.copy(totalMinor = 999))
        controller.buy(product.sku) { error("wrong quote") }
        assertEquals(MinutePurchaseNotice.PRICE_CHANGED, controller.state.value.notice)
        api.orderValue = order(); var failed = true
        api.onCreate = { if (failed) throw MinuteCommerceFailure.Unavailable }
        controller.buy(product.sku) { error("network failed") }; failed = false
        controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
        assertEquals(3, api.keys.size); assertNotEquals(api.keys[0], api.keys[1]); assertEquals(api.keys[1], api.keys[2])
    }
    @Test fun completedLocalPurchaseWaitsForServerAndNeverAddsClientMinutes() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        controller.refresh(); runCurrent()
        controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
        val gate = CompletableDeferred<Unit>(); api.onRecover = { gate.await() }
        store.events.emit(MinuteStoreEvent(MinuteStoreOutcome.PURCHASES_UPDATED,
            listOf(MinuteStorePurchase("synthetic-token", MinuteStorePurchaseState.PURCHASED))))
        runCurrent(); assertEquals(MinutePurchaseNotice.VERIFYING, controller.state.value.notice)
        assertEquals(600_000, controller.state.value.balance!!.availableMilliseconds)
        api.balanceValue = balance(2_400_000); gate.complete(Unit); runCurrent()
        assertEquals(MinutePurchaseNotice.ADDED, controller.state.value.notice)
        assertEquals(2_400_000, controller.state.value.balance!!.availableMilliseconds)
        store.events.emit(MinuteStoreEvent(MinuteStoreOutcome.PURCHASES_UPDATED,
            listOf(MinuteStorePurchase("synthetic-token", MinuteStorePurchaseState.PURCHASED))))
        runCurrent(); assertEquals(2_400_000, controller.state.value.balance!!.availableMilliseconds)
    }
    @Test fun pendingPurchaseCanRecoverAfterReinstallEvenWhenSalesArePaused() = runTest {
        val api = API(); val store = Store(); api.catalogValue = MinuteCatalog(false, emptyList())
        api.statusValue = status("pending"); store.owned = listOf(MinuteStorePurchase("pending-token", MinuteStorePurchaseState.PENDING))
        val controller = controller(api, store); controller.onForeground()
        assertFalse(controller.state.value.available); assertTrue(controller.state.value.purchaseInProgress)
        assertEquals(listOf("pending-token"), api.tokens); assertTrue(api.keys.isEmpty())
        assertEquals(MinutePurchaseNotice.PENDING, controller.state.value.notice)
        api.statusValue = status(); api.balanceValue = balance(2_400_000); controller.onForeground()
        assertFalse(controller.state.value.purchaseInProgress); assertEquals(MinutePurchaseNotice.ADDED, controller.state.value.notice)
        assertEquals(2_400_000, controller.state.value.balance!!.availableMilliseconds)
    }
    @Test fun oneUnrelatedReceiptCannotPreventRecoveringRemainingPurchases() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        store.owned = listOf("other-account-token", "owned-token", "owned-token").map { MinuteStorePurchase(it, MinuteStorePurchaseState.PURCHASED) }
        api.onRecover = { if (it.startsWith("other")) throw MinuteCommerceFailure.Http(502, "purchase_verification_failed") }
        controller.onForeground()
        assertEquals(listOf("other-account-token", "owned-token"), api.tokens)
        assertEquals(MinutePurchaseNotice.VERIFICATION_FAILED, controller.state.value.notice)
        assertNotNull(controller.state.value.balance)
    }
    @Test fun cancelAndRefundShowServerStatesWithoutChangingWalletLocally() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        controller.refresh(); runCurrent(); controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
        store.events.emit(MinuteStoreEvent(MinuteStoreOutcome.CANCELED)); runCurrent()
        assertFalse(controller.state.value.purchaseInProgress); assertEquals(MinutePurchaseNotice.CANCELED, controller.state.value.notice)
        api.statusValue = status(reversed = 900_000); api.balanceValue = balance(0)
        controller.refreshOrder(id); assertEquals(MinutePurchaseNotice.REVERSED, controller.state.value.notice)
        assertEquals(0, controller.state.value.balance!!.availableMilliseconds)
    }
    @Test fun accountChangeDuringOrderPreventsLaunchAndClearsOldBalance() = runTest {
        val api = API(); val store = Store(); var current: AccountSession? = member
        val controller = controller(api, store, current = { current }); controller.refresh()
        val gate = CompletableDeferred<Unit>(); api.onCreate = { gate.await() }
        val purchase = launch { controller.buy(product.sku) { error("old account must not pay") } }; runCurrent()
        current = member.copy(accountID = "87654321-1234-1234-1234-123456789012"); gate.complete(Unit); purchase.join()
        assertNull(controller.state.value.balance); assertEquals(MinutePurchaseNotice.SIGN_IN_REQUIRED, controller.state.value.notice)
    }
    @Test fun closeDuringFetchCannotRestoreReadyStateAndOverlapIsIgnored() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        val gate = CompletableDeferred<Unit>(); api.onCatalog = { gate.await() }
        val refresh = launch { controller.refresh() }; runCurrent(); controller.refresh()
        assertEquals(1, api.calls); controller.close(); gate.complete(Unit); refresh.join()
        assertEquals(MinutePurchaseState(), controller.state.value); assertTrue(store.closed)
    }
    @Test fun signOutDuringVerificationNeverDisplaysOtherAccountsWallet() = runTest {
        val api = API(); val store = Store(); var current: AccountSession? = member
        val controller = controller(api, store, current = { current }); controller.refresh(); runCurrent()
        val gate = CompletableDeferred<Unit>(); api.onRecover = { gate.await() }
        store.events.emit(MinuteStoreEvent(MinuteStoreOutcome.PURCHASES_UPDATED,
            listOf(MinuteStorePurchase("token", MinuteStorePurchaseState.PURCHASED)))); runCurrent()
        current = null; gate.complete(Unit); runCurrent()
        assertNull(controller.state.value.balance); assertEquals(MinutePurchaseNotice.SIGN_IN_REQUIRED, controller.state.value.notice)
    }
    @Test fun exactCurrencyMathAndReceiptRedactionAreEnforced() {
        assertEquals(5_990_000L, product.expectedMicros())
        assertEquals(599_000_000L, product.copy(currency = "jpy").expectedMicros())
        assertEquals(599_000L, product.copy(currency = "kwd").expectedMicros())
        assertNull(product.copy(currency = "xxx").expectedMicros())
        assertFalse(MinuteStorePurchase("do-not-show", MinuteStorePurchaseState.PURCHASED).toString().contains("do-not-show"))
        assertFalse(order().toString().contains("b".repeat(64)))
        for (token in listOf("", "a b", "a\n", "é", "a".repeat(4097))) {
            try { MinuteStorePurchase(token, MinuteStorePurchaseState.PURCHASED); fail("bad token") } catch (_: IllegalArgumentException) { }
        }
        for (value in listOf(-1L, 0L, 100_000_001L)) {
            try { product.copy(totalMinor = value); fail("bad amount") } catch (_: IllegalArgumentException) { }
        }
        try { status("pending").copy(grantedMilliseconds = 60_000, fulfillmentRecorded = true); fail("pending grant") } catch (_: IllegalArgumentException) { }
    }
}
