package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Jamaican Patois (Patwa) as the second Typed-only Module: a language with its own orthography,
 * grammar and teaching focus, never "broken English", with typed conversation, word lookups, meaning
 * subtitles, assessments and flashcards on every Conversation Provider.
 */
class JamaicanPatoisTest {
    private val patwa = AndroidLanguages.get("jam")!!

    /** Every fragment of a Typed-only Module is typed evidence, never spoken evidence. */
    private fun record(text: String, lemma: String, form: String, meaning: String, day: Int = 0,
                       languageID: String = "jam", evidenceLanguage: String = languageID,
                       supported: Boolean = false): SessionRecord {
        val date = 1_780_000_000.0 + day * 86400
        val session = SessionRecord(languageID = languageID, startedAt = date, themeID = "coffee")
        session.append(Fragment(speaker = Speaker.user, text = text, startMS = 100_000, endMS = 103_000,
            receivedAt = date, meaningVisible = supported, typed = true))
        val passage = session.passages.single()
        session.assessments += Assessment(passageID = passage.id, revisionKey = passage.revisionKey,
            outcome = Outcome.success, suggestedLevel = 2, nextGoal = "Ask di price a di market.",
            capability = "Orders at the corner shop", createdAt = date,
            words = listOf(WordProposal(lemma, meaning, form, EvidenceKind.independent, .95,
                passage.fragments.map { it.id }, text, evidenceLanguage)))
        return session
    }

    @Test fun jamaicanPatoisIsSelectableAndThePublishedRegistryKeepsItsContract() {
        assertEquals(LanguageRegistry.all.map { it.id } + listOf("pt-PT", "kok-Latn", "jam", "ja"),
            AndroidLanguages.all.map { it.id })
        // A Typed-only Module ships Android-first: the generated cross-platform registry is untouched.
        assertNull(LanguageRegistry.get("jam"))
        assertEquals(listOf("jam", "Jamaican Patois", "Patwa", "Jamaica", "jam", "Wah gwaan!"),
            listOf(patwa.id, patwa.name, patwa.nativeName, patwa.variety, patwa.locale, patwa.greeting))
        assertEquals("wah gwaan", patwa.greetingWord)
        assertEquals("Jamaican Patois · Jamaica", patwa.settingsTitle)
        assertEquals("A little Jamaican Patois", SessionRecord(languageID = "jam").title)
        assertEquals(6, patwa.teachingFocus.size)
        assertEquals(24, patwa.themes.size)
        assertTrue(patwa.themeOverrides.isNotEmpty())
        assertTrue(patwa.themeOverrides.all { (id, theme) -> id == theme.id })
        assertTrue(patwa.themeOverrides.keys.all { id -> Themes.shared.any { it.id == id } })
        // Jamaican situations, never a translation of another module's.
        val situations = patwa.themes.map { it.situation }
        for (marker in listOf("cookshop", "Coronation Market", "route taxi", "Knutsford", "verandah",
            "Sunday dinner", "reggae", "Jonkonnu", "reason"))
            assertTrue(marker, situations.any { it.contains(marker) })
    }

    @Test fun theGuidanceTeachesPatwaGrammarAndOrthographyAndNeverCallsItBrokenEnglish() {
        val texts = listOf(patwa.id, patwa.name, patwa.nativeName, patwa.variety, patwa.locale,
            patwa.greeting, patwa.greetingWord, patwa.speechGuidance, patwa.writingGuidance,
            patwa.lemmaGuidance, patwa.topicPlaceholder, patwa.lookupUnavailableReply) +
            patwa.teachingFocus +
            patwa.themeOverrides.values.flatMap { listOf(it.title, it.subtitle, it.symbol, it.category, it.situation) }
        for (text in texts) assertTrue(text, text.isNotBlank())
        assertEquals("Wah gwaan!", patwa.greeting)
        // Patwa grammar reaches the learner: tense-aspect markers, pronouns, plural dem, copula and focus.
        for (marker in listOf("mi a nyam", "im did tell mi", "wen", "ben", "wi a-go come", "ago",
            "mi nuh know", "seh", "fi for", "wan for", "di pikni dem", "a mi dat", "mi deh ya",
            "unu", "shi", "ihn"))
            assertTrue(marker, patwa.writingGuidance.contains(marker))
        // Both orthographies are accepted: the Cassidy-JLU phonemic spelling and popular Jamaican spelling.
        for (marker in listOf("Cassidy-JLU", "popular Jamaican", "deh yah", "wa gwaan"))
            assertTrue(patwa.writingGuidance, patwa.writingGuidance.contains(marker))
        for (marker in listOf("no speech recognition", "no text-to-speech", "typed", "Patwa", "cadence"))
            assertTrue(patwa.speechGuidance, patwa.speechGuidance.contains(marker))
        assertTrue(patwa.topicPlaceholder, patwa.topicPlaceholder.contains("yaad"))
        assertTrue(patwa.lookupUnavailableReply, patwa.lookupUnavailableReply.contains("right now"))
        assertTrue(patwa.lookupUnavailableReply, patwa.lookupUnavailableReply.contains("reason"))
        // Dignity and the continuum: every guidance names Standard Jamaican English and rejects the
        // "broken English" reading, so code-switching is never an error anywhere it is interpolated.
        for (guidance in listOf(patwa.speechGuidance, patwa.writingGuidance, patwa.lemmaGuidance)) {
            for (marker in listOf("English", "Standard Jamaican English", "never", "not broken English"))
                assertTrue(guidance, guidance.contains(marker))
        }
        assertTrue(patwa.lemmaGuidance, patwa.lemmaGuidance.contains("code-switching"))
        assertTrue(patwa.lemmaGuidance, patwa.lemmaGuidance.contains("tag them jam"))
    }

    @Test fun theTypedOnlyModuleMechanismNamesPatwaAndNothingElseDecidesItPerModule() {
        assertTrue(patwa.isTypedOnly)
        assertEquals(setOf("kok-Latn", "jam"), AndroidLanguages.typedOnlyIDs)
        // A Typed-only Module must be a Language Module this platform actually offers.
        assertTrue(AndroidLanguages.all.map { it.id }.containsAll(AndroidLanguages.typedOnlyIDs))
        for (module in AndroidLanguages.all.filterNot { it.isTypedOnly }) {
            assertFalse(module.id, module.isTypedOnly)
            assertEquals(module.id, module.id != "tl", TeachingPolicy.supportsSpeechLanguageDetection(module))
        }
        for (module in AndroidLanguages.all)
            assertEquals(module.id, !module.isTypedOnly && module.id != "tl",
                TeachingPolicy.supportsSpeechLanguageDetection(module))
        // Patwa reads as English to a detector, so a Typed-only Module must never be redirected.
        for ((detected, confidence) in listOf("en" to 0.99, "en-US" to 0.98, "jam" to 0.95, "und" to 0.99,
            "es" to 0.91, "pt" to 0.93))
            assertFalse("$detected", TeachingPolicy.shouldRedirectSpeech(patwa, detected, confidence))
        assertTrue(TeachingPolicy.shouldRedirectSpeech(AndroidLanguages.get("nb")!!, "es", 0.95))
    }

    @Test fun orthographyAndCodeSwitchingToleranceReachEveryPromptThatDrivesTheTypedConversation() {
        // `assessment()` interpolates the module's lemmaGuidance and speechGuidance, so this rule
        // reaches every judgment without changing that cross-platform prompt template.
        val learner = LearningEngine.project(emptyList(), "jam")
        val typedConversation = TeachingPolicy.voice(patwa, learner, patwa.themes.first { it.id == "coffee" }, "", "English")
        val assessment = TeachingPolicy.assessment(patwa)
        val typedReply = TeachingPolicy.typedReply(patwa)
        val lookup = TeachingPolicy.lookup(patwa, "English")
        assertTrue(typedConversation.contains(patwa.speechGuidance) && typedConversation.contains(patwa.writingGuidance))
        assertTrue(assessment, assessment.contains(patwa.lemmaGuidance))
        assertTrue(assessment, assessment.contains(patwa.speechGuidance))
        assertTrue(assessment, assessment.contains("Use language jam for target-language evidence"))
        assertTrue(lookup, lookup.contains(patwa.lemmaGuidance))
        for (prompt in listOf(typedConversation, typedReply, lookup, assessment)) {
            assertTrue(prompt, prompt.contains("Cassidy-JLU"))
            assertTrue(prompt, prompt.contains("Standard Jamaican English"))
            assertTrue(prompt, prompt.contains("not broken English"))
        }
        for (prompt in listOf(typedConversation, assessment, typedReply, lookup,
            TeachingPolicy.currentTopic(patwa), TeachingPolicy.greeting(patwa), TeachingPolicy.checkIn(patwa),
            TeachingPolicy.help(patwa), TeachingPolicy.redirect(patwa), TeachingPolicy.translation(patwa, "English"),
            TeachingPolicy.delegation(patwa))) {
            assertTrue(prompt, prompt.contains(patwa.name))
            assertFalse(prompt, prompt.contains("Norwegian"))
        }
        // The typed conversation is honest about having no voice, in every prompt that drives it.
        for (prompt in listOf(typedConversation, typedReply)) {
            assertTrue(prompt, prompt.contains("typed"))
            assertTrue(prompt, prompt.contains("never ask the learner to speak"))
        }
        assertTrue(typedConversation, typedConversation.contains("no speech recognition"))
        assertTrue(typedConversation, typedConversation.contains("no text-to-speech"))
        assertTrue(TeachingPolicy.translation(patwa, "English").contains("into English"))
        assertTrue(TeachingPolicy.currentTopic(patwa).contains(patwa.writingGuidance))
        assertTrue(TeachingPolicy.greeting(patwa).contains(patwa.greeting))
    }

    @Test fun captionsLinkPatwaWordsIncludingApostrophesHyphensAndEnglishBorrowings() {
        for ((text, expected) in listOf(
            "Wah gwaan!\n" to listOf("Wah", "gwaan"),
            "Mi a-go a di Coronation Market fi buy ackee an saltfish." to
                listOf("Mi", "a-go", "a", "di", "Coronation", "Market", "fi", "buy", "ackee", "an", "saltfish"),
            "'Im did tell mi seh 'im a-go come early." to
                listOf("Im", "did", "tell", "mi", "seh", "im", "a-go", "come", "early"),
            "Mi deh yah: wi a nyam inna di yaad, an di pikni dem a play pon di verandah." to
                listOf("Mi", "deh", "yah", "wi", "a", "nyam", "inna", "di", "yaad", "an", "di", "pikni", "dem",
                    "a", "play", "pon", "di", "verandah"),
        )) {
            val segments = CaptionWords.segments(text, "jam", null)
            assertEquals(text, segments.joinToString("") { it.text })
            assertEquals(expected, segments.mapNotNull { it.lookup })
        }
        assertEquals(patwa.greeting, CaptionWords.segments(patwa.greeting, "jam", null)
            .joinToString("") { it.text })
        assertEquals(listOf("Wah", "gwaan"), CaptionWords.segments(patwa.greeting, "jam", null).mapNotNull { it.lookup })
    }

    @Test fun typedEvidenceBuildsPatwaVocabularyAndFlashcardsInIsolation() {
        val sessions = mutableListOf(
            record("Mi want wan cup a Blue Mountain coffee.", "coffee", "coffee", "coffee"),
            record("Wi did buy ackee an saltfish a Coronation Market.", "ackee", "ackee", "ackee fruit", day = 2),
            record("'Im a-go tek di route taxi cross di yaad.", "route taxi", "route taxi", "shared taxi", day = 4))
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(
            Archive(sessions = sessions, preferences = Preferences(learningLanguageID = "jam", meaningLanguage = "English"))))
        assertEquals("jam", restored.preferences.learningLanguageID)
        assertEquals("A little Jamaican Patois", restored.sessions.first().title)
        assertEquals("Mi want wan cup a Blue Mountain coffee.", restored.sessions.first().passages.single().text)
        assertTrue(restored.sessions.flatMap { it.fragments }.all { it.typed })
        val words = LearningEngine.project(restored.sessions, "jam").words
        assertEquals(setOf("coffee", "ackee", "route taxi"), words.map { it.lemma }.toSet())
        // Typed evidence stays assisted: Mural read it, it never heard the learner.
        assertEquals(0, words.sumOf { it.independentCount })
        assertTrue(words.all { it.bars == 0 })
        val deck = FlashcardDeck(words, "jam")
        assertEquals(setOf("coffee", "ackee", "route taxi"), deck.words.map { it.lemma }.toSet())
        assertEquals(0, deck.destination(0, -1))
        assertEquals(1, deck.destination(0, 1))
        // Vocabulary stays isolated from every other Language Module, including the other Typed-only one.
        for (other in listOf("en", "kok-Latn", "pt-PT"))
            assertEquals(other, 0, FlashcardDeck(words, other).words.size)
        for (other in AndroidLanguages.all.filter { it.id != "jam" })
            assertTrue(other.id, LearningEngine.project(restored.sessions, other.id).words.isEmpty())
        // Evidence another language produced never joins this module's vocabulary.
        val english = record("I want a cup of coffee.", "coffee", "coffee", "coffee", evidenceLanguage = "en")
        assertTrue(LearningEngine.validate(english.assessments.single(), english)!!.words.isEmpty())
        // A natural English borrowing written inside Patwa is jam evidence: code-switching builds vocabulary.
        val borrowed = record("Mi did call mi doctor pon di phone.", "phone", "phone", "telephone")
        assertEquals(setOf("phone"), LearningEngine.project(listOf(borrowed), "jam").words.map { it.lemma }.toSet())
        // A visible English meaning subtitle makes the same typed evidence assisted, as everywhere.
        assertEquals(0, LearningEngine.project(listOf(record("Mi want wan cup a coffee.", "coffee", "coffee",
            "coffee", supported = true)), "jam").words.single().independentCount)
    }
}
