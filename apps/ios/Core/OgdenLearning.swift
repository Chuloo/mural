import Foundation

public enum OgdenCategory: String, Codable, CaseIterable, Identifiable, Sendable {
    case operations = "operations", generalThings = "general-things", picturableThings = "picturable-things", qualities = "qualities", opposites = "opposites"
    public var id: String { rawValue }
    public var titleKey: String { rawValue }
}

public enum OgdenPracticeType: String, Codable, CaseIterable, Identifiable, Sendable {
    case listen, meaning, cloze, spelling, synonym
    public var id: String { rawValue }
    public var titleKey: String { rawValue }
}

public struct OgdenLevel: Codable, Equatable, Identifiable, Sendable {
    public let id: String; public let category: OgdenCategory; public let index: Int; public let wordIDs: [String]
    public init(id: String, category: OgdenCategory, index: Int, wordIDs: [String]) { self.id = id; self.category = category; self.index = index; self.wordIDs = wordIDs }
}

public struct OgdenWordStats: Codable, Equatable, Sendable {
    public var attempts: Int; public var correct: Int; public var mastery: Int; public var lastStudiedAt: Date?
    public init(attempts: Int = 0, correct: Int = 0, mastery: Int = 0, lastStudiedAt: Date? = nil) { self.attempts = attempts; self.correct = correct; self.mastery = mastery; self.lastStudiedAt = lastStudiedAt }
}

public struct OgdenLearningState: Codable, Equatable, Sendable {
    public var accent = "us"
    public var traditionalChinese = false
    public var favorites: Set<String> = []
    public var stats: [String: OgdenWordStats] = [:]
    /// Optional migration field: old archives inferred mistakes from attempts.
    public var mistakes: Set<String>? = nil
    public var completedLevelIDs: Set<String> = []
    public var studyDays: Set<String> = []
    public init() {}
    public mutating func toggleFavorite(wordID: String) { if !favorites.insert(wordID).inserted { favorites.remove(wordID) } }
    public mutating func recordAnswer(wordID: String, correct: Bool, date: Date = .now) {
        var s = stats[wordID] ?? OgdenWordStats()
        s.attempts = min(s.attempts, 999_999) + 1; if correct { s.correct = min(s.correct, 999_999) + 1 }
        s.mastery = min(3, max(0, s.mastery + (correct ? 1 : -1))); s.lastStudiedAt = date; stats[wordID] = s
        var currentMistakes = mistakes ?? Set(stats.compactMap { $0.value.attempts > 0 && $0.value.correct < $0.value.attempts ? $0.key : nil })
        if !correct || s.mastery == 0 { currentMistakes.insert(wordID) } else { currentMistakes.remove(wordID) }
        mistakes = currentMistakes
        // Ogden increments its streak only for a correct practice answer.
        if correct { studyDays.insert(Self.dayKey(date)) }
    }
    public mutating func completeLevel(_ level: OgdenLevel, correct: Int, total: Int, date: Date = .now) {
        guard total == level.wordIDs.count, total > 0, correct >= 0, correct <= total else { return }; completedLevelIDs.insert(level.id); if correct > 0 { studyDays.insert(Self.dayKey(date)) }
    }
    public func isUnlocked(_ level: OgdenLevel, levels: [OgdenLevel]) -> Bool {
        guard levels.count == Set(levels.map(\.id)).count, level.index >= 0, levels.contains(where: { $0.id == level.id && $0.category == level.category && $0.index == level.index }), levels.filter({ $0.category == level.category }).allSatisfy({ $0.index >= 0 }) else { return false }
        guard level.index > 0 else { return true }
        guard let prior = levels.first(where: { $0.category == level.category && $0.index == level.index - 1 }) else { return false }
        return completedLevelIDs.contains(prior.id)
    }
    public var wrongWordIDs: Set<String> { mistakes ?? Set(stats.compactMap { $0.value.attempts > 0 && $0.value.correct < $0.value.attempts ? $0.key : nil }) }
    public var studiedCount: Int { stats.values.filter { $0.attempts > 0 }.count }
    public func streak(at date: Date = .now) -> Int {
        let calendar = Self.calendar(); var d = calendar.startOfDay(for: date)
        // Use local calendar days. Yesterday's streak remains visible before
        // today's study; a longer gap displays zero, unlike the source's stale counter.
        if !studyDays.contains(Self.dayKey(d)), let yesterday = calendar.date(byAdding: .day, value: -1, to: d), studyDays.contains(Self.dayKey(yesterday)) { d = yesterday }
        var count = 0
        while studyDays.contains(Self.dayKey(d)) { count += 1; guard let next = calendar.date(byAdding: .day, value: -1, to: d) else { break }; d = next }
        return count
    }
    public func isValid(c: OgdenCurriculum) -> Bool {
        let ids = Set(c.words.map(\.id)); guard ["us", "uk"].contains(accent), stats.count <= ids.count, favorites.count <= ids.count, mistakes.map({ $0.count <= ids.count && $0.allSatisfy { ids.contains($0) } }) ?? true, favorites.allSatisfy({ ids.contains($0) }), stats.keys.allSatisfy({ ids.contains($0) }) else { return false }
        let validLevels = Set(OgdenPractice.levels(curriculum: c).map(\.id))
        guard completedLevelIDs.allSatisfy({ validLevels.contains($0) }) else { return false }
        guard studyDays.count <= 10_000, studyDays.allSatisfy({ Self.validDayKey($0) }), stats.values.allSatisfy({ stat in stat.attempts >= 0 && stat.attempts <= 1_000_000 && stat.correct >= 0 && stat.correct <= stat.attempts && (0...3).contains(stat.mastery) && (stat.lastStudiedAt.map { $0 >= Date.distantPast && $0 <= Date.distantFuture } ?? true) }) else { return false }
        return true
    }
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = .autoupdatingCurrent; return c }
    private static func dayKey(_ date: Date) -> String { let f = DateFormatter(); f.calendar = calendar(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f.string(from: date) }
    private static func validDayKey(_ value: String) -> Bool { let f = DateFormatter(); f.calendar = calendar(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; f.isLenient = false; return f.date(from: value).map { f.string(from: $0) == value } ?? false }
}

public struct OgdenQuestion: Identifiable, Equatable, Sendable {
    public let id: UUID; public let type: OgdenPracticeType; public let wordID: String; public let prompt: String; public let options: [String]; public let answer: String; public let acceptedAnswers: [String]
    public init(id: UUID = UUID(), type: OgdenPracticeType, wordID: String, prompt: String, options: [String], answer: String, acceptedAnswers: [String]? = nil) { self.id = id; self.type = type; self.wordID = wordID; self.prompt = prompt; self.options = options; self.answer = answer; self.acceptedAnswers = acceptedAnswers ?? [answer] }
    public func matches(_ input: String) -> Bool { acceptedAnswers.contains { input.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare($0) == .orderedSame } }
}

public enum OgdenPractice {
    /// Reviewed against the bundled definitions and source related-word lists.
    /// This bounded list deliberately excludes merely related objects, opposites and broad categories.
    public static let verifiedSynonymPairs: [(String, String)] = [("little", "small"), ("business", "trade"), ("cause", "reason"), ("country", "nation"), ("discussion", "talk"), ("expansion", "growth"), ("help", "support"), ("error", "mistake"), ("answer", "reply"), ("attempt", "try"), ("danger", "risk"), ("desire", "wish"), ("fear", "dread"), ("idea", "thought"), ("question", "inquiry"), ("start", "beginning"), ("stop", "halt")]
    public static func levels(curriculum: OgdenCurriculum) -> [OgdenLevel] {
        OgdenCategory.allCases.flatMap { category in
            let ids = curriculum.words.filter { $0.category == category.rawValue }.map(\.id)
            return stride(from: 0, to: ids.count, by: 10).map { i in OgdenLevel(id: "\(category.id)-\(i / 10)", category: category, index: i / 10, wordIDs: Array(ids[i..<min(i + 10, ids.count)])) }
        }
    }
    public static func question(type: OgdenPracticeType, word: OgdenWord, curriculum: OgdenCurriculum) -> OgdenQuestion? {
        let pool = curriculum.words.filter { $0.id != word.id }; let terms = Array(pool.shuffled().prefix(3).map(\.term))
        switch type {
        case .listen: return OgdenQuestion(type: type, wordID: word.id, prompt: "Listen and choose the word", options: shuffled(word.term, terms), answer: word.term)
        case .meaning: return OgdenQuestion(type: type, wordID: word.id, prompt: word.meaningZhCn, options: shuffled(word.term, terms), answer: word.term)
        case .spelling:
            let accepted = [word.spellingUs, word.spellingUk].filter { !$0.isEmpty }.uniqued()
            return OgdenQuestion(type: type, wordID: word.id, prompt: word.meaningZhCn, options: accepted.count > 1 ? [word.spellingUs, word.spellingUk] : accepted, answer: word.spellingUs, acceptedAnswers: accepted)
        case .cloze:
            guard let range = tokenRange(of: word.term, in: word.exampleEn) else { return nil }
            var text = word.exampleEn; text.replaceSubrange(range, with: "_____" )
            return OgdenQuestion(type: type, wordID: word.id, prompt: text, options: shuffled(word.term, terms), answer: word.term)
        case .synonym:
            let pair = verifiedSynonymPairs.first { $0.0.caseInsensitiveCompare(word.term) == .orderedSame || $0.1.caseInsensitiveCompare(word.term) == .orderedSame }
            guard let pair = pair else { return nil }
            let candidate = pair.0.caseInsensitiveCompare(word.term) == .orderedSame ? pair.1 : pair.0
            guard word.synonyms.contains(where: { $0.caseInsensitiveCompare(candidate) == .orderedSame }) else { return nil }
            let forbidden = Set(verifiedSynonymPairs.flatMap { [$0.0.lowercased(), $0.1.lowercased()] }.filter { $0 != word.term.lowercased() })
            let distractors = pool.filter { !$0.term.lowercased().isEmpty && !forbidden.contains($0.term.lowercased()) && $0.term.lowercased() != candidate.lowercased() }.shuffled().prefix(3).map(\.term)
            return OgdenQuestion(type: type, wordID: word.id, prompt: "Choose a word with a similar meaning to \(word.term)", options: shuffled(candidate, distractors), answer: candidate)
        }
    }
    private static func tokenRange(of term: String, in text: String) -> Range<String.Index>? {
        var search = text.startIndex
        while let range = text.range(of: term, options: [.caseInsensitive, .diacriticInsensitive], range: search..<text.endIndex) {
            let before = range.lowerBound > text.startIndex ? text[text.index(before: range.lowerBound)] : nil
            let after = range.upperBound < text.endIndex ? text[range.upperBound] : nil
            if !(before?.isLetter ?? false || before?.isNumber ?? false) && !(after?.isLetter ?? false || after?.isNumber ?? false) { return range }
            search = range.upperBound
        }
        return nil
    }
    private static func shuffled(_ answer: String, _ others: [String]) -> [String] { Array(([answer] + others.filter { $0.caseInsensitiveCompare(answer) != .orderedSame }).uniqued().prefix(4)).shuffled() }
}

private extension Array where Element: Equatable { func uniqued() -> [Element] { reduce(into: []) { if !$0.contains($1) { $0.append($1) } } } }
