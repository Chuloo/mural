import XCTest
@testable import MuralCore

final class ConversationContinuationTests: XCTestCase {
    func testProviderDeadlineBeforeLocalTimerPreservesFreeContinuation() {
        XCTAssertTrue(ConversationContinuation.reachedFreeBoundary(hasFreeFunding: true, endReason: nil, deadlineReached: true))
        XCTAssertFalse(ConversationContinuation.reachedFreeBoundary(hasFreeFunding: true, endReason: nil, deadlineReached: false))
        XCTAssertFalse(ConversationContinuation.reachedFreeBoundary(hasFreeFunding: false, endReason: nil, deadlineReached: true))
        for reason in ["Ended by you", "App moved to background", "Inactivity"] {
            XCTAssertFalse(ConversationContinuation.reachedFreeBoundary(hasFreeFunding: true, endReason: reason, deadlineReached: true))
        }
        XCTAssertTrue(ConversationContinuation.reachedFreeBoundary(hasFreeFunding: true, endReason: "Time limit", deadlineReached: true))
    }

    func testRelaunchCheckpointRequiresSameOwnerLanguageAndArchivedBoundary() throws {
        let owner = UUID()
        var session = SessionRecord(languageID: "nb", themeID: "food")
        session.endedAt = .now; session.endReason = "Time limit"
        let checkpoint = ConversationContinuationCheckpoint(sessionID: session.id, accountID: owner)
        let restored = try JSONDecoder().decode(ConversationContinuationCheckpoint.self, from: JSONEncoder().encode(checkpoint))
        XCTAssertEqual(restored.recover(from: [session], accountID: owner, languageID: "nb")?.id, session.id)
        XCTAssertNil(restored.recover(from: [session], accountID: UUID(), languageID: "nb"))
        XCTAssertNil(restored.recover(from: [session], accountID: owner, languageID: "es"))
        XCTAssertNil(restored.recover(from: [], accountID: owner, languageID: "nb"))
        session.endReason = "Imported unfinished conversation"
        XCTAssertNil(restored.recover(from: [session], accountID: owner, languageID: "nb"))
    }
    func testContinuationKeepsRolesAndRecentContextWithinServerBudget() throws {
        let entries: [(Speaker, String)] = (0..<50).map { ($0.isMultiple(of: 2) ? .user : .assistant, "Message \($0)") }
        let history = ConversationContinuation.history(entries)
        XCTAssertEqual(history.count, 40)
        XCTAssertEqual(history.first?["role"] as? String, "user")
        XCTAssertEqual((history.last?["content"] as? [[String: String]])?.first?["text"], "Message 49")
        XCTAssertLessThanOrEqual(try JSONSerialization.data(withJSONObject: history).count, 6_000)
    }
    func testLongUnicodeAndEscapingStayWithinWireBudget() throws {
        let history = ConversationContinuation.history([(.user, String(repeating: "你好/\"", count: 4_000)), (.assistant, "继续。")])
        XCTAssertFalse(history.isEmpty)
        XCTAssertLessThanOrEqual(try JSONSerialization.data(withJSONObject: history).count, 6_000)
        XCTAssertEqual((history.last?["content"] as? [[String: String]])?.first?["text"], "继续。")
        XCTAssertTrue(ConversationContinuation.history([(.user, "\u{0001}")]).isEmpty)
    }
}
