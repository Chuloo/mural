import Foundation

/// Shared caps for learner-authored text. Both clients must refuse or stop accepting
/// input past these lengths instead of silently truncating on save/send.
public enum TextLimits {
    public static let typedReplyCharacters = 2_000
    public static let correctionCharacters = 10_000

    public static func clampTypedReply(_ text: String) -> String {
        String(text.prefix(typedReplyCharacters))
    }

    public static func clampCorrection(_ text: String) -> String {
        String(text.prefix(correctionCharacters))
    }

    public static func typedReplyExceedsLimit(_ text: String) -> Bool {
        text.count > typedReplyCharacters
    }

    public static func correctionExceedsLimit(_ text: String) -> Bool {
        text.count > correctionCharacters
    }
}
