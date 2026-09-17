package chat.mural.core

import chat.mural.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationProvidersTest {
    private val response = APIResult("Hola", emptyList(), APIUsage(12, 4))
    private class Teacher(val action: suspend (HelperPurpose?) -> APIResult) : TeachingClient {
        override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean,
            purpose: HelperPurpose?) = action(purpose)
    }
    private fun lease(teacher: TeachingClient, close: suspend () -> Unit = {},
        status: suspend () -> HostedSessionStatus = { status("closed") }) =
        HostedConversationBindings.Lease("server-a", teacher, close, status)
    private fun status(state: String) = HostedSessionStatus("server-a", state, 600_000, 1000, 600_000, null)

    @Test fun streamWaitersShareOneFundedRequestAndOnlyOneReceivesUsage() = runTest {
        var calls = 0; var callback: ((String) -> Unit)? = null
        val finished = CompletableDeferred<APIResult>()
        val teacher = object : TeachingClient {
            override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean, purpose: HelperPurpose?): APIResult = error("Must use streaming")
            override suspend fun streamMeaning(instructions: String, input: String, onText: (String) -> Unit): APIResult {
                calls++; callback = onText; return finished.await()
            }
        }
        val controller = HostedConversationBindings(backgroundScope)
        controller.bind("local", "owner", lease(teacher))
        val firstSeen = mutableListOf<String>(); val secondSeen = mutableListOf<String>()
        val first = launch { controller.respond("local", HelperPurpose.MEANING, "same", "policy", "input", onText = { firstSeen += it }) }
        runCurrent(); callback!!("Ho"); first.cancelAndJoin()
        val second = async { controller.respond("local", HelperPurpose.MEANING, "same", "policy", "input", onText = { secondSeen += it }) }
        runCurrent(); callback!!("Hola"); finished.complete(response); runCurrent()
        assertEquals(listOf("Ho"), firstSeen); assertEquals(listOf("Ho", "Hola"), secondSeen)
        assertEquals(response.usage, second.await().usage)
        assertEquals(APIUsage(), controller.respond("local", HelperPurpose.MEANING, "same", "policy", "input", onText = {}).usage)
        assertEquals(1, calls)
    }

    @Test fun selectionNeverFallsBackBetweenPersonalKeyAndHosted() {
        val ready = HostedReadiness("owner", 1, true)
        assertTrue(ConversationProviderPolicy.canStart(ConversationProvider.PERSONAL_KEY, true, HostedReadiness()))
        assertFalse(ConversationProviderPolicy.canStart(ConversationProvider.PERSONAL_KEY, false, ready))
        assertFalse(ConversationProviderPolicy.canStart(ConversationProvider.HOSTED_MINUTES, true, HostedReadiness()))
        assertTrue(ConversationProviderPolicy.canStart(ConversationProvider.HOSTED_MINUTES, false, ready))
        for (invalid in listOf(ready.copy(accountID = null), ready.copy(availableMilliseconds = 0), ready.copy(enabled = false), ready.copy(checking = true)))
            assertFalse(ConversationProviderPolicy.canStart(ConversationProvider.HOSTED_MINUTES, true, invalid))
    }

    @Test fun acknowledgedGuestRecoveryDetachesOnlyThatOwnersLeasesAndCannotReuseThemForMember() = runTest {
        var guestCalls = 0; var memberCalls = 0; var guestCloses = 0
        val controller = HostedConversationBindings(backgroundScope)
        controller.bind("guest-local", "guest-owner", lease(Teacher { guestCalls++; response }, { guestCloses++ }, { status("incomplete") }))
        controller.bind("member-local", "member-owner", lease(Teacher { memberCalls++; response }))
        controller.delegateOwnerRecovery("guest-owner")
        assertFalse(controller.hasLease("guest-local")); assertEquals(listOf("member-local"), controller.openSessionIDs)
        try { controller.respond("guest-local", HelperPurpose.MEANING, "old", "policy", "text"); fail("Guest helper reused") }
        catch (_: HostedFailure.Unavailable) { }
        assertEquals(response, controller.respond("member-local", HelperPurpose.MEANING, "new", "policy", "text"))
        assertEquals(0, guestCalls); assertEquals(0, guestCloses); assertEquals(1, memberCalls)
    }
    @Test fun helperStaysWithCreatingLeaseAndDeduplicatesLogicalRevision() = runTest {
        var aCalls = 0; var bCalls = 0
        val controller = HostedConversationBindings(backgroundScope) { testScheduler.currentTime }
        controller.bind("local-a", "owner-a", lease(Teacher { aCalls++; response }))
        controller.bind("local-b", "owner-b", lease(Teacher { bCalls++; response.copy(text = "Bonjour") }))
        assertEquals("owner-a", controller.owner("local-a"))
        assertEquals(response, controller.respond("local-a", HelperPurpose.MEANING, "revision", "policy", "input"))
        assertEquals(APIUsage(), controller.respond("local-a", HelperPurpose.MEANING, "revision", "policy", "input").usage)
        assertEquals("Bonjour", controller.respond("local-b", HelperPurpose.MEANING, "revision", "policy", "input").text)
        assertEquals(1, aCalls); assertEquals(1, bCalls)
    }

    @Test fun cancellationOfUIWaiterCannotDuplicateUncertainOrRunningHelper() = runTest {
        var calls = 0
        val controller = HostedConversationBindings(backgroundScope) { testScheduler.currentTime }
        controller.bind("local", "owner", lease(Teacher { calls++; delay(20_000); response }))
        val waiter = launch { controller.respond("local", HelperPurpose.ASSESSMENT, "revision", "policy", "input") }
        runCurrent(); waiter.cancelAndJoin()
        val final = async { controller.respond("local", HelperPurpose.ASSESSMENT, "revision", "policy", "input") }
        advanceTimeBy(20_000); runCurrent()
        assertEquals(response, final.await()); assertEquals(1, calls)
    }

    @Test fun uncertainResponsesAndAlreadyAttemptedResponsesNeverCreateNewRequests() = runTest {
        for ((index, code) in listOf("helper_response_uncertain", "helper_request_already_attempted").withIndex()) {
            var calls = 0
            val controller = HostedConversationBindings(backgroundScope)
            controller.bind("local", "owner", lease(Teacher { calls++; throw HostedFailure.Http(if (index == 0) 502 else 409, code) }))
            repeat(3) {
                try { controller.respond("local", HelperPurpose.ASSESSMENT, "same", "policy", "input"); fail("uncertain accepted") }
                catch (failure: HostedFailure.Http) { assertEquals(code, failure.code) }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun postEndMeaningLookupAssessmentWaitForClosedAndOtherPurposesAreBlocked() = runTest {
        var reads = 0; var calls = 0; var closes = 0
        val controller = HostedConversationBindings(backgroundScope) { testScheduler.currentTime }
        controller.bind("local", "owner", lease(Teacher { calls++; response }, { closes++ }, {
            status(if (reads++ == 0) "closing" else "closed")
        }))
        controller.ended("local")
        for (purpose in listOf(HelperPurpose.MEANING, HelperPurpose.LOOKUP, HelperPurpose.ASSESSMENT))
            assertEquals(response.text, controller.respond("local", purpose, "item", "policy", "input").text)
        for (purpose in listOf(HelperPurpose.HELP, HelperPurpose.TOPIC, HelperPurpose.TYPED_REPLY, HelperPurpose.DELEGATION)) {
            try { controller.respond("local", purpose, "item", "policy", "input"); fail("late purpose accepted") }
            catch (_: HostedFailure.Unavailable) {}
        }
        assertEquals(3, calls); assertEquals(1, closes); assertEquals(2, reads)
        assertTrue(controller.openSessionIDs.isEmpty())
        advanceTimeBy(120_000)
        assertFalse(controller.canAssess("local"))
        try { controller.respond("local", HelperPurpose.MEANING, "new", "policy", "input"); fail("expired helper accepted") }
        catch (_: HostedFailure.Unavailable) {}
        assertEquals(3, calls)
    }

    @Test fun unknownSettlementKeepsAccountGuardAndCannotMakeFinalHelperCall() = runTest {
        var calls = 0
        val controller = HostedConversationBindings(backgroundScope) { testScheduler.currentTime }
        controller.bind("local", "owner", lease(Teacher { calls++; response }, status = { status("incomplete") }))
        controller.ended("local")
        try { controller.respond("local", HelperPurpose.MEANING, "revision", "policy", "input"); fail("unsettled accepted") }
        catch (_: HostedFailure.Unconfirmed) {}
        assertEquals(listOf("local"), controller.openSessionIDs); assertEquals(0, calls)
        assertEquals(8000, testScheduler.currentTime)
    }

    @Test fun consentOrAccountRevocationCancelsAndDisablesHelpers() = runTest {
        val controller = HostedConversationBindings(backgroundScope)
        var calls = 0
        controller.bind("local", "owner", lease(Teacher { calls++; response }))
        controller.disableHelpers()
        assertFalse(controller.canAssess("local"))
        try { controller.respond("local", HelperPurpose.LOOKUP, "word", "policy", "input"); fail("revoked accepted") }
        catch (_: HostedFailure.Unavailable) {}
        assertEquals(0, calls)
    }

    @Test fun noBindingNeverFallsBackToAnotherSession() = runTest {
        var calls = 0
        val controller = HostedConversationBindings(backgroundScope)
        controller.bind("other", "owner", lease(Teacher { calls++; response }))
        try { controller.respond("missing", HelperPurpose.MEANING, "revision", "policy", "input"); fail("missing lease accepted") }
        catch (_: HostedFailure.Unavailable) {}
        assertEquals(0, calls)
    }

    @Test fun historyHasExactRolesAndUTF8LimitWithoutBrokenEmoji() {
        val session = SessionRecord(languageID = "es", title = "test")
        repeat(80) { index -> session.append(Fragment(speaker = if (index % 2 == 0) Speaker.user else Speaker.assistant,
            text = "😀界".repeat(100), startMS = index * 2000, endMS = index * 2000 + 100)) }
        val history = ConversationHistory.messages(session)
        assertTrue(history.size in 1..40); assertTrue(history.toString().toByteArray(Charsets.UTF_8).size <= 6000)
        history.forEach { value ->
            val row = value.jsonObject
            assertEquals(setOf("type", "role", "content"), row.keys)
            val part = row["content"]!!.jsonArray.single().jsonObject
            assertEquals(if (row["role"] == JsonPrimitive("user")) "input_text" else "output_text", part["type"]!!.jsonPrimitive.content)
            val text = part["text"]!!.jsonPrimitive.content
            assertEquals(text, text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        }
        assertEquals("😀", ConversationHistory.utf8Prefix("😀😀", 5))
    }

    @Test fun hostedProvenanceExcludesRecoveryAfterRestartAndPassageEdits() {
        val record = SessionRecord(languageID = "es", title = "test", endedAt = nowSeconds())
        record.append(Fragment(speaker = Speaker.user, text = "Buenos días", startMS = 0, endMS = 100))
        val ticket = FinalAssessmentRecovery.enqueue(record, emptyList()).single()
        val hosted = setOf(record.id)
        assertTrue(ConversationProviderPolicy.recoveryTickets(listOf(ticket), hosted).isEmpty())
        assertTrue(ConversationProviderPolicy.enqueueRecovery(record, listOf(ticket), hosted).isEmpty())
        record.correctFragment(record.fragments.single().id, "Buenas tardes")
        assertTrue(ConversationProviderPolicy.enqueueRecovery(record, emptyList(), hosted).isEmpty())
        assertEquals(1, ConversationProviderPolicy.enqueueRecovery(record, emptyList(), emptySet()).size)
    }

    @Test fun reservedDeadlineEndsLocallyAndDeletingHistoryDiscardsHelperAccess() = runTest {
        val controller = HostedConversationBindings(backgroundScope) { testScheduler.currentTime }
        var calls = 0
        controller.bind("local", "owner", HostedConversationBindings.Lease("server-a", Teacher { calls++; response }, {},
            { status("closed") }, deadlineMilliseconds = 1000))
        assertFalse(controller.reachedDeadline("local"))
        controller.respond("local", HelperPurpose.MEANING, "revision", "policy", "input")
        advanceTimeBy(1000); assertTrue(controller.reachedDeadline("local"))
        controller.forgetLearning("local")
        try { controller.respond("local", HelperPurpose.MEANING, "revision", "policy", "input"); fail("deleted history access") }
        catch (_: HostedFailure.Unavailable) {}
        assertEquals(1, calls)
        assertEquals(listOf("local"), controller.openSessionIDs)
    }

    @Test fun oversizedAssessmentTargetIsRejectedWithoutTruncatingEvidence() {
        val session = SessionRecord(languageID = "es", title = "test")
        session.append(Fragment(speaker = Speaker.user, text = "界".repeat(9000), startMS = 0, endMS = 100))
        try { ConversationHistory.helperContext(session, session.passages.single()); fail("oversized target accepted") }
        catch (_: HostedFailure.InvalidRequest) {}
    }
    @Test fun confirmedPreAdmissionDenialsAllowRetryOfTheSameLogicalMeaning() = runTest {
        for ((status, code) in listOf(429 to "helper_session_limit", 429 to "helper_budget_exhausted",
                429 to "helper_concurrency_limit", 409 to "helper_session_window_closed", 409 to "helper_session_funding_unavailable")) {
            var calls = 0
            val controller = HostedConversationBindings(backgroundScope)
            controller.bind("local", "owner", lease(Teacher {
                calls++; if (calls == 1) throw HostedFailure.Http(status, code) else response
            }))
            try { controller.respond("local", HelperPurpose.MEANING, "final-text", "policy", "input"); fail("denial accepted") }
            catch (failure: HostedFailure.Http) { assertEquals(code, failure.code) }
            assertEquals(response, controller.respond("local", HelperPurpose.MEANING, "final-text", "policy", "input"))
            assertEquals(APIUsage(), controller.respond("local", HelperPurpose.MEANING, "final-text", "policy", "input").usage)
            assertEquals(2, calls)
        }
    }

    @Test fun discardedUIWaiterCanRetryADefinitiveRejectedAttemptButNotAnUnknownOne() = runTest {
        for (confirmed in listOf(true, false)) {
            var calls = 0
            val pending = CompletableDeferred<APIResult>()
            val controller = HostedConversationBindings(backgroundScope)
            controller.bind("local", "owner", lease(Teacher { calls++; if (calls == 1) pending.await() else response }))
            val waiter = launch { controller.respond("local", HelperPurpose.MEANING, "same", "policy", "input") }
            runCurrent(); waiter.cancelAndJoin()
            pending.completeExceptionally(HostedFailure.Http(if (confirmed) 429 else 502,
                if (confirmed) "helper_session_limit" else "helper_response_uncertain")); runCurrent()
            if (confirmed) assertEquals(response, controller.respond("local", HelperPurpose.MEANING, "same", "policy", "input"))
            else {
                try { controller.respond("local", HelperPurpose.MEANING, "same", "policy", "input"); fail("unknown repeated") }
                catch (failure: HostedFailure.Http) { assertEquals("helper_response_uncertain", failure.code) }
            }
            assertEquals(if (confirmed) 2 else 1, calls)
        }
    }

    @Test fun genericNetworkAndProviderErrorsRemainCachedAcrossRetry() = runTest {
        for (failure in listOf(java.io.IOException("unknown transport outcome"), HostedFailure.InvalidResponse,
                HostedFailure.Http(500, null), HostedFailure.Http(429, "unknown_limit"), HostedFailure.Http(502, "helper_output_incomplete"))) {
            var calls = 0
            val controller = HostedConversationBindings(backgroundScope)
            controller.bind("local", "owner", lease(Teacher { calls++; throw failure }))
            repeat(2) {
                try { controller.respond("local", HelperPurpose.MEANING, "same", "policy", "input"); fail("unknown accepted") }
                catch (error: Exception) {
                    assertEquals(failure.javaClass, error.javaClass)
                    if (failure is HostedFailure.Http) {
                        assertEquals(failure.status, (error as HostedFailure.Http).status)
                        assertEquals(failure.code, error.code)
                    }
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun automaticRetriesRequireExplicitBoundedPreAdmissionMetadata() {
        assertEquals(3000L, HostedHelperRetry.automaticDelay(HostedFailure.Http(429, "helper_session_limit", true, 3000)))
        for (failure in listOf(HostedFailure.Http(429, "helper_session_limit"),
                HostedFailure.Http(429, "helper_session_limit", false, 3000),
                HostedFailure.Http(429, "helper_session_limit", true, 99),
                HostedFailure.Http(429, "helper_session_limit", true, 60001),
                HostedFailure.Http(429, "unknown_limit", true, 3000),
                HostedFailure.Http(429, "helper_budget_exhausted", true, 3000),
                HostedFailure.Http(429, "helper_concurrency_limit", true, 3000),
                HostedFailure.Http(502, "helper_response_uncertain", true, 3000),
                HostedFailure.Http(409, "helper_request_already_attempted", true, 3000)))
            assertNull(HostedHelperRetry.automaticDelay(failure))
        assertFalse(HostedHelperRetry.canRetryAtBoundary(HostedFailure.Http(429, "helper_session_limit", false)))
    }

}
