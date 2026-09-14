package chat.mural

import androidx.lifecycle.Lifecycle
import chat.mural.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MinutePurchaseLaunchTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val other = "87654321-1234-1234-1234-123456789012"
    private val member = AccountSession(id, "a".repeat(43), 100_000)
    private val product = MinuteProduct("fixture-30", "fixture_30", 30, "usd", 599, "test")
    private val resumed = MinutePurchaseActivityState(Lifecycle.State.RESUMED, finishing = false, destroyed = false)
    private inner class API : MinuteCommerceService {
        val creating = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        override suspend fun catalog() = MinuteCatalog(true, listOf(product))
        override suspend fun create(session: AccountSession, sku: String, idempotencyKey: String): MinuteOrder {
            creating.complete(Unit); finish.await()
            return MinuteOrder(id, 30, "usd", 599, PlayOrderBinding(id, "b".repeat(64), "c".repeat(64)))
        }
        override suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus = error("unused")
        override suspend fun verify(session: AccountSession, orderID: String, token: String): MinutePurchaseStatus = error("unused")
        override suspend fun recover(session: AccountSession, token: String): MinutePurchaseStatus = error("unused")
        override suspend fun balance(session: AccountSession) = MinuteBalance("milliseconds", "connected-conversation-time", 60_000, 0, 60_000)
    }
    private inner class Store : MinuteStoreGateway {
        override val events = MutableSharedFlow<MinuteStoreEvent>()
        override suspend fun connect() { }
        override suspend fun offers(productIDs: List<String>) = listOf(MinuteStoreOffer(id, product.providerProduct, "USD", 5_990_000, "$5.99"))
        override suspend fun purchases() = emptyList<MinuteStorePurchase>()
        override fun close() { }
    }
    private class Host(var state: MinutePurchaseActivityState?)

    private fun preflightCase(change: (MutableStateFlow<AccountState>, MutableStateFlow<Boolean>, MinutePurchaseLaunchGate, Host) -> Unit,
        expectedLaunches: Int) = runTest {
        val account = MutableStateFlow(AccountState(accountID = id))
        val transition = MutableStateFlow(false)
        val guard = MinutePurchaseLaunchGate().apply { bind(account, transition) }
        val permit = guard.begin()!!
        val host = Host(resumed)
        val api = API()
        val controller = MinutePurchaseController(backgroundScope, api, Store(), { member }, enabled = true, now = { 1_000 })
        controller.refresh()
        var launches = 0
        val purchase = launch {
            controller.buy(product.sku) {
                guard.launchIfReady(permit, host.state) { launches++; MinuteStoreOutcome.OPENED }
            }
        }
        api.creating.await()
        assertEquals(0, launches)
        change(account, transition, guard, host)
        api.finish.complete(Unit); purchase.join()
        assertEquals(expectedLaunches, launches)
        assertEquals(expectedLaunches == 1, controller.state.value.purchaseInProgress)
        controller.close()
    }
    @Test fun slowOrderCannotOpenPlayAfterActivityGoesIntoBackground() {
        for (state in listOf(Lifecycle.State.STARTED, Lifecycle.State.CREATED, Lifecycle.State.INITIALIZED)) {
            preflightCase({ _, _, _, host -> host.state = resumed.copy(lifecycle = state) }, 0)
        }
    }
    @Test fun rotationFinishingAndCollectedActivityCancelDelayedLaunch() {
        for (state in listOf(resumed.copy(lifecycle = Lifecycle.State.DESTROYED, destroyed = true), resumed.copy(finishing = true), null)) {
            preflightCase({ _, _, _, host -> host.state = state }, 0)
        }
    }
    @Test fun finalLaunchReadsRetainedTransitionEvenBeforeItsFlowCollectorRuns() =
        preflightCase({ _, transition, _, _ -> transition.value = true }, 0)
    @Test fun accountBusyOwnerChangeAndSignOutDuringPreflightNeverOpenPlay() {
        for (changed in listOf(AccountState(accountID = id, busy = true), AccountState(accountID = other), AccountState())) {
            preflightCase({ account, _, _, _ -> account.value = changed }, 0)
        }
    }
    @Test fun completedAccountTransitionStillInvalidatesTheOriginalTap() = preflightCase({ account, transition, guard, _ ->
        transition.value = true; guard.observe(account.value.copy(busy = true))
        transition.value = false; guard.observe(account.value)
    }, 0)
    @Test fun bindingANewAccountOwnerOrClearingViewModelInvalidatesTheOriginalTap() {
        preflightCase({ account, _, guard, _ -> guard.bind(MutableStateFlow(account.value), MutableStateFlow(false)) }, 0)
        preflightCase({ _, _, guard, _ -> guard.clear() }, 0)
    }
    @Test fun sameResumedActivityAndMemberCanLaunchAfterOrderPreflight() = preflightCase({ _, _, _, _ -> }, 1)
    @Test fun unboundGuestAndAlreadyChangingAccountsCannotBeginPurchaseIntent() {
        val gate = MinutePurchaseLaunchGate(); assertNull(gate.begin())
        val account = MutableStateFlow(AccountState()); val transition = MutableStateFlow(false)
        gate.bind(account, transition); assertNull(gate.begin())
        account.value = AccountState(accountID = id); transition.value = true; assertNull(gate.begin())
        transition.value = false; assertNotNull(gate.begin())
    }
}
