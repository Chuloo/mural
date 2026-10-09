import Foundation

/// A touch pauses following for this passage, including later streaming revisions.
public struct CaptionFollowing: Equatable, Sendable {
    public private(set) var passageID: String?
    public private(set) var interrupted = false
    public init() {}
    public mutating func receive(_ id: String?) {
        if id != passageID { passageID = id; interrupted = false }
    }
    public mutating func interrupt() { interrupted = true }
    public func nextOffset(current: Double, maximum: Double, elapsed: Double, reducedMotion: Bool = false) -> Double {
        guard passageID != nil, !interrupted else { return current }
        let end = max(0, maximum)
        if reducedMotion { return end }
        return min(end, max(0, current) + 24 * max(0, min(elapsed, 0.1)))
    }
}
