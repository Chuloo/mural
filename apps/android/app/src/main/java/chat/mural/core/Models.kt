package chat.mural.core

import java.util.UUID
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class Speaker { user, assistant }
@Serializable enum class EvidenceKind { exposure, understanding, assisted, independent, lapse }
@Serializable enum class Outcome { success, partial, breakdown, uncertain }

const val APPLE_EPOCH_UNIX_SECONDS = 978307200.0
fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0 - APPLE_EPOCH_UNIX_SECONDS

@Serializable
data class Fragment(
    val id: String = UUID.randomUUID().toString(), var revision: Int = 0,
    var previousTexts: List<String> = emptyList(), val speaker: Speaker, var text: String,
    val startMS: Int, val endMS: Int, val receivedAt: Double = nowSeconds(),
    val meaningVisible: Boolean = false, val typed: Boolean = false
)

data class Passage(val id: String, val speaker: Speaker, val fragments: List<Fragment>) {
    val text get() = join(fragments.map { it.text })
    companion object {
        /** Preserve word continuations; repair only a clear sentence break. */
        fun join(parts: List<String>): String = parts.fold("") { result, part ->
            val first = part.takeIf { it.isNotEmpty() }?.codePointAt(0)
            val last = result.lastOrNull()
            val word = result.dropLast(1).takeLastWhile { it.isLetter() }
            val sentenceBreak = first != null && Character.isUpperCase(first) &&
                (last == '!' || last == '?' || last == '…' || (last == '.' && word.length > 1))
            result + (if (sentenceBreak) " " else "") + part
        }
        // Only for validating learning evidence saved before the caption repair.
        internal fun legacyJoin(parts: List<String>): String = parts.fold("") { result, part ->
            val first = part.takeIf { it.isNotEmpty() }?.codePointAt(0)
            val last = result.takeIf { it.isNotEmpty() }?.codePointBefore(result.length)
            when {
                result.isEmpty() -> part
                last == null || first == null -> result + part
                Character.isWhitespace(last) || Character.isSpaceChar(last) || Character.isWhitespace(first) || Character.isSpaceChar(first) || isPunctuation(first) || isUnspacedBoundary(last, first) -> result + part
                else -> "$result $part"
            }
        }
        private fun isUnspacedBoundary(last: Int, first: Int): Boolean {
            fun han(c: Int) = c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF || c in 0xF900..0xFAFF || c in 0x20000..0x323AF
            val type = Character.getType(last)
            val opening = type == Character.START_PUNCTUATION.toInt() || type == Character.INITIAL_QUOTE_PUNCTUATION.toInt()
            return opening || (han(first) && (han(last) || last in 0x3000..0x303F || last in 0xFF00..0xFFEF))
        }
        private fun isPunctuation(c: Int): Boolean {
            val type = Character.getType(c)
            return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.START_PUNCTUATION.toInt() ||
                type == Character.END_PUNCTUATION.toInt() ||
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt()
        }
    }
    val revisionKey get() = fragments.joinToString(",") { "${it.id}:${it.revision}" }
    val startMS get() = fragments.firstOrNull()?.startMS ?: 0
    val endMS get() = fragments.maxOfOrNull { it.endMS } ?: 0
}
object Transcript {
    fun passages(fragments: List<Fragment>): List<Passage> {
        val result = mutableListOf<Passage>()
        val indexed = fragments.withIndex().sortedWith(compareBy<IndexedValue<Fragment>> { it.value.startMS }.thenBy { it.index })
        for (entry in indexed) {
            val f = entry.value
            val i = result.indexOfLast { it.speaker == f.speaker }
            if (i >= 0) {
                val p = result[i]
                if (f.startMS - p.endMS <= 2200 && !f.typed && !p.fragments.last().typed) {
                    result[i] = p.copy(fragments = p.fragments + f); continue
                }
            }
            result += Passage(f.id, f.speaker, listOf(f))
        }
        return result.sortedBy { it.startMS }
    }
}

/** Swift compares strings by canonical equivalence; Kotlin needs the same form on both sides. */
fun String.canonical(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)

fun String.containsCanonical(other: String): Boolean = canonical().contains(other.canonical(), ignoreCase = true)

@Serializable
data class WordProposal(
    val lemma: String, val meaning: String, val form: String, val kind: EvidenceKind,
    val confidence: Double, val sourceIDs: List<String>, val quote: String,
    val language: String = LanguageRegistry.defaultID
) { val key get() = "${language}|${lemma.trim().lowercase().canonical()}|${meaning.lowercase().canonical()}" }

@Serializable
data class Assessment(
    val passageID: String, val revisionKey: String, val outcome: Outcome, var suggestedLevel: Int,
    var nextGoal: String, var capability: String, var words: List<WordProposal>,
    val createdAt: Double = nowSeconds(), val context: String = "free", val textAssemblyVersion: Int? = null
)

@Serializable
data class SourceLink(val title: String, val url: String) {
    fun safeUrl(): String? = try {
        val uri = URI(url)
        url.takeIf { uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null }
    } catch (_: Exception) { null }
}
@Serializable
data class TopicBrief(
    val id: String = UUID.randomUUID().toString(), val languageID: String, var query: String,
    var text: String, var sources: List<SourceLink>, val retrievedAt: Double = nowSeconds()
) { val isFresh get() = nowSeconds() - retrievedAt < 6 * 3600 }

@Serializable
data class SessionRecord(
    val id: String = UUID.randomUUID().toString(), val languageID: String = LanguageRegistry.defaultID,
    var providerID: String? = null, var startedAt: Double = nowSeconds(), var endedAt: Double? = null,
    var themeID: String? = null, var title: String = LanguageRegistry.get(languageID)?.defaultTitle ?: "A conversation",
    var fragments: MutableList<Fragment> = mutableListOf(), var assessments: MutableList<Assessment> = mutableListOf(),
    var translations: MutableMap<String,String> = mutableMapOf(), var topics: MutableList<TopicBrief> = mutableListOf(),
    var voiceSeconds: Double = 0.0, var usageFinal: Boolean = false, var inputTokens: Int = 0,
    var outputTokens: Int = 0, var searchCalls: Int = 0, var endReason: String? = null
) {
    val passages get() = Transcript.passages(fragments)
    // Voice captions use the provider timeline, not time since the local connection attempt.
    val nextTypedVoiceOffsetMS get() = (fragments.maxOfOrNull { it.endMS } ?: 0).coerceAtMost(Int.MAX_VALUE - 2) + 1
    fun append(f: Fragment) { if (fragments.none { it.id == f.id }) { fragments += f; invalidateChangedAssessments() } }
    fun invalidateChangedAssessments() { val current = passages.associate { it.id to it.revisionKey }; assessments.removeAll { current[it.passageID] != it.revisionKey } }
    fun correctFragment(id: String, text: String) {
        val i = fragments.indexOfFirst { it.id == id }; if (i < 0) return
        val f = fragments[i]; fragments[i] = f.copy(previousTexts = f.previousTexts + f.text, text = text, revision = f.revision + 1)
        translations.keys.filter { translationKeyIncludesFragment(it, id) }.forEach { translations.remove(it) }
        invalidateChangedAssessments()
    }
    companion object {
        fun translationKeyIncludesFragment(key: String, id: String): Boolean {
            val revision = key.substringAfter("::", missingDelimiterValue = "")
            if (revision.isEmpty()) return false
            return revision.split(',').any { part -> part.substringBefore(':') == id }
        }
    }
}
@Serializable
data class Preferences(
    var learningLanguageID: String = LanguageRegistry.defaultID, var meaningVisible: Boolean = true,
    var meaningLanguage: String = "English", var sessionMinutes: Int = 15, var hiddenWords: List<String> = emptyList(),
    var interests: String = "", var hasOnboarded: Boolean = false, var aiConsentVersion: Int? = null
)
@Serializable data class Archive(var schemaVersion: Int = 2, var sessions: MutableList<SessionRecord> = mutableListOf(), var preferences: Preferences = Preferences())
class ArchiveError private constructor(val kind: Kind) : Exception() {
    enum class Kind { TOO_LARGE, UNSUPPORTED_VERSION, UNSUPPORTED_LANGUAGE, INVALID }
    companion object {
        val TOO_LARGE = ArchiveError(Kind.TOO_LARGE)
        val UNSUPPORTED_VERSION = ArchiveError(Kind.UNSUPPORTED_VERSION)
        val UNSUPPORTED_LANGUAGE = ArchiveError(Kind.UNSUPPORTED_LANGUAGE)
        val INVALID = ArchiveError(Kind.INVALID)
    }
}

object ArchiveCodec {
    const val MAXIMUM_ENCODED_BYTES = 30_000_000
    private const val MAXIMUM_SESSIONS = 10_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    fun encode(a: Archive): String = json.encodeToString(Archive.serializer(), a)
    fun decode(data: String): Archive {
        if (data.toByteArray().size > MAXIMUM_ENCODED_BYTES) throw ArchiveError.TOO_LARGE
        val root = try { json.parseToJsonElement(data).jsonObject } catch (_: Exception) { throw ArchiveError.INVALID }
        val a = try {
            val migrated = migrate(root)
            requireFields(migrated)
            json.decodeFromJsonElement<Archive>(migrated)
        } catch (e: ArchiveError) { throw e } catch (_: Exception) { throw ArchiveError.INVALID }
        validate(a); return a
    }
    fun merge(current: Archive, incoming: Archive): Archive {
        validate(incoming)
        val known = current.sessions.map { it.id.lowercase() }.toSet()
        val adds = incoming.sessions.filter { s -> s.id.lowercase() !in known }
        if (adds.size > MAXIMUM_SESSIONS - current.sessions.size) throw ArchiveError.TOO_LARGE
        val out = decode(encode(current))
        adds.forEach { source ->
            val s = source.copy(fragments=source.fragments.toMutableList(), assessments=source.assessments.toMutableList(), translations=source.translations.toMutableMap(), topics=source.topics.toMutableList())
            s.invalidateChangedAssessments(); s.assessments = s.assessments.mapNotNull { LearningEngine.validate(it,s) }.toMutableList(); out.sessions += s
        }
        validate(out); if (encode(out).toByteArray().size > MAXIMUM_ENCODED_BYTES) throw ArchiveError.TOO_LARGE
        return out
    }
    private fun migrate(root: JsonObject): JsonObject {
        val v = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: throw ArchiveError.INVALID
        if (v !in 1..2) throw ArchiveError.UNSUPPORTED_VERSION
        if (v == 2) return root
        val m = root.toMutableMap()
        val p = root["preferences"]?.jsonObject?.toMutableMap() ?: throw ArchiveError.INVALID
        p["learningLanguageID"] = JsonPrimitive(LanguageRegistry.defaultID)
        p["hiddenWords"] = buildJsonArray { root["preferences"]?.jsonObject?.get("hiddenWords")?.jsonArray?.forEach { add(JsonPrimitive("${LanguageRegistry.defaultID}|${it.jsonPrimitive.content}")) } }
        m["preferences"] = JsonObject(p)
        m["sessions"] = buildJsonArray {
            root["sessions"]?.jsonArray?.forEach { el ->
                val s = el.jsonObject.toMutableMap(); s["languageID"] = JsonPrimitive(LanguageRegistry.defaultID)
                s["topics"] = buildJsonArray { s["topics"]?.jsonArray?.forEach { t -> val x=t.jsonObject.toMutableMap(); x["languageID"]=JsonPrimitive(LanguageRegistry.defaultID); add(JsonObject(x)) } }
                add(JsonObject(s))
            } ?: throw ArchiveError.INVALID
        }
        m["schemaVersion"] = JsonPrimitive(2); return JsonObject(m)
    }
    // Foundation Date.distantPast / distantFuture bounds used by the iPhone importer.
    private fun validDate(d: Double) = d.isFinite() && d in -63114076800.0..63113904000.0
    private fun validUUID(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) { false }
    private fun requireFields(root: JsonObject) {
        fun fields(obj: JsonObject, names: String) {
            if (names.split(' ').any { !obj.containsKey(it) || obj[it] == JsonNull }) throw ArchiveError.INVALID
        }
        fields(root, "schemaVersion sessions preferences")
        val preferences = root.getValue("preferences").jsonObject
        fields(preferences, "learningLanguageID meaningVisible meaningLanguage sessionMinutes hiddenWords interests hasOnboarded")
        for (value in root.getValue("sessions").jsonArray) {
            val s = value.jsonObject
            fields(s, "id languageID startedAt title fragments assessments translations topics voiceSeconds usageFinal inputTokens outputTokens searchCalls")
            for (fragment in s.getValue("fragments").jsonArray) {
                fields(fragment.jsonObject, "id revision previousTexts speaker text startMS endMS receivedAt meaningVisible typed")
            }
            for (assessment in s.getValue("assessments").jsonArray) {
                fields(assessment.jsonObject, "passageID revisionKey outcome suggestedLevel nextGoal capability words createdAt context")
                for (word in assessment.jsonObject.getValue("words").jsonArray) {
                    fields(word.jsonObject, "lemma meaning form kind confidence sourceIDs quote language")
                }
            }
            for (topic in s.getValue("topics").jsonArray) {
                fields(topic.jsonObject, "id languageID query text sources retrievedAt")
            }
        }
    }
    private fun validate(a: Archive) {
        if (LanguageRegistry.get(a.preferences.learningLanguageID) == null) throw ArchiveError.UNSUPPORTED_LANGUAGE
        if (a.schemaVersion != 2 || a.sessions.size > MAXIMUM_SESSIONS || a.preferences.sessionMinutes !in 1..60 || a.sessions.map { it.id.lowercase() }.toSet().size != a.sessions.size) throw ArchiveError.INVALID
        a.sessions.forEach { s ->
            if (LanguageRegistry.get(s.languageID) == null) throw ArchiveError.UNSUPPORTED_LANGUAGE
            if (!validUUID(s.id) || !s.voiceSeconds.isFinite() || s.voiceSeconds !in 0.0..31536000.0 || listOf(s.inputTokens,s.outputTokens,s.searchCalls).any { it !in 0..1_000_000_000 } || !validDate(s.startedAt) || s.endedAt?.let { !validDate(it) } == true) throw ArchiveError.INVALID
            if (s.fragments.map { it.id }.toSet().size != s.fragments.size || s.fragments.any { it.startMS < 0 || it.endMS < it.startMS || it.text.length > 50000 || it.revision !in 0..1_000_000 || !validDate(it.receivedAt) }) throw ArchiveError.INVALID
            if (s.assessments.any { !validDate(it.createdAt) } || s.topics.any { !validUUID(it.id) || it.languageID != s.languageID || !validDate(it.retrievedAt) }) throw ArchiveError.INVALID
        }
    }
}
