package chat.mural.ondevice

import org.junit.Assert.*
import org.junit.Test

class OnDeviceModelCatalogueTest {
    @Test fun theCuratedCatalogueOffersGemma4E4BAsTheDefaultBesideTwoLighterModels() {
        val models = OnDeviceModelCatalogue.conversation
        assertEquals(listOf("gemma-4-e4b", "gemma-4-e2b", "qwen2.5-1.5b"), models.map { it.id })
        for (model in models) {
            assertEquals(ModelRuntimeKind.LITERT_LM, model.runtime)
            assertNotEquals(0, model.title)
            assertNotEquals(0, model.description)
            assertTrue(model.sizeBytes > 0)
            assertTrue(model.minDeviceMemoryGb > 0)
        }
        // The default recommendation is one entry, and every entry downloads over HTTPS.
        assertEquals("gemma-4-e4b", OnDeviceModelCatalogue.recommended().id)
        assertEquals(models[0], OnDeviceModelCatalogue.recommended())
        assertEquals(models[1], OnDeviceModelCatalogue.byID("gemma-4-e2b"))
        assertNull(OnDeviceModelCatalogue.byID("a-model-mural-does-not-curate"))
        for (url in models.mapNotNull { it.downloadURL }) assertTrue(url.startsWith("https://"))
    }

    @Test fun everyCatalogueEntryPointsAtItsVerifiedUnGatedHuggingFaceFile() {
        val models = OnDeviceModelCatalogue.conversation.associateBy { it.id }
        val e4b = models.getValue("gemma-4-e4b")
        assertEquals("gemma-4-E4B-it.litertlm", e4b.fileName)
        assertEquals(3_659_530_240L, e4b.sizeBytes)
        assertTrue(e4b.supportsAudioInput)
        assertEquals(12, e4b.minDeviceMemoryGb)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
            "28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm?download=true", e4b.downloadURL)

        val e2b = models.getValue("gemma-4-e2b")
        assertEquals("gemma-4-E2B-it.litertlm", e2b.fileName)
        assertEquals(2_588_147_712L, e2b.sizeBytes)
        assertTrue(e2b.supportsAudioInput)
        assertEquals(8, e2b.minDeviceMemoryGb)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true", e2b.downloadURL)

        val qwen = models.getValue("qwen2.5-1.5b")
        assertEquals("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", qwen.fileName)
        assertEquals(1_597_922_856L, qwen.sizeBytes)
        assertFalse(qwen.supportsAudioInput)
        assertEquals(6, qwen.minDeviceMemoryGb)
        assertEquals("https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/" +
            "5e6e7a58d03777898b31330a15d2e0a5a9107de3/" +
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true", qwen.downloadURL)
    }

    @Test fun theZeroDownloadSystemModelIsCataloguedBesideTheDownloadableOnes() {
        val system = OnDeviceModelCatalogue.GEMINI_NANO_AICORE
        assertEquals("aicore-gemini-nano", system.id)
        assertEquals(ModelRuntimeKind.AICORE, system.runtime)
        // System-managed: no file to fetch, no size to announce, and nothing for Mural to delete.
        assertNull(system.fileName)
        assertNull(system.downloadURL)
        assertEquals(0L, system.sizeBytes)
        assertEquals(0, system.minDeviceMemoryGb)
        // No native audio input: voice Talk is refused on the system model instead of pretending.
        assertFalse(system.supportsAudioInput)
        assertFalse(system.recommended)
        assertNotEquals(0, system.title)
        assertNotEquals(0, system.description)
        // The downloadable list stays the three curated files; the picker shows the system model
        // from its own entry, and only when the device probe reports AICore.
        assertEquals(listOf("gemma-4-e4b", "gemma-4-e2b", "qwen2.5-1.5b"),
            OnDeviceModelCatalogue.conversation.map { it.id })
        assertEquals(system, OnDeviceModelCatalogue.byID("aicore-gemini-nano"))
        assertEquals(OnDeviceModelCatalogue.GEMMA_4_E2B, OnDeviceModelCatalogue.byID("gemma-4-e2b"))
        // A stored file name is not a catalogue id, so it never resolves to the system model.
        assertNull(OnDeviceModelCatalogue.byID("gemma-4-E2B-it.litertlm"))
    }

    @Test fun aCatalogueFileNameIsTheOneSafeNameTheModelIsStoredUnder() {
        for (model in OnDeviceModelCatalogue.conversation)
            assertEquals(model.fileName, OnDeviceModelStore.fileName(model.fileName))
    }

    @Test fun aPastedHuggingFaceBlobLinkBecomesOneDirectDownloadURL() {
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
            OnDeviceModelURLs.normalize("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/" +
                "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm"))
        assertEquals("https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro.litertlm?download=true",
            OnDeviceModelURLs.normalize("  https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro.litertlm  "))
        assertEquals("https://example.com/model.litertlm?download=true",
            OnDeviceModelURLs.normalize("https://example.com/model.litertlm?download=true"))
        assertEquals("https://example.com/model.litertlm?token=abc&download=true",
            OnDeviceModelURLs.normalize("https://example.com/model.litertlm?token=abc"))
        // The scheme is forced to HTTPS; a path keeps its case, because a URL path is case sensitive.
        assertEquals("https://example.com/model.LITERTLM?download=true",
            OnDeviceModelURLs.normalize("HTTPS://example.com/model.LITERTLM"))
        // Credentials and a fragment in a pasted link never reach the download request.
        val withCredentials = "https://learner" + ":" + "token" + "@" + "example.com/model.litertlm#part"
        assertEquals("https://example.com/model.litertlm?download=true", OnDeviceModelURLs.normalize(withCredentials))
    }

    @Test fun onlyAnHTTPSDirectLitertlmLinkCanBePasted() {
        for (refused in listOf(null, "", "   ", "model.litertlm", "example.com/model.litertlm",
            "http://example.com/model.litertlm", "ftp://example.com/model.litertlm",
            "content://chat.mural/models/1", "https://example.com/", "https://example.com/model.gguf",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main",
            "https://example.com/model.litertlm.txt", "https://example.com/a b.litertlm",
            "https:///model.litertlm", "https://example.com/.litertlm")) {
            try { OnDeviceModelURLs.normalize(refused); fail("Accepted $refused") }
            catch (_: ModelRuntimeException) { }
        }
    }

    @Test fun aPastedLinkGivesTheSameStoredFileNameAsItsDirectDownload() {
        val url = OnDeviceModelURLs.normalize("https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/blob/" +
            "5e6e7a58d03777898b31330a15d2e0a5a9107de3/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm")
        assertEquals("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", OnDeviceModelURLs.fileName(url))
        assertEquals(OnDeviceModelCatalogue.byID("qwen2.5-1.5b")?.fileName, OnDeviceModelURLs.fileName(url))
        try { OnDeviceModelURLs.fileName("https://example.com/"); fail("Accepted a link with no file name") }
        catch (_: ModelRuntimeException) { }
    }
}
