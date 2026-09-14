import XCTest
@testable import MuralCore

@MainActor final class FinalAssessmentTests: XCTestCase {
    @MainActor private final class Provider {
        var pending: [(SessionRecord, Passage, CheckedContinuation<FinalAssessmentResult, Error>)] = []
        func assess(_ session: SessionRecord, _ passage: Passage) async throws -> FinalAssessmentResult {
            // Ignore cancellation deliberately, like a response already in flight.
            try await withCheckedThrowingContinuation { pending.append((session, passage, $0)) }
        }
        func finish() {
            let (session, passage, continuation) = pending.removeFirst()
            let assessment = Assessment(passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
                suggestedLevel: 1, nextGoal: "Una pregunta más.", capability: "Names an object",
                words: [WordProposal(lemma: "la radio", meaning: "radio", form: "radio", kind: .independent, confidence: 0.95,
                    sourceIDs: passage.fragments.map(\.id), quote: passage.text, language: session.languageID)])
            continuation.resume(returning: FinalAssessmentResult(sessionID: session.id, languageID: session.languageID,
                assessment: assessment, inputTokens: 100, outputTokens: 20))
        }
    }
    private func ended(_ languageID: String = "es") -> SessionRecord {
        var session = SessionRecord(languageID: languageID)
        session.append(Fragment(speaker: .user, text: "radio", startMS: 0, endMS: 1000))
        session.endedAt = .now
        return session
    }
    private func waitUntil(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        let limit = Date().addingTimeInterval(1)
        while !condition(), Date() < limit { try? await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition(), file: file, line: line)
    }

    func testResetAndNewLanguageDoNotRedirectResultsToTheNewSession() async {
        let old = ended(), new = ended("fr"), provider = Provider()
        var records = [old.id: old, new.id: new]
        var visible: SessionRecord? = old
        let queue = FinalAssessmentQueue(assess: provider.assess)
        queue.onResult = { result in
            if let updated = result.applying(to: records[result.sessionID]) { records[updated.id] = updated }
            if visible?.id == result.sessionID { visible = records[result.sessionID] }
        }
        XCTAssertTrue(queue.submit(old))
        await waitUntil { provider.pending.count == 1 }
        visible = nil // Automatic reset.
        visible = new // A fresh conversation, after switching language.
        provider.finish()
        await waitUntil { !queue.isPending(old.id) }
        XCTAssertEqual(records[old.id]?.assessments.count, 1)
        XCTAssertEqual(records[old.id]?.inputTokens, 100)
        XCTAssertTrue(records[new.id]!.assessments.isEmpty)
        XCTAssertEqual(visible?.id, new.id)
        XCTAssertTrue(visible!.assessments.isEmpty)
    }

    func testTagalogFinalAssessmentStaysWithItsOriginalSessionAfterSwitching() async {
        for (oldID, newID) in [("tl", "es"), ("pt", "tl")] {
            let old = ended(oldID), new = ended(newID), provider = Provider()
            var records = [old.id: old, new.id: new]
            let queue = FinalAssessmentQueue(assess: provider.assess)
            queue.onResult = { result in
                if let updated = result.applying(to: records[result.sessionID]) { records[updated.id] = updated }
            }
            XCTAssertTrue(queue.submit(old))
            await waitUntil { provider.pending.count == 1 }
            provider.finish()
            await waitUntil { !queue.isPending(old.id) }
            XCTAssertEqual(records[old.id]?.assessments.first?.words.first?.language, oldID)
            XCTAssertEqual(records[old.id]?.inputTokens, 100)
            XCTAssertTrue(records[new.id]!.assessments.isEmpty)
            XCTAssertTrue(LearningEngine.project([records[new.id]!], languageID: newID).words.isEmpty)
        }
    }

    func testTagalogResultCannotApplyToARecordWithAnotherLanguage() {
        let original = ended("tl")
        let passage = original.passages[0]
        let assessment = Assessment(passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
            suggestedLevel: 1, nextGoal: "Magtanong pa.", capability: "Names an object", words: [])
        let result = FinalAssessmentResult(sessionID: original.id, languageID: "fil", assessment: assessment)
        XCTAssertNil(result.applying(to: original))
        let correct = FinalAssessmentResult(sessionID: original.id, languageID: "tl", assessment: assessment)
        XCTAssertNotNil(correct.applying(to: original))
        XCTAssertNil(correct.applying(to: ended("es")))
    }

    func testDeletedSessionIsNeverRecreatedByALateResult() async {
        let session = ended(), provider = Provider()
        var records = [session.id: session]
        let queue = FinalAssessmentQueue(assess: provider.assess)
        queue.onResult = { result in
            if let updated = result.applying(to: records[result.sessionID]) { records[updated.id] = updated }
        }
        queue.submit(session)
        await waitUntil { provider.pending.count == 1 }
        records.removeValue(forKey: session.id)
        provider.finish()
        await waitUntil { !queue.isPending(session.id) }
        XCTAssertTrue(records.isEmpty)
    }

    func testCancellationAndDeadlineRejectResponsesThatIgnoreCancellation() async {
        for explicitlyCancel in [true, false] {
            let session = ended(), provider = Provider()
            var received = 0
            let queue = FinalAssessmentQueue(timeout: 0.03, assess: provider.assess)
            queue.onResult = { _ in received += 1 }
            queue.submit(session)
            await waitUntil { provider.pending.count == 1 }
            if explicitlyCancel { queue.cancel(session.id) }
            await waitUntil { !queue.isPending(session.id) }
            provider.finish()
            try? await Task.sleep(for: .milliseconds(5))
            XCTAssertEqual(received, 0)
        }
    }

    func testCorrectedTranscriptRejectsTheOriginalAssessment() async {
        var session = ended()
        let provider = Provider(), queue = FinalAssessmentQueue(assess: provider.assess)
        var applied = false
        queue.onResult = { result in applied = result.applying(to: session) != nil }
        queue.submit(session)
        await waitUntil { provider.pending.count == 1 }
        session.correctFragment(id: session.fragments[0].id, text: "televisión")
        provider.finish()
        await waitUntil { !queue.isPending(session.id) }
        XCTAssertFalse(applied)
    }

    func testAlreadyAssessedAndPendingPassagesDoNotStartDuplicateRequests() async {
        var session = ended()
        let provider = Provider(), queue = FinalAssessmentQueue(assess: provider.assess)
        queue.onResult = { result in session = result.applying(to: session)! }
        XCTAssertTrue(queue.submit(session))
        XCTAssertFalse(queue.submit(session))
        await waitUntil { provider.pending.count == 1 }
        provider.finish()
        await waitUntil { !queue.isPending(session.id) }
        XCTAssertFalse(queue.submit(session))
        XCTAssertEqual(session.assessments.count, 1)
        XCTAssertEqual(session.outputTokens, 20)
    }
}
