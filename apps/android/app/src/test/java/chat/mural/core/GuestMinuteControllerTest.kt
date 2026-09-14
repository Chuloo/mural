package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuestMinuteControllerTest {
    private val now = 1_800_000_000_000L
    private val guest = AccountSession("11111111-1111-4111-8111-111111111111", "g".repeat(43), now + 86_400_000)
    private val member = AccountSession("22222222-2222-4222-8222-222222222222", "m".repeat(43), now + 86_400_000)
    private class Store(var saved: GuestInstallation? = null) : GuestInstallationStorage {
        var corrupt = false
        override suspend fun read(): GuestInstallation? { if (corrupt) throw AccountFailure.SecureStorage; return saved }
        override suspend fun save(value: GuestInstallation) { if (corrupt) throw AccountFailure.SecureStorage; saved = value }
    }
    private inner class Service : GuestMinuteService {
        var grant: GuestGrant = GuestGrant.Available(guest, 600_000, false)
        var available = 480_000L
        var purchased = 1_800_000L
        var starts = 0; var links = 0; var debits = 0
        var failure: Exception? = null
        var loseNextLinkResponse = false
        val installationTokens = mutableListOf<String>()
        val linkedTokens = mutableListOf<String>()
        val fulfilled = mutableSetOf<String>()
        override suspend fun start(installationToken: String): GuestGrant {
            starts++; installationTokens += installationToken; failure?.let { throw it }; return grant
        }
        override suspend fun balance(session: AccountSession): MinuteBalance {
            failure?.let { throw it }
            return MinuteBalance("milliseconds", "connected-conversation-time", available, 0, available)
        }
        override suspend fun link(member: AccountSession, guestAccessToken: String): GuestLinkResult {
            links++; linkedTokens += guestAccessToken; failure?.let { throw it }
            if (fulfilled.add(guestAccessToken)) { purchased += available; available = 0; debits++ }
            if (loseNextLinkResponse) { loseNextLinkResponse = false; throw AccountFailure.Unavailable }
            return GuestLinkResult(480_000, false, "transferred")
        }
    }
    private fun controller(store: Store, api: Service) = GuestMinuteController(store, api, { "i".repeat(43) }, { now })

    @Test fun settledBalanceSurvivesCancelledRefreshAndCannotCrossAccounts() = runTest {
        val store = Store(); val api = Service(); val subject = controller(store, api)
        subject.acquire()
        subject.recordSettledBalance(guest, MinuteBalance("milliseconds", "connected-conversation-time", 492_000, 0, 492_000))
        api.failure = CancellationException("superseded readiness refresh")
        try { subject.acquire(); fail("refresh was not cancelled") } catch (_: CancellationException) { }
        assertEquals(GuestMinuteStatus.READY, subject.state.value.status)
        assertEquals(492_000L, subject.state.value.remainingMilliseconds)
        subject.recordSettledBalance(member, MinuteBalance("milliseconds", "connected-conversation-time", 1, 0, 1))
        assertEquals(492_000L, subject.state.value.remainingMilliseconds)
        api.failure = null
        assertTrue(subject.linkTo(member))
        subject.recordSettledBalance(guest, MinuteBalance("milliseconds", "connected-conversation-time", 600_000, 0, 600_000))
        assertNotEquals(GuestMinuteStatus.READY, subject.state.value.status)
        assertNull(subject.session())
    }

    @Test fun freshGuestNeedsNoMemberAndResumesSameAllowanceAfterRestart() = runTest {
        val store = Store(); val api = Service(); val first = controller(store, api)
        assertTrue(first.acquire()); assertEquals(600_000L, first.state.value.remainingMilliseconds)
        assertEquals(guest, first.session()); assertFalse(store.saved.toString().contains("i".repeat(43)))
        val restarted = controller(store, api)
        assertTrue(restarted.acquire()); assertEquals(480_000L, restarted.state.value.remainingMilliseconds)
        assertEquals(1, api.starts); assertEquals(null, restarted.expectedMemberID())
    }
    @Test fun pausedWelcomeFundingStillAllowsAnExistingGuestsRemainingTime() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val api = Service()
        api.grant = GuestGrant.TemporarilyUnavailable
        val subject = controller(store, api)
        assertTrue(subject.acquire()); assertEquals(0, api.starts); assertEquals(480_000L, subject.state.value.remainingMilliseconds)
    }
    @Test fun unavailableAndConnectionFailureAreDistinctAndKeepInstallationIdentity() = runTest {
        val store = Store(); val api = Service(); val subject = controller(store, api)
        api.grant = GuestGrant.TemporarilyUnavailable
        assertFalse(subject.acquire()); assertEquals(GuestMinuteStatus.UNAVAILABLE, subject.state.value.status)
        api.failure = AccountFailure.Unavailable
        assertFalse(subject.acquire()); assertEquals(GuestMinuteStatus.RETRY, subject.state.value.status)
        assertEquals(1, api.installationTokens.toSet().size); assertNull(subject.session())
    }
    @Test fun expiredBearerRenewsTheSameGuestRatherThanGivingAnotherWelcome() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest.copy(expiresAtMilliseconds = now - 1)))
        val api = Service(); api.grant = GuestGrant.Available(guest.copy(accessToken = "r".repeat(43)), 110_000, true)
        val subject = controller(store, api)
        assertTrue(subject.acquire()); assertEquals(110_000L, subject.state.value.remainingMilliseconds)
        assertEquals(guest.accountID, subject.session()?.accountID); assertEquals("r".repeat(43), store.saved?.session?.accessToken)
    }
    @Test fun providerCannotChangeGuestIdentityOnRenewal() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest.copy(expiresAtMilliseconds = now - 1)))
        val api = Service(); api.grant = GuestGrant.Available(member, 600_000, true)
        assertFalse(controller(store, api).acquire()); assertEquals(guest.accountID, store.saved?.session?.accountID)
    }
    @Test fun signInAddsRemainingTimeToPurchasedWalletWithoutRefillingTrial() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val api = Service(); val subject = controller(store, api)
        assertTrue(subject.linkTo(member)); assertEquals(2_280_000L, api.purchased); assertEquals(0L, api.available)
        assertNull(subject.session()); assertEquals(member.accountID, store.saved?.linkedMemberID)
        assertFalse(subject.acquire()); assertEquals(0, api.starts)
        assertTrue(subject.linkTo(member)); assertEquals(1, api.debits)
    }
    @Test fun lostTransferResponsePersistsExactOwnerAndBearerUntilIdempotentReplay() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val api = Service(); api.loseNextLinkResponse = true
        assertFalse(controller(store, api).linkTo(member))
        assertEquals(member.accountID, store.saved?.pendingMemberID); assertEquals(guest, store.saved?.session)
        val restarted = controller(store, api)
        assertFalse(restarted.acquire()); assertEquals(0, api.starts)
        assertTrue(restarted.linkTo(member)); assertEquals(listOf(guest.accessToken, guest.accessToken), api.linkedTokens)
        assertEquals(1, api.debits); assertEquals(2_280_000L, api.purchased)
    }
    @Test fun aDifferentGoogleAccountCannotTakeAnUncertainTransfer() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest, pendingMemberID = member.accountID)); val api = Service()
        val other = member.copy(accountID = "33333333-3333-4333-8333-333333333333")
        val subject = controller(store, api)
        assertFalse(subject.linkTo(other)); assertEquals(0, api.links); assertEquals(member.accountID, subject.expectedMemberID())
    }
    @Test fun outstandingReservationDoesNotDiscardGuestOrEnableAnotherTrial() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val api = Service()
        api.failure = AccountFailure.Http(409, "finish_guest_conversation_first")
        val subject = controller(store, api)
        assertFalse(subject.linkTo(member)); assertEquals(guest, store.saved?.session)
        assertEquals(GuestMinuteStatus.LINKING, subject.state.value.status)
        assertFalse(subject.acquire()); assertEquals(0, api.starts)
    }
    @Test fun corruptedSecureIdentityCannotCreateAnotherInstallation() = runTest {
        val store = Store().apply { corrupt = true }; val api = Service(); val subject = controller(store, api)
        assertFalse(subject.acquire()); assertEquals(GuestMinuteStatus.RETRY, subject.state.value.status); assertEquals(0, api.starts)
    }
    @Test fun sameProcessConcurrentAcquireAndUpgradeCannotDuplicateGrant() = runTest {
        val store = Store(); val api = Service(); val subject = controller(store, api)
        coroutineScope { (1..10).map { async { subject.acquire() } }.awaitAll() }
        assertEquals(1, api.starts)
        coroutineScope { (1..10).map { async { subject.linkTo(member) } }.awaitAll() }
        assertEquals(1, api.debits); assertEquals(1, api.links)
    }
    @Test fun expiresDuringUncertainUpgradeCanRenewOnlyAfterDefinitiveInvalidGuest() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest, pendingMemberID = member.accountID))
        val renewed = guest.copy(accessToken = "r".repeat(43)); val links = mutableListOf<String>()
        val api = object : GuestMinuteService {
            override suspend fun start(installationToken: String) = GuestGrant.Available(renewed, 480_000, true)
            override suspend fun balance(session: AccountSession) = error("unused")
            override suspend fun link(member: AccountSession, guestAccessToken: String): GuestLinkResult {
                links += guestAccessToken
                if (guestAccessToken == guest.accessToken) throw AccountFailure.Http(401, "invalid_guest_session")
                return GuestLinkResult(480_000, false, "transferred")
            }
        }
        val subject = GuestMinuteController(store, api, { error("must not replace installation") }, { now })
        assertTrue(subject.linkTo(member)); assertEquals(listOf(guest.accessToken, renewed.accessToken), links)
    }
    @Test fun existingMemberTrialFinalizesUpgradeWithoutBlockingOrRefillingTheirWallet() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); var calls = 0
        val api = object : GuestMinuteService {
            override suspend fun start(installationToken: String) = error("must not grant another trial")
            override suspend fun balance(session: AccountSession) = error("unused")
            override suspend fun link(member: AccountSession, guestAccessToken: String): GuestLinkResult {
                calls++
                return GuestLinkResult(0, calls > 1, "member_trial_already_claimed")
            }
        }
        val subject = GuestMinuteController(store, api, { error("must not replace identity") }, { now })
        assertTrue(subject.linkTo(member)); assertFalse(subject.needsLink())
        assertNull(subject.expectedMemberID()); assertNull(subject.session())
        assertEquals(GuestMinuteStatus.MEMBER_TRIAL_USED, subject.state.value.status)
        assertTrue(store.saved!!.memberAlreadyClaimedTrial)
        assertTrue(subject.linkTo(member)); assertEquals(1, calls)
    }
    @Test fun aBareDuplicateTrialErrorCannotPretendTheGuestWasSafelyRetired() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val api = Service()
        api.failure = AccountFailure.Http(409, "trial_already_claimed")
        val subject = controller(store, api)
        assertFalse(subject.linkTo(member)); assertTrue(subject.needsLink()); assertEquals(guest, store.saved?.session)
    }
}
