import Foundation

/// Interface preferences are separate from learning data and its backup format.
public enum InterfaceLanguage: String, CaseIterable, Sendable, Identifiable {
    case english = "en"
    case simplifiedChinese = "zh-Hans"
    case vietnamese = "vi"

    public static let preferenceKey = "mural.interfaceLanguage"
    public var id: String { rawValue }
    public var locale: Locale { Locale(identifier: rawValue) }
    public var nativeName: String {
        switch self {
        case .english: "English"
        case .simplifiedChinese: "简体中文"
        case .vietnamese: "Tiếng Việt"
        }
    }
    public static var current: Self {
        resolve(saved: UserDefaults.standard.string(forKey: preferenceKey), preferredLanguages: Locale.preferredLanguages)
    }
    public static func resolve(saved: String?, preferredLanguages: [String]) -> Self {
        if let saved, let language = Self(rawValue: saved) { return language }
        for identifier in preferredLanguages {
            switch Locale(identifier: identifier).language.languageCode?.identifier {
            case "zh": return .simplifiedChinese
            case "vi": return .vietnamese
            case "en": return .english
            default: continue
            }
        }
        return .english
    }
}

/// Foundation messages use the same standard catalog as SwiftUI's locale environment.
public enum L10n {
    public static func text(_ key: String, language: InterfaceLanguage = .current, bundle: Bundle = .main) -> String {
        guard let path = bundle.path(forResource: language.rawValue, ofType: "lproj"),
              let localizedBundle = Bundle(path: path) else { return key }
        return localizedBundle.localizedString(forKey: key, value: key, table: "Localizable")
    }
    public static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: text(key), locale: InterfaceLanguage.current.locale, arguments: arguments)
    }
    public static func date(_ date: Date, includeTime: Bool = false) -> String {
        let formatter = DateFormatter()
        formatter.locale = InterfaceLanguage.current.locale
        formatter.dateStyle = .medium
        formatter.timeStyle = includeTime ? .short : .none
        return formatter.string(from: date)
    }
    public static func error(_ error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet: return text("You’re offline. Check your connection and try again.")
            case .timedOut: return text("The request took too long. Please try again.")
            case .cancelled: return text("The request was cancelled.")
            default: return text("The service could not be reached. Check your connection and try again.")
            }
        }
        if let description = (error as? LocalizedError)?.errorDescription { return text(description) }
        let detail = error as NSError
        return format("The operation failed (%@, %d). Please try again.", detail.domain, detail.code)
    }
}
