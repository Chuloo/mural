import XCTest
@testable import MuralCore

final class AdditionalLanguageTests: XCTestCase {
    private let ids = ["de", "it", "pt", "zh"]
    private let samples = [
        ("de", "Ich gehe über die Straße.", "die Straße", "Straße", "street"),
        ("it", "Vorrei un caffè.", "un caffè", "caffè", "coffee"),
        ("pt", "Eu gosto de pão e maçã.", "o pão", "pão", "bread"),
        ("zh", "我想去银行。", "银行", "银行", "bank")
    ]

    private func session(_ id: String, text: String = "radio", lemma: String = "radio", form: String = "radio", meaning: String = "radio", day: Int = 0, supported: Bool = false, typed: Bool = false) -> SessionRecord {
        let date = Date(timeIntervalSince1970: 1_780_000_000 + Double(day) * 86400)
        var result = SessionRecord(languageID: id, themeID: "coffee")
        result.startedAt = date
        result.append(Fragment(speaker: .user, text: text, startMS: 0, endMS: 3000, receivedAt: date, meaningVisible: supported, typed: typed))
        let passage = result.passages[0]
        result.assessments = [Assessment(passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
            suggestedLevel: 3, nextGoal: "Goal for \(id)", capability: "Describes a familiar object",
            words: [WordProposal(lemma: lemma, meaning: meaning, form: form, kind: .independent, confidence: 0.95,
                sourceIDs: passage.fragments.map(\.id), quote: text, language: id)], createdAt: date)]
        return result
    }

    func testRegistrationPreservesOldIDsAndSetsRequestedVarieties() {
        XCTAssertEqual(LanguageRegistry.all.map(\.id), ["nb", "es", "en", "fr", "de", "it", "pt", "zh"])
        for (id, locale, greeting) in [("de", "de-DE", "Hallo!"), ("it", "it-IT", "Ciao!"), ("pt", "pt-BR", "Olá!"), ("zh", "zh-CN", "你好！")] {
            XCTAssertEqual(LanguageRegistry.module(for: id)?.locale, locale)
            XCTAssertEqual(LanguageRegistry.module(for: id)?.greeting, greeting)
        }
        XCTAssertTrue(MeaningLanguages.all.contains("Chinese (Simplified)"))
        XCTAssertEqual(MeaningLanguages.greeting(in: "Chinese (Simplified)"), "你好！")
    }

    func testAllPromptPathsUseEachNewTargetAndItsRegionalGuidance() throws {
        for id in ids {
            let language = try XCTUnwrap(LanguageRegistry.module(for: id))
            let learner = LearningEngine.project([], languageID: id)
            let voice = TeachingPolicy.voice(language: language, learner: learner, theme: language.themes[0], interests: "", meaningLanguage: "English")
            let assessment = TeachingPolicy.assessment(language: language)
            let prompts = [voice, assessment, TeachingPolicy.greeting(language: language), TeachingPolicy.help(language: language),
                TeachingPolicy.redirect(language: language), TeachingPolicy.translation(language: language, meaningLanguage: "English"),
                TeachingPolicy.delegation(language: language), TeachingPolicy.typedReply(language: language),
                TeachingPolicy.lookup(language: language, meaningLanguage: "English"), TeachingPolicy.currentTopic(language: language)]
            for prompt in prompts {
                XCTAssertTrue(prompt.contains(language.name), id)
                XCTAssertFalse(prompt.contains("Norwegian"), id)
                XCTAssertFalse(prompt.contains("Bokmål"), id)
            }
            XCTAssertTrue(voice.contains("Speak ONLY \(language.name)."))
            XCTAssertTrue(voice.contains("Persona and accent:"))
            XCTAssertTrue(voice.contains(language.variety))
            XCTAssertTrue(voice.contains(language.speechGuidance))
            XCTAssertTrue(voice.contains(language.writingGuidance))
            XCTAssertTrue(assessment.contains(language.lemmaGuidance))
            XCTAssertTrue(assessment.contains("Use language \(id) for target-language evidence"))
            XCTAssertTrue(language.themes.allSatisfy { !$0.situation.contains("Norway") })
        }
    }

    func testAllEightLanguagesRoundTripWithIsolatedProgressAndHiddenWords() throws {
        var archive = Archive()
        archive.sessions = LanguageRegistry.all.flatMap { [session($0.id), session($0.id, day: 2)] }
        archive.preferences.meaningLanguage = "Chinese (Simplified)"
        archive.preferences.hiddenWords = ["pt|radio|radio"]
        for id in ids {
            archive.preferences.learningLanguageID = id
            let restored = try Archive.decode(archive.encoded())
            XCTAssertEqual(restored.preferences.learningLanguageID, id)
            XCTAssertEqual(restored.preferences.meaningLanguage, "Chinese (Simplified)")
            XCTAssertEqual(restored.sessions.map(\.id), archive.sessions.map(\.id))
            var keys = Set<String>()
            for language in LanguageRegistry.all {
                let state = LearningEngine.project(restored.sessions, languageID: language.id, now: restored.sessions.last!.startedAt)
                XCTAssertEqual(state.observationCount, 2, language.id)
                XCTAssertEqual(state.words.count, 1, language.id)
                XCTAssertEqual(state.words.first?.independentCount, 2)
                XCTAssertEqual(state.words.first?.bars, 2)
                keys.formUnion(state.words.map(\.id))
                let hidden = LearningEngine.project(restored.sessions, languageID: language.id, hiddenWords: restored.preferences.hiddenWords)
                XCTAssertEqual(hidden.words.count, language.id == "pt" ? 0 : 1)
            }
            XCTAssertEqual(keys.count, 8)
        }
    }

    func testNativeScriptsAccentsAndExactEvidenceSurviveExportImport() throws {
        for (id, text, lemma, form, meaning) in samples {
            var archive = Archive()
            archive.preferences.learningLanguageID = id
            archive.sessions = [session(id, text: text, lemma: lemma, form: form, meaning: meaning)]
            let restored = try Archive.decode(archive.encoded())
            let record = restored.sessions[0]
            let word = try XCTUnwrap(LearningEngine.validate(record.assessments[0], session: record)?.words.first)
            XCTAssertEqual(word.lemma, lemma)
            XCTAssertEqual(word.quote, text)
            XCTAssertEqual(word.kind, .independent)
            XCTAssertEqual(record.passages[0].text, text)
        }
    }

    func testSupportedAndTypedPracticeCannotBecomeIndependentRecall() {
        for (id, text, lemma, form, meaning) in samples {
            for (supported, typed) in [(true, false), (false, true), (true, true)] {
                let record = session(id, text: text, lemma: lemma, form: form, meaning: meaning, supported: supported, typed: typed)
                XCTAssertEqual(LearningEngine.validate(record.assessments[0], session: record)?.words.first?.kind, .assisted, id)
                XCTAssertEqual(LearningEngine.project([record], languageID: id).words.first?.independentCount, 0)
            }
        }
    }

    func testForeignEvidenceIsRejectedForEveryNewLanguageAndEvidenceKind() {
        for id in ids {
            for other in LanguageRegistry.all where other.id != id {
                for kind in [EvidenceKind.independent, .assisted, .understanding, .exposure, .lapse] {
                    var record = session(id)
                    record.assessments[0].words[0].language = other.id
                    record.assessments[0].words[0].kind = kind
                    XCTAssertTrue(LearningEngine.validate(record.assessments[0], session: record)!.words.isEmpty, "\(id) / \(other.id)")
                }
            }
        }
    }

    func testChineseScriptAndRegionalDetectorIDsDoNotCauseRedirectLoops() {
        for detected in ["zh", "zh-Hans", "zh-Hant", "zh-CN", "zh_TW"] {
            XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: .mandarin, detectedLanguageID: detected, confidence: 0.99))
        }
        XCTAssertTrue(TeachingPolicy.shouldRedirectSpeech(language: .mandarin, detectedLanguageID: "ja", confidence: 0.99))
        XCTAssertTrue(TeachingPolicy.shouldRedirectSpeech(language: .mandarin, detectedLanguageID: "zhx", confidence: 0.99))
        XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: .portuguese, detectedLanguageID: "pt-PT", confidence: 0.99))
    }

    func testPinyinUsesWordReadingsAndNormalizesUmlautVowels() {
        for (text, reading) in [("银行", "yínháng"), ("旅行", "lǚxíng"), ("音乐", "yīnyuè"), ("快乐", "kuàilè"), ("重新", "chóngxīn"), ("重庆", "chóngqìng"), ("女儿", "nǚér"), ("你好！", "nǐhǎo！")] {
            XCTAssertEqual(MandarinPinyin.reading(text), reading, text)
        }
        XCTAssertEqual(MandarinPinyin.reading("我想去银行，然后去旅行。"), "wǒ xiǎng qù yínháng，ránhòu qù lǚxíng。")
    }

    func testChineseWordLinksPreserveMixedScriptPunctuationAndWhitespace() {
        for text in ["", "你好！", "  我想去银行，然后去旅行。\n", "你好，Mural 2026！☕️\n再见", "𠀀和咖啡", "銀行與音樂", "咖", "café e pão"] {
            XCTAssertEqual(MandarinPinyin.tokens(text).map(\.text).joined(), text)
            XCTAssertEqual(CaptionWords.segments(text, languageID: "zh").map(\.text).joined(), text)
        }
        let links = CaptionWords.segments("我想去银行，然后去旅行。", languageID: "zh").compactMap(\.lookup)
        XCTAssertTrue(links.contains("银行"))
        XCTAssertTrue(links.contains("旅行"))
        XCTAssertFalse(links.contains(where: { $0.contains("，") || $0.contains("。") }))
        XCTAssertNil(MandarinPinyin.reading("Hello, Mural 2026! ☕️"))
    }

    func testSpacedLanguageWordLinksKeepAccentsApostrophesAndLineBreaks() {
        for (text, expected) in [("  J'ai déjà\nvisité ce marché.  ", ["J'ai", "déjà", "visité", "ce", "marché"]),
                                 ("Olá!\n pão  e maçã.", ["Olá", "pão", "e", "maçã"])] {
            let segments = CaptionWords.segments(text, languageID: "pt")
            XCTAssertEqual(segments.map(\.text).joined(), text)
            XCTAssertEqual(segments.compactMap(\.lookup), expected)
        }
    }
}
