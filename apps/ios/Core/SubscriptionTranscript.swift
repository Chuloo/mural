import Foundation

/// Codex WebRTC captions use whole turns. Timing is local receipt time, not provider audio alignment.
public struct SubscriptionTranscript {
    private var turns: [String: Fragment] = [:]
    private var completed: [String] = []
    public init() {}

    public mutating func consume(_ event: [String: Any], elapsedMS: Int) -> Fragment? {
        guard let type = event["type"] as? String else { return nil }
        let now = max(0, elapsedMS)
        if type == "turn.created" || type == "turn.done" {
            guard let turn = event["turn"] as? [String: Any], let id = turn["id"] as? String,
                  !id.isEmpty, id.count <= 200, !completed.contains(id),
                  let role = turn["role"] as? String, let speaker = Speaker(rawValue: role) else { return nil }
            var fragment = turns[id] ?? Fragment(id: "subscription:" + id, speaker: speaker, text: "", startMS: now, endMS: now)
            guard fragment.speaker == speaker else { return nil }
            if let text = turn["transcript"] as? String { fragment.text = text }
            guard fragment.text.utf8.count <= 200_000 else { return nil }
            fragment.endMS = max(now, fragment.startMS)
            if type == "turn.done" {
                turns.removeValue(forKey: id); completed.append(id)
                if completed.count > 256 { completed.removeFirst() }
            } else {
                guard turns[id] != nil || turns.count < 128 else { return nil }
                turns[id] = fragment
            }
            return fragment.text.isEmpty ? nil : fragment
        }
        guard type == "turn.delta", let id = event["turn_id"] as? String,
              let delta = event["delta"] as? String, var fragment = turns[id],
              fragment.text.utf8.count + delta.utf8.count <= 200_000 else { return nil }
        fragment.text += delta; fragment.endMS = max(now, fragment.startMS); turns[id] = fragment
        return fragment.text.isEmpty ? nil : fragment
    }

    public static func apply(_ incoming: Fragment, to session: inout SessionRecord, meaningVisible: Bool) {
        guard let index = session.fragments.firstIndex(where: { $0.id == incoming.id }) else {
            var fragment = incoming; fragment.meaningVisible = meaningVisible
            session.append(fragment); return
        }
        guard session.fragments[index].speaker == incoming.speaker else { return }
        let previous = session.fragments[index].text
        let visibilityChanged = meaningVisible && !session.fragments[index].meaningVisible
        if previous == incoming.text {
            if visibilityChanged { session.fragments[index].revision += 1 }
        } else if incoming.text.hasPrefix(previous) {
            session.fragments[index].text = incoming.text
            session.fragments[index].revision += 1
        } else {
            session.correctFragment(id: incoming.id, text: incoming.text)
        }
        session.fragments[index].endMS = max(session.fragments[index].endMS, incoming.endMS)
        session.fragments[index].meaningVisible = session.fragments[index].meaningVisible || meaningVisible
        session.invalidateChangedAssessments()
    }
}
