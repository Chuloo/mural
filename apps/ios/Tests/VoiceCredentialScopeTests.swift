import XCTest
@testable import MuralCore

final class VoiceCredentialScopeTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1000)
    func testLockedDeviceRequiresAnExistingVoiceScope() {
        var scope = VoiceCredentialScope()
        XCTAssertNil(scope.credential(protectedDataAvailable: false, now: start) { XCTFail("Must not read locked storage"); return "fixture" })
        scope.begin(key: "fixture", now: start)
        XCTAssertEqual(scope.credential(protectedDataAvailable: false, now: start) { nil }, "fixture")
        XCTAssertNil(scope.credential(protectedDataAvailable: false, now: start.addingTimeInterval(65 * 60)) { nil })
    }
    func testUnlockedDeletionAndReplacementAreRespectedWhileLocked() {
        var scope = VoiceCredentialScope()
        scope.begin(key: "first", now: start)
        XCTAssertEqual(scope.credential(protectedDataAvailable: true, now: start) { "replacement" }, "replacement")
        XCTAssertEqual(scope.credential(protectedDataAvailable: false, now: start) { nil }, "replacement")
        XCTAssertNil(scope.credential(protectedDataAvailable: true, now: start) { nil })
        XCTAssertNil(scope.credential(protectedDataAvailable: false, now: start) { nil })
    }
    func testEndHasBoundedFinalHelperGraceAndNextConversationOwnsItsScope() {
        var scope = VoiceCredentialScope()
        scope.begin(key: "first", now: start)
        scope.end(now: start.addingTimeInterval(10))
        XCTAssertEqual(scope.credential(protectedDataAvailable: false, now: start.addingTimeInterval(69)) { nil }, "first")
        XCTAssertNil(scope.credential(protectedDataAvailable: false, now: start.addingTimeInterval(70)) { nil })
        scope.begin(key: "next", now: start.addingTimeInterval(80))
        XCTAssertEqual(scope.credential(protectedDataAvailable: false, now: start.addingTimeInterval(150)) { nil }, "next")
        scope.clear()
        XCTAssertNil(scope.credential(protectedDataAvailable: false, now: start.addingTimeInterval(150)) { nil })
    }
}
