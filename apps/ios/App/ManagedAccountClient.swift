import Foundation
import Security
import CryptoKit
import MuralCore

extension ManagedAccountConfiguration {
    /// These keys contain public client IDs, never OAuth secrets or Mural bearer tokens.
    static func load(bundle: Bundle = .main) -> Self? {
        #if MURAL_SIGN_IN_WITH_APPLE
        let appleCapabilityEnabled = true
        #else
        let appleCapabilityEnabled = false
        #endif
        func enabled(_ key: String) -> Bool {
            let value = bundle.object(forInfoDictionaryKey: key)
            return value as? Bool == true || (value as? String)?.uppercased() == "YES"
        }
        guard enabled("MuralManagedAccountsEnabled"),
              let api = bundle.object(forInfoDictionaryKey: "MuralManagedAPIURL") as? String,
              let bundleID = bundle.bundleIdentifier else { return nil }
        let google = enabled("MuralGoogleSignInEnabled")
            ? bundle.object(forInfoDictionaryKey: "MuralGoogleClientID") as? String : nil
        let apple = enabled("MuralAppleSignInEnabled") && appleCapabilityEnabled
            ? bundle.object(forInfoDictionaryKey: "MuralAppleClientID") as? String : nil
        let schemes = (bundle.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? [])
            .flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
        return try? Self(apiURL: api, googleClientID: google, appleClientID: apple,
                         bundleID: bundleID, registeredURLSchemes: schemes, appleCapabilityEnabled: appleCapabilityEnabled)
    }
}

/// No redirect can carry an account token, provider code or verifier to another destination.
final class ManagedAccountHTTP: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil; configuration.httpShouldSetCookies = false
        configuration.urlCache = nil; configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 20; configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let session = makeSession()
        defer { session.invalidateAndCancel() }
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse,
                  response.url == request.url, response.expectedContentLength <= 65_536
            else { throw ManagedAccountError.invalidResponse }
            var data = Data()
            for try await byte in bytes {
                guard data.count < 65_536 else { throw ManagedAccountError.invalidResponse }
                data.append(byte)
            }
            return (data, http)
        } catch let error as ManagedAccountError { throw error }
        catch is CancellationError { throw ManagedAccountError.cancelled }
        catch let error as URLError where error.code == .cancelled { throw ManagedAccountError.cancelled }
        catch { throw ManagedAccountError.transport }
    }
}

struct ManagedAccountClient {
    private struct Failure: Decodable {
        struct Detail: Decodable { let code: String }
        let error: Detail
    }
    let configuration: ManagedAccountConfiguration
    let http: ManagedAccountHTTP
    init(configuration: ManagedAccountConfiguration) { self.configuration = configuration; http = ManagedAccountHTTP() }

    func challenge() async throws -> ManagedAuthChallenge {
        let result: ManagedAuthChallenge = try await request("/v1/auth/challenge", method: "POST", body: [:])
        try result.validate(); return result
    }
    func exchange(provider: ManagedIdentityProvider, token: String, challenge: ManagedAuthChallenge) async throws -> ManagedAuthExchange {
        guard !token.isEmpty, token.utf8.count <= 16_384 else { throw ManagedAccountError.invalidResponse }
        let result: ManagedAuthExchange = try await request("/v1/auth/exchange", method: "POST", body: [
            "provider": provider.rawValue, "idToken": token, "challengeID": challenge.challengeID.uuidString
        ])
        try result.validate(); return result
    }
    func wallet(session: ManagedAccountSession) async throws -> ManagedWallet {
        let result: ManagedWallet = try await request("/v1/wallet", method: "GET", session: session)
        try result.validate(); return result
    }
    func profile(session: ManagedAccountSession) async throws -> ManagedAccountProfile {
        let result: ManagedAccountProfile = try await request("/v1/account", method: "GET", session: session)
        try result.validate(session: session); return result
    }
    func signOut(session: ManagedAccountSession) async throws {
        struct Response: Decodable { let signedOut: Bool }
        let result: Response = try await request("/v1/auth/sign-out", method: "POST", body: [:], session: session)
        guard result.signedOut else { throw ManagedAccountError.invalidResponse }
    }
    func delete(session: ManagedAccountSession, appleCode: String?) async throws {
        struct Response: Decodable { let deleted: Bool }
        let result: Response = try await request("/v1/account", method: "DELETE",
                                               body: appleCode.map { ["appleAuthorizationCode": $0] } ?? [:], session: session)
        guard result.deleted else { throw ManagedAccountError.invalidResponse }
    }
    private func request<T: Decodable>(_ path: String, method: String, body: [String: String]? = nil,
                                        session: ManagedAccountSession? = nil) async throws -> T {
        var request = URLRequest(url: try configuration.endpoint(path))
        request.httpMethod = method; request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        if let session {
            guard session.isUsable(scope: configuration.storageScope) else { throw ManagedAccountError.server("sign_in_required") }
            request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await http.send(request)
        guard (200...299).contains(response.statusCode) else {
            if response.statusCode == 401 { throw ManagedAccountError.server("sign_in_required") }
            let code = (try? JSONDecoder().decode(Failure.self, from: data))?.error.code ?? "service_unavailable"
            // Only a small allowlist is displayed. Never surface raw provider/server response text.
            let safe = ["unresolved_billing", "rate_limit", "apple_sign_in_not_ready", "apple_revocation_not_configured", "invalid_challenge", "identity_provider_not_configured"]
            throw ManagedAccountError.server(safe.contains(code) ? code : "service_unavailable")
        }
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw ManagedAccountError.invalidResponse }
    }
}

struct ManagedAccountKeychain {
    private let scope: String
    init(scope: String) { self.scope = scope }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "chat.mural.managed-account",
         kSecAttrAccount as String: Data(SHA256.hash(data: Data(scope.utf8))).base64EncodedString(),
         kSecAttrSynchronizable as String: false]
    }
    func load() throws -> ManagedAccountSession? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw ManagedAccountError.secureStorage }
        guard let value = try? JSONDecoder().decode(ManagedAccountSession.self, from: data), value.isUsable(scope: scope)
        else { try remove(); return nil }
        return value
    }
    func save(_ value: ManagedAccountSession) throws {
        guard value.isUsable(scope: scope) else { throw ManagedAccountError.invalidResponse }
        let data = try JSONEncoder().encode(value)
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess
            else { throw ManagedAccountError.secureStorage }
        } else if status != errSecSuccess { throw ManagedAccountError.secureStorage }
    }
    func remove() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw ManagedAccountError.secureStorage }
    }
}
