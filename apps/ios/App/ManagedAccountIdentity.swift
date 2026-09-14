import AuthenticationServices
import Security
import UIKit
import MuralCore

@MainActor
final class ManagedAccountIdentity: NSObject, ASWebAuthenticationPresentationContextProviding,
                                    ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    struct Credential { let idToken: String; let authorizationCode: String? }
    private var webSession: ASWebAuthenticationSession?
    private var appleController: ASAuthorizationController?
    private var appleContinuation: CheckedContinuation<Credential, Error>?
    private var webContinuation: CheckedContinuation<URL, Error>?
    private var webID: UUID?
    private var anchor: ASPresentationAnchor?
    private var appleState: String?

    static func random() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw ManagedAccountError.unavailable }
        return Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    private func prepareAnchor() throws {
        guard webContinuation == nil, appleContinuation == nil,
              let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                .filter({ $0.activationState == .foregroundActive }).flatMap(\.windows).first(where: \.isKeyWindow)
        else { throw ManagedAccountError.unavailable }
        anchor = window
    }
    func google(configuration: ManagedAccountConfiguration, nonce: String, http: ManagedAccountHTTP) async throws -> Credential {
        try prepareAnchor()
        let flow = try ManagedGoogleAuthorization(configuration: configuration, verifier: Self.random(), state: Self.random(), nonce: nonce)
        let callback: URL = try await withCheckedThrowingContinuation { continuation in
            webContinuation = continuation
            let id = UUID(); webID = id
            let session = ASWebAuthenticationSession(url: flow.authorizationURL,
                callbackURLScheme: flow.redirectURI.scheme) { [weak self] url, error in
                Task { @MainActor in
                    guard let self, self.webID == id, let pending = self.webContinuation else { return }
                    self.webContinuation = nil; self.webSession = nil; self.webID = nil
                    if let url, error == nil { pending.resume(returning: url) }
                    else if (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin {
                        pending.resume(throwing: ManagedAccountError.cancelled)
                    } else { pending.resume(throwing: ManagedAccountError.transport) }
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = true
            webSession = session
            if !session.start() {
                webContinuation = nil; webSession = nil; webID = nil
                continuation.resume(throwing: ManagedAccountError.unavailable)
            }
        }
        try Task.checkCancellation()
        let code = try flow.authorizationCode(from: callback)
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"; request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = flow.tokenBody(code: code)
        let (data, response) = try await http.send(request)
        struct Token: Decodable { let id_token: String }
        guard response.statusCode == 200, let token = try? JSONDecoder().decode(Token.self, from: data),
              !token.id_token.isEmpty, token.id_token.utf8.count <= 16_384 else { throw ManagedAccountError.invalidResponse }
        // Server verifies signature, audience, issuer and nonce. No client JWT claims grant access.
        return Credential(idToken: token.id_token, authorizationCode: nil)
    }
    func apple(nonce: String) async throws -> Credential {
        try prepareAnchor()
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.email]
        request.nonce = nonce // Raw server challenge. The server hashes the returned nonce once.
        request.state = try Self.random(); appleState = request.state
        return try await withCheckedThrowingContinuation { continuation in
            appleContinuation = continuation
            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self; controller.presentationContextProvider = self
            appleController = controller; controller.performRequests()
        }
    }
    func cancel() {
        webSession?.cancel(); appleController?.cancel()
        webSession = nil; appleController = nil
        let web = webContinuation, apple = appleContinuation
        webContinuation = nil; appleContinuation = nil; appleState = nil; webID = nil
        web?.resume(throwing: ManagedAccountError.cancelled)
        apple?.resume(throwing: ManagedAccountError.cancelled)
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor { anchor! }
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor { anchor! }
    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        guard controller === appleController, let pending = appleContinuation else { return }
        appleContinuation = nil; appleController = nil
        defer { appleState = nil }
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let expected = appleState, credential.state == expected,
              let tokenData = credential.identityToken, let token = String(data: tokenData, encoding: .utf8),
              !token.isEmpty, token.utf8.count <= 16_384,
              let codeData = credential.authorizationCode, let code = String(data: codeData, encoding: .utf8),
              !code.isEmpty, code.utf8.count <= 4096 else {
            pending.resume(throwing: ManagedAccountError.invalidResponse); return
        }
        pending.resume(returning: Credential(idToken: token, authorizationCode: code))
    }
    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        guard controller === appleController, let pending = appleContinuation else { return }
        appleContinuation = nil; appleController = nil; appleState = nil
        pending.resume(throwing: (error as? ASAuthorizationError)?.code == .canceled ? ManagedAccountError.cancelled : .transport)
    }
}
