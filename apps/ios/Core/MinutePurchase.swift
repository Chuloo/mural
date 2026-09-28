import Foundation

public struct MinuteOffer: Decodable, Equatable, Sendable, Identifiable {
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
        guard !sku.isEmpty, sku.count <= 128, providerProduct.hasPrefix("chat.mural.ios.minutes."),
              currencyExponent == 2, (storefront == "USA" && currency == "usd") || (storefront == "NOR" && currency == "nok"),
              totalMinor > 0, totalMinor <= 10_000_000, estimatedMilliseconds > 0, estimatedMilliseconds <= 1_000_000_000,
              !estimateRateVersion.isEmpty, !scheduleVersion.isEmpty, ["test", "live"].contains(environment)
        else { throw ManagedAccountError.invalidResponse }
    }
    public func total(quantity: Int) throws -> Int {
        try validate()
        guard (1...10).contains(quantity) else { throw ManagedAccountError.invalidResponse }
        return totalMinor * quantity
    }
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
        guard [1, 10].contains(maximumQuantity), products.count <= 20, available == !products.isEmpty,
              Set(products.map(\.sku)).count == products.count,
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
    public init(_ offer: MinuteOffer) {
        sku = offer.sku; productID = offer.providerProduct; storefront = offer.storefront; scheduleVersion = offer.scheduleVersion
        currency = offer.currency; unitTotalMinor = offer.totalMinor; estimatedMilliseconds = offer.estimatedMilliseconds
    }
}
