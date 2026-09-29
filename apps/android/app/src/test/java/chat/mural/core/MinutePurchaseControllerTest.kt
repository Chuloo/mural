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
        PlayOrderBinding(id, "b".repeat(64), "c".repeat(64)), product.aiValue)
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
        val regions = mutableListOf<String?>()
        val selections = mutableListOf<PlayPriceSnapshot?>()
        var onCreate: suspend () -> Unit = {}
        var onCatalog: suspend () -> Unit = {}
        var onRecover: suspend (String) -> Unit = {}
        override suspend fun catalog(regionCode: String?): MinuteCatalog { calls++; regions += regionCode; onCatalog(); return catalogValue }
        override suspend fun create(session: AccountSession, sku: String, idempotencyKey: String, selection: PlayPriceSnapshot?): MinuteOrder {
            calls++; keys += idempotencyKey; selections += selection; onCreate(); return orderValue
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
        var requestedProducts = emptyList<String>()
        var owned = emptyList<MinuteStorePurchase>()
        var region = "US"
        var regionFailure = false
        var regionReads = 0
        override suspend fun connect() { calls++ }
        override suspend fun billingRegion(): String {
            calls++; regionReads++
            if (regionFailure) throw MinuteCommerceFailure.Unavailable
            return region
        }
        override suspend fun offers(productIDs: List<String>): List<MinuteStoreOffer> { calls++; requestedProducts = productIDs; return currentOffers }
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
    @Test fun localizedCatalogRowsQueryOnePlayProductAndShowOnlyMatchingStorePrice() = runTest {
        val api = API(); val store = Store()
        val norwegian = product.copy(sku = "test-30-nor", currency = "nok", totalMinor = 8900)
        api.catalogValue = MinuteCatalog(true, listOf(product, norwegian))
        api.orderValue = order(norwegian)
        store.currentOffers = listOf(offer(norwegian).copy(formattedPrice = "kr 89,00"))
        val controller = controller(api, store)
        controller.refresh()
        assertEquals(listOf(product.providerProduct), store.requestedProducts)
        assertEquals(listOf(MinutePack(norwegian.sku, norwegian.minutes, "kr 89,00")), controller.state.value.packs)
        controller.buy(norwegian.sku) { assertEquals(norwegian, it.product); MinuteStoreOutcome.OPENED }
        assertEquals(1, api.keys.size)
    }
    @Test fun ambiguousLocalizedCatalogRowsCannotSilentlyChooseOne() = runTest {
        val api = API(); val store = Store()
        api.catalogValue = MinuteCatalog(true, listOf(product, product.copy(sku = "same-price-other-sku")))
        val controller = controller(api, store)
        controller.refresh()
        assertFalse(controller.state.value.available)
        assertTrue(controller.state.value.packs.isEmpty())
        controller.buy(product.sku) { error("ambiguous offer") }
        assertTrue(api.keys.isEmpty())
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
    @Test fun playCountryIsReadAgainOnRefreshAndCheckoutWithoutUsingDeviceLocale() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            store.region = "GB"; controller.refresh()
            store.region = "RS"; controller.refresh()
            store.region = "NO"; controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
            assertEquals(listOf("GB", "RS", "NO"), api.regions)
            assertEquals(3, store.regionReads)
        } finally { java.util.Locale.setDefault(previousLocale) }
    }
    private fun regionalProduct(region: String, schedule: String = "play-regional-v1"): MinuteProduct {
        val quote = AIValueQuote("usd", 2, 200, 1500, 30, 0, 0, 230, 1, "synthetic-usd", "synthetic-estimate",
            play = PlayPriceSnapshot("eur", 2, 271, schedule, region, "fixed-usd-allocation"))
        return MinuteProduct("regional-small", "small", 20, "eur", 271, "test", AIValueEntitlement("2000000000", 1_200_000, quote))
    }
    @Test fun equalEuroPricesCannotHideAChangedCountryOrScheduleBeforeCheckout() = runTest {
        for (changed in listOf(regionalProduct("FR"), regionalProduct("DE", "play-regional-v2"))) {
            val original = regionalProduct("DE")
            val api = API().apply { catalogValue = MinuteCatalog(true, listOf(original)); orderValue = order(original) }
            val store = Store().apply { region = "DE"; currentOffers = listOf(offer(original)) }
            val controller = controller(api, store); controller.refresh()
            store.region = changed.aiValue!!.quote.play!!.regionCode!!
            api.catalogValue = MinuteCatalog(true, listOf(changed)); api.orderValue = order(changed)
            controller.buy(original.sku) { error("changed country or quote needs a new tap") }
            assertTrue(api.keys.isEmpty()); assertEquals(MinutePurchaseNotice.PRICE_CHANGED, controller.state.value.notice)
            controller.buy(changed.sku) { MinuteStoreOutcome.OPENED }
            assertEquals(listOf(changed.aiValue!!.quote.play), api.selections)
        }
    }
    @Test fun mismatchedRegionalCatalogCannotBeBoughtEvenWithMatchingCurrencyAndPrice() = runTest {
        val product = regionalProduct("FR")
        val api = API().apply { catalogValue = MinuteCatalog(true, listOf(product)) }
        val store = Store().apply { region = "DE"; currentOffers = listOf(offer(product)) }
        val controller = controller(api, store); controller.refresh()
        assertFalse(controller.state.value.available)
        controller.buy(product.sku) { error("server returned another country's quote") }
        assertTrue(api.keys.isEmpty())
    }
    @Test fun regionUnavailableIsDistinctFromTemporaryCountryLookupFailureAndDoesNotBlockRecovery() = runTest {
        val api = API().apply { catalogValue = MinuteCatalog(false, emptyList(), regionUnavailable = true) }
        val store = Store().apply { owned = listOf(MinuteStorePurchase("paid-token", MinuteStorePurchaseState.PURCHASED)) }
        val controller = controller(api, store); controller.refresh()
        assertTrue(controller.state.value.regionUnavailable)
        assertEquals(listOf("paid-token"), api.tokens)
        assertEquals(MinutePurchaseNotice.ADDED, controller.state.value.notice)
        store.regionFailure = true; controller.refresh()
        assertFalse(controller.state.value.regionUnavailable)
        assertFalse(controller.state.value.available)
        assertEquals(listOf("paid-token", "paid-token"), api.tokens)
        assertNotNull(controller.state.value.balance)
        assertEquals(MinutePurchaseNotice.UNAVAILABLE, controller.state.value.notice)
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
    @Test fun serverQuoteConflictClearsOnlyTheUnlaunchedAttemptAndRequiresAnotherTap() = runTest {
        for (code in listOf("purchase_quote_changed", "idempotency_conflict")) {
            val api = API(); val store = Store(); val controller = controller(api, store)
            controller.refresh()
            api.onCreate = { throw MinuteCommerceFailure.Http(409, code) }
            controller.buy(product.sku) { error("rejected quote must never open Play") }
            assertEquals(MinutePurchaseNotice.PRICE_CHANGED, controller.state.value.notice)
            api.onCreate = {}
            controller.buy(product.sku) { MinuteStoreOutcome.OPENED }
            assertEquals(2, api.keys.size); assertNotEquals(api.keys[0], api.keys[1])
        }
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
    @Test fun unfinishedPlayCartPurchaseIsRecoveredBeforeAnotherOrder() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        controller.refresh()
        store.owned = listOf(MinuteStorePurchase("owned-ten-pack-token", MinuteStorePurchaseState.PURCHASED, 10))
        api.balanceValue = balance(18_000_000)
        controller.buy(product.sku) { error("an owned purchase must be recovered before another checkout") }
        assertTrue(api.keys.isEmpty())
        assertEquals(listOf("owned-ten-pack-token"), api.tokens)
        assertEquals(MinutePurchaseNotice.ADDED, controller.state.value.notice)
        assertEquals(18_000_000, controller.state.value.balance!!.availableMilliseconds)
    }
    @Test fun pendingPlayCartQuantityNeverCreditsOrClaimsPacksAreReady() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        api.statusValue = status("pending")
        store.owned = listOf(MinuteStorePurchase("pending-ten-pack-token", MinuteStorePurchaseState.PENDING, 10))
        controller.onForeground()
        assertEquals(MinutePurchaseNotice.PENDING, controller.state.value.notice)
        assertEquals(600_000, controller.state.value.balance!!.availableMilliseconds)
        assertTrue(api.keys.isEmpty())
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
    @Test fun unrelatedAccountReceiptDoesNotBlockANewCheckout() = runTest {
        val api = API(); val store = Store(); val controller = controller(api, store)
        controller.refresh()
        store.owned = listOf(MinuteStorePurchase("other-account-token", MinuteStorePurchaseState.PURCHASED))
        api.onRecover = { throw MinuteCommerceFailure.Http(502, "purchase_verification_failed") }
        var launched = false
        controller.buy(product.sku) { launched = true; MinuteStoreOutcome.OPENED }
        assertEquals(listOf("other-account-token"), api.tokens)
        assertTrue(launched)
        assertEquals(1, api.keys.size)
        assertTrue(controller.state.value.purchaseInProgress)
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
        assertEquals(1, api.regions.size); controller.close(); gate.complete(Unit); refresh.join()
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
        assertEquals(10, MinuteStorePurchase("token", MinuteStorePurchaseState.PURCHASED, 10).quantity)
        try { MinuteStorePurchase("token", MinuteStorePurchaseState.PURCHASED, 0); fail("invalid quantity") } catch (_: IllegalArgumentException) { }
        assertFalse(order().toString().contains("b".repeat(64)))
        for (token in listOf("", "a b", "a\n", "é", "a".repeat(4097))) {
            try { MinuteStorePurchase(token, MinuteStorePurchaseState.PURCHASED); fail("bad token") } catch (_: IllegalArgumentException) { }
        }
        for (value in listOf(-1L, 0L, 100_000_001L)) {
            try { product.copy(totalMinor = value); fail("bad amount") } catch (_: IllegalArgumentException) { }
        }
        try { status("pending").copy(grantedMilliseconds = 60_000, fulfillmentRecorded = true); fail("pending grant") } catch (_: IllegalArgumentException) { }
    }
    @Test fun regionalPacksMatchLocalStoreUnitsWhileKeepingTheirUsdWalletAllocation() {
        for ((currency, exponent, minor) in listOf(Triple("gbp", 2, 599L), Triple("jpy", 0, 900L), Triple("kwd", 3, 2100L))) {
            val play = PlayPriceSnapshot(currency, exponent, minor, "play-regional-v1", "GB", "fixed-usd-allocation")
            val quote = AIValueQuote("usd", 2, 369, 1500, 56, 0, 0, 425, 1, "usd-v1", "estimate-v1", play = play)
            val value = AIValueEntitlement("3690000000", 2_214_000, quote)
            val local = MinuteProduct("local-small", "small", 36, currency, minor, "test", value)
            assertEquals(minor * when (exponent) { 0 -> 1_000_000; 2 -> 10_000; else -> 1000 }, local.expectedMicros())
            assertTrue(offer(local).matches(local)); assertTrue(order(local).matches(local))
            assertEquals("3690000000", local.aiValue!!.aiValueNanoUSD)
            try { local.copy(totalMinor = minor + 1); fail("local product price must match Play snapshot") } catch (_: IllegalArgumentException) { }
            try { order(local).copy(currency = "usd"); fail("local order currency must match Play snapshot") } catch (_: IllegalArgumentException) { }
            try { quote.copy(currency = "eur"); fail("fixed allocation must keep USD accounting") } catch (_: IllegalArgumentException) { }
        }
    }
}
