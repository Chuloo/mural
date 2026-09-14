import XCTest
@testable import MuralCore

final class InterfaceLanguageTests: XCTestCase {
    func testExplicitChoiceOverridesDeviceLanguage() {
        XCTAssertEqual(InterfaceLanguage.resolve(saved: "vi", preferredLanguages: ["zh-Hans-CN", "en-US"]), .vietnamese)
        XCTAssertEqual(InterfaceLanguage.resolve(saved: "en", preferredLanguages: ["vi-VN"]), .english)
    }
    func testMissingOrInvalidChoiceUsesFirstSupportedDeviceLanguage() {
        XCTAssertEqual(InterfaceLanguage.resolve(saved: nil, preferredLanguages: ["ja-JP", "zh-Hans-CN", "en-US"]), .simplifiedChinese)
        XCTAssertEqual(InterfaceLanguage.resolve(saved: "corrupt", preferredLanguages: ["vi-VN", "en-US"]), .vietnamese)
        XCTAssertEqual(InterfaceLanguage.resolve(saved: nil, preferredLanguages: ["de-DE"]), .english)
    }
    func testChoicePersistsSeparatelyFromLearningSettings() throws {
        let suite = "mural-interface-test-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("nb", forKey: "learningLanguage")
        defaults.set("ember", forKey: "voice")
        defaults.set("vi", forKey: InterfaceLanguage.preferenceKey)
        let reopened = try XCTUnwrap(UserDefaults(suiteName: suite))
        XCTAssertEqual(InterfaceLanguage.resolve(saved: reopened.string(forKey: InterfaceLanguage.preferenceKey), preferredLanguages: ["en"]), .vietnamese)
        XCTAssertEqual(reopened.string(forKey: "learningLanguage"), "nb")
        XCTAssertEqual(reopened.string(forKey: "voice"), "ember")
    }
    func testCatalogLookupAndMissingKeyKeepOriginalText() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".bundle")
        defer { try? FileManager.default.removeItem(at: directory) }
        let resource = directory.appendingPathComponent("vi.lproj")
        try FileManager.default.createDirectory(at: resource, withIntermediateDirectories: true)
        try "\"Done\" = \"Xong\";".write(to: resource.appendingPathComponent("Localizable.strings"), atomically: true, encoding: .utf8)
        let bundle = try XCTUnwrap(Bundle(path: directory.path))
        XCTAssertEqual(L10n.text("Done", language: .vietnamese, bundle: bundle), "Xong")
        XCTAssertEqual(L10n.text("Unknown message", language: .vietnamese, bundle: bundle), "Unknown message")
        XCTAssertEqual(L10n.text("Done", language: .simplifiedChinese, bundle: bundle), "Done")
    }
}
