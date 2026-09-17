package chat.mural

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.mural.core.*
import chat.mural.network.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Maps a network/credential failure reason to its user-facing string resource, or 0 if unmapped. */
@StringRes
internal fun errorMessageRes(e: Throwable): Int = when (e) {
    is HostedFailure -> hostedErrorMessageRes(e)
    is AccountFailure.Http -> when (e.status) {
        401 -> R.string.hosted_sign_in_again
        429 -> R.string.hosted_rate_limit
        else -> R.string.hosted_request_failed
    }
    AccountFailure.Unavailable -> R.string.account_error_connection
    is APIClient.APIException.MissingKey -> R.string.error_missing_key
    is APIClient.APIException.Refused -> R.string.error_request_refused
    is APIClient.APIException.InvalidResponse, is APIClient.APIException.Incomplete -> R.string.error_incomplete_response
    is APIClient.APIException.Http -> when (e.kind) {
        ProviderFailureKind.authentication -> R.string.error_http_401
        ProviderFailureKind.modelAccess -> R.string.error_http_403_404
        ProviderFailureKind.quota -> R.string.error_provider_quota
        ProviderFailureKind.rateLimit -> R.string.error_http_429
        ProviderFailureKind.unavailable -> R.string.error_provider_unavailable
        ProviderFailureKind.invalidRequest -> R.string.error_provider_request
        ProviderFailureKind.unknown -> R.string.error_http_generic
    }
    is java.net.SocketTimeoutException -> R.string.error_request_timeout
    is java.net.UnknownHostException, is java.net.ConnectException -> R.string.error_request_connection
    is CredentialStore.CredentialException.Invalid -> R.string.error_key_invalid
    is CredentialStore.CredentialException.Save -> R.string.error_key_save
    is CredentialStore.CredentialException.Remove -> R.string.error_key_remove
    else -> 0
}

@StringRes
internal fun hostedErrorMessageRes(failure: HostedFailure): Int = when (failure) {
    HostedFailure.SignInRequired -> R.string.hosted_sign_in_again
    HostedFailure.Unconfirmed -> R.string.hosted_unconfirmed
    HostedFailure.Unavailable -> R.string.hosted_unavailable
    is HostedFailure.Http -> when {
        needsAccountRecovery(failure) -> R.string.hosted_sign_in_again
        failure.code in setOf("insufficient_minutes", "insufficient_credit") -> R.string.hosted_no_minutes
        failure.code == "helper_session_limit" && failure.retryable == true -> R.string.hosted_help_busy
        failure.code == "helper_concurrency_limit" -> R.string.hosted_help_busy
        failure.code in setOf("helper_budget_exhausted", "helper_session_limit") -> R.string.hosted_extra_help_limit
        failure.code == "helper_output_refused" -> R.string.error_request_refused
        failure.code == "helper_output_incomplete" -> R.string.error_incomplete_response
        failure.code == "provider_connection_lost" -> R.string.error_transport_network_lost
        failure.code == "helper_session_funding_unavailable" -> R.string.hosted_help_funding
        failure.code in setOf("provider_reconciliation_required", "minute_balance_reconciliation_required", "minute_purchase_reconciliation_required", "helper_provider_reconciliation_required") -> R.string.hosted_balance_checking
        failure.code == "helper_session_window_closed" -> R.string.hosted_window_closed
        failure.code in setOf("helper_request_already_attempted", "helper_response_uncertain", "live_request_already_created", "provider_session_unconfirmed") -> R.string.hosted_unconfirmed
        failure.code == "live_session_unresolved" -> R.string.hosted_checking_previous
        failure.code == "provider_create_rejected" -> R.string.hosted_start_rejected
        failure.status == 429 -> R.string.hosted_rate_limit
        failure.code in setOf("hosted_voice_not_ready", "hosted_helpers_not_ready", "hosted_paid_not_ready", "hosted_funding_cap_reached") -> R.string.hosted_unavailable
        else -> R.string.hosted_request_failed
    }
    else -> R.string.hosted_request_failed
}

internal fun needsAccountRecovery(error: Throwable): Boolean = error == HostedFailure.SignInRequired ||
    (error is HostedFailure.Http && (error.status == 401 || error.code == "sign_in_to_continue")) ||
    (error is AccountFailure.Http && error.status == 401)

internal fun requestErrorReference(error: Throwable): String? = safeRequestErrorReference(when (error) {
    is HostedFailure.Http -> error.reference
    is AccountFailure.Http -> error.reference
    else -> null
})

/** Whether the failure means the learner needs to add or fix their OpenAI key in Settings. */
internal fun errorNeedsKeySetup(e: Throwable): Boolean =
    e is APIClient.APIException.MissingKey || (e is APIClient.APIException.Http && e.status == 401)

/** Imported partial conversations are history, not local sessions awaiting cloud recovery. */
internal fun prepareImportedArchive(data: String, importedAt: Double = nowSeconds()): Archive =
    ArchiveCodec.decode(data).also { imported ->
        imported.sessions.filter { it.endedAt == null }.forEach {
            it.endedAt = importedAt
            it.endReason = "Imported unfinished conversation"
        }
    }

class MuralViewModel(application: Application) : AndroidViewModel(application) {
    // Keep intent, not an Activity-capturing callback, while the learner reviews consent.
    var pendingCloudAction: CloudAction? = null
    var archive by mutableStateOf(Archive()); private set
    var loadingHistory by mutableStateOf(true); private set
    var session by mutableStateOf<SessionRecord?>(null); private set
    var state by mutableStateOf("idle"); private set
    var error by mutableStateOf<String?>(null); private set
    var errorNeedsKeySetup by mutableStateOf(false); private set
    var errorNeedsAccountSignIn by mutableStateOf(false); private set
    var typedReplyError by mutableStateOf<String?>(null); private set
    var typedRepliesSent by mutableStateOf(0); private set
    fun clearTypedReplyError() { typedReplyError = null }
    var notice by mutableStateOf<String?>(null); private set
    var meaning by mutableStateOf(""); private set
    var translating by mutableStateOf(false); private set
    var meaningFailed by mutableStateOf(false); private set
    var meaningLimitReached by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    var isMuted by mutableStateOf(false); private set
    var inputLevel by mutableStateOf(0.0); private set
    var outputLevel by mutableStateOf(0.0); private set
    var selectedTheme by mutableStateOf<ConversationTheme?>(null); private set
    var lookupResult by mutableStateOf<String?>(null); private set
    var lookupError by mutableStateOf<String?>(null); private set
    var lookupLoading by mutableStateOf(false); private set
    private var lookupGeneration = 0L
    private var lookupJob: Job? = null
    var topicResult by mutableStateOf<TopicBrief?>(null); private set
    var hasKey by mutableStateOf(false); private set
    var endpoint by mutableStateOf(CustomEndpoint()); private set
    /** A personal conversation goes to the enabled custom endpoint, otherwise to OpenAI with the saved key. */
    val personalReady get() = if (endpoint.enabled) endpoint.textReady else hasKey
    val language get() = LanguageRegistry.get(archive.preferences.learningLanguageID)!!
    val learner get() = LearningEngine.project(archive.sessions, language.id, archive.preferences.hiddenWords)
    val isRunning get() = state in listOf("connecting", "active", "closing")
    val isVoiceSession get() = voiceSession

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val repository = LearningRepository(application)
    private val credentials = CredentialStore(application)
    private val endpoints = CustomEndpointStore(application)
    private val api = APIClient {
        endpoint.let { if (it.enabled) it.target(endpoints.credentials.read()) else APIClient.openAI(credentials.read()) }
    }
    private val liveTransport = LiveTransport(application, viewModelScope)
    private val turnTransport = TurnTransport(application, viewModelScope)
    private var transport: VoiceTransport = liveTransport
    private val providerStore = ConversationProviderStore(application)
    private val hostedConfiguration = HostedConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)
    private val accountConfiguration = ManagedAccountConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN, BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    private val memberSessions = accountConfiguration?.let { AccountSessionStore(application, it.origin.toString()) }
    private val guests = hostedConfiguration?.let { config ->
        GuestMinuteController(GuestInstallationStore(application, config.origin.toString()), GuestMinuteClient(config), {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        })
    }
    var guestState by mutableStateOf(GuestMinuteState()); private set
    var showMinuteAccess by mutableStateOf(false); private set
    private var selectedAccount = AccountState(busy = true)
    private var accessJob: Job? = null
    private val hostedBindings = HostedConversationBindings(viewModelScope)
    private var hostedSessionIDs = emptySet<String>()
    private var pendingHostedOwnerID: String? = null
    /** Reauthentication may renew this account only; another account cannot replace an unresolved owner. */
    suspend fun pendingMemberForSignIn(): String? {
        clearAcknowledgedGuestMarker()
        guests?.expectedMemberID()?.let { return it }
        val pending = pendingHostedOwnerID ?: return null
        if (guests?.owns(pending) != true) return pending
        // Renewal retains the same guest identity, allowing an interrupted conversation to settle
        // before Google login. A guest UUID is never sent as Google's expected member identity.
        if (guests.session(pending) == null) guests.acquire()
        return null
    }
    private var reconciliationJob: Job? = null
    var conversationProvider by mutableStateOf(ConversationProvider.PERSONAL_KEY); private set
    var hostedReadiness by mutableStateOf(HostedReadiness()); private set
    private val readiness = HostedReadinessController(viewModelScope,
        readSession = { availableHostedOwner() },
        fetch = { owner ->
            val enabled = hostedClient(owner.accountID).available()
            val balance = if (enabled) hostedBalance(owner) else null
            HostedReadiness(owner.accountID, balance?.readinessMilliseconds ?: 0, enabled)
        }, changed = { hostedReadiness = it })
    var accountChangeBlocked by mutableStateOf(true); private set

    private val reports = ReportController(viewModelScope,
        ReportConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)?.let(::ReportClient))
    val reportState = reports.state

    fun reportUtterance(sessionID: String, passageID: String) {
        val selectedSession = session?.takeIf { it.id == sessionID }
            ?: archive.sessions.firstOrNull { it.id == sessionID } ?: return
        ReportSelection.from(selectedSession, passageID)?.let(reports::open)
    }
    fun submitReport(report: AIReportSubmission) = reports.submit(report)
    fun dismissReport() = reports.dismiss()

    private data class SaveRequest(val snapshot: LearningSnapshot, val completion: CompletableDeferred<Unit>? = null)
    private val writes = Channel<SaveRequest>(Channel.UNLIMITED)
    private var storageReady = false
    private var finalAssessmentTickets: List<FinalAssessmentTicket> = emptyList()
    private val recoveredAssessmentIDs = mutableSetOf<String>()
    private val hostedFinalAssessmentJobs = mutableMapOf<String, Job>()
    private var connectionJob: Job? = null
    private var durationJob: Job? = null
    private var closeJob: Job? = null
    private var resetJob: Job? = null
    private var assessmentJob: Job? = null
    private var actionJob: Job? = null
    private val meanings = MeaningController(viewModelScope, canRetryFailure = HostedHelperRetry::canRetryAtBoundary,
        retryDelay = HostedHelperRetry::automaticDelay, stream = { request, onText -> translateMeaning(request, onText) }) { request -> translateMeaning(request) }
    private suspend fun translateMeaning(request: MeaningRequest, onText: ((String) -> Unit)? = null): MeaningResult {
        if (archive.preferences.aiConsentVersion != 1) throw IllegalStateException("AI processing consent is required.")
        val module = LanguageRegistry.get(request.learningLanguageID) ?: throw IllegalStateException("Unsupported language.")
        if (request.sessionID in hostedSessionIDs && request.translationInput.toByteArray(Charsets.UTF_8).size > 24_576) throw MeaningInputLimitException()
        val result = teaching(request.sessionID, HelperPurpose.MEANING, request.cacheKey,
            TeachingPolicy.translation(module, request.meaningLanguage), request.translationInput, onText = onText)
        return MeaningResult(result.text, result.usage.input, result.usage.output)
    }
    private val finalAssessments = FinalAssessmentQueue(viewModelScope) { snapshot, passage -> requestAssessment(snapshot, passage) }
    private val languageDetector = LanguageDetector(application)
    private var languageCheckJob: Job? = null
    private var lastLanguageRedirect: String? = null
    private val delegations = mutableMapOf<String, Job>()
    private var voiceSession = false
    private var activity = ConversationActivity(activityNow())
    private var conversationPace = ConversationPace()
    var inactivitySeconds by mutableStateOf<Int?>(null); private set
    private fun activityNow() = System.nanoTime() / 1_000_000_000.0
    fun noteTypingActivity() { if (state == "active" && voiceSession) { activity.typing(activityNow()); inactivitySeconds = null } }
    private var generation = 0

    init {
        guests?.let { controller -> viewModelScope.launch { controller.state.collect { guestState = it } } }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { Triple(repository.load(), credentials.hasKey, endpoints.read()) }
                archive = loaded.first.archive
                endpoint = loaded.third
                val personal = loaded.second || endpoint.enabled
                val providers = providerStore.read(if (personal) ConversationProvider.PERSONAL_KEY else ConversationProvider.HOSTED_MINUTES)
                hostedSessionIDs = providers.hostedIDs
                pendingHostedOwnerID = providers.pendingOwnerID
                accountChangeBlocked = providers.pendingOwnerID != null
                guests?.recoverAcknowledgedOwnerAtStartup(pendingHostedOwnerID,
                    clear = { owner -> clearAcknowledgedGuestMarker(owner) },
                    onFailure = { presentError(getApplication<Application>().getString(R.string.error_guest_secure_storage_unavailable)) })
                conversationProvider = providers.selection
                finalAssessmentTickets = ConversationProviderPolicy.recoveryTickets(loaded.first.finalAssessments, hostedSessionIDs)
                hasKey = loaded.second
                storageReady = true
                recoverFinalAssessments()
                refreshHostedReadiness()
                if (accountChangeBlocked) reconcileHostedSessions()
            } catch (_: CancellationException) { }
            catch (_: Exception) {
                presentError(getApplication<Application>().getString(R.string.error_local_history_unavailable))
            } finally { loadingHistory = false }
        }
        viewModelScope.launch {
            for (first in writes) {
                var latest = first
                val completions = mutableListOf<CompletableDeferred<Unit>>()
                first.completion?.let(completions::add)
                // Coalesce transcript deltas while preserving every requested durability barrier.
                while (true) {
                    val next = writes.tryReceive().getOrNull() ?: break
                    latest = next
                    next.completion?.let(completions::add)
                }
                try {
                    withContext(Dispatchers.IO) { repository.save(latest.snapshot) }
                    completions.forEach { it.complete(Unit) }
                } catch (error: Exception) {
                    completions.forEach { it.completeExceptionally(error) }
                    if (error is CancellationException) throw error
                    presentError(getApplication<Application>().getString(R.string.error_save_progress_failed))
                }
            }
        }
        for (voice in listOf(liveTransport, turnTransport)) {
            voice.onEvent = { event ->
                try { handle(event) }
                catch (_: IllegalArgumentException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
                catch (_: IllegalStateException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
            }
            voice.onFailure = { fail(it) }
            voice.onLevels = { input, output ->
                inputLevel = input; outputLevel = output
                if (state == "active" && voiceSession) {
                    if (input > 0.03) activity.inputActive(activityNow())
                    if (output > 0.03) activity.assistantActive(activityNow())
                }
            }
        }
        // One failed turn keeps the conversation open; a rejected key or unknown model ends it.
        turnTransport.onError = { error, fatal ->
            if (fatal) fail(error, R.string.error_voice_connect_failed)
            else notice = resolveMessage(error, R.string.error_send_message_failed)
        }
        meanings.onChange = { meaning = meanings.text; translating = meanings.isLoading; meaningFailed = meanings.error != null; meaningLimitReached = meanings.error is MeaningInputLimitException }
        meanings.onResult = { request, result ->
            if (session?.id == request.sessionID) updateSession {
                it.translations[request.cacheKey] = result.text
                addUsage(it, APIUsage(result.inputTokens, result.outputTokens))
            }
        }
        finalAssessments.beforeAssessment = { snapshot, passage ->
            val current = archive.sessions.firstOrNull { it.id == snapshot.id }
            val ticket = finalAssessmentTickets.firstOrNull { it.matches(snapshot, passage) }
            if (snapshot.id in hostedSessionIDs) false
            else if (!storageReady || !personalReady || archive.preferences.aiConsentVersion != 1 || current == null ||
                ticket == null || !FinalAssessmentRecovery.canAttempt(ticket, current, nowSeconds())) false
            else {
                finalAssessmentTickets = finalAssessmentTickets.map {
                    if (it == ticket) it.copy(attempts = it.attempts + 1, lastAttemptAt = nowSeconds()) else it
                }
                persistAndWait()
                true
            }
        }
        finalAssessments.onResult = { result ->
            result.applying(archive.sessions.firstOrNull { it.id == result.sessionID })?.let { updated ->
                save(updated)
                if (session?.id == updated.id) session = clone(updated)
                persistAndWait()
            }
        }
    }

    private fun recoverFinalAssessments() {
        if (!storageReady || !personalReady || archive.preferences.aiConsentVersion != 1) return
        val remaining = FinalAssessmentRecovery.MAX_RECOVERED_PER_LAUNCH - recoveredAssessmentIDs.size
        if (remaining <= 0) return
        FinalAssessmentRecovery.recover(archive.sessions, finalAssessmentTickets, nowSeconds())
            .filterNot { it.id in recoveredAssessmentIDs || it.id in hostedSessionIDs }.take(remaining)
            .forEach { if (finalAssessments.submit(clone(it))) recoveredAssessmentIDs += it.id }
    }

    private fun clone(s: SessionRecord): SessionRecord = s.copy(
        fragments = s.fragments.map { it.copy(previousTexts = it.previousTexts.toList()) }.toMutableList(),
        assessments = s.assessments.map { it.copy(words = it.words.toList()) }.toMutableList(),
        translations = s.translations.toMutableMap(),
        topics = s.topics.map { it.copy(sources = it.sources.toList()) }.toMutableList(),
    )
    private fun persistenceSnapshot() = LearningSnapshot(
        archive.copy(sessions = archive.sessions.toMutableList(), preferences = archive.preferences.copy()),
        finalAssessmentTickets.toList(),
    )
    private fun persist() {
        if (storageReady) writes.trySend(SaveRequest(persistenceSnapshot()))
    }
    private suspend fun persistAndWait() {
        check(storageReady)
        val completion = CompletableDeferred<Unit>()
        writes.send(SaveRequest(persistenceSnapshot(), completion))
        completion.await()
    }
    private fun save(s: SessionRecord) {
        finalAssessmentTickets = ConversationProviderPolicy.enqueueRecovery(s, finalAssessmentTickets, hostedSessionIDs)
        archive = archive.copy(sessions = (archive.sessions.filterNot { it.id == s.id } + clone(s)).toMutableList())
        persist()
    }
    private fun updateSession(change: (SessionRecord) -> Unit) {
        val current = session ?: return
        val next = clone(current); change(next); session = next; save(next)
    }
    private fun resolveMessage(e: Throwable, @StringRes fallback: Int): String {
        val app = getApplication<Application>()
        val res = errorMessageRes(e)
        val message = when {
            // Hosted failures have their own type, so a provider HTTP failure here came from the enabled endpoint.
            endpoint.enabled && e is APIClient.APIException.Http -> app.getString(R.string.error_endpoint_http, e.status)
            endpoint.enabled && (e is APIClient.APIException.InvalidResponse || e is APIClient.APIException.Incomplete) ->
                app.getString(R.string.error_endpoint_incomplete)
            res == R.string.error_http_generic && e is APIClient.APIException.Http -> app.getString(res, e.status)
            res != 0 -> app.getString(res)
            e is LiveTransport.TransportException -> e.message ?: app.getString(fallback)
            else -> app.getString(fallback)
        }
        if (e is APIClient.APIException.Http && e.reference != null)
            return message + "\n\n" + app.getString(if (endpoint.enabled) R.string.error_endpoint_reference else R.string.error_provider_reference, e.reference)
        return requestErrorReference(e)?.let { message + "\n\n" + app.getString(R.string.hosted_error_reference, it) } ?: message
    }
    private fun presentError(message: String, needsKeySetup: Boolean = false) {
        error = message; errorNeedsKeySetup = needsKeySetup; errorNeedsAccountSignIn = false
    }
    private fun presentError(e: Throwable, @StringRes fallback: Int) {
        presentError(resolveMessage(e, fallback), errorNeedsKeySetup(e))
        errorNeedsAccountSignIn = needsAccountRecovery(e)
    }
    private fun cloudReady(): Boolean {
        if (!storageReady) { presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first)); return false }
        if (archive.preferences.aiConsentVersion != 1) {
            presentError(getApplication<Application>().getString(R.string.error_accept_ai_consent)); return false
        }
        val currentHosted = session?.id in hostedSessionIDs
        if (!currentHosted && conversationProvider == ConversationProvider.PERSONAL_KEY && !personalReady) {
            presentError(getApplication<Application>().getString(R.string.error_missing_key), needsKeySetup = true); return false
        }
        return true
    }
    /** Called by the minutes/account UI after an explicit provider choice. No failure changes it. */
    fun selectConversationProvider(provider: ConversationProvider) {
        if (isRunning || !storageReady) return
        conversationProvider = provider
        viewModelScope.launch {
            try { providerStore.select(provider) }
            catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.provider_preference_save_failed)) }
        }
        if (provider == ConversationProvider.HOSTED_MINUTES) refreshHostedReadiness()
    }

    fun onAccountChanged(account: AccountState) {
        selectedAccount = account
        // Invalidate immediately. Slow guest or member reads may not restore access during a transition.
        readiness.selectAccount(null, true)
        accessJob?.cancel()
        if (!account.busy) refreshHostedReadiness()
    }
    fun refreshHostedReadiness() {
        if (selectedAccount.busy || !storageReady) return
        accessJob?.cancel()
        accessJob = viewModelScope.launch {
            try {
                val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
                if (member != null) {
                    clearAcknowledgedGuestMarker()
                    if (guests?.needsLink() == true) {
                        readiness.selectAccount(null, true)
                        if (!completeGuestSignIn() && guests.memberMaySpend(member.accountID) != true) return@launch
                    }
                    if (selectedAccount.busy) return@launch
                    readiness.selectAccount(member.accountID, false)
                    readiness.refresh()
                } else {
                    if (archive.preferences.aiConsentVersion != 1 || conversationProvider != ConversationProvider.HOSTED_MINUTES) {
                        readiness.selectAccount(null, false); return@launch
                    }
                    guests?.acquire()
                    if (selectedAccount.busy) return@launch
                    val guest = guests?.availableSession()
                    readiness.selectAccount(guest?.accountID, false)
                    readiness.refresh()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readiness.selectAccount(null, false) }
        }
    }
    fun dismissMinuteAccess() { showMinuteAccess = false }
    fun needsMinuteAccess(): Boolean {
        if (conversationProvider != ConversationProvider.HOSTED_MINUTES || isRunning) return false
        if (hostedReadiness.ready && !selectedAccount.busy) return false
        showMinuteAccess = true; refreshHostedReadiness(); return true
    }
    suspend fun prepareGuestCustodyForDeletion(memberID: String) {
        try { guests?.retireAcknowledgedLinkForDeletion(memberID) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw AccountFailure.SecureStorage }
    }

    /** Two encrypted stores are not crash-atomic. The durable guest receipt repairs only its marker. */
    private suspend fun clearAcknowledgedGuestMarker() {
        guests?.recoverAcknowledgedOwner(pendingHostedOwnerID) { owner -> clearAcknowledgedGuestMarker(owner) }
    }
    private suspend fun clearAcknowledgedGuestMarker(owner: String) {
        providerStore.clearPending()
        pendingHostedOwnerID = null; accountChangeBlocked = false
        hostedBindings.delegateOwnerRecovery(owner)
    }

    /** Member spending requires a durable server acknowledgment; guest transfer may finish later. */
    suspend fun completeGuestSignIn(): Boolean {
        val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) } ?: return false
        val guestOwner = guests?.retainedOwnerID()
        if (guestOwner != null && pendingHostedOwnerID == guestOwner) {
            if (isRunning) return false
            // Finish pending local provenance writes before handing remote recovery to the server.
            connectionJob?.join()
            reconciliationJob?.cancelAndJoin()
        }
        val accepted = guests?.linkTo(member, allowDeferred = true) ?: true
        val safe = accepted || guests?.memberMaySpend(member.accountID) == true
        if (safe && guestOwner != null && pendingHostedOwnerID == guestOwner) {
            // The retained GuestInstallation still owns its bearer and acknowledged member binding.
            // Server recovery owns the old lease; never close it with the new member bearer.
            withContext(NonCancellable) {
                providerStore.clearPending()
                pendingHostedOwnerID = null; accountChangeBlocked = false
                hostedBindings.delegateOwnerRecovery(guestOwner)
            }
        }
        if (!safe) showMinuteAccess = true
        return safe
    }
    private suspend fun availableHostedOwner(): AccountSession? {
        val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
        if (member != null) return member.takeIf { guests?.needsLink() != true || guests?.memberMaySpend(member.accountID) == true }
        return guests?.availableSession()
    }
    private suspend fun requireHostedOwner(ownerID: String? = null): AccountSession {
        // The pending guest lease must still be closable after Google has stored a member bearer.
        val guest = if (ownerID != null) guests?.sessionForSettlement(ownerID) else null
        return guest ?: availableHostedOwner()?.takeIf { ownerID == null || it.accountID == ownerID }
            ?: throw HostedFailure.SignInRequired
    }
    private suspend fun hostedBalance(owner: AccountSession): MinuteBalance =
        GuestMinuteClient(hostedConfiguration ?: throw HostedFailure.Unavailable).balance(owner)

    private fun hostedClient(ownerID: String): HostedAPIClient {
        val config = hostedConfiguration ?: throw HostedFailure.Unavailable
        return HostedAPIClient(config.origin, { requireHostedOwner(ownerID) }, okhttp3.OkHttpClient())
    }
    private fun binding(lease: HostedAPIClient.HostedLease) = HostedConversationBindings.Lease(
        lease.sessionID, lease.teaching, lease::requestClose, lease::status, lease.deadlineMilliseconds)

    /** The creating session, rather than the currently selected settings option, chooses every helper. */
    private suspend fun teaching(localID: String?, purpose: HelperPurpose, logicalID: String,
        instructions: String, input: String, schema: JsonObject? = null, search: Boolean = false, onText: ((String) -> Unit)? = null): APIResult {
        if (archive.preferences.aiConsentVersion != 1) throw HostedFailure.Unavailable
        if (localID != null && localID in hostedSessionIDs) {
            return hostedBindings.respond(localID, purpose, logicalID, instructions, input, schema, search, onText)
        }
        if (localID == null && conversationProvider == ConversationProvider.HOSTED_MINUTES) throw HostedFailure.Unavailable
        return if (onText != null && purpose == HelperPurpose.MEANING) api.streamMeaning(instructions, input, onText)
        else api.respond(instructions, input, schema, search, purpose)
    }
    private fun helperContext(snapshot: SessionRecord, passage: Passage? = null): String =
        if (snapshot.id in hostedSessionIDs) ConversationHistory.helperContext(snapshot, passage)
        else TeachingPolicy.context(snapshot, passage)

    /** Renewing a member token must not turn a retained guest transfer back into an OAuth gate. */
    suspend fun settleRenewedMember(): Boolean {
        val pending = pendingHostedOwnerID ?: return true
        if (guests?.owns(pending) == true) return true
        return prepareForAccountChange()
    }

    /** Google identity can be added while guest accounting finishes; guest credentials stay separate. */
    suspend fun prepareForSignIn(): Boolean = AccountSignInPreparation(
        finishLocalWork = {
            if (!storageReady) {
                presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first))
                false
            } else {
                hostedBindings.disableHelpers(); meanings.reset()
                if (isRunning && (session?.id in hostedSessionIDs || conversationProvider == ConversationProvider.HOSTED_MINUTES)) {
                    updateSession { it.endReason = "Signing in" }; finish(false)
                }
                hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
                // Wait only for local startup/provenance persistence, never remote guest settlement.
                awaitLocalSignInStartup(connectionJob) {
                    presentError(getApplication<Application>().getString(R.string.error_sign_in_finishing_startup))
                }
            }
        },
        pendingOwner = { pendingHostedOwnerID },
        guestOwns = { guests?.owns(it) == true },
        prepareAccountChange = { prepareForAccountChange() },
    ).prepare()

    /** Root UI wiring must use this before sign-out, deletion, account switching, or session revocation. */
    suspend fun prepareForAccountChange(): Boolean {
        if (!storageReady) {
            presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first)); return false
        }
        hostedBindings.disableHelpers(); meanings.reset()
        hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
        if (!accountChangeBlocked) {
            val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
            if (member != null && guests?.needsLink() == true && !completeGuestSignIn()) return false
            return true
        }
        if (isRunning && (session?.id in hostedSessionIDs || conversationProvider == ConversationProvider.HOSTED_MINUTES)) {
            updateSession { it.endReason = "Account change" }; finish(false)
        }
        val finished = withTimeoutOrNull(10_000) { connectionJob?.join(); reconciliationJob?.join(); true } ?: false
        val ready = finished && settleHostedSessions()
        if (!ready) presentError(getApplication<Application>().getString(R.string.hosted_checking_previous))
        return ready
    }

    private fun reconcileHostedSessions() {
        if (reconciliationJob?.isActive == true) return
        reconciliationJob = viewModelScope.launch {
            // Wait for cancelled creation/provenance writes before clearing the durable pending marker.
            connectionJob?.join()
            if (settleHostedSessions()) refreshHostedReadiness()
        }
    }

    private suspend fun settleHostedSessions(): Boolean {
        val ownerID = pendingHostedOwnerID
        if (ownerID == null) { accountChangeBlocked = false; return true }
        accountChangeBlocked = true
        return try {
            val owner = requireHostedOwner(ownerID)
            if (owner.accountID != ownerID) return false
            for (id in hostedBindings.openSessionIDs.filter { hostedBindings.owner(it) == ownerID })
                if (!hostedBindings.closeAndConfirm(id)) return false
            // An interrupted create may have committed remotely without returning its lease locally.
            val client = hostedClient(ownerID)
            val current = client.currentSession()
            if (current != null) {
                try { current.requestClose() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                val closed = withTimeoutOrNull(8_000) {
                    while (current.status().state != "closed") delay(500)
                    true
                } ?: false
                if (!closed) return false
            }
            val balance = hostedBalance(owner)
            if (balance.reservedMilliseconds != 0L) return false
            guests?.recordSettledBalance(owner, balance)
            providerStore.clearPending()
            pendingHostedOwnerID = null; accountChangeBlocked = false
            readiness.refresh()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    fun dismissError() { error = null; errorNeedsKeySetup = false; errorNeedsAccountSignIn = false }
    fun clearLookup() {
        lookupGeneration++
        lookupJob?.cancel()
        lookupJob = null; lookupResult = null; lookupError = null; lookupLoading = false
    }
    fun saveKey(key: String) {
        if (isRunning) return
        try { credentials.save(key); hasKey = credentials.hasKey; selectConversationProvider(ConversationProvider.PERSONAL_KEY); recoverFinalAssessments(); notice = getApplication<Application>().getString(R.string.notice_key_saved) }
        catch (e: Exception) { presentError(e, R.string.error_key_save_failed) }
    }
    fun deleteKey() {
        if (isRunning) return
        try { credentials.delete(); hasKey = false }
        catch (e: Exception) { presentError(e, R.string.error_key_delete_failed) }
        finally { hasKey = credentials.hasKey }
    }
    /** A blank [key] keeps the saved one; keys never flow back into Compose state. Returns false when nothing was saved. */
    fun saveEndpoint(value: CustomEndpoint, key: String): Boolean {
        if (isRunning) return false
        val clean = value.trimmed()
        if (!clean.canSave(keyEntered = key.isNotBlank())) {
            presentError(getApplication<Application>().getString(R.string.error_endpoint_invalid)); return false
        }
        try {
            if (key.isNotBlank()) {
                // The key and settings live in separate stores; never leave a new key paired with the old server URL.
                val previousKey = endpoints.credentials.read()
                endpoints.credentials.save(key)
                try { endpoints.save(clean) } catch (e: Exception) {
                    runCatching { previousKey?.let(endpoints.credentials::save) ?: endpoints.credentials.delete() }
                        .onFailure { runCatching { endpoints.credentials.delete() } }
                    throw e
                }
            } else endpoints.save(clean)
            endpoint = clean
            if (clean.enabled) { selectConversationProvider(ConversationProvider.PERSONAL_KEY); recoverFinalAssessments() }
            notice = getApplication<Application>().getString(R.string.notice_endpoint_saved)
            return true
        } catch (_: CredentialStore.CredentialException.Invalid) {
            presentError(getApplication<Application>().getString(R.string.error_endpoint_key_invalid))
        } catch (e: Exception) { presentError(e, R.string.error_key_save_failed) }
        return false
    }
    fun deleteEndpoint() {
        if (isRunning) return
        try { endpoints.delete(); endpoint = CustomEndpoint() }
        catch (e: Exception) { presentError(e, R.string.error_key_delete_failed) }
    }
    fun updatePreferences(preferences: Preferences) {
        if (isRunning || !storageReady) return
        if (LanguageRegistry.get(preferences.learningLanguageID) == null || preferences.meaningLanguage !in MeaningLanguages.all || preferences.sessionMinutes !in 1..60) return
        val languageChanged = preferences.learningLanguageID != language.id
        actionJob?.cancel(); clearLookup(); working = false
        if (preferences.aiConsentVersion != 1) {
            finalAssessments.cancelAll(); hostedBindings.disableHelpers()
            hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
        }
        generation++; meanings.reset()
        if (languageChanged) resetConversation()
        archive = archive.copy(preferences = preferences.copy(interests = preferences.interests.take(500)))
        persist(); scheduleTranslation(); recoverFinalAssessments(); refreshHostedReadiness()
    }
    fun selectLanguage(id: String) {
        if (!isRunning && LanguageRegistry.get(id) != null) updatePreferences(archive.preferences.copy(learningLanguageID = id))
    }
    fun chooseTheme(theme: ConversationTheme?) {
        if (!isRunning && session != null) resetConversation()
        selectedTheme = theme
        if (state == "active") {
            updateSession { it.themeID = theme?.id; it.title = theme?.title ?: language.defaultTitle }
            command("instructions", TeachingPolicy.theme(theme, language), respond = true)
        }
    }
    fun toggleMeaning() {
        archive = archive.copy(preferences = archive.preferences.copy(meaningVisible = !archive.preferences.meaningVisible)); persist()
        meanings.reset()
        if (archive.preferences.meaningVisible) scheduleTranslation()
    }
    fun toggleMute() { if (state == "active" && voiceSession) { isMuted = !isMuted; transport.mute(isMuted) } }
    fun help() {
        if (state != "active") return
        if (voiceSession) { activity.learnerEngaged(activityNow()); inactivitySeconds = null; conversationPace.askForHelp(session?.passages?.lastOrNull { it.speaker == Speaker.user }); command("instructions", conversationPace.instruction); command("instructions", TeachingPolicy.help(language), respond = true); notice = getApplication<Application>().getString(R.string.notice_help_simpler) }
        else {
            if (working || !cloudReady()) return
            val snapshot = session ?: return
            val token = generation; working = true
            actionJob = viewModelScope.launch {
                try {
                    val result = teaching(snapshot.id, HelperPurpose.HELP, UUID.randomUUID().toString(),
                        TeachingPolicy.help(language) + " Return only your brief explanation.", helperContext(snapshot))
                    if (token != generation || session?.id != snapshot.id || state != "active") return@launch
                    val offset = ((nowSeconds() - snapshot.startedAt) * 1000).toInt().coerceAtLeast(0)
                    updateSession {
                        it.append(Fragment(speaker = Speaker.assistant, text = result.text, startMS = offset, endMS = offset + 1))
                        addUsage(it, result.usage)
                    }
                    scheduleTranslation()
                } catch (_: CancellationException) { }
                catch (e: Exception) { if (token == generation) presentError(e, R.string.error_help_failed) }
                finally { if (token == generation) working = false }
            }
        }
    }

    private fun newSession(voice: Boolean, id: String = UUID.randomUUID().toString()) {
        generation++; resetJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel(); clearLookup(); working = false; meanings.reset()
        dismissError(); notice = null; isMuted = false
        voiceSession = voice
        val record = SessionRecord(id = id, languageID = language.id, themeID = selectedTheme?.id, title = selectedTheme?.title ?: language.defaultTitle)
        topicResult?.takeIf { it.languageID == language.id && selectedTheme?.id == "current" }?.let { record.topics += it }
        session = record; save(record)
    }
    fun start() {
        if (isRunning || !cloudReady()) return
        val choice = conversationProvider
        if (choice == ConversationProvider.HOSTED_MINUTES && accountChangeBlocked) {
            presentError(getApplication<Application>().getString(R.string.hosted_checking_previous))
            reconcileHostedSessions(); return
        }
        if (!ConversationProviderPolicy.canStart(choice, personalReady, hostedReadiness)) {
            if (choice == ConversationProvider.HOSTED_MINUTES) {
                showMinuteAccess = true; refreshHostedReadiness()
            } else presentError(getApplication<Application>().getString(R.string.error_missing_key), true)
            return
        }
        val turnBased = choice == ConversationProvider.PERSONAL_KEY && endpoint.enabled
        if (turnBased && !endpoint.voiceReady) {
            presentError(getApplication<Application>().getString(R.string.error_endpoint_voice_missing), true); return
        }
        newSession(true); state = "connecting"
        val id = session!!.id
        val module = language
        val instructions = TeachingPolicy.voice(module, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage)
        val history = ConversationHistory.messages(session)
        if (choice == ConversationProvider.HOSTED_MINUTES) accountChangeBlocked = true
        transport = if (turnBased) turnTransport else liveTransport
        connectionJob = viewModelScope.launch {
            try {
                if (turnBased) turnTransport.connect(api, module.id) { guidance -> spokenReply(id, instructions, module, guidance) }
                else liveTransport.connect(if (choice == ConversationProvider.PERSONAL_KEY) api else hostedProvider(id),
                    instructions, history, module.locale)
            } catch (cancelled: CancellationException) {
                if (choice == ConversationProvider.HOSTED_MINUTES) reconcileHostedSessions()
                throw cancelled
            } catch (e: Exception) {
                if (session?.id == id && isRunning) fail(e, R.string.error_voice_connect_failed)
                if (choice == ConversationProvider.HOSTED_MINUTES) reconcileHostedSessions()
            }
        }
    }
    /** Checks hosted access and commits provenance before the paid create, then binds its lease to conversation [id]. */
    private suspend fun hostedProvider(id: String): LiveSessionProvider {
        val owner = requireHostedOwner()
        if (selectedAccount.busy || owner.accountID != hostedReadiness.accountID) throw HostedFailure.SignInRequired
        val hosted = hostedClient(owner.accountID)
        val balance = hostedBalance(owner)
        if (!balance.canStartConversation || !hosted.available()) throw HostedFailure.Unavailable
        // Commit provider provenance and the unresolved-owner marker before making a paid create.
        hostedSessionIDs = hostedSessionIDs + id
        pendingHostedOwnerID = owner.accountID
        withContext(NonCancellable) { providerStore.markHosted(id, owner.accountID) }
        return object : LiveSessionProvider {
            override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection {
                val result = hosted.createLiveSession(request.copy(requestedMilliseconds = archive.preferences.sessionMinutes * 60_000L))
                val lease = result.lease as? HostedAPIClient.HostedLease ?: throw HostedFailure.InvalidResponse
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    hostedBindings.bind(id, owner.accountID, binding(lease))
                    if (session?.id != id || state != "connecting") {
                        hostedBindings.ended(id); reconcileHostedSessions()
                    }
                }
                return result
            }
        }
    }
    fun end(reason: String = "Ended by you") {
        if (state !in listOf("active", "connecting")) return
        val connecting = state == "connecting"
        state = "closing"; isMuted = true
        connectionJob?.cancel(); durationJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel(); clearLookup()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear(); working = false
        updateSession { it.endReason = reason }
        if (!voiceSession || connecting) { finish(false); return }
        transport.close()
        closeJob = viewModelScope.launch { delay(5000); if (state == "closing") finish(false) }
    }
    fun background() {
        // Leaving the foreground ends the conversation and releases the microphone; its final assessment still completes.
        generation++; actionJob?.cancel(); clearLookup(); meanings.reset(); working = false
        if (isRunning) { updateSession { it.endReason = "App moved to background" }; finish(false) }
    }
    private fun finish(final: Boolean) {
        if (!isRunning) return
        connectionJob?.cancel(); durationJob?.cancel(); closeJob?.cancel(); assessmentJob?.cancel()
        actionJob?.cancel(); clearLookup(); languageCheckJob?.cancel()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear()
        transport.disconnect(); inputLevel = 0.0; outputLevel = 0.0; working = false; isMuted = false
        updateSession { it.endedAt = nowSeconds(); it.usageFinal = final }
        state = "ended"
        notice = when (session?.endReason) {
            "Inactivity" -> getApplication<Application>().getString(R.string.notice_ended_inactivity)
            "Time limit" -> getApplication<Application>().getString(R.string.notice_time_limit_reached)
            else -> null
        }
        session?.let {
            if (it.id in hostedSessionIDs) {
                hostedBindings.ended(it.id); reconcileHostedSessions(); finishHostedAssessment(clone(it))
            } else finalAssessments.submit(clone(it))
        }
        scheduleTranslation(utteranceComplete = true)
        resetJob = viewModelScope.launch { delay(15000); if (state == "ended") resetConversation() }
    }
    private fun fail(message: String, needsKeySetup: Boolean = false) { finish(false); resetJob?.cancel(); state = "failed"; presentError(message, needsKeySetup) }
    private fun fail(e: Throwable, @StringRes fallback: Int) {
        fail(resolveMessage(e, fallback), errorNeedsKeySetup(e))
        errorNeedsAccountSignIn = needsAccountRecovery(e)
    }
    fun resetConversation() {
        if (isRunning) return
        generation++; resetJob?.cancel(); actionJob?.cancel(); clearLookup(); assessmentJob?.cancel(); languageCheckJob?.cancel(); meanings.reset()
        session = null; selectedTheme = null; topicResult = null
        notice = null; working = false; state = "idle"; voiceSession = false
    }
    /** [respond] marks guidance that needs a spoken answer now, such as a greeting or check-in. */
    private fun command(kind: String, content: String, delegationID: String? = null, respond: Boolean = false): Boolean {
        if (state != "active" || !voiceSession) return false
        return transport.send(buildJsonObject {
            put("type", "session.$kind.append"); put("event_id", UUID.randomUUID().toString())
            put("delegation_id", delegationID?.let(::JsonPrimitive) ?: JsonNull); put("content", content.take(1000))
        }, respond).also { if (!it) notice = getApplication<Application>().getString(R.string.notice_update_send_failed) }
    }
    /** Turn-based voice answers with the typed-conversation path: voice policy plus transcript context. */
    private suspend fun spokenReply(sessionID: String, instructions: String, module: LanguageModule, guidance: String): String {
        val snapshot = session?.takeIf { it.id == sessionID } ?: return ""
        val result = teaching(sessionID, HelperPurpose.TYPED_REPLY, UUID.randomUUID().toString(),
            instructions + "\n" + TeachingPolicy.spokenReply(module) + guidance, helperContext(snapshot))
        updateSession { if (it.id == sessionID) addUsage(it, result.usage) }
        return result.text
    }
    private fun handle(event: JsonObject) {
        if (session == null || !isRunning) return
        when (event["type"]?.jsonPrimitive?.content) {
            "mural.session.created" -> updateSession { it.providerID = (event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.content; it.voiceSeconds = 15.0 }
            "session.started" -> if (state == "connecting") {
                state = "active"; activity = ConversationActivity(activityNow()); conversationPace = ConversationPace(); inactivitySeconds = null
                updateSession { it.providerID = (event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: it.providerID }
                command("instructions", TeachingPolicy.greeting(language), respond = true); startDurationChecks()
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val text = event["delta"]?.jsonPrimitive?.contentOrNull ?: return
                val start = event["start_ms"]?.jsonPrimitive?.intOrNull ?: return
                val end = event["end_ms"]?.jsonPrimitive?.intOrNull ?: return
                if (start < 0 || end < start || text.length > 50000) return
                val speaker = if (event["type"]?.jsonPrimitive?.content == "session.input_transcript.delta") Speaker.user else Speaker.assistant
                updateSession { it.append(Fragment(id = event["event_id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(), speaker = speaker, text = text, startMS = start, endMS = end, meaningVisible = archive.preferences.meaningVisible)) }
                if (text.isNotBlank()) {
                    if (speaker == Speaker.user) { activity.learnerEngaged(activityNow()); inactivitySeconds = null }
                    else activity.assistantActive(activityNow())
                }
                if (speaker == Speaker.assistant) { scheduleTranslation(); if (state == "active") checkLanguage() } else scheduleAssessment()
            }
            "session.delegation.created" -> {
                val d = event["delegation"] as? JsonObject ?: return
                if (d["target"]?.jsonPrimitive?.content == "client") d["id"]?.jsonPrimitive?.content?.let(::delegate)
            }
            "session.usage.updated", "session.closed" -> {
                val seconds = (event["usage"] as? JsonObject)?.get("seconds")?.jsonPrimitive?.doubleOrNull
                if (seconds != null && seconds.isFinite() && seconds in 0.0..31536000.0) updateSession { it.voiceSeconds = seconds }
                if (event["type"]?.jsonPrimitive?.content == "session.closed") finish(true)
            }
            "error" -> { notice = getApplication<Application>().getString(R.string.notice_voice_update_rejected) }
        }
    }
    private fun startDurationChecks() {
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (state == "active") {
                delay(1000)
                val current = session ?: break
                if (current.id in hostedSessionIDs && hostedBindings.reachedDeadline(current.id)) {
                    updateSession { it.endReason = "Reserved conversation time ended" }; finish(false); break
                }
                if (nowSeconds() - current.startedAt > archive.preferences.sessionMinutes * 60) {
                    notice = getApplication<Application>().getString(R.string.notice_time_limit_reached); end("Time limit"); break
                }
                inactivitySeconds = null
                if (voiceSession) when (val next = activity.tick(activityNow(), isMuted, working || delegations.isNotEmpty())) {
                    ConversationActivity.Action.CheckIn -> command("instructions", TeachingPolicy.checkIn(language), respond = true)
                    is ConversationActivity.Action.Warning -> inactivitySeconds = next.seconds
                    ConversationActivity.Action.End -> { notice = getApplication<Application>().getString(R.string.notice_ended_inactivity); end("Inactivity"); break }
                    ConversationActivity.Action.Wait -> Unit
                }
            }
        }
    }

    private fun addUsage(s: SessionRecord, usage: APIUsage) {
        s.inputTokens = (s.inputTokens.toLong() + usage.input).coerceAtMost(1_000_000_000).toInt()
        s.outputTokens = (s.outputTokens.toLong() + usage.output).coerceAtMost(1_000_000_000).toInt()
        s.searchCalls = (s.searchCalls.toLong() + usage.searches).coerceAtMost(1_000_000_000).toInt()
    }
    private fun scheduleTranslation(utteranceComplete: Boolean = state == "ended") {
        if (!archive.preferences.meaningVisible || archive.preferences.aiConsentVersion != 1) return
        val current = session ?: return
        val passage = current.passages.lastOrNull { it.speaker == Speaker.assistant } ?: return
        val request = MeaningRequest(current.id, passage, current.languageID, archive.preferences.meaningLanguage)
        meanings.update(request, current.translations[request.cacheKey], utteranceComplete, conversationEnded = state == "ended")
    }
    fun retryMeaning() { scheduleTranslation(); meanings.retry() }
    private fun checkLanguage() {
        val passage = session?.passages?.lastOrNull { it.speaker == Speaker.assistant }
        if (!LanguageDetector.shouldCheck(passage, lastLanguageRedirect) || languageCheckJob?.isActive == true) return
        val module = language; val passageID = passage!!.id; val text = passage.text
        languageCheckJob = viewModelScope.launch {
            val detected = languageDetector.detect(text) ?: return@launch
            if (state == "active" && lastLanguageRedirect != passageID &&
                TeachingPolicy.shouldRedirectSpeech(module, detected.languageID, detected.confidence)) {
                lastLanguageRedirect = passageID
                command("instructions", TeachingPolicy.redirect(module), respond = true)
            }
        }
    }
    @Serializable private data class AssessmentResponse(val outcome: Outcome, val suggestedLevel: Int, val nextGoal: String, val capability: String, val words: List<WordProposal>)
    private fun assessmentSchema(id: String): JsonObject {
        fun string() = buildJsonObject { put("type", "string") }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(fields)); put("required", JsonArray(fields.keys.map(::JsonPrimitive))); put("additionalProperties", false)
        }
        fun enumeration(values: List<String>) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
        return obj(mapOf(
            "outcome" to enumeration(Outcome.entries.map { it.name }),
            "suggestedLevel" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 5) },
            "nextGoal" to string(), "capability" to string(),
            "words" to buildJsonObject {
                put("type", "array"); put("maxItems", 12)
                put("items", obj(mapOf("lemma" to string(), "meaning" to string(), "form" to string(), "quote" to string(),
                    "language" to enumeration(listOf(id, "en", "mixed", "uncertain").distinct()),
                    "kind" to enumeration(EvidenceKind.entries.map { it.name }),
                    "confidence" to buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) },
                    "sourceIDs" to buildJsonObject { put("type", "array"); put("items", string()) }
                )))
            }
        ))
    }
    private suspend fun requestAssessment(snapshot: SessionRecord, passage: Passage): FinalAssessmentResult {
        if (archive.preferences.aiConsentVersion != 1) throw IllegalStateException("AI processing consent is required.")
        val module = LanguageRegistry.get(snapshot.languageID) ?: throw IllegalStateException("Unsupported language.")
        val result = teaching(snapshot.id, HelperPurpose.ASSESSMENT, passage.revisionKey,
            TeachingPolicy.assessment(module), helperContext(snapshot, passage), assessmentSchema(module.id))
        val decoded = json.decodeFromString<AssessmentResponse>(result.text)
        val proposal = Assessment(passage.id, passage.revisionKey, decoded.outcome, decoded.suggestedLevel, decoded.nextGoal, decoded.capability, decoded.words, context = snapshot.themeID ?: "free", textAssemblyVersion = 2)
        return FinalAssessmentResult(snapshot.id, snapshot.languageID, proposal, result.usage.input, result.usage.output, result.usage.searches)
    }
    /** Hosted finalization awaits the original helper for its remaining lease window, without entering BYOK recovery. */
    private fun finishHostedAssessment(snapshot: SessionRecord) {
        val passage = FinalAssessmentRecovery.passage(snapshot) ?: return
        if (hostedFinalAssessmentJobs.containsKey(snapshot.id) || !hostedBindings.canAssess(snapshot.id)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(HostedConversationBindings.POST_END_MILLIS) {
                    val result = requestAssessment(snapshot, passage)
                    if (archive.preferences.aiConsentVersion != 1) return@withTimeout
                    result.applying(archive.sessions.firstOrNull { it.id == result.sessionID })?.let { updated ->
                        save(updated)
                        if (session?.id == updated.id) session = clone(updated)
                        persistAndWait()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep uncertain evidence unassessed; never submit a replacement request. */ }
            finally { hostedFinalAssessmentJobs.remove(snapshot.id) }
        }
        hostedFinalAssessmentJobs[snapshot.id] = job
        job.start()
    }

    private fun scheduleAssessment() {
        assessmentJob?.cancel()
        assessmentJob = viewModelScope.launch {
            delay(3000)
            val snapshot = session?.let(::clone) ?: return@launch
            val passage = snapshot.passages.lastOrNull { it.speaker == Speaker.user && it.text.length >= 3 } ?: return@launch
            if (snapshot.assessments.any { it.passageID == passage.id && it.revisionKey == passage.revisionKey }) return@launch
            try {
                val result = requestAssessment(snapshot, passage)
                val current = archive.sessions.firstOrNull { it.id == snapshot.id && it.languageID == snapshot.languageID } ?: return@launch
                val valid = LearningEngine.validate(result.assessment, current) ?: return@launch
                val updated = clone(current)
                updated.assessments.removeAll { it.passageID == valid.passageID }; updated.assessments += valid
                addUsage(updated, APIUsage(result.inputTokens, result.outputTokens, result.searchCalls)); save(updated)
                if (session?.id == updated.id) session = clone(updated)
                if (state == "active" && session?.id == snapshot.id &&
                    session?.passages?.lastOrNull { it.speaker == Speaker.user }?.revisionKey == passage.revisionKey) {
                    // Keep assessment notes in learning records; injecting them during speech can make the voice read them aloud.
                    if (voiceSession && conversationPace.observe(valid, passage, snapshot.languageID)) {
                        command("instructions", conversationPace.instruction)
                    }
                }
            } catch (_: CancellationException) { } catch (_: Exception) { /* No unverified progress. */ }
        }
    }
    private fun delegate(id: String) {
        if (state != "active" || delegations.containsKey(id)) return
        val sessionID = session?.id ?: return
        delegations[id] = viewModelScope.launch {
            try {
                delay(500)
                val snapshot = session ?: return@launch
                val result = teaching(snapshot.id, HelperPurpose.DELEGATION, id,
                    TeachingPolicy.delegation(language), helperContext(snapshot), search = snapshot.searchCalls < 3)
                if (state != "active" || session?.id != sessionID) return@launch
                updateSession { addUsage(it, result.usage); if (result.sources.isNotEmpty()) it.topics += TopicBrief(languageID = it.languageID, query = getApplication<Application>().getString(R.string.topics_from_conversation), text = result.text, sources = result.sources) }
                command("commentary", result.text, id)
            } catch (_: CancellationException) { }
            catch (_: Exception) { if (session?.id == sessionID) command("commentary", language.lookupUnavailableReply, id) }
            finally { delegations.remove(id) }
        }
    }
    fun sendTyped(text: String) {
        typedReplyError = null
        val clean = text.trim().take(2000)
        if (clean.isEmpty() || working || state in listOf("connecting", "closing") || !cloudReady()) return
        if (state != "active") {
            if (conversationProvider == ConversationProvider.HOSTED_MINUTES) {
                presentError(getApplication<Application>().getString(R.string.hosted_typed_start)); return
            }
            newSession(false); state = "active"; startDurationChecks()
        }
        val id = session!!.id; val token = generation
        val offset = if (voiceSession) session!!.nextTypedVoiceOffsetMS
            else ((nowSeconds() - session!!.startedAt) * 1000).toInt().coerceAtLeast(0)
        val fragment = Fragment(speaker = Speaker.user, text = clean, startMS = offset, endMS = offset + 1, meaningVisible = archive.preferences.meaningVisible, typed = true)
        val draft = clone(session!!).also { it.append(fragment) }
        if (voiceSession) { activity.learnerEngaged(activityNow()); inactivitySeconds = null }; working = true
        actionJob = viewModelScope.launch {
            try {
                val instructions = if (voiceSession) TeachingPolicy.typedReply(language) else TeachingPolicy.voice(language, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage) + "\n" + TeachingPolicy.typedReply(language)
                val result = teaching(id, HelperPurpose.TYPED_REPLY, UUID.randomUUID().toString(), instructions, helperContext(draft))
                if (token != generation || session?.id != id || state != "active") return@launch
                updateSession { addUsage(it, result.usage) }
                if (voiceSession) {
                    if (!command("thinking", "The learner typed (data): ${clean.take(650)}") || !command("commentary", result.text)) {
                        typedReplyError = getApplication<Application>().getString(R.string.error_send_message_failed)
                        return@launch
                    }
                    updateSession { it.append(fragment) }
                } else {
                    updateSession { it.append(fragment) }
                    val end = ((nowSeconds() - session!!.startedAt) * 1000).toInt().coerceAtLeast(offset + 2)
                    updateSession { it.append(Fragment(speaker = Speaker.assistant, text = result.text, startMS = end, endMS = end + 1)) }
                    scheduleTranslation()
                }
                typedRepliesSent++
                scheduleAssessment()
            } catch (_: CancellationException) { }
            catch (e: Exception) {
                if (session?.id == id) {
                    typedReplyError = resolveMessage(e, R.string.error_send_message_failed)
                    if (errorNeedsKeySetup(e) || needsAccountRecovery(e)) presentError(e, R.string.error_send_message_failed)
                }
            }
            finally { if (token == generation) working = false }
        }
    }
    fun lookup(word: String, sentence: String) {
        if (!cloudReady() || word.isBlank()) return
        clearLookup()
        val token = generation; val id = session?.id; val request = lookupGeneration
        lookupLoading = true
        lookupJob = viewModelScope.launch {
            try {
                val result = teaching(id, HelperPurpose.LOOKUP, UUID.randomUUID().toString(),
                    TeachingPolicy.lookup(language, archive.preferences.meaningLanguage), "Selected: ${word.take(200)}\nSentence: ${sentence.take(2200)}")
                if (token != generation || request != lookupGeneration) return@launch
                lookupResult = result.text
                if (session?.id == id) updateSession { addUsage(it, result.usage) }
            } catch (_: CancellationException) { }
            catch (e: Exception) {
                if (token == generation && request == lookupGeneration) lookupError = resolveMessage(e, R.string.error_lookup_word_failed)
            }
            finally { if (request == lookupGeneration) lookupLoading = false }
        }
    }
    fun currentTopic(query: String) {
        if (query.isBlank() || working || !cloudReady()) return
        archive.sessions.filter { it.languageID == language.id }.flatMap { it.topics }.firstOrNull { it.query.equals(query.trim(), true) && it.isFresh }?.let { topicResult = it; return }
        val token = generation; val module = language; working = true; topicResult = null
        actionJob = viewModelScope.launch {
            try {
                val result = teaching(session?.id, HelperPurpose.TOPIC, UUID.randomUUID().toString(), TeachingPolicy.currentTopic(module), query.trim().take(500), search = true)
                if (token != generation) return@launch
                if (result.sources.isEmpty()) { presentError(getApplication<Application>().getString(R.string.error_topic_unsourced)); return@launch }
                val brief = TopicBrief(languageID = module.id, query = query.trim().take(500), text = result.text, sources = result.sources)
                topicResult = brief
                if (isRunning) updateSession { it.topics += brief; addUsage(it, result.usage) }
                else { val record = SessionRecord(languageID = module.id, title = brief.query, endedAt = nowSeconds()); record.topics += brief; addUsage(record, result.usage); save(record) }
            } catch (_: CancellationException) { }
            catch (e: Exception) { if (token == generation) presentError(e, R.string.error_find_topic_failed) }
            finally { if (token == generation) working = false }
        }
    }
    fun discuss(brief: TopicBrief) {
        if (brief.languageID != language.id) return
        if (state == "active") {
            updateSession { if (it.topics.none { t -> t.id == brief.id }) it.topics += brief }
            if (voiceSession) {
                command("thinking", "Sourced context, data: ${brief.text}"); command("instructions", "Discuss this topic ONLY in ${language.name}.", respond = true)
                return
            }
        } else resetConversation()
        topicResult = brief; selectedTheme = currentTheme(brief)
        notice = getApplication<Application>().getString(R.string.notice_topic_ready)
    }
    private fun currentTheme(brief: TopicBrief) = ConversationTheme("current", brief.query, getApplication<Application>().getString(R.string.topics_current_theme_subtitle), "newspaper", "Interests", "Discuss this sourced reference data: ${brief.text.take(3000)}", 0)
    fun deleteSession(id: String) {
        if (isRunning) return
        if (reportState.value.selection?.sessionID == id) reports.dismiss()
        finalAssessments.cancel(id); hostedFinalAssessmentJobs.remove(id)?.cancel(); hostedBindings.forgetLearning(id)
        finalAssessmentTickets = finalAssessmentTickets.filterNot { it.sessionID == id }
        if (session?.id == id) resetConversation()
        archive = archive.copy(sessions = archive.sessions.filterNot { it.id == id }.toMutableList()); persist()
    }
    fun hideWord(id: String) {
        archive = archive.copy(preferences = archive.preferences.copy(hiddenWords = (archive.preferences.hiddenWords + id).distinct())); persist()
    }
    fun correctPassage(sessionID: String, passageID: String, text: String) {
        if (isRunning) return
        val record = archive.sessions.firstOrNull { it.id == sessionID }?.let(::clone) ?: return
        val passage = record.passages.firstOrNull { it.id == passageID && it.speaker == Speaker.user } ?: return
        finalAssessments.cancel(sessionID); hostedFinalAssessmentJobs.remove(sessionID)?.cancel(); generation++; meanings.reset()
        passage.fragments.forEachIndexed { index, f -> record.correctFragment(f.id, if (index == 0) text.take(10000) else "") }
        save(record); if (session?.id == record.id) session = record
    }
    fun deleteLearningData() {
        if (isRunning || !storageReady) return
        reports.dismiss()
        finalAssessments.cancelAll(); hostedSessionIDs.forEach(hostedBindings::forgetLearning)
        hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear(); resetConversation()
        finalAssessmentTickets = emptyList()
        archive = archive.copy(sessions = mutableListOf(), preferences = archive.preferences.copy(hiddenWords = emptyList())); persist()
    }
    fun exportData(): String = ArchiveCodec.encode(archive)
    fun importData(data: String): Boolean {
        if (isRunning || !storageReady) return false
        return try { archive = ArchiveCodec.merge(archive, prepareImportedArchive(data)); persist(); notice = getApplication<Application>().getString(R.string.notice_backup_imported); true }
        catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.error_import_failed)); false }
    }
    override fun onCleared() { hostedBindings.disableHelpers(); transport.disconnect(); super.onCleared() }
}
