import XCTest
@testable import MuralCore

@MainActor final class MeaningTests: XCTestCase {
    @MainActor private final class Translator {
        var requests: [MeaningRequest] = []
        var pending: [CheckedContinuation<MeaningResult, Error>] = []
        func translate(_ request: MeaningRequest) async throws -> MeaningResult {
            requests.append(request)
            // Intentionally ignores cancellation to exercise late network responses.
            return try await withCheckedThrowingContinuation { pending.append($0) }
        }
        func succeed(_ text: String) { pending.removeFirst().resume(returning: MeaningResult(text: text)) }
        func fail() { pending.removeFirst().resume(throwing: URLError(.notConnectedToInternet)) }
    }
    private let sessionID = UUID()
    private func request(_ text: String, revision: Int = 0, language: String = "English", passageID: String = "p") -> MeaningRequest {
        var fragment = Fragment(id: passageID, speaker: .assistant, text: text, startMS: 0, endMS: 1000)
        fragment.revision = revision
        let passage = Passage(id: passageID, speaker: .assistant, fragments: [fragment])
        return MeaningRequest(sessionID: sessionID, passage: passage, learningLanguageID: "nb", meaningLanguage: language)
    }
    private func waitUntil(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        let deadline = Date().addingTimeInterval(2)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition(), file: file, line: line)
    }

    func testGrowingSpeechCoalescesWithoutCancellingTheRunningTranslation() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, translate: translator.translate)
        controller.update(request("Hei"))
        await waitUntil { translator.requests.count == 1 }
        controller.update(request("Hei,", revision: 1))
        controller.update(request("Hei, jeg", revision: 2))
        controller.update(request("Hei, jeg liker kaffe.", revision: 3))
        XCTAssertEqual(translator.requests.count, 1)
        translator.succeed("Hi")
        await waitUntil { translator.requests.count == 2 }
        XCTAssertEqual(controller.text, "Hi")
        XCTAssertTrue(controller.isLoading)
        XCTAssertEqual(translator.requests[1].text, "Hei, jeg liker kaffe.")
        translator.succeed("Hi, I like coffee.")
        await waitUntil { !controller.isLoading }
        XCTAssertEqual(controller.text, "Hi, I like coffee.")
        XCTAssertNil(controller.error)
    }

    func testContinuousFragmentsDoNotKeepRestartingTheDelay() async {
        let translator = Translator()
        let controller = MeaningController(delay: .milliseconds(30), translate: translator.translate)
        for revision in 0..<12 {
            controller.update(request(String(repeating: "hei ", count: revision + 1), revision: revision))
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(translator.requests.count, 1)
        XCTAssertLessThan(translator.requests.first?.text.count ?? 1000, 48)
        controller.reset()
        if !translator.pending.isEmpty { translator.succeed("Hello") }
    }

    func testHidingMeaningRejectsLateResultsAndCanShowACachedTranslation() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, translate: translator.translate)
        var saved = 0
        controller.onResult = { _, _ in saved += 1 }
        controller.update(request("Hei"))
        await waitUntil { translator.requests.count == 1 }
        controller.reset()
        controller.update(request("Hei"), cached: "Hi")
        translator.fail()
        try? await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(controller.text, "Hi")
        XCTAssertNil(controller.error)
        XCTAssertFalse(controller.isLoading)
        XCTAssertEqual(saved, 0)
        XCTAssertEqual(translator.requests.count, 1)
    }

    func testNewPassageRejectsThePreviousPassagesResponse() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, translate: translator.translate)
        controller.update(request("Hei"))
        await waitUntil { translator.requests.count == 1 }
        controller.update(request("Ha det", passageID: "next"))
        await waitUntil { translator.requests.count == 2 }
        translator.succeed("Hi")
        try? await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(controller.text, "")
        XCTAssertTrue(controller.isLoading)
        translator.succeed("Goodbye")
        await waitUntil { !controller.isLoading }
        XCTAssertEqual(controller.text, "Goodbye")
    }

    func testCorrectedTranscriptNeverDisplaysMeaningOfTheOldWords() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, translate: translator.translate)
        controller.update(request("Jeg liker kaffe."))
        await waitUntil { translator.requests.count == 1 }
        controller.update(request("Jeg liker te.", revision: 1))
        translator.succeed("I like coffee.")
        await waitUntil { translator.requests.count == 2 }
        XCTAssertEqual(controller.text, "")
        translator.succeed("I like tea.")
        await waitUntil { !controller.isLoading }
        XCTAssertEqual(controller.text, "I like tea.")
    }

    func testFailureIsVisibleAndRetriesOnlyWhenRequested() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, translate: translator.translate)
        controller.update(request("Hei"))
        await waitUntil { translator.requests.count == 1 }
        translator.fail()
        await waitUntil { controller.error != nil }
        controller.update(request("Hei!", revision: 1))
        XCTAssertEqual(translator.requests.count, 1)
        XCTAssertFalse(controller.isLoading)
        controller.retry()
        await waitUntil { translator.requests.count == 2 }
        XCTAssertNil(controller.error)
        XCTAssertEqual(translator.requests[1].text, "Hei!")
        translator.succeed("Hi!")
        await waitUntil { !controller.isLoading }
        XCTAssertEqual(controller.text, "Hi!")
    }

    func testSwitchingBetweenTagalogAndSpanishDiscardsLateMeaningsAndErrors() async {
        for (oldID, newID) in [("tl", "es"), ("es", "tl")] {
            for fails in [false, true] {
                let translator = Translator()
                let controller = MeaningController(delay: .zero, translate: translator.translate)
                let passage = Passage(id: "same-passage", speaker: .assistant, fragments: [
                    Fragment(speaker: .assistant, text: "Kumusta!", startMS: 0, endMS: 1000)
                ])
                func request(_ id: String) -> MeaningRequest {
                    MeaningRequest(sessionID: sessionID, passage: passage, learningLanguageID: id, meaningLanguage: "English")
                }
                var saved: [String] = []
                controller.onResult = { request, _ in saved.append(request.learningLanguageID) }
                controller.update(request(oldID))
                await waitUntil { translator.pending.count == 1 }
                controller.update(request(newID))
                await waitUntil { translator.pending.count == 2 }
                if fails { translator.fail() } else { translator.succeed("Old meaning") }
                try? await Task.sleep(for: .milliseconds(10))
                XCTAssertEqual(controller.text, "")
                XCTAssertNil(controller.error)
                XCTAssertTrue(controller.isLoading)
                XCTAssertTrue(saved.isEmpty)
                translator.succeed("New meaning")
                await waitUntil { !controller.isLoading }
                XCTAssertEqual(controller.text, "New meaning")
                XCTAssertEqual(saved, [newID])
            }
        }
    }

    func testChangingMeaningLanguageClearsOldTextAndUsesSeparateCacheKeys() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, translate: translator.translate)
        let english = request("Hei")
        let french = request("Hei", language: "French")
        XCTAssertNotEqual(english.cacheKey, french.cacheKey)
        controller.update(english, cached: "Hi")
        controller.update(french)
        XCTAssertEqual(controller.text, "")
        await waitUntil { translator.requests.count == 1 }
        XCTAssertEqual(translator.requests[0].meaningLanguage, "French")
        translator.succeed("Salut")
        await waitUntil { !controller.isLoading }
        XCTAssertEqual(controller.text, "Salut")
    }
}
