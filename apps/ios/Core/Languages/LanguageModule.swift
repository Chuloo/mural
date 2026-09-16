import Foundation

/// A target language's content and teaching policy. IDs are stable storage keys.
public struct LanguageModule: Identifiable, Sendable {
    public let id: String
    public let name: String
    public let nativeName: String
    public let variety: String
    public let locale: String
    public let greeting: String
    public let greetingWord: String
    public let speechGuidance: String
    public let writingGuidance: String
    public let lemmaGuidance: String
    public let teachingFocus: [String]
    public let topicPlaceholder: String
    public let lookupUnavailableReply: String
    public let themeOverrides: [String: ConversationTheme]

    /// Available conversation themes, with any language-specific overrides applied.
    public var themes: [ConversationTheme] {
        ConversationTheme.shared.map { themeOverrides[$0.id] ?? $0 }
    }
    /// Default title shown in the UI for this language module.
    public var defaultTitle: String { "A little \(name)" }
    /// Title for the talk/conversation screen.
    public var talkTitle: String { "A little everyday \(name)" }
    /// Title for the settings screen showing language and variety.
    public var settingsTitle: String { "\(name) · \(variety)" }
}

public enum LanguageRegistry {
    public static let defaultID = "nb"
    public static let all: [LanguageModule] = [.norwegian, .spanish, .english, .french, .german, .italian, .portuguese, .mandarin, .turkish]
    /// Returns the language module for the given ID, or nil if not found.
    public static func module(for id: String) -> LanguageModule? { all.first { $0.id == id } }
}

public enum MeaningLanguages {
    public static let all = ["English", "French", "German", "Spanish", "Norwegian", "Portuguese", "Italian", "Chinese (Simplified)", "Polish", "Arabic", "Ukrainian", "Turkish"]
    /// Returns a greeting in the specified meaning language, or English if unsupported.
    public static func greeting(in language: String) -> String {
        ["English": "Hi!", "French": "Salut !", "German": "Hallo!", "Spanish": "¡Hola!", "Norwegian": "Hei!", "Portuguese": "Olá!", "Italian": "Ciao!", "Chinese (Simplified)": "你好！", "Chinese": "你好！", "Polish": "Cześć!", "Arabic": "مرحبًا!", "Ukrainian": "Привіт!", "Turkish": "Merhaba!"][language] ?? "Hi!"
    }
}
