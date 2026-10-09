import Foundation

/// A read-only snapshot: browsing never changes vocabulary or conversation recall.
public struct FlashcardDeck: Sendable {
    public let words: [WordState]
    public init(words: [WordState], languageID: String) {
        var seen = Set<String>()
        self.words = words.filter { $0.id.hasPrefix(languageID + "|") && seen.insert($0.id).inserted }
    }
    public func destination(from index: Int, direction: Int) -> Int {
        guard !words.isEmpty else { return 0 }
        return min(words.count - 1, max(0, index + direction))
    }
}
