import Foundation

/// A fixed question set owns its answers. Redraws and repeated taps cannot
/// regenerate options, change scores or count the same question twice.
public struct OgdenPracticeSession: Sendable {
    public let questions: [OgdenQuestion]
    public private(set) var position = 0
    public private(set) var answers: [UUID: Bool] = [:]
    public init(questions: [OgdenQuestion]) { self.questions = questions }
    public var current: OgdenQuestion? { questions.indices.contains(position) ? questions[position] : nil }
    public var score: Int { answers.values.filter { $0 }.count }
    public var finished: Bool { !questions.isEmpty && position == questions.count }
    public var currentResult: Bool? { current.flatMap { answers[$0.id] } }

    @discardableResult public mutating func submit(_ input: String) -> Bool? {
        guard let question = current, answers[question.id] == nil else { return nil }
        let correct = question.matches(input)
        answers[question.id] = correct
        return correct
    }
    public mutating func advance() {
        guard currentResult != nil else { return }
        position += 1
    }
    public func covers(_ level: OgdenLevel) -> Bool {
        finished && questions.count == level.wordIDs.count && Set(questions.map(\.wordID)) == Set(level.wordIDs)
    }

    /// A mixed round always covers each requested word. A focused round only
    /// contains words with a valid question of that type; it cannot unlock a
    /// full level unless every word was actually answered.
    public static func make(wordIDs: [String], type: OgdenPracticeType?, curriculum: OgdenCurriculum, maxQuestions: Int? = nil) -> Self {
        var questions: [OgdenQuestion] = []
        for (index, id) in wordIDs.enumerated() {
            if let maxQuestions, questions.count >= max(0, maxQuestions) { break }
            guard let word = curriculum.word(id: id) else { continue }
            if let type {
                if let question = OgdenPractice.question(type: type, word: word, curriculum: curriculum) { questions.append(question) }
                continue
            }
            let types = OgdenPracticeType.allCases
            for offset in types.indices {
                let candidate = types[(index + offset) % types.count]
                if let question = OgdenPractice.question(type: candidate, word: word, curriculum: curriculum) { questions.append(question); break }
            }
        }
        return Self(questions: questions)
    }
}
