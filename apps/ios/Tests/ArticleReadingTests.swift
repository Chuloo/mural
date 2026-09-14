import XCTest
@testable import MuralCore

final class ArticleReadingTests: XCTestCase {
    func testArticleParagraphsReconstructTextAndPreserveLongInput() {
        var settings = ClassroomSettings()
        settings.course = .articleReading
        settings.articleText = "第一段。\n\n" + String(repeating: "长文本。", count: 500)
        XCTAssertEqual(settings.articleParagraphs.joined(), settings.articleText)
        XCTAssertTrue(settings.articleParagraphs.allSatisfy { $0.utf16.count <= 1500 })
        XCTAssertTrue(settings.canStartLesson)
    }

    func testEmptyArticleCannotStart() {
        var settings = ClassroomSettings(); settings.course = .articleReading
        settings.articleText = "   "
        XCTAssertFalse(settings.canStartLesson)
    }

    func testArticlePolicyUsesCurrentParagraphOnlyAndMode() {
        var settings = ClassroomSettings(); settings.course = .articleReading
        settings.teachingLanguage = .chinese
        settings.articleText = "CURRENT paragraph"
        let prompt = ClassroomPolicy.greeting(settings: settings, word: nil, language: LanguageRegistry.module(for: "en")!)
        XCTAssertTrue(prompt.contains("CURRENT paragraph"))
        XCTAssertFalse(prompt.contains("ENGLISH 850"))
        XCTAssertTrue(prompt.contains("Do not explain it"))
        XCTAssertTrue(ClassroomPolicy.update(settings: settings, word: nil, language: .english).contains("Do not explain it"))
        XCTAssertTrue(ClassroomPolicy.articleAction(settings: settings, action: .comprehension).contains("exactly one"))
    }

    func testLegacySettingsDecodeUsesArticleDefaults() throws {
        let data = Data("{\"teachingLanguage\":\"zh-Hans\",\"targetLanguageID\":\"en\",\"course\":\"english850\"}".utf8)
        let settings = try JSONDecoder().decode(ClassroomSettings.self, from: data)
        XCTAssertEqual(settings.articleParagraphIndex, 0)
        XCTAssertEqual(settings.articleMode, .readThenCheck)
        XCTAssertTrue(settings.isValid)
    }

    func testArticleBoundsAndUnicode() {
        var settings = ClassroomSettings(); settings.course = .articleReading
        settings.articleText = String(repeating: "😀", count: 800)
        XCTAssertTrue(settings.articleParagraphs.allSatisfy { $0.utf16.count <= 1500 })
        XCTAssertEqual(settings.articleParagraphs.joined(), settings.articleText)
        settings.articleText = String(repeating: "a", count: 40_001)
        XCTAssertFalse(settings.canStartLesson)
        XCTAssertFalse(settings.isValid)
    }
}
