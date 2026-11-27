package chat.mural.ondevice

import org.junit.Assert.*
import org.junit.Test

class OnDeviceModelStoreTest {
    @Test fun importedModelMetadataSurvivesARestartAndOnlyAnImportedModelCanBeActive() {
        val saved = mutableListOf<String>()
        val store = OnDeviceModelStore(save = { saved += it })
        assertNull(store.active())
        assertFalse(store.select("gemma-4-e4b.litertlm"))
        val imported = store.import("gemma-4-e4b.litertlm", 3_200_000_000L)
        assertEquals("gemma-4-e4b.litertlm", imported.name)
        assertEquals(3_200_000_000L, imported.sizeBytes)
        assertEquals(imported, store.active())
        assertEquals(1, saved.size)
        val restored = OnDeviceModelStore(saved.single(), save = { saved += it })
        assertEquals(listOf(imported), restored.models)
        assertEquals(imported, restored.active())

        val second = restored.import("qwen2.5-1.5b.litertlm", 900_000_000L)
        assertEquals(second, restored.active())
        assertTrue(restored.select(imported.name))
        assertEquals(imported, restored.active())
        assertFalse(restored.select("model-that-was-never-imported.litertlm"))
        assertEquals(imported, restored.active())
        // Re-importing one file replaces its metadata instead of listing it twice.
        val replaced = restored.import("qwen2.5-1.5b.litertlm", 1L)
        assertEquals(listOf(imported, replaced), restored.models)
        assertEquals(replaced, restored.active())
        assertEquals(4, saved.size)
    }

    @Test fun theZeroDownloadSystemModelBecomesActiveWithoutAFileOnDisk() {
        val saved = mutableListOf<String>()
        val store = OnDeviceModelStore(save = { saved += it })
        store.selectSystem("aicore-gemini-nano")
        // The AICore model has no .litertlm file, so its catalogue id alone is the active model.
        assertEquals("aicore-gemini-nano", store.activeID())
        assertNull(store.active())
        assertTrue(store.models.isEmpty())
        val restored = OnDeviceModelStore(saved.single(), save = { saved += it })
        assertEquals("aicore-gemini-nano", restored.activeID())
        // A downloaded model replaces it, and switching back keeps the downloaded one's metadata.
        val e4b = restored.import("gemma-4-E4B-it.litertlm", 3_659_530_240L)
        assertEquals(e4b.name, restored.activeID())
        assertEquals(e4b, restored.active())
        restored.selectSystem("aicore-gemini-nano")
        assertEquals("aicore-gemini-nano", restored.activeID())
        assertEquals(listOf(e4b), restored.models)
        // The system model is not a stored file, so there is nothing to delete and nothing reclaimed.
        assertFalse(restored.delete("aicore-gemini-nano"))
        assertEquals("aicore-gemini-nano", restored.activeID())
    }

    @Test fun deletingAModelReclaimsItsPlaceAndDeletingTheActiveOneLeavesNoActiveModel() {
        val saved = mutableListOf<String>()
        val store = OnDeviceModelStore(save = { saved += it })
        assertFalse(store.delete("a-model-that-was-never-imported.litertlm"))
        assertTrue(saved.isEmpty())
        val e4b = store.import("gemma-4-E4B-it.litertlm", 3_659_530_240L)
        val qwen = store.import("Qwen2.5-1.5B-Instruct.litertlm", 1_597_922_856L)
        assertEquals(qwen, store.active())
        // Deleting a model that is not active keeps the active one, and the choice stays explicit:
        // deleting the active model leaves no active model instead of silently picking another.
        assertTrue(store.delete(e4b.name))
        assertEquals(listOf(qwen), store.models)
        assertEquals(qwen, store.active())
        assertFalse(store.delete(e4b.name))
        assertTrue(store.delete(qwen.name))
        assertTrue(store.models.isEmpty())
        assertNull(store.active())
        assertFalse(store.select(qwen.name))
        val restored = OnDeviceModelStore(saved.last(), save = { saved += it })
        assertTrue(restored.models.isEmpty())
        assertNull(restored.active())
        assertEquals(4, saved.size)
    }

    @Test fun onlyASafeLitertlmFileNameIsAcceptedFromTheDocumentPicker() {
        assertEquals("gemma-4-e4b.litertlm", OnDeviceModelStore.fileName("Downloads/gemma-4-e4b.litertlm"))
        assertEquals("gemma-4-e4b.litertlm", OnDeviceModelStore.fileName("..\\..\\gemma-4-e4b.litertlm"))
        assertEquals("gemma-4-e4b.litertlm", OnDeviceModelStore.fileName("gemma-4-e4b.litertlm"))
        assertEquals("model.litertlm", OnDeviceModelStore.fileName("model\u0000.litertlm"))
        assertEquals(104, OnDeviceModelStore.fileName("m".repeat(400) + ".litertlm").length)
        val store = OnDeviceModelStore()
        for (rejected in listOf(null, "", "   ", "gemma.gguf", "..", ".litertlm", "models/model.litertlm.txt")) {
            try { store.import(rejected, 1L); fail("Accepted $rejected") }
            catch (_: ModelRuntimeException) { }
        }
        assertTrue(store.models.isEmpty())
    }
}
