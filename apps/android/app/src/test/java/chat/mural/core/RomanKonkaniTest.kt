package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Roman Konkani as a Typed-only Module: written in the Roman script only, with typed conversation,
 * lookups, meaning subtitles, assessments and flashcards on every Conversation Provider.
 */
class RomanKonkaniTest {
    private val konkani = AndroidLanguages.get("kok-Latn")!!

    /** Every fragment of a Typed-only Module is typed evidence, never spoken evidence. */
    private fun record(text: String, lemma: String, form: String, meaning: String, day: Int = 0,
                       languageID: String = "kok-Latn", evidenceLanguage: String = languageID,
                       supported: Boolean = false): SessionRecord {
        val date = 1_780_000_000.0 + day * 86400
        val session = SessionRecord(languageID = languageID, startedAt = date, themeID = "coffee")
        session.append(Fragment(speaker = Speaker.user, text = text, startMS = 100_000, endMS = 103_000,
            receivedAt = date, meaningVisible = supported, typed = true))
        val passage = session.passages.single()
        session.assessments += Assessment(passageID = passage.id, revisionKey = passage.revisionKey,
            outcome = Outcome.success, suggestedLevel = 2, nextGoal = "Vichar bazarachea molak.",
            capability = "Orders a drink", createdAt = date,
            words = listOf(WordProposal(lemma, meaning, form, EvidenceKind.independent, .95,
                passage.fragments.map { it.id }, text, evidenceLanguage)))
        return session
    }

    @Test fun romanKonkaniIsSelectableAndThePublishedRegistryKeepsItsContract() {
        assertEquals(LanguageRegistry.all.map { it.id } + listOf("pt-PT", "kok-Latn"),
            AndroidLanguages.all.map { it.id })
        // A Typed-only Module ships Android-first: the generated cross-platform registry is untouched.
        assertNull(LanguageRegistry.get("kok-Latn"))
        assertEquals(listOf("kok-Latn", "Konkani", "Konknni", "Roman script", "kok-Latn", "Dev borem korum!"),
            listOf(konkani.id, konkani.name, konkani.nativeName, konkani.variety, konkani.locale, konkani.greeting))
        assertEquals("Konkani · Roman script", konkani.settingsTitle)
        assertEquals("A little Konkani", SessionRecord(languageID = "kok-Latn").title)
        assertEquals(6, konkani.teachingFocus.size)
        assertEquals(24, konkani.themes.size)
        assertTrue(konkani.themeOverrides.isNotEmpty())
        assertTrue(konkani.themeOverrides.all { (id, theme) -> id == theme.id })
        assertTrue(konkani.themeOverrides.keys.all { id -> Themes.shared.any { it.id == id } })
        assertTrue(konkani.themes.map { it.situation }.any { it.contains("bazar") })
        assertTrue(konkani.themes.map { it.situation }.any { it.contains("pavs") })
        assertTrue(konkani.themes.map { it.situation }.any { it.contains("zatra") })
    }

    @Test fun everyCharacterOfTheModuleIsRomanScriptAndTheGuidanceIsComplete() {
        val texts = listOf(konkani.id, konkani.name, konkani.nativeName, konkani.variety, konkani.locale,
            konkani.greeting, konkani.greetingWord, konkani.speechGuidance, konkani.writingGuidance,
            konkani.lemmaGuidance, konkani.topicPlaceholder, konkani.lookupUnavailableReply) +
            konkani.teachingFocus +
            konkani.themeOverrides.values.flatMap { listOf(it.title, it.subtitle, it.symbol, it.category, it.situation) }
        // Devanagari and Kannada are the other Konkani scripts; Romi Konknni uses neither.
        val indic = Regex("[\u0900-\u097F\u0C80-\u0CFF]")
        for (text in texts) {
            assertTrue(text, text.isNotBlank())
            assertNull(text, indic.find(text)?.value)
        }
        assertEquals("Dev borem korum!", konkani.greeting)
        assertTrue(konkani.lookupUnavailableReply, konkani.lookupUnavailableReply.contains("attam"))
        for (marker in listOf("Roman script", "Romi Konknni", "Devanagari", "Kannada"))
            assertTrue(konkani.writingGuidance, konkani.writingGuidance.contains(marker))
        for (marker in listOf("no speech recognition", "no text-to-speech", "typed", "Romi Konknni"))
            assertTrue(konkani.speechGuidance, konkani.speechGuidance.contains(marker))
    }

    @Test fun theTypedOnlyModuleMechanismNamesKonkaniAndLeavesEveryOtherModuleWithVoice() {
        assertTrue(konkani.isTypedOnly)
        assertTrue("kok-Latn" in AndroidLanguages.typedOnlyIDs)
        // A Typed-only Module must be a Language Module this platform actually offers.
        assertTrue(AndroidLanguages.all.map { it.id }.containsAll(AndroidLanguages.typedOnlyIDs))
        for (module in AndroidLanguages.all.filter { it.id != "kok-Latn" })
            assertFalse(module.id, module.isTypedOnly)
    }

    @Test fun noTypedOnlyModuleEverUsesSpeechLanguageDetectionOrRedirectsAConversation() {
        for (module in AndroidLanguages.all) {
            val expected = !module.isTypedOnly && module.id != "tl"
            assertEquals(module.id, expected, TeachingPolicy.supportsSpeechLanguageDetection(module))
        }
        // Roman Konkani looks like Marathi or Hindi to a detector, so it must never be redirected.
        for ((detected, confidence) in listOf("en" to 0.99, "mr" to 0.97, "hi" to 0.99, "kok" to 0.95,
            "und" to 0.99, "es" to 0.91))
            assertFalse("$detected", TeachingPolicy.shouldRedirectSpeech(konkani, detected, confidence))
        assertTrue(TeachingPolicy.shouldRedirectSpeech(AndroidLanguages.get("nb")!!, "es", 0.95))
    }

    @Test fun guidanceTreatsCodeSwitchingWithEnglishHindiAndMarathiAsValidKonkani() {
        // `assessment()` interpolates the module's lemmaGuidance, so this rule reaches every judgment
        // without changing that cross-platform prompt template.
        val assessment = TeachingPolicy.assessment(konkani)
        assertTrue(assessment, assessment.contains(konkani.lemmaGuidance))
        for (source in listOf(konkani.writingGuidance, konkani.lemmaGuidance, konkani.speechGuidance)) {
            for (marker in listOf("English", "Hindi", "Marathi", "never as"))
                assertTrue(source, source.contains(marker))
        }
        assertTrue(assessment, assessment.contains("Use language kok-Latn for target-language evidence"))
        val learner = LearningEngine.project(emptyList(), "kok-Latn")
        val typedConversation = TeachingPolicy.voice(konkani, learner, konkani.themes.first { it.id == "coffee" }, "", "English")
        assertTrue(typedConversation.contains(konkani.speechGuidance) && typedConversation.contains(konkani.writingGuidance))
        for (prompt in listOf(typedConversation, TeachingPolicy.typedReply(konkani),
            TeachingPolicy.lookup(konkani, "English"), TeachingPolicy.currentTopic(konkani),
            TeachingPolicy.greeting(konkani), TeachingPolicy.checkIn(konkani), TeachingPolicy.help(konkani),
            TeachingPolicy.redirect(konkani), TeachingPolicy.translation(konkani, "English"),
            TeachingPolicy.delegation(konkani))) {
            assertTrue(prompt, prompt.contains(konkani.name))
            assertFalse(prompt, prompt.contains("Norwegian"))
        }
        // The typed conversation is honest about having no voice, in every prompt that drives it.
        for (prompt in listOf(typedConversation, TeachingPolicy.typedReply(konkani))) {
            assertTrue(prompt, prompt.contains("Roman script"))
            assertTrue(prompt, prompt.contains("Devanagari"))
            assertTrue(prompt, prompt.contains("typed"))
        }
        assertTrue(TeachingPolicy.lookup(konkani, "English").contains(konkani.lemmaGuidance))
        assertTrue(TeachingPolicy.translation(konkani, "English").contains("into English"))
        assertTrue(TeachingPolicy.greeting(konkani).contains(konkani.greeting))
    }

    @Test fun captionsLinkRomanKonkaniWordsIncludingTheirBorrowings() {
        for ((text, expected) in listOf(
            "Dev borem korum!\n" to listOf("Dev", "borem", "korum"),
            "Mhaka ek kafe zai." to listOf("Mhaka", "ek", "kafe", "zai"),
            "Amchea ghorant jevon taiyar asa." to listOf("Amchea", "ghorant", "jevon", "taiyar", "asa"),
            "Hanv office-ant kam kortam." to listOf("Hanv", "office-ant", "kam", "kortam"),
        )) {
            val segments = CaptionWords.segments(text, "kok-Latn", null)
            assertEquals(text, segments.joinToString("") { it.text })
            assertEquals(expected, segments.mapNotNull { it.lookup })
        }
        assertEquals(konkani.greeting, CaptionWords.segments(konkani.greeting, "kok-Latn", null)
            .joinToString("") { it.text })
    }

    @Test fun typedEvidenceBuildsKonkaniVocabularyAndFlashcardsInIsolation() {
        val sessions = mutableListOf(
            record("Mhaka ek kafe zai.", "kafe", "kafe", "coffee"),
            record("Amchea ghorant jevon taiyar asa.", "jevon", "jevon", "food", day = 2),
            record("Hanv office-ant kam kortam.", "kam", "kam", "work", day = 4))
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(
            Archive(sessions = sessions, preferences = Preferences(learningLanguageID = "kok-Latn", meaningLanguage = "English"))))
        assertEquals("kok-Latn", restored.preferences.learningLanguageID)
        assertEquals("A little Konkani", restored.sessions.first().title)
        assertEquals("Mhaka ek kafe zai.", restored.sessions.first().passages.single().text)
        assertTrue(restored.sessions.flatMap { it.fragments }.all { it.typed })
        val words = LearningEngine.project(restored.sessions, "kok-Latn").words
        assertEquals(setOf("kafe", "jevon", "kam"), words.map { it.lemma }.toSet())
        // Typed evidence stays assisted: Mural read it, it never heard the learner.
        assertEquals(0, words.sumOf { it.independentCount })
        assertTrue(words.all { it.bars == 0 })
        val deck = FlashcardDeck(words, "kok-Latn")
        assertEquals(setOf("kafe", "jevon", "kam"), deck.words.map { it.lemma }.toSet())
        assertEquals(0, deck.destination(0, -1))
        assertEquals(0, FlashcardDeck(words, "pt-PT").words.size)
        for (other in AndroidLanguages.all.filter { it.id != "kok-Latn" })
            assertTrue(other.id, LearningEngine.project(restored.sessions, other.id).words.isEmpty())
        // Evidence another language produced never joins this module's vocabulary.
        val marathi = record("Mala ek chahi zai.", "chaha", "chahi", "tea", evidenceLanguage = "mr")
        assertTrue(LearningEngine.validate(marathi.assessments.single(), marathi)!!.words.isEmpty())
        // A visible English meaning subtitle makes the same typed evidence assisted, as everywhere.
        assertEquals(0, LearningEngine.project(listOf(record("Mhaka ek kafe zai.", "kafe", "kafe", "coffee",
            supported = true)), "kok-Latn").words.single().independentCount)
    }
}
