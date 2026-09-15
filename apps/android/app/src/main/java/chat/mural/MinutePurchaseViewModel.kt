package chat.mural

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import chat.mural.core.*
import chat.mural.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Only the existing member store is considered. /v1/account also rejects guest sessions. */
internal class MinutePurchaseMemberAccess(
    private val storage: AccountSessionStorage,
    private val profile: suspend (AccountSession) -> AccountProfile,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var accountID: String? = null
        private set
    private var generation = 0L
    private var verified: AccountSession? = null
    fun selectAccount(value: String?) {
        if (accountID != value) { accountID = value; generation++; verified = null }
    }
    suspend fun read(): AccountSession? {
        val expected = accountID ?: return null
        val selectedGeneration = generation
        val session = storage.read()?.takeIf { it.accountID == expected && it.isValid(now()) }
            ?: run { verified = null; return null }
        if (verified != session) {
            val member = try { profile(session) }
                catch (error: CancellationException) { throw error }
                catch (error: AccountFailure.Http) {
                    if (error.status == 401) return null
                    throw MinuteCommerceFailure.Unavailable
                }
                catch (_: Exception) { throw MinuteCommerceFailure.Unavailable }
            if (member.accountID != expected || member.providers.isEmpty() || member.providers.any { it !in listOf("google", "apple") })
                throw MinuteCommerceFailure.InvalidResponse
            if (generation != selectedGeneration || storage.read() != session || !session.isValid(now())) return null
            verified = session
        }
        return session.takeIf { generation == selectedGeneration }
    }
}

internal data class MinutePurchaseCapability(val enabled: Boolean, val environment: String) {
    companion object {
        fun checked(enabled: Boolean, environment: String, hasConfiguration: Boolean, packageName: String, channel: String = "play"): MinutePurchaseCapability {
            val validEnvironment = environment in listOf("test", "live")
            return MinutePurchaseCapability(enabled && validEnvironment && PurchaseChannel.parse(channel) != null && hasConfiguration && packageName == "chat.mural.android",
                if (validEnvironment) environment else "test")
        }
    }
}

internal data class MinutePurchaseActivityState(val lifecycle: Lifecycle.State, val finishing: Boolean, val destroyed: Boolean)
internal class MinutePurchaseLaunchPermit internal constructor(internal val accountID: String, internal val revision: Long) {
    override fun toString() = "MinutePurchaseLaunchPermit(redacted)"
}

/** Re-reads retained account flows immediately before the synchronous Play call. */
internal class MinutePurchaseLaunchGate {
    private var account: StateFlow<AccountState>? = null
    private var transition: StateFlow<Boolean>? = null
    private var owner: String? = null
    private var busy = false
    private var revision = 0L
    fun bind(account: StateFlow<AccountState>, transition: StateFlow<Boolean>) {
        if (this.account !== account || this.transition !== transition) revision++
        this.account = account; this.transition = transition
        refresh()
    }
    fun observe(value: AccountState) {
        if (owner != value.accountID || busy != value.busy) revision++
        owner = value.accountID; busy = value.busy
    }
    fun begin(): MinutePurchaseLaunchPermit? {
        if (!refresh() || busy) return null
        return owner?.let { MinutePurchaseLaunchPermit(it, revision) }
    }
    fun launchIfReady(permit: MinutePurchaseLaunchPermit, activity: MinutePurchaseActivityState?,
        launch: () -> MinuteStoreOutcome): MinuteStoreOutcome {
        if (!refresh() || busy || owner != permit.accountID || revision != permit.revision || activity == null ||
            activity.finishing || activity.destroyed || activity.lifecycle != Lifecycle.State.RESUMED) return MinuteStoreOutcome.UNAVAILABLE
        return launch()
    }
    private fun refresh(): Boolean {
        val current = account?.value ?: return false
        val changing = transition?.value ?: return false
        observe(current.copy(busy = current.busy || changing))
        return true
    }
    fun clear() { account = null; transition = null; owner = null; busy = true; revision++ }
}

/** Activity-independent owner: account and foreground hooks are supplied by the app shell. */
class MinutePurchaseViewModel(application: Application) : AndroidViewModel(application) {
    private val configuration = MinuteCommerceConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)
    private val accountConfiguration = ManagedAccountConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN, BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    private val capability = MinutePurchaseCapability.checked(BuildConfig.MINUTE_PURCHASES_ENABLED,
        BuildConfig.MINUTE_PURCHASE_ENVIRONMENT, configuration != null && accountConfiguration != null, application.packageName, BuildConfig.PURCHASE_CHANNEL)
    private val channel = PurchaseChannel.parse(BuildConfig.PURCHASE_CHANNEL) ?: PurchaseChannel.PLAY
    val enabled: Boolean get() = capability.enabled
    private val access = if (enabled) MinutePurchaseMemberAccess(AccountSessionStore(application, configuration!!.origin.toString()),
        ManagedAccountClient(accountConfiguration!!)::profile) else null
    private val store = if (enabled && channel == PurchaseChannel.PLAY) PlayBillingAdapter(application, enabled = true) else null
    private val mutableBalanceChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val balanceChanges = mutableBalanceChanges.asSharedFlow()
    private val selectedAccount = MutableStateFlow<String?>(null)
    private val walletOwner = MutableStateFlow<String?>(null)
    private val launchGate = MinutePurchaseLaunchGate()
    private val controller = if (enabled && channel == PurchaseChannel.PLAY) MinutePurchaseController(viewModelScope, MinuteCommerceClient(configuration!!), store!!,
        readMember = { access!!.read() }, enabled = true, expectedEnvironment = capability.environment,
        onBalanceChanged = {
            walletOwner.value = access!!.accountID
            mutableBalanceChanges.tryEmit(Unit)
        }) else null
    private val stripeController = if (enabled && channel == PurchaseChannel.STRIPE) StripeMinutePurchaseController(
        MinuteCommerceClient(configuration!!, PurchaseChannel.STRIPE),
        StripePurchaseAttemptStore(application, configuration.origin.toString(), capability.environment),
        readMember = { access!!.read() }, enabled = true, expectedEnvironment = capability.environment,
        onBalanceChanged = {
            walletOwner.value = access!!.accountID
            mutableBalanceChanges.tryEmit(Unit)
        }) else null
    private val purchaseState = controller?.state ?: stripeController?.state
    val state: StateFlow<MinutePurchaseState> = purchaseState?.let { purchases ->
        combine(purchases, selectedAccount, walletOwner) { value, selected, owner ->
            value.copy(balance = value.balance.takeIf { selected != null && selected == owner })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MinutePurchaseState(channel = channel))
    } ?: MutableStateFlow(MinutePurchaseState(channel = channel)).asStateFlow()

    /** Retained ViewModel flows only. Never pass an Activity-bound combined flow here. */
    fun bindAccountState(account: StateFlow<AccountState>, transitionBusy: StateFlow<Boolean>) {
        launchGate.bind(account, transitionBusy)
    }
    /** Call from the foreground lifecycle and pass the current AccountViewModel state. */
    fun onForeground(account: AccountState) {
        selectAccount(account)
        viewModelScope.launch { controller?.onForeground(); stripeController?.onForeground() }
    }
    /** Call when membership or account-operation state changes, including sign-out and deletion. */
    fun onAccountChanged(account: AccountState) {
        val changed = selectAccount(account)
        if (changed) viewModelScope.launch { controller?.onForeground(); stripeController?.onForeground() }
    }
    fun refresh() { viewModelScope.launch { controller?.refresh(); stripeController?.refresh() } }
    fun launch(activity: Activity, sku: String) {
        if (!enabled) return
        val permit = launchGate.begin() ?: return
        val currentActivity = WeakReference(activity)
        viewModelScope.launch {
            stripeController?.buy(sku) { checkout ->
                val host = currentActivity.get()
                val lifecycle = (host as? LifecycleOwner)?.lifecycle?.currentState
                val readiness = if (host != null && lifecycle != null)
                    MinutePurchaseActivityState(lifecycle, host.isFinishing, host.isDestroyed) else null
                launchGate.launchIfReady(permit, readiness) {
                    try {
                        // No custom URI callback and no account credentials appended to Stripe's URL.
                        host!!.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checkout.value)).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        })
                        MinuteStoreOutcome.OPENED
                    } catch (_: Exception) { MinuteStoreOutcome.UNAVAILABLE }
                }
            }
            controller?.buy(sku) { prepared ->
                val host = currentActivity.get()
                val lifecycle = (host as? LifecycleOwner)?.lifecycle?.currentState
                val readiness = if (host != null && lifecycle != null)
                    MinutePurchaseActivityState(lifecycle, host.isFinishing, host.isDestroyed) else null
                launchGate.launchIfReady(permit, readiness) { store!!.launch(host!!, prepared) }
            }
        }
    }
    fun dismissNotice() { controller?.dismissNotice(); stripeController?.dismissNotice() }
    private fun selectAccount(account: AccountState): Boolean {
        launchGate.observe(account)
        val id = account.accountID.takeIf { account.signedIn }
        val changed = selectedAccount.value != id
        if (changed) walletOwner.value = null
        selectedAccount.value = id
        access?.selectAccount(id)
        return changed
    }
    override fun onCleared() { launchGate.clear(); controller?.close(); stripeController?.close(); access?.selectAccount(null); super.onCleared() }
}
