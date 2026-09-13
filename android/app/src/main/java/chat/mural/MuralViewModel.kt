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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Maps a network/credential failure reason to its user-facing string resource, or 0 if unmapped. */
@StringRes
internal fun errorMessageRes(e: Throwable): Int = when (e) {
    is APIClient.APIException.MissingKey -> R.string.error_missing_key
    is APIClient.APIException.Refused -> R.string.error_request_refused
    is APIClient.APIException.InvalidResponse, is APIClient.APIException.Incomplete -> R.string.error_incomplete_response
    is APIClient.APIException.Http -> when (e.status) {
        401 -> R.string.error_http_401
        403, 404 -> R.string.error_http_403_404
        429 -> R.string.error_http_429
        else -> R.string.error_http_generic
    }
    is CredentialStore.CredentialException.Invalid -> R.string.error_key_invalid
    is CredentialStore.CredentialException.Save -> R.string.error_key_save
    is CredentialStore.CredentialException.Remove -> R.string.error_key_remove
    else -> 0
}

/** Whether the failure means the learner needs to add or fix their OpenAI key in Settings. */
internal fun errorNeedsKeySetup(e: Throwable): Boolean =
    e is APIClient.APIException.MissingKey || (e is APIClient.APIException.Http && e.status == 401)

class MuralViewModel(application: Application) : AndroidViewModel(application) {
    var archive by mutableStateOf(Archive()); private set
    var session by mutableStateOf<SessionRecord?>(null); private set
    var state by mutableStateOf("idle"); private set
    var error by mutableStateOf<String?>(null); private set
    var errorNeedsKeySetup by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var meaning by mutableStateOf(""); private set
    var translating by mutableStateOf(false); private set
    var meaningFailed by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    var isMuted by mutableStateOf(false); private set
    var inputLevel by mutableStateOf(0.0); private set
    var outputLevel by mutableStateOf(0.0); private set
    var selectedTheme by mutableStateOf<ConversationTheme?>(null); private set
    var lookupResult by mutableStateOf<String?>(null); private set
    var topicResult by mutableStateOf<TopicBrief?>(null); private set
    var hasKey by mutableStateOf(false); private set
    val language get() = LanguageRegistry.get(archive.preferences.learningLanguageID)!!
    val learner get() = LearningEngine.project(archive.sessions, language.id, archive.preferences.hiddenWords)
    val isRunning get() = state in listOf("connecting", "active", "closing")
    val isVoiceSession get() = voiceSession

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val repository = LearningRepository(application)
    private val credentials = CredentialStore(application)
    private val api = APIClient(credentials)
    private val transport = LiveTransport(application, viewModelScope)
    private var storageReady = true
    private var connectionJob: Job? = null
    private var durationJob: Job? = null
    private var closeJob: Job? = null
    private var resetJob: Job? = null
    private var assessmentJob: Job? = null
    private var actionJob: Job? = null
    private val meanings = MeaningController(viewModelScope) { request ->
        if (archive.preferences.aiConsentVersion != 1) throw IllegalStateException("AI processing consent is required.")
        val module = LanguageRegistry.get(request.learningLanguageID) ?: throw IllegalStateException("Unsupported language.")
        val result = api.respond(TeachingPolicy.translation(module, request.meaningLanguage), request.text.takeLast(2200))
        MeaningResult(result.text, result.usage.input, result.usage.output)
    }
    private val finalAssessments = FinalAssessmentQueue(viewModelScope) { snapshot, passage -> requestAssessment(snapshot, passage) }
    private val languageDetector = LanguageDetector(application)
    private var languageCheckJob: Job? = null
    private var lastLanguageRedirect: String? = null
    private val delegations = mutableMapOf<String, Job>()
    private var voiceSession = false
    private var lastActivity = nowSeconds()
    private var generation = 0

    init {
        try { archive = repository.load(); hasKey = credentials.hasKey }
        catch (_: Exception) {
            storageReady = false
            presentError(getApplication<Application>().getString(R.string.error_local_history_unavailable))
        }
        repository.onSaveFailed = {
            viewModelScope.launch { presentError(getApplication<Application>().getString(R.string.error_save_progress_failed)) }
        }
        transport.onEvent = { event ->
            try { handle(event) }
            catch (_: IllegalArgumentException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
            catch (_: IllegalStateException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
        }
        transport.onFailure = { fail(it) }
        transport.onLevels = { input, output ->
            inputLevel = input; outputLevel = output
            if (input > 0.03 || output > 0.03) lastActivity = nowSeconds()
        }
        meanings.onChange = { meaning = meanings.text; translating = meanings.isLoading; meaningFailed = meanings.error != null }
        meanings.onResult = { request, result ->
            if (session?.id == request.sessionID) updateSession {
                it.translations[request.cacheKey] = result.text
                addUsage(it, APIUsage(result.inputTokens, result.outputTokens))
            }
        }
        finalAssessments.onResult = { result ->
            result.applying(archive.sessions.firstOrNull { it.id == result.sessionID })?.let { updated ->
                save(updated)
                if (session?.id == updated.id) session = clone(updated)
            }
        }
    }

    private fun clone(s: SessionRecord): SessionRecord = json.decodeFromString(json.encodeToString(s))
    private fun persist() {
        if (!storageReady) return
        // Isolate the queued snapshot from all mutable domain model lists.
        repository.enqueue(json.decodeFromString(json.encodeToString(archive)))
    }
    private fun save(s: SessionRecord) {
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
        return when {
            res == R.string.error_http_generic && e is APIClient.APIException.Http -> app.getString(res, e.status)
            res != 0 -> app.getString(res)
            e is LiveTransport.TransportException -> e.message ?: app.getString(fallback)
            else -> app.getString(fallback)
        }
    }
    private fun presentError(message: String, needsKeySetup: Boolean = false) {
        error = message; errorNeedsKeySetup = needsKeySetup
    }
    private fun presentError(e: Throwable, @StringRes fallback: Int) {
        presentError(resolveMessage(e, fallback), errorNeedsKeySetup(e))
    }
    private fun cloudReady(): Boolean {
        if (!storageReady) { presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first)); return false }
        if (archive.preferences.aiConsentVersion != 1) {
            presentError(getApplication<Application>().getString(R.string.error_accept_ai_consent)); return false
        }
        if (!hasKey) { presentError(getApplication<Application>().getString(R.string.error_missing_key), needsKeySetup = true); return false }
        return true
    }
    fun dismissError() { error = null; errorNeedsKeySetup = false }
    fun clearLookup() { lookupResult = null }
    fun saveKey(key: String) {
        if (isRunning) return
        try { credentials.save(key); hasKey = credentials.hasKey; notice = getApplication<Application>().getString(R.string.notice_key_saved) }
        catch (e: Exception) { presentError(e, R.string.error_key_save_failed) }
    }
    fun deleteKey() {
        if (isRunning) return
        try { credentials.delete() }
        catch (e: Exception) { presentError(e, R.string.error_key_delete_failed) }
        finally { hasKey = credentials.hasKey }
    }
    fun updatePreferences(preferences: Preferences) {
        if (isRunning || !storageReady) return
        if (LanguageRegistry.get(preferences.learningLanguageID) == null || preferences.meaningLanguage !in MeaningLanguages.all || preferences.sessionMinutes !in 1..60) return
        val languageChanged = preferences.learningLanguageID != language.id
        actionJob?.cancel(); working = false
        if (preferences.aiConsentVersion != 1) finalAssessments.cancelAll()
        generation++; meanings.reset()
        if (languageChanged) resetConversation()
        archive = archive.copy(preferences = preferences.copy(interests = preferences.interests.take(500)))
        persist(); scheduleTranslation()
    }
    fun selectLanguage(id: String) {
        if (!isRunning && LanguageRegistry.get(id) != null) updatePreferences(archive.preferences.copy(learningLanguageID = id))
    }
    fun chooseTheme(theme: ConversationTheme?) {
        if (!isRunning && session != null) resetConversation()
        selectedTheme = theme
        if (state == "active") {
            updateSession { it.themeID = theme?.id; it.title = theme?.title ?: language.defaultTitle }
            command("instructions", TeachingPolicy.theme(theme, language))
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
        if (voiceSession) { command("instructions", TeachingPolicy.help(language)); notice = getApplication<Application>().getString(R.string.notice_help_simpler) }
        else {
            if (working || !cloudReady()) return
            val snapshot = session ?: return
            val token = generation; working = true
            actionJob = viewModelScope.launch {
                try {
                    val result = api.respond(TeachingPolicy.help(language) + " Return only your brief explanation.", TeachingPolicy.context(snapshot))
                    if (token != generation || session?.id != snapshot.id || state != "active") return@launch
                    val offset = ((nowSeconds() - snapshot.startedAt) * 1000).toInt().coerceAtLeast(0)
                    updateSession {
                        it.append(Fragment(speaker = Speaker.assistant, text = result.text, startMS = offset, endMS = offset + 1))
                        addUsage(it, result.usage)
                    }
                    lastActivity = nowSeconds(); scheduleTranslation()
                } catch (_: CancellationException) { }
                catch (e: Exception) { if (token == generation) presentError(e, R.string.error_help_failed) }
                finally { if (token == generation) working = false }
            }
        }
    }

    private fun newSession(voice: Boolean) {
        generation++; resetJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel(); working = false; meanings.reset()
        error = null; errorNeedsKeySetup = false; notice = null; isMuted = false
        voiceSession = voice; lastActivity = nowSeconds()
        val record = SessionRecord(languageID = language.id, themeID = selectedTheme?.id, title = selectedTheme?.title ?: language.defaultTitle)
        topicResult?.takeIf { it.languageID == language.id && selectedTheme?.id == "current" }?.let { record.topics += it }
        session = record; save(record)
    }
    fun start() {
        if (isRunning || !cloudReady()) return
        newSession(true); state = "connecting"
        val id = session!!.id
        val instructions = TeachingPolicy.voice(language, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage)
        connectionJob = viewModelScope.launch {
            try { transport.connect(api, instructions) }
            catch (_: CancellationException) { }
            catch (e: Exception) { if (session?.id == id && isRunning) fail(e, R.string.error_voice_connect_failed) }
        }
    }
    fun end(reason: String = "Ended by you") {
        if (state !in listOf("active", "connecting")) return
        val connecting = state == "connecting"
        state = "closing"; isMuted = true
        connectionJob?.cancel(); durationJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear(); working = false
        updateSession { it.endReason = reason }
        if (!voiceSession || connecting) { finish(false); return }
        transport.close()
        closeJob = viewModelScope.launch { delay(5000); if (state == "closing") finish(false) }
    }
    fun background() {
        // Leaving the foreground ends the conversation and releases the microphone; its final assessment still completes.
        generation++; actionJob?.cancel(); meanings.reset(); working = false
        if (isRunning) { updateSession { it.endReason = "App moved to background" }; finish(false) }
    }
    private fun finish(final: Boolean) {
        if (!isRunning) return
        connectionJob?.cancel(); durationJob?.cancel(); closeJob?.cancel(); assessmentJob?.cancel()
        actionJob?.cancel(); languageCheckJob?.cancel()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear()
        transport.disconnect(); inputLevel = 0.0; outputLevel = 0.0; working = false; isMuted = false
        updateSession { it.endedAt = nowSeconds(); it.usageFinal = final }
        state = "ended"
        session?.let { finalAssessments.submit(clone(it)) }
        scheduleTranslation()
        resetJob = viewModelScope.launch { delay(15000); if (state == "ended") resetConversation() }
    }
    private fun fail(message: String, needsKeySetup: Boolean = false) { finish(false); resetJob?.cancel(); state = "failed"; presentError(message, needsKeySetup) }
    private fun fail(e: Throwable, @StringRes fallback: Int) { fail(resolveMessage(e, fallback), errorNeedsKeySetup(e)) }
    fun resetConversation() {
        if (isRunning) return
        generation++; resetJob?.cancel(); actionJob?.cancel(); assessmentJob?.cancel(); languageCheckJob?.cancel(); meanings.reset()
        session = null; selectedTheme = null; topicResult = null
        notice = null; working = false; state = "idle"; voiceSession = false
    }
    private fun command(kind: String, content: String, delegationID: String? = null): Boolean {
        if (state != "active" || !voiceSession) return false
        return transport.send(buildJsonObject {
            put("type", "session.$kind.append"); put("event_id", UUID.randomUUID().toString())
            put("delegation_id", delegationID?.let(::JsonPrimitive) ?: JsonNull); put("content", content.take(1000))
        }).also { if (!it) notice = getApplication<Application>().getString(R.string.notice_update_send_failed) }
    }
    private fun handle(event: JsonObject) {
        if (session == null || !isRunning) return
        when (event["type"]?.jsonPrimitive?.content) {
            "mural.session.created" -> updateSession { it.providerID = (event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.content; it.voiceSeconds = 15.0 }
            "session.started" -> if (state == "connecting") {
                state = "active"; lastActivity = nowSeconds()
                updateSession { it.providerID = (event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: it.providerID }
                command("instructions", TeachingPolicy.greeting(language)); startDurationChecks()
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val text = event["delta"]?.jsonPrimitive?.contentOrNull ?: return
                val start = event["start_ms"]?.jsonPrimitive?.intOrNull ?: return
                val end = event["end_ms"]?.jsonPrimitive?.intOrNull ?: return
                if (start < 0 || end < start || text.length > 50000) return
                val speaker = if (event["type"]?.jsonPrimitive?.content == "session.input_transcript.delta") Speaker.user else Speaker.assistant
                updateSession { it.append(Fragment(id = event["event_id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(), speaker = speaker, text = text, startMS = start, endMS = end, meaningVisible = archive.preferences.meaningVisible)) }
                lastActivity = nowSeconds()
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
                delay(5000)
                val current = session ?: break
                if (nowSeconds() - current.startedAt > archive.preferences.sessionMinutes * 60) {
                    notice = getApplication<Application>().getString(R.string.notice_time_limit_reached); end("Time limit"); break
                }
                if (SessionLimits.endsForInactivity(voiceSession, nowSeconds() - lastActivity)) { notice = getApplication<Application>().getString(R.string.notice_ended_inactivity); end("Inactivity"); break }
            }
        }
    }

    private fun addUsage(s: SessionRecord, usage: APIUsage) {
        s.inputTokens = (s.inputTokens.toLong() + usage.input).coerceAtMost(1_000_000_000).toInt()
        s.outputTokens = (s.outputTokens.toLong() + usage.output).coerceAtMost(1_000_000_000).toInt()
        s.searchCalls = (s.searchCalls.toLong() + usage.searches).coerceAtMost(1_000_000_000).toInt()
    }
    private fun scheduleTranslation() {
        if (!archive.preferences.meaningVisible || archive.preferences.aiConsentVersion != 1) return
        val current = session ?: return
        val passage = current.passages.lastOrNull { it.speaker == Speaker.assistant } ?: return
        val request = MeaningRequest(current.id, passage, current.languageID, archive.preferences.meaningLanguage)
        meanings.update(request, current.translations[request.cacheKey])
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
        val module = LanguageRegistry.get(snapshot.languageID) ?: throw IllegalStateException("Unsupported language.")
        val result = api.respond(TeachingPolicy.assessment(module), TeachingPolicy.context(snapshot, passage), assessmentSchema(module.id))
        val decoded = json.decodeFromString<AssessmentResponse>(result.text)
        val proposal = Assessment(passage.id, passage.revisionKey, decoded.outcome, decoded.suggestedLevel, decoded.nextGoal, decoded.capability, decoded.words, context = snapshot.themeID ?: "free")
        return FinalAssessmentResult(snapshot.id, snapshot.languageID, proposal, result.usage.input, result.usage.output, result.usage.searches)
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
                if (state == "active" && session?.id == snapshot.id) {
                    val progress = learner
                    val targetLanguage = LanguageRegistry.get(snapshot.languageID)?.name ?: language.name
                    val revisit = progress.words.filter { it.dueAt < nowSeconds() }.take(3).joinToString(", ") { it.lemma }
                    command("thinking", "Teaching context, not spoken text: challenge ${progress.challenge}/5 in $targetLanguage. Next goal: ${progress.nextGoal}. Revisit naturally: $revisit.")
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
                val result = api.respond(TeachingPolicy.delegation(language), TeachingPolicy.context(snapshot), search = snapshot.searchCalls < 3)
                if (state != "active" || session?.id != sessionID) return@launch
                updateSession { addUsage(it, result.usage); if (result.sources.isNotEmpty()) it.topics += TopicBrief(languageID = it.languageID, query = getApplication<Application>().getString(R.string.topics_from_conversation), text = result.text, sources = result.sources) }
                command("commentary", result.text, id)
            } catch (_: CancellationException) { }
            catch (_: Exception) { if (session?.id == sessionID) command("commentary", language.lookupUnavailableReply, id) }
            finally { delegations.remove(id) }
        }
    }
    fun sendTyped(text: String) {
        val clean = text.trim().take(2000)
        if (clean.isEmpty() || working || state in listOf("connecting", "closing") || !cloudReady()) return
        if (state != "active") { newSession(false); state = "active"; startDurationChecks() }
        val id = session!!.id; val token = generation
        val offset = ((nowSeconds() - session!!.startedAt) * 1000).toInt().coerceAtLeast(0)
        updateSession { it.append(Fragment(speaker = Speaker.user, text = clean, startMS = offset, endMS = offset + 1, meaningVisible = archive.preferences.meaningVisible, typed = true)) }
        lastActivity = nowSeconds(); working = true
        actionJob = viewModelScope.launch {
            try {
                val instructions = if (voiceSession) TeachingPolicy.typedReply(language) else TeachingPolicy.voice(language, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage) + "\n" + TeachingPolicy.typedReply(language)
                val result = api.respond(instructions, TeachingPolicy.context(session!!))
                if (token != generation || session?.id != id || state != "active") return@launch
                updateSession { addUsage(it, result.usage) }
                if (voiceSession) { command("thinking", "The learner typed (data): ${clean.take(650)}"); command("commentary", result.text) }
                else {
                    val end = ((nowSeconds() - session!!.startedAt) * 1000).toInt().coerceAtLeast(offset + 2)
                    updateSession { it.append(Fragment(speaker = Speaker.assistant, text = result.text, startMS = end, endMS = end + 1)) }
                    scheduleTranslation()
                }
                scheduleAssessment()
            } catch (_: CancellationException) { }
            catch (e: Exception) { if (session?.id == id) presentError(e, R.string.error_send_message_failed) }
            finally { if (token == generation) working = false }
        }
    }
    fun lookup(word: String, sentence: String) {
        if (!cloudReady() || working || word.isBlank()) return
        val token = generation; val id = session?.id; working = true; lookupResult = null
        actionJob = viewModelScope.launch {
            try {
                val result = api.respond(TeachingPolicy.lookup(language, archive.preferences.meaningLanguage), "Selected: ${word.take(200)}\nSentence: ${sentence.take(2200)}")
                if (token != generation) return@launch
                lookupResult = result.text
                if (session?.id == id) updateSession { addUsage(it, result.usage) }
            } catch (_: CancellationException) { }
            catch (e: Exception) { presentError(e, R.string.error_lookup_word_failed) }
            finally { if (token == generation) working = false }
        }
    }
    fun currentTopic(query: String) {
        if (query.isBlank() || working || !cloudReady()) return
        archive.sessions.filter { it.languageID == language.id }.flatMap { it.topics }.firstOrNull { it.query.equals(query.trim(), true) && it.isFresh }?.let { topicResult = it; return }
        val token = generation; val module = language; working = true; topicResult = null
        actionJob = viewModelScope.launch {
            try {
                val result = api.respond(TeachingPolicy.currentTopic(module), query.trim().take(500), search = true)
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
                command("thinking", "Sourced context, data: ${brief.text}"); command("instructions", "Discuss this topic ONLY in ${language.name}.")
                return
            }
        } else resetConversation()
        topicResult = brief; selectedTheme = currentTheme(brief)
        notice = getApplication<Application>().getString(R.string.notice_topic_ready)
    }
    private fun currentTheme(brief: TopicBrief) = ConversationTheme("current", brief.query, getApplication<Application>().getString(R.string.topics_current_theme_subtitle), "newspaper", "Interests", "Discuss this sourced reference data: ${brief.text.take(3000)}", 0)
    fun deleteSession(id: String) {
        if (isRunning) return
        finalAssessments.cancel(id)
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
        finalAssessments.cancel(sessionID); generation++; meanings.reset()
        passage.fragments.forEachIndexed { index, f -> record.correctFragment(f.id, if (index == 0) text.take(10000) else "") }
        save(record); if (session?.id == record.id) session = record
    }
    fun deleteLearningData() {
        if (isRunning || !storageReady) return
        finalAssessments.cancelAll(); resetConversation()
        archive = archive.copy(sessions = mutableListOf(), preferences = archive.preferences.copy(hiddenWords = emptyList())); persist()
    }
    fun exportData(): String = ArchiveCodec.encode(archive)
    fun importData(data: String): Boolean {
        if (isRunning || !storageReady) return false
        return try { archive = ArchiveCodec.merge(archive, ArchiveCodec.decode(data)); persist(); notice = getApplication<Application>().getString(R.string.notice_backup_imported); true }
        catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.error_import_failed)); false }
    }
    override fun onCleared() { transport.disconnect(); super.onCleared() }
}
