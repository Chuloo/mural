import Foundation
import Security
import CryptoKit
import MuralCore

enum ConversationProvider: String, Hashable { case hosted, personalKey }

enum HostedError: LocalizedError {
    case unavailable, invalidResponse, secureStorage, signInRequired, noMinutes, unconfirmed, personalKeyRequired, server(String, retryable: Bool)
    var needsSignInRecovery: Bool {
        switch self {
        case .signInRequired, .server("sign_in_required", _), .server("sign_in_to_continue", _): true
        default: false
        }
    }
    var errorDescription: String? {
        return switch self {
        case .unavailable: "Mural conversations are unavailable right now. Try again later or use your own API key."
        case .invalidResponse: "Mural couldn’t verify the server response. Please try again."
        case .secureStorage: "Mural couldn’t read this iPhone’s secure trial record. Unlock your iPhone and try again."
        case .signInRequired: "Sign in to continue using Mural minutes, or use your own API key."
        case .noMinutes: "No Mural minutes are available for a new conversation. Check Account or use your own API key."
        case .unconfirmed: "Mural is checking an earlier conversation. Please try again shortly."
        case .personalKeyRequired: "Current topics need your API key. Add one in Settings and switch to Your key."
        case .server(let code, _):
            switch code {
            case "insufficient_minutes", "insufficient_credit": HostedError.noMinutes.errorDescription
            case "sign_in_required", "sign_in_to_continue": HostedError.signInRequired.errorDescription
            case "live_session_unresolved", "provider_session_unconfirmed", "live_request_already_created": HostedError.unconfirmed.errorDescription
            default: HostedError.unavailable.errorDescription
            }
        }
    }
    var meaningGuidance: MeaningRequestFailure {
        switch self {
        case .server("helper_session_limit", let retryable):
            return retryable
                ? MeaningRequestFailure("Meaning is busy. Wait a moment, then try again.")
                : MeaningRequestFailure("This conversation has reached its limit for extra meanings.", canRetry: false)
        case .server("helper_concurrency_limit", _):
            return MeaningRequestFailure("Meaning is busy. Wait a moment, then try again.")
        case .server("helper_budget_exhausted", _):
            return MeaningRequestFailure("Meaning isn’t available yet. Keep talking, then try again.")
        case .server("helper_session_window_closed", _):
            return MeaningRequestFailure("Start a new conversation for more meanings.", canRetry: false)
        case .server("helper_session_funding_unavailable", _):
            return MeaningRequestFailure("Extra meanings aren’t available in this conversation.", canRetry: false)
        case .server("helper_output_refused", _):
            return MeaningRequestFailure("Mural couldn’t explain that passage. Try another one.", canRetry: false)
        case .server("helper_output_incomplete", _):
            return MeaningRequestFailure("That meaning was incomplete. Please try again.")
        case .signInRequired, .server("sign_in_required", _), .server("sign_in_to_continue", _):
            return MeaningRequestFailure("Sign in to keep using Mural meanings.", canRetry: false)
        default:
            return MeaningRequestFailure("Meaning is temporarily unavailable. You can keep talking and try again.")
        }
    }
}

struct MeaningRequestFailure: LocalizedError, MeaningRetryGuidance {
    let message: String
    let retryMeaningAllowed: Bool
    init(_ message: String, canRetry: Bool = true) {
        self.message = message; retryMeaningAllowed = canRetry
    }
    var errorDescription: String? { message }
}

struct HostedOwner: Codable, Sendable {
    let accountID: UUID
    let accessToken: String
    let expiresAt: Date
    var usable: Bool { expiresAt > .now && accessToken.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil }
}

struct HostedBalance {
    let presentation: MuralMinutesPresentation?
    let availableMilliseconds: Int
    let paidEstimatedMilliseconds: Int?
    let paidAvailable: Bool
    let hasPaidRemainder: Bool
    let paidReserved: Bool
    var canStart: Bool { presentation.map { $0.availabilityReason == "ready" } ?? (availableMilliseconds > 0 || paidAvailable) }
    var totalDisplayMilliseconds: Int? {
        guard let paidEstimatedMilliseconds else { return hasPaidRemainder ? nil : availableMilliseconds }
        let (sum, overflow) = availableMilliseconds.addingReportingOverflow(paidEstimatedMilliseconds)
        return overflow ? nil : sum
    }
    var displayText: String {
        if let presentation { return presentation.displayText }
        guard let total = totalDisplayMilliseconds else { return "Couldn’t check your minutes" }
        if total == 0 && paidReserved { return "Updating your minutes…" }
        if hasPaidRemainder {
            let minutes = total / 60_000
            return minutes > 0 ? "About \(minutes) min" : "Less than 1 min"
        }
        guard total > 0 else { return "No minutes left" }
        let seconds = MinuteBalanceTime.roundedSeconds(total)
        return "\(seconds / 60) min \(seconds % 60) sec"
    }
    init(_ value: [String: Any]) throws {
        guard value["unit"] as? String == "milliseconds", value["billingBasis"] as? String == "connected-conversation-time",
              let balance = value["balanceMilliseconds"] as? Int, let reserved = value["reservedMilliseconds"] as? Int,
              let available = value["availableMilliseconds"] as? Int,
              balance >= 0, reserved >= 0, reserved <= balance, available == balance - reserved
        else { throw HostedError.invalidResponse }
        availableMilliseconds = available
        if let paid = value["paid"] as? [String: Any] {
            guard paid["billingBasis"] as? String == "actual-ai-usage",
                  let balanceText = paid["balanceNanoUSD"] as? String,
                  let reservedText = paid["reservedNanoUSD"] as? String,
                  let availableText = paid["availableNanoUSD"] as? String,
                  let balanceValue = Int64(balanceText), let reservedValue = Int64(reservedText), reservedValue >= 0,
                  let availableValue = Int64(availableText), availableValue >= 0,
                  availableValue == (balanceValue > reservedValue ? balanceValue - reservedValue : 0),
                  let estimate = paid["estimatedMilliseconds"] as? Int, estimate >= 0,
                  let minimumText = paid["minimumSessionNanoUSD"] as? String,
                  let minimum = Int64(minimumText), minimum > 0,
                  let eligible = paid["available"] as? Bool,
                  eligible == (availableValue >= minimum)
            else { throw HostedError.invalidResponse }
            paidEstimatedMilliseconds = estimate
            paidAvailable = eligible
            hasPaidRemainder = availableValue > 0
            paidReserved = reservedValue > 0
        } else {
            paidEstimatedMilliseconds = nil
            paidAvailable = false
            hasPaidRemainder = false
            paidReserved = false
        }
        if let projection = value["presentation"] {
            presentation = try JSONDecoder().decode(MuralMinutesPresentation.self, from: JSONSerialization.data(withJSONObject: projection))
            try presentation!.validate()
            guard presentation!.freeAvailableMilliseconds == availableMilliseconds,
                  presentation!.hasPurchasedRemainder == hasPaidRemainder else { throw HostedError.invalidResponse }
            if presentation!.paidSupported {
                guard paidEstimatedMilliseconds != nil, presentation!.paidEstimatedMilliseconds == paidEstimatedMilliseconds else { throw HostedError.invalidResponse }
            } else if paidEstimatedMilliseconds != nil { throw HostedError.invalidResponse }
        } else { presentation = nil }
    }
    static func preview(paidOnly: Bool, reserved: Bool = false) -> HostedBalance? {
        let free = paidOnly ? 0 : 260_000
        return try? HostedBalance(["unit": "milliseconds", "billingBasis": "connected-conversation-time",
                                   "balanceMilliseconds": free, "reservedMilliseconds": 0,
                                   "availableMilliseconds": free,
                                   "paid": ["billingBasis": "actual-ai-usage", "balanceNanoUSD": "3690000000",
                                            "reservedNanoUSD": reserved ? "3690000000" : "0",
                                            "availableNanoUSD": reserved ? "0" : "3690000000",
                                            "estimatedMilliseconds": reserved ? 0 : 2_214_000,
                                            "minimumSessionNanoUSD": "100000000", "available": !reserved]])
    }
}

enum HostedFunding: String { case minutes, aiValue = "ai-value" }

struct HostedLease {
    let sessionID: UUID
    let owner: HostedOwner
    let deadline: Date
    let providerSessionID: String
    let answerSDP: String
    let funding: HostedFunding
    let limitMilliseconds: Int
    let minimumChargeMilliseconds: Int
}

/// The same HTTPS origin is used for guest admission, member minutes and hosted voice.
@MainActor final class HostedClient {
    static let shared: HostedClient? = try? HostedClient()
    let origin: URL
    private let http = ManagedAccountHTTP()

    private init(bundle: Bundle = .main) throws {
        guard let value = bundle.object(forInfoDictionaryKey: "MuralManagedAPIURL") as? String,
              let components = URLComponents(string: value), components.scheme == "https",
              components.host != nil, components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/", let url = components.url
        else { throw HostedError.unavailable }
        origin = url
    }
    private func request(_ path: String, method: String = "GET", body: [String: Any]? = nil,
                         owner: HostedOwner? = nil, key: UUID? = nil) async throws -> [String: Any] {
        guard path.hasPrefix("/v1/"), !path.contains(".."), !path.contains("?"),
              var parts = URLComponents(url: origin, resolvingAgainstBaseURL: false)
        else { throw HostedError.invalidResponse }
        parts.path = path
        guard let url = parts.url else { throw HostedError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let owner {
            guard owner.usable else { throw HostedError.signInRequired }
            request.setValue("Bearer " + owner.accessToken, forHTTPHeaderField: "Authorization")
        }
        if let key { request.setValue(key.uuidString.lowercased(), forHTTPHeaderField: "Idempotency-Key") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await http.send(request)
        guard let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw HostedError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else {
            let code = (value["error"] as? [String: Any])?["code"] as? String ?? "service_unavailable"
            if response.statusCode == 401 && code != "invalid_guest_session" { throw HostedError.signInRequired }
            let retryable = (value["error"] as? [String: Any])?["retryable"] as? Bool == true
            throw HostedError.server(code, retryable: retryable)
        }
        return value
    }
    func guest(installationToken: String) async throws -> [String: Any] {
        try await request("/v1/guest/minutes", method: "POST", body: ["installationToken": installationToken])
    }
    func linkGuest(_ guest: HostedOwner?, guestID: UUID, member: HostedOwner) async throws -> [String: Any] {
        var body: [String: Any] = ["guestAccountID": guestID.uuidString.lowercased(), "deferPending": true]
        if let guest { body["guestAccessToken"] = guest.accessToken }
        return try await request("/v1/minutes/link-guest", method: "POST", body: body, owner: member)
    }
    func balance(_ owner: HostedOwner) async throws -> HostedBalance {
        try HostedBalance(await request("/v1/minutes", owner: owner))
    }
    func available(_ owner: HostedOwner) async throws -> Bool {
        let value = try await request("/v1/live/capabilities", owner: owner)
        guard let available = value["hostedMinutes"] as? Bool else { throw HostedError.invalidResponse }
        return available && value["experimental"] as? Bool == true
    }
    func create(owner: HostedOwner, sdp: String, language: String, instructions: String,
                requestedMilliseconds: Int, requestID: UUID, history: [[String: Any]] = []) async throws -> HostedLease {
        guard sdp.hasPrefix("v=0"), sdp.utf8.count <= 65_536, instructions.utf8.count <= 12_000,
              history.count <= 40, (try JSONSerialization.data(withJSONObject: history)).count <= 6_000,
              (60_000...3_600_000).contains(requestedMilliseconds) else { throw HostedError.invalidResponse }
        try HostedCloseRecovery.shared.begin(requestID: requestID, owner: owner)
        let value: [String: Any]
        do { value = try await request("/v1/live/sessions", method: "POST", body: [
            "sdp": sdp, "language": language, "instructions": instructions, "history": history,
            "requestedMilliseconds": requestedMilliseconds
        ], owner: owner, key: requestID) }
        catch { HostedCloseRecovery.shared.failed(requestID: requestID); throw error }
        do {
            let lease = try decodeLease(value, owner: owner, requestedMilliseconds: requestedMilliseconds)
            try HostedCloseRecovery.shared.track(lease, requestID: requestID)
            return lease
        }
        catch {
            HostedCloseRecovery.shared.failed(requestID: requestID)
            // A malformed success response can still represent a funded server session.
            if let sessionID = UUID(uuidString: value["sessionID"] as? String ?? "") {
                try? HostedCloseRecovery.shared.track(sessionID: sessionID, owner: owner, active: false)
                _ = try? await request("/v1/live/sessions/\(sessionID.uuidString.lowercased())/close",
                                       method: "POST", body: [:], owner: owner)
            }
            throw error
        }
    }
    private func decodeLease(_ value: [String: Any], owner: HostedOwner,
                             requestedMilliseconds: Int) throws -> HostedLease {
        guard let id = UUID(uuidString: value["sessionID"] as? String ?? ""),
              let providerID = value["providerSessionID"] as? String, !providerID.isEmpty,
              let answer = value["sdp"] as? String, answer.hasPrefix("v=0"), answer.utf8.count <= 65_536,
              let deadlineText = value["deadline"] as? String,
              let deadline = Self.parseDate(deadlineText), deadline > .now,
              value["experimental"] as? Bool == true,
              let fundingText = value["fundingMode"] as? String,
              let funding = HostedFunding(rawValue: fundingText),
              let minimum = value["minimumChargeMilliseconds"] as? Int,
              minimum == 15_000
        else { throw HostedError.invalidResponse }
        let limit: Int
        switch funding {
        case .minutes:
            guard value["billingBasis"] as? String == "connected-conversation-time",
                  value["billingPolicy"] as? String == "connected-time-15s-minimum-v1",
                  let reserved = value["reservedMilliseconds"] as? Int,
                  (1...requestedMilliseconds).contains(reserved), value["limitMilliseconds"] == nil
            else { throw HostedError.invalidResponse }
            limit = reserved
        case .aiValue:
            guard value["billingBasis"] as? String == "actual-ai-usage",
                  value["billingPolicy"] as? String == "actual-ai-usage-15s-minimum-v1",
                  let granted = value["limitMilliseconds"] as? Int,
                  (minimum...requestedMilliseconds).contains(granted), value["reservedMilliseconds"] == nil
            else { throw HostedError.invalidResponse }
            limit = granted
        }
        return HostedLease(sessionID: id, owner: owner, deadline: deadline, providerSessionID: providerID,
                           answerSDP: answer, funding: funding, limitMilliseconds: limit,
                           minimumChargeMilliseconds: minimum)
    }
    private static func parseDate(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
    func close(_ lease: HostedLease) async throws {
        _ = try await close(sessionID: lease.sessionID, owner: lease.owner)
    }
    func close(requestID: UUID, owner: HostedOwner) async throws -> Bool {
        let value = try await request("/v1/live/requests/\(requestID.uuidString.lowercased())/close", method: "POST", body: [:], owner: owner)
        guard UUID(uuidString: value["requestID"] as? String ?? "") == requestID else { throw HostedError.invalidResponse }
        if value["preventedCreate"] as? Bool == true && value["session"] is NSNull { return true }
        guard let session = value["session"] as? [String: Any], UUID(uuidString: session["sessionID"] as? String ?? "") != nil else { throw HostedError.invalidResponse }
        return session["state"] as? String == "closed" && session["settlementState"] as? String == "final"
    }
    func close(sessionID: UUID, owner: HostedOwner) async throws -> Bool {
        let value = try await request("/v1/live/sessions/\(sessionID.uuidString.lowercased())/close", method: "POST", body: [:], owner: owner)
        guard UUID(uuidString: value["sessionID"] as? String ?? "") == sessionID,
              let state = value["state"] as? String, ["creating", "active", "closing", "incomplete", "closed"].contains(state)
        else { throw HostedError.invalidResponse }
        // Older servers and a closed voice stream are not proof that helper usage settled.
        return state == "closed" && value["settlementState"] as? String == "final"
    }
    func helper(_ lease: HostedLease, purpose: String, instructions: String, input: String,
                schema: [String: Any]?, search: Bool) async throws -> APIResult {
        let id = UUID()
        var body: [String: Any] = ["requestID": id.uuidString.lowercased(), "purpose": purpose,
                                    "instructions": instructions, "input": input, "search": search]
        if let schema { body["schema"] = schema }
        let value = try await request("/v1/live/sessions/\(lease.sessionID.uuidString.lowercased())/helpers",
                                      method: "POST", body: body, owner: lease.owner)
        guard value["requestID"] as? String == id.uuidString.lowercased(),
              let text = value["text"] as? String, !text.isEmpty,
              let usage = value["usage"] as? [String: Any],
              let inputTokens = usage["inputTokens"] as? Int, let outputTokens = usage["outputTokens"] as? Int,
              let searches = usage["searchCalls"] as? Int,
              inputTokens >= 0, outputTokens >= 0, (0...1).contains(searches),
              let rawSources = value["sources"] as? [[String: Any]]
        else { throw HostedError.invalidResponse }
        let sources = rawSources.compactMap { item -> SourceLink? in
            guard let url = item["url"] as? String else { return nil }
            let source = SourceLink(title: item["title"] as? String ?? "Source", url: url)
            return source.safeURL == nil ? nil : source
        }
        return APIResult(text: text, sources: sources, usage: APIUsage(input: inputTokens, output: outputTokens, searches: searches))
    }
}

private struct GuestRecord: Codable {
    let installationToken: String
    var owner: HostedOwner?
    var pendingMemberID: UUID?
    var linkedMemberID: UUID?
}

/// This device-only Keychain record survives app restarts without entering learning backups.
@MainActor final class GuestAccess {
    static let shared = GuestAccess()
    private init() {}
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "chat.mural.ios.guest",
         kSecAttrAccount as String: "installation",
         kSecAttrSynchronizable as String: false]
    }
    private func read() throws -> GuestRecord? {
        var value = query; value[kSecReturnData as String] = true; value[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(value as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data,
              let record = try? JSONDecoder().decode(GuestRecord.self, from: data),
              record.installationToken.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil
        else { throw HostedError.secureStorage }
        return record
    }
    private func save(_ record: GuestRecord) throws {
        let data = try JSONEncoder().encode(record)
        let attrs: [String: Any] = [kSecValueData as String: data,
                                    kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(query.merging(attrs) { _, new in new } as CFDictionary, nil) == errSecSuccess else { throw HostedError.secureStorage }
        } else if status != errSecSuccess { throw HostedError.secureStorage }
    }
    private func record() throws -> GuestRecord {
        if let current = try read() { return current }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw HostedError.secureStorage }
        let token = Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let new = GuestRecord(installationToken: token, owner: nil, pendingMemberID: nil, linkedMemberID: nil)
        try save(new)
        return new
    }
    var hasUnlinkedAccount: Bool {
        guard let record = try? read() else { return false }
        return record.owner != nil && record.pendingMemberID == nil && record.linkedMemberID == nil
    }
    /// Retain the installation proof so deleting an account cannot reset the free trial.
    func deleteAccount(using client: ManagedAccountClient) async throws {
        guard var record = try read(), let existing = record.owner,
              record.pendingMemberID == nil, record.linkedMemberID == nil else { return }
        let owner: HostedOwner
        if existing.usable { owner = existing } else { owner = try await self.owner(member: nil) }
        try await client.deleteGuest(owner: owner)
        record.owner = nil
        try save(record)
    }
    func owner(member: ManagedAccountSession?) async throws -> HostedOwner {
        let client = try HostedClient.shared.unwrap()
        if let member {
            let memberOwner = HostedOwner(accountID: member.accountID, accessToken: member.accessToken, expiresAt: member.expiresAt)
            try await linkIfNeeded(to: memberOwner)
            return memberOwner
        }
        var record = try record()
        if record.linkedMemberID != nil || record.pendingMemberID != nil { throw HostedError.signInRequired }
        if let owner = record.owner, owner.usable { return owner }
        let response = try await client.guest(installationToken: record.installationToken)
        let owner = try Self.grantedOwner(response)
        record.owner = owner
        try save(record)
        return owner
    }
    private static func grantedOwner(_ response: [String: Any]) throws -> HostedOwner {
        guard let available = response["available"] as? Bool else { throw HostedError.invalidResponse }
        if !available {
            switch response["reason"] as? String {
            case "sign_in_required": throw HostedError.signInRequired
            case "temporarily_unavailable": throw HostedError.unavailable
            default: throw HostedError.invalidResponse
            }
        }
        guard let id = UUID(uuidString: response["guestID"] as? String ?? ""),
              let token = response["accessToken"] as? String,
              token.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil,
              let lifetime = response["expiresInSeconds"] as? Int, (1...86_400).contains(lifetime),
              let remaining = response["remainingMilliseconds"] as? Int, remaining >= 0
        else { throw HostedError.invalidResponse }
        return HostedOwner(accountID: id, accessToken: token, expiresAt: .now.addingTimeInterval(Double(lifetime)))
    }
    func linkIfNeeded(to member: HostedOwner) async throws {
        var record = try record()
        let client = try HostedClient.shared.unwrap()
        // Signing in before a first conversation must not bypass the installation's
        // eligible trial. An unavailable trial must not hide an existing paid balance.
        if record.owner == nil, record.linkedMemberID == nil, record.pendingMemberID == nil,
           let response = try? await client.guest(installationToken: record.installationToken),
           let guest = try? Self.grantedOwner(response) {
            record.owner = guest
            try save(record)
        }
        guard var guest = record.owner else { return }
        if let pending = record.pendingMemberID, pending != member.accountID { throw HostedError.signInRequired }
        if record.pendingMemberID == nil { record.pendingMemberID = member.accountID; try save(record) }
        let response: [String: Any]
        do { response = try await client.linkGuest(guest, guestID: guest.accountID, member: member) }
        catch HostedError.server("invalid_guest_session", _) {
            if let renewed = try? Self.grantedOwner(await client.guest(installationToken: record.installationToken)) {
                guard renewed.accountID == guest.accountID else { throw HostedError.unconfirmed }
                guest = renewed; record.owner = renewed; try save(record)
                response = try await client.linkGuest(guest, guestID: guest.accountID, member: member)
            } else {
                response = try await client.linkGuest(nil, guestID: guest.accountID, member: member)
            }
        }
        guard let outcome = response["outcome"] as? String,
              outcome == "transferred" || outcome == "member_trial_already_claimed" || outcome == "pending",
              let transferred = response["transferredMilliseconds"] as? Int, transferred >= 0
        else { throw HostedError.unconfirmed }
        if outcome == "pending" { return }
        record.owner = nil; record.pendingMemberID = nil; record.linkedMemberID = member.accountID
        try save(record)
    }
}

private extension Optional where Wrapped == HostedClient {
    func unwrap() throws -> HostedClient { guard let self else { throw HostedError.unavailable }; return self }
}
