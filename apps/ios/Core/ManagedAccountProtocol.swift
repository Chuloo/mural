import Foundation
import CryptoKit

public enum ManagedIdentityProvider: String, Codable, Hashable, Sendable { case google, apple }

public enum ManagedAccountError: Error, Equatable, Sendable {
    case unavailable, invalidResponse, invalidCallback, cancelled, secureStorage, transport
    case server(String)
}

/// Providers are enabled separately so an unavailable provider cannot block a configured one.
public struct ManagedAccountConfiguration: Equatable, Sendable {
    public let origin: URL
    public let bundleID: String
    public let googleClientID: String?
    public let googleRedirectURI: URL?
    public let appleClientID: String?
    public var providers: Set<ManagedIdentityProvider> {
        var result = Set<ManagedIdentityProvider>()
        if googleClientID != nil { result.insert(.google) }
        if appleClientID != nil { result.insert(.apple) }
        return result
    }
    public var storageScope: String {
        // Adding another sign-in option does not sign out an existing account.
        [origin.absoluteString, bundleID].joined(separator: "|")
    }

    public init(apiURL: String, googleClientID: String? = nil, appleClientID: String? = nil,
                bundleID: String, registeredURLSchemes: [String], appleCapabilityEnabled: Bool) throws {
        guard !bundleID.isEmpty,
              let url = URL(string: apiURL), let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == "https", let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil, components.query == nil,
              components.fragment == nil, components.path.isEmpty || components.path == "/"
        else { throw ManagedAccountError.unavailable }
        var googleRedirect: URL?
        if let googleClientID {
            guard googleClientID.range(of: "^[A-Za-z0-9-]+\\.apps\\.googleusercontent\\.com$", options: .regularExpression) != nil
            else { throw ManagedAccountError.unavailable }
            let scheme = googleClientID.split(separator: ".").reversed().joined(separator: ".")
            guard registeredURLSchemes.contains(scheme), let redirect = URL(string: scheme + ":/oauth2redirect")
            else { throw ManagedAccountError.unavailable }
            googleRedirect = redirect
        }
        if let appleClientID {
            guard appleCapabilityEnabled, appleClientID == bundleID else { throw ManagedAccountError.unavailable }
        }
        guard googleClientID != nil || appleClientID != nil else { throw ManagedAccountError.unavailable }
        var normalized = components
        normalized.path = ""
        guard let origin = normalized.url else { throw ManagedAccountError.unavailable }
        self.origin = origin; self.bundleID = bundleID; self.googleClientID = googleClientID
        self.googleRedirectURI = googleRedirect; self.appleClientID = appleClientID
    }

    public func endpoint(_ path: String) throws -> URL {
        let allowed = ["/v1/auth/challenge", "/v1/auth/exchange", "/v1/wallet", "/v1/auth/sign-out", "/v1/account"]
        guard allowed.contains(path), var c = URLComponents(url: origin, resolvingAgainstBaseURL: false)
        else { throw ManagedAccountError.unavailable }
        c.path = path
        guard let result = c.url else { throw ManagedAccountError.unavailable }
        return result
    }
}

public struct ManagedAuthChallenge: Decodable, Sendable {
    public let challengeID: UUID
    public let nonce: String
    public let expiresInSeconds: Int
    public func validate() throws {
        guard nonce.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              (1...300).contains(expiresInSeconds) else { throw ManagedAccountError.invalidResponse }
    }
}

public struct ManagedAuthExchange: Decodable, Sendable {
    public let accountID: UUID
    public let accessToken: String
    public let expiresInSeconds: Int
    public func validate() throws {
        guard accessToken.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil,
              (1...86_400).contains(expiresInSeconds) else { throw ManagedAccountError.invalidResponse }
    }
}

public struct ManagedAccountSession: Codable, Equatable, Sendable {
    public let accountID: UUID
    public let accessToken: String
    public let provider: ManagedIdentityProvider
    public let expiresAt: Date
    public let scope: String
    public init(exchange: ManagedAuthExchange, provider: ManagedIdentityProvider, scope: String, now: Date = .now) throws {
        try exchange.validate()
        accountID = exchange.accountID; accessToken = exchange.accessToken; self.provider = provider
        expiresAt = now.addingTimeInterval(TimeInterval(exchange.expiresInSeconds)); self.scope = scope
    }
    public func isUsable(scope: String, now: Date = .now) -> Bool {
        self.scope == scope && expiresAt > now && expiresAt.timeIntervalSince(now) <= 86_400 &&
        accessToken.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil
    }
}

public struct ManagedAccountProfile: Decodable, Equatable, Sendable {
    public let accountID: UUID
    public let email: String?
    public let providers: [ManagedIdentityProvider]
    public let createdAt: String

    public func validate(session: ManagedAccountSession) throws {
        guard accountID == session.accountID, providers.contains(session.provider),
              providers.count <= 2, Set(providers).count == providers.count
        else { throw ManagedAccountError.invalidResponse }
        if let email {
            guard !email.isEmpty, email.utf8.count <= 320, email.contains("@"),
                  !email.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
            else { throw ManagedAccountError.invalidResponse }
        }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let fractionalDate = formatter.date(from: createdAt)
        formatter.formatOptions = [.withInternetDateTime]
        guard fractionalDate != nil || formatter.date(from: createdAt) != nil
        else { throw ManagedAccountError.invalidResponse }
    }
}

public struct ManagedWallet: Decodable, Equatable, Sendable {
    public let currency: String
    public let balanceNanoUSD: String
    public let reservedNanoUSD: String
    public let availableNanoUSD: String
    /// Keep money exact. The server uses signed 64-bit integer nano-dollars, never JSON doubles.
    public func validate() throws {
        guard currency == "USD", let balance = Self.nano(balanceNanoUSD),
              let reserved = Self.nano(reservedNanoUSD), reserved >= 0,
              let available = Self.nano(availableNanoUSD) else { throw ManagedAccountError.invalidResponse }
        let (expected, overflow) = balance.subtractingReportingOverflow(reserved)
        guard !overflow, expected == available else { throw ManagedAccountError.invalidResponse }
    }
    public var availableDollars: Decimal? {
        guard let nano = Self.nano(availableNanoUSD) else { return nil }
        return Decimal(nano) / Decimal(1_000_000_000)
    }
    private static func nano(_ value: String) -> Int64? {
        guard value.range(of: "^(0|-?[1-9][0-9]{0,18})$", options: .regularExpression) != nil else { return nil }
        return Int64(value)
    }
}

public struct ManagedGoogleAuthorization: Sendable {
    public let configuration: ManagedAccountConfiguration
    public let verifier: String
    public let state: String
    public let nonce: String
    public let clientID: String
    public let redirectURI: URL

    public init(configuration: ManagedAccountConfiguration, verifier: String, state: String, nonce: String) throws {
        guard let clientID = configuration.googleClientID, let redirectURI = configuration.googleRedirectURI
        else { throw ManagedAccountError.unavailable }
        guard verifier.range(of: "^[A-Za-z0-9._~-]{43,128}$", options: .regularExpression) != nil,
              state.range(of: "^[A-Za-z0-9_-]{43,128}$", options: .regularExpression) != nil,
              nonce.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil
        else { throw ManagedAccountError.invalidResponse }
        self.configuration = configuration; self.verifier = verifier; self.state = state; self.nonce = nonce
        self.clientID = clientID; self.redirectURI = redirectURI
    }
    public static func codeChallenge(verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    public var authorizationURL: URL {
        var c = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        c.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI.absoluteString),
            URLQueryItem(name: "response_type", value: "code"), URLQueryItem(name: "scope", value: "openid email"),
            URLQueryItem(name: "state", value: state), URLQueryItem(name: "nonce", value: nonce),
            URLQueryItem(name: "code_challenge", value: Self.codeChallenge(verifier: verifier)),
            URLQueryItem(name: "code_challenge_method", value: "S256")
        ]
        return c.url!
    }
    public func authorizationCode(from callback: URL) throws -> String {
        guard let c = URLComponents(url: callback, resolvingAgainstBaseURL: false),
              c.scheme == redirectURI.scheme, c.host == nil, c.user == nil,
              c.password == nil, c.port == nil, c.path == redirectURI.path,
              c.fragment == nil else { throw ManagedAccountError.invalidCallback }
        let items = c.queryItems ?? []
        func unique(_ name: String) throws -> String? {
            let matches = items.filter { $0.name == name }
            guard matches.count <= 1 else { throw ManagedAccountError.invalidCallback }
            return matches.first?.value
        }
        guard try unique("state") == state else { throw ManagedAccountError.invalidCallback }
        let code = try unique("code"), error = try unique("error")
        if error == "access_denied", code == nil { throw ManagedAccountError.cancelled }
        guard error == nil, let code, !code.isEmpty, code.utf8.count <= 4096,
              !code.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
        else { throw ManagedAccountError.invalidCallback }
        return code
    }
    public func tokenBody(code: String) -> Data {
        Self.form([("client_id", clientID), ("redirect_uri", redirectURI.absoluteString),
                   ("code", code), ("code_verifier", verifier), ("grant_type", "authorization_code")])
    }
    public static func form(_ fields: [(String, String)]) -> Data {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        return Data(fields.map { key, value in
            key.addingPercentEncoding(withAllowedCharacters: allowed)! + "=" + value.addingPercentEncoding(withAllowedCharacters: allowed)!
        }.joined(separator: "&").utf8)
    }
}

/// Invalidates asynchronous completions when a view closes or the user signs out.
public struct ManagedAccountOperationGate: Sendable {
    private var generation: UInt64 = 0
    public init() {}
    public mutating func begin() -> UInt64 { generation &+= 1; return generation }
    public mutating func cancel() { generation &+= 1 }
    public func accepts(_ token: UInt64) -> Bool { generation == token }
}
