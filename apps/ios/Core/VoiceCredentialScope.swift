import Foundation

/// Retains authentication only for a voice conversation started while the device was unlocked.
/// Persistent credentials keep their existing device-only, when-unlocked protection.
public struct VoiceCredentialScope {
    private var key: String?
    private var expiresAt: Date?
    public init() {}

    public mutating func begin(key: String, now: Date = .now) {
        self.key = key
        expiresAt = now.addingTimeInterval(65 * 60)
    }
    public mutating func end(now: Date = .now) {
        // Allow already-scheduled final helpers to authenticate for at most one minute.
        if let expiry = expiresAt { expiresAt = min(expiry, now.addingTimeInterval(60)) }
    }
    public mutating func clear() { key = nil; expiresAt = nil }

    public mutating func credential(protectedDataAvailable: Bool, now: Date = .now,
                                    readStored: () -> String?) -> String? {
        if let expiry = expiresAt, now >= expiry { clear() }
        if protectedDataAvailable {
            let stored = readStored()
            // An unlocked deletion or replacement takes effect on the next request.
            if expiresAt != nil { key = stored }
            return stored
        }
        return expiresAt == nil ? nil : key
    }
}
