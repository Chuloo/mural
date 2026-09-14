import XCTest
import CryptoKit
@testable import MuralCore

final class ClassroomTests: XCTestCase {
    func testSourceUpdatePreservesAllLegacyIDsAndOrder() throws {
        let c = try OgdenCurriculum.load()
        // Fingerprint captured from the pre-update 850-word resource, not from the new source.
        let ids = Data(c.words.map(\.id).joined(separator: "\n").utf8)
        XCTAssertEqual(SHA256.hash(data: ids).map { String(format: "%02x", $0) }.joined(),
                       "02f0639e0bce3af2ec0f0ad5bc0ab88f563a94e1ea7e416a0f40c3b15088bce7")
        var archive = Archive()
        archive.sessions = c.words.map { word in
            var session = SessionRecord(languageID: "en")
            var settings = ClassroomSettings(); settings.wordID = word.id
            session.classroom = settings
            return session
        }
        let restored = try Archive.decode(archive.encoded())
        XCTAssertEqual(restored.sessions.map { $0.classroom?.wordID }, c.words.map { Optional($0.id) })
        for (uk, us) in [("behaviour", "behavior"), ("colour", "color"), ("harbour", "harbor"), ("humour", "humor")] {
            let id = "english:ogden:\(uk)"
            XCTAssertEqual(c.word(id: id)?.term, us)
            XCTAssertTrue(c.search(uk).contains { $0.id == id })
            XCTAssertTrue(c.search(us).contains { $0.id == id })
            var settings = ClassroomSettings(); settings.wordID = id
            archive.preferences.classroom = settings
            XCTAssertEqual(try Archive.decode(archive.encoded()).preferences.classroom?.wordID, id)
        }
        XCTAssertEqual(c.word(id: "english:ogden:i")?.term, "I")
    }

    func testNewChineseSourceAndCorrectedSenseReachTeacher() throws {
        let c = try OgdenCurriculum.load()
        let come = try XCTUnwrap(c.word(id: "english:ogden:come"))
        XCTAssertEqual(come.exampleEn, "Come here when you're ready.")
        XCTAssertEqual(come.meaningZhCn, "来,前来")
        let lead = try XCTUnwrap(c.word(id: "english:ogden:lead"))
        XCTAssertEqual(lead.exampleZhCn, "这些管子是铅制的。")
        XCTAssertFalse(lead.definitionEn.contains("pencils"))
        XCTAssertFalse(lead.synonyms.contains("graphite"))
        let settings = ClassroomSettings()
        let language = try XCTUnwrap(LanguageRegistry.module(for: "en"))
        let prompt = ClassroomPolicy.greeting(settings: settings, word: lead, language: language)
        XCTAssertTrue(prompt.contains(lead.exampleEn))
        XCTAssertTrue(prompt.contains(lead.exampleZhCn))
        XCTAssertTrue(prompt.contains("never teach them as automatically interchangeable"))
    }
    func testAcknowledgementCannotCommitAnotherSessionOrExpiredWord() {
        let session = UUID(), time = Date(timeIntervalSince1970: 1_800_000_000)
        var next = ClassroomSettings(); next.wordID = "english:ogden:get"
        let change = ClassroomWordChange(commandID: "next", sessionID: session, settings: next, sentAt: time)
        XCTAssertEqual(change.acknowledgement("next", sessionID: session, at: time.addingTimeInterval(1)), .accept)
        XCTAssertEqual(change.acknowledgement("another", sessionID: session, at: time), .ignore)
        XCTAssertEqual(change.acknowledgement("next", sessionID: UUID(), at: time), .ignore)
        XCTAssertEqual(change.acknowledgement("next", sessionID: nil, at: time), .ignore)
        XCTAssertFalse(change.hasExpired(at: time.addingTimeInterval(19.99)))
        XCTAssertTrue(change.hasExpired(at: time.addingTimeInterval(20)))
        // The server may have applied this card while its ACK was delayed.
        // An expired ACK must never resume teaching against the old visible card.
        XCTAssertEqual(change.acknowledgement("next", sessionID: session, at: time.addingTimeInterval(20)), .expired)
        XCTAssertEqual(change.acknowledgement("next", sessionID: session, at: time.addingTimeInterval(25)), .expired)
    }
    func testPinnedDatasetAndCompleteUsableCards() throws {
        let curriculum = try OgdenCurriculum.load()
        XCTAssertEqual(curriculum.words.count, 850)
        XCTAssertEqual(Set(curriculum.words.map(\.term)).count, 850)
        XCTAssertTrue(curriculum.notice.contains("MIT License"))
        XCTAssertTrue(curriculum.notice.contains("Copyright (c) 2026 Skivein"))
        XCTAssertTrue(curriculum.notice.contains("not been certified by a human dictionary editor"))
        XCTAssertTrue(OgdenCurriculum.sourceURL.absoluteString.contains("longlong-skyligo/Ogden"))
        XCTAssertTrue(curriculum.notice.contains(OgdenCurriculum.sourceRevision))
        for word in curriculum.words {
            let data = try XCTUnwrap(word.promptData.data(using: .utf8))
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
            XCTAssertEqual(object["id"], word.id)
            XCTAssertEqual(object["example_en"], word.exampleEn)
            XCTAssertEqual(object["related_words"], word.synonyms.joined(separator: ", "))
            XCTAssertTrue(word.ipaUk.hasPrefix("/"))
            XCTAssertTrue(word.ipaUs.hasPrefix("/"))
            XCTAssertEqual(curriculum.word(id: word.id), word)
        }
    }

    func testSearchSelectionAndEndDoNotSkipOrWrap() throws {
        let c = try OgdenCurriculum.load()
        XCTAssertEqual(c.search("   ").count, 850)
        XCTAssertTrue(c.search(" COME ").contains { $0.term == "come" })
        XCTAssertTrue(c.search("前来").contains { $0.term == "come" })
        XCTAssertTrue(c.search("zzzz-not-a-word").isEmpty)
        XCTAssertNil(c.word(id: "missing"))
        XCTAssertEqual(c.next(after: nil), c.words.first)
        for index in c.words.indices.dropLast() {
            XCTAssertEqual(c.next(after: c.words[index].id), c.words[index + 1])
        }
        XCTAssertNil(c.next(after: c.words.last?.id))
    }

    func testEveryCardReachesGreetingAndUntruncatedSameSessionUpdateWithoutFreezingInitialCard() throws {
        let c = try OgdenCurriculum.load()
        let language = try XCTUnwrap(LanguageRegistry.module(for: "en"))
        for teacher in TeachingLanguage.allCases {
            var settings = ClassroomSettings(); settings.teachingLanguage = teacher
            for word in c.words {
                settings.wordID = word.id
                let voice = ClassroomPolicy.voice(settings: settings, word: word, language: language)
                let update = ClassroomPolicy.update(settings: settings, word: word, language: language)
                XCTAssertFalse(voice.contains(word.promptData))
                XCTAssertTrue(ClassroomPolicy.greeting(settings: settings, word: word, language: language).contains(word.promptData))
                XCTAssertTrue(update.contains(word.promptData))
                XCTAssertTrue(update.contains(teacher.name))
                XCTAssertLessThanOrEqual(update.utf16.count, 4000, word.id)
                XCTAssertFalse(voice.contains("Speak ONLY English"))
                XCTAssertFalse(voice.contains("Never translate"))
            }
            for instruction in [ClassroomPolicy.help(settings: settings, language: language),
                                ClassroomPolicy.reply(settings: settings, language: language, purpose: "Answer help") ] {
                XCTAssertTrue(instruction.contains(teacher.name))
            }
        }
    }

    func testTargetTeachingAndExistingPreferencesStayIndependent() throws {
        var archive = Archive()
        archive.preferences.learningLanguageID = "fr"
        archive.preferences.meaningLanguage = "German"
        var settings = ClassroomSettings(); settings.teachingLanguage = .vietnamese
        settings.wordID = "english:ogden:come"
        archive.preferences.classroom = settings
        var record = SessionRecord(languageID: "en"); record.classroom = settings
        archive.sessions = [record, SessionRecord(languageID: "fr")]
        let decoded = try Archive.decode(archive.encoded())
        XCTAssertEqual(decoded.preferences.learningLanguageID, "fr")
        XCTAssertEqual(decoded.preferences.meaningLanguage, "German")
        XCTAssertEqual(decoded.preferences.classroom, settings)
        XCTAssertEqual(decoded.sessions[0].classroom?.effectiveLanguageID, "en")
        XCTAssertEqual(decoded.sessions[0].classroom?.teachingLanguage, .vietnamese)
        XCTAssertNil(decoded.sessions[1].classroom)
        settings.course = .guided; settings.targetLanguageID = "fr"
        XCTAssertEqual(settings.effectiveLanguageID, "fr")
        settings.teachingLanguage = .chinese
        XCTAssertEqual(settings.effectiveLanguageID, "fr")
    }

    func testPreClassroomArchiveStillLoadsAndKeepsHistory() throws {
        var archive = Archive(); archive.preferences.hasOnboarded = true
        var record = SessionRecord(languageID: "en")
        record.append(Fragment(speaker: .user, text: "Hi", startMS: 0, endMS: 300))
        archive.sessions = [record]
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: archive.encoded()) as? [String: Any])
        var preferences = try XCTUnwrap(object["preferences"] as? [String: Any]); preferences.removeValue(forKey: "classroom")
        object["preferences"] = preferences
        object["sessions"] = try XCTUnwrap(object["sessions"] as? [[String: Any]]).map { original in
            var session = original; session.removeValue(forKey: "classroom"); return session
        }
        let decoded = try Archive.decode(JSONSerialization.data(withJSONObject: object))
        XCTAssertNil(decoded.preferences.classroom)
        XCTAssertNil(decoded.sessions[0].classroom)
        XCTAssertEqual(decoded.sessions[0].fragments[0].text, "Hi")
        XCTAssertTrue(decoded.preferences.hasOnboarded)
    }

    func testImportRejectsMismatchedTargetAndInvalidSettings() throws {
        var archive = Archive(); var record = SessionRecord(languageID: "fr")
        record.classroom = ClassroomSettings(); archive.sessions = [record]
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        archive.sessions = []
        var settings = ClassroomSettings(); settings.targetLanguageID = "invalid"
        archive.preferences.classroom = settings
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        settings.targetLanguageID = "en"; settings.wordID = "not-in-the-library"
        archive.preferences.classroom = settings
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
    }
}
