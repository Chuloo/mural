import Foundation
import XCTest
@testable import MuralCore

final class ApplePurchaseRequestTests: XCTestCase, @unchecked Sendable {
    private let origin = URL(string: "https://api.mural.chat")!
    private actor Calls {
        private(set) var count = 0
        func increment() { count += 1 }
    }
    private func request(_ path: String, method: String = "GET") -> URLRequest {
        var result = URLRequest(url: origin.appendingPathComponent(path))
        result.httpMethod = method
        result.setValue("Bearer local-test-token", forHTTPHeaderField: "Authorization")
        result.setValue("local-idempotency-key", forHTTPHeaderField: "Idempotency-Key")
        if method == "POST" { result.httpBody = Data("{\"test\":true}".utf8) }
        return result
    }
    func testAccountAndConversationRequestsNeverFetchOrAttachAppleProof() async throws {
        let calls = Calls()
        let paths = ["/v1/auth/challenge", "/v1/auth/exchange", "/v1/auth/sign-out", "/v1/account",
                     "/v1/account/connect-google", "/v1/guest/minutes", "/v1/minutes/link-guest", "/v1/minutes",
                     "/v1/wallet", "/v1/live/capabilities", "/v1/live/sessions", "/v1/live/sessions/session",
                     "/v1/live/sessions/session/close", "/v1/live/requests/request/close", "/v1/live/sessions/session/helpers"]
        for path in paths {
            var original = request(path, method: path == "/v1/minutes" || path == "/v1/wallet" ? "GET" : "POST")
            original.setValue("stale-apple-proof", forHTTPHeaderField: "X-Mural-Apple-App-Transaction")
            let prepared = try await ApplePurchaseRequest.prepare(original, origin: origin, automatic: true) {
                await calls.increment()
                throw ManagedAccountError.unavailable
            }
            XCTAssertEqual(prepared.url, original.url, path)
            XCTAssertEqual(prepared.httpMethod, original.httpMethod, path)
            XCTAssertEqual(prepared.httpBody, original.httpBody, path)
            XCTAssertEqual(prepared.value(forHTTPHeaderField: "Authorization"), "Bearer local-test-token", path)
            XCTAssertEqual(prepared.value(forHTTPHeaderField: "Idempotency-Key"), "local-idempotency-key", path)
            XCTAssertNil(prepared.value(forHTTPHeaderField: "X-Mural-Apple-App-Transaction"), path)
        }
        let count = await calls.count
        XCTAssertEqual(count, 0)
    }
    func testCommerceRetainsVerifiedLiveAndSandboxProofWithoutChangingCheckout() async throws {
        for environment in ["live", "test"] {
            for path in ["/v1/minutes/products", "/v1/minutes/orders", "/v1/minutes/orders/order", "/v1/minutes/apple/recover"] {
                let original = request(path, method: path == "/v1/minutes/orders" || path == "/v1/minutes/apple/recover" ? "POST" : "GET")
                let prepared = try await ApplePurchaseRequest.prepare(original, origin: origin, automatic: true) {
                    AppleAppTransactionProof(environment: environment, signedAppTransaction: "verified-local-test-proof")
                }
                XCTAssertEqual(prepared.url, original.url)
                XCTAssertEqual(prepared.httpBody, original.httpBody)
                XCTAssertEqual(prepared.value(forHTTPHeaderField: "X-Mural-Apple-App-Transaction"), "verified-local-test-proof")
            }
        }
    }
    func testFailedCommerceProofCannotSendAnUnverifiedPurchaseRequest() async {
        for path in ["/v1/minutes/products", "/v1/minutes/orders", "/v1/minutes/orders/order", "/v1/minutes/apple/recover"] {
            do {
                _ = try await ApplePurchaseRequest.prepare(request(path), origin: origin, automatic: true) {
                    throw ManagedAccountError.unavailable
                }
                XCTFail("Commerce request escaped without proof: \(path)")
            } catch { XCTAssertEqual(error as? ManagedAccountError, .purchaseVerificationUnavailable) }
        }
    }
    func testUnknownOrUnboundedProofCannotSelectAnEnvironment() async {
        for proof in [AppleAppTransactionProof(environment: "xcode", signedAppTransaction: "proof"),
                      AppleAppTransactionProof(environment: "unknown", signedAppTransaction: "proof"),
                      AppleAppTransactionProof(environment: "live", signedAppTransaction: ""),
                      AppleAppTransactionProof(environment: "test", signedAppTransaction: String(repeating: "x", count: 16_385))] {
            do {
                _ = try await ApplePurchaseRequest.prepare(request("/v1/minutes/orders"), origin: origin, automatic: true) { proof }
                XCTFail("Invalid proof was accepted")
            } catch { XCTAssertEqual(error as? ManagedAccountError, .purchaseVerificationUnavailable) }
        }
    }
    func testProofNeverReachesOAuthOrAnotherOriginAndDoesNotLoadStoreKit() async throws {
        let calls = Calls()
        for destination in ["https://oauth2.googleapis.com/token", "https://sandbox-api.mural.chat/v1/minutes/orders",
                            "http://api.mural.chat/v1/minutes/orders", "https://api.mural.chat:8443/v1/minutes/orders",
                            "https://api.mural.chat.attacker.invalid/v1/minutes/orders", "https://user@api.mural.chat/v1/minutes/orders",
                            "https://api.mural.chat/v1/minutes/orders#other"] {
            var original = URLRequest(url: URL(string: destination)!)
            original.setValue("stale-apple-proof", forHTTPHeaderField: "X-Mural-Apple-App-Transaction")
            let prepared = try await ApplePurchaseRequest.prepare(original, origin: origin, automatic: true) {
                await calls.increment()
                return AppleAppTransactionProof(environment: "live", signedAppTransaction: "proof")
            }
            XCTAssertNil(prepared.value(forHTTPHeaderField: "X-Mural-Apple-App-Transaction"), destination)
            XCTAssertEqual(prepared.url, original.url)
        }
        let count = await calls.count
        XCTAssertEqual(count, 0)
    }
    func testProviderCancellationNeverBecomesPurchaseVerificationFailure() async {
        for error: any Error in [CancellationError(), ManagedAccountError.cancelled, URLError(.cancelled)] {
            do {
                _ = try await ApplePurchaseRequest.prepare(request("/v1/minutes/orders"), origin: origin, automatic: true) { throw error }
                XCTFail("Cancelled purchase request escaped")
            } catch { XCTAssertEqual(error as? ManagedAccountError, .cancelled) }
        }
    }
    func testCancelledCallerCannotPrepareARequestEvenWithoutAppleProof() async {
        let calls = Calls()
        let original = request("/v1/live/sessions", method: "POST")
        let origin = origin
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await ApplePurchaseRequest.prepare(original, origin: origin, automatic: true) {
                await calls.increment()
                return AppleAppTransactionProof(environment: "live", signedAppTransaction: "proof")
            }
        }
        do { _ = try await task.value; XCTFail("Cancelled caller prepared a session request") }
        catch { XCTAssertTrue(error is CancellationError) }
        let count = await calls.count
        XCTAssertEqual(count, 0)
    }
    func testCancellationWhileFetchingProofCannotSendACompletedPurchaseRequest() async {
        let original = request("/v1/minutes/orders", method: "POST")
        let origin = origin
        let task = Task {
            try await ApplePurchaseRequest.prepare(original, origin: origin, automatic: true) {
                withUnsafeCurrentTask { $0?.cancel() }
                return AppleAppTransactionProof(environment: "live", signedAppTransaction: "proof")
            }
        }
        do { _ = try await task.value; XCTFail("Purchase escaped after cancellation during proof loading") }
        catch { XCTAssertTrue(error is CancellationError) }
    }
}
