package chat.mural.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MinutePurchaseNotice { UNAVAILABLE, SIGN_IN_REQUIRED, PRICE_CHANGED, CANCELED, PENDING, VERIFYING, ADDED, REVERSED, VERIFICATION_FAILED }
data class MinutePack(val sku: String, val minutes: Int, val formattedPrice: String, val aiValue: AIValueEntitlement? = null)
data class MinutePurchaseState(val busy: Boolean = false, val available: Boolean = false, val packs: List<MinutePack> = emptyList(),
    val balance: MinuteBalance? = null, val purchaseInProgress: Boolean = false, val notice: MinutePurchaseNotice? = null)

/** UI state never contains an account bearer, receipt, provider binding or purchase token. */
class MinutePurchaseController(
    scope: CoroutineScope,
    private val api: MinuteCommerceService,
    private val store: MinuteStoreGateway,
    private val readMember: suspend () -> AccountSession?,
    private val enabled: Boolean = false,
    private val expectedEnvironment: String = "test",
    private val now: () -> Long = System::currentTimeMillis,
    private val onBalanceChanged: suspend () -> Unit = {},
) {
    init { require(expectedEnvironment in listOf("test", "live")) }
    private val mutable = MutableStateFlow(MinutePurchaseState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var products: Map<String, Pair<MinuteProduct, MinuteStoreOffer>> = emptyMap()
    @Volatile private var stopped = false
    private var identity: String? = null
    private val attempts = mutableMapOf<Pair<String, String>, String>()
    private val observer: Job = scope.launch { store.events.collect { event ->
        if (enabled && !stopped) mutex.withLock { if (!stopped) processEvent(event) }
    } }

    /** Call on foreground and after account changes; it also recovers payments missed while closed. */
    suspend fun onForeground() = operation {
        val member = memberOrNull(); updateIdentity(member)
        loadCatalog()
        if (member != null) {
            store.connect()
            processPurchases(store.purchases(), member)
            updateBalance(member)
        }
    }
    suspend fun refresh() = onForeground()
    suspend fun buy(sku: String, launch: (PreparedMinutePurchase) -> MinuteStoreOutcome) = operation {
        val member = memberOrNull() ?: throw MinuteCommerceFailure.SignInRequired
        updateIdentity(member)
        if (mutable.value.purchaseInProgress) return@operation
        val shown = products[sku]?.first ?: throw MinuteCommerceFailure.Unavailable
        // Re-fetch both sources before creating a payable order. A changed quote needs a new tap.
        loadCatalog()
        val (product, offer) = products[sku] ?: throw MinuteCommerceFailure.PriceChanged
        if (shown != product) throw MinuteCommerceFailure.PriceChanged
        requireCurrent(member)
        val attempt = member.accountID to sku
        val key = attempts.getOrPut(attempt) { UUID.randomUUID().toString() }
        val order = api.create(member, sku, key)
        if (!order.matches(product)) {
            // This order was never passed to Play. A new tap may request the current quote.
            attempts.remove(attempt)
            throw MinuteCommerceFailure.PriceChanged
        }
        requireCurrent(member)
        when (launch(PreparedMinutePurchase(product, order, offer))) {
            MinuteStoreOutcome.OPENED -> {
                attempts.remove(attempt)
                mutable.value = mutable.value.copy(purchaseInProgress = true, notice = null)
            }
            MinuteStoreOutcome.CANCELED -> mutable.value = mutable.value.copy(notice = MinutePurchaseNotice.CANCELED)
            MinuteStoreOutcome.ALREADY_OWNED -> processPurchases(store.purchases(), member)
            else -> throw MinuteCommerceFailure.Unavailable
        }
    }
    suspend fun refreshOrder(orderID: String) = operation {
        val member = memberOrNull() ?: throw MinuteCommerceFailure.SignInRequired
        updateIdentity(member); val result = api.status(member, orderID); requireCurrent(member)
        applyStatus(result); updateBalance(member)
    }
    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }
    fun close() { stopped = true; observer.cancel(); store.close(); products = emptyMap(); attempts.clear(); mutable.value = MinutePurchaseState() }

    private suspend fun loadCatalog() {
        products = emptyMap(); mutable.value = mutable.value.copy(available = false, packs = emptyList())
        val catalog = api.catalog(); requireOpen()
        if (catalog.products.any { it.environment != expectedEnvironment }) throw MinuteCommerceFailure.InvalidResponse
        if (!catalog.available) return
        store.connect()
        val offers = store.offers(catalog.products.map { it.providerProduct }.distinct()); requireOpen()
        products = catalog.products.mapNotNull { product ->
            val matches = offers.filter { it.matches(product) }
            // Ambiguous eligible offers cannot silently choose different purchase terms.
            matches.singleOrNull()?.let { product.sku to (product to it) }
        }.toMap()
        mutable.value = mutable.value.copy(available = products.isNotEmpty(),
            packs = products.values.map { (product, offer) -> MinutePack(product.sku, product.minutes, offer.formattedPrice, product.aiValue) })
    }
    private suspend fun processEvent(event: MinuteStoreEvent) {
        try {
            when (event.outcome) {
                MinuteStoreOutcome.CANCELED -> mutable.value = mutable.value.copy(purchaseInProgress = false, notice = MinutePurchaseNotice.CANCELED)
                MinuteStoreOutcome.PURCHASES_UPDATED -> {
                    val member = memberOrNull() ?: throw MinuteCommerceFailure.SignInRequired
                    updateIdentity(member); processPurchases(event.purchases, member); updateBalance(member)
                }
                MinuteStoreOutcome.ALREADY_OWNED -> {
                    val member = memberOrNull() ?: throw MinuteCommerceFailure.SignInRequired
                    updateIdentity(member); store.connect(); processPurchases(store.purchases(), member); updateBalance(member)
                }
                else -> mutable.value = mutable.value.copy(purchaseInProgress = false, notice = MinutePurchaseNotice.UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure(error) }
    }
    private suspend fun processPurchases(purchases: List<MinuteStorePurchase>, member: AccountSession) {
        if (purchases.size > 100) throw MinuteCommerceFailure.InvalidResponse
        var pending = false
        var verificationFailed = false
        for (purchase in purchases.distinctBy { it.token }) {
            requireCurrent(member)
            mutable.value = mutable.value.copy(notice = MinutePurchaseNotice.VERIFYING)
            // Pending tokens are uploaded too so the server can observe completion without this app.
            try {
                val result = api.recover(member, purchase.token)
                requireCurrent(member); applyStatus(result)
                pending = pending || result.state in listOf("created", "pending")
            } catch (error: MinuteCommerceFailure.Http) {
                // An unrelated account's old receipt must not block this account's valid purchases.
                if (error.code != "purchase_verification_failed" || error.status == 401) throw error
                requireCurrent(member); verificationFailed = true
            }
        }
        mutable.value = mutable.value.copy(purchaseInProgress = pending,
            notice = if (pending) MinutePurchaseNotice.PENDING else if (verificationFailed) MinutePurchaseNotice.VERIFICATION_FAILED else mutable.value.notice)
    }
    private fun applyStatus(result: MinutePurchaseStatus) {
        mutable.value = mutable.value.copy(purchaseInProgress = result.state in listOf("created", "pending"), notice = when {
            result.state in listOf("created", "pending") -> MinutePurchaseNotice.PENDING
            result.state == "voided" || result.reversedMilliseconds > 0 || result.reversalOutstandingMilliseconds > 0 || result.aiValue?.reversed == true -> MinutePurchaseNotice.REVERSED
            result.fulfillmentRecorded -> MinutePurchaseNotice.ADDED
            else -> MinutePurchaseNotice.VERIFICATION_FAILED
        })
    }
    private suspend fun updateBalance(member: AccountSession) {
        val balance = api.balance(member); requireCurrent(member)
        mutable.value = mutable.value.copy(balance = balance)
        onBalanceChanged()
    }
    private suspend fun memberOrNull(): AccountSession? = readMember()?.takeIf { it.isValid(now()) }
    private suspend fun requireCurrent(member: AccountSession) {
        requireOpen()
        val current = memberOrNull()
        if (current?.accountID != member.accountID) {
            updateIdentity(current)
            throw MinuteCommerceFailure.SignInRequired
        }
    }
    private fun updateIdentity(member: AccountSession?) {
        if (identity != member?.accountID) {
            identity = member?.accountID; attempts.clear()
            mutable.value = mutable.value.copy(balance = null, purchaseInProgress = false, notice = null)
        }
    }
    private fun requireOpen() { if (stopped) throw CancellationException("Minute purchases closed") }
    private fun failure(error: Exception) {
        if (stopped) return
        val notice = when (error) {
            MinuteCommerceFailure.SignInRequired -> MinutePurchaseNotice.SIGN_IN_REQUIRED
            MinuteCommerceFailure.PriceChanged -> MinutePurchaseNotice.PRICE_CHANGED
            is MinuteCommerceFailure.Http -> if (error.status == 401) MinutePurchaseNotice.SIGN_IN_REQUIRED
                else if (error.code == "purchase_verification_failed") MinutePurchaseNotice.VERIFICATION_FAILED else MinutePurchaseNotice.UNAVAILABLE
            else -> MinutePurchaseNotice.UNAVAILABLE
        }
        mutable.value = mutable.value.copy(notice = notice)
    }
    private suspend fun operation(block: suspend () -> Unit) {
        if (!enabled || stopped || !mutex.tryLock()) return
        mutable.value = mutable.value.copy(busy = true, notice = null)
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure(error) }
        finally { mutable.value = if (stopped) MinutePurchaseState() else mutable.value.copy(busy = false); mutex.unlock() }
    }
}
