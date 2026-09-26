package chat.mural.core

import java.io.IOException
import chat.mural.network.HostedFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeaningControllerTest {
    private class Translator {
        val requests = mutableListOf<MeaningRequest>()
        val pending = ArrayDeque<CompletableDeferred<MeaningResult>>()
        val partials = mutableListOf<(String) -> Unit>()
        suspend fun stream(request: MeaningRequest, onText: (String) -> Unit): MeaningResult {
            partials += onText
            return translate(request)
        }
        suspend fun translate(request: MeaningRequest): MeaningResult {
            requests += request
            val response = CompletableDeferred<MeaningResult>().also { pending.addLast(it) }
            // Ignores cancellation to exercise late network responses.
            return withContext(NonCancellable) { response.await() }
        }
        fun succeed(text: String) { pending.removeFirst().complete(MeaningResult(text)) }
        fun fail() { pending.removeFirst().completeExceptionally(IOException("offline")) }
    }

    private fun request(text: String, revision: Int = 0, language: String = "English", passageID: String = "p"): MeaningRequest {
        val fragment = Fragment(id = passageID, revision = revision, speaker = Speaker.assistant, text = text, startMS = 0, endMS = 1000)
        return MeaningRequest("session", Passage(passageID, Speaker.assistant, listOf(fragment)), learningLanguageID = "nb", meaningLanguage = language)
    }

    private fun TestScope.controller(translator: Translator, delayMillis: Long = 0) =
        MeaningController(backgroundScope, minimumSpacingMillis = 0, now = { testScheduler.currentTime }, delayMillis = delayMillis, incompleteDelayMillis = delayMillis, translate = translator::translate)

    @Test fun partialMeaningIsVisibleBeforeCompletionAndSavedOnlyOnce() = runTest {
        val translator = Translator()
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, delayMillis = 0, incompleteDelayMillis = 0,
            now = { testScheduler.currentTime }, stream = translator::stream, translate = translator::translate)
        var saved = 0; controller.onResult = { _, _ -> saved++ }
        controller.update(request("Hei, verden.")); runCurrent()
        translator.partials[0]("Hello")
        assertEquals("Hello", controller.text); assertTrue(controller.isLoading); assertEquals(0, saved)
        translator.succeed("Hello, world."); runCurrent()
        translator.partials[0]("Late text")
        assertEquals("Hello, world.", controller.text); assertFalse(controller.isLoading); assertEquals(1, saved)
    }
    @Test fun growingMeaningDoesNotFlashBackToItsFirstWord() = runTest {
        val translator = Translator()
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, delayMillis = 0, incompleteDelayMillis = 0,
            now = { testScheduler.currentTime }, stream = translator::stream, translate = translator::translate)
        controller.update(request("Hei, jeg liker")); runCurrent()
        translator.succeed("Hello, I like"); runCurrent()
        controller.update(request("Hei, jeg liker fisk.", revision = 1)); runCurrent()
        translator.partials[1]("Hello")
        assertEquals("Hello, I like", controller.text)
        translator.partials[1]("Hello, I like fish")
        assertEquals("Hello, I like fish", controller.text)
        translator.succeed("Hi, I like fish."); runCurrent()
        assertEquals("Hi, I like fish.", controller.text)
    }
    @Test fun correctedCaptionAndCachedResultRejectOldStreamCallbacks() = runTest {
        val translator = Translator()
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, delayMillis = 0, incompleteDelayMillis = 0,
            now = { testScheduler.currentTime }, stream = translator::stream, translate = translator::translate)
        controller.update(request("Jeg liker kaffe.")); runCurrent()
        translator.partials[0]("I like coffee")
        val corrected = request("Jeg liker te.", revision = 1)
        controller.update(corrected); translator.partials[0]("Late coffee")
        assertEquals("", controller.text)
        translator.succeed("I like coffee."); runCurrent()
        translator.partials[1]("I like tea")
        controller.update(corrected, cached = "Cached tea")
        translator.partials[1]("Late tea"); translator.fail(); runCurrent()
        assertEquals("Cached tea", controller.text); assertNull(controller.error)
    }
    @Test fun failedOrHiddenStreamsDoNotCacheOrResurrectPartialText() = runTest {
        val translator = Translator()
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, delayMillis = 0, incompleteDelayMillis = 0,
            now = { testScheduler.currentTime }, stream = translator::stream, translate = translator::translate)
        var saved = 0; controller.onResult = { _, _ -> saved++ }
        controller.update(request("Hei")); runCurrent(); translator.partials[0]("Hi")
        translator.fail(); runCurrent(); translator.partials[0]("Late")
        assertEquals("", controller.text); assertNotNull(controller.error); assertEquals(0, saved)
        controller.retry(); runCurrent(); translator.partials[1]("Hi")
        controller.reset(); translator.partials[1]("Late"); translator.succeed("Hi"); runCurrent()
        assertEquals("", controller.text); assertEquals(0, saved); assertFalse(controller.isLoading)
    }

    @Test fun growingSpeechCoalescesWithoutCancellingTheRunningTranslation() = runTest {
        val translator = Translator(); val controller = controller(translator)
        controller.update(request("Hei")); runCurrent()
        assertEquals(1, translator.requests.size)
        controller.update(request("Hei,", revision = 1))
        controller.update(request("Hei, jeg", revision = 2))
        controller.update(request("Hei, jeg liker kaffe.", revision = 3))
        assertEquals(1, translator.requests.size)
        translator.succeed("Hi"); runCurrent()
        assertEquals(2, translator.requests.size)
        assertEquals("Hi", controller.text)
        assertTrue(controller.isLoading)
        assertEquals("Hei, jeg liker kaffe.", translator.requests[1].text)
        translator.succeed("Hi, I like coffee."); runCurrent()
        assertFalse(controller.isLoading)
        assertEquals("Hi, I like coffee.", controller.text)
        assertNull(controller.error)
    }

    @Test fun continuousFragmentsWaitForAQuietIntervalBeforeCalling() = runTest {
        val translator = Translator(); val controller = controller(translator, delayMillis = 30)
        for (revision in 0 until 12) {
            controller.update(request("hei ".repeat(revision + 1), revision = revision))
            advanceTimeBy(10); runCurrent()
        }
        assertEquals(0, translator.requests.size)
        advanceTimeBy(30); runCurrent()
        assertEquals(1, translator.requests.size)
        assertEquals(48, translator.requests.first().text.length)
        controller.reset()
        if (translator.pending.isNotEmpty()) translator.succeed("Hello")
    }

    @Test fun hidingMeaningRejectsLateResultsAndCanShowACachedTranslation() = runTest {
        val translator = Translator(); val controller = controller(translator)
        var saved = 0
        controller.onResult = { _, _ -> saved++ }
        controller.update(request("Hei")); runCurrent()
        assertEquals(1, translator.requests.size)
        controller.reset()
        controller.update(request("Hei"), cached = "Hi")
        translator.fail(); runCurrent()
        assertEquals("Hi", controller.text)
        assertNull(controller.error)
        assertFalse(controller.isLoading)
        assertEquals(0, saved)
        assertEquals(1, translator.requests.size)
    }

    @Test fun newPassageRejectsThePreviousPassagesResponse() = runTest {
        val translator = Translator(); val controller = controller(translator)
        controller.update(request("Hei")); runCurrent()
        controller.update(request("Ha det", passageID = "next")); runCurrent()
        assertEquals(1, translator.requests.size)
        translator.succeed("Hi"); runCurrent()
        assertEquals(2, translator.requests.size)
        assertEquals("", controller.text)
        assertTrue(controller.isLoading)
        translator.succeed("Goodbye"); runCurrent()
        assertFalse(controller.isLoading)
        assertEquals("Goodbye", controller.text)
    }

    @Test fun correctedTranscriptNeverDisplaysMeaningOfTheOldWords() = runTest {
        val translator = Translator(); val controller = controller(translator)
        controller.update(request("Jeg liker kaffe.")); runCurrent()
        controller.update(request("Jeg liker te.", revision = 1))
        translator.succeed("I like coffee."); runCurrent()
        assertEquals(2, translator.requests.size)
        assertEquals("", controller.text)
        translator.succeed("I like tea."); runCurrent()
        assertFalse(controller.isLoading)
        assertEquals("I like tea.", controller.text)
    }

    @Test fun failureIsVisibleAndRetriesOnlyWhenRequested() = runTest {
        val translator = Translator(); val controller = controller(translator)
        controller.update(request("Hei")); runCurrent()
        translator.fail(); runCurrent()
        assertNotNull(controller.error)
        controller.update(request("Hei!", revision = 1)); runCurrent()
        assertEquals(1, translator.requests.size)
        assertFalse(controller.isLoading)
        controller.retry(); runCurrent()
        assertEquals(2, translator.requests.size)
        assertNull(controller.error)
        assertEquals("Hei!", translator.requests[1].text)
        translator.succeed("Hi!"); runCurrent()
        assertFalse(controller.isLoading)
        assertEquals("Hi!", controller.text)
    }

    @Test fun changingMeaningLanguageClearsOldTextAndUsesSeparateCacheKeys() = runTest {
        val translator = Translator(); val controller = controller(translator)
        val english = request("Hei"); val french = request("Hei", language = "French")
        assertNotEquals(english.cacheKey, french.cacheKey)
        controller.update(english, cached = "Hi")
        controller.update(french)
        assertEquals("", controller.text)
        runCurrent()
        assertEquals(1, translator.requests.size)
        assertEquals("French", translator.requests[0].meaningLanguage)
        translator.succeed("Salut"); runCurrent()
        assertFalse(controller.isLoading)
        assertEquals("Salut", controller.text)
    }
    @Test fun sentenceBoundaryUsesShortDelayAndAnIncompleteSentenceWaitsLonger() = runTest {
        val translator = Translator()
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, now = { testScheduler.currentTime }, delayMillis = 100, incompleteDelayMillis = 1000, translate = translator::translate)
        controller.update(request("Por cierto,")); advanceTimeBy(100); runCurrent()
        assertTrue(translator.requests.isEmpty())
        controller.update(request("Por cierto, ¿te gusta el café?", revision = 1))
        advanceTimeBy(99); runCurrent(); assertTrue(translator.requests.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(1, translator.requests.size)
        translator.succeed("By the way, do you like coffee?"); runCurrent()
        assertNull(controller.error)
    }

    @Test fun finalUtteranceFlushesPendingTextWithoutWaitingForPunctuation() = runTest {
        val translator = Translator()
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, now = { testScheduler.currentTime }, incompleteDelayMillis = 2000, translate = translator::translate)
        val utterance = request("Hola, me llamo Ana")
        controller.update(utterance); advanceTimeBy(200)
        assertTrue(translator.requests.isEmpty())
        controller.update(utterance, utteranceComplete = true); runCurrent()
        assertEquals(1, translator.requests.size)
        translator.succeed("Hello, my name is Ana"); runCurrent()
        assertEquals("Hello, my name is Ana", controller.text)
    }

    @Test fun endingAfterRateDenialRetriesTheFinalTextAndClearsStalePrefix() = runTest {
        var calls = 0
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, now = { testScheduler.currentTime }, delayMillis = 0, incompleteDelayMillis = 0,
            canRetryFailure = HostedHelperRetry::isConfirmedNotAdmitted) {
            calls++
            when (calls) {
                1 -> MeaningResult("By the way,")
                2 -> throw HostedFailure.Http(429, "helper_session_limit")
                else -> MeaningResult("By the way, do you like coffee?")
            }
        }
        controller.update(request("Por cierto,")); runCurrent()
        val final = request("Por cierto, ¿te gusta el café?", revision = 1)
        controller.update(final); runCurrent()
        assertNotNull(controller.error); assertEquals("", controller.text)
        controller.update(final, utteranceComplete = true); runCurrent()
        assertEquals(3, calls); assertNull(controller.error)
        assertEquals("By the way, do you like coffee?", controller.text)
    }

    @Test fun endDuringInflightDenialAllowsOnlyOneConfirmedSafeRetry() = runTest {
        var calls = 0
        val rejected = CompletableDeferred<MeaningResult>()
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, now = { testScheduler.currentTime }, delayMillis = 0, incompleteDelayMillis = 0,
            canRetryFailure = HostedHelperRetry::isConfirmedNotAdmitted) {
            calls++; if (calls == 1) rejected.await() else throw HostedFailure.Http(429, "helper_session_limit")
        }
        val final = request("Hola.")
        controller.update(final); runCurrent()
        controller.update(final, utteranceComplete = true)
        rejected.completeExceptionally(HostedFailure.Http(409, "helper_session_window_closed")); runCurrent()
        assertEquals(2, calls); assertNotNull(controller.error); assertFalse(controller.isLoading)
    }

    @Test fun endNeverAutomaticallyRetriesAnUncertainProviderOutcome() = runTest {
        var calls = 0
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, now = { testScheduler.currentTime }, delayMillis = 0, incompleteDelayMillis = 0,
            canRetryFailure = HostedHelperRetry::isConfirmedNotAdmitted) {
            calls++; throw HostedFailure.Http(502, "helper_response_uncertain")
        }
        val final = request("Hola.")
        controller.update(final); runCurrent()
        controller.update(final, utteranceComplete = true); runCurrent()
        assertEquals(1, calls); assertNotNull(controller.error)
    }

    @Test fun repeatedLearnerFragmentsCannotLoopRetriesButEndCanRequestFinalMeaningOnce() = runTest {
        var calls = 0
        val controller = MeaningController(backgroundScope, minimumSpacingMillis = 0, now = { testScheduler.currentTime }, delayMillis = 0, incompleteDelayMillis = 0,
            canRetryFailure = HostedHelperRetry::isConfirmedNotAdmitted) {
            calls++; if (calls < 3) throw HostedFailure.Http(429, "helper_session_limit") else MeaningResult("Hello")
        }
        val final = request("Hola.")
        controller.update(final); runCurrent()
        controller.update(final, utteranceComplete = true); runCurrent()
        repeat(5) { controller.update(final, utteranceComplete = true); runCurrent() }
        assertEquals(2, calls); assertNotNull(controller.error)
        controller.update(final, utteranceComplete = true, conversationEnded = true); runCurrent()
        assertEquals(3, calls); assertEquals("Hello", controller.text)
    }

    @Test fun immediatelyCompletedTranslationDoesNotLeaveAStaleWorker() = runTest {
        var calls = 0
        val immediate = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val controller = MeaningController(immediate, minimumSpacingMillis = 0, now = { testScheduler.currentTime }, delayMillis = 0, incompleteDelayMillis = 0) {
            calls++; MeaningResult(it.text)
        }
        try {
            controller.update(request("Hola."))
            assertFalse(controller.isLoading)
            controller.update(request("Hola. ¿Cómo estás?", revision = 1))
            assertEquals(2, calls); assertEquals("Hola. ¿Cómo estás?", controller.text)
            assertFalse(controller.isLoading)
        } finally { immediate.cancel() }
    }

    @Test fun streamingRevisionsArePacedAndOnlyTheLatestTextIsDispatched() = runTest {
        val dispatched = mutableListOf<String>()
        val controller = MeaningController(backgroundScope, delayMillis = 0, incompleteDelayMillis = 0,
            minimumSpacingMillis = 2500, now = { testScheduler.currentTime }) {
            dispatched += it.text; MeaningResult(it.text)
        }
        controller.update(request("Hola.")); runCurrent()
        repeat(20) { revision ->
            advanceTimeBy(100); controller.update(request("Hola. " + "word ".repeat(revision + 1), revision + 1)); runCurrent()
        }
        assertEquals(listOf("Hola."), dispatched)
        advanceTimeBy(500); runCurrent()
        assertEquals(2, dispatched.size)
        assertEquals("Hola. " + "word ".repeat(20), dispatched.last())
        assertFalse(controller.isLoading)
    }

    @Test fun safeRateBackoffKeepsLoadingAndRetriesTheLatestRevision() = runTest {
        val dispatched = mutableListOf<String>()
        val controller = MeaningController(backgroundScope, delayMillis = 0, incompleteDelayMillis = 0,
            minimumSpacingMillis = 0, now = { testScheduler.currentTime },
            canRetryFailure = HostedHelperRetry::canRetryAtBoundary, retryDelay = HostedHelperRetry::automaticDelay) {
            dispatched += it.text
            if (dispatched.size == 1) throw HostedFailure.Http(429, "helper_session_limit", true, 5000)
            MeaningResult("Hello, Alex. Where are you from?")
        }
        controller.update(request("Hola, Alex.")); runCurrent()
        assertTrue(controller.isLoading); assertNull(controller.error)
        repeat(4) { revision ->
            advanceTimeBy(1000); controller.update(request("Hola, Alex. " + "word ".repeat(revision + 1), revision + 1)); runCurrent()
        }
        val final = request("Hola, Alex. ¿De dónde eres?", revision = 5)
        controller.update(final, conversationEnded = true); runCurrent()
        assertEquals(1, dispatched.size)
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("Hola, Alex.", final.text), dispatched)
        assertEquals("Hello, Alex. Where are you from?", controller.text)
        assertFalse(controller.isLoading); assertNull(controller.error)
    }

    @Test fun automaticRetryBudgetDoesNotResetForDeltasOrEnd() = runTest {
        var calls = 0
        val controller = MeaningController(backgroundScope, delayMillis = 0, incompleteDelayMillis = 0,
            minimumSpacingMillis = 0, now = { testScheduler.currentTime },
            canRetryFailure = HostedHelperRetry::canRetryAtBoundary, retryDelay = HostedHelperRetry::automaticDelay) {
            calls++; throw HostedFailure.Http(429, "helper_session_limit", true, 1000)
        }
        controller.update(request("Hola.")); runCurrent()
        repeat(12) { revision ->
            advanceTimeBy(1000); controller.update(request("Hola. " + "word ".repeat(revision + 1), revision + 1)); runCurrent()
        }
        controller.update(request("Hola. Final.", revision = 20), conversationEnded = true); runCurrent()
        assertEquals(4, calls); assertNotNull(controller.error); assertFalse(controller.isLoading)
    }

    @Test fun safeRetryWindowCannotExtendBeyondThirtySeconds() = runTest {
        var calls = 0
        val controller = MeaningController(backgroundScope, delayMillis = 0, incompleteDelayMillis = 0,
            minimumSpacingMillis = 0, now = { testScheduler.currentTime }, retryDelay = HostedHelperRetry::automaticDelay) {
            calls++; throw HostedFailure.Http(429, "helper_session_limit", true, 12000)
        }
        controller.update(request("Hola.")); runCurrent()
        advanceTimeBy(40_000); runCurrent()
        assertEquals(3, calls); assertNotNull(controller.error); assertFalse(controller.isLoading)
    }

    @Test fun closedHardLimitNeverSchedulesAutomaticRetries() = runTest {
        var calls = 0
        val controller = MeaningController(backgroundScope, delayMillis = 0, incompleteDelayMillis = 0,
            minimumSpacingMillis = 0, now = { testScheduler.currentTime },
            canRetryFailure = HostedHelperRetry::canRetryAtBoundary, retryDelay = HostedHelperRetry::automaticDelay) {
            calls++; throw HostedFailure.Http(429, "helper_session_limit", false)
        }
        controller.update(request("Hola.")); runCurrent()
        controller.update(request("Hola."), conversationEnded = true); advanceTimeBy(40_000); runCurrent()
        assertEquals(1, calls); assertNotNull(controller.error)
    }

    @Test fun latePrefixCannotReplaceOrFailNewerCachedMeaningOrDispatchItAgain() = runTest {
        for (fails in listOf(false, true)) {
            val translator = Translator(); val controller = controller(translator)
            controller.update(request("Hola.")); runCurrent()
            val complete = request("Hola. ¿Cómo estás?", revision = 1)
            controller.update(complete, cached = "Hello. How are you?")
            assertEquals("Hello. How are you?", controller.text)
            if (fails) translator.fail() else translator.succeed("Hello.")
            runCurrent()
            assertEquals("Hello. How are you?", controller.text)
            assertFalse(controller.isLoading); assertNull(controller.error)
            assertEquals(1, translator.requests.size)
        }
    }

    @Test fun translationInputKeepsTheStartOfLongPassages() {
        val text = "UNIQUE_START " + "y".repeat(2300) + " END"
        val prepared = MeaningRequest.translationInput(text)
        assertTrue(prepared.startsWith("UNIQUE_START"))
        assertTrue(prepared.endsWith(" END"))
        assertEquals(text, prepared)
    }

    @Test fun longCaptionFailureDoesNotCachePartialMeaningAndRetryKeepsFullInput() = runTest {
        val caption = "UNIQUE_START " + "我喜欢咖啡。 ".repeat(600) + " UNIQUE_END"
        val translator = Translator(); val controller = controller(translator)
        var saved = 0
        controller.onResult = { _, _ -> saved++ }
        controller.update(request(caption)); runCurrent()
        assertEquals(caption, translator.requests.single().translationInput)
        translator.fail(); runCurrent()
        assertNotNull(controller.error)
        assertEquals(0, saved)
        controller.retry(); runCurrent()
        assertEquals(caption, translator.requests.last().translationInput)
        translator.succeed("The complete translation."); runCurrent()
        assertEquals(1, saved)
        assertEquals("The complete translation.", controller.text)
    }

}
