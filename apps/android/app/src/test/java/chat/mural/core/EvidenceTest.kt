package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class EvidenceTest {
    private fun record(typed: Boolean = false): SessionRecord {
        val session = SessionRecord(languageID = "es")
        session.append(Fragment(id="target",speaker=Speaker.user,text="la casa",startMS=5000,endMS=6000,typed=typed))
        val passage = session.passages.single()
        session.assessments += Assessment(passage.id,passage.revisionKey,Outcome.success,2,"goal","capability", listOf(WordProposal("la casa","house","casa",EvidenceKind.independent,0.95,listOf("target"),"la casa","es")))
        return session
    }
    @Test fun correctionsRevokeEvidenceAndTranslationsKeepPreviousText() {
        val s = record(); s.translations["English::target:0"] = "the house"
        s.translations["English::other:0"] = "unrelated"
        s.correctFragment("target", "la calle")
        assertTrue(s.assessments.isEmpty())
        assertNull(s.translations["English::target:0"])
        assertEquals("unrelated", s.translations["English::other:0"])
        assertEquals(listOf("la casa"), s.fragments.single().previousTexts)
        assertEquals(1, s.fragments.single().revision)
    }
    @Test fun typingAndImmediateImitationAreAssisted() {
        val typed = record(true)
        assertEquals(EvidenceKind.assisted,LearningEngine.validate(typed.assessments.single(),typed)!!.words.single().kind)
        val modeled = record()
        modeled.fragments.add(0,Fragment(id="model",speaker=Speaker.assistant,text="casa",startMS=0,endMS=1000))
        assertEquals(EvidenceKind.assisted,LearningEngine.validate(modeled.assessments.single(),modeled)!!.words.single().kind)
    }
    @Test fun fabricatedQuotesReferencesAndConfidenceAreRejected() {
        val s = record(); val a = s.assessments.single(); val word = a.words.single()
        for (bad in listOf(word.copy(quote="invented"),word.copy(sourceIDs=listOf("missing")),word.copy(confidence=.79),word.copy(confidence=Double.NaN),word.copy(language="en"))) {
            assertTrue(LearningEngine.validate(a.copy(words=listOf(bad)),s)!!.words.isEmpty())
        }
        assertNull(LearningEngine.validate(a.copy(revisionKey="old"),s))
    }
    @Test fun quoteAcrossProviderWordBoundaryIsKept() {
        val session = SessionRecord(languageID = "es")
        session.append(Fragment(id="f1",speaker=Speaker.user,text="Me gusta",startMS=0,endMS=500))
        session.append(Fragment(id="f2",speaker=Speaker.user,text=" el café",startMS=600,endMS=1200))
        val passage = session.passages.single()
        assertEquals("Me gusta el café", passage.text)
        session.assessments += Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "Sigue.", "Expresses liking",
            listOf(WordProposal("gustar","to like","gusta",EvidenceKind.independent,0.95,listOf("f1","f2"),"Me gusta el café","es")))
        assertEquals(1, LearningEngine.validate(session.assessments.single(), session)!!.words.size)
    }
    @Test fun hiddenWordsAreLanguageScopedAndRepetitionIsDeduplicated() {
        val s = record(); val a = s.assessments.single(); s.assessments += a.copy()
        assertEquals(1,LearningEngine.project(listOf(s),"es").observationCount)
        assertTrue(LearningEngine.project(listOf(s),"es",listOf(a.words.single().key)).words.isEmpty())
        assertEquals(1,LearningEngine.project(listOf(s),"es",listOf("en|la casa|house")).words.size)
    }
    private fun recordWith(lemma: String, meaning: String, day: Int): SessionRecord {
        val s = record().copy(startedAt = record().startedAt + day * 86400.0)
        val a = s.assessments.single(); val w = a.words.single()
        s.assessments = mutableListOf(a.copy(createdAt = a.createdAt + day * 86400.0, words = listOf(w.copy(lemma = lemma, meaning = meaning))))
        return s
    }
    @Test fun paraphrasedMeaningsShareOneWord() {
        val sessions = listOf(recordWith("la casa","house",0), recordWith("la casa","a house or home",1), recordWith("la casa","a building where people live",2))
        val words = LearningEngine.project(sessions,"es").words
        assertEquals(1, words.size)
        assertEquals(3, words.single().independentCount)
        assertEquals("a building where people live", words.single().meaning)
    }
    @Test fun leadingArticlesAndCaseDoNotSplitAWord() {
        val words = LearningEngine.project(listOf(recordWith("la casa","house",0), recordWith("Casa","house",1), recordWith("  la  casa ","house",2)),"es").words
        assertEquals(listOf("es|casa"), words.map { it.id })
        assertEquals("  la  casa ", words.single().lemma)
    }
    @Test fun wordKeysDropEachLanguagesLeadingArticles() {
        fun key(language: String, lemma: String) = WordProposal(lemma,"m","f",EvidenceKind.independent,0.9,listOf("x"),"q",language).key
        assertEquals("en|version", key("en","a version")); assertEquals("en|version", key("en","the version")); assertEquals("en|version", key("en","version"))
        assertEquals("nb|gå", key("nb","å gå")); assertEquals("nb|tur", key("nb","en tur"))
        assertEquals("fr|ami", key("fr","l'ami")); assertEquals("fr|ami", key("fr","l’ami")); assertEquals("fr|maison", key("fr","une maison"))
        assertEquals("de|haus", key("de","das Haus")); assertEquals("it|studente", key("it","lo studente")); assertEquals("pt|pão", key("pt","o pão"))
        assertEquals("zh|洗澡", key("zh","洗澡"))
        assertEquals("en|a", key("en","a")); assertEquals("es|el", key("es","el"))
        assertEquals("en|apple", key("en","apple")); assertEquals("en|another", key("en","another"))
    }
    private fun session(language: String, lemma: String, day: Int = 0): SessionRecord {
        val s = SessionRecord(languageID = language, startedAt = 1e9 + day * 86400.0)
        s.append(Fragment(id = "t", speaker = Speaker.user, text = lemma, startMS = 5000, endMS = 6000))
        val p = s.passages.single()
        s.assessments += Assessment(p.id, p.revisionKey, Outcome.success, 2, "goal", "capability",
            listOf(WordProposal(lemma, "meaning", lemma, EvidenceKind.independent, 0.95, listOf("t"), lemma, language)), createdAt = s.startedAt)
        return s
    }
    @Test fun hidingAProjectedIdHidesThatWordOnly() {
        val sessions = listOf(session("pt", "um a um"), session("pt", "um", 1))
        val ids = LearningEngine.project(sessions, "pt").words.map { it.id }.sorted()
        assertEquals(listOf("pt|a um", "pt|um"), ids)
        assertEquals(listOf("pt|um"), LearningEngine.project(sessions, "pt", listOf("pt|a um")).words.map { it.id })
    }
    @Test fun wordKeysDropIndefinitePluralsPartitivesAndElidedUn() {
        fun key(language: String, lemma: String) = wordKey(language, lemma)
        assertEquals("es|vacaciones", key("es", "unas vacaciones")); assertEquals("es|amigos", key("es", "unos amigos"))
        assertEquals("pt|férias", key("pt", "umas férias")); assertEquals("pt|amigos", key("pt", "uns amigos"))
        assertEquals("it|amica", key("it", "un'amica")); assertEquals("it|amica", key("it", "un’amica"))
        assertEquals("fr|pain", key("fr", "du pain")); assertEquals("fr|confiture", key("fr", "de la confiture"))
        assertEquals("de|hund", key("de", "den Hund")); assertEquals("de|kind", key("de", "dem Kind")); assertEquals("de|tages", key("de", "des Tages"))
        assertEquals("de|freund", key("de", "einen Freund")); assertEquals("de|frau", key("de", "einer Frau"))
    }
    @Test fun wordKeysStayComposedAfterLowercasing() {
        assertEquals(wordKey("en", "ǰ"), wordKey("en", "J̌"))
        val sessions = listOf(session("en", "J̌"), session("en", "ǰ", 1))
        assertEquals(1, LearningEngine.project(sessions, "en").words.size)
        assertTrue(LearningEngine.project(sessions, "en", listOf(wordKey("en", "J̌"))).words.isEmpty())
    }
    @Test fun wordKeysTreatEveryUnicodeWhitespaceAlike() {
        assertEquals("zh|洗澡", wordKey("zh", "洗澡"))
        assertEquals("en|version", wordKey("en", "a version "))
    }
    @Test fun legacyAndCurrentHiddenKeysBothHideTheWord() {
        val sessions = listOf(recordWith("la casa","house",0), recordWith("casa","a house",1))
        assertTrue(LearningEngine.project(sessions,"es",listOf("es|la casa|house")).words.isEmpty())
        assertTrue(LearningEngine.project(sessions,"es",listOf("es|casa")).words.isEmpty())
        assertEquals(1, LearningEngine.project(sessions,"es",listOf("es|la calle|street")).words.size)
    }
    @Test fun twoSuccessesRequiredAndBreakdownReducesChallenge() {
        val first = record(); val second = record().copy(startedAt=first.startedAt+1)
        assertEquals(0,LearningEngine.project(listOf(first),"es").challenge)
        assertEquals(1,LearningEngine.project(listOf(first,second),"es").challenge)
        val third = record().copy(startedAt=first.startedAt+2)
        third.assessments = mutableListOf(third.assessments.single().copy(outcome=Outcome.breakdown))
        assertEquals(0,LearningEngine.project(listOf(first,second,third),"es").challenge)
    }
    @Test fun invalidAssessmentDoesNotBlockALaterValidOneForTheSamePassage() {
        val s = record()
        val valid = s.assessments.single()
        val invalid = valid.copy(revisionKey = "stale", createdAt = valid.createdAt - 1)
        s.assessments = mutableListOf(invalid, valid)
        val projection = LearningEngine.project(listOf(s), "es")
        assertEquals(1, projection.observationCount)
        assertEquals(1, projection.words.size)
    }
}
