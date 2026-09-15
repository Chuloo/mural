package chat.mural

import chat.mural.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MinutePurchaseMemberAccessTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val member = AccountSession(id, "a".repeat(43), 100_000)
    private fun profile(id: String = this.id) = AccountProfile(id, null, listOf("google"), "2026-09-13")
    private class Storage(var value: AccountSession?) : AccountSessionStorage {
        var reads = 0
        override suspend fun read(): AccountSession? { reads++; return value }
        override suspend fun save(session: AccountSession) { value = session }
        override suspend fun clear() { value = null }
    }
    @Test fun capabilityRequiresExplicitFlagValidEnvironmentConfigurationAndPermanentPackage() {
        assertFalse(MinutePurchaseCapability.checked(false, "test", true, "chat.mural.android").enabled)
        assertFalse(MinutePurchaseCapability.checked(true, "staging", true, "chat.mural.android").enabled)
        assertFalse(MinutePurchaseCapability.checked(true, "test", false, "chat.mural.android").enabled)
        assertFalse(MinutePurchaseCapability.checked(true, "test", true, "chat.mural.android.uitest").enabled)
        assertTrue(MinutePurchaseCapability.checked(true, "test", true, "chat.mural.android").enabled)
        assertTrue(MinutePurchaseCapability.checked(true, "live", true, "chat.mural.android", "stripe").enabled)
        assertFalse(MinutePurchaseCapability.checked(true, "live", true, "chat.mural.android", "unknown").enabled)
        assertEquals("live", MinutePurchaseCapability.checked(true, "live", true, "chat.mural.android").environment)
    }
    @Test fun noSelectedMemberNeverReadsStoredGuestOrMakesProfileRequest() = runTest {
        val storage = Storage(member); val access = MinutePurchaseMemberAccess(storage, { error("no profile request") }, now = { 1_000 })
        assertNull(access.read()); assertEquals(0, storage.reads)
    }
    @Test fun serverRejectsGuestEvenIfItsTokenWasPlacedInMemberStorage() = runTest {
        var calls = 0
        val access = MinutePurchaseMemberAccess(Storage(member), { calls++; throw AccountFailure.Http(401, "sign_in_required") }, now = { 1_000 })
        access.selectAccount(id)
        assertNull(access.read()); assertNull(access.read()); assertEquals(2, calls)
    }
    @Test fun memberProfileIsCachedOnlyForTheSameUnexpiredBearer() = runTest {
        val storage = Storage(member); var calls = 0
        val access = MinutePurchaseMemberAccess(storage, { calls++; profile() }, now = { 1_000 })
        access.selectAccount(id); assertEquals(member, access.read()); assertEquals(member, access.read()); assertEquals(1, calls)
        storage.value = member.copy(accessToken = "b".repeat(43))
        assertEquals(storage.value, access.read()); assertEquals(2, calls)
        access.selectAccount(null); assertNull(access.read())
        access.selectAccount(id); assertEquals(storage.value, access.read()); assertEquals(3, calls)
    }
    @Test fun expiredCrossAccountAndMissingSessionsAreRejectedBeforeProfile() = runTest {
        val storage = Storage(member.copy(expiresAtMilliseconds = 900))
        val access = MinutePurchaseMemberAccess(storage, { error("invalid bearer must stay local") }, now = { 1_000 })
        access.selectAccount(id); assertNull(access.read())
        storage.value = member.copy(accountID = "87654321-1234-1234-1234-123456789012"); assertNull(access.read())
        storage.value = null; assertNull(access.read())
    }
    @Test fun crossAccountProfileIsRejected() = runTest {
        val access = MinutePurchaseMemberAccess(Storage(member), { profile("87654321-1234-1234-1234-123456789012") }, now = { 1_000 })
        access.selectAccount(id)
        try { access.read(); fail("cross-account profile") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
    }
    @Test fun signOutDuringProfileCannotRestoreOldBearer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val access = MinutePurchaseMemberAccess(Storage(member), { gate.await(); profile() }, now = { 1_000 })
        access.selectAccount(id); val read = async { access.read() }; runCurrent()
        access.selectAccount(null); gate.complete(Unit)
        assertNull(read.await()); assertNull(access.read())
    }
    @Test fun rotationOrExpirationDuringProfileCannotRestoreOldBearer() = runTest {
        for (expire in listOf(false, true)) {
            val storage = Storage(member); var time = 1_000L; val gate = CompletableDeferred<Unit>()
            val access = MinutePurchaseMemberAccess(storage, { gate.await(); profile() }, now = { time })
            access.selectAccount(id); val read = async { access.read() }; runCurrent()
            if (expire) time = 100_001 else storage.value = member.copy(accessToken = "c".repeat(43))
            gate.complete(Unit); assertNull(read.await())
        }
    }
    @Test fun networkFailureNeverFallsBackToAnUnverifiedMember() = runTest {
        val access = MinutePurchaseMemberAccess(Storage(member), { throw AccountFailure.Unavailable }, now = { 1_000 })
        access.selectAccount(id)
        try { access.read(); fail("unverified member") } catch (_: MinuteCommerceFailure.Unavailable) { }
    }
}
