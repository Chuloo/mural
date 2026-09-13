import Foundation

public struct MandarinPronunciationToken: Equatable, Sendable {
    public let text: String
    public let pinyin: String?
    public init(text: String, pinyin: String?) {
        self.text = text
        self.pinyin = pinyin
    }
}

public enum MandarinPinyin {
    public static func tokens(_ text: String) -> [MandarinPronunciationToken] {
        text.map { character in
            if isHan(character), let pinyin = pinyin(for: character) {
                MandarinPronunciationToken(text: String(character), pinyin: pinyin)
            } else {
                MandarinPronunciationToken(text: String(character), pinyin: nil)
            }
        }
    }

    public static func annotated(_ text: String) -> String? {
        var result = ""
        var foundHan = false
        var previousWasHan = false
        for character in text {
            if isHan(character), let pinyin = pinyin(for: character) {
                if previousWasHan { result.append(" ") }
                result.append("\(character)(\(pinyin))")
                foundHan = true
                previousWasHan = true
            } else {
                result.append(character)
                previousWasHan = false
            }
        }
        return foundHan ? result : nil
    }

    private static func pinyin(for character: Character) -> String? {
        let mutable = NSMutableString(string: String(character))
        guard CFStringTransform(mutable, nil, kCFStringTransformMandarinLatin, false) else { return nil }
        let transformed = (mutable as String)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        return transformed.isEmpty ? nil : transformed
    }

    private static func isHan(_ character: Character) -> Bool {
        character.unicodeScalars.contains { scalar in
            switch scalar.value {
            case 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF,
                 0x20000...0x2A6DF, 0x2A700...0x2B73F, 0x2B740...0x2B81F,
                 0x2B820...0x2CEAF, 0x2CEB0...0x2EBEF, 0x30000...0x3134F:
                true
            default:
                false
            }
        }
    }
}
