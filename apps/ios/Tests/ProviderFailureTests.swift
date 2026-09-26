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
    func testSpecificBillingCodesAreNotMistakenForTemporaryRateLimits() {
        let cases: [(String, ProviderFailureKind)] = [
            ("credit_balance_exhausted", .creditExhausted),
            ("organization_spend_limit_exceeded", .spendLimit),
            ("project_spend_limit_exceeded", .spendLimit),
            ("organization_usage_limit_exceeded", .usageLimit),
            ("insufficient_quota", .quota),
            ("slow_down", .rateLimit)
        ]
        for (code, expected) in cases {
            let body = Data("{\"error\":{\"code\":\"\(code)\",\"message\":\"private billing details\"}}".utf8)
            let failure = ProviderFailure(status: 429, body: body)
            XCTAssertEqual(failure.kind, expected, code)
            XCTAssertFalse(failure.localizedDescription.contains("private billing details"))
            XCTAssertEqual(ProviderFailure(status: 401, body: body).kind, .authentication)
        }
    }
    func testRealtimeBillingFailureUsesTheSameSafeRecovery() {
        XCTAssertEqual(ProviderFailure.fromRealtime(code: "credit_balance_exhausted")?.kind, .creditExhausted)
        XCTAssertEqual(ProviderFailure.fromRealtime(code: "project_spend_limit_exceeded")?.kind, .spendLimit)
        XCTAssertEqual(ProviderFailure.fromRealtime(code: "invalid_api_key")?.kind, .authentication)
        XCTAssertNil(ProviderFailure.fromRealtime(code: "slow_down"))
        XCTAssertNil(ProviderFailure.fromRealtime(code: "private_api_key_here"))
    }
}
