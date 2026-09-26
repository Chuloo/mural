import Foundation

enum AIProvider: String, CaseIterable, Identifiable {
    case openAI
    case googleAIStudio

    var id: String { rawValue }

    var title: String {
        switch self {
        case .openAI: "OpenAI"
        case .googleAIStudio: "Google AI Studio"
        }
    }

    var helperModel: String {
        switch self {
        case .openAI: "gpt-5.6-luna"
        case .googleAIStudio: "gemma-4-31b-it"
        }
    }

    var liveModel: String {
        switch self {
        case .openAI: "gpt-live-1"
        case .googleAIStudio: "gemini-3.8-live"
        }
    }

    var keyURL: URL {
        switch self {
        case .openAI: URL(string: "https://platform.openai.com/api-keys")!
        case .googleAIStudio: URL(string: "https://aistudio.google.com/app/apikey")!
        }
    }

    var usageURL: URL {
        switch self {
        case .openAI: URL(string: "https://platform.openai.com/usage")!
        case .googleAIStudio: URL(string: "https://aistudio.google.com/app/usage")!
        }
    }

    var dataControlsURL: URL {
        switch self {
        case .openAI: URL(string: "https://developers.openai.com/api/docs/guides/your-data")!
        case .googleAIStudio: URL(string: "https://ai.google.dev/gemini-api/docs/usage-policies")!
        }
    }

    var keyPlaceholder: String {
        switch self {
        case .openAI: "OpenAI API key"
        case .googleAIStudio: "Google AI Studio API key"
        }
    }

    var keyHelp: String {
        switch self {
        case .openAI: "Stored only on this iPhone. Mural sends it directly to OpenAI."
        case .googleAIStudio: "Stored only on this iPhone. Mural sends it directly to Google AI Studio."
        }
    }
}

enum AIProviderSelection {
    private static let defaultsKey = "mural.ai-provider"

    static var current: AIProvider {
        guard let rawValue = UserDefaults.standard.string(forKey: defaultsKey),
              let provider = AIProvider(rawValue: rawValue) else { return .openAI }
        return provider
    }

    static func save(_ provider: AIProvider) {
        UserDefaults.standard.set(provider.rawValue, forKey: defaultsKey)
    }
}
