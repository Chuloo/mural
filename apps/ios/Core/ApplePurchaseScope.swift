import Foundation
import StoreKit

public enum ApplePurchaseScope {
    public static func requiresProof(path: String) -> Bool {
        path == "/v1/wallet" || path == "/v1/guest/minutes" || path == "/v1/minutes" ||
            path.hasPrefix("/v1/minutes/") || path.hasPrefix("/v1/live/")
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
              target.path.hasPrefix("/v1/"), !target.path.contains("..") else { return false }
        return true
    }
}
