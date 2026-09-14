import Foundation

// Builds on richardguerre's Mandarin contribution in Chuloo/mural#4.
public struct MandarinPronunciationToken: Equatable, Sendable {
    public let text: String
    public let pinyin: String?
    public init(text: String, pinyin: String?) {
        self.text = text
        self.pinyin = pinyin
    }
}

public enum MandarinPinyin {
    /// Uses the system's word readings so 银行 and 旅行 keep different readings of 行.
    /// Punctuation, spacing and unrecognized characters remain exactly as supplied.
    public static func tokens(_ text: String) -> [MandarinPronunciationToken] {
        guard !text.isEmpty else { return [] }
        let source = text as NSString
        let tokenizer = CFStringTokenizerCreate(nil, text as CFString,
            CFRange(location: 0, length: source.length), kCFStringTokenizerUnitWord,
            CFLocaleCreate(nil, CFLocaleIdentifier("zh_CN" as CFString)))!
        var result: [MandarinPronunciationToken] = []
        var cursor = 0
        while CFStringTokenizerAdvanceToNextToken(tokenizer).rawValue != 0 {
            let range = CFStringTokenizerGetCurrentTokenRange(tokenizer)
            guard range.location >= cursor, range.length > 0, range.location + range.length <= source.length else { continue }
            if range.location > cursor {
                result.append(.init(text: source.substring(with: NSRange(location: cursor, length: range.location - cursor)), pinyin: nil))
            }
            let word = source.substring(with: NSRange(location: range.location, length: range.length))
            var reading: String?
            if containsHan(word), let latin = CFStringTokenizerCopyCurrentTokenAttribute(tokenizer, kCFStringTokenizerAttributeLatinTranscription) as? String {
                // Apple's dictionary uses v for ü in some readings (e.g. 旅行).
                let normalized = String(String.UnicodeScalarView(latin.lowercased().unicodeScalars.map { $0 == "v" ? "ü" : $0 }))
                    .precomposedStringWithCanonicalMapping.trimmingCharacters(in: .whitespacesAndNewlines)
                if !normalized.isEmpty && !containsHan(normalized) { reading = normalized }
            }
            result.append(.init(text: word, pinyin: reading))
            cursor = range.location + range.length
        }
        if cursor < source.length {
            result.append(.init(text: source.substring(from: cursor), pinyin: nil))
        }
        return result
    }

    /// A separate reading aid; source text and learning evidence are never replaced.
    public static func reading(_ text: String) -> String? {
        let parts = tokens(text)
        guard parts.contains(where: { $0.pinyin != nil }) else { return nil }
        var result = ""
        for part in parts {
            let value = part.pinyin ?? part.text
            if let last = result.last, let first = value.first,
               (last.isLetter || last.isNumber), (first.isLetter || first.isNumber) {
                result.append(" ")
            }
            result.append(value)
        }
        return result
    }

    private static func containsHan(_ text: String) -> Bool {
        text.unicodeScalars.contains { scalar in
            switch scalar.value {
            case 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF,
                 0x20000...0x2FA1F, 0x30000...0x3347F: true
            default: false
            }
        }
    }
}

public struct CaptionSegment: Equatable, Sendable {
    public let text: String
    public let lookup: String?
}

public enum CaptionWords {
    /// Keeps every source character, linking Chinese words instead of whole sentences.
    public static func segments(_ text: String, languageID: String) -> [CaptionSegment] {
        if languageID == "zh" {
            return MandarinPinyin.tokens(text).map {
                CaptionSegment(text: $0.text, lookup: $0.text.contains(where: \.isLetter) ? $0.text : nil)
            }
        }
        var result: [CaptionSegment] = []
        var run = ""
        for character in text {
            if let last = run.last, last.isWhitespace != character.isWhitespace {
                result.append(segment(run)); run = ""
            }
            run.append(character)
        }
        if !run.isEmpty { result.append(segment(run)) }
        return result
    }

    private static func segment(_ text: String) -> CaptionSegment {
        let word = text.trimmingCharacters(in: .punctuationCharacters.union(.whitespacesAndNewlines))
        return CaptionSegment(text: text, lookup: word.contains(where: \.isLetter) ? word : nil)
    }
}
