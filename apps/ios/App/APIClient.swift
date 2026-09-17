import Foundation
import MuralCore

final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct APIUsage { var input = 0; var output = 0; var searches = 0 }
struct APIResult {
    var text: String
    var sources: [SourceLink]
    var usage: APIUsage
    var searchEntryPointHTML: String? = nil
}

@MainActor final class APIClient {
    private let session: URLSession
    private(set) var provider: AIProvider
    init(provider: AIProvider = AIProviderSelection.current) {
        self.provider = provider
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 60
        config.httpCookieStorage = nil; config.urlCache = nil
        session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    func setProvider(_ provider: AIProvider) {
        guard self.provider != provider else { return }
        self.provider = provider
    }
    func post(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        guard let key = CredentialStore.read(.openAI) else { throw APIError.missingKey }
        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/" + path)!)
        request.httpMethod = "POST"; request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw ProviderFailure(status: http.statusCode, body: data, reference: http.value(forHTTPHeaderField: "x-request-id")) }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw APIError.invalidResponse }
        return json
    }
    func respond(instructions: String, input: String, schema: [String: Any]? = nil, search: Bool = false) async throws -> APIResult {
        if provider == .googleAIStudio {
            return try await geminiRespond(instructions: instructions, input: input, schema: schema, search: search)
        }
        var body: [String: Any] = ["model": "gpt-5.6-luna", "store": false, "instructions": instructions,
                                  "input": [["role": "user", "content": input]], "max_output_tokens": schema == nil ? 1400 : 2200,
                                  "reasoning": ["effort": "low"]]
        if let schema { body["text"] = ["format": ["type": "json_schema", "name": "mural_result", "strict": true, "schema": schema]] }
        if search { body["tools"] = [["type": "web_search"]]; body["tool_choice"] = "auto"; body["max_tool_calls"] = 1 }
        let json = try await post("responses", body: body)
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
        guard !text.isEmpty else { throw APIError.incomplete }
        return APIResult(text: text, sources: sources, usage: usage)
    }
    private func geminiRespond(instructions: String, input: String, schema: [String: Any]?, search: Bool) async throws -> APIResult {
        guard let key = CredentialStore.read(.googleAIStudio) else { throw APIError.missingKey }
        let model = AIProvider.googleAIStudio.helperModel
        var generationConfig: [String: Any] = ["maxOutputTokens": schema == nil ? 1400 : 2200]
        if schema != nil {
            generationConfig["responseMimeType"] = "application/json"
            generationConfig["responseJsonSchema"] = Self.geminiCompatibleSchema(schema!)
        }
        var body: [String: Any] = [
            "systemInstruction": ["parts": [["text": instructions]]],
            "contents": [["role": "user", "parts": [["text": input]]]],
            "generationConfig": generationConfig
        ]
        if search { body["tools"] = [["googleSearch": [:]]] }
        let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(key, forHTTPHeaderField: "x-goog-api-key")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            throw ProviderFailure(status: http.statusCode, body: data, reference: http.value(forHTTPHeaderField: "x-request-id"), providerName: "Google AI Studio")
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidate = (json["candidates"] as? [[String: Any]])?.first else { throw APIError.invalidResponse }
        var text = ""
        for part in (candidate["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? [] {
            text += part["text"] as? String ?? ""
        }
        let grounding = candidate["groundingMetadata"] as? [String: Any]
        var sources: [SourceLink] = []
        for chunk in (grounding?["groundingChunks"] as? [[String: Any]]) ?? [] {
            guard let web = chunk["web"] as? [String: Any], let url = web["uri"] as? String else { continue }
            let source = SourceLink(title: web["title"] as? String ?? "Source", url: url)
            if source.safeURL != nil && !sources.contains(where: { $0.url == url }) { sources.append(source) }
        }
        var usage = APIUsage()
        if let metadata = json["usageMetadata"] as? [String: Any] {
            usage.input = metadata["promptTokenCount"] as? Int ?? 0
            usage.output = metadata["candidatesTokenCount"] as? Int ?? 0
        }
        let searchQueries = (grounding?["webSearchQueries"] as? [String] ?? [])
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        usage.searches = search ? (searchQueries.isEmpty && !sources.isEmpty ? 1 : searchQueries.count) : 0
        let searchEntryPointHTML = ((grounding?["searchEntryPoint"] as? [String: Any])?["renderedContent"] as? String).map { String($0.prefix(32_000)) }
        guard !text.isEmpty else { throw APIError.incomplete }
        return APIResult(text: text, sources: sources, usage: usage, searchEntryPointHTML: searchEntryPointHTML)
    }
    private static func geminiCompatibleSchema(_ value: Any) -> Any {
        if let dictionary = value as? [String: Any] {
            return dictionary.reduce(into: [String: Any]()) { result, entry in
                guard entry.key != "additionalProperties" else { return }
                result[entry.key] = geminiCompatibleSchema(entry.value)
            }
        }
        if let array = value as? [Any] { return array.map { geminiCompatibleSchema($0) } }
        return value
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
        case missingKey, invalidResponse, incomplete, refused, http(Int)
        var errorDescription: String? {
            switch self {
            case .missingKey: "Add your selected AI provider key in Settings to begin."
            case .invalidResponse, .incomplete: "The AI provider returned an incomplete response. Please try again."
            case .refused: "Mural couldn’t complete that request. Try a different topic."
            case .http(401): "Your AI provider key wasn’t accepted. Check it in Settings."
            case .http(403), .http(404): "This API key may not have access to the requested model. Check your provider project."
            case .http(429): "The AI provider’s usage or rate limit was reached. Check your project’s billing and limits."
            case .http(let status): "The AI provider couldn’t complete the request (HTTP \(status)). Please try again."
            }
        }
    }
}
