package chat.mural.ondevice

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fake must satisfy the same contract the LiteRT-LM adapter offers to providers. */
class ScriptedModelRuntimeTest {
    private suspend fun session(vararg replies: ScriptedReply): ScriptedModelRuntime.ScriptedSession {
        val runtime = ScriptedModelRuntime(*replies)
        return runtime.load("/fake/model.litertlm") as ScriptedModelRuntime.ScriptedSession
    }

    @Test
    fun `streams partial tokens and returns the joined reply`() = runTest {
        val session = session(ScriptedReply("Olá", " ", "tudo bem?"))
        val partials = mutableListOf<String>()
        val reply = session.respond(GenerationRequest("hi")) { partials += it }
        assertEquals("Olá tudo bem?", reply)
        assertEquals(listOf("Olá", " ", "tudo bem?"), partials)
    }

    @Test
    fun `records each request with the session system prompt and pops replies in order`() = runTest {
        val runtime = ScriptedModelRuntime(
            ScriptedReply("first"),
            ScriptedReply("second"),
        )
        val session = runtime.load("/fake/model.litertlm") as ScriptedModelRuntime.ScriptedSession
        session.restart("You are Mural.")
        assertEquals("first", session.respond(GenerationRequest("one")))
        assertEquals("second", session.respond(GenerationRequest("two")))
        assertEquals(2, runtime.requests.size)
        assertEquals("You are Mural.", runtime.requests[0].systemPrompt)
        assertEquals("one", runtime.requests[0].request.prompt)
        assertEquals("two", runtime.requests[1].request.prompt)
    }

    @Test
    fun `carries schema and token-limit requests unchanged`() = runTest {
        val runtime = ScriptedModelRuntime(ScriptedReply("{}"))
        val session = runtime.load("/fake/model.litertlm") as ScriptedModelRuntime.ScriptedSession
        session.respond(
            GenerationRequest(prompt = "assess", jsonSchema = """{"type":"object"}""", maxOutputTokens = 512)
        )
        val recorded = runtime.requests.single().request
        assertEquals("""{"type":"object"}""", recorded.jsonSchema)
        assertEquals(512, recorded.maxOutputTokens)
    }

    @Test
    fun `surfaces scripted failures and missing replies as ModelRuntimeException`() = runTest {
        val session = session(ScriptedReply(failure = "engine exploded"))
        try {
            session.respond(GenerationRequest("hi"))
            fail("Expected ModelRuntimeException")
        } catch (e: ModelRuntimeException) {
            assertEquals("engine exploded", e.message)
        }
        try {
            session.respond(GenerationRequest("again"))
            fail("Expected ModelRuntimeException once the script is empty")
        } catch (e: ModelRuntimeException) {
            assertTrue(e.message!!.contains("No scripted reply left"))
        }
    }

    @Test
    fun `cancelling the caller stops further streaming`() = runTest {
        val session = session(ScriptedReply("a", "b", "c", delayMillis = 50))
        val partials = mutableListOf<String>()
        val job = launch { session.respond(GenerationRequest("slow")) { partials += it } }
        // The first delay elapses far in the future of virtual time; cancel before any token.
        job.cancelAndJoin()
        assertTrue(partials.isEmpty())
    }
}
