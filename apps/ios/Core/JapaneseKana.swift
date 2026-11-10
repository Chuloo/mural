import Foundation

public struct JapaneseReadingToken: Equatable, Sendable {
    public let text: String
    public let kana: String?
    public init(text: String, kana: String?) {
        self.text = text
        self.kana = kana
    }
}

public enum JapaneseKana {
    /// Uses the system's word readings so 今日 and 一人 read as words rather than character by character.
    /// Kana, punctuation, spacing and words without a dictionary reading remain exactly as supplied.
    public static func tokens(_ text: String) -> [JapaneseReadingToken] {
        SystemWordReadings.tokens(text, locale: "ja_JP").map { token in
            guard MandarinPinyin.containsHan(token.text),
                  let kana = everydayReadings[token.text] ?? token.latin?.applyingTransform(.latinToHiragana, reverse: false),
                  !kana.isEmpty, kana.unicodeScalars.allSatisfy(isHiragana) else {
                return .init(text: token.text, kana: nil)
            }
            return .init(text: token.text, kana: kana)
        }
    }

    /// A separate reading aid; source text and learning evidence are never replaced.
    public static func reading(_ text: String) -> String? {
        let parts = tokens(text)
        guard parts.contains(where: { $0.kana != nil }) else { return nil }
        return parts.map { $0.kana ?? $0.text }.joined()
    }

    /// Everyday words whose dictionary reading is the formal variant.
    static let everydayReadings = ["私": "わたし", "日本": "にほん"]

    private static func isHiragana(_ scalar: Unicode.Scalar) -> Bool {
        (0x3041...0x309F).contains(scalar.value) || scalar.value == 0x30FC
    }
}

/// The reading aid shown below text in languages written with Han characters.
public enum ReadingAid {
    public static func supports(languageID: String) -> Bool { languageID == "zh" || languageID == "ja" }

    public static func reading(_ text: String, languageID: String) -> String? {
        switch languageID {
        case "zh": MandarinPinyin.reading(text)
        case "ja": JapaneseKana.reading(text)
        default: nil
        }
    }
}
