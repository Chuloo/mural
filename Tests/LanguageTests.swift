import XCTest
@testable import MuralCore

final class LanguageTests: XCTestCase {
    func testExistingArchivesRequireConsentAndAcceptedVersionRoundTrips() throws {
        var archive = Archive()
        archive.preferences.hasOnboarded = true
        let legacy = try Archive.decode(archive.encoded())
        XCTAssertTrue(legacy.preferences.hasOnboarded)
        XCTAssertNil(legacy.preferences.aiConsentVersion)
        archive.preferences.aiConsentVersion = 1
        XCTAssertEqual(try Archive.decode(archive.encoded()).preferences.aiConsentVersion, 1)
    }
    private func evidence(languageID: String, day: Int = 0, supported: Bool = false) -> SessionRecord {
        let date = Date(timeIntervalSince1970: 1_780_000_000 + Double(day) * 86400)
        var session = SessionRecord(languageID: languageID, themeID: "coffee")
        session.startedAt = date
        session.append(Fragment(speaker: .user, text: "radio", startMS: 1000, endMS: 2000, receivedAt: date, meaningVisible: supported))
        let passage = session.passages[0]
        session.assessments = [Assessment(passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
            suggestedLevel: 3, nextGoal: "A goal for \(languageID)", capability: "Describes a familiar object",
            words: [WordProposal(lemma: "radio", meaning: "radio", form: "radio", kind: .independent, confidence: 0.95,
                sourceIDs: passage.fragments.map(\.id), quote: "radio", language: languageID)], createdAt: date)]
        return session
    }

    func testProgressAndVocabularyStayWithinTheirLanguage() {
        let sessions = [evidence(languageID: "es"), evidence(languageID: "es", day: 2)]
        let spanish = LearningEngine.project(sessions, languageID: "es", now: sessions[1].startedAt)
        let norwegian = LearningEngine.project(sessions, languageID: "nb", now: sessions[1].startedAt)
        XCTAssertEqual(spanish.challenge, 1)
        XCTAssertEqual(spanish.words.first?.bars, 2)
        XCTAssertEqual(norwegian.challenge, 0)
        XCTAssertEqual(norwegian.observationCount, 0)
        XCTAssertTrue(norwegian.words.isEmpty)
        XCTAssertNotEqual(norwegian.nextGoal, "A goal for es")
    }

    func testHidingACognateDoesNotHideItInAnotherLanguage() {
        let sessions = [evidence(languageID: "nb"), evidence(languageID: "es")]
        let spanishID = sessions[1].assessments[0].words[0].key
        XCTAssertTrue(LearningEngine.project(sessions, languageID: "es", hiddenWords: [spanishID]).words.isEmpty)
        XCTAssertEqual(LearningEngine.project(sessions, languageID: "nb", hiddenWords: [spanishID]).words.count, 1)
    }

    func testEvidenceInAnotherLanguageIsNeverCredited() {
        for kind in [EvidenceKind.independent, .assisted, .understanding, .exposure, .lapse] {
            var session = evidence(languageID: "es")
            session.assessments[0].words[0].language = "nb"
            session.assessments[0].words[0].kind = kind
            XCTAssertTrue(LearningEngine.validate(session.assessments[0], session: session)!.words.isEmpty)
        }
    }

    func testSpanishWithMeaningSupportIsAssisted() {
        let session = evidence(languageID: "es", supported: true)
        XCTAssertEqual(LearningEngine.validate(session.assessments[0], session: session)?.words.first?.kind, .assisted)
        XCTAssertEqual(LearningEngine.project([session], languageID: "es").words.first?.independentCount, 0)
    }

    func testVersionOneMigrationPreservesNorwegianEvidenceAndHiddenWords() throws {
        var original = Archive()
        var session = evidence(languageID: "nb")
        session.topics = [TopicBrief(languageID: "nb", query: "weather", text: "En solrik dag.", sources: [])]
        original.sessions = [session]
        original.preferences.hiddenWords = [session.assessments[0].words[0].key]
        var legacy = try JSONSerialization.jsonObject(with: original.encoded()) as! [String: Any]
        legacy["schemaVersion"] = 1
        var preferences = legacy["preferences"] as! [String: Any]
        preferences.removeValue(forKey: "learningLanguageID")
        preferences["hiddenWords"] = ["radio|radio"]
        legacy["preferences"] = preferences
        var oldSession = (legacy["sessions"] as! [[String: Any]])[0]
        oldSession.removeValue(forKey: "languageID")
        var oldTopic = (oldSession["topics"] as! [[String: Any]])[0]
        oldTopic.removeValue(forKey: "languageID")
        oldSession["topics"] = [oldTopic]; legacy["sessions"] = [oldSession]
        let migrated = try Archive.decode(JSONSerialization.data(withJSONObject: legacy))
        XCTAssertEqual(migrated.schemaVersion, 2)
        XCTAssertEqual(migrated.preferences.learningLanguageID, "nb")
        XCTAssertEqual(migrated.preferences.hiddenWords, original.preferences.hiddenWords)
        XCTAssertEqual(migrated.sessions[0].id, session.id)
        XCTAssertEqual(migrated.sessions[0].fragments, session.fragments)
        XCTAssertEqual(migrated.sessions[0].topics[0].languageID, "nb")
        XCTAssertEqual(migrated.sessions[0].topics[0].text, session.topics[0].text)
        XCTAssertEqual(LearningEngine.project(migrated.sessions).words.first?.independentCount, 1)
        XCTAssertTrue(LearningEngine.project(migrated.sessions, hiddenWords: migrated.preferences.hiddenWords).words.isEmpty)
        XCTAssertEqual(try Archive.decode(migrated.encoded()).preferences.hiddenWords, original.preferences.hiddenWords)
    }

    func testBilingualArchiveRoundTripAndSelection() throws {
        var archive = Archive()
        archive.preferences.learningLanguageID = "es"
        archive.sessions = [evidence(languageID: "nb"), evidence(languageID: "es")]
        let restored = try Archive.decode(archive.encoded())
        XCTAssertEqual(restored.preferences.learningLanguageID, "es")
        XCTAssertEqual(restored.sessions.map(\.languageID), ["nb", "es"])
        XCTAssertEqual(LearningEngine.project(restored.sessions, languageID: "es").words.count, 1)
    }

    func testVersionTwoRequiresExplicitSupportedLanguage() throws {
        var archive = Archive(); archive.sessions = [evidence(languageID: "es")]
        var root = try JSONSerialization.jsonObject(with: archive.encoded()) as! [String: Any]
        var session = (root["sessions"] as! [[String: Any]])[0]
        session.removeValue(forKey: "languageID"); root["sessions"] = [session]
        XCTAssertThrowsError(try Archive.decode(JSONSerialization.data(withJSONObject: root)))
        archive.sessions = [SessionRecord(languageID: "not-installed")]
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
    }

    func testTopicCannotBeAttachedToADifferentLanguage() throws {
        var archive = Archive(); var session = SessionRecord(languageID: "es")
        session.topics = [TopicBrief(languageID: "nb", query: "food", text: "Mat", sources: [])]
        archive.sessions = [session]
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
    }

    func testEveryModuleHasCompleteCurriculumAndStableThemeIDs() {
        XCTAssertEqual(Set(LanguageRegistry.all.map(\.id)).count, LanguageRegistry.all.count)
        for language in LanguageRegistry.all {
            XCTAssertEqual(language.teachingFocus.count, 6)
            XCTAssertTrue(language.teachingFocus.allSatisfy { !$0.isEmpty })
            XCTAssertEqual(Set(language.themes.map(\.id)), Set(ConversationTheme.shared.map(\.id)))
            XCTAssertTrue(language.themeOverrides.allSatisfy { $0.key == $0.value.id })
            XCTAssertFalse(language.speechGuidance.isEmpty)
            XCTAssertFalse(language.lemmaGuidance.isEmpty)
            XCTAssertFalse(language.lookupUnavailableReply.isEmpty)
        }
    }

    func testSpanishFlowsUseSpanishPolicyAndCulturalContext() {
        let language = LanguageModule.spanish
        let learner = LearningEngine.project([], languageID: language.id)
        let prompts = [TeachingPolicy.voice(language: language, learner: learner, theme: nil, interests: "", meaningLanguage: "English"),
            TeachingPolicy.assessment(language: language), TeachingPolicy.greeting(language: language),
            TeachingPolicy.help(language: language), TeachingPolicy.redirect(language: language),
            TeachingPolicy.translation(language: language, meaningLanguage: "English"), TeachingPolicy.delegation(language: language),
            TeachingPolicy.typedReply(language: language), TeachingPolicy.lookup(language: language, meaningLanguage: "English"),
            TeachingPolicy.currentTopic(language: language)]
        for prompt in prompts {
            XCTAssertTrue(prompt.contains("Spanish"))
            XCTAssertFalse(prompt.contains("Norwegian"))
            XCTAssertFalse(prompt.contains("Bokmål"))
        }
        XCTAssertTrue(language.themes.allSatisfy { !$0.situation.contains("Norway") && !$0.situation.contains("Norwegian") })
        XCTAssertEqual(language.locale, "es-ES")
    }

    func testEnglishAndFrenchHaveTargetLanguagePromptsWithoutEnglishSupportAssumptions() {
        for language in [LanguageModule.english, .french] {
            let learner = LearningEngine.project([], languageID: language.id)
            let voice = TeachingPolicy.voice(language: language, learner: learner, theme: nil, interests: "", meaningLanguage: "Norwegian")
            let typed = TeachingPolicy.typedReply(language: language)
            let redirect = TeachingPolicy.redirect(language: language)
            XCTAssertTrue(voice.contains("Speak ONLY \(language.name)."))
            XCTAssertTrue(voice.contains("Meaning subtitles in Norwegian"))
            XCTAssertTrue(typed.contains("Reply only in \(language.name)"))
            XCTAssertTrue(redirect.contains("continue ONLY in \(language.name)"))
            for prompt in [voice, typed, redirect] {
                XCTAssertFalse(prompt.contains("Never translate into English"))
                XCTAssertFalse(prompt.contains("Stop the English explanation"))
                XCTAssertFalse(prompt.contains("no headings or English translation"))
            }
            XCTAssertTrue(TeachingPolicy.assessment(language: language).contains("Use language \(language.id) for target-language evidence"))
            XCTAssertTrue(language.themes.allSatisfy { !$0.situation.contains("Norway") && !$0.situation.contains("Norwegian") })
        }
        XCTAssertEqual(LanguageRegistry.module(for: "en")?.greeting, "Hi!")
        XCTAssertEqual(LanguageRegistry.module(for: "fr")?.greeting, "Salut !")
        XCTAssertEqual(LanguageModule.french.locale, "fr-FR")
        XCTAssertEqual(MeaningLanguages.greeting(in: "Norwegian"), "Hei!")
        XCTAssertTrue(MeaningLanguages.all.contains("Chinese"))
        XCTAssertEqual(MeaningLanguages.greeting(in: "Chinese"), "你好！")
    }

    func testMandarinUsesSimplifiedChinesePolicyAndCulturalContext() {
        let language = LanguageModule.mandarin
        let learner = LearningEngine.project([], languageID: language.id)
        let prompts = [TeachingPolicy.voice(language: language, learner: learner, theme: nil, interests: "", meaningLanguage: "English"),
            TeachingPolicy.assessment(language: language), TeachingPolicy.greeting(language: language),
            TeachingPolicy.help(language: language), TeachingPolicy.redirect(language: language),
            TeachingPolicy.translation(language: language, meaningLanguage: "English"), TeachingPolicy.delegation(language: language),
            TeachingPolicy.typedReply(language: language), TeachingPolicy.lookup(language: language, meaningLanguage: "English"),
            TeachingPolicy.currentTopic(language: language)]
        for prompt in prompts {
            XCTAssertTrue(prompt.contains("Mandarin Chinese"))
            XCTAssertFalse(prompt.contains("Norwegian"))
        }
        XCTAssertEqual(language.id, "zh")
        XCTAssertEqual(language.locale, "zh-CN")
        XCTAssertEqual(language.greeting, "你好！")
        XCTAssertTrue(language.writingGuidance.contains("Simplified Chinese"))
        XCTAssertTrue(language.themes.allSatisfy { !$0.situation.contains("Norway") && !$0.situation.contains("Norwegian") })
    }

    func testMandarinPinyinPairsEachCharacter() {
        XCTAssertEqual(MandarinPinyin.annotated("你好！"), "你(nǐ) 好(hǎo)！")
        XCTAssertEqual(MandarinPinyin.tokens("喝茶？"), [
            MandarinPronunciationToken(text: "喝", pinyin: "hē"),
            MandarinPronunciationToken(text: "茶", pinyin: "chá"),
            MandarinPronunciationToken(text: "？", pinyin: nil)
        ])
        XCTAssertNil(MandarinPinyin.annotated("Hello!"))
    }

    func testEnglishIsProductionWhenEnglishIsTheTarget() {
        let session = evidence(languageID: "en")
        XCTAssertEqual(LearningEngine.validate(session.assessments[0], session: session)?.words.first?.kind, .independent)
        XCTAssertEqual(LearningEngine.project([session], languageID: "en").words.first?.independentCount, 1)

        var french = evidence(languageID: "fr")
        french.assessments[0].words[0].language = "en"
        XCTAssertTrue(LearningEngine.validate(french.assessments[0], session: french)!.words.isEmpty)
    }

    func testFourLanguageArchivePreservesSeparateProgressAndGlossaryKeys() throws {
        var archive = Archive()
        archive.preferences.learningLanguageID = "fr"
        archive.sessions = ["nb", "es", "en", "fr", "zh"].flatMap { [evidence(languageID: $0), evidence(languageID: $0, day: 2)] }
        let restored = try Archive.decode(archive.encoded())
        XCTAssertEqual(restored.preferences.learningLanguageID, "fr")
        let keys = ["nb", "es", "en", "fr", "zh"].compactMap { id -> String? in
            let learner = LearningEngine.project(restored.sessions, languageID: id, now: restored.sessions.last!.startedAt)
            XCTAssertEqual(learner.observationCount, 2)
            XCTAssertEqual(learner.words.count, 1)
            XCTAssertEqual(learner.words.first?.independentCount, 2)
            XCTAssertEqual(learner.words.first?.bars, 2)
            return learner.words.first?.id
        }
        XCTAssertEqual(Set(keys).count, 5)
    }

    func testFrenchEvidencePreservesAccentsAndElisions() throws {
        var session = SessionRecord(languageID: "fr")
        session.append(Fragment(speaker: .user, text: "J'ai déjà visité ce marché.", startMS: 1000, endMS: 3000))
        let passage = session.passages[0]
        session.assessments = [Assessment(passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
            suggestedLevel: 2, nextGoal: "Raconter une visite au marché.", capability: "Describes a past visit",
            words: [WordProposal(lemma: "un marché", meaning: "market", form: "marché", kind: .independent, confidence: 0.95,
                sourceIDs: passage.fragments.map(\.id), quote: "J'ai déjà visité ce marché.", language: "fr")])]
        let accepted = try XCTUnwrap(LearningEngine.validate(session.assessments[0], session: session)?.words.first)
        XCTAssertEqual(accepted.lemma, "un marché")
        XCTAssertEqual(accepted.quote, "J'ai déjà visité ce marché.")
        XCTAssertEqual(accepted.kind, .independent)
    }

    func testLanguageRedirectUsesTheSelectedTargetInsteadOfAnEnglishBlacklist() {
        for language in LanguageRegistry.all {
            XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: language, detectedLanguageID: language.id, confidence: 0.99))
            let otherID = language.id == "en" ? "fr" : "en"
            XCTAssertTrue(TeachingPolicy.shouldRedirectSpeech(language: language, detectedLanguageID: otherID, confidence: 0.99))
            for confidence in [0.0, 0.88, .nan, .infinity, 1.1] {
                XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: language, detectedLanguageID: otherID, confidence: confidence))
            }
            XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: language, detectedLanguageID: "und", confidence: 0.99))
            XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: language, detectedLanguageID: "", confidence: 0.99))
        }
    }
}
