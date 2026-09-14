import Foundation
import MuralCore
import OSLog

final class NoRedirect: NSObject, URLSessionTaskDelegate {
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Mural", category: "NetworkTiming")
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        let path = task.originalRequest?.url?.path ?? ""
        let route = path.hasSuffix("/responses") ? "response" : path.hasSuffix("/live/sessions") ? "live_create" : "control"
        func milliseconds(_ start: Date?, _ end: Date?) -> Int {
            guard let start, let end else { return -1 }
            return max(0, Int(end.timeIntervalSince(start) * 1000))
        }
        for transaction in metrics.transactionMetrics {
            let dns = milliseconds(transaction.domainLookupStartDate, transaction.domainLookupEndDate)
            let connect = milliseconds(transaction.connectStartDate, transaction.connectEndDate)
            let tls = milliseconds(transaction.secureConnectionStartDate, transaction.secureConnectionEndDate)
            let upload = milliseconds(transaction.requestStartDate, transaction.requestEndDate)
            let wait = milliseconds(transaction.requestEndDate, transaction.responseStartDate)
            let download = milliseconds(transaction.responseStartDate, transaction.responseEndDate)
            logger.info("network_route=\(route, privacy: .public) total_ms=\(Int(metrics.taskInterval.duration * 1000)) dns_ms=\(dns) connect_ms=\(connect) tls_ms=\(tls) upload_ms=\(upload) wait_ms=\(wait) download_ms=\(download) proxy=\(transaction.isProxyConnection) reused=\(transaction.isReusedConnection)")
        }
    }
}

struct APIUsage { var input = 0; var output = 0; var searches = 0 }
struct APIResult { var text: String; var sources: [SourceLink]; var usage: APIUsage }

@MainActor final class APIClient {
    private let session: URLSession
    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 60
        config.httpCookieStorage = nil; config.urlCache = nil
        session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    func post(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        if let connection = try SubscriptionStore.connectionForRequest() {
            return try await subscriptionRequest(connection, path: path, method: "POST", body: body)
        }
        guard path == "responses" || path == "live/sessions" else { throw APIError.invalidResponse }
        guard let key = CredentialStore.read() else { throw APIError.missingKey }
        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/" + path)!)
        request.httpMethod = "POST"; request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw APIError.http(http.statusCode) }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw APIError.invalidResponse }
        return json
    }
    func subscriptionRequest(_ connection: SubscriptionConnection, path: String, method: String,
                             body: [String: Any]? = nil, after: Int? = nil) async throws -> [String: Any] {
        var request = URLRequest(url: try connection.endpoint(path, after: after))
        request.httpMethod = method
        request.setValue("Bearer " + connection.token, forHTTPHeaderField: "Authorization")
        if let body { request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw APIError.subscription(http.statusCode) }
        guard data.count <= 2_000_000, let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw APIError.invalidResponse }
        return json
    }
    func respond(instructions: String, input: String, schema: [String: Any]? = nil, search: Bool = false) async throws -> APIResult {
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
        case missingKey, invalidResponse, incomplete, refused, http(Int), subscription(Int)
        var errorDescription: String? {
            switch self {
            case .missingKey: "Connect your ChatGPT subscription or add an OpenAI key in Settings to begin."
            case .invalidResponse, .incomplete: "OpenAI returned an incomplete response. Please try again."
            case .refused: "Mural couldn’t complete that request. Try a different topic."
            case .http(401): "Your OpenAI key wasn’t accepted. Check it in Settings."
            case .http(403), .http(404): "This API key may not have access to the requested model. Check your OpenAI project."
            case .http(429): "OpenAI’s usage or rate limit was reached. Check your project’s billing and limits."
            case .http(let status): L10n.format("OpenAI couldn’t complete the request (HTTP %d). Please try again.", status)
            case .subscription(401): "Your subscription connection has expired, or its service is not signed in to ChatGPT. Check Settings and your service."
            case .subscription(403): "Your subscription cannot use this feature, or the service rejected the request."
            case .subscription(422): "Your selected voice is unavailable. Choose an available voice in Settings."
            case .subscription(429): "Your subscription or connection service is at its limit. Please try later."
            case .subscription(let status): L10n.format("Your subscription service could not complete this request (HTTP %d). Your API key was not used.", status)
            }
        }
    }
}
