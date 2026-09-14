import XCTest
@testable import MuralCore

/// Runs the same archive fixture as Android's CrossPlatformFixtureTest so both cores stay interchangeable.
final class CrossPlatformFixtureTests: XCTestCase {
    private let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../shared/fixtures/cross-platform")

    private func source() throws -> Data { try Data(contentsOf: directory.appendingPathComponent("archive.json")) }
    private func expected() throws -> [String: Any] {
        let data = try Data(contentsOf: directory.appendingPathComponent("archive-expected.json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testReencodedArchiveKeepsEveryFieldOfTheSharedFixture() throws {
        let data = try source()
        let archive = try Archive.decode(data)
        let reencoded = try archive.encoded()
        XCTAssertEqual(try fieldPaths(data), try fieldPaths(reencoded))
        XCTAssertEqual(try Archive.decode(reencoded).sessions.map { $0.passages.map(\.text) }, archive.sessions.map { $0.passages.map(\.text) })
    }

    func testTranscriptPassagesMatchTheSharedFixture() throws {
        let archive = try Archive.decode(source())
        let passages = try XCTUnwrap(expected()["passages"] as? [String: [[String: Any]]])
        XCTAssertEqual(Set(passages.keys), Set(archive.sessions.map(\.id.uuidString)))
        for session in archive.sessions {
            let want = try XCTUnwrap(passages[session.id.uuidString])
            XCTAssertEqual(want.count, session.passages.count, session.id.uuidString)
            for (item, passage) in zip(want, session.passages) {
                XCTAssertEqual(item["speaker"] as? String, passage.speaker.rawValue)
                XCTAssertEqual(item["text"] as? String, passage.text)
                XCTAssertEqual(item["fragmentIDs"] as? [String], passage.fragments.map(\.id))
            }
        }
    }

    func testLearnerProjectionMatchesTheSharedFixture() throws {
        let archive = try Archive.decode(source())
        let fixture = try expected()
        let learner = try XCTUnwrap(fixture["learner"] as? [String: Any])
        let state = LearningEngine.project(
            archive.sessions,
            languageID: try XCTUnwrap(fixture["languageID"] as? String),
            hiddenWords: archive.preferences.hiddenWords,
            now: Date(timeIntervalSinceReferenceDate: try XCTUnwrap(fixture["now"] as? Double))
        )
        XCTAssertEqual(learner["challenge"] as? Int, state.challenge)
        XCTAssertEqual(learner["observationCount"] as? Int, state.observationCount)
        XCTAssertEqual(learner["nextGoal"] as? String, state.nextGoal)
        XCTAssertEqual(learner["capabilities"] as? [String], state.capabilities)
        let want = try XCTUnwrap(learner["words"] as? [[String: Any]])
        let words = state.words.sorted { $0.id < $1.id }
        XCTAssertEqual(want.compactMap { $0["id"] as? String }, words.map(\.id))
        for (item, word) in zip(want, words) {
            XCTAssertEqual(item["lemma"] as? String, word.lemma)
            XCTAssertEqual(item["meaning"] as? String, word.meaning)
            XCTAssertEqual(item["form"] as? String, word.form)
            XCTAssertEqual(item["example"] as? String, word.example)
            XCTAssertEqual(item["bars"] as? Int, word.bars)
            XCTAssertEqual(item["understandingCount"] as? Int, word.understandingCount)
            XCTAssertEqual(item["independentCount"] as? Int, word.independentCount)
            XCTAssertEqual(item["lastSeen"] as? Double, word.lastSeen.timeIntervalSinceReferenceDate)
            XCTAssertEqual(item["dueAt"] as? Double, word.dueAt.timeIntervalSinceReferenceDate)
        }
    }

    /// Collects every non-null field path; array indices and translation keys are data, not schema.
    private func fieldPaths(_ data: Data) throws -> Set<String> {
        var paths = Set<String>()
        func visit(_ value: Any, _ prefix: String) {
            if let object = value as? [String: Any] {
                for (key, child) in object where !(child is NSNull) {
                    let path = prefix.hasSuffix(".translations") ? prefix + ".{}" : prefix + "." + key
                    paths.insert(path)
                    visit(child, path)
                }
            } else if let array = value as? [Any] {
                array.forEach { visit($0, prefix + "[]") }
            }
        }
        visit(try JSONSerialization.jsonObject(with: data), "")
        return paths
    }
}
