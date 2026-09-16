import XCTest
@testable import MuralCore

final class SpeechTurnTests: XCTestCase {
    func testDetectorWaitsForOnsetAndALearnersPauseAndDropsClicks() {
        var detector = SpeechDetector()
        func feed(_ level: Double, _ frames: Int) -> SpeechDetector.Event {
            var last = SpeechDetector.Event.none
            for _ in 0..<frames { last = detector.feed(level) }
            return last
        }
        _ = feed(0.002, 50)
        XCTAssertEqual(feed(0.08, 3), .start)
        _ = feed(0.08, 30)
        XCTAssertEqual(feed(0.002, 50), .none, "a one-second pause is still the same turn")
        XCTAssertTrue(detector.active)
        _ = feed(0.08, 10)
        XCTAssertEqual(feed(0.002, 60), .end)
        XCTAssertFalse(detector.active)
        _ = feed(0.08, 3)
        XCTAssertEqual(feed(0.002, 60), .discard, "a click is not a turn")
    }

    func testGuidanceQueuesOneReplyAndSurvivesAFailedReply() {
        var guidance = TurnGuidance(limit: 3)
        let greet = guidance.add("greet", respond: true)
        let checkIn = guidance.add("check in", respond: true)
        let context = guidance.add("context", respond: false)
        XCTAssertTrue(greet)
        XCTAssertFalse(checkIn, "a second request while one is pending")
        XCTAssertFalse(context)
        let used = guidance.begin()
        XCTAssertFalse(guidance.replyPending)
        XCTAssertEqual(used, ["greet", "check in", "context"])
        XCTAssertTrue(TurnGuidance.prompt(used).hasSuffix("greet\ncheck in\ncontext"))
        let retry = guidance.begin()
        XCTAssertEqual(retry, used, "a failed reply consumes nothing")
        _ = guidance.add("newer", respond: false)
        guidance.consumed(used)
        let next = guidance.begin()
        XCTAssertEqual(next, ["newer"])
        XCTAssertEqual(TurnGuidance.prompt([]), "")
    }

    func testWavHeaderDescribesSixteenKilohertzMonoPCM() {
        let wav = Wav.encode([0, 1, -1, 32_767], sampleRate: 16_000)
        XCTAssertEqual(wav.count, 52)
        XCTAssertEqual(String(decoding: wav[0..<4], as: UTF8.self), "RIFF")
        XCTAssertEqual(String(decoding: wav[36..<40], as: UTF8.self), "data")
        XCTAssertEqual(wav[24], 0x80); XCTAssertEqual(wav[25], 0x3E) // 16 000 Hz, little-endian
        XCTAssertEqual(Array(wav[44..<48]), [0, 0, 1, 0])
        XCTAssertEqual(Wav.level([]), 0)
        XCTAssertEqual(Wav.level([16_384, -16_384]), 0.5, accuracy: 0.0001)
    }

    func testEndpointURLMustBeCredentialFreeHTTPS() {
        XCTAssertEqual(CustomEndpointURL.parse(" https://example.com/v1 ")?.absoluteString, "https://example.com/v1/")
        XCTAssertEqual(CustomEndpointURL.parse("https://example.com")?.absoluteString, "https://example.com/")
        for bad in ["http://example.com/v1", "https://user:pass@example.com/v1", "https://example.com/v1?x=1", "https://example.com/#x", "example.com", ""] {
            XCTAssertNil(CustomEndpointURL.parse(bad), bad)
        }
    }
}
