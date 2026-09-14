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
) {
    init {
        require(Regex("[A-Za-z0-9_-]{43}").matches(installationToken))
        listOfNotNull(pendingMemberID, linkedMemberID).forEach {
            require(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(it))
        }
        require(!memberAlreadyClaimedTrial || linkedMemberID != null)
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

data class GuestLinkResult(val transferredMilliseconds: Long, val alreadyLinked: Boolean, val outcome: String) {
    init {
        require(transferredMilliseconds in 0..9_007_199_254_740_991L)
        require(outcome in setOf("transferred", "member_trial_already_claimed"))
        require(outcome != "member_trial_already_claimed" || transferredMilliseconds == 0L)
    }
}

interface GuestMinuteService {
    suspend fun start(installationToken: String): GuestGrant
    suspend fun balance(session: AccountSession): MinuteBalance
    suspend fun link(member: AccountSession, guestAccessToken: String): GuestLinkResult
}

enum class GuestMinuteStatus { IDLE, CHECKING, READY, UNAVAILABLE, SIGN_IN_REQUIRED, RETRY, LINKING, MEMBER_TRIAL_USED }
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

    suspend fun expectedMemberID(): String? = lock.withLock { storage.read()?.pendingMemberID }
    suspend fun owns(accountID: String): Boolean = lock.withLock { storage.read()?.session?.accountID == accountID }
    suspend fun session(ownerID: String? = null): AccountSession? = lock.withLock {
        storage.read()?.session?.takeIf { it.isValid(now()) && (ownerID == null || it.accountID == ownerID) }
    }
    suspend fun needsLink(): Boolean = lock.withLock { storage.read()?.session != null }

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

    /** Call only after guest conversations settle. Member wallet credits are added by the server. */
    suspend fun linkTo(member: AccountSession): Boolean = lock.withLock {
        try {
            require(member.isValid(now()))
            var stored = storage.read() ?: return@withLock true
            if (stored.linkedMemberID != null || stored.session == null) return@withLock true
            if (stored.pendingMemberID != null && stored.pendingMemberID != member.accountID) return@withLock false
            if (stored.session.accountID == member.accountID) return@withLock false
            mutableState.value = GuestMinuteState(GuestMinuteStatus.LINKING)
            stored = stored.copy(pendingMemberID = member.accountID)
            withContext(NonCancellable) { storage.save(stored) }
            val result = try { service.link(member, stored.session!!.accessToken) }
            catch (failure: AccountFailure.Http) {
                // A definite invalid/expired bearer is safe to renew. An uncertain request always
                // retries its original token first, so an already-completed transfer stays idempotent.
                if (failure.status != 401 || failure.code != "invalid_guest_session") throw failure
                val renewed = service.start(stored.installationToken) as? GuestGrant.Available ?: throw failure
                require(renewed.session.accountID == stored.session!!.accountID && renewed.session.isValid(now()))
                stored = stored.copy(session = renewed.session)
                withContext(NonCancellable) { storage.save(stored) }
                service.link(member, renewed.session.accessToken)
            }
            withContext(NonCancellable) {
                storage.save(stored.copy(session = null, pendingMemberID = null, linkedMemberID = member.accountID,
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
