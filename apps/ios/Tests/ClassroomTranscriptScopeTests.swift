import XCTest
@testable import MuralCore

final class ClassroomTranscriptScopeTests: XCTestCase {
    func testEmptyStartedTurnStaysWithOldLessonAfterLateCompletion() throws {
        let event: [String: Any] = ["type": "turn.created", "turn": ["id": "old", "role": "assistant", "transcript": ""]]
        let id = try XCTUnwrap(ClassroomTranscriptScope.startedTurnID(event))
        var transcript = SubscriptionTranscript(), scope = ClassroomTranscriptScope()
        scope.observe(id)
        XCTAssertNil(transcript.consume(event, elapsedMS: 1))
        scope.beginTransition(); scope.confirmTransition()
        let late = try XCTUnwrap(transcript.consume(["type": "turn.done", "turn": ["id": "old", "role": "assistant", "transcript": "Old word"]], elapsedMS: 4))
        scope.observe(late.id)
        XCTAssertFalse(scope.contains(late.id))
        var archive = SessionRecord(languageID: "en")
        SubscriptionTranscript.apply(late, to: &archive, meaningVisible: true)
        XCTAssertEqual(archive.fragments.first?.text, "Old word")
        scope.observe("new"); XCTAssertTrue(scope.contains("new"))
    }
    func testPendingTurnsCannotBecomeNewLessonContextAndRejectionRetainsOldScope() {
        var scope = ClassroomTranscriptScope()
        scope.observe("old"); scope.beginTransition(); scope.observe("uncertain")
        XCTAssertFalse(scope.contains("uncertain"))
        scope.cancelTransition()
        XCTAssertTrue(scope.contains("old")); XCTAssertFalse(scope.contains("uncertain"))
        scope.beginTransition(); scope.observe("uncertain-2"); scope.confirmTransition()
        XCTAssertFalse(scope.contains("old")); XCTAssertFalse(scope.contains("uncertain-2"))
        scope.observe("new"); XCTAssertTrue(scope.contains("new"))
        scope.observe("old"); XCTAssertFalse(scope.contains("old"))
    }
    func testMalformedTurnDoesNotCreateScope() {
        for role in ["tool", "developer", ""] {
            XCTAssertNil(ClassroomTranscriptScope.startedTurnID(["type": "turn.created", "turn": ["id": "x", "role": role]]))
        }
        XCTAssertNil(ClassroomTranscriptScope.startedTurnID(["type": "turn.done", "turn": ["id": "x", "role": "user"]]))
    }
    func testArticleVoiceNavigationWithPunctuationAndNegativeExamples() {
        XCTAssertEqual(ArticleVoiceCommand.parse("下一段。"), .next)
        XCTAssertEqual(ArticleVoiceCommand.parse(" Tôi đọc xong rồi! "), .comprehension)
        XCTAssertEqual(ArticleVoiceCommand.parse("Explain this paragraph."), .explain)
        for value in ["不要下一段", "What does next paragraph mean?", "He said next paragraph", "我还没读完了"] {
            XCTAssertNil(ArticleVoiceCommand.parse(value))
        }
    }
}
