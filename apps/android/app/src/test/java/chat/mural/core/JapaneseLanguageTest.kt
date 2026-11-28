package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

/** Japanese as a full voice and typed Language Module, with spaceless captions still tappable word by word. */
class JapaneseLanguageTest {
    private val japanese = AndroidLanguages.get("ja")!!
    private val mandarin = AndroidLanguages.get("zh")!!

    private fun record(text: String, lemma: String, form: String, meaning: String, day: Int = 0,
                       languageID: String = "ja", evidenceLanguage: String = languageID,
                       supported: Boolean = false): SessionRecord {
        val date = 1_780_000_000.0 + day * 86400
        val session = SessionRecord(languageID = languageID, startedAt = date, themeID = "coffee")
        session.append(Fragment(speaker = Speaker.user, text = text, startMS = 100_000, endMS = 103_000,
            receivedAt = date, meaningVisible = supported))
        val passage = session.passages.single()
        session.assessments += Assessment(passageID = passage.id, revisionKey = passage.revisionKey,
            outcome = Outcome.success, suggestedLevel = 2, nextGoal = "喫茶店で飲み物を注文します。",
            capability = "Orders a drink", createdAt = date,
            words = listOf(WordProposal(lemma, meaning, form, EvidenceKind.independent, .95,
                passage.fragments.map { it.id }, text, evidenceLanguage)))
        return session
    }

    @Test fun japaneseIsSelectableAsAFullVoiceLanguageModuleAndThePublishedRegistryKeepsItsContract() {
        assertEquals(LanguageRegistry.all.map { it.id } + listOf("pt-PT", "kok-Latn", "jam", "ja"),
            AndroidLanguages.all.map { it.id })
        // A Language Module that ships Android-first: the generated cross-platform registry is untouched.
        assertNull(LanguageRegistry.get("ja"))
        assertEquals(listOf("ja", "Japanese", "日本語", "Japan", "ja-JP", "こんにちは！", "こんにちは"),
            listOf(japanese.id, japanese.name, japanese.nativeName, japanese.variety, japanese.locale,
                japanese.greeting, japanese.greetingWord))
        assertEquals("Japanese · Japan", japanese.settingsTitle)
        assertEquals("A little Japanese", SessionRecord(languageID = "ja").title)
        // Japanese has voice on the phone and on a Personal Key, so it is never a Typed-only Module.
        assertFalse(japanese.isTypedOnly)
        assertFalse("ja" in AndroidLanguages.typedOnlyIDs)
        assertTrue(TeachingPolicy.supportsSpeechLanguageDetection(japanese))
        assertEquals(6, japanese.teachingFocus.size)
        assertEquals(24, japanese.themes.size)
    }

    @Test fun theGuidanceTeachesNaturalSpokenAndWrittenJapaneseWithDictionaryFormLemmas() {
        val texts = listOf(japanese.id, japanese.name, japanese.nativeName, japanese.variety, japanese.locale,
            japanese.greeting, japanese.greetingWord, japanese.speechGuidance, japanese.writingGuidance,
            japanese.lemmaGuidance, japanese.topicPlaceholder, japanese.lookupUnavailableReply) +
            japanese.teachingFocus +
            japanese.themeOverrides.values.flatMap { listOf(it.title, it.subtitle, it.symbol, it.category, it.situation) }
        for (text in texts) assertTrue(text, text.isNotBlank())
        // Standard Japanese pronunciation: mora timing, long vowels, the geminate っ and pitch accent.
        for (marker in listOf("mora", "long vowel", "っ", "pitch accent", "standard Japanese"))
            assertTrue(marker, japanese.speechGuidance.contains(marker))
        // Polite です・ます by default, adapting to the learner's casual plain form without calling it an error.
        for (marker in listOf("です・ます", "plain form", "regional", "never"))
            assertTrue(marker, japanese.speechGuidance.contains(marker))
        // Kanji, Hiragana and Katakana with no spaces, standard punctuation, and no romaji or furigana.
        for (marker in listOf("Kanji", "Hiragana", "Katakana", "no spaces", "。", "、", "？", "！", "「」"))
            assertTrue(marker, japanese.writingGuidance.contains(marker))
        for (marker in listOf("romaji", "furigana"))
            assertTrue(marker, japanese.writingGuidance.contains(marker))
        assertTrue(japanese.lemmaGuidance, japanese.lemmaGuidance.contains("no romaji"))
        for (marker in listOf("食べる", "行く", "静か", "電車", "dictionary", "form", "quote", "気をつける", "お願いします"))
            assertTrue(marker, japanese.lemmaGuidance.contains(marker))
        // The teaching stages run from greetings to nuanced idiomatic conversation.
        for (marker in listOf("はじめまして", "をください", "particles", "ます", "て form", "と思う", "から",
            "たら", "あげる", "keigo"))
            assertTrue(marker, japanese.teachingFocus.any { it.contains(marker) })
        assertEquals("今はそれを確認できません。よければ、この話題について一般的に話しましょう。", japanese.lookupUnavailableReply)
    }

    @Test fun theThemeOverridesAreEverydayJapaneseSituations() {
        assertTrue(japanese.themeOverrides.size >= 7)
        assertTrue(japanese.themeOverrides.all { (id, theme) -> id == theme.id })
        assertTrue(japanese.themeOverrides.keys.all { id -> Themes.shared.any { it.id == id } })
        // The shared symbol and colour keep the catalogue appearance; only the situation is localized.
        for ((id, theme) in japanese.themeOverrides) {
            val shared = Themes.shared.first { it.id == id }
            assertEquals(id, shared.symbol, theme.symbol)
            assertEquals(id, shared.category, theme.category)
            assertEquals(id, shared.colorIndex, theme.colorIndex)
            assertNotEquals(id, shared.situation, theme.situation)
        }
        val situations = japanese.themes.map { it.situation }
        for (marker in listOf("喫茶店", "商店街", "JR", "地下鉄", "花見", "定食", "居酒屋", "旅館", "温泉", "お正月"))
            assertTrue(marker, situations.any { it.contains(marker) })
        // Japanese situations, never a translation of the Mandarin module's.
        for (id in japanese.themeOverrides.keys.intersect(mandarin.themeOverrides.keys))
            assertNotEquals(id, mandarin.themeOverrides.getValue(id).situation, japanese.themeOverrides.getValue(id).situation)
    }

    @Test fun theGreetingIsTappableAsOneWordAndKeepsEveryCharacter() {
        assertEquals(listOf("こんにちは", "！"), CaptionWords.segments(japanese.greeting, "ja", null).map { it.text })
        assertEquals(listOf("こんにちは"), CaptionWords.segments(japanese.greeting, "ja", null).mapNotNull { it.lookup })
        assertEquals(japanese.greeting,
            CaptionWords.segments(japanese.greeting, "ja", null).joinToString("") { it.text })
        assertEquals(japanese.greetingWord,
            CaptionWords.segments(japanese.greeting, "ja", null).mapNotNull { it.lookup }.first())
    }

    @Test fun detectedJapaneseDoesNotRedirectAJapaneseConversationButMandarinDoes() {
        for ((detected, confidence) in listOf("ja" to 0.95, "ja-JP" to 0.97, "JA_jp" to 0.99,
            "en" to 0.60, "und" to 0.99))
            assertFalse(detected, TeachingPolicy.shouldRedirectSpeech(japanese, detected, confidence))
        // Vocabulary and speech stay separate from Mandarin: Chinese speech redirects a Japanese session.
        for (detected in listOf("zh", "zh-CN", "ko", "en"))
            assertTrue(detected, TeachingPolicy.shouldRedirectSpeech(japanese, detected, 0.95))
        assertTrue(TeachingPolicy.detectedLanguageMatches(japanese, "ja"))
        assertFalse(TeachingPolicy.detectedLanguageMatches(japanese, "zh"))
        // A Japanese session never redirects a Mandarin conversation either.
        assertTrue(TeachingPolicy.shouldRedirectSpeech(mandarin, "ja", 0.95))
    }

    @Test fun archiveRoundTripKeepsJapaneseSessionsAndIsolatesVocabularyFromMandarin() {
        val sessions = mutableListOf(
            record("喫茶店でコーヒーを飲みました。", "飲む", "飲み", "to drink"),
            record("駅まで電車で行きます。", "電車", "電車", "train", day = 2))
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(
            Archive(sessions = sessions, preferences = Preferences(learningLanguageID = "ja", meaningLanguage = "English"))))
        assertEquals("ja", restored.preferences.learningLanguageID)
        assertEquals("A little Japanese", restored.sessions.first().title)
        assertEquals("喫茶店でコーヒーを飲みました。", restored.sessions.first().passages.single().text)
        assertEquals(setOf("飲む", "電車"),
            LearningEngine.project(restored.sessions, "ja").words.map { it.lemma }.toSet())
        assertEquals(1, LearningEngine.project(restored.sessions, "ja").words.single { it.lemma == "飲む" }.independentCount)
        for (other in AndroidLanguages.all.filter { it.id != "ja" })
            assertTrue(other.id, LearningEngine.project(restored.sessions, other.id).words.isEmpty())
        // Evidence tagged Mandarin never joins the Japanese module's vocabulary.
        val mixed = record("私は日本語を勉強しています。", "勉強する", "勉強", "study", evidenceLanguage = "zh")
        assertTrue(LearningEngine.validate(mixed.assessments.single(), mixed)!!.words.isEmpty())
        val chinese = record("我想去银行。", "银行", "银行", "bank", languageID = "zh")
        val both = ArchiveCodec.decode(ArchiveCodec.encode(Archive(sessions = mutableListOf(chinese, sessions[0]))))
        assertEquals(setOf("银行"), LearningEngine.project(both.sessions, "zh").words.map { it.lemma }.toSet())
        assertEquals(setOf("飲む"), LearningEngine.project(both.sessions, "ja").words.map { it.lemma }.toSet())
        // A supported meaning makes the Japanese evidence assisted, exactly as for every other module.
        assertEquals(0, LearningEngine.project(listOf(record("お茶をください。", "お茶", "お茶", "tea",
            supported = true)), "ja").words.single().independentCount)
    }

    @Test fun teachingPromptsSpeakJapaneseAndNeverFallBackToTheGenericPartner() {
        val learner = LearningEngine.project(emptyList(), "ja")
        val voice = TeachingPolicy.voice(japanese, learner, japanese.themes.first(), "", "English")
        for (prompt in listOf(voice, TeachingPolicy.assessment(japanese), TeachingPolicy.greeting(japanese),
            TeachingPolicy.checkIn(japanese), TeachingPolicy.help(japanese), TeachingPolicy.redirect(japanese),
            TeachingPolicy.translation(japanese, "English"), TeachingPolicy.delegation(japanese),
            TeachingPolicy.typedReply(japanese), TeachingPolicy.lookup(japanese, "English"),
            TeachingPolicy.currentTopic(japanese))) {
            assertTrue(prompt, prompt.contains(japanese.name))
            assertFalse(prompt, prompt.contains("Norwegian"))
        }
        assertTrue(voice.contains(japanese.speechGuidance) && voice.contains(japanese.writingGuidance))
        // The Japanese introduction and correction policy, never the English fallback or another module's.
        assertTrue(voice, voice.contains("あなたはMural"))
        assertTrue(voice, voice.contains("日本語だけ"))
        assertTrue(voice, voice.contains("訂正は一度の返答につき一つまで"))
        assertTrue(voice, voice.contains("話題を変える"))
        assertTrue(voice, voice.contains("推測せずに"))
        assertFalse(voice, voice.contains("You are Mural, a warm conversation partner."))
        assertFalse(voice, voice.contains("你是Mural"))
        assertTrue(TeachingPolicy.assessment(japanese).contains("Use language ja for target-language evidence"))
        assertTrue(TeachingPolicy.lookup(japanese, "English").contains(japanese.lemmaGuidance))
        assertTrue(TeachingPolicy.currentTopic(japanese).contains(japanese.writingGuidance))
        assertTrue(TeachingPolicy.greeting(japanese).contains(japanese.greeting))
        val openings = japanese.themes.map { TeachingPolicy.greeting(japanese, it) }
        assertEquals(24, openings.toSet().size)
        assertTrue(openings.all { it.length <= 1000 })
    }

    @Test fun spacelessJapaneseCaptionsSplitIntoTappableWordsAndKeepEveryCharacter() {
        val sentence = "今日は喫茶店でコーヒーを飲みました。"
        val segments = CaptionWords.segments(sentence, "ja", null)
        assertEquals(sentence, segments.joinToString("") { it.text })
        // Never one sentence blob: each word, particle included, is its own tap target.
        assertEquals(listOf("今日", "は", "喫茶店", "で", "コーヒー", "を", "飲", "みました", "。"),
            segments.map { it.text })
        assertEquals(listOf("今日", "は", "喫茶店", "で", "コーヒー", "を", "飲", "みました"),
            segments.mapNotNull { it.lookup })
    }

    @Test fun everyJapaneseCaptionKeepsEverySourceCharacterAndNeverLinksPunctuation() {
        for (text in listOf(
            "こんにちは！", "元気ですか？", "「そうです。」と言った", "駅でJRに乗ります",
            "お花見に行きませんか", "気をつけてください", "日本語\nとEnglish混在テスト。",
            "2026年、東京は暑いです！ ☕️", "、。！？〜ー", "🙂🇯🇵絵文字", "  ", "\t", "")) {
            val segments = CaptionWords.segments(text, "ja", null)
            assertEquals(text, segments.joinToString("") { it.text })
            for (segment in segments) {
                val hasLetter = segment.text.any { it.isLetter() }
                assertEquals(segment.text, hasLetter, segment.lookup != null)
                if (hasLetter) assertEquals(segment.text, segment.lookup)
            }
        }
    }

    @Test fun japaneseCaptionsIgnoreTheMandarinReaderSoVocabularyStaysIsolatedFromChinese() {
        val hanReader = object : HanReader {
            override fun words(text: String) = listOf(text)
            override fun reading(word: String) = "nihongo"
        }
        val sentence = "私は日本語を勉強しています。"
        // A Mandarin reader is never consulted for Japanese: the same characters split into Japanese words.
        assertEquals(CaptionWords.segments(sentence, "ja", null), CaptionWords.segments(sentence, "ja", hanReader))
        assertTrue(CaptionWords.segments(sentence, "ja", hanReader).size > 3)
        // The Mandarin branch keeps its own whole-sentence segmentation for the same text.
        assertEquals(listOf(CaptionSegment(sentence, sentence)), CaptionWords.segments(sentence, "zh", hanReader))
    }
}
