package chat.mural.ondevice

import chat.mural.core.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Learning records of an On-Device Model session: assessment evidence keeps Session Provenance, and
 * flashcards, word strength and learner goals update exactly as they do from a cloud session.
 */
class OnDeviceHelpersTest {
    private val noisyReply = """Here is the assessment:
```json
{"outcome":"success","suggestedLevel":2,"nextGoal":"Practica en tu casa.","capability":"Talks about home","words":[{"lemma":"la casa","meaning":"house","form":"casa","quote":"la casa","language":"es","kind":"independent","confidence":0.95,"sourceIDs":["target"]}]}
```
I hope that helps."""

    private fun ended(context: String): SessionRecord {
        val session = SessionRecord(languageID = "es")
        session.append(Fragment(id = "target", speaker = Speaker.user, text = "la casa", startMS = 5000, endMS = 6000))
        session.endedAt = session.startedAt + 60
        val passage = session.passages.single()
        session.assessments += Assessment(passage.id, passage.revisionKey, Outcome.success, 2, "Practica en tu casa.",
            "Talks about home", listOf(WordProposal("la casa", "house", "casa", EvidenceKind.independent, 0.95,
                listOf("target"), "la casa", "es")), createdAt = 1_000.0, context = context)
        return session
    }

    @Test fun assessmentEvidenceRecordsOnDeviceProvenanceAndCloudEvidenceKeepsItsTheme() {
        assertEquals("on-device:free", ConversationProviderPolicy.assessmentContext(true, null))
        assertEquals("on-device:coffee", ConversationProviderPolicy.assessmentContext(true, "coffee"))
        assertEquals("coffee", ConversationProviderPolicy.assessmentContext(false, "coffee"))
        assertEquals("free", ConversationProviderPolicy.assessmentContext(false, null))
        assertEquals("on-device:free", ended("on-device:free").assessments.single().context)
    }

    @Test fun flashcardsWordStrengthAndGoalsUpdateFromOnDeviceSessionsExactlyAsFromCloudOnes() {
        val cloud = LearningEngine.project(listOf(ended("free")), "es", now = 2_000.0)
        val onDevice = LearningEngine.project(listOf(ended("on-device:free")), "es", now = 2_000.0)
        assertEquals(cloud, onDevice)
        assertEquals(1, onDevice.observationCount)
        assertEquals("Practica en tu casa.", onDevice.nextGoal)
        assertEquals(listOf("es|la casa|house"), FlashcardDeck(onDevice.words, "es").words.map { it.id })
        assertEquals(FlashcardDeck(cloud.words, "es").words.map { it.label },
            FlashcardDeck(onDevice.words, "es").words.map { it.label })
    }

    @Test fun anExportedBackupKeepsOnDeviceProvenanceOnAssessmentEvidence() {
        val archive = Archive(sessions = mutableListOf(ended("on-device:coffee")),
            preferences = Preferences(learningLanguageID = "es"))
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(archive))
        assertEquals("on-device:coffee", restored.sessions.single().assessments.single().context)
        assertEquals("es|la casa|house",
            LearningEngine.project(restored.sessions, "es", now = 2_000.0).words.single().id)
    }

    @Test fun aNoisyOnDeviceAssessmentReachesTheLearningEngineAsCleanEvidence() {
        val session = ended("free").also { it.assessments.clear() }
        val proposal = OnDeviceAssessmentExtractor.proposal(noisyReply)!!
        val passage = session.passages.single()
        val assessment = Assessment(passage.id, passage.revisionKey, proposal.outcome, proposal.suggestedLevel,
            proposal.nextGoal, proposal.capability, proposal.words,
            context = ConversationProviderPolicy.assessmentContext(true, session.themeID), textAssemblyVersion = 2)
        val validated = LearningEngine.validate(assessment, session)!!
        assertEquals("on-device:free", validated.context)
        assertEquals(listOf("la casa"), validated.words.map { it.lemma })
        // The end-of-session path records it like any other provider's final assessment.
        val updated = FinalAssessmentResult(session.id, session.languageID, assessment).applying(session)!!
        val learner = LearningEngine.project(listOf(updated), "es", now = 2_000.0)
        assertEquals(1, learner.observationCount)
        assertEquals("Practica en tu casa.", learner.nextGoal)
        assertEquals(listOf("es|la casa|house"), FlashcardDeck(learner.words, "es").words.map { it.id })
    }
}
