import XCTest
@testable import MuralCore

final class CaptionAssemblyTests: XCTestCase {
    func testEverySupportedLanguagePreservesWordFragments() {
        let examples: [(String, [String], String)] = [
            ("nb", ["Hygg", "elig! Jeg liker fri", "lufts", "liv."], "Hyggelig! Jeg liker friluftsliv."),
            ("en", ["That is inter", "esting."], "That is interesting."),
            ("es", ["Me gusta apren", "der espa", "ñol."], "Me gusta aprender español."),
            ("fr", ["Aujourd", "’hui, c’est inté", "ressant."], "Aujourd’hui, c’est intéressant."),
            ("de", ["Das ist eine Sprach", "lern", "anwendung."], "Das ist eine Sprachlernanwendung."),
            ("it", ["È una conver", "sazione interes", "sante."], "È una conversazione interessante."),
            ("pt", ["Estou apren", "dendo portu", "guês."], "Estou aprendendo português."),
            ("zh", ["我", "喜欢", "学习", "中文。", "你呢？"], "我喜欢学习中文。你呢？")
        ]
        for (id, parts, expected) in examples {
            XCTAssertNotNil(LanguageRegistry.module(for: id))
            XCTAssertEqual(Passage.join(parts), expected, id)
            // The result must remain stable if the provider changes its token boundaries.
            for boundary in expected.indices {
                XCTAssertEqual(Passage.join([String(expected[..<boundary]), String(expected[boundary...])]), expected, id)
            }
        }
    }
    func testSentenceRepairDoesNotSplitAcronymsNumbersURLsOrCombiningMarks() {
        for (parts, expected) in [
            (["It is easy.", "Now you?"], "It is easy. Now you?"),
            (["Hei", "!", "Hvordan går det?"], "Hei! Hvordan går det?"),
            (["U.", "S.", "A."], "U.S.A."),
            (["3.", "14"], "3.14"),
            (["example.", "com"], "example.com"),
            (["caf", "e", "\u{301}"], "café"),
            (["今天。", "Hello!"], "今天。Hello!")
        ] { XCTAssertEqual(Passage.join(parts), expected) }
    }
    func testLegacyLearningEvidenceIsPreservedButNewEvidenceUsesCorrectText() {
        var session = SessionRecord(languageID: "nb")
        session.append(Fragment(id: "a", speaker: .user, text: "Jeg liker fri", startMS: 0, endMS: 100))
        session.append(Fragment(id: "b", speaker: .user, text: "luftsliv.", startMS: 100, endMS: 200))
        let passage = session.passages[0]
        let oldWord = WordProposal(lemma: "friluftsliv", meaning: "outdoor life", form: "luftsliv", kind: .assisted, confidence: 0.9,
            sourceIDs: ["a", "b"], quote: "Jeg liker fri luftsliv.", language: "nb")
        var old = Assessment(passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
            suggestedLevel: 2, nextGoal: "Fortell mer.", capability: "Describes interests", words: [oldWord])
        old.textAssemblyVersion = nil
        XCTAssertEqual(LearningEngine.validate(old, session: session)?.words.count, 1)
        old.textAssemblyVersion = 2
        XCTAssertEqual(LearningEngine.validate(old, session: session)?.words.count, 0)
        XCTAssertEqual(passage.text, "Jeg liker friluftsliv.")
    }
}
