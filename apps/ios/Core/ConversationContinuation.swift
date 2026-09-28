import Foundation

/// A local pointer only: conversation text stays in the existing learning archive.
public struct ConversationContinuationCheckpoint: Codable, Sendable {
    public let sessionID: UUID
    public let accountID: UUID
    public init(sessionID: UUID, accountID: UUID) {
        self.sessionID = sessionID; self.accountID = accountID
    }
    public func recover(from sessions: [SessionRecord], accountID: UUID, languageID: String) -> SessionRecord? {
        guard self.accountID == accountID else { return nil }
        return sessions.first { $0.id == sessionID && $0.languageID == languageID && $0.endedAt != nil && $0.endReason == "Time limit" }
    }
}

public enum ConversationContinuation {
    public static func needsMoreMinutes(settlementState: String?, availabilityReason: String?) -> Bool {
        settlementState == "settled" && availabilityReason == "insufficient_remaining_time"
    }

    /// A server close can arrive before the next local timer tick.
    public static func reachedFreeBoundary(hasFreeFunding: Bool, endReason: String?, deadlineReached: Bool) -> Bool {
        hasFreeFunding && (endReason == "Time limit" || endReason == "Reserved conversation time ended" ||
                           (endReason == nil && deadlineReached))
    }

    /// Keep recent context within the server's forty-message / 6 KB contract.
    public static func history(_ entries: [(Speaker, String)]) -> [[String: Any]] {
        var result: [[String: Any]] = entries.suffix(40).compactMap { speaker, original in
            var text = original.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, !text.unicodeScalars.contains(where: {
                ($0.value < 32 && ![9, 10, 13].contains($0.value)) || $0.value == 127
            }) else { return nil }
            while text.utf8.count > 4_000 { text.removeLast() }
            return ["type": "message", "role": speaker.rawValue,
                    "content": [["type": speaker == .user ? "input_text" : "output_text", "text": text]]]
        }
        while !result.isEmpty && ((try? JSONSerialization.data(withJSONObject: result).count) ?? Int.max) > 6_000 {
            result.removeFirst()
        }
        return result
    }
}
