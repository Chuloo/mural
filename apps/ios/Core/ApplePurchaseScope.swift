import Foundation
import StoreKit

public enum ApplePurchaseScope {
    public static func completionMessage(testPurchase: Bool) -> String {
        testPurchase ? "Test purchase complete. You weren’t charged and no usable minutes were added." : "Your minutes have been updated."
    }
    public static func pendingMessage(testPurchase: Bool) -> String {
        testPurchase ? "Your test purchase is awaiting approval. It won’t add usable minutes." : "Your purchase is awaiting approval. Minutes will appear when it’s complete."
    }
    public static func historyStatus(testPurchase: Bool, refunded: Bool) -> String {
        if refunded { return testPurchase ? "Test refund recorded" : "Refund recorded" }
        return testPurchase ? "Test purchase recorded · no usable minutes added" : "Minutes added"
    }
    public static func requiresProof(path: String) -> Bool {
        path == "/v1/minutes/products" || path == "/v1/minutes/orders" ||
            path.hasPrefix("/v1/minutes/orders/") || path == "/v1/minutes/apple/recover"
    }
    public static func environment(_ environment: AppStore.Environment) throws -> String {
        switch environment {
        case .production: return "live"
        case .sandbox: return "test"
        default: throw ManagedAccountError.unavailable
        }
    }

    /// App transaction proofs must never accompany Google OAuth or another origin.
    public static func permitsProof(to destination: URL?, origin: URL) -> Bool {
        guard let destination,
              let target = URLComponents(url: destination, resolvingAgainstBaseURL: false),
              let expected = URLComponents(url: origin, resolvingAgainstBaseURL: false),
              target.scheme == "https", expected.scheme == "https",
              target.host == expected.host, target.port == expected.port,
              target.user == nil, target.password == nil,
              target.path.hasPrefix("/v1/"), !target.path.contains(".."),
              target.fragment == nil else { return false }
        return true
    }
}
