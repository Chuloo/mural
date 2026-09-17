import Foundation

/// Energy endpointing for one speaker at a time. The thresholds are calibration knobs: tune them on real devices.
public struct SpeechDetector: Sendable {
    public enum Event: Sendable, Equatable { case none, start, end, discard }
    public static let minLevel = 0.01
    public static let floorRatio = 3.0
    public static let floorAdaptation = 0.05
    public static let onsetMS = 60
    /// Learners pause to find words; a short gap must not end their turn.
    public static let endSilenceMS = 1_200
    public static let minVoicedMS = 250
    public static let maxUtteranceMS = 30_000

    public private(set) var active = false
    /// Quiet time at the end of the current turn; zero when a turn is cut off at the length limit mid-speech.
    public private(set) var silenceMS = 0
    private let frameMS: Int
    private var floor = 0.003, onset = 0, length = 0, voiced = 0

    public init(frameMS: Int = 20) { self.frameMS = frameMS }

    public mutating func feed(_ level: Double) -> Event {
        let loud = level >= max(Self.minLevel, floor * Self.floorRatio)
        if !active {
            guard loud else { onset = 0; floor += (level - floor) * Self.floorAdaptation; return .none }
            onset += 1
            guard onset * frameMS >= Self.onsetMS else { return .none }
            // The onset frames are speech too; without them a short "sí" would be discarded as a click.
            let onsetDuration = onset * frameMS
            active = true; onset = 0; length = onsetDuration; voiced = onsetDuration; silenceMS = 0
            return .start
        }
        length += frameMS
        if loud { voiced += frameMS; silenceMS = 0 } else { silenceMS += frameMS }
        guard silenceMS >= Self.endSilenceMS || length >= Self.maxUtteranceMS else { return .none }
        active = false
        return voiced >= Self.minVoicedMS ? .end : .discard
    }

    public mutating func reset() { active = false; onset = 0 }
}

/// Coordinator guidance waiting for the next spoken reply. A reply request made while one is pending is dropped.
public struct TurnGuidance: Sendable {
    public private(set) var replyPending = false
    private var notes: [String] = []
    private let limit: Int

    public init(limit: Int = 12) { self.limit = limit }

    /// Returns true when the caller should queue a reply.
    public mutating func add(_ note: String, respond: Bool) -> Bool {
        notes.append(note)
        if notes.count > limit { notes.removeFirst() }
        guard respond, !replyPending else { return false }
        replyPending = true
        return true
    }

    /// Starts a reply. Notes stay until `consumed`, so a failed reply keeps its guidance for the next turn.
    public mutating func begin() -> [String] { replyPending = false; return notes }
    /// Removes one occurrence per used note, so an identical note added during the reply survives.
    public mutating func consumed(_ used: [String]) {
        for note in used { if let index = notes.firstIndex(of: note) { notes.remove(at: index) } }
    }
    public mutating func clear() { notes = []; replyPending = false }

    public static func prompt(_ used: [String]) -> String {
        used.isEmpty ? "" : "\nApp guidance for this reply, never to be read aloud:\n" + used.joined(separator: "\n")
    }
}

public enum Wav {
    /// 16-bit mono PCM WAV. Apple platforms are little-endian, matching WAV's byte order.
    public static func encode(_ samples: [Int16], sampleRate: Int) -> Data {
        var data = Data(capacity: 44 + samples.count * 2)
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8)); append(UInt32(36 + samples.count * 2)); data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); append(UInt32(16)); append(UInt16(1)); append(UInt16(1))
        append(UInt32(sampleRate)); append(UInt32(sampleRate * 2)); append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); append(UInt32(samples.count * 2))
        samples.withUnsafeBytes { data.append(contentsOf: $0) }
        return data
    }

    /// Root-mean-square level from 0 to 1.
    public static func level(_ samples: [Int16]) -> Double {
        guard !samples.isEmpty else { return 0 }
        let sum = samples.reduce(0.0) { $0 + Double($1) * Double($1) }
        return (sum / Double(samples.count)).squareRoot() / 32_768
    }
}

public enum CustomEndpointURL {
    /// HTTPS only, without credentials, query or fragment. Always ends in "/" so API paths resolve below it.
    public static func parse(_ value: String) -> URL? {
        guard var components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "https", let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil
        else { return nil }
        if !components.path.hasSuffix("/") { components.path += "/" }
        return components.url
    }
}
