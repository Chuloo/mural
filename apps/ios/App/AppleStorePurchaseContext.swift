import Foundation
import StoreKit
import MuralCore

/// StoreKit verifies locally; the server independently verifies this signed proof.
actor AppleStorePurchaseContext {
    static let shared = AppleStorePurchaseContext()
    struct Proof: Sendable {
        let environment: String
        let signedAppTransaction: String
    }
    private var cached: Proof?
    private var loading: Task<Proof, Error>?

    func proof() async throws -> Proof {
        if let cached { return cached }
        if let loading { return try await loading.value }
        let task = Task<Proof, Error> {
            let result = try await AppTransaction.shared
            guard case .verified(let transaction) = result,
                  transaction.bundleID == Bundle.main.bundleIdentifier,
                  !result.jwsRepresentation.isEmpty,
                  result.jwsRepresentation.utf8.count <= 16_384 else { throw ManagedAccountError.invalidResponse }
            return Proof(environment: try ApplePurchaseScope.environment(transaction.environment),
                         signedAppTransaction: result.jwsRepresentation)
        }
        loading = task
        defer { loading = nil }
        let proof = try await task.value
        cached = proof
        return proof
    }

    func attachingProof(to request: URLRequest) async throws -> URLRequest {
        guard let configuration = ManagedAccountConfiguration.load(),
              ApplePurchaseScope.permitsProof(to: request.url, origin: configuration.origin),
              (Bundle.main.object(forInfoDictionaryKey: "MuralApplePurchaseEnvironment") as? String) == "auto"
        else { return request }
        do {
            let proof = try await proof()
            var request = request
            request.setValue(proof.signedAppTransaction, forHTTPHeaderField: "X-Mural-Apple-App-Transaction")
            return request
        } catch {
            // A failed sandbox proof must never silently switch spending to the live wallet.
            guard !ApplePurchaseScope.requiresProof(path: request.url?.path ?? "") else { throw ManagedAccountError.unavailable }
            return request
        }
    }
}
