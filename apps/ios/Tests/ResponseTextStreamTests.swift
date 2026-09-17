import XCTest
@testable import MuralCore

final class ResponseTextStreamTests: XCTestCase {
    func testNetworkBytesPreserveBlankBoundariesCRLFAndSplitUnicode() throws {
        let source = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好 café 👋\"}\r\n\r\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
        var decoder = ResponseTextStream(), texts: [String] = [], completed = false
        for byte in source.utf8 {
            switch try decoder.consume(byte: byte) {
            case .text(let text): texts.append(text)
            case .completed: completed = true
            case nil: break
            }
        }
        XCTAssertEqual(texts, ["你好 café 👋"]); XCTAssertTrue(completed)
    }
    private func event(_ object: [String: Any], decoder: inout ResponseTextStream) throws -> ResponseTextStream.Update? {
        let json = String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
        XCTAssertNil(try decoder.consume("event: provider-event"))
        XCTAssertNil(try decoder.consume("data: " + json))
        return try decoder.consume("")
    }
    func testUnicodeDeltasAreVisibleBeforeCompletionAndUsageIsRetained() throws {
        var decoder = ResponseTextStream(), seen = ""
        for delta in ["Hygg", "elig! café ", "你好 👋"] {
            guard case .text(let text) = try event(["type": "response.output_text.delta", "delta": delta], decoder: &decoder) else { return XCTFail("Missing partial text") }
            seen += delta; XCTAssertEqual(text, seen)
        }
        guard case .completed(let response) = try event(["type": "response.completed", "response": ["status": "completed", "usage": ["output_tokens": 8]]], decoder: &decoder) else { return XCTFail("Missing completion") }
        XCTAssertEqual((response["usage"] as? [String: Int])?["output_tokens"], 8)
        XCTAssertNil(try decoder.consume("data: [DONE]"))
    }
    func testCommentsAndMultipleDataLinesRespectEventBoundary() throws {
        var decoder = ResponseTextStream()
        for line in [": heartbeat", "", "data: {\"type\":\"response.output_text.delta\",", "data: \"delta\":\"Bonjour\"}"] { XCTAssertNil(try decoder.consume(line)) }
        guard case .text(let value) = try decoder.consume("") else { return XCTFail("Missing event") }
        XCTAssertEqual(value, "Bonjour")
    }
    func testFailureRefusalMalformedAndOversizedEventsCannotComplete() throws {
        for type in ["response.failed", "response.incomplete", "error", "response.refusal.delta"] {
            var decoder = ResponseTextStream()
            XCTAssertThrowsError(try event(["type": type], decoder: &decoder))
        }
        for line in ["data: [DONE]", "data: not json", "data: {}"] {
            var decoder = ResponseTextStream(); _ = try decoder.consume(line)
            XCTAssertThrowsError(try decoder.consume(""))
        }
        var decoder = ResponseTextStream()
        XCTAssertThrowsError(try decoder.consume("data: " + String(repeating: "x", count: 65_536)))
        var incomplete = ResponseTextStream()
        XCTAssertThrowsError(try event(["type": "response.completed", "response": ["status": "incomplete"]], decoder: &incomplete))
    }
}
