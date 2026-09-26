import XCTest
@testable import MuralCore

final class LearningTests: XCTestCase {
    func fixture(day: Double = 0, theme: String = "walk", supported: Bool = false, kind: EvidenceKind = .independent) -> SessionRecord {
        let date = Date(timeIntervalSince1970: 1_780_000_000 + day * 86400)
        var s = SessionRecord(themeID: theme)
        s.startedAt = date
        s.append(Fragment(id: UUID().uuidString, speaker: .user, text: "Jeg gikk i skogen.", startMS: 1000, endMS: 2000, receivedAt: date, meaningVisible: supported))
        let p = s.passages[0]
        s.assessments = [Assessment(passageID: p.id, revisionKey: p.revisionKey, outcome: .success, suggestedLevel: 2, nextGoal: "Fortell mer.", capability: "Describes a past outing", words: [WordProposal(lemma: "å gå", meaning: "to go", form: "gikk", kind: kind, confidence: 0.95, sourceIDs: p.fragments.map(\.id), quote: "Jeg gikk i skogen.")], createdAt: date, context: theme)]
        return s
    }
    func testDuplicateProviderEventsDoNotChangeTranscript() {
        var s = SessionRecord()
        let f = Fragment(id: "same", speaker: .user, text: "Hei", startMS: 0, endMS: 100)
        s.append(f); s.append(f)
        XCTAssertEqual(s.fragments.count, 1)
    }
    func testConcatenationPreservesExactProviderWhitespace() {
        let f = [Fragment(id: "a", speaker: .assistant, text: "Hva", startMS: 0, endMS: 100), Fragment(id: "b", speaker: .assistant, text: " gjorde du?", startMS: 100, endMS: 400)]
        XCTAssertEqual(Transcript.passages(f).first?.text, "Hva gjorde du?")
    }
    func testConcatenationInsertsSpaceBetweenBareFragmentBoundaries() {
        let f = [
            Fragment(id: "a", speaker: .assistant, text: "It is easy.", startMS: 0, endMS: 100),
            Fragment(id: "b", speaker: .assistant, text: "Now you?", startMS: 400, endMS: 700),
        ]
        XCTAssertEqual(Transcript.passages(f).first?.text, "It is easy. Now you?")
        let punctuated = [
            Fragment(id: "c", speaker: .assistant, text: "Hei", startMS: 0, endMS: 100),
            Fragment(id: "d", speaker: .assistant, text: "!", startMS: 100, endMS: 150),
        ]
        XCTAssertEqual(Transcript.passages(punctuated).first?.text, "Hei!")
    }
    func testJoinPreservesMandarinAndUnicodeBoundaries() {
        XCTAssertEqual(Passage.join(["我", "喜欢", "咖啡。", "你呢？"]), "我喜欢咖啡。你呢？")
        XCTAssertEqual(Passage.join(["“", "Hola", "!”"]), "“Hola!”")
        XCTAssertEqual(Passage.join(["Hola", "\u{10100}"]), "Hola\u{10100}")
        XCTAssertEqual(Passage.join(["Hola\u{00A0}", "mundo"]), "Hola\u{00A0}mundo")
        XCTAssertEqual(Passage.join(["", "Hello.", "", "Again."]), "Hello. Again.")
    }
    func testLateFragmentsRebuildEarlierPassageAndInvalidateEvidence() {
        var s = fixture()
        s.append(Fragment(id: "late", speaker: .user, text: " kanskje", startMS: 2100, endMS: 2500))
        XCTAssertEqual(s.assessments.count, 0)
        XCTAssertEqual(s.passages.count, 1)
    }
    func testOverlappingSpeakersRemainSeparate() {
        let fragments = [Fragment(speaker: .assistant, text: "Hei", startMS: 0, endMS: 500), Fragment(speaker: .user, text: "Hallo", startMS: 100, endMS: 400), Fragment(speaker: .assistant, text: "!", startMS: 500, endMS: 600)]
        let p = Transcript.passages(fragments)
        XCTAssertEqual(p.count, 2); XCTAssertEqual(p[0].text, "Hei!"); XCTAssertEqual(p[1].text, "Hallo")
    }
    func testVisibleMeaningCannotAwardIndependentRecall() {
        let s = fixture(supported: true)
        let valid = LearningEngine.validate(s.assessments[0], session: s)
        XCTAssertEqual(valid?.words[0].kind, .assisted)
        XCTAssertEqual(LearningEngine.project([s]).words[0].independentCount, 0)
    }
    func testImmediateImitationIsAssisted() {
        var s = fixture()
        s.fragments.insert(Fragment(speaker: .assistant, text: "Du gikk en tur?", startMS: 0, endMS: 500), at: 0)
        XCTAssertEqual(LearningEngine.validate(s.assessments[0], session: s)?.words[0].kind, .assisted)
    }
    func testEnglishCannotAwardNorwegianProduction() {
        var s = fixture(); s.assessments[0].words[0].language = "en"
        XCTAssertEqual(LearningEngine.validate(s.assessments[0], session: s)?.words.count, 0)
    }
    func testTypingCannotAwardIndependentSpokenRecall() {
        var s = fixture(); s.fragments[0].typed = true
        XCTAssertEqual(LearningEngine.validate(s.assessments[0], session: s)?.words[0].kind, .assisted)
    }
    func testFabricatedSourceAndQuotesAreRejected() {
        var s = fixture(); s.assessments[0].words[0].sourceIDs = ["invented"]
        XCTAssertEqual(LearningEngine.validate(s.assessments[0], session: s)?.words.count, 0)
        s = fixture(); s.assessments[0].words[0].quote = "Jeg kan fly."
        XCTAssertEqual(LearningEngine.validate(s.assessments[0], session: s)?.words.count, 0)
    }
    func testQuoteAcrossProviderWordBoundaryIsKept() {
        var session = SessionRecord(languageID: "es")
        session.append(Fragment(id: "f1", speaker: .user, text: "Me gusta", startMS: 0, endMS: 500))
        session.append(Fragment(id: "f2", speaker: .user, text: " el café", startMS: 600, endMS: 1200))
        let passage = session.passages[0]
        XCTAssertEqual(passage.text, "Me gusta el café")
        session.assessments = [Assessment(
            passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
            suggestedLevel: 1, nextGoal: "Sigue.", capability: "Expresses liking",
            words: [WordProposal(lemma: "gustar", meaning: "to like", form: "gusta",
                kind: .independent, confidence: 0.95, sourceIDs: ["f1", "f2"],
                quote: "Me gusta el café", language: "es")])]
        XCTAssertEqual(LearningEngine.validate(session.assessments[0], session: session)?.words.count, 1)
    }
    func testDuplicateAssessmentsNeverDoubleCredit() {
        var s = fixture(); s.assessments += s.assessments
        let projection = LearningEngine.project([s], now: s.startedAt)
        XCTAssertEqual(projection.words[0].independentCount, 1)
        XCTAssertEqual(projection.observationCount, 1)
    }
    func testInvalidAssessmentDoesNotBlockALaterValidOneForTheSamePassage() {
        var s = fixture()
        let valid = s.assessments[0]
        var invalid = valid
        invalid.revisionKey = "stale"
        invalid.createdAt = valid.createdAt.addingTimeInterval(-1)
        s.assessments = [invalid, valid]
        let projection = LearningEngine.project([s], now: s.startedAt)
        XCTAssertEqual(projection.observationCount, 1)
        XCTAssertEqual(projection.words.count, 1)
    }
    func testDuplicateWordProposalsNeverDoubleCredit() {
        var s = fixture(); s.assessments[0].words += s.assessments[0].words
        XCTAssertEqual(LearningEngine.project([s], now: s.startedAt).words[0].independentCount, 1)
    }
    func testSteadyRequiresSpacingAndDifferentContexts() {
        let first = fixture(), second = fixture(day: 2), third = fixture(day: 8, theme: "dinner")
        let p = LearningEngine.project([first, second, third], now: third.startedAt)
        XCTAssertEqual(p.words[0].bars, 3)
        let sameContext = LearningEngine.project([first, second, fixture(day: 8)], now: third.startedAt)
        XCTAssertEqual(sameContext.words[0].bars, 2)
        let massed = LearningEngine.project([first, fixture(day: 0.01), fixture(day: 0.02)], now: first.startedAt)
        XCTAssertLessThanOrEqual(massed.words[0].bars, 2)
    }
    func testStrengthFadesAndLapsesLowerIt() {
        let sessions = [fixture(), fixture(day: 2), fixture(day: 8, theme: "dinner")]
        let projected = LearningEngine.project(sessions, now: sessions.last!.startedAt.addingTimeInterval(30 * 86400))
        XCTAssertEqual(projected.words[0].bars, 2)
        let lapse = fixture(day: 9, kind: .lapse)
        XCTAssertEqual(LearningEngine.project(sessions + [lapse], now: lapse.startedAt).words[0].bars, 1)
    }
    func testCorrectedTranscriptRevokesEvidence() {
        var s = fixture(); s.correctFragment(id: s.fragments[0].id, text: "I went for a walk.")
        XCTAssertTrue(s.assessments.isEmpty)
        XCTAssertTrue(LearningEngine.project([s]).words.isEmpty)
    }
    func testCorrectedFragmentOnlyDropsAffectedTranslations() {
        var s = fixture()
        let id = s.fragments[0].id
        s.translations["English::\(id):0"] = "I went into the forest."
        s.translations["English::other:0"] = "Unrelated meaning"
        s.correctFragment(id: id, text: "I went for a walk.")
        XCTAssertNil(s.translations["English::\(id):0"])
        XCTAssertEqual(s.translations["English::other:0"], "Unrelated meaning")
    }
    func testArchiveRoundTripAndVersionGuard() throws {
        var archive = Archive(); archive.sessions = [fixture()]
        let decoded = try Archive.decode(archive.encoded())
        XCTAssertEqual(decoded.sessions.count, 1)
        archive.schemaVersion = 99
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
    }
    func testDuplicateSessionImportRejected() throws {
        var archive = Archive(); let s = fixture(); archive.sessions = [s,s]
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
    }
    func testArchiveRejectsNumbersThatCanCrashTheInterface() throws {
        var archive = Archive(); archive.sessions = [fixture()]
        archive.sessions[0].voiceSeconds = 1e308
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        archive.sessions[0].voiceSeconds = 0
        archive.sessions[0].searchCalls = Int.max
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        archive.sessions[0].searchCalls = 0
        archive.sessions[0].fragments[0].revision = Int.max
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        archive.sessions[0].fragments[0].revision = 0
        archive.sessions[0].inputTokens = -1
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        archive.sessions[0].inputTokens = 0
        archive.sessions[0].assessments[0].createdAt = Date(timeIntervalSinceReferenceDate: 1e308)
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
    }
    func testImportFileReadRejectsOversizedAndNonFileInputs() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let expected = try Archive().encoded()
        try expected.write(to: url)
        XCTAssertEqual(try Archive.readImportData(from: url), expected)
        let file = try FileHandle(forWritingTo: url)
        try file.truncate(atOffset: UInt64(Archive.maximumEncodedBytes) + 1)
        try file.close()
        XCTAssertThrowsError(try Archive.readImportData(from: url)) { XCTAssertEqual($0 as? ArchiveError, .tooLarge) }
        XCTAssertThrowsError(try Archive.readImportData(from: URL(string: "https://example.test/backup.json")!))
        XCTAssertThrowsError(try Archive.readImportData(from: FileManager.default.temporaryDirectory))
    }
    func testImportMergeCannotPersistAnArchiveThatFailsOnRelaunch() throws {
        var original = Archive(), incoming = Archive()
        original.sessions = (0..<10_000).map { _ in SessionRecord() }
        incoming.sessions = [SessionRecord()]
        XCTAssertThrowsError(try original.merging(incoming)) { XCTAssertEqual($0 as? ArchiveError, .tooLarge) }
        XCTAssertEqual(original.sessions.count, 10_000)
        incoming.sessions = [original.sessions[0]]
        XCTAssertEqual(try original.merging(incoming).sessions.count, 10_000)
        original.sessions = [SessionRecord(title: String(repeating: "a", count: Archive.maximumEncodedBytes / 2))]
        incoming.sessions = [SessionRecord(title: String(repeating: "b", count: Archive.maximumEncodedBytes / 2))]
        XCTAssertNoThrow(try Archive.decode(original.encoded()))
        XCTAssertNoThrow(try Archive.decode(incoming.encoded()))
        XCTAssertThrowsError(try original.merging(incoming)) { XCTAssertEqual($0 as? ArchiveError, .tooLarge) }
        XCTAssertEqual(original.sessions.count, 1)
    }
    func testImportMergePreservesLocalPreferencesAndValidatesNewEvidence() throws {
        var original = Archive(), incoming = Archive()
        original.preferences.meaningLanguage = "Spanish"
        original.preferences.aiConsentVersion = 1
        incoming.preferences.meaningLanguage = "English"
        var invalidEvidence = fixture()
        invalidEvidence.assessments[0].revisionKey = "changed"
        incoming.sessions = [invalidEvidence]
        let merged = try original.merging(incoming)
        XCTAssertEqual(merged.preferences.meaningLanguage, "Spanish")
        XCTAssertEqual(merged.preferences.aiConsentVersion, 1)
        XCTAssertTrue(merged.sessions[0].assessments.isEmpty)
        XCTAssertEqual(try Archive.decode(merged.encoded()).sessions.count, 1)
    }
    func testSourceLinksRejectNonHTTPSAndCredentials() {
        XCTAssertNil(SourceLink(title: "bad", url: "javascript:alert(1)").safeURL)
        XCTAssertNil(SourceLink(title: "bad", url: "https://user@example.com/page").safeURL)
        XCTAssertNotNil(SourceLink(title: "good", url: "https://www.nrk.no/").safeURL)
    }
    func testTwentyFourDistinctThemes() { XCTAssertEqual(Set(LanguageModule.norwegian.themes.map(\.id)).count, 24) }
}
