package chat.mural.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GuestInstallation(
    val installationToken: String,
    val session: AccountSession? = null,
    val pendingMemberID: String? = null,
    val linkedMemberID: String? = null,
    val memberAlreadyClaimedTrial: Boolean = false,
    val serverDeferred: Boolean = false,
    val acknowledgedGuestID: String? = null,
) {
    init {
        require(Regex("[A-Za-z0-9_-]{43}").matches(installationToken))
        listOfNotNull(pendingMemberID, linkedMemberID, acknowledgedGuestID).forEach {
            require(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(it))
        }
        require(acknowledgedGuestID == null || linkedMemberID != null)
        require(!memberAlreadyClaimedTrial || linkedMemberID != null)
        require(!serverDeferred || (pendingMemberID != null && session != null && linkedMemberID == null))
        require(pendingMemberID == null || session != null)
        require(linkedMemberID == null || (session == null && pendingMemberID == null))
    }
    override fun toString() = "GuestInstallation(redacted)"
}

interface GuestInstallationStorage {
    suspend fun read(): GuestInstallation?
    suspend fun save(value: GuestInstallation)
}

sealed interface GuestGrant {
    data class Available(val session: AccountSession, val remainingMilliseconds: Long, val resumed: Boolean) : GuestGrant {
        init { require(remainingMilliseconds in 0..9_007_199_254_740_991L) }
    }
    data object TemporarilyUnavailable : GuestGrant
    data object SignInRequired : GuestGrant
}

data class GuestLinkResult(val transferredMilliseconds: Long, val alreadyLinked: Boolean, val outcome: String, val pending: Boolean = false) {
    init {
        require(transferredMilliseconds in 0..9_007_199_254_740_991L)
        require(outcome in setOf("transferred", "member_trial_already_claimed", "pending"))
        require(outcome != "member_trial_already_claimed" || transferredMilliseconds == 0L)
        require(pending == (outcome == "pending"))
        require(!pending || (transferredMilliseconds == 0L && !alreadyLinked))
    }
}

interface GuestMinuteService {
    suspend fun start(installationToken: String): GuestGrant
    suspend fun balance(session: AccountSession): MinuteBalance
    suspend fun link(member: AccountSession, guestAccessToken: String): GuestLinkResult
    suspend fun deferLink(member: AccountSession, guestAccessToken: String?, guestAccountID: String): GuestLinkResult = throw AccountFailure.Unavailable
}

enum class GuestMinuteStatus { IDLE, CHECKING, READY, UNAVAILABLE, SIGN_IN_REQUIRED, RETRY, LINKING, DEFERRED, MEMBER_TRIAL_USED }
data class GuestMinuteState(
    val status: GuestMinuteStatus = GuestMinuteStatus.IDLE,
    val accountID: String? = null,
    val remainingMilliseconds: Long = 0,
)

/** A guest is never a member. Only this controller sees its separate credentials and upgrade ticket. */
class GuestMinuteController(
    private val storage: GuestInstallationStorage,
    private val service: GuestMinuteService,
    private val newInstallationToken: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(GuestMinuteState())
    val state = mutableState.asStateFlow()

    suspend fun retainedOwnerID(): String? = lock.withLock { storage.read()?.session?.accountID }
    suspend fun memberMaySpend(accountID: String): Boolean = lock.withLock {
        val stored = storage.read()
        stored?.serverDeferred == true && stored.pendingMemberID == accountID
    }
    /** A terminal transfer or explicitly retired, server-owned lease needs no client credential. */
    suspend fun recoverAcknowledgedOwner(pendingOwner: String?, clear: suspend (String) -> Unit): Boolean = lock.withLock {
        val acknowledged = storage.read()?.acknowledgedGuestID ?: return@withLock false
        if (pendingOwner != acknowledged) return@withLock false
        withContext(NonCancellable) { clear(acknowledged) }
        true
    }

    /** Guest credential recovery must not prevent independent learning/BYOK storage from opening. */
    suspend fun recoverAcknowledgedOwnerAtStartup(pendingOwner: String?, clear: suspend (String) -> Unit,
        onFailure: () -> Unit) {
        try { recoverAcknowledgedOwner(pendingOwner, clear) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onFailure() }
    }

    /** Persist custody retirement before DELETE: even a lost deletion response cannot pin OAuth.
     * This changes no server balance and never resets the installation's consumed trial. */
    suspend fun retireAcknowledgedLinkForDeletion(memberID: String) = lock.withLock {
        val stored = storage.read() ?: return@withLock
        if (!stored.serverDeferred || stored.pendingMemberID != memberID) return@withLock
        withContext(NonCancellable) {
            storage.save(stored.copy(acknowledgedGuestID = stored.session!!.accountID,
                session = null, pendingMemberID = null, linkedMemberID = memberID, serverDeferred = false))
        }
        mutableState.value = GuestMinuteState(GuestMinuteStatus.SIGN_IN_REQUIRED)
    }
    suspend fun expectedMemberID(): String? = lock.withLock { storage.read()?.pendingMemberID }
    suspend fun owns(accountID: String): Boolean = lock.withLock { storage.read()?.session?.accountID == accountID }
    suspend fun session(ownerID: String? = null): AccountSession? = lock.withLock {
        storage.read()?.session?.takeIf { it.isValid(now()) && (ownerID == null || it.accountID == ownerID) }
    }
    suspend fun availableSession(): AccountSession? = lock.withLock {
        val stored = storage.read() ?: return@withLock null
        stored.session?.takeIf { stored.pendingMemberID == null && !stored.serverDeferred && it.isValid(now()) }
    }
    suspend fun needsLink(): Boolean = lock.withLock { storage.read()?.session != null }

    /** Renew an exhausted guest only for its retained lease, without creating a new identity.
     * An uncertain transfer must replay its exact original bearer through linkTo instead. */
    suspend fun sessionForSettlement(ownerID: String): AccountSession? {
        session(ownerID)?.let { return it }
        if (!owns(ownerID) || expectedMemberID() != null) return null
        acquire()
        return session(ownerID)
    }

    suspend fun acquire(): Boolean = lock.withLock {
        val previous = mutableState.value
        mutableState.value = mutableState.value.copy(status = GuestMinuteStatus.CHECKING)
        try {
            var stored = storage.read() ?: GuestInstallation(newInstallationToken()).also { storage.save(it) }
            if (stored.linkedMemberID != null) {
                mutableState.value = GuestMinuteState(GuestMinuteStatus.SIGN_IN_REQUIRED); return@withLock false
            }
            // A response may have been lost after transfer. Keep the exact original token for replay.
            if (stored.pendingMemberID != null) {
                mutableState.value = GuestMinuteState(GuestMinuteStatus.LINKING); return@withLock false
            }
            val existing = stored.session
            if (existing != null && existing.isValid(now())) {
                try {
                    val balance = service.balance(existing)
                    ready(existing, balance.availableMilliseconds)
                    return@withLock balance.availableMilliseconds > 0
                } catch (failure: AccountFailure.Http) { if (failure.status != 401) throw failure }
            }
            when (val grant = service.start(stored.installationToken)) {
                is GuestGrant.Available -> {
                    require(grant.session.isValid(now()))
                    require(existing == null || existing.accountID == grant.session.accountID)
                    stored = stored.copy(session = grant.session)
                    withContext(NonCancellable) { storage.save(stored) }
                    ready(grant.session, grant.remainingMilliseconds)
                    grant.remainingMilliseconds > 0
                }
                GuestGrant.TemporarilyUnavailable -> { mutableState.value = GuestMinuteState(GuestMinuteStatus.UNAVAILABLE); false }
                GuestGrant.SignInRequired -> { mutableState.value = GuestMinuteState(GuestMinuteStatus.SIGN_IN_REQUIRED); false }
            }
        } catch (cancelled: CancellationException) { mutableState.value = previous; throw cancelled }
        catch (_: Exception) { mutableState.value = GuestMinuteState(GuestMinuteStatus.RETRY); false }
    }

    /** Retain an authoritative balance already fetched while closing this guest's conversation. */
    suspend fun recordSettledBalance(owner: AccountSession, balance: MinuteBalance) = lock.withLock {
        val stored = storage.read() ?: return@withLock
        val guest = stored.session ?: return@withLock
        if (stored.pendingMemberID != null || stored.linkedMemberID != null || guest.accountID != owner.accountID) return@withLock
        ready(guest, balance.availableMilliseconds)
    }

    /** Legacy links require settlement; opt-in deferred links retain the guest until server completion. */
    suspend fun linkTo(member: AccountSession, allowDeferred: Boolean = false): Boolean = lock.withLock {
        try {
            require(member.isValid(now()))
            var stored = storage.read() ?: return@withLock true
            if (stored.linkedMemberID != null || stored.session == null) return@withLock true
            if (stored.pendingMemberID != null && stored.pendingMemberID != member.accountID) return@withLock false
            if (stored.session.accountID == member.accountID) return@withLock false
            mutableState.value = GuestMinuteState(GuestMinuteStatus.LINKING)
            stored = stored.copy(pendingMemberID = member.accountID)
            withContext(NonCancellable) { storage.save(stored) }
            val result = try {
                if (allowDeferred) service.deferLink(member, if (stored.serverDeferred) null else stored.session!!.accessToken, stored.session!!.accountID)
                else service.link(member, stored.session!!.accessToken)
            }
            catch (failure: AccountFailure.Http) {
                // A definite invalid/expired bearer is safe to renew. An uncertain request always
                // retries its original token first, so an already-completed transfer stays idempotent.
                if (failure.status != 401 || failure.code != "invalid_guest_session") throw failure
                val renewed = service.start(stored.installationToken) as? GuestGrant.Available ?: throw failure
                require(renewed.session.accountID == stored.session!!.accountID && renewed.session.isValid(now()))
                stored = stored.copy(session = renewed.session)
                withContext(NonCancellable) { storage.save(stored) }
                if (allowDeferred) service.deferLink(member, renewed.session.accessToken, renewed.session.accountID) else service.link(member, renewed.session.accessToken)
            }
            if (result.pending) {
                require(allowDeferred)
                withContext(NonCancellable) { storage.save(stored.copy(serverDeferred = true)) }
                mutableState.value = GuestMinuteState(GuestMinuteStatus.DEFERRED)
                return@withLock true
            }
            withContext(NonCancellable) {
                storage.save(stored.copy(acknowledgedGuestID = stored.session!!.accountID, session = null, pendingMemberID = null, linkedMemberID = member.accountID, serverDeferred = false,
                    memberAlreadyClaimedTrial = result.outcome == "member_trial_already_claimed"))
            }
            mutableState.value = GuestMinuteState(if (result.outcome == "member_trial_already_claimed")
                GuestMinuteStatus.MEMBER_TRIAL_USED else GuestMinuteStatus.SIGN_IN_REQUIRED)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = GuestMinuteState(GuestMinuteStatus.LINKING); false }
    }

    private fun ready(session: AccountSession, remaining: Long) {
        mutableState.value = GuestMinuteState(GuestMinuteStatus.READY, session.accountID, remaining)
    }
}
