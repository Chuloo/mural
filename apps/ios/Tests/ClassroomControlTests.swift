import XCTest
@testable import MuralCore

final class ClassroomControlTests: XCTestCase {
    func testQueuedWordOnlyBelongsToItsOriginalLessonAndCard() {
        let session = UUID()
        let change = DeferredClassroomWordChange(wordID: "next", previousID: "current", sessionID: session)
        XCTAssertEqual(change.target(sessionID: session, currentWordID: "current"), "next")
        XCTAssertNil(change.target(sessionID: UUID(), currentWordID: "current"))
        XCTAssertNil(change.target(sessionID: nil, currentWordID: "current"))
        XCTAssertNil(change.target(sessionID: session, currentWordID: "different"))
    }
    func testLearnerSaysTheyHaveLearnedTheWordAndContinues() throws {
        let c = try OgdenCurriculum.load()
        let current = "english:ogden:colour"
        for request in ["我已经掌握了", "老师，我已经掌握了。", "已经掌握了", "我已经掌握这个单词", "这个词我掌握了", "已经学会这个词了", "这个单词我已经掌握了", "我学会了", "这个词我会了", "我已经会了，下一个", "再下一个", "I have learned this word", "I already know this word", "Tôi đã biết từ này"] {
            XCTAssertEqual(ClassroomVoiceCommand.parse(request, curriculum: c, currentWordID: current), .selectWord("english:ogden:comfort"), request)
        }
        for request in ["我还没掌握", "我没有掌握", "不要换词", "已经掌握了是什么意思", "你觉得我已经掌握了吗", "我已经掌握这个单词了吗", "这个词我掌握了的意思是什么", "我已经掌握了，但是想再复习", "I don't know this word", "Have I mastered this word?", "He said I already know this word", "Tôi chưa biết từ này"] {
            XCTAssertNil(ClassroomVoiceCommand.parse(request, curriculum: c, currentWordID: current), request)
        }
        XCTAssertNil(ClassroomVoiceCommand.parse("我已经掌握了", curriculum: c, currentWordID: c.words.last?.id))
    }

    func testExplicitNavigationAndTeachingCommandsDoNotMatchOrdinarySpeech() throws {
        let c = try OgdenCurriculum.load(), current = "english:ogden:colour"
        for request in ["下一个单词", "老师，帮我换到下一个词吧。", "next word please", "từ tiếp theo"] {
            XCTAssertEqual(ClassroomVoiceCommand.parse(request, curriculum: c, currentWordID: current), .selectWord("english:ogden:comfort"), request)
        }
        for request in ["学习单词 lead", "请切换到 colour", "learn the word color"] {
            let id = request.contains("lead") ? "english:ogden:lead" : current
            XCTAssertEqual(ClassroomVoiceCommand.parse(request, curriculum: c, currentWordID: current), .selectWord(id), request)
        }
        for request in ["不要换下一个词", "Don't go to the next word", "What does next word mean?", "He said next word", "color is my favorite word", "学习单词 definitely-not-in-library"] {
            XCTAssertNil(ClassroomVoiceCommand.parse(request, curriculum: c, currentWordID: current), request)
        }
        XCTAssertEqual(ClassroomVoiceCommand.parse("讲一下语法", curriculum: c, currentWordID: current), .action(.grammar))
        XCTAssertEqual(ClassroomVoiceCommand.parse("我想学习日语", curriculum: c, currentWordID: current), .setTargetLanguage("日语"))
        XCTAssertNil(ClassroomVoiceCommand.parse("下一词", curriculum: c, currentWordID: c.words.last?.id))
    }

    func testOtherTargetDoesNotStartWithAnEnglishOrOldWordLesson() throws {
        var settings = ClassroomSettings(); settings.course = .guided; settings.targetLanguageID = "other"
        settings.wordID = "english:ogden:come"
        XCTAssertTrue(settings.isValid)
        XCTAssertTrue(settings.isCustomTarget)
        let language = try XCTUnwrap(settings.resolvedLanguage)
        let old = try OgdenCurriculum.load().word(id: settings.wordID)
        let greeting = ClassroomPolicy.greeting(settings: settings, word: old, language: language)
        XCTAssertTrue(greeting.contains("ask which language"))
        XCTAssertFalse(greeting.contains("Come here"))
        XCTAssertFalse(greeting.contains("one short English"))
        settings.customTargetLanguage = "日本語"
        XCTAssertEqual(settings.resolvedLanguage?.name, "日本語")
        var archive = Archive(); archive.preferences.classroom = settings
        var session = SessionRecord(languageID: "other"); session.classroom = settings; archive.sessions = [session]
        XCTAssertEqual(try Archive.decode(archive.encoded()).sessions.first?.classroom?.customTargetName, "日本語")
        archive.sessions[0].classroom = nil
        XCTAssertThrowsError(try Archive.decode(archive.encoded()))
        settings.customTargetLanguage = String(repeating: "a", count: 81)
        XCTAssertFalse(settings.isValid)
    }

    func testPronunciationAndGrammarReferOnlyToTheSelectedCard() throws {
        let c = try OgdenCurriculum.load(); let settings = ClassroomSettings()
        for word in c.words {
            for action in ClassroomAction.allCases {
                let prompt = ClassroomPolicy.action(action, settings: settings, word: word, language: .english)
                XCTAssertTrue(prompt.contains(word.promptData))
                XCTAssertLessThanOrEqual(prompt.utf16.count, 4000, word.id)
            }
        }
        let lead = try XCTUnwrap(c.word(id: "english:ogden:lead"))
        let grammar = ClassroomPolicy.action(.grammar, settings: settings, word: lead, language: .english)
        XCTAssertTrue(grammar.contains("grammar"))
        XCTAssertTrue(grammar.contains("Mandarin Chinese"))
        let british = ClassroomPolicy.action(.pronounceBritish, settings: settings, word: lead, language: .english)
        XCTAssertTrue(british.contains(lead.ipaUk))
        XCTAssertTrue(british.contains("British"))
        XCTAssertFalse(ClassroomPolicy.voice(settings: settings, word: lead, language: .english).contains(lead.promptData))
    }

    func testConversationExplanationsAreIndependentAcrossEveryReplyPath() throws {
        for motherTongue in ["Mandarin Chinese", "Vietnamese", "日本語"] {
            let learner = LearningEngine.project([], languageID: "en")
            let prompts = [
                TeachingPolicy.voice(language: .english, learner: learner, theme: nil, interests: "", meaningLanguage: "German", explanationLanguage: motherTongue),
                TeachingPolicy.greeting(language: .english, explanationLanguage: motherTongue),
                TeachingPolicy.help(language: .english, explanationLanguage: motherTongue),
                TeachingPolicy.typedReply(language: .english, explanationLanguage: motherTongue),
                TeachingPolicy.delegation(language: .english, explanationLanguage: motherTongue),
                TeachingPolicy.theme(nil, language: .english, explanationLanguage: motherTongue),
                TeachingPolicy.redirect(language: .english, explanationLanguage: motherTongue)
            ]
            for prompt in prompts {
                XCTAssertTrue(prompt.contains(motherTongue))
                XCTAssertTrue(prompt.contains("English"))
                XCTAssertFalse(prompt.contains("ONLY English"))
                XCTAssertFalse(prompt.contains("Never translate"))
                XCTAssertFalse(prompt.contains("Reply only in English"))
            }
        }
        var archive = Archive(); archive.preferences.learningLanguageID = "fr"
        archive.preferences.meaningLanguage = "German"
        archive.preferences.conversationTeachingLanguage = "Vietnamese"
        archive.preferences.orbSkinID = "ocean"
        let restored = try Archive.decode(archive.encoded()).preferences
        XCTAssertEqual(restored.learningLanguageID, "fr")
        XCTAssertEqual(restored.meaningLanguage, "German")
        XCTAssertEqual(restored.resolvedConversationTeachingLanguage, "Vietnamese")
        XCTAssertEqual(restored.orbSkinID, "ocean")
        XCTAssertNil(try Archive.decode(Archive().encoded()).preferences.orbSkinID)
    }
}
