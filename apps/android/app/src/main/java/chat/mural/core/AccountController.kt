package chat.mural.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class AccountNotice { UNAVAILABLE, SIGN_IN_AGAIN, INVALID_RESPONSE, SECURE_STORAGE, GOOGLE, BILLING_UNRESOLVED,
    APPLE_DELETION, SIGNED_OUT_LOCALLY, DELETED, SAME_ACCOUNT_REQUIRED }

data class AccountState(
    val busy: Boolean = false,
    val googleAvailable: Boolean = false,
    val accountID: String? = null,
    val email: String? = null,
    val minutes: MinuteBalance? = null,
    val notice: AccountNotice? = null,
) { val signedIn: Boolean get() = accountID != null }

/** Only one account mutation may run at a time. Bearers never enter renderable UI state. */
class AccountController(
    private val api: AccountService,
    private val storage: AccountSessionStorage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(AccountState())
    val state = mutable.asStateFlow()
    private val lock = Mutex()
    private var session: AccountSession? = null

    suspend fun restore() = operation {
        val stored = storage.read()
        if (stored != null && !stored.isValid(now())) { storage.clear(); return@operation }
        session = stored
        mutable.value = mutable.value.copy(accountID = stored?.accountID)
        if (stored != null) refreshAccount(stored)
    }
    suspend fun refresh() = operation {
        mutable.value = mutable.value.copy(minutes = null)
        mutable.value = mutable.value.copy(googleAvailable = api.providers().googleAndroid)
        session?.let { refreshAccount(it) }
    }
    suspend fun signIn(expectedAccountID: String? = null, getGoogleToken: suspend (nonce: String) -> String) = operation {
        if (session?.isValid(now()) == true) {
            if (expectedAccountID != null && session?.accountID != expectedAccountID) throw AccountFailure.Http(409, "same_account_required")
            return@operation
        }
        if (session != null) {
            storage.clear(); session = null
            mutable.value = AccountState(busy = true, googleAvailable = mutable.value.googleAvailable)
        }
        if (!api.providers().googleAndroid) throw AccountFailure.Unavailable
        val challenge = api.challenge()
        val deadline = now() + challenge.expiresInSeconds * 1000L
        val token = getGoogleToken(challenge.nonce)
        if (now() >= deadline) throw AccountFailure.Google
        val exchanged = api.exchange(challenge, token, expectedAccountID)
        val newSession = AccountSession(exchanged.accountID, exchanged.accessToken, now() + exchanged.expiresInSeconds * 1000L)
        if (expectedAccountID != null && newSession.accountID != expectedAccountID) {
            // A pending conversation can be recovered only by its original account. Never save another bearer.
            throw AccountFailure.Http(409, "same_account_required")
        }
        // Once the exchange succeeds, rotation must not leave a half-written local session.
        withContext(NonCancellable) {
            storage.save(newSession)
            session = newSession
            mutable.value = mutable.value.copy(accountID = newSession.accountID, email = null, minutes = null)
        }
        refreshAccount(newSession)
    }
    suspend fun signOut() = operation {
        val current = session ?: return@operation
        var remoteEnded = false
        try { api.signOut(current); remoteEnded = true }
        catch (error: AccountFailure.Http) { if (error.status == 401) remoteEnded = true }
        catch (_: AccountFailure) { /* Local removal remains available without a connection. */ }
        withContext(NonCancellable) {
            storage.clear(); session = null
            mutable.value = AccountState(busy = true, googleAvailable = mutable.value.googleAvailable,
                notice = if (remoteEnded) null else AccountNotice.SIGNED_OUT_LOCALLY)
        }
    }
    suspend fun delete(beforeRequest: suspend (String) -> Unit = {}) = operation {
        val current = session ?: return@operation
        beforeRequest(current.accountID)
        api.delete(current)
        withContext(NonCancellable) {
            storage.clear(); session = null
            mutable.value = AccountState(busy = true, googleAvailable = mutable.value.googleAvailable, notice = AccountNotice.DELETED)
        }
    }
    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }
    private suspend fun refreshAccount(current: AccountSession) {
        if (!current.isValid(now())) throw AccountFailure.Http(401, "sign_in_required")
        val profile = api.profile(current)
        if (profile.accountID != current.accountID) throw AccountFailure.InvalidResponse
        mutable.value = mutable.value.copy(email = profile.email, minutes = null)
        mutable.value = mutable.value.copy(minutes = api.minutes(current))
    }
    private suspend fun operation(block: suspend () -> Unit) {
        if (!lock.tryLock()) return
        mutable.value = mutable.value.copy(busy = true, notice = null)
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            var notice = when (error) {
                AccountFailure.SecureStorage -> AccountNotice.SECURE_STORAGE
                AccountFailure.InvalidResponse -> AccountNotice.INVALID_RESPONSE
                AccountFailure.Google -> AccountNotice.GOOGLE
                is AccountFailure.Http -> when {
                    error.status == 401 -> AccountNotice.SIGN_IN_AGAIN
                    error.code == "unresolved_billing" -> AccountNotice.BILLING_UNRESOLVED
                    error.code == "same_account_required" -> AccountNotice.SAME_ACCOUNT_REQUIRED
                    error.code == "apple_revocation_not_configured" -> AccountNotice.APPLE_DELETION
                    else -> AccountNotice.UNAVAILABLE
                }
                else -> AccountNotice.UNAVAILABLE
            }
            if (error is AccountFailure.Http && error.status == 401 && session != null) {
                try {
                    storage.clear(); session = null
                    mutable.value = AccountState(busy = true, googleAvailable = mutable.value.googleAvailable)
                } catch (_: Exception) { notice = AccountNotice.SECURE_STORAGE }
            }
            mutable.value = mutable.value.copy(notice = notice)
        } finally { mutable.value = mutable.value.copy(busy = false); lock.unlock() }
    }
}
