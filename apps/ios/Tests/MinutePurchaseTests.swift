import XCTest
import StoreKit
@testable import MuralCore

final class MinutePurchaseTests: XCTestCase {
    func testSandboxPurchaseFeedbackNeverClaimsUsableCredit() {
        XCTAssertEqual(ApplePurchaseScope.completionMessage(testPurchase: true),
                       "Test purchase complete. You weren’t charged and no usable minutes were added.")
        XCTAssertEqual(ApplePurchaseScope.pendingMessage(testPurchase: true),
                       "Your test purchase is awaiting approval. It won’t add usable minutes.")
        XCTAssertEqual(ApplePurchaseScope.historyStatus(testPurchase: true, refunded: false),
                       "Test purchase recorded · no usable minutes added")
        XCTAssertEqual(ApplePurchaseScope.historyStatus(testPurchase: true, refunded: true), "Test refund recorded")
        XCTAssertEqual(ApplePurchaseScope.completionMessage(testPurchase: false), "Your minutes have been updated.")
        XCTAssertEqual(ApplePurchaseScope.historyStatus(testPurchase: false, refunded: false), "Minutes added")
    }
    func testAppleProofStaysOnConfiguredOriginAndNeverReachesOAuth() throws {
        let origin = try XCTUnwrap(URL(string: "https://api.mural.chat"))
        XCTAssertTrue(ApplePurchaseScope.permitsProof(to: URL(string: "https://api.mural.chat/v1/minutes"), origin: origin))
        for destination in ["https://oauth2.googleapis.com/token", "https://sandbox-api.mural.chat/v1/minutes",
                            "http://api.mural.chat/v1/minutes", "https://api.mural.chat:8443/v1/minutes",
                            "https://api.mural.chat.attacker.invalid/v1/minutes", "https://user@api.mural.chat/v1/minutes",
                            "https://api.mural.chat/healthz", "https://api.mural.chat/v1/../token"] {
            XCTAssertFalse(ApplePurchaseScope.permitsProof(to: URL(string: destination), origin: origin), destination)
        }
        XCTAssertFalse(ApplePurchaseScope.permitsProof(to: nil, origin: origin))
    }
    func testAppleScopeRejectsUnsignedXcodeEnvironment() throws {
        XCTAssertEqual(try ApplePurchaseScope.environment(.production), "live")
        XCTAssertEqual(try ApplePurchaseScope.environment(.sandbox), "test")
        XCTAssertThrowsError(try ApplePurchaseScope.environment(.xcode))
        XCTAssertThrowsError(try ApplePurchaseScope.environment(AppStore.Environment(rawValue: "unknown")))
    }
    func testOnlyPurchaseAcquisitionRequiresAppleProof() {
        for path in ["/v1/minutes/products", "/v1/minutes/orders", "/v1/minutes/orders/example", "/v1/minutes/apple/recover"] {
            XCTAssertTrue(ApplePurchaseScope.requiresProof(path: path), path)
        }
        for path in ["/v1/auth/challenge", "/v1/auth/exchange", "/v1/auth/sign-out", "/v1/account",
                     "/v1/wallet", "/v1/guest/minutes", "/v1/minutes", "/v1/minutes/link-guest", "/v1/live/capabilities",
                     "/v1/live/sessions", "/v1/live/sessions/example", "/v1/live/sessions/example/close",
                     "/v1/live/requests/example/close", "/v1/live/sessions/example/helpers"] {
            XCTAssertFalse(ApplePurchaseScope.requiresProof(path: path), path)
        }
    }
    func testSharedMinutesPresentationFixtures() throws {
        struct Fixture: Decodable { let name: String; let text: String; let presentation: MuralMinutesPresentation }
        let path = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../shared/fixtures/cross-platform/minutes-presentation.json")
        for fixture in try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: path)) {
            try fixture.presentation.validate()
            XCTAssertEqual(fixture.presentation.displayText, fixture.text, fixture.name)
        }
    }
    private func offerFields(_ changes: [String: Any] = [:]) -> [String: Any] {
        var fields: [String: Any] = ["sku": "small-us", "providerProduct": "chat.mural.ios.minutes.small.v1",
            "currency": "usd", "currencyExponent": 2, "totalMinor": 700,
            "estimatedMilliseconds": 2_214_000, "estimateRateVersion": "usd-0.10-v1",
            "scheduleVersion": "us-v1", "storefront": "USA", "environment": "test"]
        fields.merge(changes) { _, new in new }
        return fields
    }
    private func offer(_ changes: [String: Any] = [:]) throws -> MinuteOffer {
        try JSONDecoder().decode(MinuteOffer.self, from: JSONSerialization.data(withJSONObject: offerFields(changes)))
    }
    func testQuantityUsesCombinedWholeMinutesAndExactCheckoutPrice() throws {
        let value = try offer()
        for (quantity, minutes, minor) in [(1, 36, 700), (2, 73, 1400), (10, 369, 7000)] {
            XCTAssertEqual(try value.minutes(quantity: quantity), minutes)
            XCTAssertEqual(try value.total(quantity: quantity), minor)
        }
        for quantity in [Int.min, -1, 0, 11, Int.max] { XCTAssertThrowsError(try value.total(quantity: quantity)) }
    }
    func testRejectsMismatchedCurrencyPrecisionAndUnboundedPrices() throws {
        for fields: [String: Any] in [["totalMinor": 0], ["totalMinor": 100_000_001], ["totalMinor": Int.max], ["estimatedMilliseconds": Int.max],
            ["currencyExponent": 3], ["environment": "xcode"],
            ["providerProduct": "unrelated.product"], ["scheduleVersion": ""]] {
            XCTAssertThrowsError(try offer(fields).validate())
        }
        try offer(["storefront": "NOR", "currency": "nok", "totalMinor": 8900]).validate()
    }
    func testExportedIndonesiaPricesAllowTenPacksWithinCheckedAppleMoneyBounds() throws {
        // App Store Connect's three current price exports, retrieved 2026-10-04.
        for (sku, price) in [("small", 149_000), ("medium", 249_000), ("large", 399_000)] {
            let value = try offer(["sku": sku, "providerProduct": "chat.mural.ios.minutes.\(sku).v1",
                                  "storefront": "IDN", "currency": "idr", "currencyExponent": 2, "totalMinor": price * 100])
            try value.validate()
            XCTAssertTrue(value.matches(price: Decimal(price), currency: "IDR"), sku)
            XCTAssertEqual(try value.total(quantity: 1), price * 100, sku)
            XCTAssertEqual(try value.total(quantity: 10), price * 1_000, sku)
            XCTAssertEqual(try value.price(quantity: 10), Decimal(price * 10), sku)
            let attempt = try ApplePurchaseAttempt(accountID: UUID(), offer: value, quantity: 10)
            let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONEncoder().encode(attempt))
            XCTAssertEqual(restored, attempt)
            XCTAssertTrue(restored.matches(value, quantity: 10))
        }
        let maximum = try offer(["totalMinor": 100_000_000])
        XCTAssertEqual(try maximum.total(quantity: 10), 1_000_000_000)
        XCTAssertEqual(try maximum.price(quantity: 10), 10_000_000)
        XCTAssertThrowsError(try maximum.total(quantity: 11))
        XCTAssertThrowsError(try offer(["totalMinor": 100_000_001]).total(quantity: 1))
        XCTAssertThrowsError(try offer(["totalMinor": Int.max]).total(quantity: 10))
    }
    func testGlobalOffersUseExactMinorUnitsForZeroTwoAndThreeDecimalCurrencies() throws {
        for (storefront, currency, exponent, minor, price) in [
            ("JPN", "jpy", 0, 1_100, "1100"), ("KOR", "krw", 0, 9_900, "9900"),
            ("FRA", "eur", 2, 799, "7.99"), ("USA", "usd", 2, 700, "7.00"),
            ("KWT", "kwd", 3, 2_199, "2.199"), ("BHR", "bhd", 3, 2_650, "2.650")
        ] {
            let value = try offer(["storefront": storefront, "currency": currency, "currencyExponent": exponent, "totalMinor": minor])
            try value.validate()
            let decimal = try XCTUnwrap(Decimal(string: price, locale: Locale(identifier: "en_US_POSIX")))
            XCTAssertTrue(value.matches(price: decimal, currency: currency.uppercased()), currency)
            XCTAssertEqual(try value.price(quantity: 1), decimal, currency)
            XCTAssertEqual(try value.price(quantity: 10), decimal * 10, currency)
            XCTAssertEqual(try value.total(quantity: 10), minor * 10, currency)
            for different in [decimal + Decimal(string: "0.0001")!, decimal - Decimal(string: "0.0001")!,
                              Decimal.zero, -decimal, Decimal.nan] {
                XCTAssertFalse(value.matches(price: different, currency: currency), currency)
            }
            XCTAssertFalse(value.matches(price: decimal, currency: currency == "usd" ? "nok" : "usd"), currency)
            XCTAssertFalse(value.matches(price: decimal, currency: currency.uppercased() + " "), currency)
        }
        XCTAssertFalse(try offer(["currency": "kwd", "currencyExponent": 3]).matches(price: Decimal(string: "0.700")!, currency: "KWD"))
    }
    func testMultipleStorefrontsMayShareApplesCheckoutCurrency() throws {
        for storefront in ["USA", "ECU", "SLV", "BRB"] {
            let value = try offer(["storefront": storefront])
            try value.validate()
            XCTAssertTrue(value.matches(price: 7, currency: "USD"), storefront)
        }
        for storefront in ["FRA", "DEU", "ESP", "BGR"] {
            try offer(["storefront": storefront, "currency": "eur"]).validate()
        }
    }
    func testGlobalOffersRejectMalformedCodesUnknownCurrenciesAndForgedExponents() throws {
        for storefront in ["", "US", "USAA", "usa", "USA ", "UЅA", "ＵＳＡ", "US1", "US\n"] {
            XCTAssertThrowsError(try offer(["storefront": storefront]).validate(), storefront)
        }
        for currency in ["", "us", "usdd", "USD", "usd ", "uѕd", "ｕｓｄ", "u$d", "zzz", "xxx", "xau", "clf"] {
            XCTAssertThrowsError(try offer(["currency": currency]).validate(), currency)
        }
        for exponent in [-1, 0, 1, 3, 4, Int.max] {
            let value = try offer(["currencyExponent": exponent])
            XCTAssertThrowsError(try value.validate())
            XCTAssertThrowsError(try value.price(quantity: 1))
            XCTAssertFalse(value.matches(price: 7, currency: "USD"))
        }
        for (currency, exponent) in [("jpy", 2), ("kwd", 2), ("eur", 0)] {
            XCTAssertThrowsError(try offer(["currency": currency, "currencyExponent": exponent]).validate())
        }
        for product in ["chat.mural.ios.minutes.unknown.v1", "chat.mural.ios.minutes.small.v1.suffix", "chat.mural.ios.minutes."] {
            XCTAssertThrowsError(try offer(["providerProduct": product]).validate())
        }
    }
    func testCatalogKeepsOffersBoundToTheCurrentStorefront() throws {
        func catalog(_ fields: [[String: Any]], available: Bool = true) throws -> MinuteCatalog {
            let products = fields.map { offerFields($0) }
            return try JSONDecoder().decode(MinuteCatalog.self, from: JSONSerialization.data(withJSONObject:
                ["available": available, "maximumQuantity": 1, "products": products]))
        }
        let france = try catalog([["storefront": "FRA", "currency": "eur"]])
        try france.validate(storefront: "FRA")
        XCTAssertThrowsError(try france.validate(storefront: "DEU"))
        XCTAssertThrowsError(try france.validate(storefront: "fra"))
        let repeated = try catalog([[:], ["sku": "another-sku"]])
        XCTAssertThrowsError(try repeated.validate(storefront: "USA"))
        let mixed = try catalog([[:], ["sku": "medium", "providerProduct": "chat.mural.ios.minutes.medium.v1", "storefront": "DEU"]])
        XCTAssertThrowsError(try mixed.validate(storefront: "USA"))
        let empty = try catalog([], available: false)
        try empty.validate(storefront: "JPN")
        XCTAssertThrowsError(try empty.validate(storefront: "JP"))
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
    func testHistoricalTwoDecimalAttemptsKeepIdempotencyAndRecoveryAcrossGlobalPricing() throws {
        let owner = UUID(), order = UUID()
        for fields: [String: Any] in [[:], ["storefront": "NOR", "currency": "nok", "totalMinor": 8_900]] {
            let selected = try offer(fields)
            var attempt = try ApplePurchaseAttempt(accountID: owner, offer: selected, quantity: 2)
            attempt.orderID = order
            for phase in [ApplePurchaseAttempt.Phase.preparing, .submitted, .awaitingApproval] {
                attempt.phase = phase
                var old = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(attempt)) as? [String: Any])
                var terms = try XCTUnwrap(old["offer"] as? [String: Any])
                terms.removeValue(forKey: "currencyExponent"); old["offer"] = terms
                let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONSerialization.data(withJSONObject: old))
                XCTAssertNil(restored.offer.currencyExponent)
                XCTAssertEqual(restored, attempt)
                XCTAssertTrue(restored.matches(selected, quantity: 2))
                XCTAssertEqual(restored.key, attempt.key)
                XCTAssertEqual(restored.orderID, order)
                if phase == .preparing {
                    XCTAssertEqual(try restored.preparingCheckout(for: selected, quantity: 2).key, attempt.key)
                } else {
                    XCTAssertFalse(restored.canResumeCheckout)
                    XCTAssertThrowsError(try restored.preparingCheckout(for: selected, quantity: 2))
                }
                terms.removeValue(forKey: "environment"); old["offer"] = terms
                let legacy = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONSerialization.data(withJSONObject: old))
                XCTAssertNil(legacy.offer.environment)
                XCTAssertEqual(legacy.orderID, order)
                XCTAssertEqual(legacy.phase, phase)
                XCTAssertFalse(legacy.matches(selected, quantity: 2), "An unsigned old scope must not reuse new purchase terms")
            }
        }
    }
    func testGlobalSnapshotsRetainTheirCurrencyPrecisionAndNeverGuessMissingTerms() throws {
        for (storefront, currency, exponent) in [("JPN", "jpy", 0), ("FRA", "eur", 2), ("KWT", "kwd", 3)] {
            let selected = try offer(["storefront": storefront, "currency": currency, "currencyExponent": exponent])
            var attempt = try ApplePurchaseAttempt(accountID: UUID(), offer: selected, quantity: 1)
            attempt.orderID = UUID()
            let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONEncoder().encode(attempt))
            XCTAssertEqual(restored.offer.currencyExponent, exponent)
            XCTAssertEqual(restored, attempt)
            XCTAssertTrue(restored.matches(selected, quantity: 1))
            var saved = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(attempt)) as? [String: Any])
            var terms = try XCTUnwrap(saved["offer"] as? [String: Any])
            terms.removeValue(forKey: "currencyExponent"); saved["offer"] = terms
            let missing = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONSerialization.data(withJSONObject: saved))
            XCTAssertFalse(missing.matches(selected, quantity: 1))
            XCTAssertNotEqual(try missing.preparingCheckout(for: selected, quantity: 1).key, attempt.key)
            XCTAssertEqual(missing.orderID, attempt.orderID, "Decoding must retain an existing order for recovery")
            terms["currencyExponent"] = exponent == 2 ? 0 : 2; saved["offer"] = terms
            let changed = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONSerialization.data(withJSONObject: saved))
            XCTAssertFalse(changed.matches(selected, quantity: 1))
        }
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
    func testPreparingCheckoutCannotReuseTermsAcrossAppleEnvironments() throws {
        let test = try offer(), live = try offer(["environment": "live"])
        var original = try ApplePurchaseAttempt(accountID: UUID(), offer: test, quantity: 1)
        original.orderID = UUID()
        let replacement = try original.preparingCheckout(for: live, quantity: 1)
        XCTAssertNotEqual(original.key, replacement.key)
        XCTAssertNil(replacement.orderID)
        original.phase = .submitted
        XCTAssertThrowsError(try original.preparingCheckout(for: live, quantity: 1))
        var oldSnapshot = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as? [String: Any])
        var terms = try XCTUnwrap(oldSnapshot["offer"] as? [String: Any])
        terms.removeValue(forKey: "environment"); oldSnapshot["offer"] = terms
        let restored = try JSONDecoder().decode(ApplePurchaseAttempt.self, from: JSONSerialization.data(withJSONObject: oldSnapshot))
        XCTAssertEqual(restored.orderID, original.orderID)
        XCTAssertFalse(restored.canResumeCheckout)
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
    @MainActor func testSubmissionKeepsActorOwnedResultOnTheMainActor() async throws {
        final class PurchaseResultReference { var delivered = false }
        let expected = PurchaseResultReference()
        var clearCalled = false
        let result = try await ApplePurchaseSubmission.perform(purchase: {
            MainActor.preconditionIsolated()
            return expected
        }, clearRejectedAttempt: { clearCalled = true })
        XCTAssertTrue(result === expected)
        result.delivered = true
        XCTAssertTrue(expected.delivered)
        XCTAssertFalse(clearCalled)
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
