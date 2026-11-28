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
import chat.mural.ondevice.*
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
        ProviderFailureKind.creditExhausted -> R.string.error_provider_credit_exhausted
        ProviderFailureKind.spendLimit -> R.string.error_provider_spend_limit
        ProviderFailureKind.usageLimit -> R.string.error_provider_usage_limit
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
    is ModelRuntimeException -> R.string.error_on_device_model_failed
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
    var targetCaptionFollowing by mutableStateOf(CaptionFollowing())
    var meaningCaptionFollowing by mutableStateOf(CaptionFollowing())
    var error by mutableStateOf<String?>(null); private set
    var errorNeedsKeySetup by mutableStateOf(false); private set
    var errorNeedsAccountSignIn by mutableStateOf(false); private set
    var typedReplyError by mutableStateOf<String?>(null); private set
    var typedRepliesSent by mutableStateOf(0); private set
    fun clearTypedReplyError() { typedReplyError = null }
    var notice by mutableStateOf<String?>(null); private set
    private var continuationSession: SessionRecord? = null
    private var continuationOwnerID: String? = null
    private var freeBoundaryOwnerID: String? = null
    var hasContinuation by mutableStateOf(false); private set
    var continuationReady by mutableStateOf(false); private set
    var continuationNeedsMinutes by mutableStateOf(false); private set
    /** Whether the saved conversation was a voice one: a typed conversation stays typed when continued. */
    var continuationWasVoice by mutableStateOf(false); private set
    /** Whether Continue resumes voice or typed Talk on this phone, for the saved conversation. */
    val continuationIsVoice get() = ConversationContinuationPolicy.resumesVoice(conversationProvider,
        language.isTypedOnly, canHearOnDeviceVoice, continuationWasVoice)
    private val continuationPreferences = application.getSharedPreferences("mural_continuation", android.content.Context.MODE_PRIVATE)
    private val continuationKey = "v1." + BuildConfig.MANAGED_API_ORIGIN
    private val continuationDeclinedKey = "declined.v1." + BuildConfig.MANAGED_API_ORIGIN
    /**
     * Remembers the On-Device Model continuation the learner just continued or declined. A session a
     * process death interrupted keeps its recovery reason in the learning record forever, so without
     * this the same Continue/New choice would come back at every launch.
     */
    private fun declineOnDeviceContinuation() {
        continuationSession?.takeIf { it.id in onDeviceSessionIDs }
            ?.let { continuationPreferences.edit().putString(continuationDeclinedKey, it.id).apply() }
    }
    private fun clearContinuation() {
        declineOnDeviceContinuation()
        continuationSession = null; continuationOwnerID = null; freeBoundaryOwnerID = null
        hasContinuation = false; continuationReady = false; continuationNeedsMinutes = false
        continuationWasVoice = false
        continuationPreferences.edit().remove(continuationKey).apply()
    }
    /** A record restored from disk keeps its own evidence of having been a voice conversation. */
    private fun wasVoice(saved: SessionRecord) = saved.voiceSeconds > 0 || saved.fragments.any { !it.typed }
    private fun restoreContinuation(accountID: String) {
        if (isRunning || hasContinuation || conversationProvider != ConversationProvider.HOSTED_MINUTES) return
        val checkpoint = runCatching { json.decodeFromString<ConversationContinuationCheckpoint>(
            continuationPreferences.getString(continuationKey, null) ?: return) }.getOrNull() ?: return
        val saved = checkpoint.recover(archive.sessions, accountID, language.id) ?: return
        continuationSession = saved; continuationOwnerID = accountID; hasContinuation = true; continuationReady = false
        continuationWasVoice = wasVoice(saved)
        session = saved; selectedTheme = language.themes.firstOrNull { it.id == saved.themeID }; topicResult = saved.topics.lastOrNull()
        if (saved.themeID == "current") selectedTheme = topicResult?.let(::currentTheme)
        state = "ended"; resetJob?.cancel()
        notice = getApplication<Application>().getString(R.string.notice_continue_settling)
    }
    /**
     * The On-Device Model conversation a low-memory kill or a relaunch interrupted. Its transcript
     * never left this phone, so the same Continue/New choice is offered again with nothing to settle:
     * a killed process never costs the learner the conversation.
     */
    private fun restoreOnDeviceContinuation() {
        if (isRunning || hasContinuation || conversationProvider != ConversationProvider.ON_DEVICE) return
        val checkpoint = runCatching { json.decodeFromString<ConversationContinuationCheckpoint>(
            continuationPreferences.getString(continuationKey, null) ?: "") }.getOrNull()
        val saved = checkpoint?.recoverOnDevice(archive.sessions, language.id)
            ?: ConversationContinuationPolicy.recoveredOnDeviceSession(archive.sessions, onDeviceSessionIDs,
                language.id, continuationPreferences.getString(continuationDeclinedKey, null))
                ?.also(::saveOnDeviceContinuationCheckpoint)
            ?: return
        continuationSession = saved; continuationOwnerID = null
        continuationWasVoice = wasVoice(saved)
        hasContinuation = true; continuationReady = true; continuationNeedsMinutes = false
        session = saved; selectedTheme = language.themes.firstOrNull { it.id == saved.themeID }; topicResult = saved.topics.lastOrNull()
        if (saved.themeID == "current") selectedTheme = topicResult?.let(::currentTheme)
        state = "ended"; resetJob?.cancel()
        notice = getApplication<Application>().getString(R.string.notice_continue_on_device_saved)
    }
    private fun saveOnDeviceContinuationCheckpoint(saved: SessionRecord) {
        continuationPreferences.edit().putString(continuationKey, json.encodeToString(
            kotlinx.serialization.serializer<ConversationContinuationCheckpoint>(),
            ConversationContinuationCheckpoint(saved.id, "", onDevice = true))).apply()
    }
    /**
     * Continue resumes typed: the saved conversation is reopened with its own transcript, so a
     * Typed-only Module, an On-Device Model that cannot hear the learner and a conversation that was
     * typed all carry on instead of meeting a microphone prompt that leads to nothing.
     */
    fun continueTypedConversation() {
        if (isRunning || !hasContinuation) return
        val continuing = continuationSession ?: return
        generation++; resetJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel(); clearLookup()
        dismissError(); working = false; isMuted = false; meanings.reset()
        selectedTheme = language.themes.firstOrNull { it.id == continuing.themeID }
        topicResult = continuing.topics.lastOrNull()
        if (continuing.themeID == "current") selectedTheme = topicResult?.let(::currentTheme)
        val resumed = clone(continuing).also { it.endedAt = null; it.endReason = null }
        voiceSession = false; session = resumed; save(resumed)
        clearContinuation()
        notice = null
        // The length limit runs from the moment of continuing: the transcript already spent the
        // previous clock, and measuring it again would end the conversation at once.
        durationBaseline = nowSeconds()
        state = "active"; startDurationChecks()
    }
    private suspend fun refreshContinuation() {
        val expected = continuationOwnerID ?: return
        if (isRunning) return
        continuationReady = false; continuationNeedsMinutes = false
        val owner = availableHostedOwner()?.takeIf { it.accountID == expected } ?: return
        val balance = hostedBalance(owner)
        if (isRunning || continuationOwnerID != expected || availableHostedOwner()?.accountID != expected) return
        continuationReady = !accountChangeBlocked && balance.canStartConversation && balance.presentation?.settlementState == "settled"
        continuationNeedsMinutes = ConversationContinuationPolicy.needsMoreMinutes(balance.presentation?.settlementState,
            balance.presentation?.availabilityReason)
        if (continuationNeedsMinutes) notice = getApplication<Application>().getString(R.string.notice_continue_insufficient)
        else if (continuationReady) notice = getApplication<Application>().getString(
            if (balance.availableMilliseconds > 0) R.string.notice_continue_remaining_free
            else R.string.notice_continue_purchased)
    }
    var meaning by mutableStateOf(""); private set
    var translating by mutableStateOf(false); private set
    var meaningFailed by mutableStateOf(false); private set
    var meaningLimitReached by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    /** The phone is generating a teacher reply: the idle timer waits for it as for a cloud reply. */
    var answering by mutableStateOf(false); private set
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
    val language get() = AndroidLanguages.get(archive.preferences.learningLanguageID)!!
    val learner get() = LearningEngine.project(archive.sessions, language.id, archive.preferences.hiddenWords)
    val isRunning get() = state in listOf("connecting", "active", "closing")
    val isVoiceSession get() = voiceSession

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val repository = LearningRepository(application)
    private val credentials = CredentialStore(application)
    private val api = APIClient(credentials)
    private val transport = LiveTransport(application, viewModelScope)
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
    /** Untimed Conversation opt-in: On-Device Model sessions with no conversation-length limit. */
    private var untimedConversation by mutableStateOf(false)
    val untimedOnDevice get() = untimedConversation
    var providerIssue by mutableStateOf<ProviderFailureKind?>(null); private set
    var hostedReadiness by mutableStateOf(HostedReadiness()); private set
    private val readiness = HostedReadinessController(viewModelScope,
        readSession = { availableHostedOwner() },
        fetch = { owner ->
            val enabled = hostedClient(owner.accountID).available()
            val balance = if (enabled) hostedBalance(owner) else null
            HostedReadiness(owner.accountID, balance?.readinessMilliseconds ?: 0, enabled,
                verified = balance != null)
        }, changed = { hostedReadiness = it })
    private val onDeviceModels = OnDeviceModelController(application, viewModelScope, changed = ::onDeviceChanged,
        // The Model Runtime holds a model in RAM only for the Conversation Provider that runs it.
        providerIsOnDevice = { conversationProvider == ConversationProvider.ON_DEVICE })
    var onDeviceReadiness by mutableStateOf(OnDeviceReadiness()); private set
    var onDeviceStoredModels by mutableStateOf(emptyList<OnDeviceModel>()); private set
    /** Whether this phone can hear the learner on the active On-Device Model setup: a text-only
     * model with no speech recognition alternate cannot, and Talk then stays typed from idle. */
    var canHearOnDeviceVoice by mutableStateOf(false); private set
    private fun refreshOnDeviceVoice() { canHearOnDeviceVoice = onDeviceModels.resolvedSpeechInput(language.locale) != null }
    private fun onDeviceChanged(next: OnDeviceReadiness) {
        if (next.failure != null && next.failure != onDeviceReadiness.failure) presentError(next.failure)
        onDeviceReadiness = next; onDeviceStoredModels = onDeviceModels.stored()
        // Deleting the speech recognition alternate puts the learner back on native audio input, and
        // deleting a voice model puts its languages back on the phone's built-in voice.
        onDeviceSpeechInput = onDeviceModels.speechInputEngine()
        onDeviceTeacherVoices = onDeviceModels.teacherVoices()
        refreshOnDeviceVoice()
    }
    /** The Helpers of an On-Device Model session: strictly offline, never a cloud call. */
    private val onDeviceTeaching = OnDeviceTeachingClient(onDeviceModels.turn) { language.lookupUnavailableReply }
    private var onDeviceSessionIDs = emptySet<String>()
    /** Switches the active On-Device Model: an imported file, or the zero-download system model. */
    fun selectOnDeviceModel(name: String) { if (!isRunning) onDeviceModels.select(name) }
    /** The learner's speech-input engine: the active model's native audio input, or the alternate. */
    var onDeviceSpeechInput by mutableStateOf(onDeviceModels.speechInputEngine()); private set
    /** Chooses it; a running conversation keeps the microphone it opened with. */
    fun selectOnDeviceSpeechInput(engine: SpeechInputEngine) {
        if (!isRunning) onDeviceModels.selectSpeechInput(engine)
    }
    /**
     * The teacher voice of every Language Module on this phone. A language the learner never picked
     * one for speaks with the phone's built-in voice, which is also what a picked voice that is
     * missing, deleted or unsupported for that language falls back to.
     */
    var onDeviceTeacherVoices by mutableStateOf(onDeviceModels.teacherVoices()); private set
    /** Chooses the active Language Module's teacher voice; a running conversation keeps its voice. */
    fun selectOnDeviceTeacherVoice(engine: TeacherVoiceEngine) {
        if (!isRunning) onDeviceModels.selectTeacherVoice(language.id, engine)
    }
    /** Whether this phone offers the zero-download system model: the AICore device probe. */
    var onDeviceSystemModelAvailable by mutableStateOf(false); private set
    /** Probed when the model picker opens, so an unsupported phone never sees the row at all. */
    fun probeOnDeviceSystemModel() {
        viewModelScope.launch { onDeviceSystemModelAvailable = onDeviceModels.systemModelAvailable() }
    }
    /** The warnings for a catalogue download, decided before its multi-gigabyte transfer starts. */
    fun onDeviceDownloadPlan(model: CataloguedModel) = onDeviceModels.plan(model)
    /** Downloads a curated model; a refusal or an interruption arrives as a readiness failure. */
    fun downloadOnDeviceModel(model: CataloguedModel) { if (!isRunning) onDeviceModels.download(model) }
    /** Downloads a pasted direct link through the same flow as the curated catalogue. */
    fun downloadOnDeviceModelURL(pasted: String) {
        if (isRunning) return
        try { onDeviceModels.download(pasted) }
        catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.error_on_device_link_invalid)) }
    }
    /** Stops an in-flight download; the partial file stays, so a later attempt resumes it. */
    fun cancelOnDeviceDownload() { onDeviceModels.cancelDownload() }
    /** Deletes a model to reclaim its storage; the active model's session closes first. */
    fun deleteOnDeviceModel(name: String) { if (!isRunning) onDeviceModels.delete(name) }
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
    /** The live On-Device Model voice conversation, while a session runs on this phone. */
    private var onDeviceVoice: OnDeviceLiveSession? = null
    private var durationJob: Job? = null
    /** A conversation's length limit runs from when it started or was continued, never from the
     * transcript it carries: a continued conversation would otherwise end at once. */
    private var durationBaseline = 0.0
    private var closeJob: Job? = null
    private var resetJob: Job? = null
    private var assessmentJob: Job? = null
    private var actionJob: Job? = null
    private val meanings = MeaningController(viewModelScope, canRetryFailure = HostedHelperRetry::canRetryAtBoundary,
        retryDelay = HostedHelperRetry::automaticDelay, stream = { request, onText -> translateMeaning(request, onText) }) { request -> translateMeaning(request) }
    private suspend fun translateMeaning(request: MeaningRequest, onText: ((String) -> Unit)? = null): MeaningResult {
        if (archive.preferences.aiConsentVersion != 1) throw IllegalStateException("AI processing consent is required.")
        val module = AndroidLanguages.get(request.learningLanguageID) ?: throw IllegalStateException("Unsupported language.")
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
    private var inForeground = true
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
                val loaded = withContext(Dispatchers.IO) { repository.load() to credentials.hasKey }
                archive = loaded.first.archive
                val providers = providerStore.read(if (loaded.second) ConversationProvider.PERSONAL_KEY else ConversationProvider.HOSTED_MINUTES)
                hostedSessionIDs = providers.hostedIDs
                // An imported backup carries On-Device Model provenance, so rebuild it too.
                onDeviceSessionIDs = providers.onDeviceIDs +
                    loaded.first.archive.sessions.filter { it.providerID == ON_DEVICE_PROVIDER_ID }.map { it.id }
                pendingHostedOwnerID = providers.pendingOwnerID
                accountChangeBlocked = providers.pendingOwnerID != null
                guests?.recoverAcknowledgedOwnerAtStartup(pendingHostedOwnerID,
                    clear = { owner -> clearAcknowledgedGuestMarker(owner) },
                    onFailure = { presentError(getApplication<Application>().getString(R.string.error_guest_secure_storage_unavailable)) })
                conversationProvider = providers.selection
                untimedConversation = providers.untimedOnDevice
                // The Model Runtime's RAM follows the explicit choice: On-Device Model loads the
                // active model, any other provider keeps only its stored selection.
                if (providers.selection == ConversationProvider.ON_DEVICE) onDeviceModels.ensureLoaded()
                else onDeviceModels.refresh()
                restoreOnDeviceContinuation()
                finalAssessmentTickets = ConversationProviderPolicy.recoveryTickets(loaded.first.finalAssessments, hostedSessionIDs, onDeviceSessionIDs)
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
        transport.onEvent = ::deliver
        transport.onFailure = { fail(it) }
        transport.onLevels = ::applyLevels
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
            // An On-Device Model session keeps no recovery ledger and needs no key: its model
            // answers the final assessment now, or the passage stays honestly unassessed.
            else if (snapshot.id in onDeviceSessionIDs) storageReady && onDeviceReadiness.ready &&
                archive.preferences.aiConsentVersion == 1 && current != null
            else if (!storageReady || !hasKey || archive.preferences.aiConsentVersion != 1 || current == null ||
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
        if (!storageReady || !hasKey || archive.preferences.aiConsentVersion != 1) return
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
        finalAssessmentTickets = ConversationProviderPolicy.enqueueRecovery(s, finalAssessmentTickets, hostedSessionIDs, onDeviceSessionIDs)
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
            res == R.string.error_http_generic && e is APIClient.APIException.Http -> app.getString(res, e.status)
            res != 0 -> app.getString(res)
            e is LiveTransport.TransportException -> e.message ?: app.getString(fallback)
            else -> app.getString(fallback)
        }
        if (e is APIClient.APIException.Http && e.reference != null)
            return message + "\n\n" + app.getString(R.string.error_provider_reference, e.reference)
        return requestErrorReference(e)?.let { message + "\n\n" + app.getString(R.string.hosted_error_reference, it) } ?: message
    }
    private fun presentError(message: String, needsKeySetup: Boolean = false) {
        error = message; errorNeedsKeySetup = needsKeySetup; errorNeedsAccountSignIn = false
    }
    private fun presentError(e: Throwable, @StringRes fallback: Int) {
        if (e is ModelRuntimeException) android.util.Log.e("MuralOnDevice", "On-device runtime error: ${e.message}", e)
        if (conversationProvider == ConversationProvider.PERSONAL_KEY && e is APIClient.APIException.Http)
            providerIssue = e.kind
        presentError(resolveMessage(e, fallback), errorNeedsKeySetup(e))
        errorNeedsAccountSignIn = needsAccountRecovery(e)
    }
    private fun cloudReady(): Boolean {
        if (!storageReady) { presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first)); return false }
        if (archive.preferences.aiConsentVersion != 1) {
            presentError(getApplication<Application>().getString(R.string.error_accept_ai_consent)); return false
        }
        val currentHosted = session?.id in hostedSessionIDs
        // An On-Device Model conversation needs its model, never a key or a minute balance.
        if (!currentHosted && session?.id !in onDeviceSessionIDs &&
            conversationProvider == ConversationProvider.ON_DEVICE && !onDeviceReadiness.ready) {
            // A learner mid-download or mid-load is told which of the two it is, and never pointed
            // at another Conversation Provider.
            val onDevice = onDeviceReadiness
            presentError(getApplication<Application>().getString(when {
                onDevice.download != null -> R.string.error_on_device_downloading
                onDevice.preparing -> R.string.error_on_device_preparing
                else -> R.string.error_on_device_model_needed
            }))
            return false
        }
        if (!currentHosted && conversationProvider == ConversationProvider.PERSONAL_KEY && !hasKey) {
            presentError(getApplication<Application>().getString(R.string.error_missing_key), needsKeySetup = true); return false
        }
        return true
    }
    /** Called by the minutes/account UI after an explicit provider choice. No failure changes it. */
    fun selectConversationProvider(provider: ConversationProvider) {
        if (isRunning || !storageReady) return
        if (provider != conversationProvider) clearContinuation()
        conversationProvider = provider
        viewModelScope.launch {
            try { providerStore.select(provider) }
            catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.provider_preference_save_failed)) }
        }
        if (provider == ConversationProvider.HOSTED_MINUTES) refreshHostedReadiness()
        // Switching to On-Device Model loads the active model into the Model Runtime; switching away
        // releases that RAM and keeps the selection, so a cloud provider never pays for a held model.
        if (provider == ConversationProvider.ON_DEVICE) onDeviceModels.ensureLoaded() else onDeviceModels.unload()
    }
    /** Turns the Untimed Conversation option on or off; it reaches On-Device Model sessions only. */
    fun setUntimedOnDevice(enabled: Boolean) {
        untimedConversation = enabled
        viewModelScope.launch {
            try { providerStore.setUntimedOnDevice(enabled) }
            catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.provider_preference_save_failed)) }
        }
    }
    /** A source preview never creates a hosted lease or changes who pays. */
    suspend fun checkHostedBalanceForSwitch(): Long? {
        if (isRunning || accountChangeBlocked || !storageReady) return null
        return try {
            if (availableHostedOwner() == null) guests?.acquire()
            val owner = availableHostedOwner() ?: return null
            val enabled = hostedClient(owner.accountID).available()
            val balance = if (enabled) hostedBalance(owner) else return null
            balance.availableMilliseconds.takeIf { availableHostedOwner() == owner && !isRunning }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    fun onAccountChanged(account: AccountState) {
        selectedAccount = account
        continuationReady = false
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
                    restoreContinuation(member.accountID)
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
                refreshContinuation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readiness.selectAccount(null, false) }
        }
    }
    fun dismissMinuteAccess() { showMinuteAccess = false }
    fun needsMinuteAccess(): Boolean {
        if (!ConversationProviderPolicy.consumesMinutes(conversationProvider) || isRunning) return false
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

    /** The creating session, or the explicit selection when none exists, decides what answers offline. */
    private fun answersOnDevice(id: String?) = id in onDeviceSessionIDs ||
        (id == null && conversationProvider == ConversationProvider.ON_DEVICE)

    /** The creating session, rather than the currently selected settings option, chooses every helper. */
    private suspend fun teaching(localID: String?, purpose: HelperPurpose, logicalID: String,
        instructions: String, input: String, schema: JsonObject? = null, search: Boolean = false, onText: ((String) -> Unit)? = null): APIResult {
        if (archive.preferences.aiConsentVersion != 1) throw HostedFailure.Unavailable
        if (localID != null && localID in hostedSessionIDs) {
            return hostedBindings.respond(localID, purpose, logicalID, instructions, input, schema, search, onText)
        }
        // The creating On-Device Model session answers every Helper on the phone.
        if (answersOnDevice(localID)) {
            return if (onText != null && purpose == HelperPurpose.MEANING) onDeviceTeaching.streamMeaning(instructions, input, onText)
            else onDeviceTeaching.respond(instructions, input, schema, search, purpose)
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
            repeat(40) {
                if (settleHostedSessions()) { refreshHostedReadiness(); return@launch }
                delay(15_000)
            }
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
            if (balance.reservedMilliseconds != 0L || balance.paid?.reservedNanoUSD?.toBigInteger()?.signum() == 1 ||
                balance.presentation?.settlementState?.let { it != "settled" } == true) return false
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
    fun saveKey(key: String, useAfterSave: Boolean = true) {
        if (isRunning) return
        try { credentials.save(key); hasKey = credentials.hasKey; providerIssue = null; if (useAfterSave) selectConversationProvider(ConversationProvider.PERSONAL_KEY); recoverFinalAssessments(); notice = getApplication<Application>().getString(R.string.notice_key_saved) }
        catch (e: Exception) { presentError(e, R.string.error_key_save_failed) }
    }
    fun deleteKey() {
        if (isRunning) return
        try { credentials.delete(); hasKey = false; providerIssue = null }
        catch (e: Exception) { presentError(e, R.string.error_key_delete_failed) }
        finally { hasKey = credentials.hasKey }
    }
    fun updatePreferences(preferences: Preferences) {
        if (isRunning || !storageReady) return
        if (AndroidLanguages.get(preferences.learningLanguageID) == null || preferences.meaningLanguage !in MeaningLanguages.all || preferences.sessionMinutes !in 1..60) return
        val languageChanged = preferences.learningLanguageID != language.id
        actionJob?.cancel(); clearLookup(); working = false
        if (preferences.aiConsentVersion != 1) {
            finalAssessments.cancelAll(); hostedBindings.disableHelpers()
            hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
        }
        generation++; meanings.reset()
        if (languageChanged) resetConversation()
        archive = archive.copy(preferences = preferences.copy(interests = preferences.interests.take(500)))
        // Another Language Module can be one this phone's speech recognition alternate does not hear.
        refreshOnDeviceVoice()
        persist(); scheduleTranslation(); recoverFinalAssessments(); refreshHostedReadiness()
    }
    fun selectLanguage(id: String) {
        if (!isRunning && AndroidLanguages.get(id) != null) updatePreferences(archive.preferences.copy(learningLanguageID = id))
    }
    private var startingWithHistory = false
    fun chooseTheme(theme: ConversationTheme?) {
        if (!isRunning && session != null) resetConversation()
        selectedTheme = theme
        if (theme?.id != "current") topicResult = null
        if (state == "active" || state == "connecting") {
            updateSession { it.themeID = theme?.id; it.title = theme?.title ?: language.defaultTitle }
            // The opening instruction applies a selection made while connecting.
            startingWithHistory = false
            if (state == "active") command("instructions", TeachingPolicy.theme(theme, language))
        }
    }
    fun toggleMeaning() {
        archive = archive.copy(preferences = archive.preferences.copy(meaningVisible = !archive.preferences.meaningVisible)); persist()
        meanings.reset()
        if (archive.preferences.meaningVisible) scheduleTranslation()
    }
    fun toggleMute() {
        if (state != "active" || !voiceSession) return
        isMuted = !isMuted
        onDeviceVoice?.mute(isMuted) ?: transport.mute(isMuted)
    }
    fun help() {
        if (state != "active") return
        if (voiceSession) { activity.learnerEngaged(activityNow()); inactivitySeconds = null; conversationPace.askForHelp(session?.passages?.lastOrNull { it.speaker == Speaker.user }); command("instructions", conversationPace.instruction); command("instructions", TeachingPolicy.help(language)); notice = getApplication<Application>().getString(R.string.notice_help_simpler) }
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
        if (conversationProvider == ConversationProvider.ON_DEVICE) {
            // Session Provenance, recorded once and never rewritten. No remote usage can arrive
            // for an offline session, so its local counts are final.
            record.providerID = ON_DEVICE_PROVIDER_ID
            record.usageFinal = true
            onDeviceSessionIDs = onDeviceSessionIDs + id
            // shortcut: best-effort store write; providerID in the archive rebuilds this set at startup.
            viewModelScope.launch { runCatching { providerStore.markOnDevice(id) } }
        }
        durationBaseline = record.startedAt
        session = record; save(record)
    }
    fun start() {
        // A Typed-only Module has no voice on any provider: never ask for the microphone, never open
        // a voice session. Its conversation starts through sendTyped().
        if (language.isTypedOnly) return
        if (!inForeground || isRunning || !cloudReady()) return
        if (hasContinuation && !continuationReady) { reconcileHostedSessions(); refreshHostedReadiness(); return }
        val continuing = continuationSession
        val continuingOwner = continuationOwnerID
        val choice = conversationProvider
        // Voice Talk on the phone: the learner's speech goes to the active model and the phone's own
        // text-to-speech speaks the streamed reply. No network call is made, and no cloud provider,
        // key or minute balance takes part.
        if (choice == ConversationProvider.ON_DEVICE) { startOnDeviceVoice(continuing); return }
        // An Android-first Language Module (Portuguese Portugal, Japanese) has no hosted voice yet:
        // `services/api/src/live-provider.ts` does not list its locale. Refuse it here, before the
        // foreground service and before any provider call, and name the two providers that can —
        // never switch the learner's provider for them.
        if (choice == ConversationProvider.HOSTED_MINUTES && LanguageRegistry.all.none { it.locale == language.locale }) {
            presentError(getApplication<Application>()
                .getString(R.string.hosted_language_unsupported, language.settingsTitle)); return
        }
        if (choice == ConversationProvider.HOSTED_MINUTES && accountChangeBlocked) {
            presentError(getApplication<Application>().getString(R.string.hosted_checking_previous))
            reconcileHostedSessions(); return
        }
        if (!ConversationProviderPolicy.canStart(choice, hasKey, hostedReadiness, onDeviceReadiness)) {
            if (choice == ConversationProvider.HOSTED_MINUTES) {
                showMinuteAccess = true; refreshHostedReadiness()
            } else presentError(getApplication<Application>().getString(R.string.error_missing_key), true)
            return
        }
        newSession(true); state = "connecting"
        val id = session!!.id
        try {
            // Start while visible and after microphone consent, before Android 15 audio focus.
            VoiceConversationService.start(getApplication(), id) { end("Ended by you") }
        } catch (error: Exception) {
            fail(error, R.string.error_voice_connect_failed); return
        }
        val module = language
        val instructions = TeachingPolicy.voice(module, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage)
        val history = ConversationHistory.messages(continuing ?: session)
        startingWithHistory = history.isNotEmpty()
        if (choice == ConversationProvider.HOSTED_MINUTES) accountChangeBlocked = true
        connectionJob = viewModelScope.launch {
            try {
                val provider: LiveSessionProvider = if (choice == ConversationProvider.PERSONAL_KEY) api else {
                    val owner = requireHostedOwner()
                    if (selectedAccount.busy || owner.accountID != hostedReadiness.accountID ||
                        (continuingOwner != null && continuingOwner != owner.accountID)) throw HostedFailure.SignInRequired
                    val hosted = hostedClient(owner.accountID)
                    val balance = hostedBalance(owner)
                    if (!balance.canStartConversation || !hosted.available()) throw HostedFailure.Unavailable
                    freeBoundaryOwnerID = if (balance.availableMilliseconds in 1 until archive.preferences.sessionMinutes * 60_000L &&
                        balance.paid?.available == true) owner.accountID else null
                    if (freeBoundaryOwnerID != null) notice = getApplication<Application>().getString(R.string.notice_free_minutes_first)
                    // Commit provider provenance and the unresolved-owner marker before making a paid create.
                    hostedSessionIDs = hostedSessionIDs + id
                    pendingHostedOwnerID = owner.accountID
                    withContext(NonCancellable) { providerStore.markHosted(id, owner.accountID) }
                    object : LiveSessionProvider {
                        override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection {
                            val result = hosted.createLiveSession(request.copy(requestedMilliseconds = archive.preferences.sessionMinutes * 60_000L))
                            val lease = result.lease as? HostedAPIClient.HostedLease ?: throw HostedFailure.InvalidResponse
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                if (lease.paid) freeBoundaryOwnerID = null
                                hostedBindings.bind(id, owner.accountID, binding(lease))
                                if (session?.id != id || state != "connecting") {
                                    hostedBindings.ended(id); reconcileHostedSessions()
                                }
                            }
                            return result
                        }
                    }
                }
                transport.connect(provider, instructions, history, module.locale)
            } catch (cancelled: CancellationException) {
                if (choice == ConversationProvider.HOSTED_MINUTES) reconcileHostedSessions()
                throw cancelled
            } catch (e: Exception) {
                if (session?.id == id && isRunning) fail(e, R.string.error_voice_connect_failed)
                if (choice == ConversationProvider.HOSTED_MINUTES) reconcileHostedSessions()
            }
        }
    }
    /**
     * Opens a voice conversation on this phone. The learner's speech has to reach the model: through
     * its own native audio input, or through the downloaded speech recognition alternate, which also
     * lets a text-only model hear. With neither, a voice session is honestly refused and never
     * substituted by another provider — typed Talk keeps working.
     */
    private fun startOnDeviceVoice(continuing: SessionRecord?) {
        val app = getApplication<Application>()
        // Resolved against the active Language Module's locale, so a speech recognition alternate
        // that does not hear this language (Parakeet for Mandarin or Japanese) falls back honestly.
        val speechEngine = onDeviceModels.resolvedSpeechInput(language.locale)
        if (speechEngine == null) {
            presentError(app.getString(R.string.error_on_device_no_audio_input)); return
        }
        // The same microphone gate the cloud transport applies, so a revoked permission is an
        // honest refusal instead of a silent session.
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            presentError(app.getString(R.string.error_transport_microphone)); return
        }
        newSession(true); state = "connecting"
        val id = session!!.id
        try {
            // The same foreground service holds the microphone and playback as for cloud voice.
            VoiceConversationService.start(app, id) { end("Ended by you") }
        } catch (error: Exception) {
            fail(error, R.string.error_voice_connect_failed); return
        }
        val module = language
        val instructions = TeachingPolicy.voice(module, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage)
        val history = ConversationHistory.messages(continuing ?: session)
        startingWithHistory = history.isNotEmpty()
        val onDevice = OnDeviceLiveSession(viewModelScope, onDeviceModels.turn,
            onDeviceModels.speechInput(speechEngine),
            // The teacher's voice: the learner's open-weight pick for this Language Module, or the
            // phone's built-in voice, which a missing or failing pick falls back to sentence by sentence.
            onDeviceModels.speechOutput(module.id, module.locale),
            // The model calls back on the engine's own thread; events land on the main one in order.
            onEvent = { event -> viewModelScope.launch { deliver(event) } },
            onLevels = ::applyLevels,
            onBusy = { answering = it },
            onFailure = { error -> if (session?.id == id && isRunning) fail(error, R.string.error_voice_connect_failed) })
        onDeviceVoice = onDevice
        // Only a native audio input turn needs the model to report what it heard: an alternate
        // already transcribed the learner, so its turns carry no <heard> rule overhead.
        onDevice.start(instructions, module.locale,
            nativeAudioInput = speechEngine == SpeechInputEngine.NATIVE_AUDIO, history = history)
    }
    fun end(reason: String = "Ended by you") {
        if (state !in listOf("active", "connecting")) return
        val connecting = state == "connecting"
        state = "closing"; isMuted = true
        connectionJob?.cancel(); durationJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel(); clearLookup()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear(); working = false
        updateSession { it.endReason = reason }
        if (!voiceSession || connecting) { finish(false); return }
        // A local conversation closes at once; a cloud one keeps the existing five-second guard.
        onDeviceVoice?.close() ?: transport.close()
        closeJob = viewModelScope.launch { delay(5000); if (state == "closing") finish(false) }
    }
    fun background() {
        inForeground = false
        // Screen state is not learner inactivity. The foreground service owns this call.
        if (isRunning && voiceSession && VoiceConversationService.holds(session?.id)) return
        generation++; actionJob?.cancel(); clearLookup(); meanings.reset(); working = false
        if (isRunning) { updateSession { it.endReason = "App moved to background" }; finish(false) }
    }
    fun foreground() { inForeground = true }
    private fun finish(final: Boolean) {
        if (!isRunning) return
        if (ConversationContinuationPolicy.reachedFreeBoundary(freeBoundaryOwnerID != null,
                session?.endReason, session?.id?.let(hostedBindings::reachedDeadline) == true)) {
            updateSession { it.endReason = "Reserved conversation time ended" }
        }
        connectionJob?.cancel(); durationJob?.cancel(); closeJob?.cancel(); assessmentJob?.cancel()
        actionJob?.cancel(); clearLookup(); languageCheckJob?.cancel()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear()
        onDeviceVoice?.close(); onDeviceVoice = null
        transport.disconnect(); inputLevel = 0.0; outputLevel = 0.0; working = false; isMuted = false
        answering = false
        VoiceConversationService.stop(getApplication(), session?.id)
        updateSession { it.endedAt = nowSeconds(); it.usageFinal = final || it.id in onDeviceSessionIDs }
        state = "ended"
        notice = when (session?.endReason) {
            "Inactivity" -> getApplication<Application>().getString(R.string.notice_ended_inactivity)
            "Time limit" -> getApplication<Application>().getString(R.string.notice_time_limit_reached)
            else -> null
        }
        if (freeBoundaryOwnerID != null && session?.endReason in listOf("Time limit", "Reserved conversation time ended")) {
            continuationSession = session; continuationOwnerID = freeBoundaryOwnerID
            hasContinuation = true; continuationReady = false; continuationWasVoice = voiceSession
            session?.let { saved ->
                continuationPreferences.edit().putString(continuationKey, json.encodeToString(
                    kotlinx.serialization.serializer<ConversationContinuationCheckpoint>(),
                    ConversationContinuationCheckpoint(saved.id, freeBoundaryOwnerID!!))).apply()
            }
            notice = getApplication<Application>().getString(R.string.notice_continue_settling)
        } else if (ConversationContinuationPolicy.reachedOnDeviceTimeLimit(session?.id in onDeviceSessionIDs, session?.endReason)) {
            // The identical Continue/New choice, with nothing to settle: an On-Device Model
            // conversation consumed no minutes, so it is ready to continue at once. Its checkpoint
            // survives process death, so a low-memory kill cannot lose the conversation either.
            continuationSession = session; continuationOwnerID = null
            hasContinuation = true; continuationReady = true; continuationNeedsMinutes = false
            continuationWasVoice = voiceSession
            session?.let(::saveOnDeviceContinuationCheckpoint)
        }
        session?.let {
            if (it.id in hostedSessionIDs) {
                hostedBindings.ended(it.id); reconcileHostedSessions(); finishHostedAssessment(clone(it))
            } else finalAssessments.submit(clone(it))
        }
        scheduleTranslation(utteranceComplete = true)
        if (!hasContinuation) resetJob = viewModelScope.launch { delay(15000); if (state == "ended" && !hasContinuation) resetConversation() }
    }
    private fun fail(message: String, needsKeySetup: Boolean = false) { finish(false); resetJob?.cancel(); state = "failed"; presentError(message, needsKeySetup) }
    private fun fail(e: Throwable, @StringRes fallback: Int) {
        if (conversationProvider == ConversationProvider.PERSONAL_KEY && e is APIClient.APIException.Http)
            providerIssue = e.kind
        fail(resolveMessage(e, fallback), errorNeedsKeySetup(e))
        errorNeedsAccountSignIn = needsAccountRecovery(e)
    }
    fun resetConversation() {
        if (isRunning) return
        clearContinuation()
        generation++; resetJob?.cancel(); actionJob?.cancel(); clearLookup(); assessmentJob?.cancel(); languageCheckJob?.cancel(); meanings.reset()
        session = null; selectedTheme = null; topicResult = null
        notice = null; working = false; state = "idle"; voiceSession = false
    }
    private fun command(kind: String, content: String, delegationID: String? = null): Boolean {
        if (state != "active" || !voiceSession) return false
        // An on-device conversation has no data channel: an instruction is a turn of its own model,
        // and no delegation ever reaches a client offline.
        onDeviceVoice?.let { onDevice ->
            return onDevice.command(kind, content)
                .also { if (!it) notice = getApplication<Application>().getString(R.string.notice_update_send_failed) }
        }
        return transport.send(buildJsonObject {
            put("type", "session.$kind.append"); put("event_id", UUID.randomUUID().toString())
            put("delegation_id", delegationID?.let(::JsonPrimitive) ?: JsonNull); put("content", content.take(1000))
        }).also { if (!it) notice = getApplication<Application>().getString(R.string.notice_update_send_failed) }
    }
    /** One provider event, from the cloud transport or from the local voice conversation alike. */
    private fun deliver(event: JsonObject) {
        try { handle(event) }
        catch (_: IllegalArgumentException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
        catch (_: IllegalStateException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
    }
    /** The orb's two energies, and the activity the idle timer is built from. */
    private fun applyLevels(input: Double, output: Double) {
        inputLevel = input; outputLevel = output
        if (state == "active" && voiceSession) {
            if (input > 0.03) activity.inputActive(activityNow())
            if (output > 0.03) activity.assistantActive(activityNow())
        }
    }
    private fun handle(event: JsonObject) {
        if (session == null || !isRunning) return
        when (event["type"]?.jsonPrimitive?.content) {
            "mural.session.created" -> updateSession { it.providerID = (event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.content; it.voiceSeconds = 15.0 }
            "session.started" -> if (state == "connecting") {
                declineOnDeviceContinuation()
                continuationSession = null; continuationOwnerID = null; hasContinuation = false; continuationReady = false
                continuationWasVoice = false
                continuationPreferences.edit().remove(continuationKey).apply()
                state = "active"; activity = ConversationActivity(activityNow()); conversationPace = ConversationPace(); inactivitySeconds = null
                if (conversationProvider == ConversationProvider.PERSONAL_KEY) providerIssue = null
                updateSession { it.providerID = (event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: it.providerID }
                if (selectedTheme?.id == "current") topicResult?.let { command("thinking", "Sourced context, data: ${it.text}") }
                command("instructions", TeachingPolicy.greeting(language, selectedTheme, startingWithHistory)); startDurationChecks()
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
            "error" -> {
                val code = (event["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
                val issue = if (conversationProvider == ConversationProvider.PERSONAL_KEY)
                    ProviderFailureKind.realtimeCode(code) else null
                if (issue != null) {
                    val status = if (issue == ProviderFailureKind.authentication) 401 else 429
                    fail(APIClient.APIException.Http(status, code), R.string.error_voice_connect_failed)
                } else notice = getApplication<Application>().getString(R.string.notice_voice_update_rejected)
            }
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
                // An Untimed Conversation never ends here: the idle end and the manual end still do.
                if (ConversationProviderPolicy.endsForDuration(current.id in onDeviceSessionIDs, untimedOnDevice,
                        nowSeconds() - durationBaseline, archive.preferences.sessionMinutes)) {
                    notice = getApplication<Application>().getString(R.string.notice_time_limit_reached); end("Time limit"); break
                }
                inactivitySeconds = null
                if (voiceSession) when (val next = activity.tick(activityNow(), isMuted, working || delegations.isNotEmpty() || answering)) {
                    ConversationActivity.Action.CheckIn -> command("instructions", TeachingPolicy.checkIn(language))
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
                command("instructions", TeachingPolicy.redirect(module))
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
        val module = AndroidLanguages.get(snapshot.languageID) ?: throw IllegalStateException("Unsupported language.")
        val onDevice = answersOnDevice(snapshot.id)
        val result = teaching(snapshot.id, HelperPurpose.ASSESSMENT, passage.revisionKey,
            TeachingPolicy.assessment(module), helperContext(snapshot, passage), assessmentSchema(module.id))
        // An On-Device Model reply is parsed defensively; a cloud reply keeps its strict decode.
        val proposal = if (onDevice) OnDeviceAssessmentExtractor.proposal(result.text)
            ?: throw ModelRuntimeException("The On-Device Model returned no usable assessment.")
        else json.decodeFromString<AssessmentResponse>(result.text)
            .let { AssessmentProposal(it.outcome, it.suggestedLevel, it.nextGoal, it.capability, it.words) }
        val assessment = Assessment(passage.id, passage.revisionKey, proposal.outcome, proposal.suggestedLevel,
            proposal.nextGoal, proposal.capability, proposal.words,
            context = ConversationProviderPolicy.assessmentContext(onDevice, snapshot.themeID), textAssemblyVersion = 2)
        return FinalAssessmentResult(snapshot.id, snapshot.languageID, assessment, result.usage.input, result.usage.output, result.usage.searches)
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
        // The delegation Helper is a web search, so an On-Device Model session never offers it:
        // no model and no network call, only the language's existing honest refusal.
        if (answersOnDevice(sessionID)) { command("commentary", language.lookupUnavailableReply, id); return }
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
                // Mural minutes carry voice leases only, and a Typed-only Module has no voice at all.
                // The provider is never switched for the learner: name the two that can type.
                if (language.isTypedOnly) typedReplyError = getApplication<Application>().getString(R.string.hosted_typed_only_provider)
                else presentError(getApplication<Application>().getString(R.string.hosted_typed_start))
                return
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
                // The current-topic theme must never promise a delegated, source-backed lookup offline.
                val offlineTopic = if (answersOnDevice(id) && selectedTheme?.id == "current") "\n" + OnDeviceTeachingRules.offlineCurrentTopic else ""
                val instructions = if (voiceSession) TeachingPolicy.typedReply(language) else TeachingPolicy.voice(language, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage) + "\n" + TeachingPolicy.typedReply(language) + offlineTopic
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
        // A sourced current topic needs the web: an On-Device Model session makes no network call.
        if (answersOnDevice(session?.id)) {
            presentError(getApplication<Application>().getString(R.string.error_topic_offline)); return
        }
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
            topicResult = brief; selectedTheme = currentTheme(brief)
            updateSession {
                it.themeID = selectedTheme?.id; it.title = brief.query
                if (it.topics.none { t -> t.id == brief.id }) it.topics += brief
            }
            if (voiceSession) {
                command("thinking", "Sourced context, data: ${brief.text}"); command("instructions", TeachingPolicy.theme(selectedTheme, language))
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
    /** Imports a learner-chosen `.litertlm` file as the active On-Device Model. */
    fun importOnDeviceModel(uri: android.net.Uri) { onDeviceModels.importModel(uri) }
    fun importData(data: String): Boolean {
        if (isRunning || !storageReady) return false
        return try { archive = ArchiveCodec.merge(archive, prepareImportedArchive(data)); persist(); notice = getApplication<Application>().getString(R.string.notice_backup_imported); true }
        catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.error_import_failed)); false }
    }
    override fun onCleared() {
        if (isRunning) finish(false)
        VoiceConversationService.stop(getApplication(), session?.id)
        onDeviceModels.close()
        hostedBindings.disableHelpers(); transport.disconnect(); super.onCleared()
    }
}
