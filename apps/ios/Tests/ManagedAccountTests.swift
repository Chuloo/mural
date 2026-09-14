import XCTest
@testable import MuralCore

final class ManagedAccountTests: XCTestCase {
    private let client = "123-example.apps.googleusercontent.com"
    private let scheme = "com.googleusercontent.apps.123-example"
    private let nonce = String(repeating: "a", count: 64)
    private let state = String(repeating: "s", count: 43)
    private func config(api: String = "https://accounts.example.test", enabled: Bool = true,
                        schemes: [String]? = nil, apple: String = "chat.mural.test") throws -> ManagedAccountConfiguration {
        try ManagedAccountConfiguration(apiURL: api, googleClientID: client, appleClientID: apple,
            bundleID: "chat.mural.test", registeredURLSchemes: schemes ?? [scheme], appleCapabilityEnabled: enabled)
    }
    private func flow() throws -> ManagedGoogleAuthorization {
        try ManagedGoogleAuthorization(configuration: config(), verifier: String(repeating: "v", count: 43), state: state, nonce: nonce)
    }
    func testDeploymentRejectsMissingCapabilityAndUntrustedOrigins() throws {
        XCTAssertThrowsError(try config(enabled: false))
        XCTAssertThrowsError(try config(schemes: []))
        XCTAssertThrowsError(try config(apple: "different.bundle"))
        for url in ["http://example.test", "https://user:pass@example.test", "https://example.test/api", "https://example.test?token=abc", "https://example.test/#fragment"] {
            XCTAssertThrowsError(try config(api: url), url)
        }
        XCTAssertEqual(try config(api: "https://accounts.example.test/").origin.absoluteString, "https://accounts.example.test")
        XCTAssertThrowsError(try config().endpoint("https://attacker.example.test/v1/wallet"))
        XCTAssertThrowsError(try config().endpoint("//attacker.example.test"))
    }
    func testGoogleCanBeConfiguredWithoutAppleCapability() throws {
        let google = try ManagedAccountConfiguration(apiURL: "https://accounts.example.test", googleClientID: client,
            bundleID: "chat.mural.test", registeredURLSchemes: [scheme], appleCapabilityEnabled: false)
        XCTAssertEqual(google.providers, [.google])
        XCTAssertNil(google.appleClientID)
        XCTAssertEqual(google.googleRedirectURI?.absoluteString, scheme + ":/oauth2redirect")
        // Adding a provider preserves the account's server and app binding.
        XCTAssertEqual(google.storageScope, try config().storageScope)
        XCTAssertThrowsError(try ManagedAccountConfiguration(apiURL: "https://accounts.example.test", googleClientID: client,
            bundleID: "chat.mural.test", registeredURLSchemes: [], appleCapabilityEnabled: false))
    }
    func testAppleOnlyRequiresCapabilityAndCannotStartGoogleAuthorization() throws {
        let apple = try ManagedAccountConfiguration(apiURL: "https://accounts.example.test", appleClientID: "chat.mural.test",
            bundleID: "chat.mural.test", registeredURLSchemes: [], appleCapabilityEnabled: true)
        XCTAssertEqual(apple.providers, [.apple])
        XCTAssertNil(apple.googleRedirectURI)
        XCTAssertThrowsError(try ManagedGoogleAuthorization(configuration: apple, verifier: String(repeating: "v", count: 43), state: state, nonce: nonce))
        XCTAssertThrowsError(try ManagedAccountConfiguration(apiURL: "https://accounts.example.test", appleClientID: "chat.mural.test",
            bundleID: "chat.mural.test", registeredURLSchemes: [], appleCapabilityEnabled: false))
        XCTAssertThrowsError(try ManagedAccountConfiguration(apiURL: "https://accounts.example.test",
            bundleID: "chat.mural.test", registeredURLSchemes: [], appleCapabilityEnabled: true))
    }
    func testPKCES256MatchesRFC7636VectorAndBindsRawNonce() throws {
        XCTAssertEqual(ManagedGoogleAuthorization.codeChallenge(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
                       "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let query = URLComponents(url: try flow().authorizationURL, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(query.first { $0.name == "nonce" }?.value, nonce)
        XCTAssertEqual(query.first { $0.name == "code_challenge_method" }?.value, "S256")
        XCTAssertEqual(query.first { $0.name == "scope" }?.value, "openid email")
        XCTAssertNil(query.first { $0.name == "access_type" })
    }
    func testOAuthCallbackRejectsSpoofedOriginStateAndAmbiguity() throws {
        let flow = try flow(), base = scheme + ":/oauth2redirect"
        XCTAssertEqual(try flow.authorizationCode(from: URL(string: base + "?state=\(state)&code=a%2Bb%2Fc&scope=openid")!), "a+b/c")
        for candidate in [
            "evil:/oauth2redirect?state=\(state)&code=x", scheme + "://attacker/oauth2redirect?state=\(state)&code=x",
            base + "?state=wrong&code=x", base + "?state=\(state)&state=\(state)&code=x",
            base + "?state=\(state)&code=a&code=b", base + "?state=\(state)&code=x&error=access_denied",
            base + "?state=\(state)&code=x#ignored", base + "?state=\(state)&code=%0A",
            scheme + ":/other?state=\(state)&code=x"
        ] { XCTAssertThrowsError(try flow.authorizationCode(from: URL(string: candidate)!)) }
        XCTAssertThrowsError(try flow.authorizationCode(from: URL(string: base + "?state=\(state)&error=access_denied")!)) {
            XCTAssertEqual($0 as? ManagedAccountError, .cancelled)
        }
        let body = String(data: flow.tokenBody(code: "a+b&c= d"), encoding: .utf8)!
        XCTAssertTrue(body.contains("code=a%2Bb%26c%3D%20d"))
        XCTAssertFalse(body.contains("client_secret"))
    }
    func testSessionExpiryAndBackendBinding() throws {
        let token = String(repeating: "x", count: 43)
        let data = Data("{\"accountID\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"accessToken\":\"\(token)\",\"expiresInSeconds\":86400}".utf8)
        let exchange = try JSONDecoder().decode(ManagedAuthExchange.self, from: data)
        let now = Date(timeIntervalSince1970: 1_000)
        let session = try ManagedAccountSession(exchange: exchange, provider: .google, scope: config().storageScope, now: now)
        XCTAssertTrue(session.isUsable(scope: try config().storageScope, now: now))
        XCTAssertFalse(session.isUsable(scope: try config(api: "https://other.example.test").storageScope, now: now))
        XCTAssertFalse(session.isUsable(scope: try config().storageScope, now: now.addingTimeInterval(86_400)))
        XCTAssertFalse(session.isUsable(scope: try config().storageScope, now: now.addingTimeInterval(-1)))
        let malformed = Data("{\"accountID\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"accessToken\":\"bad\\nheader\",\"expiresInSeconds\":86400}".utf8)
        XCTAssertThrowsError(try JSONDecoder().decode(ManagedAuthExchange.self, from: malformed).validate())
    }
    func testAccountProfileBindsIdentityAndAcceptsPrivateEmail() throws {
        let id = UUID()
        let exchange = try JSONDecoder().decode(ManagedAuthExchange.self, from: JSONSerialization.data(withJSONObject: [
            "accountID": id.uuidString, "accessToken": String(repeating: "x", count: 43), "expiresInSeconds": 86400
        ]))
        let session = try ManagedAccountSession(exchange: exchange, provider: .google, scope: config().storageScope)
        func profile(id: UUID, email: Any = NSNull(), providers: [String] = ["google"], date: String = "2026-09-12T12:34:56.123Z") throws -> ManagedAccountProfile {
            try JSONDecoder().decode(ManagedAccountProfile.self, from: JSONSerialization.data(withJSONObject: [
                "accountID": id.uuidString, "email": email, "providers": providers, "createdAt": date
            ]))
        }
        try profile(id: id).validate(session: session)
        try profile(id: id, email: "learner@privaterelay.appleid.com", providers: ["google", "apple"], date: "2026-09-12T12:34:56Z").validate(session: session)
        XCTAssertThrowsError(try profile(id: UUID()).validate(session: session))
        XCTAssertThrowsError(try profile(id: id, providers: ["apple"]).validate(session: session))
        XCTAssertThrowsError(try profile(id: id, providers: ["google", "google"]).validate(session: session))
        XCTAssertThrowsError(try profile(id: id, email: "learner@example.test\nOther identity").validate(session: session))
        XCTAssertThrowsError(try profile(id: id, date: "not a timestamp").validate(session: session))
    }
    func testWalletPreservesLargeIntegerAmountsAndRejectsInconsistentMath() throws {
        func wallet(_ balance: String, _ reserved: String, _ available: String) throws -> ManagedWallet {
            let data = try JSONSerialization.data(withJSONObject: ["currency": "USD", "balanceNanoUSD": balance, "reservedNanoUSD": reserved, "availableNanoUSD": available])
            return try JSONDecoder().decode(ManagedWallet.self, from: data)
        }
        try wallet("9007199254740993", "1", "9007199254740992").validate()
        try wallet("-10", "0", "-10").validate()
        for values in [("100", "2", "99"), ("1", "-1", "2"), ("1e9", "0", "1e9"),
                       ("01", "0", "1"), ("9223372036854775808", "0", "9223372036854775808"),
                       ("-9223372036854775808", "1", "9223372036854775807")] {
            XCTAssertThrowsError(try wallet(values.0, values.1, values.2).validate())
        }
    }
    func testCancelledOperationCannotRestoreAccount() {
        var gate = ManagedAccountOperationGate()
        let stale = gate.begin(); gate.cancel()
        XCTAssertFalse(gate.accepts(stale))
        let fresh = gate.begin()
        XCTAssertTrue(gate.accepts(fresh)); XCTAssertFalse(gate.accepts(stale))
    }
    func testChallengeRejectsMalformedNonceAndExcessiveLifetime() throws {
        for pair in [("wrong", 300), (nonce, 0), (nonce, 301)] {
            let data = try JSONSerialization.data(withJSONObject: ["challengeID": UUID().uuidString, "nonce": pair.0, "expiresInSeconds": pair.1])
            XCTAssertThrowsError(try JSONDecoder().decode(ManagedAuthChallenge.self, from: data).validate())
        }
    }
}
