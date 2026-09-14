import XCTest
@testable import MuralCore

final class OgdenAudioResourcesTests: XCTestCase {
    func testAllOriginalRecordingsAreBundledUnderDistinctAccentFolders() throws {
        let curriculum = try OgdenCurriculum.load()
        var paths = Set<String>()
        for word in curriculum.words {
            for accent in ["us", "uk"] {
                let url = try XCTUnwrap(OgdenAudioResources.url(for: word, accent: accent), "\(word.term) \(accent)")
                XCTAssertTrue(paths.insert(url.path).inserted)
                XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
                XCTAssertGreaterThan(try Data(contentsOf: url).count, 100)
            }
        }
        XCTAssertEqual(paths.count, 1700)
        XCTAssertNil(OgdenAudioResources.url(for: curriculum.words[0], accent: "../us"))
        for word in curriculum.words where word.spellingUs != word.spellingUk {
            XCTAssertEqual(OgdenAudioResources.url(for: word, accent: "uk")?.lastPathComponent, word.term.lowercased() + ".mp3")
        }
    }

    func testFiveWordWindowFollowsSharedIDsAndNeverWrapsOrDefaults() throws {
        let c = try OgdenCurriculum.load()
        for i in c.words.indices {
            XCTAssertEqual(OgdenAudioResources.window(startingAt: c.words[i].id, curriculum: c).map(\.id),
                           Array(c.words[i..<min(i + 5, c.words.count)]).map(\.id))
        }
        XCTAssertTrue(OgdenAudioResources.window(startingAt: nil, curriculum: c).isEmpty)
        XCTAssertTrue(OgdenAudioResources.window(startingAt: "unknown", curriculum: c).isEmpty)
    }
}
