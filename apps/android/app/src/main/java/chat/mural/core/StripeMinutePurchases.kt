package chat.mural.core

import java.math.BigDecimal
import java.net.URI
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable

/** Distribution is selected when building, never inferred from an installer or a payment return link. */
enum class PurchaseChannel(val provider: String) {
    PLAY("play"), STRIPE("stripe");
    companion object { fun parse(value: String) = entries.singleOrNull { it.provider == value } }
}

class StripeCheckoutURL private constructor(val value: String, val environment: String) {
    companion object {
        fun checked(value: String): StripeCheckoutURL {
            val uri = try { URI(value) } catch (_: Exception) { throw MinuteCommerceFailure.InvalidResponse }
            if (value.length !in 1..8192 || value.any { it.isWhitespace() || it.isISOControl() } ||
                uri.scheme != "https" || uri.host != "checkout.stripe.com" || uri.port != -1 || uri.rawUserInfo != null ||
                !Regex("/c/pay/cs_(test|live)_[A-Za-z0-9]+").matches(uri.rawPath ?: "") || uri.rawQuery != null)
                throw MinuteCommerceFailure.InvalidResponse
            return StripeCheckoutURL(value, if (uri.rawPath.startsWith("/c/pay/cs_live_")) "live" else "test")
        }
    }
    override fun toString() = "StripeCheckoutURL(redacted)"
}
class StripeMinuteOrder(val orderID: String, val currency: String, val totalMinor: Long,
    val checkout: StripeCheckoutURL, val aiValue: AIValueEntitlement) {
    init { require(minuteUUID.matches(orderID) && currency == aiValue.quote.currency && totalMinor == aiValue.quote.totalMinor) }
    fun matches(product: MinuteProduct) = currency == product.currency && totalMinor == product.totalMinor && aiValue == product.aiValue
    override fun toString() = "StripeMinuteOrder(redacted)"
}
interface StripeCommerceService {
    suspend fun catalog(): MinuteCatalog
    suspend fun createStripe(session: AccountSession, sku: String, idempotencyKey: String): StripeMinuteOrder
    suspend fun findStripeOrder(session: AccountSession, idempotencyKey: String): String?
    suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus
    suspend fun balance(session: AccountSession): MinuteBalance
}

/** Only recovery identifiers are persisted; never checkout URLs, account bearers or card details. */
@Serializable
data class StripePurchaseAttempt(val accountID: String, val sku: String, val key: String, val orderID: String? = null) {
    init { require(minuteUUID.matches(accountID) && sku.length <= 128 && minuteIdentifier.matches(sku) && minuteUUID.matches(key) &&
        (orderID == null || minuteUUID.matches(orderID))) }
    override fun toString() = "StripePurchaseAttempt(redacted)"
}
interface StripePurchaseAttemptStorage {
    suspend fun read(accountID: String): StripePurchaseAttempt?
    suspend fun save(attempt: StripePurchaseAttempt)
    suspend fun remove(accountID: String)
}

/** Stripe grants arrive only from the authenticated server status, including after process death. */
class StripeMinutePurchaseController(
    private val api: StripeCommerceService,
    private val storage: StripePurchaseAttemptStorage,
    private val readMember: suspend () -> AccountSession?,
    private val enabled: Boolean = false,
    private val expectedEnvironment: String = "test",
    private val now: () -> Long = System::currentTimeMillis,
    private val onBalanceChanged: suspend () -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    init { require(expectedEnvironment in listOf("test", "live")) }
    private fun emptyState() = MinutePurchaseState(channel = PurchaseChannel.STRIPE)
    private val mutable = MutableStateFlow(emptyState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var stopped = false
    private var identity: String? = null
    private var products: Map<String, MinuteProduct> = emptyMap()

    suspend fun onForeground() = operation {
        val member = member(); select(member)
        loadCatalog()
        if (member != null) {
            var pending = storage.read(member.accountID)
            if (pending != null && pending.orderID == null) {
                pending = recoverOrder(member, pending)
                if (pending.orderID == null) {
                    // No owned order exists yet; reuse the durable key for the interrupted create.
                    val recovered = createOrder(member, pending)
                    current(member)
                    pending = pending.copy(orderID = recovered.orderID)
                    storage.save(pending)
                }
            }
            if (pending != null) {
                repeat(6) { index ->
                    if (index > 0) pause(1500)
                    current(member)
                    val status = api.status(member, pending!!.orderID!!)
                    current(member)
                    if (status.orderID != pending!!.orderID) throw MinuteCommerceFailure.InvalidResponse
                    applyStatus(status)
                    if (status.state !in listOf("created", "pending")) {
                        storage.remove(member.accountID)
                        updateBalance(member)
                        return@operation
                    }
                }
            }
            updateBalance(member)
        }
    }
    suspend fun refresh() = onForeground()
    suspend fun buy(sku: String, launch: (StripeCheckoutURL) -> MinuteStoreOutcome) = operation {
        val member = member() ?: throw MinuteCommerceFailure.SignInRequired
        select(member)
        val shown = products[sku] ?: throw MinuteCommerceFailure.Unavailable
        loadCatalog()
        val product = products[sku] ?: throw MinuteCommerceFailure.PriceChanged
        if (shown != product) throw MinuteCommerceFailure.PriceChanged
        current(member)
        var existing = storage.read(member.accountID)
        if (existing != null) {
            existing = recoverOrder(member, existing)
            if (existing.orderID != null) {
                val status = api.status(member, existing.orderID!!)
                current(member)
                if (status.orderID != existing.orderID) throw MinuteCommerceFailure.InvalidResponse
                applyStatus(status)
                if (status.state !in listOf("created", "pending")) {
                    storage.remove(member.accountID)
                    updateBalance(member)
                    return@operation
                }
            }
        }
        if (existing != null && existing.sku != sku) throw MinuteCommerceFailure.Unavailable
        val attempt = existing ?: StripePurchaseAttempt(member.accountID, sku, UUID.randomUUID().toString()).also {
            // Durable before contacting the server: retries cannot create duplicate orders.
            storage.save(it)
        }
        val order = createOrder(member, attempt)
        if (attempt.orderID != null && attempt.orderID != order.orderID) throw MinuteCommerceFailure.InvalidResponse
        storage.save(attempt.copy(orderID = order.orderID))
        current(member)
        if (!order.matches(product)) throw MinuteCommerceFailure.PriceChanged
        if (expectedEnvironment != order.checkout.environment) throw MinuteCommerceFailure.InvalidResponse
        when (launch(order.checkout)) {
            MinuteStoreOutcome.OPENED -> mutable.value = mutable.value.copy(notice = MinutePurchaseNotice.PENDING)
            MinuteStoreOutcome.CANCELED -> mutable.value = mutable.value.copy(notice = MinutePurchaseNotice.CANCELED)
            else -> throw MinuteCommerceFailure.Unavailable
        }
        // Leaving or returning from the browser is never evidence of a successful purchase.
    }
    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }
    fun close() { stopped = true; products = emptyMap(); mutable.value = emptyState() }
    private suspend fun createOrder(member: AccountSession, attempt: StripePurchaseAttempt): StripeMinuteOrder = try {
        api.createStripe(member, attempt.sku, attempt.key)
    } catch (error: MinuteCommerceFailure.Http) {
        // These catalog errors occur only after the server checks for an existing order,
        // and before inserting one. Other errors can follow a committed payable order.
        if (attempt.orderID == null && error.status == 503 && error.code in setOf(
                "ai_value_product_unavailable", "minute_product_unavailable")) {
            current(member)
            storage.remove(member.accountID)
        }
        throw error
    }
    private suspend fun recoverOrder(member: AccountSession, attempt: StripePurchaseAttempt): StripePurchaseAttempt {
        if (attempt.orderID != null) return attempt
        val orderID = api.findStripeOrder(member, attempt.key)
        current(member)
        if (orderID == null) return attempt
        if (!minuteUUID.matches(orderID)) throw MinuteCommerceFailure.InvalidResponse
        return attempt.copy(orderID = orderID).also { storage.save(it) }
    }
    private suspend fun loadCatalog() {
        val catalog = api.catalog(); open()
        if (catalog.products.any { it.environment != expectedEnvironment || it.aiValue == null }) throw MinuteCommerceFailure.InvalidResponse
        products = catalog.products.associateBy { it.sku }
        mutable.value = mutable.value.copy(available = catalog.available, packs = catalog.products.map { product ->
            val quote = product.aiValue!!.quote
            val price = NumberFormat.getCurrencyInstance().apply { currency = Currency.getInstance(quote.currency.uppercase(Locale.ROOT)) }
                .format(BigDecimal.valueOf(product.totalMinor, quote.currencyExponent))
            MinutePack(product.sku, product.minutes, price, product.aiValue)
        })
    }
    private fun applyStatus(status: MinutePurchaseStatus) {
        if (status.aiValue == null) throw MinuteCommerceFailure.InvalidResponse
        mutable.value = mutable.value.copy(notice = when {
            status.state in listOf("created", "pending") -> MinutePurchaseNotice.PENDING
            status.state == "voided" || status.aiValue.reversed -> MinutePurchaseNotice.REVERSED
            status.fulfillmentRecorded -> MinutePurchaseNotice.ADDED
            else -> MinutePurchaseNotice.VERIFICATION_FAILED
        })
    }
    private suspend fun updateBalance(member: AccountSession) {
        val balance = api.balance(member); current(member)
        mutable.value = mutable.value.copy(balance = balance); onBalanceChanged()
    }
    private suspend fun member() = readMember()?.takeIf { it.isValid(now()) }
    private fun select(member: AccountSession?) {
        if (identity != member?.accountID) { identity = member?.accountID; mutable.value = emptyState().copy(busy = mutable.value.busy); products = emptyMap() }
    }
    private suspend fun current(expected: AccountSession) {
        open(); val actual = member()
        if (actual?.accountID != expected.accountID) { select(actual); throw MinuteCommerceFailure.SignInRequired }
    }
    private fun open() { if (stopped) throw CancellationException("Stripe purchases closed") }
    private suspend fun operation(block: suspend () -> Unit) {
        if (!enabled || stopped || !mutex.tryLock()) return
        mutable.value = mutable.value.copy(busy = true, notice = null)
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (!stopped) mutable.value = mutable.value.copy(notice = when (error) {
                MinuteCommerceFailure.SignInRequired -> MinutePurchaseNotice.SIGN_IN_REQUIRED
                MinuteCommerceFailure.PriceChanged -> MinutePurchaseNotice.PRICE_CHANGED
                is MinuteCommerceFailure.Http -> if (error.status == 401) MinutePurchaseNotice.SIGN_IN_REQUIRED else MinutePurchaseNotice.UNAVAILABLE
                else -> MinutePurchaseNotice.UNAVAILABLE
            })
        } finally { mutable.value = if (stopped) emptyState() else mutable.value.copy(busy = false); mutex.unlock() }
    }
}
