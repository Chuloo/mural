package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

/** Portuguese (Portugal) as its own Language Module, with Portuguese (Brazil) untouched. */
class PortuguesePortugalTest {
    private val portugal = AndroidLanguages.get("pt-PT")!!
    private val brazil = AndroidLanguages.get("pt")!!

    private fun record(text: String, lemma: String, form: String, meaning: String, day: Int = 0,
                       languageID: String = "pt-PT", evidenceLanguage: String = languageID,
                       supported: Boolean = false): SessionRecord {
        val date = 1_780_000_000.0 + day * 86400
        val session = SessionRecord(languageID = languageID, startedAt = date, themeID = "coffee")
        session.append(Fragment(speaker = Speaker.user, text = text, startMS = 100_000, endMS = 103_000,
            receivedAt = date, meaningVisible = supported))
        val passage = session.passages.single()
        session.assessments += Assessment(passageID = passage.id, revisionKey = passage.revisionKey,
            outcome = Outcome.success, suggestedLevel = 2, nextGoal = "Pergunta o preço na pastelaria.",
            capability = "Orders a drink", createdAt = date,
            words = listOf(WordProposal(lemma, meaning, form, EvidenceKind.independent, .95,
                passage.fragments.map { it.id }, text, evidenceLanguage)))
        return session
    }

    @Test fun portuguesePortugalIsSelectableAlongsideTheUnchangedBrazilianModule() {
        assertEquals(LanguageRegistry.all.map { it.id } + listOf("pt-PT", "kok-Latn", "jam"), AndroidLanguages.all.map { it.id })
        assertEquals(LanguageRegistry.all.map { it.id },
            listOf("nb", "es", "en", "fr", "de", "it", "pt", "zh", "sr", "el", "tl", "nl", "ru"))
        // The generated cross-platform registry keeps its published contract.
        assertNull(LanguageRegistry.get("pt-PT"))
        assertNull(AndroidLanguages.get("fil"))
        assertEquals(listOf("pt-BR", "Brazil", "Olá!", "Português"),
            listOf(brazil.locale, brazil.variety, brazil.greeting, brazil.nativeName))
        assertTrue(brazil.writingGuidance.contains("a gente"))
        assertEquals(listOf("pt-PT", "Portuguese", "Português", "Portugal", "pt-PT", "Olá!", "olá"),
            listOf(portugal.id, portugal.name, portugal.nativeName, portugal.variety, portugal.locale,
                portugal.greeting, portugal.greetingWord))
        assertEquals("Portuguese · Portugal", portugal.settingsTitle)
        assertEquals("A little Portuguese", SessionRecord(languageID = "pt-PT").title)
        assertEquals("Olá!", MeaningLanguages.greeting(portugal.name))
    }

    @Test fun europeanGuidanceThemesAndGreetingDescribePortugal() {
        assertEquals(6, portugal.teachingFocus.size)
        for (text in portugal.teachingFocus + listOf(portugal.speechGuidance, portugal.writingGuidance,
            portugal.lemmaGuidance, portugal.topicPlaceholder, portugal.lookupUnavailableReply))
            assertTrue(text, text.isNotBlank())
        for (marker in listOf("European Portuguese", "vowel", "diz-me", "tu", "você"))
            assertTrue(portugal.speechGuidance, portugal.speechGuidance.contains(marker))
        for (word in listOf("autocarro", "comboio", "pequeno-almoço", "telemóvel", "casa de banho", "estou a fazer"))
            assertTrue(portugal.writingGuidance, portugal.writingGuidance.contains(word))
        for (form in listOf("o comboio", "a chávena", "ir"))
            assertTrue(portugal.lemmaGuidance, portugal.lemmaGuidance.contains(form))
        assertFalse(portugal.writingGuidance, portugal.writingGuidance.contains("a gente"))
        assertEquals(24, portugal.themes.size)
        assertTrue(portugal.themeOverrides.isNotEmpty())
        assertTrue(portugal.themeOverrides.all { (id, theme) -> id == theme.id })
        assertTrue(portugal.themeOverrides.keys.all { id -> Themes.shared.any { it.id == id } })
        val situations = portugal.themes.map { it.situation }
        assertTrue(situations.any { it.contains("pastelaria") && it.contains("pastel de nata") })
        assertTrue(situations.any { it.contains("bica") })
        assertTrue(situations.any { it.contains("comboio") && it.contains("elétrico") })
        assertTrue(situations.any { it.contains("miradouro") })
        assertTrue(situations.any { it.contains("mercearia") })
        assertTrue(situations.any { it.contains("tasca") })
        assertTrue(situations.any { it.contains("Santos Populares") })
        // Localized European situations, never a translation of the Brazilian module's.
        for (id in portugal.themeOverrides.keys.intersect(brazil.themeOverrides.keys))
            assertNotEquals(id, brazil.themeOverrides.getValue(id).situation, portugal.themeOverrides.getValue(id).situation)
    }

    @Test fun captionsPreserveEuropeanPortugueseWordsAndExactSourceText() {
        for ((text, expected) in listOf(
            "Diz-me se queres uma bica e um pastel de nata.\n" to
                listOf("Diz-me", "se", "queres", "uma", "bica", "e", "um", "pastel", "de", "nata"),
            "Vou de elétrico ao miradouro depois do pequeno-almoço." to
                listOf("Vou", "de", "elétrico", "ao", "miradouro", "depois", "do", "pequeno-almoço"),
            "Não me digas; a casa de banho é ao pé da chávena?" to
                listOf("Não", "me", "digas", "a", "casa", "de", "banho", "é", "ao", "pé", "da", "chávena"),
            "Olá!" to listOf("Olá"),
            "cafe\u0301" to listOf("cafe\u0301"),
        )) {
            val segments = CaptionWords.segments(text, "pt-PT", null)
            assertEquals(text, segments.joinToString("") { it.text })
            assertEquals(expected, segments.mapNotNull { it.lookup })
        }
        assertEquals(portugal.greeting, CaptionWords.segments(portugal.greeting, "pt-PT", null)
            .joinToString("") { it.text })
    }

    @Test fun detectedPortugueseDoesNotRedirectAEuropeanConversation() {
        for ((language, detected, confidence) in listOf(
            Triple("pt-PT", "pt", 0.97), Triple("pt-PT", "pt-PT", 0.95), Triple("pt-PT", "PT_pt", 0.99),
            Triple("pt-PT", "pt-BR", 0.96), Triple("pt-PT", "es", 0.60), Triple("pt-PT", "und", 0.99),
            Triple("pt", "pt", 0.97), Triple("pt", "pt-PT", 0.95)))
            assertFalse("$language / $detected",
                TeachingPolicy.shouldRedirectSpeech(AndroidLanguages.get(language)!!, detected, confidence))
        for ((language, detected) in listOf("pt-PT" to "es", "pt-PT" to "en", "pt" to "es"))
            assertTrue("$language / $detected",
                TeachingPolicy.shouldRedirectSpeech(AndroidLanguages.get(language)!!, detected, 0.95))
        assertTrue(TeachingPolicy.detectedLanguageMatches(portugal, "pt"))
        assertFalse(TeachingPolicy.detectedLanguageMatches(portugal, "es"))
    }

    @Test fun archiveRoundTripKeepsEuropeanSessionsAndIsolatesVocabularyFromBrazil() {
        val european = mutableListOf(
            record("Estou a fazer um passeio de elétrico.", "o elétrico", "elétrico", "tram"),
            record("Diz-me onde fica a casa de banho.", "a casa de banho", "casa de banho", "bathroom", day = 2))
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(
            Archive(sessions = european, preferences = Preferences(learningLanguageID = "pt-PT", meaningLanguage = "English"))))
        assertEquals("pt-PT", restored.preferences.learningLanguageID)
        assertEquals("A little Portuguese", restored.sessions.first().title)
        assertEquals("Estou a fazer um passeio de elétrico.", restored.sessions.first().passages.single().text)
        assertEquals(setOf("o elétrico", "a casa de banho"),
            LearningEngine.project(restored.sessions, "pt-PT").words.map { it.lemma }.toSet())
        assertEquals(1, LearningEngine.project(restored.sessions, "pt-PT").words.single { it.lemma == "o elétrico" }.independentCount)
        for (other in AndroidLanguages.all.filter { it.id != "pt-PT" })
            assertTrue(other.id, LearningEngine.project(restored.sessions, other.id).words.isEmpty())
        // Evidence tagged with the other Portuguese variety never joins this module's vocabulary.
        val mixed = record("Quero um café.", "o café", "café", "coffee", evidenceLanguage = "pt")
        assertTrue(LearningEngine.validate(mixed.assessments.single(), mixed)!!.words.isEmpty())
        val brazilian = record("Estou fazendo um passeio de ônibus.", "o ônibus", "ônibus", "bus", languageID = "pt")
        val both = ArchiveCodec.decode(ArchiveCodec.encode(Archive(sessions = mutableListOf(brazilian, european[0]))))
        assertEquals(setOf("o ônibus"), LearningEngine.project(both.sessions, "pt").words.map { it.lemma }.toSet())
        assertEquals(setOf("o elétrico"), LearningEngine.project(both.sessions, "pt-PT").words.map { it.lemma }.toSet())
        // A supported meaning makes the European evidence assisted, exactly as for every other module.
        assertEquals(0, LearningEngine.project(listOf(record("Vou de autocarro.", "o autocarro", "autocarro",
            "bus", supported = true)), "pt-PT").words.single().independentCount)
    }

    @Test fun teachingPromptsUseEuropeanPortugueseWithoutBrazilianWording() {
        val learner = LearningEngine.project(emptyList(), "pt-PT")
        val voice = TeachingPolicy.voice(portugal, learner, portugal.themes.first(), "", "English")
        for (prompt in listOf(voice, TeachingPolicy.assessment(portugal), TeachingPolicy.greeting(portugal),
            TeachingPolicy.checkIn(portugal), TeachingPolicy.help(portugal), TeachingPolicy.redirect(portugal),
            TeachingPolicy.translation(portugal, "English"), TeachingPolicy.delegation(portugal),
            TeachingPolicy.typedReply(portugal), TeachingPolicy.lookup(portugal, "English"),
            TeachingPolicy.currentTopic(portugal))) {
            assertTrue(prompt, prompt.contains(portugal.name))
            assertFalse(prompt, prompt.contains("Norwegian"))
        }
        assertTrue(voice.contains(portugal.speechGuidance) && voice.contains(portugal.writingGuidance))
        assertTrue(voice, voice.contains("português europeu"))
        assertTrue(voice, voice.contains("Corrige um erro"))
        assertTrue(voice, voice.contains("aplicação"))
        assertFalse(voice, voice.contains("Você é Mural"))
        assertFalse(voice, voice.contains("Corrija um erro"))
        assertFalse(voice, voice.contains("aplicativo"))
        assertTrue(TeachingPolicy.assessment(portugal).contains("Use language pt-PT for target-language evidence"))
        assertTrue(TeachingPolicy.lookup(portugal, "English").contains(portugal.lemmaGuidance))
        assertTrue(TeachingPolicy.currentTopic(portugal).contains(portugal.writingGuidance))
        assertTrue(TeachingPolicy.greeting(portugal).contains(portugal.greeting))
        // The Brazilian module keeps its own wording.
        assertTrue(TeachingPolicy.voice(brazil, LearningEngine.project(emptyList(), "pt"), null, "", "English")
            .contains("Você é Mural"))
        val openings = portugal.themes.map { TeachingPolicy.greeting(portugal, it) }
        assertEquals(24, openings.toSet().size)
        assertTrue(openings.all { it.length <= 1000 })
    }
}
