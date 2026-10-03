package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class TagalogTest {
    private fun record(text: String, lemma: String, form: String, meaning: String, day: Int = 0,
                       languageID: String = "tl", typed: Boolean = false, supported: Boolean = false): SessionRecord {
        val date = 810_000_000.0 + day * 86400
        val session = SessionRecord(languageID = languageID, startedAt = date, themeID = "coffee")
        session.append(Fragment(speaker = Speaker.user, text = text, startMS = 100_000, endMS = 103_000,
            receivedAt = date, meaningVisible = supported, typed = typed))
        val passage = session.passages.single()
        session.assessments += Assessment(passageID = passage.id, revisionKey = passage.revisionKey,
            outcome = Outcome.success, suggestedLevel = 2, nextGoal = "Magtanong tungkol sa presyo.",
            capability = "Orders a drink", createdAt = date,
            words = listOf(WordProposal(lemma, meaning, form, EvidenceKind.independent, .95,
                passage.fragments.map { it.id }, text, languageID)))
        return session
    }

    @Test fun captionsPreserveTagalogWordLinksAndExactSourceText() {
        for ((text, expected) in listOf(
            "  Mag-aaral ako araw-araw.\n" to listOf("Mag-aaral", "ako", "araw-araw"),
            "Ako'y masaya. Siya’y narito!" to listOf("Ako'y", "masaya", "Siya’y", "narito"),
            "Bukás ang pinto; búkas tayo aalis. ☕️" to listOf("Bukás", "ang", "pinto", "búkas", "tayo", "aalis"),
        )) {
            val segments = CaptionWords.segments(text, "tl", null)
            assertEquals(text, segments.joinToString("") { it.text })
            assertEquals(expected, segments.mapNotNull { it.lookup })
        }
    }

    @Test fun aspectVariantsShareCitationFormWhileVoiceFocusRemainsDistinctAfterExport() {
        val sessions = mutableListOf(
            record("Kumakain ako ngayon.", "kumain", "Kumakain", "eat"),
            record("Kakain ako mamaya.", "kumain", "Kakain", "eat", day = 2),
            record("Kinain ko ang saging.", "kainin", "Kinain", "eat", day = 2),
        )
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(Archive(sessions = sessions)))
        val words = LearningEngine.project(restored.sessions, languageID = "tl", now = sessions[1].startedAt).words
        assertEquals(setOf("tl|kumain|eat", "tl|kainin|eat"), words.map { it.id }.toSet())
        assertEquals(2, words.single { it.lemma == "kumain" }.independentCount)
        assertEquals(1, words.single { it.lemma == "kainin" }.independentCount)
        assertEquals(sessions.map { it.passages.single().text }, restored.sessions.map { it.passages.single().text })
    }

    @Test fun hidingOneHomographSenseDoesNotHideAnotherSenseOrLanguage() {
        val sessions = listOf(
            record("Bukás ang pinto.", "bukas", "Bukás", "open"),
            record("Bukas ako aalis.", "bukas", "Bukas", "tomorrow"),
            record("radio", "radio", "radio", "radio"),
            record("radio", "radio", "radio", "radio", languageID = "es"),
        )
        val hidden = listOf("tl|bukas|open", "tl|radio|radio")
        assertEquals(listOf("tl|bukas|tomorrow"), LearningEngine.project(sessions, "tl", hidden).words.map { it.id })
        assertEquals(listOf("es|radio|radio"), LearningEngine.project(sessions, "es", hidden).words.map { it.id })
    }

    @Test fun foreignAndDisplayAliasEvidenceCannotEnterTagalogVocabulary() {
        val session = record("Gusto ko ng kape.", "kape", "kape", "coffee")
        val assessment = session.assessments.single()
        for (language in listOf("en", "mixed", "fil", "tl-PH", "id")) {
            val proposal = assessment.copy(words = assessment.words.map { it.copy(language = language) })
            assertTrue(language, LearningEngine.validate(proposal, session)!!.words.isEmpty())
        }
        assertEquals("tl|kape|coffee", LearningEngine.validate(assessment, session)!!.words.single().key)
    }

    @Test fun EnglishSubtitleSupportAndTypingCannotBecomeUnaidedRecall() {
        for ((typed, supported) in listOf(true to false, false to true, true to true)) {
            val session = record("Gusto ko ng kape.", "kape", "kape", "coffee", typed = typed, supported = supported)
            val restored = ArchiveCodec.decode(ArchiveCodec.encode(Archive(sessions = mutableListOf(session),
                preferences = Preferences(learningLanguageID = "tl", meaningLanguage = "English"))))
            val evidence = LearningEngine.validate(restored.sessions.single().assessments.single(), restored.sessions.single())!!
            assertEquals(EvidenceKind.assisted, evidence.words.single().kind)
            assertEquals(0, LearningEngine.project(restored.sessions, languageID = "tl").words.single().independentCount)
            assertEquals("English", restored.preferences.meaningLanguage)
        }
    }
}
