import Foundation
import PDFKit
import CoreFoundation
import zlib

/// Check every hop before URLSession sends an article request to a new address.
final class ArticleImportRedirectPolicy: NSObject, URLSessionTaskDelegate {
    static func accepts(_ url: URL?) -> Bool {
        guard let url, url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil else { return false }
        return true
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(Self.accepts(request.url) ? request : nil)
    }
}

/// A complete, user supplied article ready for the reading classroom.
struct ImportedArticle: Equatable, Sendable {
    let title: String
    let text: String
    let source: String
}

@MainActor
final class ArticleImporter {
    static let maximumTextUTF16Length = 40_000
    static let recommendedMaximumBytes = 10 * 1_024 * 1_024

    enum ImportError: LocalizedError, Equatable {
        case unsupportedURL
        case unsupportedContentType
        case requestFailed
        case responseTooLarge
        case badResponse
        case unreadablePDF
        case unreadableDocument
        case invalidArchive
        case unsupportedArchive
        case scannedPDF(page: Int)
        case emptyArticle
        case articleTooLong
        case webpageNeedsPastedText

        /// The UI can use this stable key to provide its three translations.
        var localizationKey: String {
            switch self {
            case .unsupportedURL: return "article_import_error_unsupported_url"
            case .unsupportedContentType: return "article_import_error_unsupported_content_type"
            case .requestFailed: return "article_import_error_request_failed"
            case .responseTooLarge: return "article_import_error_response_too_large"
            case .badResponse: return "article_import_error_bad_response"
            case .unreadablePDF: return "article_import_error_unreadable_pdf"
            case .unreadableDocument: return "article_import_error_unreadable_document"
            case .invalidArchive: return "article_import_error_invalid_archive"
            case .unsupportedArchive: return "article_import_error_unsupported_archive"
            case .scannedPDF: return "article_import_error_scanned_pdf"
            case .emptyArticle: return "article_import_error_empty_article"
            case .articleTooLong: return "article_import_error_article_too_long"
            case .webpageNeedsPastedText: return "article_import_error_webpage_needs_pasted_text"
            }
        }

        var errorDescription: String? { localizationKey }
    }

    static func importPDF(from url: URL) async throws -> ImportedArticle {
        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed { url.stopAccessingSecurityScopedResource() }
        }

        guard url.isFileURL else { throw ImportError.unsupportedURL }
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        if values?.fileSize == nil { throw ImportError.unreadablePDF }
        if let size = values?.fileSize, size > recommendedMaximumBytes {
            throw ImportError.responseTooLarge
        }
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else {
            throw ImportError.unreadablePDF
        }
        return try importPDFData(data, url: url, source: url.lastPathComponent)
    }

    static func importFile(from url: URL) async throws -> ImportedArticle {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        guard url.isFileURL else { throw ImportError.unsupportedURL }
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize),
              size <= recommendedMaximumBytes else { throw ImportError.responseTooLarge }
        let ext = url.pathExtension.lowercased()
        if ext == "pdf" { return try await importPDF(from: url) }
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { throw ImportError.requestFailed }
        let source = url.lastPathComponent
        switch ext {
        case "txt", "text", "md", "markdown":
            return try importedText(decodeText(data), title: url.deletingPathExtension().lastPathComponent, source: source)
        case "rtf":
            guard let attributed = try? NSAttributedString(data: data, options: [.documentType: NSAttributedString.DocumentType.rtf], documentAttributes: nil) else {
                throw ImportError.unreadableDocument
            }
            return try importedText(attributed.string, title: url.deletingPathExtension().lastPathComponent, source: source)
        case "html", "htm":
            guard let html = decodeText(data) else { throw ImportError.webpageNeedsPastedText }
            return try importHTML(html, source: source)
        case "docx":
            return try importDOCX(data, source: source)
        case "epub":
            return try importEPUB(data, source: source)
        default:
            throw ImportError.unsupportedContentType
        }
    }

    private static func importPDFData(_ data: Data, url: URL, source: String) throws -> ImportedArticle {
        guard data.count <= recommendedMaximumBytes else { throw ImportError.responseTooLarge }
        guard let document = PDFDocument(data: data) else {
            throw ImportError.unreadablePDF
        }
        guard !document.isLocked else { throw ImportError.unreadablePDF }
        var pages: [String] = []
        var characterCount = 0
        pages.reserveCapacity(document.pageCount)
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index),
                  let value = page.string?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !value.isEmpty else {
                throw ImportError.scannedPDF(page: index + 1)
            }
            pages.append(value)
            characterCount += value.utf16.count
            if characterCount > maximumTextUTF16Length { throw ImportError.articleTooLong }
        }
        let text = pages.joined(separator: "\n\n")
        let normalized = try normalize(text)
        let title = document.documentAttributes?[PDFDocumentAttribute.titleAttribute] as? String
            ?? url.deletingPathExtension().lastPathComponent
        return ImportedArticle(title: title.trimmingCharacters(in: .whitespacesAndNewlines), text: normalized, source: source)
    }

    static func importURL(_ input: String) async throws -> ImportedArticle {
        guard let url = URL(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              ArticleImportRedirectPolicy.accepts(url) else { throw ImportError.unsupportedURL }

        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("text/html, application/xhtml+xml, application/pdf;q=0.9", forHTTPHeaderField: "Accept")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 20
        let session = URLSession(configuration: configuration, delegate: ArticleImportRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var data = Data()
        let response: URLResponse
        do {
            let (bytes, receivedResponse) = try await session.bytes(for: request)
            response = receivedResponse
            for try await byte in bytes {
                data.append(byte)
                if data.count > recommendedMaximumBytes { throw ImportError.responseTooLarge }
            }
        } catch {
            if let error = error as? ImportError { throw error }
            throw ImportError.requestFailed
        }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw ImportError.badResponse
        }
        guard data.count <= recommendedMaximumBytes else { throw ImportError.responseTooLarge }
        guard let finalURL = response.url,
              ArticleImportRedirectPolicy.accepts(finalURL) else {
            throw ImportError.unsupportedURL
        }
        let mime = (http.mimeType ?? "").lowercased()
        if mime == "application/pdf" || finalURL.pathExtension.lowercased() == "pdf" {
            return try importPDFData(data, url: finalURL, source: finalURL.absoluteString)
        }
        guard mime == "text/html" || mime == "application/xhtml+xml" || mime == "text/plain" || mime == "text/markdown" || mime.isEmpty else {
            throw ImportError.unsupportedContentType
        }
        guard let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            throw ImportError.webpageNeedsPastedText
        }
        if mime == "text/plain" || mime == "text/markdown" {
            return try importedText(html, title: finalURL.deletingPathExtension().lastPathComponent, source: finalURL.absoluteString)
        }
        return try importHTML(html, source: finalURL.absoluteString)
    }

    private static func importedText(_ value: String?, title: String, source: String) throws -> ImportedArticle {
        guard let value else { throw ImportError.emptyArticle }
        return ImportedArticle(title: title, text: try normalize(value), source: source)
    }

    private static func decodeText(_ data: Data) -> String? {
        if data.starts(with: [0xEF, 0xBB, 0xBF]) { return String(data: data.dropFirst(3), encoding: .utf8) }
        if data.starts(with: [0xFF, 0xFE]) { return String(data: data.dropFirst(2), encoding: .utf16LittleEndian) }
        if data.starts(with: [0xFE, 0xFF]) { return String(data: data.dropFirst(2), encoding: .utf16BigEndian) }
        if let utf8 = String(data: data, encoding: .utf8), !utf8.contains("\u{FFFD}") { return utf8 }
        let gb18030 = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        return String(data: data, encoding: gb18030)
    }

    private static func importDOCX(_ data: Data, source: String) throws -> ImportedArticle {
        let zip = try ZIPReader(data: data)
        guard let xml = zip.text(at: "word/document.xml") else { throw ImportError.invalidArchive }
        let paragraphs = try XMLTextExtractor.paragraphs(Data(xml.utf8), docx: true)
        return try importedText(paragraphs.joined(separator: "\n\n"), title: (source as NSString).deletingPathExtension, source: source)
    }

    private static func importEPUB(_ data: Data, source: String) throws -> ImportedArticle {
        let zip = try ZIPReader(data: data)
        guard let container = zip.text(at: "META-INF/container.xml"),
              let root = firstCapture(#"(?is)<rootfile\b[^>]*full-path\s*=\s*[\"']([^\"']+)[\"']"#, in: container),
              let opf = zip.text(at: root) else { throw ImportError.invalidArchive }
        let base = (root as NSString).deletingLastPathComponent
        let opfInfo = try XMLTextExtractor.epubInfo(Data(opf.utf8))
        let hrefs = opfInfo.manifest.reduce(into: [String: String]()) { $0[$1.id] = joinArchivePath(base, $1.href) }
        let ids = opfInfo.spine
        var parts: [String] = []
        var title = firstCapture(#"(?is)<dc:title\b[^>]*>(.*?)</dc:title>"#, in: opf).map { stripTags($0).htmlDecoded } ?? (source as NSString).deletingPathExtension
        for id in ids {
            guard let path = hrefs[id], let xhtml = zip.text(at: path) else { throw ImportError.invalidArchive }
            let chapter = try XMLTextExtractor.xhtmlText(Data(xhtml.utf8))
            if !chapter.isEmpty { parts.append(chapter) }
            if title.isEmpty, let chapterTitle = firstCapture(#"(?is)<title[^>]*>(.*?)</title>"#, in: xhtml) { title = stripTags(chapterTitle).htmlDecoded }
        }
        return try importedText(parts.joined(separator: "\n\n"), title: title, source: source)
    }

    private static func allCaptures(_ pattern: String, in string: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: string, range: NSRange(string.startIndex..., in: string)).compactMap { match in
            guard let range = Range(match.range(at: 1), in: string) else { return nil }; return String(string[range])
        }
    }

    private static func allAttributePairs(_ pattern: String, in string: String) -> [(String, String)] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: string, range: NSRange(string.startIndex..., in: string)).compactMap { match in
            guard let a = Range(match.range(at: 1), in: string), let b = Range(match.range(at: 2), in: string) else { return nil }
            return (String(string[a]), String(string[b]).removingPercentEncoding ?? String(string[b]))
        }
    }

    private static func joinArchivePath(_ base: String, _ child: String) -> String {
        let raw = ([base, child.removingPercentEncoding ?? child].filter { !$0.isEmpty }.joined(separator: "/"))
        return raw.split(separator: "/").reduce(into: [String]()) { result, part in
            if part == "." { return }; if part == ".." { if !result.isEmpty { result.removeLast() } } else { result.append(String(part)) }
        }.joined(separator: "/")
    }

    private static func importHTML(_ html: String, source: String) throws -> ImportedArticle {
        let title = firstCapture(#"(?is)<title[^>]*>\s*(.*?)\s*</title>"#, in: html).map { stripTags($0).htmlDecoded } ?? ""
        let candidate = elementBody(named: "article", in: html)
            ?? elementBody(named: "main", in: html)
            ?? html
        let withoutNoise = candidate.replacingOccurrences(of: #"(?is)<(head|script|style|noscript|nav|header|footer|aside|form)\b[^>]*>.*?</\1>"#, with: "", options: .regularExpression)
        let withParagraphBreaks = withoutNoise.replacingOccurrences(of: #"(?i)</(p|div|h[1-6]|li|br|tr)>"#, with: "\n", options: .regularExpression)
        let text = try normalize(stripTags(withParagraphBreaks).htmlDecoded)
        let lower = html.lowercased()
        let hasLoginForm = lower.contains("type=\"password\"") || lower.contains("type='password'")
            || ["/login", "sign in", "signin", "log in", "captcha", "challenge"].contains(where: { lower.contains($0) })
        if hasLoginForm && candidate == html { throw ImportError.webpageNeedsPastedText }
        guard !text.isEmpty else { throw ImportError.webpageNeedsPastedText }
        let fallbackTitle = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? source : title.trimmingCharacters(in: .whitespacesAndNewlines)
        return ImportedArticle(title: fallbackTitle, text: text, source: source)
    }

    private static func firstCapture(_ pattern: String, in string: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: string, range: NSRange(string.startIndex..., in: string)),
              match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: string) else { return nil }
        return String(string[range])
    }

    private static func longestCapture(_ pattern: String, in string: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(string.startIndex..., in: string)
        return regex.matches(in: string, range: range).compactMap { match in
            guard let capture = Range(match.range(at: 1), in: string) else { return nil }
            return String(string[capture])
        }.max { $0.utf16.count < $1.utf16.count }
    }

    private static func elementBody(named name: String, in html: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: "(?is)<(/?)\\s*" + name + "\\b[^>]*>") else { return nil }
        let range = NSRange(html.startIndex..., in: html)
        let matches = regex.matches(in: html, range: range)
        guard let opening = matches.first(where: { $0.range(at: 1).length == 0 }),
              let start = Range(opening.range, in: html)?.upperBound else { return nil }
        var depth = 1
        for match in matches where match.range.location > opening.range.location {
            if match.range(at: 1).length == 0 { depth += 1 } else { depth -= 1 }
            if depth == 0, let end = Range(match.range, in: html)?.lowerBound {
                return String(html[start..<end])
            }
        }
        return nil
    }

    private static func stripTags(_ value: String) -> String {
        let separated = value.replacingOccurrences(of: #"(?i)<\s*(br|p|div|h[1-6]|li|tr|blockquote)\b[^>]*>"#, with: "\n", options: .regularExpression)
        return separated.replacingOccurrences(of: #"(?is)<[^>]+>"#, with: "", options: .regularExpression)
    }

    private static func normalize(_ value: String) throws -> String {
        let compact = value.replacingOccurrences(of: "\u{00A0}", with: " ")
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .joined(separator: "\n")
        let text = compact.replacingOccurrences(of: #"\n{3,}"#, with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw ImportError.emptyArticle }
        guard text.utf16.count <= maximumTextUTF16Length else { throw ImportError.articleTooLong }
        return text
    }
}

private extension String {
    var htmlDecoded: String {
        var result = self
        let entities = ["&nbsp;": " ", "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&#39;": "'"]
        for (entity, value) in entities { result = result.replacingOccurrences(of: entity, with: value) }
        if let regex = try? NSRegularExpression(pattern: "&#(x[0-9a-fA-F]+|[0-9]+);") {
            let matches = regex.matches(in: result, range: NSRange(result.startIndex..., in: result)).reversed()
            for match in matches {
                guard let range = Range(match.range, in: result), let valueRange = Range(match.range(at: 1), in: result) else { continue }
                let raw = String(result[valueRange])
                let scalar = raw.lowercased().hasPrefix("x") ? UInt32(raw.dropFirst(), radix: 16) : UInt32(raw)
                if let scalar, let character = UnicodeScalar(scalar) { result.replaceSubrange(range, with: String(character)) }
            }
        }
        return result
    }
}

private struct ZIPReader {
    private struct Entry { let method: UInt16; let compressed: Int; let uncompressed: Int; let offset: Int; let crc: UInt32 }
    private let data: Data
    private var entries: [String: Entry] = [:]
    private static let limit = 10 * 1_024 * 1_024

    init(data: Data) throws {
        self.data = data
        guard data.count >= 22, data.count <= Self.limit else { throw ArticleImporter.ImportError.responseTooLarge }
        guard let eocd = data.lastIndex(of: 0x50) else { throw ArticleImporter.ImportError.invalidArchive }
        var end = -1
        for i in stride(from: data.count - 4, through: max(0, eocd - 65_535), by: -1) where data[i] == 0x50 && data[i+1] == 0x4b && data[i+2] == 0x05 && data[i+3] == 0x06 { end = i; break }
        guard end >= 0, end + 22 <= data.count, end + 22 + Int(read16(data, end + 20)) == data.count, read16(data, end + 4) == 0, read16(data, end + 6) == 0, read16(data, end + 8) == read16(data, end + 10) else { throw ArticleImporter.ImportError.invalidArchive }
        let count = Int(read16(data, end + 10)); let directorySize = Int(read32(data, end + 12)); let directoryOffset = Int(read32(data, end + 16))
        guard count <= 1_000, directorySize >= 0, directoryOffset >= 0, directoryOffset + directorySize == end else { throw ArticleImporter.ImportError.unsupportedArchive }
        var cursor = directoryOffset; var total = 0; var seen = Set<String>()
        for _ in 0..<count {
            guard cursor + 46 <= end, read32(data, cursor) == 0x02014b50 else { throw ArticleImporter.ImportError.invalidArchive }
            let flags = read16(data, cursor + 8); let method = read16(data, cursor + 10); let compressed = Int(read32(data, cursor + 20)); let uncompressed = Int(read32(data, cursor + 24)); let nameLength = Int(read16(data, cursor + 28)); let extraLength = Int(read16(data, cursor + 30)); let commentLength = Int(read16(data, cursor + 32)); let offset = Int(read32(data, cursor + 42))
            guard compressed != Int(UInt32.max), uncompressed != Int(UInt32.max), offset != Int(UInt32.max), (flags & 1) == 0, compressed >= 0, uncompressed >= 0, uncompressed <= Self.limit, total <= Self.limit - uncompressed, cursor + 46 + nameLength + extraLength + commentLength <= end else { throw ArticleImporter.ImportError.unsupportedArchive }
            let nameData = data.subdata(in: cursor + 46..<cursor + 46 + nameLength); guard let name = String(data: nameData, encoding: .utf8), !name.isEmpty, !name.hasPrefix("/"), !name.split(separator: "/").contains("..") else { throw ArticleImporter.ImportError.invalidArchive }
            guard seen.insert(name).inserted else { throw ArticleImporter.ImportError.invalidArchive }
            entries[name] = Entry(method: method, compressed: compressed, uncompressed: uncompressed, offset: offset, crc: read32(data, cursor + 16)); total += uncompressed; cursor += 46 + nameLength + extraLength + commentLength
        }
        guard cursor == end else { throw ArticleImporter.ImportError.invalidArchive }
    }

    func text(at path: String) -> String? { guard let bytes = try? bytes(at: path) else { return nil }; return String(data: bytes, encoding: .utf8) }

    private func bytes(at path: String) throws -> Data {
        guard let entry = entries[path], entry.offset >= 0, entry.offset <= data.count - 30, read32(data, entry.offset) == 0x04034b50 else { throw ArticleImporter.ImportError.invalidArchive }
        let nameLength = Int(read16(data, entry.offset + 26)); let extraLength = Int(read16(data, entry.offset + 28)); let start = entry.offset + 30 + nameLength + extraLength; guard start >= 0, start <= data.count, entry.compressed <= data.count - start else { throw ArticleImporter.ImportError.invalidArchive }
        let compressed = data.subdata(in: start..<start + entry.compressed)
        if entry.method == 0 { guard entry.compressed == entry.uncompressed, crc(compressed) == entry.crc else { throw ArticleImporter.ImportError.invalidArchive }; return compressed }
        guard entry.method == 8 else { throw ArticleImporter.ImportError.unsupportedArchive }
        var stream = z_stream(); var output = Data(count: entry.uncompressed); let outputCount = output.count
        let result: Int32 = compressed.withUnsafeBytes { input in output.withUnsafeMutableBytes { out in
            stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress); stream.avail_in = uInt(compressed.count); stream.next_out = out.bindMemory(to: Bytef.self).baseAddress; stream.avail_out = uInt(outputCount); guard inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return Z_DATA_ERROR }; defer { inflateEnd(&stream) }; return inflate(&stream, Z_FINISH)
        }}
        guard result == Z_STREAM_END, Int(stream.total_out) == entry.uncompressed, Int(stream.total_in) == entry.compressed, crc(output) == entry.crc else { throw ArticleImporter.ImportError.invalidArchive }
        return output
    }
    private func crc(_ data: Data) -> UInt32 {
        data.withUnsafeBytes { raw in
            let pointer = raw.bindMemory(to: Bytef.self).baseAddress
            return UInt32(zlib.crc32(0, pointer, uInt(data.count)))
        }
    }
    private func read16(_ data: Data, _ index: Int) -> UInt16 { data[index..<index+2].enumerated().reduce(0) { $0 | UInt16($1.element) << UInt16($1.offset * 8) } }
    private func read32(_ data: Data, _ index: Int) -> UInt32 { data[index..<index+4].enumerated().reduce(0) { $0 | UInt32($1.element) << UInt32($1.offset * 8) } }
}

private final class XMLTextExtractor: NSObject, XMLParserDelegate {
    private var paragraphs: [String] = []; private var current = ""; private var inText = false
    private var skipDepth = 0
    private var insideBody = false
    private var manifest: [(id: String, href: String)] = []; private var spine: [String] = []; private var title = ""
    private var mode = 0
    static func paragraphs(_ data: Data, docx: Bool) throws -> [String] { let d = XMLTextExtractor(); d.mode = 1; let p = XMLParser(data: data); p.delegate = d; p.shouldResolveExternalEntities = false; guard p.parse() else { throw ArticleImporter.ImportError.invalidArchive }; return d.paragraphs }
    static func epubInfo(_ data: Data) throws -> (manifest: [(id: String, href: String)], spine: [String]) { let d = XMLTextExtractor(); d.mode = 2; let p = XMLParser(data: data); p.delegate = d; p.shouldResolveExternalEntities = false; guard p.parse() else { throw ArticleImporter.ImportError.invalidArchive }; return (d.manifest,d.spine) }
    static func xhtmlText(_ data: Data) throws -> String { let d = XMLTextExtractor(); d.mode = 3; let p = XMLParser(data: data); p.delegate = d; p.shouldResolveExternalEntities = false; guard p.parse() else { throw ArticleImporter.ImportError.invalidArchive }; return d.paragraphs.joined(separator: "\n\n") }
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String : String] = [:]) {
        let n = (qName ?? elementName).split(separator: ":").last.map(String.init) ?? elementName
        if mode == 1 { if n == "p" { current = "" }; if n == "t" { inText = true } ; if n == "tab" { current += "\t" }; if n == "br" { current += "\n" } }
        if mode == 2 { if n == "item", let id=attributes["id"], let href=attributes["href"] { manifest.append((id,href)) }; if n == "itemref", let id=attributes["idref"] { spine.append(id) } }
        if mode == 3 {
            if n == "body" { insideBody = true }
            if ["script","style","nav","head","noscript"].contains(n) { skipDepth += 1 }
            if insideBody && skipDepth == 0 && ["p","div","br","h1","h2","h3","h4","h5","h6","li","blockquote"].contains(n) && !current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { paragraphs.append(current.trimmingCharacters(in: .whitespacesAndNewlines)); current = "" }
            if insideBody && skipDepth == 0 && n == "br" { current += "\n" }
        }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) { if mode == 1 && inText { current += string }; if mode == 3 && insideBody && skipDepth == 0 { current += string } }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let n = (qName ?? elementName).split(separator: ":").last.map(String.init) ?? elementName
        if mode == 3 && ["script","style","nav","head","noscript"].contains(n) { skipDepth = max(0, skipDepth - 1); return }
        if mode == 1 { if n == "t" { inText = false }; if n == "p", !current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { paragraphs.append(current.trimmingCharacters(in: .whitespacesAndNewlines)); current = "" } }
        if mode == 3 && insideBody && skipDepth == 0 && ["body","p","div","h1","h2","h3","h4","h5","h6","li","blockquote"].contains(n), !current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { paragraphs.append(current.trimmingCharacters(in: .whitespacesAndNewlines)); current = "" }
        if mode == 3 && n == "body" { insideBody = false }
    }
}
