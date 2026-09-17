import Foundation
import MuralCore

final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct APIUsage { var input = 0; var output = 0; var searches = 0 }
struct APIResult { var text: String; var sources: [SourceLink]; var usage: APIUsage }

enum EndpointProtocol: String, Codable, Hashable, CaseIterable { case chatCompletions, responses }

/// A learner-owned OpenAI-compatible server. Settings live in UserDefaults; its key lives in the Keychain.
/// Voice runs turn by turn through the server's audio endpoints.
struct CustomEndpoint: Codable, Equatable {
    var enabled = false
    var baseURL = ""
    var style = EndpointProtocol.chatCompletions
    var model = ""
    var transcriptionModel = ""
    var speechModel = ""
    var voice = ""
    /// Asks reasoning models (e.g. Qwen on vLLM) to answer directly. Optional so settings saved before it still decode.
    var skipThinking: Bool?

    var url: URL? { CustomEndpointURL.parse(baseURL) }
    var textReady: Bool { url != nil && !model.isEmpty }
    var voiceReady: Bool { textReady && !transcriptionModel.isEmpty && !speechModel.isEmpty && !voice.isEmpty }

    private static let defaultsKey = "mural.customEndpoint"
    static func load() -> CustomEndpoint {
        UserDefaults.standard.data(forKey: defaultsKey).flatMap { try? JSONDecoder().decode(CustomEndpoint.self, from: $0) } ?? CustomEndpoint()
    }
    /// The endpoint personal requests use, or nil for OpenAI.
    static var active: CustomEndpoint? { let endpoint = load(); return endpoint.enabled && endpoint.textReady ? endpoint : nil }
    func save() throws { UserDefaults.standard.set(try JSONEncoder().encode(self), forKey: Self.defaultsKey) }
    static func delete() throws {
        try CredentialStore.delete(service: CredentialStore.customEndpoint)
        UserDefaults.standard.removeObject(forKey: defaultsKey)
    }
}

@MainActor final class APIClient {
    private let session: URLSession
    private struct Target { var base: URL; var key: String?; var endpoint: CustomEndpoint? }
    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 60
        config.httpCookieStorage = nil; config.urlCache = nil
        session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    private func resolveTarget() throws -> Target {
        // A custom server may need no key; OpenAI always does.
        if let endpoint = CustomEndpoint.active, let base = endpoint.url {
            return Target(base: base, key: CredentialStore.read(service: CredentialStore.customEndpoint), endpoint: endpoint)
        }
        guard let key = CredentialStore.read() else { throw APIError.missingKey }
        return Target(base: URL(string: "https://api.openai.com/v1/")!, key: key, endpoint: nil)
    }
    func post(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        try await postJSON(try resolveTarget(), path, body: body)
    }
    private func postJSON(_ target: Target, _ path: String, body: [String: Any]) async throws -> [String: Any] {
        let data = try await send(target, path, body: try JSONSerialization.data(withJSONObject: body), contentType: "application/json")
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw APIError.invalidResponse }
        return json
    }
    /// The same limits as Android: a faulty server can't exhaust memory with an endless JSON or audio body.
    static let jsonLimit = 1_048_576, audioLimit = 16_777_216, errorBodyLimit = 16_384
    private func send(_ target: Target, _ path: String, body: Data, contentType: String, limit: Int = APIClient.jsonLimit) async throws -> Data {
        guard let url = URL(string: path, relativeTo: target.base)?.absoluteURL else { throw APIError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        if let key = target.key { request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization") }
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        let succeeded = (200..<300).contains(http.statusCode)
        // Error bodies only supply a short error code, so they're read no further than that.
        let cap = succeeded ? limit : Self.errorBodyLimit
        if succeeded && http.expectedContentLength > Int64(cap) { throw APIError.invalidResponse }
        var data = Data()
        for try await byte in bytes {
            guard data.count < cap else {
                if succeeded { throw APIError.invalidResponse }
                break
            }
            data.append(byte)
        }
        try Task.checkCancellation()
        guard succeeded else {
            // OpenAI failures name OpenAI and its billing; a custom server's failure must not.
            if target.endpoint != nil { throw APIError.endpoint(http.statusCode) }
            throw ProviderFailure(status: http.statusCode, body: data, reference: http.value(forHTTPHeaderField: "x-request-id"))
        }
        return data
    }
    /// `language` is the learning language. Left to auto-detection, Whisper can turn accented Spanish into an
    /// English translation; the cost is that a reply in another language may transcribe poorly.
    func transcribe(wav: Data, language: String) async throws -> String {
        let target = try resolveTarget()
        guard let endpoint = target.endpoint else { throw APIError.invalidResponse }
        let boundary = "mural-" + UUID().uuidString
        var body = Data()
        // Whisper names Norwegian "no".
        for (name, value) in [("model", endpoint.transcriptionModel), ("response_format", "json"), ("language", language == "nb" ? "no" : language)] {
            body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
        }
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".utf8))
        body.append(wav)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        let data = try await send(target, "audio/transcriptions", body: body, contentType: "multipart/form-data; boundary=\(boundary)")
        guard let text = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["text"] as? String else { throw APIError.invalidResponse }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    /// Returns WAV bytes; 16-bit PCM WAV is the one format every compatible speech server offers.
    func speak(_ text: String) async throws -> Data {
        let target = try resolveTarget()
        guard let endpoint = target.endpoint else { throw APIError.invalidResponse }
        let body: [String: Any] = ["model": endpoint.speechModel, "voice": endpoint.voice, "input": text, "response_format": "wav"]
        return try await send(target, "audio/speech", body: try JSONSerialization.data(withJSONObject: body), contentType: "application/json", limit: Self.audioLimit)
    }
    func respond(instructions: String, input: String, schema: [String: Any]? = nil, search: Bool = false, onText: (@MainActor (String) -> Void)? = nil) async throws -> APIResult {
        let target = try resolveTarget()
        if let endpoint = target.endpoint, endpoint.style == .chatCompletions {
            // No standard web search exists in Chat Completions, so `search` is ignored and topics stay unsourced.
            var body: [String: Any] = ["model": endpoint.model, "messages": [["role": "system", "content": instructions], ["role": "user", "content": input]]]
            // vLLM's chat-template switch; OpenAI and some other servers reject unknown fields, so it's opt-in.
            if endpoint.skipThinking == true { body["chat_template_kwargs"] = ["enable_thinking": false] }
            if let schema { body["response_format"] = ["type": "json_schema", "json_schema": ["name": "mural_result", "strict": true, "schema": schema]] }
            let result = try Self.decodeChatCompletion(try await postJSON(target, "chat/completions", body: body))
            onText?(result.text) // A custom endpoint answers in one piece.
            return result
        }
        var body: [String: Any] = ["model": target.endpoint?.model ?? "gpt-5.6-luna", "store": false, "instructions": instructions,
                                  "input": [["role": "user", "content": input]], "max_output_tokens": schema == nil ? 1400 : 2200,
                                  "reasoning": ["effort": "low"]]
        if let schema { body["text"] = ["format": ["type": "json_schema", "name": "mural_result", "strict": true, "schema": schema]] }
        // Web search is OpenAI's hosted tool; a compatible Responses server may reject it.
        if search && target.endpoint == nil { body["tools"] = [["type": "web_search"]]; body["tool_choice"] = "auto"; body["max_tool_calls"] = 1 }
        let json: [String: Any]
        // OpenAI streams meanings as they arrive; a custom Responses server answers in one piece.
        if let onText, target.endpoint == nil {
            body["stream"] = true
            json = try await streamResponse(target, body: body, onText: onText)
        } else { json = try await postJSON(target, "responses", body: body) }
        guard json["status"] as? String == "completed" else { throw APIError.incomplete }
        var text = "", sources: [SourceLink] = [], usage = APIUsage()
        for item in json["output"] as? [[String: Any]] ?? [] {
            if item["type"] as? String == "web_search_call" { usage.searches += 1 }
            for content in item["content"] as? [[String: Any]] ?? [] {
                if content["type"] as? String == "refusal" { throw APIError.refused }
                if content["type"] as? String == "output_text" { text += content["text"] as? String ?? "" }
                for citation in content["annotations"] as? [[String: Any]] ?? [] {
                    guard citation["type"] as? String == "url_citation", let url = citation["url"] as? String else { continue }
                    let source = SourceLink(title: citation["title"] as? String ?? "Source", url: url)
                    if source.safeURL != nil && !sources.contains(where: { $0.url == url }) { sources.append(source) }
                }
            }
        }
        if let u = json["usage"] as? [String: Any] { usage.input = u["input_tokens"] as? Int ?? 0; usage.output = u["output_tokens"] as? Int ?? 0 }
        // A local reasoning model behind a Responses endpoint can inline its thinking too.
        text = text.replacingOccurrences(of: Self.thinking, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw APIError.incomplete }
        if target.endpoint != nil { onText?(text) }
        return APIResult(text: text, sources: sources, usage: usage)
    }
    /// Inline reasoning, including a block the server cut off before its closing tag.
    static let thinking = "<think>[\\s\\S]*?(?:</think>|$)"
    /// Compatible servers carry no web citations. Local reasoning models may inline thinking, which is never spoken or stored.
    static func decodeChatCompletion(_ json: [String: Any]) throws -> APIResult {
        guard let choice = (json["choices"] as? [[String: Any]])?.first, let message = choice["message"] as? [String: Any] else { throw APIError.incomplete }
        let finish = choice["finish_reason"] as? String
        if message["refusal"] is String || finish == "content_filter" { throw APIError.refused }
        if finish == "length" { throw APIError.incomplete }
        let text = (message["content"] as? String ?? "")
            .replacingOccurrences(of: Self.thinking, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw APIError.incomplete }
        let usage = json["usage"] as? [String: Any]
        return APIResult(text: text, sources: [], usage: APIUsage(input: usage?["prompt_tokens"] as? Int ?? 0, output: usage?["completion_tokens"] as? Int ?? 0))
    }
    private func streamResponse(_ target: Target, body: [String: Any], onText: @MainActor (String) -> Void) async throws -> [String: Any] {
        guard let key = target.key, let url = URL(string: "responses", relativeTo: target.base)?.absoluteURL else { throw APIError.missingKey }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            var body = Data()
            for try await byte in bytes { body.append(byte); if body.count >= 16_384 { break } }
            throw ProviderFailure(status: http.statusCode, body: body, reference: http.value(forHTTPHeaderField: "x-request-id"))
        }
        guard http.value(forHTTPHeaderField: "Content-Type")?.lowercased().hasPrefix("text/event-stream") == true else { throw APIError.invalidResponse }
        var decoder = ResponseTextStream()
        do {
            for try await byte in bytes {
                try Task.checkCancellation()
                guard let update = try decoder.consume(byte: byte) else { continue }
                switch update {
                case .text(let value): onText(value)
                case .completed(let result): return result
                }
            }
        } catch ResponseTextStream.Failure.refused { throw APIError.refused }
        catch is ResponseTextStream.Failure { throw APIError.incomplete }
        throw APIError.incomplete
    }
    static func object(_ fields: [String: Any]) -> [String: Any] { ["type": "object", "properties": fields, "required": fields.keys.sorted(), "additionalProperties": false] }
    static let string: [String: Any] = ["type": "string"]
    static func assessmentSchema(language: LanguageModule) -> [String: Any] { object([
        "outcome": ["type": "string", "enum": ["success", "partial", "breakdown", "uncertain"]],
        "suggestedLevel": ["type": "integer", "minimum": 0, "maximum": 5], "nextGoal": string, "capability": string,
        "words": ["type": "array", "maxItems": 12, "items": object([
            "lemma": string, "meaning": string, "form": string, "quote": string, "language": ["type": "string", "enum": Array(Set([language.id, "en", "mixed", "uncertain"])).sorted()],
            "kind": ["type": "string", "enum": ["exposure", "understanding", "assisted", "independent", "lapse"]],
            "confidence": ["type": "number", "minimum": 0, "maximum": 1], "sourceIDs": ["type": "array", "items": string]
        ])]
    ]) }
    enum APIError: LocalizedError {
        case missingKey, invalidResponse, incomplete, refused, http(Int), endpoint(Int)
        var errorDescription: String? {
            switch self {
            case .endpoint(let status): "Your custom endpoint couldn’t complete the request (HTTP \(status)). Check its settings."
            case .missingKey: "Add your OpenAI key in Settings to begin."
            case .invalidResponse, .incomplete: CustomEndpoint.active == nil
                ? "OpenAI returned an incomplete response. Please try again."
                : "Your custom endpoint returned an incomplete or unexpected response. Check its API style and models."
            case .refused: "Mural couldn’t complete that request. Try a different topic."
            case .http(401): "Your OpenAI key wasn’t accepted. Check it in Settings."
            case .http(403), .http(404): "This API key may not have access to the requested model. Check your OpenAI project."
            case .http(429): "OpenAI’s usage or rate limit was reached. Check your project’s billing and limits."
            case .http(let status): "OpenAI couldn’t complete the request (HTTP \(status)). Please try again."
            }
        }
    }
}
