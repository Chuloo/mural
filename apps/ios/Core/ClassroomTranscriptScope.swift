import Foundation

/// Keeps known realtime turns with the lesson in which they first appeared.
/// It filters local captions/context; it does not erase the provider's history.
public struct ClassroomTranscriptScope {
    private var current = UUID()
    private var pending: UUID?
    private var turns: [String: UUID] = [:]
    public init() {}

    public mutating func observe(_ id: String) {
        guard !id.isEmpty, turns[id] == nil, turns.count < 20_000 else { return }
        turns[id] = pending ?? current
    }
    public func contains(_ id: String) -> Bool { turns[id] == current }
    public mutating func beginTransition() { pending = UUID() }
    public mutating func confirmTransition() { current = UUID(); pending = nil }
    public mutating func cancelTransition() { pending = nil }

    /// Empty turn.created events matter: their first text can arrive after a switch.
    public static func startedTurnID(_ event: [String: Any]) -> String? {
        guard event["type"] as? String == "turn.created",
              let turn = event["turn"] as? [String: Any],
              let id = turn["id"] as? String, !id.isEmpty, id.count <= 200,
              let role = turn["role"] as? String, Speaker(rawValue: role) != nil else { return nil }
        return "subscription:" + id
    }
}
