import Foundation

public enum HostedFundingAvailability {
    public static func hasFreeConversationTime(_ milliseconds: Int) -> Bool { milliseconds >= 15_000 }
    public static func canStart(freeMilliseconds: Int, paidAvailable: Bool, availabilityReason: String?) -> Bool {
        if let availabilityReason { return availabilityReason == "ready" }
        return hasFreeConversationTime(freeMilliseconds) || paidAvailable
    }
}

public struct MuralMinutesPresentation: Decodable, Sendable {
    public let schemaVersion: Int
    public let asOf: String
    public let revision: String
    public let freeAvailableMilliseconds: Int
    public let paidEstimatedMilliseconds: Int?
    public let hasPurchasedRemainder: Bool
    public let totalDisplayMilliseconds: Int?
    public let displayKind: String
    public let estimateRateVersion: String?
    public let availabilityReason: String
    public let settlementState: String
    public let paidSupported: Bool
    public var revisionNumber: UInt64? { UInt64(revision) }
    public func validate() throws {
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard schemaVersion == 1, revisionNumber != nil, String(revisionNumber!) == revision,
              formatter.date(from: asOf) != nil, freeAvailableMilliseconds >= 0,
              ["ready", "insufficient_remaining_time", "active_conversation", "settling", "service_unavailable", "account_action_needed"].contains(availabilityReason),
              ["settled", "in_use", "pending"].contains(settlementState),
              displayKind == (hasPurchasedRemainder ? "approximate" : "exactFree"),
              !hasPurchasedRemainder || paidSupported,
              !paidSupported || estimateRateVersion?.isEmpty == false
        else { throw ManagedAccountError.invalidResponse }
        if let paid = paidEstimatedMilliseconds {
            let (sum, overflow) = freeAvailableMilliseconds.addingReportingOverflow(paid)
            guard paid >= 0, !overflow, totalDisplayMilliseconds == sum,
                  hasPurchasedRemainder || paid == 0 else { throw ManagedAccountError.invalidResponse }
        } else {
            guard totalDisplayMilliseconds == nil, !paidSupported else { throw ManagedAccountError.invalidResponse }
        }
        guard (availabilityReason == "settling") == (settlementState == "pending"),
              (availabilityReason == "active_conversation") == (settlementState == "in_use")
        else { throw ManagedAccountError.invalidResponse }
    }
    public var displayText: String {
        guard let total = totalDisplayMilliseconds else { return "Couldn’t check your minutes" }
        if total == 0 && settlementState == "pending" { return "Updating your minutes…" }
        if total == 0 && settlementState == "in_use" { return "Minutes in use" }
        if hasPurchasedRemainder { return total < 60_000 ? "Less than 1 min" : "About \(total / 60_000) min" }
        guard total > 0 else { return "No minutes left" }
        let seconds = MinuteBalanceTime.roundedSeconds(total)
        return "\(seconds / 60) min \(seconds % 60) sec"
    }
}
