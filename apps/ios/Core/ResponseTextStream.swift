import Foundation

/// Bounded SSE decoding for meaning helpers. Completion is required before a result is cached.
public struct ResponseTextStream {
    public enum Failure: Error { case malformed, incomplete, refused, tooLarge }
    public enum Update { case text(String), completed([String: Any]) }
    private var dataLines: [String] = []
    private var eventBytes = 0
    private var totalBytes = 0
    private var text = ""
    private var completed = false
    private var lineBytes = Data()
    public init() {}

    // AsyncBytes.lines drops empty lines, which are the event separators in SSE.
    public mutating func consume(byte: UInt8) throws -> Update? {
        guard !completed else { return nil }
        if byte == 10 {
            if lineBytes.last == 13 { lineBytes.removeLast() }
            guard let line = String(data: lineBytes, encoding: .utf8) else { throw Failure.malformed }
            lineBytes.removeAll(keepingCapacity: true)
            return try consume(line)
        }
        lineBytes.append(byte)
        guard lineBytes.count <= 65_536 else { throw Failure.tooLarge }
        return nil
    }

    public mutating func consume(_ line: String) throws -> Update? {
        guard !completed else { return nil }
        let count = line.utf8.count + 1
        totalBytes += count; eventBytes += count
        guard eventBytes <= 65_536, totalBytes <= 2_097_152 else { throw Failure.tooLarge }
        if !line.isEmpty {
            if line.hasPrefix("data:") {
                let data = line.dropFirst(5)
                dataLines.append(String(data.first == " " ? data.dropFirst() : data))
            }
            return nil
        }
        let payload = dataLines.joined(separator: "\n")
        dataLines.removeAll(keepingCapacity: true); eventBytes = 0
        guard !payload.isEmpty else { return nil }
        guard payload != "[DONE]", let data = payload.data(using: .utf8),
              let event = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = event["type"] as? String else { throw Failure.incomplete }
        switch type {
        case "response.output_text.delta":
            guard let delta = event["delta"] as? String else { throw Failure.malformed }
            text += delta
            guard text.utf8.count <= 65_536 else { throw Failure.tooLarge }
            return .text(text)
        case "response.completed":
            guard let response = event["response"] as? [String: Any], response["status"] as? String == "completed" else { throw Failure.incomplete }
            completed = true
            return .completed(response)
        case "response.refusal.delta", "response.refusal.done": throw Failure.refused
        case "error", "response.failed", "response.incomplete": throw Failure.incomplete
        default: return nil
        }
    }
}
