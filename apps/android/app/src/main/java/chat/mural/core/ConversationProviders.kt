package chat.mural.core

import chat.mural.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Choice is explicit. An unavailable provider never authorizes use of the other one. */
enum class ConversationProvider { PERSONAL_KEY, HOSTED_MINUTES }

data class HostedReadiness(val accountID: String? = null, val availableMilliseconds: Long = 0,
    val enabled: Boolean = false, val checking: Boolean = false) {
    val ready get() = enabled && accountID != null && availableMilliseconds > 0 && !checking
}

object ConversationProviderPolicy {
    fun recoveryTickets(tickets: List<FinalAssessmentTicket>, hostedIDs: Set<String>) =
        tickets.filterNot { it.sessionID in hostedIDs }
    fun enqueueRecovery(session: SessionRecord, tickets: List<FinalAssessmentTicket>, hostedIDs: Set<String>): List<FinalAssessmentTicket> =
        if (session.id in hostedIDs) tickets.filterNot { it.sessionID == session.id }
        else if (session.endedAt != null) FinalAssessmentRecovery.enqueue(session, tickets) else tickets

    fun canStart(choice: ConversationProvider, hasKey: Boolean, hosted: HostedReadiness): Boolean =
        when (choice) { ConversationProvider.PERSONAL_KEY -> hasKey; ConversationProvider.HOSTED_MINUTES -> hosted.ready }
}

/** The same short, valid message history can be passed to either voice provider. */
object ConversationHistory {
    fun messages(session: SessionRecord?): JsonArray {
        val result = mutableListOf<JsonElement>()
        for (passage in session?.passages.orEmpty().takeLast(40).asReversed()) {
            if (passage.text.isBlank()) continue
            val text = utf8Prefix(passage.text, 4_500)
            val item = buildJsonObject {
                put("type", "message"); put("role", passage.speaker.name)
                putJsonArray("content") { add(buildJsonObject {
                    put("type", if (passage.speaker == Speaker.user) "input_text" else "output_text"); put("text", text)
                }) }
            }
            val candidate = listOf(item) + result
            if (JsonArray(candidate).toString().toByteArray(Charsets.UTF_8).size > 6_000) break
            result.add(0, item)
        }
        return JsonArray(result)
    }

    /** Preserves the target passage and its evidence IDs instead of cutting through an assessment. */
    fun helperContext(session: SessionRecord, passage: Passage? = null): String {
        var snapshot = session.copy(fragments = session.fragments.toMutableList())
        while (true) {
            val context = TeachingPolicy.context(snapshot, passage)
            if (context.toByteArray(Charsets.UTF_8).size <= 24_000) return context
            val first = snapshot.passages.firstOrNull() ?: throw HostedFailure.InvalidRequest
            if (snapshot.passages.size <= 1 || first.id == passage?.id) throw HostedFailure.InvalidRequest
            val removeIDs = first.fragments.map { it.id }.toSet()
            snapshot = snapshot.copy(fragments = snapshot.fragments.filterNot { it.id in removeIDs }.toMutableList())
        }
    }

    internal fun utf8Prefix(text: String, bytes: Int): String {
        val output = StringBuilder(); var used = 0; var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            if (point in 0xD800..0xDFFF) { index++; continue }
            val part = String(Character.toChars(point)); val size = part.toByteArray(Charsets.UTF_8).size
            if (used + size > bytes) break
            output.append(part); used += size; index += Character.charCount(point)
        }
        return output.toString()
    }
}

/** These exact responses are emitted before a provider request is admitted. Unknown outcomes stay one-shot. */
object HostedHelperRetry {
    fun canRetryAtBoundary(error: Throwable): Boolean = isConfirmedNotAdmitted(error) &&
        (error as HostedFailure.Http).retryable != false

    fun automaticDelay(error: Throwable): Long? {
        if (!isConfirmedNotAdmitted(error)) return null
        val failure = error as HostedFailure.Http
        return failure.retryAfterMilliseconds?.takeIf { failure.status == 429 && failure.code == "helper_session_limit" && failure.retryable == true && it in 1000..60_000 }
    }

    fun isConfirmedNotAdmitted(error: Throwable): Boolean = error is HostedFailure.Http && when (error.status) {
        429 -> error.code in setOf("helper_session_limit", "helper_concurrency_limit", "helper_budget_exhausted")
        409 -> error.code in setOf("helper_session_window_closed", "helper_session_funding_unavailable")
        else -> false
    }
}

/** Main-dispatcher confined. No transcript or helper response is persisted by this controller. */
class HostedConversationBindings(private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    class Lease(val serverID: String, val teaching: TeachingClient, val close: suspend () -> Unit,
        val status: suspend () -> HostedSessionStatus, val deadlineMilliseconds: Long = Long.MAX_VALUE)
    private class Attempt(val result: Deferred<APIResult>, var usageDelivered: Boolean = false)
    private class Binding(val ownerID: String, val lease: Lease) {
        var endedAt: Long? = null
        var confirmedClosed = false
        var disabled = false
        val attempts = mutableMapOf<String, Attempt>()
    }
    private val helpersScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val bindings = mutableMapOf<String, Binding>()
    val openSessionIDs get() = bindings.filterValues { !it.confirmedClosed }.keys.toList()

    fun bind(localID: String, ownerID: String, lease: Lease) {
        prune()
        check(localID !in bindings && bindings.size < 16)
        bindings[localID] = Binding(ownerID, lease)
    }
    /** Only after an authenticated server acknowledgment accepts this owner's recovery. */
    fun delegateOwnerRecovery(ownerID: String) {
        bindings.filterValues { it.ownerID == ownerID }.keys.toList().forEach { id ->
            bindings.remove(id)?.attempts?.values?.forEach { it.result.cancel() }
        }
    }
    fun hasLease(localID: String) = localID in bindings
    fun owner(localID: String) = bindings[localID]?.ownerID
    fun reachedDeadline(localID: String) = bindings[localID]?.let { now() >= it.lease.deadlineMilliseconds } == true
    fun ended(localID: String) { bindings[localID]?.let { if (it.endedAt == null) it.endedAt = now() } }
    fun disableHelpers() { bindings.values.forEach { it.disabled = true; it.attempts.values.forEach { attempt -> attempt.result.cancel() } } }
    fun forgetLearning(localID: String) {
        bindings[localID]?.let { binding ->
            binding.disabled = true
            binding.attempts.values.forEach { it.result.cancel() }
            binding.attempts.clear()
        }
    }
    fun canAssess(localID: String): Boolean = bindings[localID]?.let {
        !it.disabled && (it.endedAt?.let { ended -> now() - ended in 0 until POST_END_MILLIS } ?: true)
    } == true

    /** Each logical automatic request owns one deferred result, including an uncertain failure.
     * Cancelling a UI waiter cannot create a second provider bill or discard the request identity. */
    suspend fun respond(localID: String, purpose: HelperPurpose, logicalID: String,
        instructions: String, input: String, schema: JsonObject? = null, search: Boolean = false): APIResult {
        prune()
        val binding = bindings[localID] ?: throw HostedFailure.Unavailable
        if (binding.disabled) throw HostedFailure.Unavailable
        val key = "${purpose.wireValue}:$logicalID"
        binding.attempts[key]?.let {
            try { return deliver(binding, key, it) }
            catch (failure: Exception) { if (!HostedHelperRetry.isConfirmedNotAdmitted(failure)) throw failure }
        }
        if (binding.attempts.size >= 128) throw HostedFailure.Unavailable
        val request = helpersScope.async(start = CoroutineStart.LAZY) {
            val ended = binding.endedAt
            if (ended != null) {
                if (purpose !in POST_END_PURPOSES || now() - ended !in 0 until POST_END_MILLIS) throw HostedFailure.Unavailable
                if (!closeAndConfirm(localID)) throw HostedFailure.Unconfirmed
                if (now() - ended !in 0 until POST_END_MILLIS) throw HostedFailure.Unavailable
            }
            binding.lease.teaching.respond(instructions, input, schema, search, purpose)
        }
        val attempt = Attempt(request)
        binding.attempts[key] = attempt
        request.start()
        return deliver(binding, key, attempt)
    }

    private suspend fun deliver(binding: Binding, key: String, attempt: Attempt): APIResult {
        val result = try { attempt.result.await() }
        catch (failure: Exception) {
            // A concurrent waiter must not remove a later successful retry for this logical request.
            if (HostedHelperRetry.isConfirmedNotAdmitted(failure) && binding.attempts[key] === attempt)
                binding.attempts.remove(key)
            throw failure
        }
        return if (attempt.usageDelivered) result.copy(usage = APIUsage())
        else { attempt.usageDelivered = true; result }
    }

    /** Reads may repeat; the lease itself makes cutoff one-shot. Unknown settlement stays blocked. */
    suspend fun closeAndConfirm(localID: String): Boolean {
        val binding = bindings[localID] ?: return false
        if (binding.confirmedClosed) return true
        ended(localID)
        return withTimeoutOrNull(8_000) {
            try { binding.lease.close() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            while (true) {
                val status = try { binding.lease.status() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { return@withTimeoutOrNull false }
                if (status.sessionID != binding.lease.serverID) return@withTimeoutOrNull false
                if (status.state == "closed") { binding.confirmedClosed = true; return@withTimeoutOrNull true }
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    }

    fun prune() {
        val expired = bindings.filterValues { it.confirmedClosed && it.endedAt?.let { ended -> now() - ended >= POST_END_MILLIS } == true }.keys
        expired.forEach { id -> bindings.remove(id)?.attempts?.values?.forEach { it.result.cancel() } }
    }
    companion object {
        const val POST_END_MILLIS = 120_000L
        private val POST_END_PURPOSES = setOf(HelperPurpose.MEANING, HelperPurpose.LOOKUP, HelperPurpose.ASSESSMENT)
    }
}
