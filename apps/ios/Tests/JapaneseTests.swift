import XCTest
@testable import MuralCore

final class JapaneseTests: XCTestCase {
    func testKanaReadingUsesWordReadingsAndKeepsKanaAndPunctuation() {
        for (text, reading) in [("東京", "とうきょう"), ("友達", "ともだち"), ("一人", "ひとり"), ("二人", "ふたり"), ("大人", "おとな"), ("銀行", "ぎんこう")] {
            XCTAssertEqual(JapaneseKana.reading(text), reading, text)
        }
        XCTAssertEqual(JapaneseKana.reading("今日は天気がいいですね。"), "きょうはてんきがいいですね。")
        XCTAssertEqual(JapaneseKana.reading("友達とコーヒーを飲みました。"), "ともだちとコーヒーをのみました。")
        XCTAssertEqual(JapaneseKana.reading("私は日本語を勉強しています。"), "わたしはにほんごをべんきょうしています。")
    }

    func testTextWithoutKanjiHasNoReading() {
        for text in ["", "こんにちは！", "コーヒー", "Hello, Mural 2026! ☕️"] {
            XCTAssertNil(JapaneseKana.reading(text), text)
        }
    }

    func testWordLinksPreserveMixedScriptPunctuationAndWhitespace() {
        for text in ["", "こんにちは！", "  銀行に行ってから、旅行します。\n", "こんにちは、Mural 2026！☕️\nまたね", "𠀀とコーヒー", "本", "café e pão"] {
            XCTAssertEqual(JapaneseKana.tokens(text).map(\.text).joined(), text)
            XCTAssertEqual(CaptionWords.segments(text, languageID: "ja").map(\.text).joined(), text)
        }
        let links = CaptionWords.segments("銀行に行ってから、旅行します。", languageID: "ja").compactMap(\.lookup)
        XCTAssertTrue(links.contains("銀行"))
        XCTAssertTrue(links.contains("旅行"))
        XCTAssertGreaterThan(links.count, 4)
        XCTAssertFalse(links.contains(where: { $0.contains("、") || $0.contains("。") }))
    }

    func testReadingAidSelectsTheLanguageSpecificReading() {
        XCTAssertEqual(ReadingAid.reading("東京", languageID: "ja"), "とうきょう")
        XCTAssertEqual(ReadingAid.reading("银行", languageID: "zh"), "yínháng")
        XCTAssertNil(ReadingAid.reading("東京", languageID: "ru"))
        XCTAssertEqual(LanguageRegistry.all.map(\.id).filter(ReadingAid.supports), ["zh", "ja"])
    }

    func testJapaneseScriptAndRegionalDetectorIDsDoNotCauseRedirects() {
        for detected in ["ja", "ja-JP", "ja_JP"] {
            XCTAssertFalse(TeachingPolicy.shouldRedirectSpeech(language: .japanese, detectedLanguageID: detected, confidence: 0.99))
        }
        XCTAssertTrue(TeachingPolicy.shouldRedirectSpeech(language: .japanese, detectedLanguageID: "zh-Hans", confidence: 0.99))
    }

    func testModuleTeachesStandardWritingWithoutAppendedReadings() throws {
        let japanese = try XCTUnwrap(LanguageRegistry.module(for: "ja"))
        XCTAssertEqual(japanese.settingsTitle, "Japanese · Standard")
        XCTAssertTrue(japanese.writingGuidance.contains("do not append furigana, rōmaji or English translations"))
        XCTAssertTrue(japanese.lemmaGuidance.contains("plain dictionary form"))
        XCTAssertEqual(japanese.teachingFocus.count, 6)
        XCTAssertEqual(japanese.themes.count, 24)
        XCTAssertEqual(MeaningLanguages.greeting(in: "Japanese"), "こんにちは！")
    }
}
