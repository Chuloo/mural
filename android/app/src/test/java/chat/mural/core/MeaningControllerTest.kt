package chat.mural.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
        MeaningController(backgroundScope, delayMillis, translator::translate)

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

    @Test fun continuousFragmentsDoNotKeepRestartingTheDelay() = runTest {
        val translator = Translator(); val controller = controller(translator, delayMillis = 30)
        for (revision in 0 until 12) {
            controller.update(request("hei ".repeat(revision + 1), revision = revision))
            advanceTimeBy(10); runCurrent()
        }
        assertEquals(1, translator.requests.size)
        assertTrue(translator.requests.first().text.length < 48)
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
        assertEquals(2, translator.requests.size)
        translator.succeed("Hi"); runCurrent()
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
}
