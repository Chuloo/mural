import XCTest
@testable import MuralCore

final class CaptionAndFlashcardTests: XCTestCase {
    func testFollowingPausesAcrossStreamingAndResumesOnlyForAnotherPassage() {
        var following = CaptionFollowing()
        XCTAssertEqual(following.nextOffset(current: 0, maximum: 100, elapsed: 0.05), 0)
        following.receive("first")
        XCTAssertEqual(following.nextOffset(current: 0, maximum: 100, elapsed: 0.05), 1.2, accuracy: 0.001)
        following.interrupt()
        following.receive("first")
        XCTAssertEqual(following.nextOffset(current: 20, maximum: 200, elapsed: 0.05), 20)
        following.receive("next")
        XCTAssertFalse(following.interrupted)
        XCTAssertEqual(following.nextOffset(current: 20, maximum: 200, elapsed: 0.05), 21.2, accuracy: 0.001)
    }
    func testFollowingClampsAtEndAndHandlesReducedMotion() {
        var following = CaptionFollowing(); following.receive("caption")
        XCTAssertEqual(following.nextOffset(current: 99, maximum: 100, elapsed: 10), 100)
        XCTAssertEqual(following.nextOffset(current: 0, maximum: -1, elapsed: 0.05), 0)
        XCTAssertEqual(following.nextOffset(current: 20, maximum: 200, elapsed: 0.05, reducedMotion: true), 200)
        following.interrupt()
        XCTAssertEqual(following.nextOffset(current: 20, maximum: 200, elapsed: 0.05, reducedMotion: true), 20)
    }
    func testDeckPreservesWordsProficiencyAndOrderWithinSelectedLanguage() {
        let date = Date(timeIntervalSince1970: 0)
        let first = WordState(id: "ru|книга|book", lemma: "книга", meaning: "book", form: "книгу", example: "Я читаю книгу.", bars: 2, understandingCount: 1, independentCount: 2, lastSeen: date, dueAt: date)
        var foreign = first; foreign.id = "nl|boek|book"
        var second = first; second.id = "ru|кофе|coffee"; second.lemma = "кофе"
        let input = [first, foreign, first, second]
        let deck = FlashcardDeck(words: input, languageID: "ru")
        XCTAssertEqual(deck.words.map(\.id), [first.id, second.id])
        XCTAssertEqual(deck.words[0].bars, first.bars)
        XCTAssertEqual(deck.words[0].meaning, first.meaning)
        XCTAssertEqual(deck.destination(from: 0, direction: -1), 0)
        XCTAssertEqual(deck.destination(from: 0, direction: 1), 1)
        XCTAssertEqual(deck.destination(from: 1, direction: 1), 1)
        XCTAssertEqual(deck.destination(from: 1, direction: -1), 0)
        XCTAssertEqual(input.count, 4)
        XCTAssertEqual(FlashcardDeck(words: [], languageID: "ru").destination(from: 0, direction: 1), 0)
    }
    func testDutchAndRussianWordLinksPreserveNativeTextAndStreamingBoundaries() {
        for (id, parts, expected, links) in [
            ("nl", ["Ik houd van kof", "fie. ", "Jij ook?"], "Ik houd van koffie. Jij ook?", ["Ik", "houd", "van", "koffie", "Jij", "ook"]),
            ("ru", ["Я читаю кни", "гу. ", "А ты?"], "Я читаю книгу. А ты?", ["Я", "читаю", "книгу", "А", "ты"])
        ] {
            XCTAssertEqual(Passage.join(parts), expected)
            let segments = CaptionWords.segments(expected, languageID: id)
            XCTAssertEqual(segments.map(\.text).joined(), expected)
            XCTAssertEqual(segments.compactMap(\.lookup), links)
        }
    }
}
