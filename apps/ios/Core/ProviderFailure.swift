import Foundation

public enum ProviderFailureKind: String, Sendable {
    case authentication, modelAccess, quota, rateLimit, unavailable, invalidRequest, unknown
    public static func classify(status: Int, code: String?) -> Self {
        if status == 401 { return .authentication }
        if status == 403 || status == 404 { return .modelAccess }
        if status == 429 { return code == "insufficient_quota" ? .quota : .rateLimit }
        if status == 408 || status >= 500 { return .unavailable }
        if status == 400 || status == 422 { return .invalidRequest }
        return .unknown
    }
}

/// Keeps a provider's safe category and support reference, never its message or response body.
public struct ProviderFailure: LocalizedError, Sendable {
    public let status: Int
    public let code: String?
    public let reference: String?
    public let providerName: String
    public var kind: ProviderFailureKind { .classify(status: status, code: code) }
    public init(status: Int, body: Data = Data(), reference: String? = nil, providerName: String = "OpenAI") {
        self.status = status
        self.providerName = providerName
        let json = body.count <= 16_384 ? (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] : nil
        let code = (json?["error"] as? [String: Any])?["code"] as? String
        self.code = Self.safeCode(code)
        self.reference = Self.safeReference(reference)
    }
    public static func safeCode(_ value: String?) -> String? {
        guard let value, ["invalid_api_key", "insufficient_quota", "rate_limit_exceeded", "model_not_found", "permission_denied", "server_error"].contains(value) else { return nil }
        return value
    }
    public static func safeReference(_ value: String?) -> String? {
        guard let value, !value.isEmpty, value.utf8.count <= 128,
              value.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 || $0 == 95 }) else { return nil }
        return value
    }
    public var errorDescription: String? {
        let message: String = switch kind {
        case .authentication: "Your \(providerName) key wasn’t accepted. Check it in Settings."
        case .modelAccess: "This API key may not have access to the requested model. Check your \(providerName) project."
        case .quota: "Your \(providerName) project has no available API credit. Check its billing and usage limit before trying again."
        case .rateLimit: "\(providerName) is limiting requests. Wait briefly and try again. If this continues, check your project’s billing and limits."
        case .unavailable: "The voice or teaching service is temporarily unavailable. Please try again shortly."
        case .invalidRequest: "The service could not accept this request. If this continues, contact support."
        case .unknown: "The service could not complete this request. Please try again later."
        }
        return reference.map { message + "\n\n\(providerName) reference: " + $0 } ?? message
    }
}
