package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

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

    @Test fun unreadableGuestCredentialsDoNotAbortIndependentLearningStartupOrClearPendingOwner() = runTest {
        val saved = GuestInstallation("i".repeat(43), guest, pendingMemberID = member.accountID)
        val store = Store(saved).apply { corrupt = true }; val subject = controller(store, Service())
        var pendingOwner: String? = guest.accountID; var notices = 0
        subject.recoverAcknowledgedOwnerAtStartup(pendingOwner, { pendingOwner = null }, { notices++ })
        assertEquals(1, notices); assertEquals(guest.accountID, pendingOwner); assertEquals(saved, store.saved)
        // Recovery failed closed: no new identity, hosted owner, or account mutation is authorized.
        assertFalse(subject.acquire())
        try { subject.needsLink(); fail("Unreadable credentials must not imply no pending link") }
        catch (_: AccountFailure.SecureStorage) { }
        store.corrupt = false
        assertEquals(member.accountID, subject.expectedMemberID())
    }

    @Test fun startupReceiptCleanupFailurePreservesRetryAndCancellationPropagates() = runTest {
        val saved = GuestInstallation("i".repeat(43), linkedMemberID = member.accountID, acknowledgedGuestID = guest.accountID)
        val store = Store(saved); val subject = controller(store, Service()); var notices = 0
        var pendingOwner: String? = guest.accountID
        subject.recoverAcknowledgedOwnerAtStartup(pendingOwner, { throw AccountFailure.SecureStorage }, { notices++ })
        assertEquals(1, notices); assertEquals(saved, store.saved); assertEquals(guest.accountID, pendingOwner)
        subject.recoverAcknowledgedOwnerAtStartup(pendingOwner, { pendingOwner = null }, { error("Retry must succeed") })
        assertNull(pendingOwner)
        try {
            subject.recoverAcknowledgedOwnerAtStartup(guest.accountID,
                { throw CancellationException("ViewModel cleared") }, { error("Cancellation is not storage failure") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun terminalReceiptRepairsOnlyItsGuestMarkerAfterRestartBetweenStoreWrites() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val api = Service()
        val first = controller(store, api)
        assertFalse(first.recoverAcknowledgedOwner(guest.accountID) { error("No server acknowledgment yet") })
        assertTrue(first.linkTo(member))
        // Simulate process death after the terminal receipt but before provider-marker cleanup.
        val reloaded = Store(Json.decodeFromString<GuestInstallation>(Json.encodeToString(store.saved!!)))
        val restarted = controller(reloaded, api)
        assertNull(restarted.expectedMemberID()); assertNull(restarted.session())
        var marker: String? = guest.accountID
        assertFalse(restarted.recoverAcknowledgedOwner(member.accountID) { error("Other owner must stay pending") })
        assertTrue(restarted.recoverAcknowledgedOwner(marker) { assertEquals(guest.accountID, it); marker = null })
        assertNull(marker)
        assertFalse(restarted.recoverAcknowledgedOwner(marker) { error("Already cleared") })
        assertFalse(restarted.acquire()); assertEquals(0, api.starts); assertEquals(1, api.debits)
        assertTrue(restarted.linkTo(member)); assertEquals(1, api.links)
    }

    @Test fun acknowledgedCustodyRetirementKeepsConsumedInstallationAndCannotRebindOrRefill() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest, pendingMemberID = member.accountID, serverDeferred = true))
        val api = Service(); val subject = controller(store, api)
        subject.retireAcknowledgedLinkForDeletion(guest.accountID)
        assertTrue(store.saved!!.serverDeferred); assertEquals(member.accountID, subject.expectedMemberID())
        subject.retireAcknowledgedLinkForDeletion(member.accountID)
        val restarted = controller(Store(Json.decodeFromString<GuestInstallation>(Json.encodeToString(store.saved!!))), api)
        assertNull(restarted.expectedMemberID()); assertNull(restarted.session()); assertFalse(restarted.needsLink())
        assertEquals("i".repeat(43), store.saved!!.installationToken)
        assertEquals(member.accountID, store.saved!!.linkedMemberID)
        assertEquals(guest.accountID, store.saved!!.acknowledgedGuestID)
        assertFalse(restarted.acquire()); assertEquals(0, api.starts)
        assertTrue(restarted.linkTo(member.copy(accountID = "33333333-3333-4333-8333-333333333333")))
        assertEquals(0, api.links) // A fresh OAuth member never receives the retired guest.
        assertTrue(restarted.recoverAcknowledgedOwner(guest.accountID) {})
    }

    @Test fun unacknowledgedOrFailedStorageRetirementKeepsOriginalProof() = runTest {
        val original = GuestInstallation("i".repeat(43), guest, pendingMemberID = member.accountID)
        val store = Store(original); val subject = controller(store, Service())
        subject.retireAcknowledgedLinkForDeletion(member.accountID)
        assertEquals(original, store.saved)
        store.saved = original.copy(serverDeferred = true); store.corrupt = true
        try { subject.retireAcknowledgedLinkForDeletion(member.accountID); fail("Storage failure must abort deletion") }
        catch (_: AccountFailure.SecureStorage) { }
        store.corrupt = false
        assertEquals(original.copy(serverDeferred = true), store.saved)
    }

    @Test fun versionFiveInstallationLoadsWithoutReplacingIdentityOrPendingProof() = runTest {
        val savedV5 = """{"installationToken":"${"i".repeat(43)}","session":{"accountID":"${guest.accountID}","accessToken":"${guest.accessToken}","expiresAtMilliseconds":${guest.expiresAtMilliseconds}},"pendingMemberID":"${member.accountID}","linkedMemberID":null,"memberAlreadyClaimedTrial":false}"""
        val store = Store(Json.decodeFromString<GuestInstallation>(savedV5)); val api = Service()
        val subject = controller(store, api)
        assertFalse(store.saved!!.serverDeferred); assertEquals(guest, store.saved!!.session)
        assertEquals(member.accountID, subject.expectedMemberID())
        assertFalse(subject.memberMaySpend(member.accountID))
        assertFalse(subject.acquire()); assertEquals(0, api.starts)
        assertEquals("i".repeat(43), store.saved!!.installationToken)
    }
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
    @Test fun exhaustedGuestCanRenewForSettlementAfterMemberIdentityWasAdded() = runTest {
        val expired = guest.copy(expiresAtMilliseconds = now - 1)
        val store = Store(GuestInstallation("i".repeat(43), expired)); val api = Service()
        val renewed = guest.copy(accessToken = "r".repeat(43))
        api.grant = GuestGrant.Available(renewed, 0, true)
        val subject = controller(store, api)
        assertEquals(renewed, subject.sessionForSettlement(guest.accountID))
        assertEquals(1, api.starts); assertEquals(0, api.links)
        assertTrue(subject.needsLink()); assertEquals(0L, subject.state.value.remainingMilliseconds)
        assertEquals(listOf("i".repeat(43)), api.installationTokens)
    }
    @Test fun settlementRenewalCannotReplaceUncertainLinkBearerOrAnotherGuest() = runTest {
        val expired = guest.copy(expiresAtMilliseconds = now - 1)
        val store = Store(GuestInstallation("i".repeat(43), expired, pendingMemberID = member.accountID))
        val api = Service(); val subject = controller(store, api)
        assertNull(subject.sessionForSettlement(guest.accountID))
        assertEquals(expired, store.saved!!.session); assertEquals(0, api.starts)
        assertNull(subject.sessionForSettlement(member.accountID)); assertEquals(0, api.starts)
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
    @Test fun pendingServerAcknowledgmentAllowsOnlyTheBoundMembersOwnMinutesAcrossRestart() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val requests = mutableListOf<String?>()
        var final = false
        val api = object : GuestMinuteService {
            override suspend fun start(installationToken: String) = error("No new trial")
            override suspend fun balance(session: AccountSession) = error("No client balance transfer")
            override suspend fun link(member: AccountSession, guestAccessToken: String) = error("Must opt in")
            override suspend fun deferLink(member: AccountSession, guestAccessToken: String?, guestAccountID: String): GuestLinkResult {
                assertEquals(guest.accountID, guestAccountID)
                requests += guestAccessToken
                return if (final) GuestLinkResult(0, true, "transferred") else GuestLinkResult(0, false, "pending", true)
            }
        }
        val subject = GuestMinuteController(store, api, { error("No new installation") }, { now })
        assertFalse(subject.memberMaySpend(member.accountID))
        assertTrue(subject.linkTo(member, allowDeferred = true))
        assertTrue(subject.memberMaySpend(member.accountID)); assertTrue(subject.needsLink())
        assertNull(subject.availableSession()); assertEquals(guest, subject.session(guest.accountID))
        assertEquals(guest, store.saved!!.session); assertEquals(member.accountID, subject.expectedMemberID())
        val restarted = GuestMinuteController(store, api, { error("No new installation") }, { now + 172_800_000 })
        val renewedMember = member.copy(expiresAtMilliseconds = now + 259_200_000)
        assertTrue(restarted.memberMaySpend(member.accountID))
        assertFalse(restarted.memberMaySpend(guest.accountID))
        assertTrue(restarted.linkTo(renewedMember, allowDeferred = true))
        assertEquals(listOf(guest.accessToken, null), requests)
        val other = renewedMember.copy(accountID = "33333333-3333-4333-8333-333333333333")
        assertFalse(restarted.linkTo(other, allowDeferred = true)); assertEquals(2, requests.size)
        assertFalse(restarted.acquire())
        final = true
        assertTrue(restarted.linkTo(renewedMember, allowDeferred = true))
        assertFalse(restarted.needsLink()); assertNull(store.saved!!.session)
        assertEquals(member.accountID, store.saved!!.linkedMemberID)
    }
    @Test fun lostDeferredAcknowledgmentRetainsGuestAndDoesNotUnlockSpendingUntilReplay() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val requests = mutableListOf<String?>()
        val api = object : GuestMinuteService {
            override suspend fun start(installationToken: String) = error("Must retain original proof")
            override suspend fun balance(session: AccountSession) = error("unused")
            override suspend fun link(member: AccountSession, guestAccessToken: String) = error("unused")
            override suspend fun deferLink(member: AccountSession, guestAccessToken: String?, guestAccountID: String): GuestLinkResult {
                requests += guestAccessToken
                if (requests.size == 1) throw AccountFailure.Unavailable
                return GuestLinkResult(0, false, "pending", true)
            }
        }
        val subject = GuestMinuteController(store, api, { error("must not replace") }, { now })
        assertFalse(subject.linkTo(member, allowDeferred = true))
        assertFalse(subject.memberMaySpend(member.accountID)); assertEquals(guest, store.saved!!.session)
        assertNull(subject.availableSession())
        assertEquals(member.accountID, subject.expectedMemberID())
        assertTrue(subject.linkTo(member, allowDeferred = true))
        assertEquals(listOf(guest.accessToken, guest.accessToken), requests)
        assertTrue(subject.memberMaySpend(member.accountID))
    }
    @Test fun deferredBindingRenewsExpiredGuestOnlyAfterDefinitiveRejectionAndKeepsOriginalGuestID() = runTest {
        val old = guest.copy(expiresAtMilliseconds = now - 1)
        val renewed = guest.copy(accessToken = "r".repeat(43))
        val store = Store(GuestInstallation("i".repeat(43), old)); val requests = mutableListOf<String?>()
        var renewals = 0
        val api = object : GuestMinuteService {
            override suspend fun start(installationToken: String): GuestGrant {
                assertEquals("i".repeat(43), installationToken); renewals++
                return GuestGrant.Available(renewed, 0, true)
            }
            override suspend fun balance(session: AccountSession) = error("unused")
            override suspend fun link(member: AccountSession, guestAccessToken: String) = error("unused")
            override suspend fun deferLink(member: AccountSession, guestAccessToken: String?, guestAccountID: String): GuestLinkResult {
                assertEquals(guest.accountID, guestAccountID); requests += guestAccessToken
                if (guestAccessToken == old.accessToken) throw AccountFailure.Http(401, "invalid_guest_session")
                return GuestLinkResult(0, false, "pending", true)
            }
        }
        val subject = GuestMinuteController(store, api, { error("No replacement installation") }, { now })
        assertTrue(subject.linkTo(member, allowDeferred = true))
        assertEquals(1, renewals); assertEquals(listOf(old.accessToken, renewed.accessToken), requests)
        assertEquals(renewed, store.saved!!.session); assertTrue(subject.memberMaySpend(member.accountID))
    }
    @Test fun cancelledDeferredTransferKeepsPinnedIdentityAndOriginalCredentials() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest))
        val api = object : GuestMinuteService {
            override suspend fun start(installationToken: String) = error("unused")
            override suspend fun balance(session: AccountSession) = error("unused")
            override suspend fun link(member: AccountSession, guestAccessToken: String) = error("unused")
            override suspend fun deferLink(member: AccountSession, guestAccessToken: String?, guestAccountID: String): GuestLinkResult = throw CancellationException("rotation")
        }
        val subject = GuestMinuteController(store, api, { error("must not replace") }, { now })
        try { subject.linkTo(member, allowDeferred = true); fail("cancelled") } catch (_: CancellationException) { }
        assertEquals(member.accountID, subject.expectedMemberID()); assertEquals(guest, store.saved!!.session)
        assertFalse(subject.memberMaySpend(member.accountID)); assertTrue(subject.needsLink())
    }
    @Test fun malformedPendingResultsCannotClaimFundingOrPretendCompletion() {
        for (values in listOf(Triple(1L,false,true), Triple(0L,true,true), Triple(0L,false,false))) {
            try { GuestLinkResult(values.first,values.second,"pending",values.third); fail("Malformed pending") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun aBareDuplicateTrialErrorCannotPretendTheGuestWasSafelyRetired() = runTest {
        val store = Store(GuestInstallation("i".repeat(43), guest)); val api = Service()
        api.failure = AccountFailure.Http(409, "trial_already_claimed")
        val subject = controller(store, api)
        assertFalse(subject.linkTo(member)); assertTrue(subject.needsLink()); assertEquals(guest, store.saved?.session)
    }
}
