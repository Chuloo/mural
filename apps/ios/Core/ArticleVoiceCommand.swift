import Foundation

public enum ArticleVoiceCommand: Equatable {
    case next, previous, comprehension, explain

    public static func parse(_ text: String) -> Self? {
        let trim = CharacterSet.whitespacesAndNewlines.union(.punctuationCharacters)
        let value = text.trimmingCharacters(in: trim).lowercased()
        switch value {
        case "下一段", "下一个段落", "next paragraph", "next section", "đoạn tiếp theo": return .next
        case "上一段", "上一个段落", "previous paragraph", "đoạn trước": return .previous
        case "读完了", "我读完了", "可以提问", "开始提问", "i'm done reading", "i am done reading", "you can ask me", "tôi đọc xong rồi": return .comprehension
        case "解释这一段", "讲解这一段", "explain this paragraph", "giải thích đoạn này": return .explain
        default: return nil
        }
    }
}
