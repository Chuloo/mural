import Foundation

/// macOS regression harness using the actual importer and redirect delegate; no requests are sent.
@main struct ArticleImportURLTests {
    @MainActor static func main() async throws {
        let source = URL(string: "https://origin.example.test/article")!
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: source) // Deliberately never resumed.
        let response = HTTPURLResponse(url: source, statusCode: 302, httpVersion: nil, headerFields: nil)!
        let delegate = ArticleImportRedirectPolicy()
        let cases = [
            ("https://publisher.example.test/article", true),
            ("https://publisher.example.test/a?lang=vi", true),
            ("http://publisher.example.test/article", false),
            ("https://user:password@publisher.example.test/article", false),
            ("file:///tmp/article.txt", false),
            ("javascript:alert(1)", false),
            ("/relative/article", false)
        ]
        for (address, allowed) in cases {
            let request = URLRequest(url: URL(string: address)!)
            var called = false
            delegate.urlSession(session, task: task, willPerformHTTPRedirection: response, newRequest: request) {
                called = true
                precondition(($0 != nil) == allowed, "Unexpected redirect decision")
            }
            precondition(called, "Redirect decision was not returned")
        }
        for address in ["http://publisher.example.test/article", "file:///tmp/article.txt"] {
            do {
                _ = try await ArticleImporter.importURL(address)
                preconditionFailure("Unsafe initial address was accepted")
            } catch ArticleImporter.ImportError.unsupportedURL {
                // Rejected before creating a network request.
            }
        }
        print("PASS: 7 redirect cases and 2 initial-address rejections; no network requests")
    }
}
