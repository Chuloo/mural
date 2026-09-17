import Foundation

public enum Speaker: String, Codable, Sendable { case user, assistant }
public enum EvidenceKind: String, Codable, Sendable { case exposure, understanding, assisted, independent, lapse }
public enum Outcome: String, Codable, Sendable { case success, partial, breakdown, uncertain }

public struct Fragment: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var revision: Int = 0
    public var previousTexts: [String] = []
    public var speaker: Speaker
    public var text: String
    public var startMS: Int
    public var endMS: Int
    public var receivedAt: Date
    public var meaningVisible: Bool
    public var typed: Bool
    public init(id: String = UUID().uuidString, speaker: Speaker, text: String, startMS: Int, endMS: Int, receivedAt: Date = .now, meaningVisible: Bool = false, typed: Bool = false) {
        self.id = id; self.speaker = speaker; self.text = text; self.startMS = startMS
        self.endMS = endMS; self.receivedAt = receivedAt; self.meaningVisible = meaningVisible; self.typed = typed
    }
}

public struct Passage: Identifiable, Sendable {
    public var id: String
    public var speaker: Speaker
    public var fragments: [Fragment]
    public var text: String { Self.join(fragments.map(\.text)) }
    /// Deltas can end inside a word. Preserve them, repairing only a clear sentence break.
    public static func join(_ parts: [String]) -> String {
        parts.reduce(into: "") { result, part in
            guard let first = part.first, let last = result.last else { result += part; return }
            if !last.isWhitespace, !first.isWhitespace, first.isUppercase {
                let tail = result.drop(while: { $0.isWhitespace })
                let ending = tail.last
                let word = tail.dropLast().reversed().prefix(while: { $0.isLetter })
                if ending == "!" || ending == "?" || ending == "…" || (ending == "." && word.count > 1) {
                    result += " "
                }
            }
            result += part
        }
    }
    // Existing assessments may quote the old presentation; never use it for new captions.
    static func legacyJoin(_ parts: [String]) -> String {
        parts.reduce(into: "") { result, part in
            if result.isEmpty { result = part; return }
            guard let last = result.last, let first = part.first else {
                result.append(part); return
            }
            let needsSpace = !last.isWhitespace && !first.isWhitespace && !first.isPunctuation && !Self.isUnspacedBoundary(last, first)
            if needsSpace { result.append(" ") }
            result.append(part)
        }
    }
    private static func isUnspacedBoundary(_ last: Character, _ first: Character) -> Bool {
        func han(_ c: Character) -> Bool {
            c.unicodeScalars.contains { (0x3400...0x4DBF).contains($0.value) || (0x4E00...0x9FFF).contains($0.value) ||
                (0xF900...0xFAFF).contains($0.value) || (0x20000...0x323AF).contains($0.value) }
        }
        let opening = last.unicodeScalars.first.map { $0.properties.generalCategory == .openPunctuation || $0.properties.generalCategory == .initialPunctuation } ?? false
        let cjkPunctuation = last.unicodeScalars.first.map { (0x3000...0x303F).contains($0.value) || (0xFF00...0xFFEF).contains($0.value) } ?? false
        return opening || (han(first) && (han(last) || cjkPunctuation))
    }
    public var revisionKey: String { fragments.map { "\($0.id):\($0.revision)" }.joined(separator: ",") }
    public var startMS: Int { fragments.first?.startMS ?? 0 }
    public var endMS: Int { fragments.map(\.endMS).max() ?? 0 }
}

public enum Transcript {
    /// Presentation grouping only: neither the gap nor the arrival of another speaker proves a completed turn.
    public static func passages(_ fragments: [Fragment]) -> [Passage] {
        var result: [Passage] = []
        let indexed = fragments.enumerated().sorted {
            $0.element.startMS == $1.element.startMS ? $0.offset < $1.offset : $0.element.startMS < $1.element.startMS
        }
        for (_, fragment) in indexed {
            if let i = result.lastIndex(where: { $0.speaker == fragment.speaker }),
               fragment.startMS - result[i].endMS <= 2200,
               !fragment.typed, !(result[i].fragments.last?.typed ?? false) {
                result[i].fragments.append(fragment)
            } else {
                result.append(Passage(id: fragment.id, speaker: fragment.speaker, fragments: [fragment]))
            }
        }
        return result.sorted { $0.startMS < $1.startMS }
    }
}

public struct WordProposal: Codable, Sendable {
    public var lemma: String
    public var meaning: String
    public var form: String
    public var kind: EvidenceKind
    public var confidence: Double
    public var sourceIDs: [String]
    public var quote: String
    public var language: String
    public init(lemma: String, meaning: String, form: String, kind: EvidenceKind, confidence: Double, sourceIDs: [String], quote: String, language: String = LanguageRegistry.defaultID) {
        self.lemma = lemma; self.meaning = meaning; self.form = form; self.kind = kind
        self.confidence = confidence; self.sourceIDs = sourceIDs; self.quote = quote; self.language = language
    }
    public var key: String { language + "|" + lemma.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() + "|" + meaning.lowercased() }
}

public struct Assessment: Codable, Identifiable, Sendable {
    public var id: String { passageID }
    public var passageID: String
    public var revisionKey: String
    public var outcome: Outcome
    public var suggestedLevel: Int
    public var nextGoal: String
    public var capability: String
    public var words: [WordProposal]
    public var createdAt: Date
    public var context: String
    public var textAssemblyVersion: Int?
    public init(passageID: String, revisionKey: String, outcome: Outcome, suggestedLevel: Int, nextGoal: String, capability: String, words: [WordProposal], createdAt: Date = .now, context: String = "free") {
        self.passageID = passageID; self.revisionKey = revisionKey; self.outcome = outcome
        self.suggestedLevel = suggestedLevel; self.nextGoal = nextGoal; self.capability = capability
        self.words = words; self.createdAt = createdAt; self.context = context; self.textAssemblyVersion = 2
    }
}

public struct SourceLink: Codable, Identifiable, Hashable, Sendable {
    public var id: String { url }
    public var title: String
    public var url: String
    public init(title: String, url: String) { self.title = title; self.url = url }
    public var safeURL: URL? {
        guard let u = URL(string: url), u.scheme == "https", u.host != nil, u.user == nil else { return nil }
        return u
    }
}

public struct TopicBrief: Codable, Identifiable, Sendable {
    public var id = UUID()
    public let languageID: String
    public var query: String
    public var text: String
    public var sources: [SourceLink]
    public var retrievedAt = Date()
    public var isFresh: Bool { Date().timeIntervalSince(retrievedAt) < 6 * 3600 }
    public init(languageID: String, query: String, text: String, sources: [SourceLink]) { self.languageID = languageID; self.query = query; self.text = text; self.sources = sources }
}

public struct SessionRecord: Codable, Identifiable, Sendable {
    public var id = UUID()
    public let languageID: String
    public var providerID: String?
    public var startedAt = Date()
    public var endedAt: Date?
    public var themeID: String?
    public var title: String
    public var fragments: [Fragment] = []
    public var assessments: [Assessment] = []
    public var translations: [String: String] = [:]
    public var topics: [TopicBrief] = []
    public var voiceSeconds: Double = 0
    public var usageFinal = false
    public var inputTokens = 0
    public var outputTokens = 0
    public var searchCalls = 0
    public var endReason: String?
    public init(languageID: String = LanguageRegistry.defaultID, themeID: String? = nil, title: String? = nil) {
        self.languageID = languageID; self.themeID = themeID
        self.title = title ?? LanguageRegistry.module(for: languageID)?.defaultTitle ?? "A conversation"
    }
    public var passages: [Passage] { Transcript.passages(fragments) }
    // Voice fragments use the provider timeline, which starts after the local connection attempt.
    // Place typed turns after received speech instead of mixing in the local wall clock.
    public var nextTypedVoiceOffsetMS: Int { min(fragments.map(\.endMS).max() ?? 0, Int.max - 2) + 1 }
    public mutating func append(_ fragment: Fragment) {
        guard !fragments.contains(where: { $0.id == fragment.id }) else { return }
        fragments.append(fragment)
        invalidateChangedAssessments()
    }
    public mutating func invalidateChangedAssessments() {
        let current = Dictionary(uniqueKeysWithValues: passages.map { ($0.id, $0.revisionKey) })
        assessments.removeAll { current[$0.passageID] != $0.revisionKey }
    }
    public mutating func correctFragment(id: String, text: String) {
        guard let index = fragments.firstIndex(where: { $0.id == id }) else { return }
        fragments[index].previousTexts.append(fragments[index].text)
        fragments[index].text = text; fragments[index].revision += 1
        translations = translations.filter { !Self.translationKey($0.key, includesFragment: id) }
        invalidateChangedAssessments()
    }
    private static func translationKey(_ key: String, includesFragment id: String) -> Bool {
        guard let revision = key.split(separator: "::", maxSplits: 1).last else { return false }
        return revision.split(separator: ",").contains {
            $0.split(separator: ":", maxSplits: 1).first.map(String.init) == id
        }
    }
}

public struct Preferences: Codable, Sendable {
    public var learningLanguageID = LanguageRegistry.defaultID
    public var meaningVisible = true
    public var meaningLanguage = "English"
    public var sessionMinutes = 15
    public var hiddenWords: [String] = []
    public var interests = ""
    public var hasOnboarded = false
    public var aiConsentVersion: Int?
    public init() {}
}

public struct Archive: Codable, Sendable {
    public static let maximumEncodedBytes = 30_000_000
    private static let maximumSessions = 10_000
    public var schemaVersion = 2
    public var sessions: [SessionRecord] = []
    public var preferences = Preferences()
    public init() {}
    public static func decode(_ data: Data) throws -> Archive {
        guard data.count <= maximumEncodedBytes else { throw ArchiveError.tooLarge }
        let migrated = try migrate(data)
        let archive = try JSONDecoder().decode(Archive.self, from: migrated)
        try archive.validate()
        return archive
    }
    /// Bound the selected file before loading it. The read limit also covers growth after the size check.
    public static func readImportData(from url: URL) throws -> Data {
        guard url.isFileURL, try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true
        else { throw ArchiveError.invalid }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        guard try file.seekToEnd() <= maximumEncodedBytes else { throw ArchiveError.tooLarge }
        try file.seek(toOffset: 0)
        var data = Data()
        while let chunk = try file.read(upToCount: min(65_536, maximumEncodedBytes - data.count + 1)), !chunk.isEmpty {
            guard chunk.count <= maximumEncodedBytes - data.count else { throw ArchiveError.tooLarge }
            data.append(chunk)
        }
        return data
    }
    /// Reject the complete candidate before changing local history, so it remains readable on relaunch.
    public func merging(_ incoming: Archive) throws -> Archive {
        try incoming.validate()
        let known = Set(sessions.map(\.id))
        let additions = incoming.sessions.filter { !known.contains($0.id) }
        guard additions.count <= Self.maximumSessions - sessions.count else { throw ArchiveError.tooLarge }
        var candidate = self
        for var session in additions {
            session.invalidateChangedAssessments()
            session.assessments = session.assessments.compactMap { LearningEngine.validate($0, session: session) }
            candidate.sessions.append(session)
        }
        try candidate.validate()
        guard try candidate.encoded().count <= Self.maximumEncodedBytes else { throw ArchiveError.tooLarge }
        return candidate
    }
    private func validate() throws {
        guard LanguageRegistry.module(for: preferences.learningLanguageID) != nil else { throw ArchiveError.unsupportedLanguage }
        guard Set(sessions.map(\.id)).count == sessions.count,
              sessions.count <= Self.maximumSessions,
              preferences.sessionMinutes >= 1, preferences.sessionMinutes <= 60 else { throw ArchiveError.invalid }
        func validDate(_ date: Date) -> Bool { date >= .distantPast && date <= .distantFuture }
        for s in sessions {
            guard LanguageRegistry.module(for: s.languageID) != nil else { throw ArchiveError.unsupportedLanguage }
            // These generous limits exceed a normal session while keeping UI conversions and totals safe.
            guard s.voiceSeconds.isFinite, (0...31_536_000).contains(s.voiceSeconds),
                  [s.inputTokens, s.outputTokens, s.searchCalls].allSatisfy({ (0...1_000_000_000).contains($0) }),
                  validDate(s.startedAt), s.endedAt.map(validDate) ?? true,
                  s.assessments.allSatisfy({ validDate($0.createdAt) }) else { throw ArchiveError.invalid }
            guard Set(s.fragments.map(\.id)).count == s.fragments.count,
                  s.fragments.allSatisfy({ $0.startMS >= 0 && $0.endMS >= $0.startMS && $0.text.count <= 50_000 &&
                      (0...1_000_000).contains($0.revision) && validDate($0.receivedAt) }),
                  s.topics.allSatisfy({ $0.languageID == s.languageID && validDate($0.retrievedAt) }) else { throw ArchiveError.invalid }
        }
    }
    /// Version 1 was Norwegian-only. Migration assigns that provenance once;
    /// version 2 records must explicitly declare their language.
    private static func migrate(_ data: Data) throws -> Data {
        guard var root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = root["schemaVersion"] as? Int else { throw ArchiveError.invalid }
        guard version == 1 || version == 2 else { throw ArchiveError.unsupportedVersion }
        if version == 2 { return data }
        guard var preferences = root["preferences"] as? [String: Any],
              let sessions = root["sessions"] as? [[String: Any]] else { throw ArchiveError.invalid }
        preferences["learningLanguageID"] = LanguageRegistry.defaultID
        if let hidden = preferences["hiddenWords"] as? [String] {
            preferences["hiddenWords"] = hidden.map { LanguageRegistry.defaultID + "|" + $0 }
        }
        root["preferences"] = preferences
        root["sessions"] = sessions.map { original in
            var session = original; session["languageID"] = LanguageRegistry.defaultID
            if let topics = session["topics"] as? [[String: Any]] {
                session["topics"] = topics.map { original in
                    var topic = original; topic["languageID"] = LanguageRegistry.defaultID; return topic
                }
            }
            return session
        }
        root["schemaVersion"] = 2
        return try JSONSerialization.data(withJSONObject: root)
    }
    public func encoded() throws -> Data {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(self)
    }
}
public enum ArchiveError: Error, LocalizedError {
    case tooLarge, unsupportedVersion, unsupportedLanguage, invalid
    public var errorDescription: String? {
        switch self {
        case .tooLarge: "This backup is too large to import."
        case .unsupportedVersion: "This backup needs a newer version of Mural."
        case .unsupportedLanguage: "This backup contains a language module that this version of Mural does not support."
        case .invalid: "This backup has invalid or duplicate records."
        }
    }
}
