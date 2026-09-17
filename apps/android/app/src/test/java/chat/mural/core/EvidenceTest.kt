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
