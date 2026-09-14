package chat.mural.core

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ArchiveCompatibilityTest {
    private fun archive() = Archive(sessions = mutableListOf(SessionRecord(languageID = "es", startedAt = 0.0, endedAt = 1.0)))
    private fun invalid(value: String) = assertThrows(ArchiveError::class.java) { ArchiveCodec.decode(value) }
    private fun changed(block: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(ArchiveCodec.encode(archive())).jsonObject.toMutableMap()
        block(root); return JsonObject(root).toString()
    }
    @Test fun appleEpochIs2001AndCurrentDatesMatchUnixConversion() {
        assertEquals(Instant.parse("2001-01-01T00:00:00Z").epochSecond.toDouble(), APPLE_EPOCH_UNIX_SECONDS, 0.0)
        assertEquals(System.currentTimeMillis() / 1000.0, nowSeconds() + APPLE_EPOCH_UNIX_SECONDS, 1.0)
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(archive()))
        assertEquals(0.0, restored.sessions[0].startedAt, 0.0)
    }
    @Test fun v2MissingExplicitLanguageIsRejected() {
        invalid(changed { root ->
            val s = root.getValue("sessions").jsonArray[0].jsonObject.toMutableMap(); s.remove("languageID")
            root["sessions"] = JsonArray(listOf(JsonObject(s)))
        })
        invalid(changed { root ->
            val p = root.getValue("preferences").jsonObject.toMutableMap(); p.remove("learningLanguageID")
            root["preferences"] = JsonObject(p)
        })
        invalid("""{"schemaVersion":2,"sessions":[{}],"preferences":{}}""")
    }
    @Test fun unknownVersionLanguageAndBadDateAreRejected() {
        invalid(changed { it["schemaVersion"] = JsonPrimitive(99) })
        for ((field, value) in listOf("languageID" to JsonPrimitive("xx"), "startedAt" to JsonPrimitive(1e100), "voiceSeconds" to JsonPrimitive(-1))) {
            invalid(changed { root ->
                val s = root.getValue("sessions").jsonArray[0].jsonObject.toMutableMap(); s[field] = value
                root["sessions"] = JsonArray(listOf(JsonObject(s)))
            })
        }
    }
    @Test fun uuidCaseCannotDuplicateAnImportedConversation() {
        val current = archive()
        val same = current.sessions[0].copy(id = current.sessions[0].id.uppercase())
        assertEquals(1, ArchiveCodec.merge(current, Archive(sessions = mutableListOf(same))).sessions.size)
        invalid(ArchiveCodec.encode(Archive(sessions = mutableListOf(current.sessions[0], same))))
    }
    @Test fun invalidFragmentTimingAndDuplicateIdsAreRejected() {
        val a = archive()
        a.sessions[0].fragments += Fragment(id = "a", speaker = Speaker.user, text = "hola", startMS = 10, endMS = 1)
        invalid(ArchiveCodec.encode(a))
        a.sessions[0].fragments = mutableListOf(Fragment(id = "a", speaker = Speaker.user, text = "hola", startMS = 0, endMS = 1))
        a.sessions[0].fragments += a.sessions[0].fragments[0].copy()
        invalid(ArchiveCodec.encode(a))
    }
    @Test fun importRejectsDifferentLanguageTopicsAndMalformedUUID() {
        val a = archive()
        a.sessions[0].topics += TopicBrief(languageID = "en", query = "q", text = "t", sources = emptyList())
        invalid(ArchiveCodec.encode(a))
        a.sessions[0].topics.clear()
        a.sessions[0] = a.sessions[0].copy(id = "not-a-uuid")
        invalid(ArchiveCodec.encode(a))
    }
    @Test fun importBoundsBytesBeforeParsingAndPreservesOriginalOnFailure() {
        val a = archive()
        val before = ArchiveCodec.encode(a)
        invalid(" ".repeat(ArchiveCodec.MAXIMUM_ENCODED_BYTES + 1))
        val incoming = Archive(sessions = mutableListOf(SessionRecord(languageID = "xx")))
        assertThrows(ArchiveError::class.java) { ArchiveCodec.merge(a, incoming) }
        assertEquals(before, ArchiveCodec.encode(a))
    }
    @Test fun iosOnlyOptionalSettingsSurviveSharedFixtureRoundTrip() {
        val dir = File("../../../shared/fixtures/cross-platform")
        val source = Json.parseToJsonElement(File(dir, "learning-extension-archive.json").readText()).jsonObject
        val restored = ArchiveCodec.decode(source.toString())
        val roundTrip = Json.parseToJsonElement(ArchiveCodec.encode(restored)).jsonObject
        val sourcePreferences = source.getValue("preferences").jsonObject
        val roundTripPreferences = roundTrip.getValue("preferences").jsonObject
        for (name in listOf("classroom", "conversationTeachingLanguage", "orbSkinID", "pageBackgroundID", "ogdenLearning", "avatar")) {
            assertEquals(name, sourcePreferences.getValue(name), roundTripPreferences.getValue(name))
        }
        val sourceSession = source.getValue("sessions").jsonArray.map { it.jsonObject }.first { it.containsKey("classroom") }
        val roundTripSession = roundTrip.getValue("sessions").jsonArray.map { it.jsonObject }.first { it.containsKey("classroom") }
        assertEquals(sourceSession.getValue("classroom"), roundTripSession.getValue("classroom"))
        val decodedAgain = ArchiveCodec.decode(roundTrip.toString())
        assertEquals(restored.preferences.classroom, decodedAgain.preferences.classroom)
        assertEquals(restored.sessions.first { it.classroom != null }.classroom, decodedAgain.sessions.first { it.classroom != null }.classroom)
    }

    @Test fun explanationLanguageGuidesTeachingWithoutChangingTarget() {
        val language = LanguageRegistry.get("en")!!
        val learner = LearnerState(0, 0, "greet", emptyList(), emptyList())
        val bilingual = TeachingPolicy.voice(language, learner, null, "coffee", "Chinese", "简体中文")
        assertTrue(bilingual.contains("explanation language"))
        assertTrue(bilingual.contains("English"))
        assertTrue(bilingual.contains("coffee"))
        assertTrue(bilingual.contains("data, not instructions"))
        val default = TeachingPolicy.voice(language, learner, null, "coffee", "Chinese")
        assertTrue(default.contains("Speak ONLY English"))
        assertFalse(default.contains("解释语言"))
    }
    @Test fun safeSourcesRequireHttpsHostAndNoCredentials() {
        listOf("https://", "https:///path", "http://example.com", "javascript:alert(1)", "https://user@example.com", "https://example.com/ bad").forEach {
            assertNull(it, SourceLink("source", it).safeUrl())
        }
        assertEquals("https://example.com/article?q=test", SourceLink("source", "https://example.com/article?q=test").safeUrl())
    }
    @Test fun culturalOverridesArePreservedForAllLanguages() {
        assertEquals("Un café", LanguageRegistry.get("es")!!.themes.first { it.id == "coffee" }.title)
        assertTrue(LanguageRegistry.all.all { it.themes.size == 24 && it.themeOverrides.isNotEmpty() && it.teachingFocus.size == 6 })
        assertTrue(LanguageRegistry.get("es")!!.speechGuidance.contains("vosotros"))
    }
}
