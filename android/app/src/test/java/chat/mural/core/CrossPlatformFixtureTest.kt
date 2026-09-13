package chat.mural.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CrossPlatformFixtureTest {
    private val dir = File("../../Tests/Fixtures/cross-platform")
    private val source = File(dir, "archive.json").readText()
    private val expected = Json.parseToJsonElement(File(dir, "archive-expected.json").readText()).jsonObject
    private val archive = ArchiveCodec.decode(source)

    @Test fun redirectDecisionsMatchTheSharedCases() {
        val cases = Json.parseToJsonElement(File(dir, "redirect-cases.json").readText()).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (item in cases.map { it.jsonObject }) {
            val language = LanguageRegistry.get(item.getValue("language").jsonPrimitive.content)!!
            val detected = item.getValue("detected").jsonPrimitive.content
            val confidence = item.getValue("confidence").jsonPrimitive.double
            assertEquals("${language.id} / $detected / $confidence", item.getValue("redirect").jsonPrimitive.boolean,
                TeachingPolicy.shouldRedirectSpeech(language, detected, confidence))
        }
    }

    @Test fun reencodedArchiveKeepsEveryFieldOfTheSharedFixture() {
        val reencoded = ArchiveCodec.encode(archive)
        assertEquals(fieldPaths(Json.parseToJsonElement(source)), fieldPaths(Json.parseToJsonElement(reencoded)))
        assertEquals(content(Json.parseToJsonElement(source)), content(Json.parseToJsonElement(reencoded)))
        assertEquals(archive.sessions.map { it.passages }, ArchiveCodec.decode(reencoded).sessions.map { it.passages })
    }

    @Test fun transcriptPassagesMatchTheSharedFixture() {
        val passages = expected.getValue("passages").jsonObject
        assertEquals(passages.keys, archive.sessions.map { it.id }.toSet())
        for (session in archive.sessions) {
            val actual = session.passages.map { p ->
                buildJsonObject {
                    put("speaker", p.speaker.name)
                    put("text", p.text)
                    put("fragmentIDs", JsonArray(p.fragments.map { JsonPrimitive(it.id) }))
                }
            }
            assertEquals(session.id, passages.getValue(session.id), JsonArray(actual))
        }
    }

    @Test fun learnerProjectionMatchesTheSharedFixture() {
        val learner = expected.getValue("learner").jsonObject
        val state = LearningEngine.project(
            archive.sessions,
            languageID = expected.getValue("languageID").jsonPrimitive.content,
            hiddenWords = archive.preferences.hiddenWords,
            now = expected.getValue("now").jsonPrimitive.double,
        )
        assertEquals(learner.getValue("challenge").jsonPrimitive.int, state.challenge)
        assertEquals(learner.getValue("observationCount").jsonPrimitive.int, state.observationCount)
        assertEquals(learner.getValue("nextGoal").jsonPrimitive.content, state.nextGoal)
        assertEquals(learner.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }, state.capabilities)
        val expectedWords = learner.getValue("words").jsonArray.map { it.jsonObject }
        val words = state.words.sortedBy { it.id }
        assertEquals(expectedWords.map { it.getValue("id").jsonPrimitive.content }, words.map { it.id })
        for ((want, w) in expectedWords.zip(words)) {
            fun text(name: String) = want.getValue(name).jsonPrimitive.content
            fun number(name: String) = want.getValue(name).jsonPrimitive.double
            assertEquals(listOf(text("lemma"), text("meaning"), text("form"), text("example")), listOf(w.lemma, w.meaning, w.form, w.example))
            assertEquals(listOf(number("bars"), number("understandingCount"), number("independentCount"), number("lastSeen"), number("dueAt")),
                listOf(w.bars.toDouble(), w.understandingCount.toDouble(), w.independentCount.toDouble(), w.lastSeen, w.dueAt))
        }
    }

    /** Values and collection sizes with null members removed and numbers compared numerically. */
    private fun content(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.filterValues { it !is JsonNull }.mapValues { content(it.value) }.toSortedMap()
        is JsonArray -> element.map { content(it) }
        is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.doubleOrNull ?: element.content
    }

    private fun fieldPaths(element: JsonElement, prefix: String = "", out: MutableSet<String> = sortedSetOf()): Set<String> {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (value is JsonNull) continue
                val path = if (prefix.endsWith(".translations")) "$prefix.{}" else "$prefix.$key"
                out += path
                fieldPaths(value, path, out)
            }
            is JsonArray -> element.forEach { fieldPaths(it, "$prefix[]", out) }
            else -> Unit
        }
        return out
    }
}
