import XCTest
@testable import MuralCore

final class ProviderFailureTests: XCTestCase {
    func testCreditAndTemporaryLimitsGiveDifferentRecoveryAdvice() {
        let quota = ProviderFailure(status: 429, body: Data(#"{"error":{"code":"insufficient_quota","message":"private billing data"}}"#.utf8), reference: "req_support")
        let rate = ProviderFailure(status: 429, body: Data(#"{"error":{"code":"rate_limit_exceeded"}}"#.utf8))
        XCTAssertEqual(quota.kind, .quota)
        XCTAssertEqual(rate.kind, .rateLimit)
        XCTAssertTrue(quota.errorDescription!.contains("billing"))
        XCTAssertTrue(rate.errorDescription!.contains("Wait"))
        XCTAssertFalse(quota.errorDescription!.contains("private"))
        XCTAssertTrue(quota.errorDescription!.contains("req_support"))
    }
    func testMalformedAndUntrustedProviderDetailsStayOutOfTheInterface() {
        for body in ["not json", #"{"error":{"code":"secret_value","message":"private"}}"#, String(repeating: "x", count: 16_385)] {
            let error = ProviderFailure(status: 503, body: Data(body.utf8), reference: "private\nheader")
            XCTAssertEqual(error.kind, .unavailable)
            XCTAssertNil(error.code); XCTAssertNil(error.reference)
            XCTAssertFalse(error.errorDescription!.contains("private"))
        }
        XCTAssertEqual(ProviderFailure(status: 401, body: Data(#"{"error":{"code":"insufficient_quota"}}"#.utf8)).kind, .authentication)
        XCTAssertEqual(ProviderFailure(status: 403).kind, .modelAccess)
    }
    func testProviderNameIsRenderedInSafeRecoveryAdvice() {
        let error = ProviderFailure(status: 401, providerName: "Google AI Studio")
        XCTAssertTrue(error.errorDescription!.contains("Google AI Studio"))
        XCTAssertFalse(error.errorDescription!.contains("(providerName)"))
    }
}
