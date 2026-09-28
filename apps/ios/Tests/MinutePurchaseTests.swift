import XCTest
import StoreKit
@testable import MuralCore

final class MinutePurchaseTests: XCTestCase {
    func testSharedMinutesPresentationFixtures() throws {
        struct Fixture: Decodable { let name: String; let text: String; let presentation: MuralMinutesPresentation }
        let path = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../shared/fixtures/cross-platform/minutes-presentation.json")
        for fixture in try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: path)) {
            try fixture.presentation.validate()
            XCTAssertEqual(fixture.presentation.displayText, fixture.text, fixture.name)
        }
    }
    private func offer(_ changes: [String: Any] = [:]) throws -> MinuteOffer {
        var fields: [String: Any] = ["sku": "small-us", "providerProduct": "chat.mural.ios.minutes.small.v1",
            "currency": "usd", "currencyExponent": 2, "totalMinor": 700,
            "estimatedMilliseconds": 2_214_000, "estimateRateVersion": "usd-0.10-v1",
            "scheduleVersion": "us-v1", "storefront": "USA", "environment": "test"]
        fields.merge(changes) { _, new in new }
        return try JSONDecoder().decode(MinuteOffer.self, from: JSONSerialization.data(withJSONObject: fields))
    }
    func testQuantityUsesCombinedWholeMinutesAndExactCheckoutPrice() throws {
        let value = try offer()
        for (quantity, minutes, minor) in [(1, 36, 700), (2, 73, 1400), (10, 369, 7000)] {
            XCTAssertEqual(try value.minutes(quantity: quantity), minutes)
            XCTAssertEqual(try value.total(quantity: quantity), minor)
        }
        for quantity in [Int.min, -1, 0, 11, Int.max] { XCTAssertThrowsError(try value.total(quantity: quantity)) }
    }
    func testRejectsUnsupportedStorefrontCurrencyAndUnboundedPrices() throws {
        for fields: [String: Any] in [["totalMinor": 0], ["totalMinor": Int.max], ["estimatedMilliseconds": Int.max],
            ["currencyExponent": 3], ["storefront": "NOR"], ["currency": "nok"], ["environment": "xcode"],
            ["providerProduct": "unrelated.product"], ["scheduleVersion": ""]] {
            XCTAssertThrowsError(try offer(fields).validate())
        }
        try offer(["storefront": "NOR", "currency": "nok", "totalMinor": 8900]).validate()
    }
    func testInterruptedCreatePreservesOwnerKeyAndTermsAcrossRestart() throws {
        let value = try offer(), owner = UUID()
        let attempt = try ApplePurchaseAttempt(accountID: owner, offer: value, quantity: 2)
        let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONEncoder().encode(attempt))
        XCTAssertEqual(restored, attempt)
        XCTAssertEqual(restored.accountID, owner)
        XCTAssertTrue(restored.canResumeCheckout)
        XCTAssertTrue(restored.matches(value, quantity: 2))
        XCTAssertFalse(restored.matches(value, quantity: 1))
        XCTAssertFalse(try restored.matches(offer(["scheduleVersion": "us-v2"]), quantity: 2))
    }
    func testSubmittedAndPendingPurchasesCannotLaunchAgainAfterRestart() throws {
        var attempt = try ApplePurchaseAttempt(accountID: UUID(), offer: offer(), quantity: 10)
        attempt.orderID = UUID()
        for phase in [ApplePurchaseAttempt.Phase.submitted, .awaitingApproval] {
            attempt.phase = phase
            let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONEncoder().encode(attempt))
            XCTAssertFalse(restored.canResumeCheckout)
            XCTAssertEqual(restored.orderID, attempt.orderID)
        }
    }
    func testPreparingCheckoutReusesUnchangedOrderButReplacesStaleTerms() throws {
        let originalOffer = try offer(), owner = UUID()
        var attempt = try ApplePurchaseAttempt(accountID: owner, offer: originalOffer, quantity: 2)
        attempt.orderID = UUID()
        let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONEncoder().encode(attempt))
        XCTAssertEqual(try restored.preparingCheckout(for: originalOffer, quantity: 2), attempt)
        for (selected, quantity) in [(try offer(["scheduleVersion": "us-v2"]), 2),
            (try offer(["storefront": "NOR", "currency": "nok", "totalMinor": 8900]), 2), (originalOffer, 1)] {
            let replacement = try restored.preparingCheckout(for: selected, quantity: quantity)
            XCTAssertEqual(replacement.accountID, owner)
            XCTAssertNotEqual(replacement.key, attempt.key)
            XCTAssertNil(replacement.orderID)
            XCTAssertTrue(replacement.canResumeCheckout)
            XCTAssertTrue(replacement.matches(selected, quantity: quantity))
        }
        for phase in [ApplePurchaseAttempt.Phase.submitted, .awaitingApproval] {
            attempt.phase = phase
            XCTAssertThrowsError(try attempt.preparingCheckout(for: originalOffer, quantity: 2))
            XCTAssertThrowsError(try attempt.preparingCheckout(for: offer(["scheduleVersion": "us-v2"]), quantity: 1))
        }
    }
    @MainActor func testStoreKitRejectionClearsSubmittedAttemptAcrossRestart() async throws {
        let rejected: [Error] = [Product.PurchaseError.productUnavailable, Product.PurchaseError.purchaseNotAllowed,
            Product.PurchaseError.invalidQuantity, Product.PurchaseError.ineligibleForOffer,
            Product.PurchaseError.invalidOfferIdentifier, Product.PurchaseError.invalidOfferPrice,
            Product.PurchaseError.invalidOfferSignature, Product.PurchaseError.missingOfferParameters,
            StoreKitError.userCancelled, StoreKitError.notAvailableInStorefront, StoreKitError.notEntitled]
        for error in rejected {
            var attempt = try ApplePurchaseAttempt(accountID: UUID(), offer: offer(), quantity: 2)
            attempt.orderID = UUID(); attempt.phase = .submitted
            var saved: Data? = try JSONEncoder().encode(attempt)
            do {
                let _: Bool = try await ApplePurchaseSubmission.perform(purchase: { throw error }, clearRejectedAttempt: { saved = nil })
                XCTFail("Expected StoreKit rejection")
            } catch { }
            XCTAssertNil(saved, "A definite rejection must allow another purchase after restart: \(error)")
        }
    }
    @MainActor func testAmbiguousStoreKitFailuresKeepSubmittedOrderForRecovery() async throws {
        let uncertain: [Error] = [StoreKitError.unknown, StoreKitError.networkError(URLError(.timedOut)),
            StoreKitError.systemError(NSError(domain: "StoreKitTest", code: 1)), CancellationError(), URLError(.notConnectedToInternet)]
        for error in uncertain {
            var attempt = try ApplePurchaseAttempt(accountID: UUID(), offer: offer(), quantity: 2)
            attempt.orderID = UUID(); attempt.phase = .submitted
            var saved: Data? = try JSONEncoder().encode(attempt)
            do {
                let _: Bool = try await ApplePurchaseSubmission.perform(purchase: { throw error }, clearRejectedAttempt: { saved = nil })
                XCTFail("Expected uncertain StoreKit failure")
            } catch { }
            let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: XCTUnwrap(saved))
            XCTAssertEqual(restored, attempt)
            XCTAssertFalse(restored.canResumeCheckout, "An uncertain charge must never launch again")
        }
    }
    @MainActor func testPendingStoreKitResultRetainsRecoveryRecord() async throws {
        var clearCalled = false
        let result = try await ApplePurchaseSubmission.perform(purchase: { Product.PurchaseResult.pending },
            clearRejectedAttempt: { clearCalled = true })
        guard case .pending = result else { return XCTFail("Pending result was changed") }
        XCTAssertFalse(clearCalled)
    }
    @MainActor func testRejectedAttemptStorageFailureDoesNotPretendItWasCleared() async throws {
        struct StorageFailure: Error { }
        do {
            let _: Bool = try await ApplePurchaseSubmission.perform(purchase: { throw Product.PurchaseError.purchaseNotAllowed },
                clearRejectedAttempt: { throw StorageFailure() })
            XCTFail("Expected storage failure")
        } catch is StorageFailure { }
        catch { XCTFail("Unexpected error: \(error)") }
    }
    func testServerFulfillmentUnlocksOnlyTheMatchingOwnersCompletedOrder() throws {
        let owner = UUID(), order = UUID()
        var attempt = try ApplePurchaseAttempt(accountID: owner, offer: offer(), quantity: 2)
        attempt.orderID = order; attempt.phase = .submitted
        func status(_ changes: [String: Any] = [:]) throws -> ApplePurchaseStatus {
            var fields: [String: Any] = ["orderID": order.uuidString, "entitlementKind": "ai_value", "state": "purchased", "fulfillmentRecorded": true]
            fields.merge(changes) { _, new in new }
            return try JSONDecoder().decode(ApplePurchaseStatus.self, from: JSONSerialization.data(withJSONObject: fields))
        }
        let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONEncoder().encode(attempt))
        XCTAssertTrue(try restored.isFulfilled(by: status(), for: owner))
        XCTAssertFalse(try restored.isFulfilled(by: status(), for: UUID()))
        for changes: [String: Any] in [["orderID": UUID().uuidString], ["state": "created"], ["state": "pending"],
                                      ["state": "voided"], ["fulfillmentRecorded": false], ["entitlementKind": "minutes"]] {
            XCTAssertFalse(try restored.isFulfilled(by: status(changes), for: owner))
        }
    }
}
