package chat.mural.ondevice

import chat.mural.core.EvidenceKind
import chat.mural.core.Outcome
import chat.mural.network.HelperPurpose
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * The assessment Helper of an On-Device Model session: constrained decoding first, a defensive parse
 * of whatever the local model returned, and at most one unconstrained retry with bounded output.
 */
class OnDeviceAssessmentTest {
    private val model = "models/gemma-4-e4b.litertlm"
    private val instructions = "Assess only the marked TARGET user passage."
    private val schema = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("required", "outcome")
    }
    private val word = """{"lemma":"la casa","meaning":"house","form":"casa","quote":"la casa","language":"es","kind":"independent","confidence":0.95,"sourceIDs":["target"]}"""
    private val valid = """{"outcome":"success","suggestedLevel":2,"nextGoal":"Practica en tu casa.","capability":"Talks about home","words":[$word]}"""
    private fun helpers(runtime: ScriptedModelRuntime) =
        OnDeviceTeachingClient({ runtime.load(model) }) { "No he podido comprobarlo ahora mismo." }

    @Test fun aSchemaValidReplyBecomesACleanProposal() {
        val proposal = OnDeviceAssessmentExtractor.proposal(valid)!!
        assertEquals(Outcome.success, proposal.outcome)
        assertEquals(2, proposal.suggestedLevel)
        assertEquals("Practica en tu casa.", proposal.nextGoal)
        assertEquals("Talks about home", proposal.capability)
        val evidence = proposal.words.single()
        assertEquals("la casa", evidence.lemma)
        assertEquals("house", evidence.meaning)
        assertEquals("casa", evidence.form)
        assertEquals("la casa", evidence.quote)
        assertEquals("es", evidence.language)
        assertEquals(EvidenceKind.independent, evidence.kind)
        assertEquals(0.95, evidence.confidence, 0.0)
        assertEquals(listOf("target"), evidence.sourceIDs)
    }

    @Test fun markdownFencesAndProseAroundTheObjectStillYieldTheAssessment() {
        val noisy = "Here is the assessment you asked for:\n```json\n$valid\n```\nI hope that helps."
        assertEquals(OnDeviceAssessmentExtractor.proposal(valid), OnDeviceAssessmentExtractor.proposal(noisy))
    }

    @Test fun aBraceInsideEvidenceDoesNotEndTheObject() {
        val reply = """{"outcome":"partial","suggestedLevel":1,"nextGoal":"","capability":"","words":[{"lemma":"x","meaning":"y","form":"z","quote":"a {b} c","language":"es","kind":"assisted","confidence":0.9,"sourceIDs":["f"]}]}"""
        assertEquals("a {b} c", OnDeviceAssessmentExtractor.proposal(reply)!!.words.single().quote)
    }

    @Test fun outOfRangeLevelsAndUnknownOutcomesAreCoercedNotRejected() {
        assertEquals(5, OnDeviceAssessmentExtractor.proposal("""{"outcome":"success","suggestedLevel":9}""")!!.suggestedLevel)
        assertEquals(0, OnDeviceAssessmentExtractor.proposal("""{"outcome":"success","suggestedLevel":-3}""")!!.suggestedLevel)
        assertEquals(3, OnDeviceAssessmentExtractor.proposal("""{"outcome":"success","suggestedLevel":"3"}""")!!.suggestedLevel)
        assertEquals(0, OnDeviceAssessmentExtractor.proposal("""{"outcome":"success","suggestedLevel":"hard"}""")!!.suggestedLevel)
        assertEquals(Outcome.uncertain, OnDeviceAssessmentExtractor.proposal("""{"outcome":"brilliant","suggestedLevel":1}""")!!.outcome)
        assertEquals(Outcome.breakdown, OnDeviceAssessmentExtractor.proposal("""{"outcome":"BREAKDOWN"}""")!!.outcome)
        val defaults = OnDeviceAssessmentExtractor.proposal("""{"outcome":"partial"}""")!!
        assertEquals("", defaults.nextGoal)
        assertEquals("", defaults.capability)
        assertTrue(defaults.words.isEmpty())
    }

    @Test fun oneMalformedWordNeverDiscardsTheWholeAssessment() {
        val reply = """{"outcome":"success","suggestedLevel":2,"nextGoal":"g","capability":"c","words":[$word,"not-an-object",
            {"lemma":"","meaning":"house","form":"casa","quote":"la casa","language":"es","kind":"independent","confidence":0.95,"sourceIDs":["target"]},
            {"lemma":"el perro","meaning":"dog","form":"perro","quote":"el perro","language":"es","kind":"inventive","confidence":0.95,"sourceIDs":["target"]},
            {"lemma":"el gato","meaning":"cat","form":"gato","quote":"el gato","language":"es","kind":"assisted","confidence":"certain","sourceIDs":["target"]},
            {"lemma":"el pan","meaning":"bread","form":"pan","quote":"el pan","kind":"assisted","confidence":0.9,"sourceIDs":["target"]}]}"""
        val proposal = OnDeviceAssessmentExtractor.proposal(reply)!!
        assertEquals(Outcome.success, proposal.outcome)
        assertEquals(listOf("la casa"), proposal.words.map { it.lemma })
    }

    @Test fun wordsBeyondTheSchemaMaximumAreTrimmed() {
        val many = (1..14).joinToString(",") { word.replace("la casa", "la casa $it") }
        val proposal = OnDeviceAssessmentExtractor.proposal("""{"outcome":"success","suggestedLevel":1,"words":[$many]}""")!!
        assertEquals(12, proposal.words.size)
        assertEquals("la casa 1", proposal.words.first().lemma)
        assertEquals("la casa 12", proposal.words.last().lemma)
    }

    @Test fun aReplyWithoutAnAssessmentObjectIsUnusable() {
        for (raw in listOf("", "   ", "I cannot assess that.", """{"nextGoal":"no outcome here"}""", "[1,2,3]",
            "{broken", "```json\nnull\n```")) {
            assertNull(raw, OnDeviceAssessmentExtractor.proposal(raw))
        }
    }

    @Test fun theConstrainedAttemptRunsFirstWithABoundedOutputLength() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply(valid))
        val result = helpers(runtime).respond(instructions, "TARGET LANGUAGE: es", schema,
            purpose = HelperPurpose.ASSESSMENT)
        assertEquals(valid, result.text)
        val request = runtime.requests.single().request
        assertEquals(schema.toString(), request.jsonSchema)
        assertEquals(1_024, request.maxOutputTokens)
        assertTrue(result.sources.isEmpty())
    }

    @Test fun anUnusableConstrainedReplyGetsExactlyOneUnconstrainedRetry() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("The assessment ", "is not JSON."), ScriptedReply(valid))
        val result = helpers(runtime).respond(instructions, "TARGET LANGUAGE: es", schema,
            purpose = HelperPurpose.ASSESSMENT)
        assertEquals(valid, result.text)
        assertEquals(2, runtime.requests.size)
        val retry = runtime.requests[1]
        assertNull(retry.request.jsonSchema)
        assertEquals(1_024, retry.request.maxOutputTokens)
        assertEquals("TARGET LANGUAGE: es", retry.request.prompt)
        assertTrue(retry.systemPrompt!!, retry.systemPrompt!!.startsWith("$instructions\n"))
        assertTrue(retry.systemPrompt!!,
            retry.systemPrompt!!.contains("Return ONLY a single valid JSON object matching this schema, with no markdown or prose"))
        assertTrue(retry.systemPrompt!!, retry.systemPrompt!!.contains(schema.toString()))
    }

    @Test fun aRuntimeWithoutConstrainedDecodingStillAssessesOnTheRetry() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply(failure = "jsonSchema is not supported"), ScriptedReply(valid))
        val result = helpers(runtime).respond(instructions, "TARGET LANGUAGE: es", schema,
            purpose = HelperPurpose.ASSESSMENT)
        assertEquals(valid, result.text)
        assertEquals(2, runtime.requests.size)
        assertNull(runtime.requests[1].request.jsonSchema)
    }

    @Test fun twoUnusableAttemptsStopWithoutAThirdRequest() = runTest {
        // Only two replies exist: a third attempt would fail the test with no scripted reply left.
        val runtime = ScriptedModelRuntime(ScriptedReply(""), ScriptedReply("Sorry, I cannot."))
        val result = helpers(runtime).respond(instructions, "TARGET LANGUAGE: es", schema,
            purpose = HelperPurpose.ASSESSMENT)
        assertEquals(2, runtime.requests.size)
        assertNull(OnDeviceAssessmentExtractor.proposal(result.text))
    }

    @Test fun aFailedRetrySurfacesTheModelRuntimeFailure() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply(failure = "constrained decoding unsupported"),
            ScriptedReply(failure = "engine unavailable"))
        try {
            helpers(runtime).respond(instructions, "TARGET LANGUAGE: es", schema, purpose = HelperPurpose.ASSESSMENT)
            fail("A failed On-Device Model answered the assessment Helper")
        } catch (_: ModelRuntimeException) { }
        assertEquals(2, runtime.requests.size)
    }
}
