import Foundation

public struct AppleAppTransactionProof: Sendable {
    public let environment: String
    public let signedAppTransaction: String
    public init(environment: String, signedAppTransaction: String) {
        self.environment = environment; self.signedAppTransaction = signedAppTransaction
    }
    fileprivate var valid: Bool {
        ["live", "test"].contains(environment) && !signedAppTransaction.isEmpty &&
            signedAppTransaction.utf8.count <= 16_384
    }
}

/// Account funding comes from the server ledger; Apple proof is needed only to acquire purchases.
public enum ApplePurchaseRequest {
    public static func prepare(_ original: URLRequest, origin: URL, automatic: Bool,
                               proofProvider: @Sendable () async throws -> AppleAppTransactionProof) async throws -> URLRequest {
        try Task.checkCancellation()
        var request = original
        request.setValue(nil, forHTTPHeaderField: "X-Mural-Apple-App-Transaction")
        guard automatic, ApplePurchaseScope.permitsProof(to: request.url, origin: origin),
              ApplePurchaseScope.requiresProof(path: request.url?.path ?? "") else { return request }
        do {
            let proof = try await proofProvider()
            try Task.checkCancellation()
            guard proof.valid else { throw ManagedAccountError.purchaseVerificationUnavailable }
            request.setValue(proof.signedAppTransaction, forHTTPHeaderField: "X-Mural-Apple-App-Transaction")
            return request
        } catch {
            try Task.checkCancellation()
            if error is CancellationError || error as? ManagedAccountError == .cancelled ||
                (error as? URLError)?.code == .cancelled { throw ManagedAccountError.cancelled }
            throw ManagedAccountError.purchaseVerificationUnavailable
        }
    }
}
