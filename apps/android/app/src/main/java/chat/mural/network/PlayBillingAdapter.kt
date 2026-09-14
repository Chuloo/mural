package chat.mural.network

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import chat.mural.core.*
import com.android.billingclient.api.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One adapter per application; foreground and close are explicit. It never grants or consumes purchases. */
class PlayBillingAdapter(context: Context, private val enabled: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis) : MinuteStoreGateway {
    private val application = context.applicationContext
    private val updates = MutableSharedFlow<MinuteStoreEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = updates.asSharedFlow()
    private val connection = Mutex()
    @Volatile private var closed = false
    private data class CachedOffer(val product: ProductDetails, val token: String?, val view: MinuteStoreOffer, val expiresAt: Long)
    private val details = mutableMapOf<String, CachedOffer>()
    private val billingLazy = lazy {
        BillingClient.newBuilder(application).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection().setListener { result, purchases ->
                if (!closed) {
                    val outcome = outcome(result.responseCode, updated = true)
                    val event = try { MinuteStoreEvent(outcome, if (outcome == MinuteStoreOutcome.PURCHASES_UPDATED) mapPurchases(purchases.orEmpty()) else emptyList()) }
                        catch (_: Exception) { MinuteStoreEvent(MinuteStoreOutcome.FAILED) }
                    updates.tryEmit(event)
                }
            }.build()
    }
    private val billing get() = billingLazy.value

    override suspend fun connect() = withContext(Dispatchers.Main.immediate) {
        if (!enabled || closed || application.packageName != PACKAGE) throw MinuteCommerceFailure.Unavailable
        connection.withLock {
            if (billing.isReady) return@withLock
            timed {
                suspendCancellableCoroutine<Unit> { continuation ->
                    billing.startConnection(object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            if (continuation.isActive) {
                                if (!closed && result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                                else continuation.resumeWithException(MinuteCommerceFailure.Unavailable)
                            }
                        }
                        override fun onBillingServiceDisconnected() {
                            // The SDK reconnects on the next foreground or purchase request.
                            if (continuation.isActive) continuation.resumeWithException(MinuteCommerceFailure.Unavailable)
                        }
                    })
                }
            }
        }
    }
    override suspend fun offers(productIDs: List<String>): List<MinuteStoreOffer> = withContext(Dispatchers.Main.immediate) {
        if (productIDs.isEmpty() || productIDs.size > 100 || productIDs.distinct().size != productIDs.size || productIDs.any { !minuteIdentifier.matches(it) })
            throw MinuteCommerceFailure.InvalidResponse
        connect()
        val params = QueryProductDetailsParams.newBuilder().setProductList(productIDs.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }).build()
        timed {
            suspendCancellableCoroutine { continuation ->
                billing.queryProductDetailsAsync(params) { result, response ->
                    if (continuation.isActive) try {
                        if (closed || result.responseCode != BillingClient.BillingResponseCode.OK || response.productDetailsList.size > 100)
                            throw MinuteCommerceFailure.Unavailable
                        details.clear()
                        val mapped = response.productDetailsList.flatMap { product ->
                            if (product.productId !in productIDs || product.productType != BillingClient.ProductType.INAPP) throw MinuteCommerceFailure.InvalidResponse
                            val offers = product.oneTimePurchaseOfferDetailsList ?: listOfNotNull(product.oneTimePurchaseOfferDetails)
                            if (offers.size > 100) throw MinuteCommerceFailure.InvalidResponse
                            offers.filter { it.rentalDetails == null && it.preorderDetails == null }.map { offer ->
                                val handle = UUID.randomUUID().toString()
                                val view = MinuteStoreOffer(handle, product.productId, offer.priceCurrencyCode, offer.priceAmountMicros, offer.formattedPrice)
                                val token = offer.offerToken?.takeIf { it.isNotEmpty() }
                                if (token != null && !validPurchaseToken(token)) throw MinuteCommerceFailure.InvalidResponse
                                details[handle] = CachedOffer(product, token, view, now() + 60_000)
                                view
                            }
                        }
                        if (mapped.size > 1000) { details.clear(); throw MinuteCommerceFailure.InvalidResponse }
                        continuation.resume(mapped)
                    } catch (error: Exception) { continuation.resumeWithException(error as? MinuteCommerceFailure ?: MinuteCommerceFailure.InvalidResponse) }
                }
            }
        }
    }
    /** Activity is used only for this synchronous SDK call; it is never retained by the adapter. */
    fun launch(activity: Activity, prepared: PreparedMinutePurchase): MinuteStoreOutcome {
        if (Looper.myLooper() != Looper.getMainLooper() || !enabled || closed || activity.isFinishing || activity.isDestroyed ||
            application.packageName != PACKAGE || !billingLazy.isInitialized() || !billing.isReady) return MinuteStoreOutcome.UNAVAILABLE
        val cached = details[prepared.offer.handle] ?: return MinuteStoreOutcome.UNAVAILABLE
        if (cached.expiresAt <= now() || cached.view != prepared.offer || !cached.view.matches(prepared.product) || !prepared.order.matches(prepared.product))
            return MinuteStoreOutcome.UNAVAILABLE
        val detail = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(cached.product)
        cached.token?.let(detail::setOfferToken)
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(detail.build()))
            .setObfuscatedAccountId(prepared.order.payment.obfuscatedAccountID)
            .setObfuscatedProfileId(prepared.order.payment.obfuscatedProfileID).build()
        return try { outcome(billing.launchBillingFlow(activity, params).responseCode) } catch (_: Exception) { MinuteStoreOutcome.FAILED }
    }
    override suspend fun purchases(): List<MinuteStorePurchase> = withContext(Dispatchers.Main.immediate) {
        connect()
        timed {
            suspendCancellableCoroutine { continuation ->
                billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
                    if (continuation.isActive) try {
                        if (closed || result.responseCode != BillingClient.BillingResponseCode.OK) throw MinuteCommerceFailure.Unavailable
                        continuation.resume(mapPurchases(purchases))
                    } catch (error: Exception) { continuation.resumeWithException(error as? MinuteCommerceFailure ?: MinuteCommerceFailure.InvalidResponse) }
                }
            }
        }
    }
    private fun mapPurchases(purchases: List<Purchase>): List<MinuteStorePurchase> {
        if (purchases.size > 100) throw MinuteCommerceFailure.InvalidResponse
        return purchases.map { purchase ->
            if (purchase.packageName != PACKAGE || purchase.products.size != 1 || purchase.quantity != 1 || !minuteIdentifier.matches(purchase.products.single()))
                throw MinuteCommerceFailure.InvalidResponse
            val state = when (purchase.purchaseState) {
                Purchase.PurchaseState.PENDING -> MinuteStorePurchaseState.PENDING
                Purchase.PurchaseState.PURCHASED -> MinuteStorePurchaseState.PURCHASED
                else -> throw MinuteCommerceFailure.InvalidResponse
            }
            MinuteStorePurchase(purchase.purchaseToken, state)
        }
    }
    private suspend fun <T : Any> timed(block: suspend () -> T): T =
        withTimeoutOrNull(20_000) { block() } ?: throw MinuteCommerceFailure.Unavailable
    override fun close() {
        closed = true
        val finish = { details.clear(); if (billingLazy.isInitialized()) billing.endConnection() }
        if (Looper.myLooper() == Looper.getMainLooper()) finish() else Handler(Looper.getMainLooper()).post { finish() }
    }
    companion object {
        private const val PACKAGE = "chat.mural.android"
        internal fun outcome(code: Int, updated: Boolean = false) = when (code) {
            BillingClient.BillingResponseCode.OK -> if (updated) MinuteStoreOutcome.PURCHASES_UPDATED else MinuteStoreOutcome.OPENED
            BillingClient.BillingResponseCode.USER_CANCELED -> MinuteStoreOutcome.CANCELED
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> MinuteStoreOutcome.ALREADY_OWNED
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE, BillingClient.BillingResponseCode.NETWORK_ERROR -> MinuteStoreOutcome.UNAVAILABLE
            else -> MinuteStoreOutcome.FAILED
        }
    }
}
