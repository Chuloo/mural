import Foundation
import StoreKit
import MuralCore

/// StoreKit verifies locally; the server independently verifies this signed proof.
actor AppleStorePurchaseContext {
    static let shared = AppleStorePurchaseContext()
    typealias Proof = AppleAppTransactionProof
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
        try Task.checkCancellation()
        var request = request
        request.setValue(nil, forHTTPHeaderField: "X-Mural-Apple-App-Transaction")
        let automatic = (Bundle.main.object(forInfoDictionaryKey: "MuralApplePurchaseEnvironment") as? String) == "auto"
        guard let value = Bundle.main.object(forInfoDictionaryKey: "MuralManagedAPIURL") as? String,
              let origin = URL(string: value) else {
            if automatic, ApplePurchaseScope.requiresProof(path: request.url?.path ?? "") {
                throw ManagedAccountError.purchaseVerificationUnavailable
            }
            return request
        }
        return try await ApplePurchaseRequest.prepare(request, origin: origin, automatic: automatic) { try await self.proof() }
    }
}
