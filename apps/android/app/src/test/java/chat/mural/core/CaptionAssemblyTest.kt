package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class CaptionAssemblyTest {
    @Test fun allSupportedLanguagesPreserveWordContinuations() {
        val examples = listOf(
            Triple("nb", listOf("Hygg", "elig! Jeg liker fri", "lufts", "liv."), "Hyggelig! Jeg liker friluftsliv."),
            Triple("en", listOf("That is inter", "esting."), "That is interesting."),
            Triple("es", listOf("Me gusta apren", "der espa", "ñol."), "Me gusta aprender español."),
            Triple("fr", listOf("Aujourd", "’hui, c’est inté", "ressant."), "Aujourd’hui, c’est intéressant."),
            Triple("de", listOf("Das ist eine Sprach", "lern", "anwendung."), "Das ist eine Sprachlernanwendung."),
            Triple("it", listOf("È una conver", "sazione interes", "sante."), "È una conversazione interessante."),
            Triple("pt", listOf("Estou apren", "dendo portu", "guês."), "Estou aprendendo português."),
            Triple("zh", listOf("我", "喜欢", "学习", "中文。", "你呢？"), "我喜欢学习中文。你呢？")
        )
        examples.forEach { (id, parts, expected) ->
            assertNotNull(LanguageRegistry.get(id))
            assertEquals(id, expected, Passage.join(parts))
            expected.indices.forEach { boundary ->
                assertEquals(id, expected, Passage.join(listOf(expected.take(boundary), expected.drop(boundary))))
            }
        }
    }
    @Test fun sentenceRepairPreservesOtherBoundaries() {
        val examples = listOf(
            listOf("It is easy.", "Now you?") to "It is easy. Now you?",
            listOf("Hei", "!", "Hvordan går det?") to "Hei! Hvordan går det?",
            listOf("U.", "S.", "A.") to "U.S.A.",
            listOf("3.", "14") to "3.14",
            listOf("example.", "com") to "example.com",
            listOf("caf", "e", "\u0301") to "cafe\u0301",
            listOf("今天。", "Hello!") to "今天。Hello!"
        )
        examples.forEach { (parts, expected) -> assertEquals(expected, Passage.join(parts)) }
    }
    @Test fun typedVoiceReplyUsesProviderTimelineDespiteSlowConnection() {
        val session = SessionRecord(languageID = "en", startedAt = nowSeconds() - 20)
        session.append(Fragment(id = "question", speaker = Speaker.assistant, text = "What did you do?", startMS = 2000, endMS = 4000))
        val offset = session.nextTypedVoiceOffsetMS
        session.append(Fragment(id = "typed", speaker = Speaker.user, text = "I went walking.", startMS = offset, endMS = offset + 1, typed = true))
        session.append(Fragment(id = "reply", speaker = Speaker.assistant, text = "Where did you go?", startMS = 8000, endMS = 10000))
        assertEquals(listOf("question", "typed", "reply"), session.passages.map { it.id })
        assertEquals(listOf("What did you do?", "I went walking.", "Where did you go?"), session.passages.map { it.text })
    }
    @Test fun oldEvidenceRemainsValidWhileNewEvidenceMustMatchRepairedText() {
        val session = SessionRecord(languageID = "nb")
        session.append(Fragment(id = "a", speaker = Speaker.user, text = "Jeg liker fri", startMS = 0, endMS = 100))
        session.append(Fragment(id = "b", speaker = Speaker.user, text = "luftsliv.", startMS = 100, endMS = 200))
        val p = session.passages[0]
        val word = WordProposal("friluftsliv", "outdoor life", "luftsliv", EvidenceKind.assisted, 0.9,
            listOf("a", "b"), "Jeg liker fri luftsliv.", "nb")
        val old = Assessment(p.id, p.revisionKey, Outcome.success, 2, "Fortell mer.", "Describes interests", listOf(word))
        assertEquals(1, LearningEngine.validate(old, session)?.words?.size)
        assertEquals(0, LearningEngine.validate(old.copy(textAssemblyVersion = 2), session)?.words?.size)
        val repaired = old.copy(textAssemblyVersion = 2,
            words = listOf(word.copy(quote = "Jeg liker friluftsliv.", form = "friluftsliv")))
        assertEquals(1, LearningEngine.validate(repaired, session)?.words?.size)
        assertEquals("A caption repair must not revive previously rejected saved evidence", 0,
            LearningEngine.validate(repaired.copy(textAssemblyVersion = null), session)?.words?.size)
        assertEquals("Jeg liker friluftsliv.", p.text)
    }
}
