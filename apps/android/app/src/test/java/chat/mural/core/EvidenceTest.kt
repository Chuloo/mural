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
    @Test fun paraphrasedMeaningsAndArticleVariantsCollapseToOneWord() {
        fun session(day: Double, lemma: String, meaning: String): SessionRecord {
            val date = 1_780_000_000.0 + day * 86400
            val s = SessionRecord(languageID = "en", themeID = "work", startedAt = date)
            s.append(Fragment(id="f-${day.toInt()}",speaker=Speaker.user,text="version",startMS=0,endMS=1000,receivedAt=date))
            val p = s.passages.single()
            s.assessments += Assessment(p.id, p.revisionKey, Outcome.success, 2, "Keep going.", "Names software releases",
                listOf(WordProposal(lemma, meaning, "version", EvidenceKind.independent, 0.95, p.fragments.map { it.id }, "version", "en")),
                createdAt = date, context = "work")
            return s
        }
        val sessions = listOf(
            session(0.0, "a version", "a particular form of a product or software"),
            session(2.0, "version", "a particular form or release of software"),
            session(4.0, "version", "a particular form or release of something"),
        )
        val projected = LearningEngine.project(sessions, "en", now = sessions[2].startedAt)
        assertEquals(1, projected.words.size)
        assertEquals("en|version", projected.words.single().id)
        assertEquals(3, projected.words.single().independentCount)
        assertTrue(LearningEngine.project(sessions, "en",
            listOf("en|version|a particular form of a product or software"), sessions[2].startedAt).words.isEmpty())
    }
    @Test fun hiddenWordsAreLanguageScopedAndRepetitionIsDeduplicated() {
        val s = record(); val a = s.assessments.single(); s.assessments += a.copy()
        assertEquals(1,LearningEngine.project(listOf(s),"es").observationCount)
        assertTrue(LearningEngine.project(listOf(s),"es",listOf(a.words.single().key)).words.isEmpty())
        assertEquals(1,LearningEngine.project(listOf(s),"es",listOf("en|la casa|house")).words.size)
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
