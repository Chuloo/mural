package chat.mural.ondevice

import chat.mural.core.*
import chat.mural.network.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zero server telemetry, pinned: a whole On-Device Model conversation — a voice turn, every Helper
 * and a filed report — with each server seam present and configured as a signed-in learner holding
 * a saved key would have it. Every seam counts the attempt and fails loud, so one automatic call
 * ends the test at the call site and the counters assert none happened from session start to end.
 *
 * There is no report client left to call: a report is a [ReportRecord] in on-device storage.
 */
class OnDeviceZeroEgressTest {
    private val attempts = mutableListOf<String>()

    /** One constrained assessment object, as a Model Runtime that supports `jsonSchema` returns it. */
    private val assessmentReply = """{"outcome":"success","suggestedLevel":2,"nextGoal":"Practica en casa.","capability":"Greets","words":[{"lemma":"hola","meaning":"hello","form":"hola","quote":"hola","language":"es","kind":"independent","confidence":0.9,"sourceIDs":["target"]}]}"""

    /** A configured server seam. It records the attempt before failing, so a swallowed call still shows. */
    private abstract inner class FailLoud(private val seam: String) {
        var calls = 0; private set
        protected fun egress(): Nothing {
            calls++; attempts += seam
            throw AssertionError("An On-Device Model session reached $seam")
        }
    }

    /** The cloud conversation and Helper seam: a personal key's client and a hosted lease alike. */
    private inner class FailLoudCloud : FailLoud("a cloud conversation, Helper or live session"),
        TeachingClient, LiveSessionProvider {
        override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean,
            purpose: HelperPurpose?): APIResult = egress()
        override suspend fun streamMeaning(instructions: String, input: String, onText: (String) -> Unit): APIResult = egress()
        override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection = egress()
    }

    /** A signed-in member's account seam, including the launch and foreground profile probes. */
    private inner class FailLoudAccount : FailLoud("an account probe"), AccountService {
        override suspend fun providers(): AccountProviders = egress()
        override suspend fun challenge(): AccountChallenge = egress()
        override suspend fun exchange(challenge: AccountChallenge, idToken: String, expectedAccountID: String?): AccountExchange = egress()
        override suspend fun profile(session: AccountSession): AccountProfile = egress()
        override suspend fun minutes(session: AccountSession): MinuteBalance = egress()
        override suspend fun signOut(session: AccountSession) = egress()
        override suspend fun delete(session: AccountSession) = egress()
    }

    /** Guest minutes, whose grant and balance reads fund a hosted conversation. */
    private inner class FailLoudGuestMinutes : FailLoud("a guest minute probe"), GuestMinuteService {
        override suspend fun start(installationToken: String): GuestGrant = egress()
        override suspend fun balance(session: AccountSession): MinuteBalance = egress()
        override suspend fun link(member: AccountSession, guestAccessToken: String): GuestLinkResult = egress()
    }

    private class FakeSpeechInput(turns: List<SpeechTurn>) : OnDeviceSpeechInput {
        private val queue = ArrayDeque(turns)
        var closed = false
        override val level = 0.0
        override suspend fun awaitTurn(): SpeechTurn? = queue.removeFirstOrNull() ?: awaitCancellation()
        override fun mute(muted: Boolean) { }
        override fun close() { closed = true }
    }

    private class FakeSpeechOutput : OnDeviceSpeechOutput {
        val spoken = mutableListOf<String>()
        override val speaking = false
        override val level = 0.0
        override fun speak(sentence: String, locale: String, queueAdd: Boolean) { spoken += sentence }
        override fun stop() { }
        override fun close() { }
    }

    private fun member() = AccountSession("e3c1d862-2d0f-4bf0-a44f-404e9c559581", "a".repeat(43),
        System.currentTimeMillis() + 60_000)

    /** The fakes are the guarantee's teeth: without this the ledger could stay empty by ignoring a call. */
    @Test fun everyConfiguredServerSeamFailsLoudSoAnEmptyLedgerMeansZeroCalls() = runTest {
        val cloud = FailLoudCloud(); val accounts = FailLoudAccount(); val guests = FailLoudGuestMinutes()
        assertTrue(runCatching { cloud.respond("Teach.", "hola") }.exceptionOrNull() is AssertionError)
        assertTrue(runCatching { cloud.createLiveSession(LiveSessionRequest("", "")) }.exceptionOrNull() is AssertionError)
        assertTrue(runCatching { accounts.providers() }.exceptionOrNull() is AssertionError)
        assertTrue(runCatching { accounts.minutes(member()) }.exceptionOrNull() is AssertionError)
        assertTrue(runCatching { guests.balance(member()) }.exceptionOrNull() is AssertionError)
        assertEquals(2, cloud.calls); assertEquals(2, accounts.calls); assertEquals(1, guests.calls)
        assertEquals(5, attempts.size)
    }

    @Test fun aFullOnDeviceConversationAndItsReportNeverReachAServerClient() = runTest {
        val cloud = FailLoudCloud(); val accounts = FailLoudAccount(); val guests = FailLoudGuestMinutes()
        val runtime = ScriptedModelRuntime(
            ScriptedReply("¡Hola! ", "¿Cómo estás?"),
            ScriptedReply("<heard>Hola</heard>", "Muy bien. ", "¿Y tú?"),
            ScriptedReply("Hola ", "significa hello."),
            ScriptedReply(assessmentReply),
            ScriptedReply("Una casa ", "es a house."),
        )
        val turn = scriptedTurn(runtime)
        val input = FakeSpeechInput(listOf(SpeechTurn.AudioWav(400, WavAudio.wrapPcm16Mono(ByteArray(400 * 32)))))
        val output = FakeSpeechOutput()
        val session = OnDeviceLiveSession(backgroundScope, turn, input, output, onEvent = {}, now = { 1_000.0 })
        // The Helpers of an On-Device Model session, wired exactly as the conversation ViewModel does.
        val helpers = OnDeviceTeachingClient(turn) { "Offline, I cannot look that up." }

        session.start("Teach Spanish.", "es-ES", nativeAudioInput = true)
        advanceTimeBy(3_000); runCurrent()
        assertTrue(session.command("instructions", "Greet the learner in Spanish."))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf("¡Hola!", "¿Cómo estás?"), output.spoken.take(2))
        // The learner's recorded clip and the teacher's reply both stay on the phone.
        advanceTimeBy(3_000); runCurrent()
        assertTrue(output.spoken.toString(), output.spoken.contains("Muy bien."))

        // Every Helper purpose a conversation reaches: meaning, assessment, lookup and a web topic.
        assertEquals("Hola significa hello.", helpers.streamMeaning("Give the meaning.", "Hola") {}.text)
        val assessment = helpers.respond("Assess.", "hola", buildJsonObject { put("type", "object") },
            purpose = HelperPurpose.ASSESSMENT)
        assertTrue(assessment.text.contains("\"outcome\""))
        assertTrue(helpers.respond("Explain.", "casa", purpose = HelperPurpose.LOOKUP).text.contains("house"))
        // A web-dependent Helper never searches: a topic lives on its sources, so offline it is
        // empty rather than invented, and anything else degrades to the module's offline reply.
        assertEquals("", helpers.respond("Find a topic.", "Madrid", search = true, purpose = HelperPurpose.TOPIC).text)
        assertEquals("Offline, I cannot look that up.",
            helpers.respond("Explain.", "casa", search = true, purpose = HelperPurpose.LOOKUP).text)

        // Filing a report saves it on this phone, with every cloud seam above still configured.
        val saved = mutableListOf<ReportRecord>()
        val reports = ReportController(backgroundScope, { saved += it }, now = { 1_000.0 })
        val conversation = SessionRecord(languageID = "es").also {
            it.append(Fragment(id = "target", speaker = Speaker.assistant, text = "¡Hola! ¿Cómo estás?", startMS = 0, endMS = 900))
        }
        reports.open(ReportSelection.from(conversation, "target")!!)
        reports.submit(AIReportSubmission("e3c1d862-2d0f-4bf0-a44f-404e9c559581", "es", "incorrect",
            "¡Hola! ¿Cómo estás?", REPORT_CONSENT_VERSION))
        runCurrent()
        assertEquals(ReportDelivery.SAVED, reports.state.value.delivery)
        assertEquals(listOf("¡Hola! ¿Cómo estás?"), saved.map { it.excerpt })

        session.close()
        advanceTimeBy(3_000); runCurrent()
        assertTrue(input.closed)
        // The conversation really ran on the Model Runtime, so an empty ledger is not a no-op pass.
        assertTrue(runtime.requests.size >= 5)
        assertEquals(0, cloud.calls); assertEquals(0, accounts.calls); assertEquals(0, guests.calls)
        assertEquals(emptyList<String>(), attempts)
    }
}
