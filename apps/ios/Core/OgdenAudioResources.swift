import Foundation

/// Both accent folders use the source's term (including grey/plough), not a
/// computed accent spelling. The migrated term-to-file mapping covers all 850.
public enum OgdenAudioResources {
    public static func url(for word: OgdenWord, accent: String) -> URL? {
        guard accent == "us" || accent == "uk" else { return nil }
        let filename = word.term.lowercased()
        guard !filename.isEmpty,
              filename.unicodeScalars.allSatisfy({ CharacterSet.lowercaseLetters.union(.init(charactersIn: "- ")).contains($0) }) else { return nil }
        return Bundle.module.url(forResource: filename, withExtension: "mp3", subdirectory: "OgdenAudio/\(accent)")
    }

    /// Current word plus four successors, bounded at the end of the curriculum.
    /// Unknown IDs produce no window instead of silently jumping to the first word.
    public static func window(startingAt wordID: String?, curriculum: OgdenCurriculum) -> [OgdenWord] {
        guard let index = curriculum.words.firstIndex(where: { $0.id == wordID }) else { return [] }
        return Array(curriculum.words[index..<min(index + 5, curriculum.words.count)])
    }
}
