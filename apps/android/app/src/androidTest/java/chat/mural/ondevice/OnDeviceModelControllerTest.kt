package chat.mural.ondevice

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.OnDeviceReadiness
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The On-Device Model's RAM lifecycle, observed at the [OnDeviceReadiness] seam and at the
 * [ModelRuntime] the controller is given: which Conversation Provider is chosen decides whether a
 * multi-gigabyte model is held in RAM at all, and releasing it never loses the learner's selection.
 * Needs a real [Context] for the model metadata, so it runs on a device like the other stores.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceModelControllerTest {
    private val seededModels = """{"models":[{"name":"first-model.litertlm","sizeBytes":1024},""" +
        """{"name":"second-model.litertlm","sizeBytes":2048}],"active":"first-model.litertlm"}"""

    private class RecordingModelRuntime : ModelRuntime {
        val sessions = Collections.synchronizedList(mutableListOf<RecordingSession>())
        override suspend fun load(modelPath: String, options: RuntimeOptions): RuntimeSession =
            RecordingSession(modelPath).also { sessions += it }
        override suspend fun supportsAudioInput(modelPath: String) = true
    }

    private class RecordingSession(val modelPath: String) : RuntimeSession {
        @Volatile var closed = false
        override fun restart(systemPrompt: String?) { }
        override suspend fun respond(request: GenerationRequest, onPartial: (String) -> Unit) = ""
        override fun close() { closed = true }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("timed out waiting for $what", condition())
    }

    @Test fun unloadClosesTheRuntimeSessionAndTheSameModelReloadsWithoutLosingItsSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("mural_on_device_models", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = RecordingModelRuntime()
        val readiness = AtomicReference(OnDeviceReadiness())
        val onDeviceChosen = AtomicBoolean(true)
        try {
            preferences.edit().putString("models", seededModels).commit()
            val controller = OnDeviceModelController(context, scope, runtime = runtime,
                changed = readiness::set, providerIsOnDevice = { onDeviceChosen.get() })

            // On-Device Model chosen: the active model is loaded into the Model Runtime's RAM.
            controller.ensureLoaded()
            awaitUntil("the active model to load") { readiness.get().ready }
            assertEquals("first-model.litertlm", readiness.get().modelID)
            assertEquals(1, runtime.sessions.size)
            assertFalse(runtime.sessions.single().closed)

            // Another Conversation Provider is chosen: the session closes and the RAM is released,
            // while the active model stays selected so switching back needs no learner action.
            onDeviceChosen.set(false)
            controller.unload()
            assertTrue(runtime.sessions.single().closed)
            assertFalse(readiness.get().initialized)
            assertEquals("first-model.litertlm", readiness.get().modelID)
            assertEquals(listOf("first-model.litertlm", "second-model.litertlm"),
                controller.stored().map { it.name })

            // On-Device Model again: the same selection loads cleanly into a fresh session.
            onDeviceChosen.set(true)
            controller.ensureLoaded()
            awaitUntil("the same model to reload") { readiness.get().ready }
            assertEquals(2, runtime.sessions.size)
            assertTrue(runtime.sessions[0].closed)
            assertFalse(runtime.sessions[1].closed)
            assertEquals("first-model.litertlm", readiness.get().modelID)
            controller.close()
            assertTrue(runtime.sessions[1].closed)
        } finally { scope.cancel(); preferences.edit().clear().commit() }
    }

    @Test fun aModelChosenForAnotherProviderIsStoredButNeverLoadedIntoRam() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("mural_on_device_models", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = RecordingModelRuntime()
        val readiness = AtomicReference(OnDeviceReadiness())
        try {
            preferences.edit().putString("models", seededModels).commit()
            val controller = OnDeviceModelController(context, scope, runtime = runtime,
                changed = readiness::set, providerIsOnDevice = { false })

            // A cloud Conversation Provider is chosen: selecting a model records the choice and
            // leaves the Model Runtime empty, so no gigabytes are held for a provider that will
            // never run them.
            controller.select("second-model.litertlm")
            controller.ensureLoaded()
            awaitUntil("the selection to publish") { readiness.get().modelID == "second-model.litertlm" }
            Thread.sleep(500)
            assertTrue(runtime.sessions.isEmpty())
            assertFalse(readiness.get().initialized)
            assertFalse(readiness.get().ready)
            assertEquals("second-model.litertlm", controller.stored().last().name)
        } finally { scope.cancel(); preferences.edit().clear().commit() }
    }
}
