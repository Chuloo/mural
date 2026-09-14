import XCTest
@testable import MuralCore

final class TagalogTests: XCTestCase {
    private func language() throws -> LanguageModule { try XCTUnwrap(LanguageRegistry.module(for: "tl")) }

    private func record(_ text: String = "Gusto ko ng kape.", lemma: String = "kape", form: String = "kape",
                        meaning: String = "coffee", languageID: String = "tl", day: Int = 0,
                        typed: Bool = false, supported: Bool = false) -> SessionRecord {
        let date = Date(timeIntervalSince1970: 1_780_000_000 + Double(day) * 86400)
        var session = SessionRecord(languageID: languageID, themeID: "coffee")
        session.startedAt = date
        session.append(Fragment(speaker: .user, text: text, startMS: 100_000, endMS: 103_000,
                                receivedAt: date, meaningVisible: supported, typed: typed))
        let passage = session.passages[0]
        session.assessments = [Assessment(passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
            suggestedLevel: 2, nextGoal: "Magtanong tungkol sa presyo.", capability: "Orders a drink",
            words: [WordProposal(lemma: lemma, meaning: meaning, form: form, kind: .independent, confidence: 0.95,
                sourceIDs: passage.fragments.map(\.id), quote: text, language: languageID)], createdAt: date)]
        session.endedAt = date.addingTimeInterval(104)
        return session
    }

    func testIdentityUsesOneTagalogNamespaceWithFilipinoDisplayAlias() throws {
        let module = try language()
        XCTAssertEqual(module.name, "Tagalog (Filipino)")
        XCTAssertEqual(module.nativeName, "Tagalog (Filipino)")
        XCTAssertEqual(module.settingsTitle, "Tagalog (Filipino) · Philippines")
        XCTAssertEqual(module.locale, "tl-PH")
        XCTAssertEqual(module.greeting, "Kumusta!")
        XCTAssertEqual(module.greetingWord, "kumusta")
        XCTAssertEqual(LanguageRegistry.defaultID, "nb")
        XCTAssertEqual(LanguageRegistry.all.filter { $0.id == "tl" }.count, 1)
        for alias in ["fil", "fil-PH", "tgl", "tl-PH"] { XCTAssertNil(LanguageRegistry.module(for: alias)) }
    }

    func testEveryPromptPathUsesTagalogWithoutAnotherTeachingTarget() throws {
        let module = try language()
        let theme = try XCTUnwrap(module.themes.first { $0.id == "coffee" })
        let voice = TeachingPolicy.voice(language: module, learner: LearningEngine.project([], languageID: "tl"),
                                        theme: theme, interests: "music", meaningLanguage: "French")
        let assessment = TeachingPolicy.assessment(language: module)
        let prompts = [voice, assessment, TeachingPolicy.greeting(language: module), TeachingPolicy.help(language: module),
            TeachingPolicy.redirect(language: module), TeachingPolicy.theme(theme, language: module),
            TeachingPolicy.translation(language: module, meaningLanguage: "French"), TeachingPolicy.delegation(language: module),
            TeachingPolicy.typedReply(language: module), TeachingPolicy.lookup(language: module, meaningLanguage: "French"),
            TeachingPolicy.currentTopic(language: module)]
        for prompt in prompts {
            XCTAssertTrue(prompt.contains(module.name))
            for contaminant in ["Norwegian", "Bokmål", "Brazilian", "Spain", "Mandarin"] {
                XCTAssertFalse(prompt.contains(contaminant), contaminant)
            }
        }
        XCTAssertTrue(voice.contains(module.speechGuidance))
        XCTAssertTrue(voice.contains(module.writingGuidance))
        XCTAssertTrue(assessment.contains(module.lemmaGuidance))
        XCTAssertTrue(assessment.contains("Use language tl for target-language evidence"))
        XCTAssertTrue(TeachingPolicy.translation(language: module, meaningLanguage: "French").contains("into French"))
    }

    func testAllSixChallengesReceiveTheirTagalogFocusIncludingClampedBounds() throws {
        let module = try language()
        XCTAssertEqual(module.teachingFocus.count, 6)
        XCTAssertEqual(Set(module.teachingFocus).count, 6)
        for level in -1...6 {
            var learner = LearningEngine.project([], languageID: "tl")
            learner.challenge = level
            let prompt = TeachingPolicy.voice(language: module, learner: learner, theme: nil, interests: "", meaningLanguage: "English")
            XCTAssertTrue(prompt.contains(module.teachingFocus[min(5, max(0, level))]))
        }
    }

    func testPhilippineThemesOverrideStableIDsAndInheritTheRest() throws {
        let module = try language()
        XCTAssertEqual(Set(module.themeOverrides.keys), ["coffee", "groceries", "travel", "cabin", "traditions"])
        XCTAssertEqual(module.themes.map(\.id), ConversationTheme.shared.map(\.id))
        for theme in module.themes {
            if let override = module.themeOverrides[theme.id] {
                XCTAssertEqual(theme, override)
                XCTAssertTrue(theme.situation.contains("Philippines"))
            } else { XCTAssertEqual(theme, ConversationTheme.shared.first { $0.id == theme.id }) }
        }
        XCTAssertFalse(module.lookupUnavailableReply.isEmpty)
        XCTAssertTrue(module.topicPlaceholder.contains("Philippines"))
    }

    func testUnreliableDetectorCannotRedirectValidTagalogAtHighConfidence() throws {
        let module = try language()
        XCTAssertFalse(TeachingPolicy.supportsSpeechLanguageDetection(language: module))
        for id in ["id", "hr", "en", "tl", "tl-PH", "tl_PH", "fil", "fil-PH", "und", ""] {
            for confidence in [0.0, 0.88, 0.99247, 0.99974, 1, .nan, .infinity] {
                XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: module, detectedLanguageID: id, confidence: confidence))
            }
        }
    }

    func testDetectorPolicyStillProtectsEveryPreviouslySupportedTarget() {
        for module in LanguageRegistry.all where module.id != "tl" {
            XCTAssertTrue(TeachingPolicy.supportsSpeechLanguageDetection(language: module))
            let wrong = module.id == "en" ? "es" : "en"
            XCTAssertTrue(TeachingPolicy.shouldRedirectSpeech(language: module, detectedLanguageID: wrong, confidence: 0.99), module.id)
            for detected in [module.id, module.locale, module.locale.replacingOccurrences(of: "-", with: "_")] {
                XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: module, detectedLanguageID: detected, confidence: 0.99))
            }
            for confidence in [0, 0.88, 1.01, .nan, .infinity] {
                XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: module, detectedLanguageID: wrong, confidence: confidence))
            }
        }
    }

    func testUnaidedTagalogRecallGrowsAcrossDaysAndStaysSeparate() throws {
        _ = try language()
        let first = record(), second = record(day: 2)
        let sessions = [first, second, record("Eu gosto de café.", lemma: "o café", form: "café", languageID: "pt")]
        let state = LearningEngine.project(sessions, languageID: "tl", now: second.startedAt)
        XCTAssertEqual(state.observationCount, 2)
        XCTAssertEqual(state.challenge, 1)
        XCTAssertEqual(state.words.first?.id, "tl|kape|coffee")
        XCTAssertEqual(state.words.first?.independentCount, 2)
        XCTAssertEqual(state.words.first?.bars, 2)
        XCTAssertTrue(LearningEngine.project(sessions, languageID: "nb").words.isEmpty)
        XCTAssertEqual(LearningEngine.project(sessions, languageID: "pt").words.first?.id, "pt|o café|coffee")
    }

    func testTypedVisibleMeaningAndImmediateImitationAreAssisted() throws {
        _ = try language()
        for (typed, supported) in [(true, false), (false, true), (true, true)] {
            let session = record(typed: typed, supported: supported)
            XCTAssertEqual(LearningEngine.validate(session.assessments[0], session: session)?.words.first?.kind, .assisted)
            XCTAssertEqual(LearningEngine.project([session], languageID: "tl").words.first?.independentCount, 0)
        }
        var imitated = record()
        imitated.append(Fragment(speaker: .assistant, text: "Gusto mo ba ng kape?", startMS: 90_000, endMS: 93_000))
        XCTAssertEqual(LearningEngine.validate(imitated.assessments[0], session: imitated)?.words.first?.kind, .assisted)
    }

    func testForeignMixedAndAliasEvidenceCannotBecomeTagalogVocabulary() throws {
        _ = try language()
        for id in ["en", "es", "pt", "nb", "fil", "fil-PH", "tgl", "id", "mixed", "uncertain"] {
            for kind in [EvidenceKind.independent, .assisted, .understanding, .exposure, .lapse] {
                var session = record()
                session.assessments[0].words[0].language = id
                session.assessments[0].words[0].kind = kind
                XCTAssertTrue(try XCTUnwrap(LearningEngine.validate(session.assessments[0], session: session)).words.isEmpty, "\(id) / \(kind)")
            }
        }
    }

    func testMixedPassageRetainsOnlyItsExplicitTagalogProposal() throws {
        _ = try language()
        var session = record("I would like kape, please.")
        var english = session.assessments[0].words[0]
        english.lemma = "please"; english.form = "please"; english.meaning = "please"; english.language = "en"
        session.assessments[0].words.append(english)
        let words = try XCTUnwrap(LearningEngine.validate(session.assessments[0], session: session)).words
        XCTAssertEqual(words.map(\.key), ["tl|kape|coffee"])
    }

    func testFabricatedOrStaleEvidenceIsRejected() throws {
        _ = try language()
        for mutation in 0..<4 {
            var session = record()
            switch mutation {
            case 0: session.assessments[0].words[0].sourceIDs = ["invented"]
            case 1: session.assessments[0].words[0].quote = "Wala akong kape."
            case 2: session.assessments[0].words[0].form = "tsaa"
            default: session.assessments[0].words[0].confidence = .nan
            }
            XCTAssertTrue(try XCTUnwrap(LearningEngine.validate(session.assessments[0], session: session)).words.isEmpty)
        }
        var revised = record()
        revised.fragments[0].revision += 1
        XCTAssertNil(LearningEngine.validate(revised.assessments[0], session: revised))
    }

    func testAspectVariantsShareCitationFormButDifferentFocusStaysDistinct() throws {
        _ = try language()
        let sessions = [record("Kumakain ako ngayon.", lemma: "kumain", form: "Kumakain", meaning: "eat"),
                        record("Kakain ako mamaya.", lemma: "kumain", form: "Kakain", meaning: "eat", day: 2),
                        record("Kinain ko ang saging.", lemma: "kainin", form: "Kinain", meaning: "eat", day: 2)]
        let words = LearningEngine.project(sessions, languageID: "tl", now: sessions[1].startedAt).words
        XCTAssertEqual(Set(words.map(\.id)), ["tl|kumain|eat", "tl|kainin|eat"])
        XCTAssertEqual(words.first { $0.lemma == "kumain" }?.independentCount, 2)
        XCTAssertEqual(words.first { $0.lemma == "kainin" }?.independentCount, 1)
    }

    func testHomographsAndHiddenWordsKeepTheirSensesAndLanguage() throws {
        _ = try language()
        let sessions = [record("Bukás ang pinto.", lemma: "bukas", form: "Bukás", meaning: "open"),
                        record("Bukas ako aalis.", lemma: "bukas", form: "Bukas", meaning: "tomorrow"),
                        record("radio", lemma: "radio", form: "radio", meaning: "radio"),
                        record("radio", lemma: "radio", form: "radio", meaning: "radio", languageID: "es")]
        let hidden = ["tl|bukas|open", "tl|radio|radio"]
        XCTAssertEqual(Set(LearningEngine.project(sessions, languageID: "tl", hiddenWords: hidden).words.map(\.id)), ["tl|bukas|tomorrow"])
        XCTAssertEqual(LearningEngine.project(sessions, languageID: "es", hiddenWords: hidden).words.count, 1)
    }

    func testWordLinksPreserveHyphensApostrophesDiacriticsAndWhitespace() {
        for (text, expected) in [
            ("  Mag-aaral ako araw-araw.\n", ["Mag-aaral", "ako", "araw-araw"]),
            ("Ako'y masaya. Siya’y narito!", ["Ako'y", "masaya", "Siya’y", "narito"]),
            ("Bukás ang pinto; búkas tayo aalis. ☕️", ["Bukás", "ang", "pinto", "búkas", "tayo", "aalis"])
        ] {
            let segments = CaptionWords.segments(text, languageID: "tl")
            XCTAssertEqual(segments.map(\.text).joined(), text)
            XCTAssertEqual(segments.compactMap(\.lookup), expected)
        }
    }

    func testArchiveRetainsExactTextSelectionTopicsAndHiddenWords() throws {
        _ = try language()
        let text = "Ako'y mag-aaral araw-araw.\nBukás ang pinto."
        var archive = Archive()
        archive.preferences.learningLanguageID = "tl"
        archive.preferences.meaningLanguage = "French"
        archive.preferences.hiddenWords = ["tl|mag-aral|study"]
        archive.sessions = [record(text, lemma: "mag-aral", form: "mag-aaral", meaning: "study"), record(languageID: "nb")]
        archive.sessions[0].topics = [TopicBrief(languageID: "tl", query: "pagkain", text: "Pag-usapan natin ang pagkain.", sources: [])]
        let restored = try Archive.decode(archive.encoded())
        XCTAssertEqual(restored.schemaVersion, 2)
        XCTAssertEqual(restored.preferences.learningLanguageID, "tl")
        XCTAssertEqual(restored.preferences.meaningLanguage, "French")
        XCTAssertEqual(restored.preferences.hiddenWords, archive.preferences.hiddenWords)
        XCTAssertEqual(restored.sessions.map(\.id), archive.sessions.map(\.id))
        XCTAssertEqual(restored.sessions[0].passages[0].text, text)
        XCTAssertEqual(restored.sessions[0].assessments[0].words[0].quote, text)
        XCTAssertEqual(restored.sessions[0].topics[0].languageID, "tl")
    }

    func testImportMergesTagalogWithoutReplacingExistingLanguageOrHistory() throws {
        _ = try language()
        var existing = Archive(), incoming = Archive()
        existing.preferences.learningLanguageID = "es"
        existing.sessions = [record(languageID: "es")]
        incoming.preferences.learningLanguageID = "tl"
        incoming.sessions = [record()]
        let merged = try existing.merging(Archive.decode(incoming.encoded()))
        XCTAssertEqual(merged.preferences.learningLanguageID, "es")
        XCTAssertEqual(merged.sessions.map(\.id), existing.sessions.map(\.id) + incoming.sessions.map(\.id))
        XCTAssertEqual(LearningEngine.project(merged.sessions, languageID: "tl").words.first?.id, "tl|kape|coffee")
    }

    func testUnknownIDsAndMixedLanguageTopicsCannotReplaceAnArchive() throws {
        _ = try language()
        var original = Archive()
        original.sessions = [record()]
        let before = try original.encoded()
        for id in ["fil", "tl-PH", "unknown"] {
            var incoming = original
            incoming.preferences.learningLanguageID = id
            XCTAssertThrowsError(try Archive.decode(incoming.encoded()))
            incoming.preferences.learningLanguageID = "tl"
            incoming.sessions[0] = record(languageID: id)
            XCTAssertThrowsError(try original.merging(incoming))
        }
        var incoming = original
        incoming.sessions[0].topics = [TopicBrief(languageID: "es", query: "food", text: "Comida", sources: [])]
        XCTAssertThrowsError(try original.merging(incoming))
        XCTAssertEqual(try original.encoded(), before)
    }
}
