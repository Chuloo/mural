package chat.mural.core

import org.junit.Test
import org.junit.Assert.*

class DutchRussianTest {
    private fun evidence(id: String, text: String, lemma: String, form: String, supported: Boolean = false): SessionRecord {
        val session = SessionRecord(languageID = id, startedAt = 1_780_000_000.0)
        session.append(Fragment(id = "native-$id", speaker = Speaker.user, text = text, startMS = 0, endMS = 1000, meaningVisible = supported))
        val passage = session.passages.single()
        session.assessments += Assessment(passage.id, passage.revisionKey, Outcome.success, 2, "A goal", "A capability",
            listOf(WordProposal(lemma, "meaning", form, EvidenceKind.independent, .95, listOf("native-$id"), text, id)), createdAt = session.startedAt)
        return session
    }
    @Test fun allTeachingPathsUseSelectedLanguageAndExistingIDsRemainAvailable() {
        for ((id, locale, greeting) in listOf(Triple("nl", "nl-NL", "Hoi!"), Triple("ru", "ru-RU", "Привет!"))) {
            val language = LanguageRegistry.get(id)!!
            assertEquals(locale, language.locale); assertEquals(greeting, language.greeting)
            assertEquals(greeting, MeaningLanguages.greeting(language.name)); assertTrue(MeaningLanguages.all.contains(language.name))
            val learner = LearningEngine.project(emptyList(), id)
            val voice = TeachingPolicy.voice(language, learner, language.themes.first(), "", "English")
            for (prompt in listOf(voice, TeachingPolicy.assessment(language), TeachingPolicy.greeting(language), TeachingPolicy.help(language),
                TeachingPolicy.redirect(language), TeachingPolicy.translation(language, "English"), TeachingPolicy.delegation(language),
                TeachingPolicy.typedReply(language), TeachingPolicy.lookup(language, "English"), TeachingPolicy.currentTopic(language))) {
                assertTrue(prompt, prompt.contains(language.name)); assertFalse(prompt, prompt.contains("Norwegian"))
            }
            assertTrue(voice.contains(language.speechGuidance)); assertTrue(voice.contains(language.writingGuidance))
            assertEquals(6, language.teachingFocus.size); assertEquals(24, language.themes.size)
        }
    }
    @Test fun archiveRoundTripEvidenceSupportAndLanguageIsolation() {
        for ((id, text, lemma, form) in listOf(listOf("nl", "Ik fiets naar huis.", "fietsen", "fiets"), listOf("ru", "Я читаю книгу.", "книга", "книгу"))) {
            val session = evidence(id, text, lemma, form)
            val archive = Archive(sessions = mutableListOf(session), preferences = Preferences(learningLanguageID = id, meaningLanguage = "Russian"))
            val restored = ArchiveCodec.decode(ArchiveCodec.encode(archive))
            assertEquals(id, restored.preferences.learningLanguageID); assertEquals(text, restored.sessions.single().passages.single().text)
            val words = LearningEngine.project(restored.sessions, id, now = session.startedAt).words
            assertEquals(lemma, words.single().lemma); assertEquals(1, words.single().independentCount)
            for (other in LanguageRegistry.all.filter { it.id != id }) assertTrue(LearningEngine.project(restored.sessions, other.id).words.isEmpty())
            assertTrue(LearningEngine.project(restored.sessions, id, hiddenWords = listOf(words.single().id)).words.isEmpty())
            val supported = evidence(id, text, lemma, form, true)
            assertEquals(0, LearningEngine.project(listOf(supported), id).words.single().independentCount)
            supported.assessments[0].words = listOf(supported.assessments[0].words.single().copy(language = "es"))
            assertTrue(LearningEngine.validate(supported.assessments[0], supported)!!.words.isEmpty())
        }
    }
}
