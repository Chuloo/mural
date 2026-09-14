import XCTest
@testable import MuralCore

final class AvatarSelectionTests: XCTestCase {
    func testExistingArchivesKeepSelectedSkinWithoutAvatar() throws {
        var archive = Archive()
        archive.preferences.orbSkinID = "prism"
        let restored = try Archive.decode(archive.encoded())
        XCTAssertNil(restored.preferences.avatar)
        XCTAssertEqual(restored.preferences.orbSkinID, "prism")
    }

    func testEveryBuiltInAvatarRoundTrips() throws {
        for mode in [AvatarMode.cartoon, .animal] {
            for index in 0..<20 {
                var archive = Archive(), avatar = AvatarSelection()
                avatar.mode = mode; avatar.index = index
                archive.preferences.avatar = avatar
                XCTAssertEqual(try Archive.decode(archive.encoded()).preferences.avatar, avatar)
            }
        }
    }

    func testArchiveWithoutBackgroundFieldPreservesAppearanceAndLearning() throws {
        var archive = Archive(), avatar = AvatarSelection(), learning = OgdenLearningState()
        avatar.mode = .animal
        avatar.index = 7
        learning.accent = "uk"
        learning.traditionalChinese = true
        archive.preferences.avatar = avatar
        archive.preferences.orbSkinID = "prism"
        archive.preferences.pageBackgroundID = "original"
        archive.preferences.ogdenLearning = learning
        archive.preferences.conversationTeachingLanguage = "zh-Hans"
        archive.preferences.sessionMinutes = 20

        // Remove the key from actual JSON, rather than relying on encoding a nil property.
        var document = try XCTUnwrap(JSONSerialization.jsonObject(with: archive.encoded()) as? [String: Any])
        var preferences = try XCTUnwrap(document["preferences"] as? [String: Any])
        XCTAssertEqual(preferences.removeValue(forKey: "pageBackgroundID") as? String, "original")
        document["preferences"] = preferences
        XCTAssertNil(preferences["pageBackgroundID"])
        let legacyData = try JSONSerialization.data(withJSONObject: document)

        let restored = try Archive.decode(legacyData)
        XCTAssertNil(restored.preferences.pageBackgroundID)
        XCTAssertEqual(restored.preferences.avatar, avatar)
        XCTAssertEqual(restored.preferences.orbSkinID, "prism")
        XCTAssertEqual(restored.preferences.ogdenLearning, learning)
        XCTAssertEqual(restored.preferences.conversationTeachingLanguage, "zh-Hans")
        XCTAssertEqual(restored.preferences.sessionMinutes, 20)
        XCTAssertEqual(restored.schemaVersion, archive.schemaVersion)
    }

    func testBackgroundSkinAndAvatarSaveIndependently() throws {
        var archive = Archive(), avatar = AvatarSelection(), learning = OgdenLearningState()
        avatar.mode = .cartoon
        avatar.index = 3
        learning.accent = "uk"
        learning.traditionalChinese = true
        archive.preferences.avatar = avatar
        archive.preferences.orbSkinID = "prism"
        archive.preferences.pageBackgroundID = "original"
        archive.preferences.ogdenLearning = learning

        archive.preferences.pageBackgroundID = "aurora"
        archive = try Archive.decode(archive.encoded())
        XCTAssertEqual(archive.preferences.pageBackgroundID, "aurora")
        XCTAssertEqual(archive.preferences.orbSkinID, "prism")
        XCTAssertEqual(archive.preferences.avatar, avatar)
        XCTAssertEqual(archive.preferences.ogdenLearning, learning)

        avatar.mode = .animal
        avatar.index = 12
        archive.preferences.avatar = avatar
        archive = try Archive.decode(archive.encoded())
        XCTAssertEqual(archive.preferences.avatar, avatar)
        XCTAssertEqual(archive.preferences.pageBackgroundID, "aurora")
        XCTAssertEqual(archive.preferences.orbSkinID, "prism")
        XCTAssertEqual(archive.preferences.ogdenLearning, learning)

        archive.preferences.orbSkinID = "classic"
        archive = try Archive.decode(archive.encoded())
        XCTAssertEqual(archive.preferences.orbSkinID, "classic")
        XCTAssertEqual(archive.preferences.pageBackgroundID, "aurora")
        XCTAssertEqual(archive.preferences.avatar, avatar)
        XCTAssertEqual(archive.preferences.ogdenLearning, learning)

        archive.preferences.pageBackgroundID = nil
        archive = try Archive.decode(archive.encoded())
        XCTAssertNil(archive.preferences.pageBackgroundID)
        XCTAssertEqual(archive.preferences.orbSkinID, "classic")
        XCTAssertEqual(archive.preferences.avatar, avatar)
        XCTAssertEqual(archive.preferences.ogdenLearning, learning)
    }

    func testInvalidIndexesAndPathsRejectEntireImport() throws {
        for index in [-1, 20, Int.max] {
            var archive = Archive(), avatar = AvatarSelection()
            avatar.mode = .animal; avatar.index = index
            archive.preferences.avatar = avatar
            XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        }
        for filename in ["../secret.jpg", "/tmp/photo.jpg", "https://example.com/a.jpg", String(repeating: "-", count: 36) + ".jpg", "photo.jpg"] {
            var archive = Archive(), avatar = AvatarSelection()
            avatar.mode = .custom; avatar.customImageFilename = filename
            archive.preferences.avatar = avatar
            XCTAssertThrowsError(try Archive.decode(archive.encoded()), filename)
        }
    }

    func testCustomRequiresFilenameAndKeepsOnlyLocalBasename() throws {
        var archive = Archive(), avatar = AvatarSelection()
        avatar.mode = .custom; archive.preferences.avatar = avatar
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        avatar.customImageFilename = UUID().uuidString + ".jpg"
        archive.preferences.avatar = avatar
        XCTAssertEqual(try Archive.decode(archive.encoded()).preferences.avatar, avatar)
    }
}
