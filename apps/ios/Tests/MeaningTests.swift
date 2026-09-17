import XCTest
@testable import MuralCore

@MainActor final class MeaningTests: XCTestCase {
    @MainActor private final class Translator {
        var requests: [MeaningRequest] = []
        var pending: [CheckedContinuation<MeaningResult, Error>] = []
        var partials: [@MainActor (String) -> Void] = []
        func stream(_ request: MeaningRequest, onText: @escaping @MainActor (String) -> Void) async throws -> MeaningResult {
            partials.append(onText)
            return try await translate(request)
        }
        func translate(_ request: MeaningRequest) async throws -> MeaningResult {
            requests.append(request)
            // Intentionally ignores cancellation to exercise late network responses.
            return try await withCheckedThrowingContinuation { pending.append($0) }
        }
        func succeed(_ text: String) { pending.removeFirst().resume(returning: MeaningResult(text: text)) }
        func fail() { pending.removeFirst().resume(throwing: URLError(.notConnectedToInternet)) }
    }
    private let sessionID = UUID()
    func testPartialMeaningAppearsEarlyButOnlyCompletionIsSaved() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, streaming: translator.stream)
        var saved = 0; controller.onResult = { _, _ in saved += 1 }
        controller.update(request("Hei, verden."))
        await waitUntil { translator.partials.count == 1 }
        translator.partials[0]("Hello")
        XCTAssertEqual(controller.text, "Hello"); XCTAssertTrue(controller.isLoading); XCTAssertEqual(saved, 0)
        translator.succeed("Hello, world.")
        await waitUntil { !controller.isLoading }
        translator.partials[0]("Late partial")
        XCTAssertEqual(controller.text, "Hello, world."); XCTAssertEqual(saved, 1)
    }
    func testGrowingMeaningDoesNotFlashBackToItsFirstWord() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, streaming: translator.stream)
        controller.update(request("Hei, jeg liker"))
        await waitUntil { translator.partials.count == 1 }
        translator.succeed("Hello, I like")
        await waitUntil { !controller.isLoading }
        controller.update(request("Hei, jeg liker fisk.", revision: 1))
        await waitUntil { translator.partials.count == 2 }
        translator.partials[1]("Hello")
        XCTAssertEqual(controller.text, "Hello, I like")
        translator.partials[1]("Hello, I like fish")
        XCTAssertEqual(controller.text, "Hello, I like fish")
        translator.succeed("Hi, I like fish.")
        await waitUntil { !controller.isLoading }
        XCTAssertEqual(controller.text, "Hi, I like fish.")
    }
    func testCorrectionAndResetRejectLatePartialText() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, streaming: translator.stream)
        controller.update(request("Jeg liker kaffe."))
        await waitUntil { translator.partials.count == 1 }
        translator.partials[0]("I like coffee")
        controller.update(request("Jeg liker te.", revision: 1))
        translator.partials[0]("I like coffee.")
        XCTAssertEqual(controller.text, "")
        translator.succeed("I like coffee.")
        await waitUntil { translator.partials.count == 2 }
        translator.partials[1]("I like tea")
        controller.reset(); translator.partials[1]("Late tea")
        translator.succeed("I like tea.")
        try? await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(controller.text, ""); XCTAssertFalse(controller.isLoading)
    }
    func testFailedStreamClearsPartialAndDoesNotCacheOrRetry() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, streaming: translator.stream)
        var saved = 0; controller.onResult = { _, _ in saved += 1 }
        controller.update(request("Hei")); await waitUntil { translator.partials.count == 1 }
        translator.partials[0]("Hi"); translator.fail()
        await waitUntil { controller.error != nil }
        translator.partials[0]("Late text")
        XCTAssertEqual(controller.text, ""); XCTAssertEqual(saved, 0); XCTAssertEqual(translator.requests.count, 1)
    }
    func testSlowRequestDoesNotAddAnotherFullSchedulingDelay() async {
        let translator = Translator()
        let controller = MeaningController(delay: .milliseconds(200), streaming: translator.stream)
        controller.update(request("Hei")); await waitUntil { translator.requests.count == 1 }
        controller.update(request("Hei, verden.", revision: 1))
        try? await Task.sleep(for: .milliseconds(250))
        let finished = ContinuousClock.now; translator.succeed("Hi")
        await waitUntil { translator.requests.count == 2 }
        XCTAssertLessThan(finished.duration(to: .now), .milliseconds(150))
        translator.succeed("Hello, world."); await waitUntil { !controller.isLoading }
    }
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

    func testFailureForAnExtendedCaptionClearsItsEarlierPartialMeaning() async {
        let translator = Translator()
        let controller = MeaningController(delay: .zero, translate: translator.translate)
        controller.update(request("Hei"))
        await waitUntil { translator.requests.count == 1 }
        translator.succeed("Hi")
        await waitUntil { !controller.isLoading }
        controller.update(request("Hei, jeg liker kaffe.", revision: 1))
        await waitUntil { translator.requests.count == 2 }
        translator.fail()
        await waitUntil { controller.error != nil }
        XCTAssertEqual(controller.text, "")
        controller.retry()
        await waitUntil { translator.requests.count == 3 }
        translator.succeed("Hi, I like coffee.")
        await waitUntil { !controller.isLoading }
        XCTAssertEqual(controller.text, "Hi, I like coffee.")
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

    func testTranslationInputKeepsTheStartOfLongPassages() {
        let text = "UNIQUE_START " + String(repeating: "y", count: 2_300) + " END"
        XCTAssertTrue(MeaningRequest.translationInput(for: text).hasPrefix("UNIQUE_START"))
        XCTAssertTrue(MeaningRequest.translationInput(for: text).hasSuffix(" END"))
        XCTAssertEqual(MeaningRequest.translationInput(for: text), text)
    }
    func testLongCaptionFailureIsVisibleAndOnlyCompleteRetryIsCached() async {
        let text = "UNIQUE_START " + String(repeating: "我喜欢咖啡。 ", count: 600) + " UNIQUE_END"
        let translator = Translator()
        let controller = MeaningController(delay: .zero, translate: translator.translate)
        var saved: [String: String] = [:]
        controller.onResult = { request, result in saved[request.cacheKey] = result.text }
        let longRequest = request(text)
        controller.update(longRequest)
        await waitUntil { translator.requests.count == 1 }
        XCTAssertEqual(translator.requests[0].translationInput, text)
        translator.fail()
        await waitUntil { !controller.isLoading }
        XCTAssertNotNil(controller.error)
        XCTAssertTrue(saved.isEmpty)
        XCTAssertEqual(controller.text, "")
        controller.retry()
        await waitUntil { translator.requests.count == 2 }
        XCTAssertEqual(translator.requests[1].translationInput, text)
        translator.succeed("The entire caption, including its beginning and end.")
        await waitUntil { !controller.isLoading }
        XCTAssertNil(controller.error)
        XCTAssertEqual(saved[longRequest.cacheKey], controller.text)
    }

}
