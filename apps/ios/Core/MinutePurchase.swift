import Foundation
import StoreKit

public enum ApplePurchaseSubmission {
    /// Only rejection by StoreKit proves that this invocation cannot deliver a purchase later.
    /// Network, system and task-cancellation errors retain the durable recovery record.
    @MainActor public static func perform<Result>(purchase: @MainActor () async throws -> Result,
                                                  clearRejectedAttempt: @MainActor () throws -> Void) async throws -> Result {
        do { return try await purchase() }
        catch {
            if definitelyRejected(error) { try clearRejectedAttempt() }
            throw error
        }
    }

    private static func definitelyRejected(_ error: Error) -> Bool {
        if let error = error as? Product.PurchaseError {
            switch error {
            case .productUnavailable, .purchaseNotAllowed, .invalidQuantity, .ineligibleForOffer,
                 .invalidOfferIdentifier, .invalidOfferPrice, .invalidOfferSignature, .missingOfferParameters:
                return true
            @unknown default: return false
            }
        }
        if let error = error as? StoreKitError {
            switch error {
            case .userCancelled, .notAvailableInStorefront, .notEntitled: return true
            default: return false
            }
        }
        return false
    }
}

public struct MinuteOffer: Decodable, Equatable, Sendable, Identifiable {
    private static let maximumUnitTotalMinor = 100_000_000
    private static let maximumTotalMinor = 1_000_000_000
    public var id: String { sku }
    public let sku: String
    public let providerProduct: String
    public let currency: String
    public let currencyExponent: Int
    public let totalMinor: Int
    public let estimatedMilliseconds: Int
    public let estimateRateVersion: String
    public let scheduleVersion: String
    public let storefront: String
    public let environment: String
    public func validate() throws {
        guard !sku.isEmpty, sku.count <= 128,
              ["chat.mural.ios.minutes.small.v1", "chat.mural.ios.minutes.medium.v1", "chat.mural.ios.minutes.large.v1"].contains(providerProduct),
              ApplePurchaseCurrency.validStorefront(storefront),
              ApplePurchaseCurrency.exponent(for: currency) == currencyExponent,
              totalMinor > 0, totalMinor <= Self.maximumUnitTotalMinor, estimatedMilliseconds > 0, estimatedMilliseconds <= 1_000_000_000,
              !estimateRateVersion.isEmpty, !scheduleVersion.isEmpty, ["test", "live"].contains(environment)
        else { throw ManagedAccountError.invalidResponse }
    }
    public func total(quantity: Int) throws -> Int {
        try validate()
        guard (1...10).contains(quantity) else { throw ManagedAccountError.invalidResponse }
        let (total, overflow) = totalMinor.multipliedReportingOverflow(by: quantity)
        guard !overflow, total <= Self.maximumTotalMinor else { throw ManagedAccountError.invalidResponse }
        return total
    }
    public func price(quantity: Int) throws -> Decimal {
        let total = try total(quantity: quantity)
        return Decimal(total) / minorUnitScale
    }
    public func matches(price: Decimal, currency: String) -> Bool {
        guard (try? validate()) != nil, !price.isNaN, price > 0, currency.utf8.count == 3,
              currency.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) }),
              currency.lowercased() == self.currency else { return false }
        return price * minorUnitScale == Decimal(totalMinor)
    }
    private var minorUnitScale: Decimal { Decimal([1, 10, 100, 1_000][currencyExponent]) }
    public func minutes(quantity: Int) throws -> Int {
        _ = try total(quantity: quantity)
        return estimatedMilliseconds * quantity / 60_000
    }
}
public struct MinuteCatalog: Decodable, Sendable {
    public let available: Bool
    public let maximumQuantity: Int
    public let products: [MinuteOffer]
    public func validate(storefront: String) throws {
        guard ApplePurchaseCurrency.validStorefront(storefront),
              [1, 10].contains(maximumQuantity), products.count <= 3, available == !products.isEmpty,
              Set(products.map(\.sku)).count == products.count,
              Set(products.map(\.providerProduct)).count == products.count,
              products.allSatisfy({ $0.storefront == storefront }) else { throw ManagedAccountError.invalidResponse }
        for offer in products { try offer.validate() }
    }
}
public struct ApplePurchaseAttempt: Codable, Equatable, Sendable {
    public enum Phase: String, Codable, Sendable { case preparing, submitted, awaitingApproval }
    public let accountID: UUID
    public let key: UUID
    public let offer: MinuteOfferSnapshot
    public let quantity: Int
    public var orderID: UUID?
    public var phase: Phase = .preparing
    public var canResumeCheckout: Bool { phase == .preparing }
    public func isFulfilled(by status: ApplePurchaseStatus, for owner: UUID) -> Bool {
        accountID == owner && orderID == status.orderID && status.entitlementKind == "ai_value" &&
            status.state == "purchased" && status.fulfillmentRecorded
    }
    public func matches(_ selected: MinuteOffer, quantity: Int) -> Bool {
        offer == MinuteOfferSnapshot(selected) && self.quantity == quantity
    }
    public func preparingCheckout(for selected: MinuteOffer, quantity: Int) throws -> ApplePurchaseAttempt {
        guard canResumeCheckout else { throw ManagedAccountError.unavailable }
        // No StoreKit invocation has occurred in this phase. A fresh explicit choice
        // may replace stale terms; unchanged terms keep the key for lost-response recovery.
        return matches(selected, quantity: quantity) ? self :
            try ApplePurchaseAttempt(accountID: accountID, offer: selected, quantity: quantity)
    }
    public init(accountID: UUID, offer: MinuteOffer, quantity: Int) throws {
        _ = try offer.total(quantity: quantity)
        self.accountID = accountID; key = UUID(); self.offer = MinuteOfferSnapshot(offer); self.quantity = quantity
    }
}
public struct ApplePurchaseStatus: Decodable, Sendable {
    public let orderID: UUID
    public let entitlementKind: String
    public let state: String
    public let fulfillmentRecorded: Bool
    public let reversedNanoUSD: String?
}
public struct MinuteOfferSnapshot: Codable, Equatable, Sendable {
    public let sku: String
    public let productID: String
    public let storefront: String
    public let scheduleVersion: String
    public let currency: String
    public let unitTotalMinor: Int
    public let estimatedMilliseconds: Int
    public let environment: String?
    public let currencyExponent: Int?
    public init(_ offer: MinuteOffer) {
        sku = offer.sku; productID = offer.providerProduct; storefront = offer.storefront; scheduleVersion = offer.scheduleVersion
        currency = offer.currency; unitTotalMinor = offer.totalMinor; estimatedMilliseconds = offer.estimatedMilliseconds
        environment = offer.environment; currencyExponent = offer.currencyExponent
    }
    private var effectiveCurrencyExponent: Int? {
        if let currencyExponent { return currencyExponent }
        // Before global pricing, validated saved offers were exclusively USA/USD or NOR/NOK.
        return (storefront == "USA" && currency == "usd") || (storefront == "NOR" && currency == "nok") ? 2 : nil
    }
    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.sku == rhs.sku && lhs.productID == rhs.productID && lhs.storefront == rhs.storefront &&
            lhs.scheduleVersion == rhs.scheduleVersion && lhs.currency == rhs.currency &&
            lhs.unitTotalMinor == rhs.unitTotalMinor && lhs.estimatedMilliseconds == rhs.estimatedMilliseconds &&
            lhs.environment == rhs.environment && lhs.effectiveCurrencyExponent == rhs.effectiveCurrencyExponent
    }
}
