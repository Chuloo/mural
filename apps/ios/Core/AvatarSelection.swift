import Foundation

public enum AvatarMode: String, Codable, CaseIterable, Sendable {
    case animated, cartoon, animal, custom
}

/// Stores only presentation choices and a local image basename, never an external path.
public struct AvatarSelection: Codable, Equatable, Sendable {
    public var mode: AvatarMode = .animated
    public var index: Int = 0
    public var customImageFilename: String?

    public init() {}

    public static func validCustomImageFilename(_ filename: String) -> Bool {
        guard filename.count == 40, filename.hasSuffix(".jpg") else { return false }
        return UUID(uuidString: String(filename.dropLast(4))) != nil
    }

    public var isValid: Bool {
        guard (0..<20).contains(index) else { return false }
        if let filename = customImageFilename, !Self.validCustomImageFilename(filename) { return false }
        return mode != .custom || customImageFilename != nil
    }
}
