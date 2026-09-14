import XCTest
@testable import MuralCore

final class OgdenPracticeSessionTests: XCTestCase {
    func testEveryMixedLevelRequiresOneAnswerForEachOfItsTenWords() throws {
        let c = try OgdenCurriculum.load()
        for level in OgdenPractice.levels(curriculum: c) {
            var run = OgdenPracticeSession.make(wordIDs: level.wordIDs, type: nil, curriculum: c)
            XCTAssertEqual(run.questions.count, 10)
            let snapshot = run.questions
            run.advance()
            XCTAssertEqual(run.position, 0)
            while let q = run.current {
                XCTAssertNotNil(run.submit(q.answer))
                XCTAssertNil(run.submit(q.answer))
                XCTAssertEqual(run.questions, snapshot)
                run.advance()
            }
            XCTAssertEqual(run.score, 10)
            XCTAssertTrue(run.covers(level))
        }
    }
    func testIncorrectAnswerIsFinalForThisQuestionAndPartialPracticeCannotUnlock() throws {
        let c = try OgdenCurriculum.load()
        let level = OgdenPractice.levels(curriculum: c)[0]
        var run = OgdenPracticeSession.make(wordIDs: level.wordIDs, type: .meaning, curriculum: c)
        XCTAssertEqual(run.submit("wrong"), false)
        XCTAssertNil(run.submit(run.current!.answer))
        XCTAssertEqual(run.score, 0)
        XCTAssertFalse(run.covers(level))
        var empty = OgdenPracticeSession(questions: [])
        empty.advance(); XCTAssertFalse(empty.finished); XCTAssertNil(empty.submit("answer"))
    }
    func testAllFiveFocusedTypesHaveRealQuestionsWithoutSubstitution() throws {
        let c = try OgdenCurriculum.load()
        for type in OgdenPracticeType.allCases {
            let run = OgdenPracticeSession.make(wordIDs: c.words.map(\.id), type: type, curriculum: c)
            XCTAssertFalse(run.questions.isEmpty, type.rawValue)
            XCTAssertTrue(run.questions.allSatisfy { $0.type == type })
        }
    }
}
