import Foundation

public struct WordState: Identifiable, Sendable {
    public var id: String
    public var lemma: String
    public var meaning: String
    public var form: String
    public var example: String
    public var bars: Int
    public var understandingCount: Int
    public var independentCount: Int
    public var lastSeen: Date
    public var dueAt: Date
    public var label: String { ["New", "Fragile", "Growing", "Steady"][min(3, max(0, bars))] }
    public var explanation: String {
        if independentCount == 0 { return "Heard or used with support. Try using it in your own words." }
        if bars == 1 { return "Used independently. We’ll bring it back soon." }
        if bars == 2 { return "Recalled on different days. Still worth revisiting." }
        return "Recalled across days and contexts. Strength can fade with time."
    }
}

public struct LearnerState: Sendable {
    public var challenge: Int
    public var observationCount: Int
    public var nextGoal: String
    public var capabilities: [String]
    public var words: [WordState]
    public var levelLabel: String { observationCount < 4 ? "Getting to know you" : "Finding your pace" }
}

public enum LearningEngine {
    public static func validate(_ proposal: Assessment, session: SessionRecord) -> Assessment? {
        guard LanguageRegistry.module(for: session.languageID) != nil,
              let passage = session.passages.first(where: { $0.id == proposal.passageID && $0.speaker == .user }),
              passage.revisionKey == proposal.revisionKey,
              (0...5).contains(proposal.suggestedLevel), proposal.words.count <= 12 else { return nil }
        let allowed = Set(passage.fragments.map(\.id))
        // Re-evaluate saved evidence with the same assembly it was originally judged against.
        let join = proposal.textAssemblyVersion == nil ? Passage.legacyJoin : Passage.join
        let evidenceText = join(passage.fragments.map(\.text))
        var validated = proposal
        validated.nextGoal = String(validated.nextGoal.prefix(300))
        validated.capability = String(validated.capability.prefix(160))
        validated.words = proposal.words.compactMap { word in
            guard word.language == session.languageID,
                  !word.sourceIDs.isEmpty, Set(word.sourceIDs).isSubset(of: allowed),
                  word.confidence.isFinite, word.confidence >= 0.8, word.confidence <= 1,
                  !word.lemma.isEmpty, word.lemma.count < 100, !word.meaning.isEmpty, word.meaning.count < 180,
                  !word.form.isEmpty, !word.quote.isEmpty,
                  evidenceText.localizedCaseInsensitiveContains(word.quote),
                  word.quote.localizedCaseInsensitiveContains(word.form) else { return nil }
            let refs = join(passage.fragments.filter { word.sourceIDs.contains($0.id) }.map(\.text))
            guard refs.localizedCaseInsensitiveContains(word.quote) else { return nil }
            var result = word
            if result.kind == .independent {
                // A visible meaning or immediate imitation is supporting evidence, never independent recall.
                let recentlyModeled = session.passages.contains {
                    $0.speaker == .assistant && $0.startMS <= passage.startMS && passage.startMS - $0.endMS < 90_000 &&
                    join($0.fragments.map(\.text)).localizedCaseInsensitiveContains(word.form)
                }
                if passage.fragments.contains(where: { $0.meaningVisible || $0.typed }) || recentlyModeled { result.kind = .assisted }
            }
            return result
        }
        return validated
    }

    public static func project(_ sessions: [SessionRecord], languageID: String = LanguageRegistry.defaultID, hiddenWords: [String] = [], now: Date = .now) -> LearnerState {
        var level = 0, count = 0, successes = 0
        var nextGoal = "Start with a greeting and one small question. Adjust from what the learner actually says."
        var capabilityEvidence: [String: Set<String>] = [:]
        var events: [String: [(WordProposal, Date, String)]] = [:]
        let calendar = Calendar(identifier: .gregorian)
        for session in sessions.filter({ $0.languageID == languageID }).sorted(by: { $0.startedAt < $1.startedAt }) {
            var seen = Set<String>()
            for raw in session.assessments.sorted(by: { $0.createdAt < $1.createdAt }) {
                guard !seen.contains(raw.passageID), let a = validate(raw, session: session) else { continue }
                seen.insert(raw.passageID)
                count += 1
                if a.outcome == .breakdown { level = max(0, level - 1); successes = 0 }
                else if a.outcome == .success {
                    successes += 1
                    if successes >= 2 { level = min(5, max(level, min(level + 1, a.suggestedLevel))); successes = 0 }
                } else { successes = 0 }
                if !a.nextGoal.isEmpty { nextGoal = a.nextGoal }
                if a.outcome == .success && !a.capability.isEmpty {
                    capabilityEvidence[a.capability, default: []].insert("\(calendar.startOfDay(for: a.createdAt))|\(a.context)")
                }
                var seenWords = Set<String>()
                for word in a.words where !hiddenWords.contains(word.key) && seenWords.insert(word.key).inserted {
                    events[word.key, default: []].append((word, a.createdAt, a.context))
                }
            }
        }
        let words: [WordState] = events.compactMap { key, observations in
            guard let last = observations.last else { return nil }
            let independent = observations.filter { $0.0.kind == .independent }
            let days = Set(independent.map { calendar.startOfDay(for: $0.1) }).count
            let contexts = Set(independent.map { $0.2 }).count
            let lastRecall = independent.last?.1
            var bars = independent.isEmpty ? 0 : 1
            if days >= 2 { bars = 2 }
            if days >= 3 && contexts >= 2 && (independent.last!.1.timeIntervalSince(independent.first!.1) >= 7 * 86400) { bars = 3 }
            let interval = [1.0, 1, 4, 14][bars] * 86400
            let due = (lastRecall ?? last.1).addingTimeInterval(interval)
            if now > due && bars > 1 { bars -= 1 }
            if let lapse = observations.last(where: { $0.0.kind == .lapse }), lapse.1 > (lastRecall ?? .distantPast) { bars = min(bars, 1) }
            return WordState(id: key, lemma: last.0.lemma, meaning: last.0.meaning, form: last.0.form,
                             example: last.0.quote, bars: bars, understandingCount: observations.filter { $0.0.kind == .understanding }.count,
                             independentCount: independent.count, lastSeen: last.1, dueAt: due)
        }.sorted { $0.lastSeen > $1.lastSeen }
        return LearnerState(challenge: level, observationCount: count, nextGoal: nextGoal,
                            capabilities: capabilityEvidence.filter { $0.value.count >= 3 }.keys.sorted(), words: words)
    }
}
