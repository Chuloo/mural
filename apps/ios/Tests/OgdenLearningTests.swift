import XCTest
@testable import MuralCore

final class OgdenLearningTests: XCTestCase {
    func testLevelsAreTenWordsAndProgressivelyUnlock() throws {
        let c = try OgdenCurriculum.load()
        let levels = OgdenPractice.levels(curriculum: c)
        XCTAssertEqual(levels.count, 85)
        XCTAssertTrue(levels.allSatisfy { $0.wordIDs.count <= 10 })
        XCTAssertTrue(levels.first!.wordIDs.count == 10)
        var state = OgdenLearningState()
        XCTAssertTrue(state.isUnlocked(levels[0], levels: levels))
        XCTAssertFalse(state.isUnlocked(levels[1], levels: levels))
        state.completeLevel(levels[0], correct: 10, total: 10, date: Date())
        XCTAssertTrue(state.isUnlocked(levels[1], levels: levels))
        for category in OgdenCategory.allCases {
            XCTAssertTrue(state.isUnlocked(try XCTUnwrap(levels.first { $0.category == category }), levels: levels))
        }
    }

    func testCompletionRequiresExactLevelSizeAndMistakesFollowSourceRule() throws {
        let c = try OgdenCurriculum.load(); let level = OgdenPractice.levels(curriculum: c)[0]; var state = OgdenLearningState()
        state.completeLevel(level, correct: 1, total: 1); XCTAssertFalse(state.completedLevelIDs.contains(level.id))
        state.completeLevel(level, correct: level.wordIDs.count, total: level.wordIDs.count); XCTAssertTrue(state.completedLevelIDs.contains(level.id))
        let id = level.wordIDs[0]
        for _ in 0..<3 { state.recordAnswer(wordID: id, correct: true, date: Date(timeIntervalSince1970: 1_700_000_000)) }
        state.recordAnswer(wordID: id, correct: false, date: Date(timeIntervalSince1970: 1_700_000_000)); XCTAssertTrue(state.wrongWordIDs.contains(id))
        state.recordAnswer(wordID: id, correct: true, date: Date(timeIntervalSince1970: 1_700_000_000)); XCTAssertFalse(state.wrongWordIDs.contains(id))
    }

    func testStreakHasYesterdayGraceButBreaksAcrossGap() throws {
        var state = OgdenLearningState(); let base = Date(timeIntervalSince1970: 1_700_000_000)
        state.recordAnswer(wordID: "english:ogden:come", correct: true, date: base)
        state.recordAnswer(wordID: "english:ogden:get", correct: true, date: base.addingTimeInterval(86_400))
        XCTAssertEqual(state.streak(at: base.addingTimeInterval(86_400)), 2)
        XCTAssertEqual(state.streak(at: base.addingTimeInterval(2 * 86_400)), 2)
        XCTAssertEqual(state.streak(at: base.addingTimeInterval(4 * 86_400)), 0)
    }

    func testProgressFavoritesMistakesAndStreakRestore() throws {
        let c = try OgdenCurriculum.load(); let word = c.words[0]
        var state = OgdenLearningState(); state.toggleFavorite(wordID: word.id)
        state.recordAnswer(wordID: word.id, correct: false, date: Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertTrue(state.favorites.contains(word.id)); XCTAssertTrue(state.wrongWordIDs.contains(word.id))
        XCTAssertEqual(state.stats[word.id]?.attempts, 1)
        let data = try JSONEncoder().encode(state); let restored = try JSONDecoder().decode(OgdenLearningState.self, from: data)
        XCTAssertEqual(restored, state)
        XCTAssertEqual(state.streak(at: Date(timeIntervalSince1970: 1_700_000_000)), 0)
    }

    func testQuestionTypesHaveSafeAnswers() throws {
        let c = try OgdenCurriculum.load(); let word = c.words.first!
        var available = Set<OgdenPracticeType>()
        for type in OgdenPracticeType.allCases {
            guard let q = OgdenPractice.question(type: type, word: word, curriculum: c) else { continue }; available.insert(type)
            XCTAssertTrue(q.options.contains(q.answer)); XCTAssertTrue(q.matches(q.answer)); XCTAssertFalse(q.matches("definitely-not-answer"))
            XCTAssertEqual(q.options.filter { q.matches($0) }.count, 1)
        }
        XCTAssertTrue(available.isSuperset(of: [.listen, .meaning, .cloze, .spelling]))
        let spelling = try XCTUnwrap(OgdenPractice.question(type: .spelling, word: c.words.first(where: { $0.spellingUk != $0.spellingUs })!, curriculum: c))
        XCTAssertTrue(spelling.matches(c.words.first(where: { $0.id == spelling.wordID })!.spellingUk))
    }

    func testQuestionsCoverEveryWordAndClozeUsesTokenBoundary() throws {
        let c = try OgdenCurriculum.load()
        for word in c.words {
            XCTAssertNotNil(OgdenPractice.question(type: .listen, word: word, curriculum: c))
            XCTAssertNotNil(OgdenPractice.question(type: .meaning, word: word, curriculum: c))
            XCTAssertNotNil(OgdenPractice.question(type: .spelling, word: word, curriculum: c))
            if let q = OgdenPractice.question(type: .cloze, word: word, curriculum: c) { XCTAssertTrue(q.prompt.contains("_____")) }
        }
        let plural = try XCTUnwrap(c.words.first(where: { $0.term == "plant" }))
        XCTAssertNil(OgdenPractice.question(type: .cloze, word: plural, curriculum: c))
        XCTAssertGreaterThan(c.words.filter { OgdenPractice.question(type: .synonym, word: $0, curriculum: c) != nil }.count, 5)
    }

    func testInvalidStateRejectsUnknownWord() throws {
        let c = try OgdenCurriculum.load(); var state = OgdenLearningState()
        state.stats["unknown"] = OgdenWordStats(attempts: 1, correct: 0, mastery: 0)
        XCTAssertFalse(state.isValid(c: c))
    }

    func testWholeArchivePreservesLearningAndIndependentPreferences() throws {
        let c = try OgdenCurriculum.load()
        var archive = Archive(), state = OgdenLearningState(), avatar = AvatarSelection()
        avatar.mode = .animal; avatar.index = 2
        state.accent = "uk"; state.traditionalChinese = true
        state.toggleFavorite(wordID: c.words[0].id)
        state.recordAnswer(wordID: c.words[0].id, correct: false)
        state.completeLevel(OgdenPractice.levels(curriculum: c)[0], correct: 0, total: 10)
        archive.preferences.ogdenLearning = state
        archive.preferences.avatar = avatar; archive.preferences.orbSkinID = "prism"
        archive.preferences.conversationTeachingLanguage = "vi"
        let restored = try Archive.decode(archive.encoded())
        XCTAssertEqual(restored.preferences.ogdenLearning, state)
        XCTAssertEqual(restored.preferences.avatar, avatar)
        XCTAssertEqual(restored.preferences.orbSkinID, "prism")
        XCTAssertEqual(restored.preferences.conversationTeachingLanguage, "vi")
        archive.preferences.ogdenLearning = nil
        XCTAssertNil(try Archive.decode(archive.encoded()).preferences.ogdenLearning)
    }

    func testInvalidProgressRejectsEntireArchive() throws {
        let c = try OgdenCurriculum.load(), id = "english:ogden:come"
        var cases: [OgdenLearningState] = []
        var invalid = OgdenLearningState(); invalid.studyDays = ["2026-02-30"]; cases.append(invalid)
        invalid = OgdenLearningState(); invalid.stats[id] = .init(attempts: 1, correct: 2); cases.append(invalid)
        invalid = OgdenLearningState(); invalid.stats[id] = .init(attempts: Int.max, correct: 0); cases.append(invalid)
        invalid = OgdenLearningState(); invalid.mistakes = ["unknown"]; cases.append(invalid)
        invalid = OgdenLearningState(); invalid.completedLevelIDs = ["unknown"]; cases.append(invalid)
        for state in cases {
            XCTAssertFalse(state.isValid(c: c))
            var archive = Archive(); archive.preferences.ogdenLearning = state
            XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        }
    }
}
