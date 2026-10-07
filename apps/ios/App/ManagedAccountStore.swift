import Foundation
import Observation
import MuralCore

@MainActor @Observable
final class ManagedAccountStore {
    let configuration: ManagedAccountConfiguration?
    private(set) var session: ManagedAccountSession?
    private(set) var profile: ManagedAccountProfile?
    private(set) var hostedBalanceMilliseconds: Int?
    private(set) var hostedBalance: HostedBalance?
    private(set) var isBusy = false
    var message: String?
    @ObservationIgnored private let client: ManagedAccountClient?
    @ObservationIgnored private let keychain: ManagedAccountKeychain?
    @ObservationIgnored private let identity = ManagedAccountIdentity()
    @ObservationIgnored private var operation: Task<Void, Never>?
    @ObservationIgnored private var deletingGuest = false
    @ObservationIgnored private var gate = ManagedAccountOperationGate()
    @ObservationIgnored private var balanceRevision: (account: UUID, revision: UInt64)?
    private let isPreview: Bool

    init(configuration: ManagedAccountConfiguration? = .load()) {
        #if DEBUG && targetEnvironment(simulator)
        isPreview = ProcessInfo.processInfo.arguments.contains("--preview")
        #else
        isPreview = false
        #endif
        self.configuration = configuration
        client = configuration.map(ManagedAccountClient.init)
        keychain = configuration.map { ManagedAccountKeychain(scope: $0.storageScope) }
        // Screenshot and UI-test fixtures never read a real account or create a server session.
        if isPreview {
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("--preview-member") || arguments.contains("--preview-apple") ||
                arguments.contains("--preview-paid-member") || arguments.contains("--preview-paid-only") ||
                arguments.contains("--preview-paid-reserved") {
                let apple = arguments.contains("--preview-apple")
                let id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                let exchangeData = Data("{\"accountID\":\"\(id)\",\"accessToken\":\"\(String(repeating: "x", count: 43))\",\"expiresInSeconds\":86400}".utf8)
                if let exchange = try? JSONDecoder().decode(ManagedAuthExchange.self, from: exchangeData) {
                    session = try? ManagedAccountSession(exchange: exchange, provider: apple ? .apple : .google,
                                                         scope: configuration?.storageScope ?? "preview")
                }
                let address = apple ? "a.very.long.private.relay.address.for.layout@privaterelay.appleid.com" : "preview@example.test"
                let profileData = Data("{\"accountID\":\"\(id)\",\"email\":\"\(address)\",\"providers\":[\"\(apple ? "apple" : "google")\"],\"createdAt\":\"2026-09-25T12:00:00Z\"}".utf8)
                profile = try? JSONDecoder().decode(ManagedAccountProfile.self, from: profileData)
                if arguments.contains("--preview-paid-member") || arguments.contains("--preview-paid-only") ||
                    arguments.contains("--preview-paid-reserved") {
                    hostedBalance = HostedBalance.preview(paidOnly: !arguments.contains("--preview-paid-member"),
                                                          reserved: arguments.contains("--preview-paid-reserved"))
                    hostedBalanceMilliseconds = hostedBalance?.availableMilliseconds
                } else {
                    hostedBalanceMilliseconds = 534_000
                    hostedBalance = try? HostedBalance(["unit": "milliseconds", "billingBasis": "connected-conversation-time",
                                                         "balanceMilliseconds": 534_000, "reservedMilliseconds": 0,
                                                         "availableMilliseconds": 534_000])
                }
            }
        } else {
            do { session = try keychain?.load() }
            catch { message = Self.message(for: error) }
        }
    }
    func signIn(_ provider: ManagedIdentityProvider) {
        guard !isPreview else { message = "Sign-in isn’t available in this preview."; return }
        guard !isBusy, session == nil, let client, let configuration, let keychain,
              configuration.providers.contains(provider) else { return }
        run { [self] token in
            // Claim this installation's trial before account creation so a new member can
            // keep its unused minutes. Existing members remain subject to the server's limit.
            if HostedClient.shared != nil { _ = try? await GuestAccess.shared.owner(member: nil) }
            let challenge = try await client.challenge()
            let expiresAt = Date.now.addingTimeInterval(TimeInterval(challenge.expiresInSeconds))
            let credential = try await (provider == .google
                ? identity.google(configuration: configuration, nonce: challenge.nonce, http: client.http)
                : identity.apple(nonce: challenge.nonce))
            guard gate.accepts(token), Date.now < expiresAt else { throw ManagedAccountError.cancelled }
            let exchanged = try await client.exchange(provider: provider, token: credential.idToken, challenge: challenge)
            let newSession = try ManagedAccountSession(exchange: exchanged, provider: provider, scope: configuration.storageScope)
            guard gate.accepts(token), !Task.isCancelled else {
                try? await client.signOut(session: newSession); return
            }
            do { try keychain.save(newSession) }
            catch { try? await client.signOut(session: newSession); throw error }
            session = newSession
            Task { await HostedCloseRecovery.shared.resume() }
            let result = try await client.profile(session: newSession)
            guard gate.accepts(token) else { return }
            profile = result
            if let hosted = HostedClient.shared {
                let owner = HostedOwner(accountID: newSession.accountID, accessToken: newSession.accessToken, expiresAt: newSession.expiresAt)
                let balance = try await refreshHostedBalance(hosted, owner: owner)
                guard gate.accepts(token), session?.accountID == newSession.accountID else { return }
                acceptBalance(balance, account: newSession.accountID)
            }
        }
    }
    func refresh() {
        guard !isPreview else { return }
        guard !isBusy, let client, let session else { return }
        hostedBalanceMilliseconds = nil
        hostedBalance = nil
        run { [self] token in
            let result = try await client.profile(session: session)
            guard gate.accepts(token) else { return }
            profile = result
            if let hosted = HostedClient.shared {
                let owner = HostedOwner(accountID: session.accountID, accessToken: session.accessToken, expiresAt: session.expiresAt)
                let balance = try await refreshHostedBalance(hosted, owner: owner)
                guard gate.accepts(token), self.session?.accountID == session.accountID else { return }
                acceptBalance(balance, account: session.accountID)
            }
        }
    }
    private func refreshHostedBalance(_ hosted: HostedClient, owner: HostedOwner) async throws -> HostedBalance {
        do { try await GuestAccess.shared.linkIfNeeded(to: owner) }
        catch {
            // GuestAccess keeps its pending link until the server confirms transfer.
            // A delayed guest transfer must not hide this member's existing credit.
            if error is CancellationError || error as? ManagedAccountError == .cancelled ||
                (error as? URLError)?.code == .cancelled { throw ManagedAccountError.cancelled }
        }
        try Task.checkCancellation()
        return try await hosted.balance(owner)
    }
    func connectGoogle() {
        guard !isPreview, !isBusy, let session, let client, let configuration,
              configuration.providers.contains(.google), profile?.providers.contains(.apple) == true else { return }
        run { [self] token in
            let appleChallenge = try await client.challenge()
            let apple = try await identity.apple(nonce: appleChallenge.nonce)
            guard gate.accepts(token), self.session?.accountID == session.accountID else { throw ManagedAccountError.cancelled }
            let googleChallenge = try await client.challenge()
            let google = try await identity.google(configuration: configuration, nonce: googleChallenge.nonce, http: client.http)
            guard gate.accepts(token), self.session?.accountID == session.accountID else { throw ManagedAccountError.cancelled }
            try await client.connectGoogle(session: session, apple: apple.idToken, appleChallenge: appleChallenge,
                                           google: google.idToken, googleChallenge: googleChallenge)
            let refreshed = try await client.profile(session: session)
            guard gate.accepts(token), self.session?.accountID == session.accountID else { return }
            profile = refreshed; message = "Google is connected. Use it to sign in to Mural on Android."
        }
    }
    func refreshAndWait() async {
        refresh()
        await operation?.value
    }
    private func acceptBalance(_ balance: HostedBalance?, account: UUID) {
        if let previous = balanceRevision, previous.account == account {
            guard let revision = balance?.presentation?.revisionNumber, revision >= previous.revision else {
                hostedBalance = nil; hostedBalanceMilliseconds = nil; return
            }
        }
        if let revision = balance?.presentation?.revisionNumber { balanceRevision = (account, revision) }
        hostedBalance = balance; hostedBalanceMilliseconds = balance?.availableMilliseconds
    }
    func signOut() {
        guard !isPreview else { message = "Preview account actions don’t change a real account."; return }
        guard !isBusy, let client, let session, let keychain else { return }
        run { [self] token in
            var remoteFailed = false
            do { try await client.signOut(session: session) }
            catch let error as ManagedAccountError where error == .server("sign_in_required") { /* Already expired or revoked. */ }
            catch { remoteFailed = true }
            guard gate.accepts(token) else { return }
            try keychain.remove()
            self.session = nil; profile = nil; hostedBalanceMilliseconds = nil; hostedBalance = nil
            if remoteFailed { message = "Signed out on this iPhone. We couldn’t reach Mural to revoke other sessions; they expire within 24 hours." }
        }
    }
    func deleteAccount() {
        guard !isPreview else { message = "Preview account actions don’t change a real account."; return }
        guard !isBusy, let client, let session, let keychain else { return }
        run { [self] token in
            var code: String?
            // A Google session may belong to an account that also linked Apple.
            // Refresh providers before deletion; the server is the source of truth.
            let currentProfile = try await client.profile(session: session)
            if currentProfile.providers.contains(.apple) {
                // A fresh code lets the server verify this Apple identity and revoke its authorization.
                code = try await identity.apple(nonce: ManagedAccountIdentity.random()).authorizationCode
            }
            guard gate.accepts(token) else { return }
            try await client.delete(session: session, appleCode: code)
            guard gate.accepts(token) else { return }
            self.session = nil; profile = nil; hostedBalanceMilliseconds = nil; hostedBalance = nil
            do { try keychain.remove() }
            catch {
                message = "Account deleted. Mural couldn’t clear its local secure sign-in record. Unlock this iPhone and reopen Account to clear it."
                return
            }
            message = "Account deleted. Your learning history remains on this iPhone."
        }
    }
    var hasGuestAccount: Bool { GuestAccess.shared.hasUnlinkedAccount }
    func deleteGuestAccount() {
        guard !isPreview, !isBusy, session == nil, let client else { return }
        deletingGuest = true
        run { [self] _ in
            defer { deletingGuest = false }
            try await GuestAccess.shared.deleteAccount(using: client)
            message = "Guest account deleted. Your learning history remains on this iPhone."
        }
    }
    /// Close pending sign-in on dismissal. Account mutations finish so their result is not ambiguous.
    func cancelSignIn() {
        guard session == nil, !deletingGuest else { return }
        gate.cancel(); operation?.cancel(); identity.cancel(); operation = nil; isBusy = false
    }
    private func run(_ body: @escaping @MainActor (UInt64) async throws -> Void) {
        let token = gate.begin(); isBusy = true; message = nil
        operation = Task { [self] in
            defer { if gate.accepts(token) { isBusy = false; operation = nil } }
            do { try await body(token) }
            catch {
                guard gate.accepts(token) else { return }
                if error as? ManagedAccountError == .server("sign_in_required") {
                    session = nil; profile = nil
                    do { try keychain?.remove() } catch { message = Self.message(for: error); return }
                }
                if error as? ManagedAccountError != .cancelled, !(error is CancellationError) {
                    message = Self.message(for: error)
                }
            }
        }
    }
    private static func message(for error: Error) -> String {
        if let hosted = error as? HostedError { return hosted.localizedDescription }
        return switch error as? ManagedAccountError {
        case .secureStorage: "Mural couldn’t update this iPhone’s secure account storage. Unlock the iPhone and try again."
        case .server("account_usage_pending"), .server("unresolved_billing"):
            "End your conversation and wait for its minutes to update, then try deleting your account again."
        case .server("sign_in_required"): "Please sign in again. Your learning history is still on this iPhone."
        case .server("rate_limit"): "Please try again later."
        case .server("same_account_required"): "Use the Apple account already connected to this Mural account."
        case .server("identity_link_conflict"): "That Google account is already connected to another Mural account. Contact hi@hackmamba.io for help. Your minutes haven’t moved."
        case .server("apple_sign_in_not_ready"), .server("apple_revocation_not_configured"):
            "Sign in with Apple is temporarily unavailable. Please try again later."
        case .server("identity_provider_not_configured"), .unavailable:
            "This sign-in option is unavailable. Please try again later."
        case .purchaseVerificationUnavailable:
            "Mural couldn’t verify Apple purchases right now. Please try again later."
        case .invalidCallback, .invalidResponse, .server("invalid_challenge"):
            "Sign-in couldn’t be verified. Please start again."
        default: "Mural couldn’t complete that request. Check your connection and try again."
        }
    }
}
