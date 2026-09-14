import Foundation

/// The phone stores only an application pairing credential, never the subscription OAuth token.
public struct SubscriptionConnection: Codable, Equatable, Sendable {
    public let origin: URL
    public let token: String
    /// The voice selected by the user for the next subscription conversation.
    /// Older keychain entries decode as the service's default voice.
    public let voice: String

    public init(address: String, token: String, voice: String = "cove") throws {
        let value = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var url = URLComponents(string: value), url.scheme == "https",
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil, url.path.isEmpty || url.path == "/",
              url.port.map({ (1...65535).contains($0) }) ?? true else { throw ConnectionError.address }
        let credential = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (32...128).contains(credential.count), !credential.hasPrefix("sk-"),
              credential.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil else { throw ConnectionError.token }
        url.path = ""
        guard let origin = url.url else { throw ConnectionError.address }
        guard Self.isValidVoiceID(voice) else { throw ConnectionError.unsupportedVoice }
        self.origin = origin; self.token = credential; self.voice = voice
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(address: container.decode(String.self, forKey: .origin),
                      token: container.decode(String.self, forKey: .token),
                      voice: container.decodeIfPresent(String.self, forKey: .voice) ?? "cove")
    }

    public static func isValidVoiceID(_ value: String) -> Bool {
        value.range(of: "^[a-z][a-z0-9_-]{0,31}$", options: .regularExpression) != nil
    }

    /// Resolves the one-time installer input without touching Keychain or environment state.
    /// A missing pairing code means a normal relaunch and therefore skips provisioning.
    public static func installerProvisioningConnection(serviceAddress: String?, pairingCode: String?, existing: SubscriptionConnection?) throws -> SubscriptionConnection? {
        guard let pairingCode else { return nil }
        guard let serviceAddress, !serviceAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ConnectionError.address }
        let candidate = try SubscriptionConnection(address: serviceAddress, token: pairingCode)
        if let existing {
            guard existing.origin == candidate.origin, existing.token == candidate.token else { throw ConnectionError.provisioningConflict }
            return existing
        }
        return candidate
    }

    public struct VoiceCatalog: Equatable, Sendable {
        public let voices: [String]
        public let defaultVoice: String

        public init(account: [String: Any]) throws {
            guard let raw = account["voices"] as? [Any] else { throw ConnectionError.invalidVoiceCatalog }
            var seen = Set<String>(), values = [String]()
            for item in raw {
                guard let voice = item as? String, SubscriptionConnection.isValidVoiceID(voice), seen.insert(voice).inserted else {
                    throw ConnectionError.invalidVoiceCatalog
                }
                values.append(voice)
            }
            guard values.count <= 40, !values.isEmpty, let defaultVoice = account["defaultVoice"] as? String,
                  values.contains(defaultVoice) else { throw ConnectionError.invalidVoiceCatalog }
            self.voices = values; self.defaultVoice = defaultVoice
        }
    }

    public func endpoint(_ path: String, after: Int? = nil) throws -> URL {
        let pieces = path.split(separator: "/", omittingEmptySubsequences: false)
        let sessionPath = pieces.count >= 3 && pieces[0] == "live" && pieces[1] == "sessions"
            && UUID(uuidString: String(pieces[2])) != nil
            && (pieces.count == 3 || (pieces.count == 4 && pieces[3] == "events"))
        guard ["account", "responses", "live/sessions"].contains(path) || sessionPath,
              after == nil || (path.hasSuffix("/events") && after! >= 0),
              var components = URLComponents(url: origin, resolvingAgainstBaseURL: false) else { throw ConnectionError.address }
        components.path = "/v1/" + path
        if let after { components.queryItems = [URLQueryItem(name: "after", value: String(after))] }
        guard let url = components.url else { throw ConnectionError.address }
        return url
    }

    public enum ConnectionError: LocalizedError {
        case address, token, storage, unsupportedVoice, invalidVoiceCatalog, provisioningConflict
        public var errorDescription: String? {
            switch self {
            case .address: "Enter your private connection service’s HTTPS address, without a path."
            case .token: "Enter the pairing code from your connection service, not an OpenAI API key."
            case .storage: "The saved subscription connection is unavailable. Unlock your phone or reconnect in Settings."
            case .unsupportedVoice: "This voice is not available from your ChatGPT subscription service. Choose one from the current voice list."
            case .invalidVoiceCatalog: "Your subscription service returned an invalid voice list. Reconnect and try again."
            case .provisioningConflict: "A different subscription connection is already saved on this phone. It was not replaced."
            }
        }
    }
}
