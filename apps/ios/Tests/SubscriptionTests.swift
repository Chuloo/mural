import XCTest
@testable import MuralCore

final class SubscriptionTests: XCTestCase {
    private let token = String(repeating: "a", count: 43)
    func testPrivateHTTPSConnectionRoundTripAndBoundedRoutes() throws {
        let value = try SubscriptionConnection(address: "https://my-service.example:8445/", token: token, voice: "alloy")
        XCTAssertEqual(try JSONDecoder().decode(SubscriptionConnection.self, from: JSONEncoder().encode(value)), value)
        XCTAssertEqual(value.voice, "alloy")
        let id = UUID().uuidString
        XCTAssertEqual(try value.endpoint("live/sessions/" + id + "/events", after: 2).query, "after=2")
        for path in ["../account", "https://attacker.example", "live/sessions/not-a-uuid", "account?redirect=evil", "live/sessions/" + id + "/files"] {
            XCTAssertThrowsError(try value.endpoint(path))
        }
        XCTAssertThrowsError(try value.endpoint("account", after: 1))
    }
    func testOldSavedConnectionUsesCoveAndVoiceCatalogRejectsInvalidValues() throws {
        let old = Data("{\"origin\":\"https://my-service.example\",\"token\":\"\(token)\"}".utf8)
        XCTAssertEqual(try JSONDecoder().decode(SubscriptionConnection.self, from: old).voice, "cove")
        let account: [String: Any] = ["type": "chatgpt", "defaultVoice": "cove", "voices": ["cove", "alloy"]]
        let catalog = try SubscriptionConnection.VoiceCatalog(account: account)
        XCTAssertEqual(catalog.voices, ["cove", "alloy"])
        XCTAssertEqual(catalog.defaultVoice, "cove")
        XCTAssertThrowsError(try SubscriptionConnection.VoiceCatalog(account: ["type": "chatgpt", "defaultVoice": "cove", "voices": ["cove", "bad voice"]]))
        XCTAssertThrowsError(try SubscriptionConnection(address: "https://my-service.example", token: token, voice: "bad voice"))
        XCTAssertThrowsError(try SubscriptionConnection(address: "https://my-service.example", token: token, voice: "Cove"))
        let tooManyVoices: [String] = (0..<41).map { "voice\($0)" }
        XCTAssertThrowsError(try SubscriptionConnection.VoiceCatalog(account: ["type": "chatgpt", "defaultVoice": "voice0", "voices": tooManyVoices]))
    }
    func testInstallerProvisioningSkipsNormalRelaunchAndPreservesExistingVoice() throws {
        XCTAssertNil(try SubscriptionConnection.installerProvisioningConnection(serviceAddress: nil, pairingCode: nil, existing: nil))
        let existing = try SubscriptionConnection(address: "https://my-service.example", token: token, voice: "alloy")
        let resolved = try SubscriptionConnection.installerProvisioningConnection(serviceAddress: "https://my-service.example", pairingCode: token, existing: existing)
        XCTAssertEqual(resolved, existing)
        XCTAssertEqual(resolved?.voice, "alloy")
    }
    func testInstallerProvisioningRejectsDifferentOrInvalidCredentials() throws {
        let existing = try SubscriptionConnection(address: "https://my-service.example", token: token, voice: "alloy")
        XCTAssertThrowsError(try SubscriptionConnection.installerProvisioningConnection(serviceAddress: "https://other.example", pairingCode: token, existing: existing))
        XCTAssertThrowsError(try SubscriptionConnection.installerProvisioningConnection(serviceAddress: "https://my-service.example", pairingCode: "bad", existing: nil))
    }
    func testSecretsCannotBeSentOverHTTPOrToAnInjectedURL() {
        for address in ["http://host.example", "https://user:pass@host.example", "https://host.example/path", "https://host.example?x=1", "https://host.example#fragment", "file:///tmp/data"] {
            XCTAssertThrowsError(try SubscriptionConnection(address: address, token: token))
        }
        for credential in ["short", "sk-" + token, token + "\nsecret", token + "/"] {
            XCTAssertThrowsError(try SubscriptionConnection(address: "https://host.example", token: credential))
        }
    }
    func testStreamingAndCorrectedFinalTurnDoNotDuplicateOrKeepLearningEvidence() {
        var transcript = SubscriptionTranscript(), session = SessionRecord()
        XCTAssertNil(transcript.consume(["type": "turn.created", "turn": ["id": "a", "role": "user"]], elapsedMS: 10))
        let first = transcript.consume(["type": "turn.delta", "turn_id": "a", "delta": "Jeg liker"], elapsedMS: 20)!
        SubscriptionTranscript.apply(first, to: &session, meaningVisible: false)
        let second = transcript.consume(["type": "turn.delta", "turn_id": "a", "delta": " kaffe."], elapsedMS: 30)!
        SubscriptionTranscript.apply(second, to: &session, meaningVisible: true)
        XCTAssertEqual(session.fragments.count, 1)
        XCTAssertEqual(session.passages.first?.text, "Jeg liker kaffe.")
        XCTAssertTrue(session.fragments[0].previousTexts.isEmpty)
        let p = session.passages[0]
        session.assessments = [Assessment(passageID: p.id, revisionKey: p.revisionKey, outcome: .success, suggestedLevel: 1, nextGoal: "", capability: "", words: [])]
        let final = transcript.consume(["type": "turn.done", "turn": ["id": "a", "role": "user", "transcript": "Jeg liker te."]], elapsedMS: 40)!
        SubscriptionTranscript.apply(final, to: &session, meaningVisible: false)
        XCTAssertEqual(session.fragments[0].text, "Jeg liker te.")
        XCTAssertEqual(session.fragments[0].previousTexts, ["Jeg liker kaffe."])
        XCTAssertTrue(session.fragments[0].meaningVisible)
        XCTAssertTrue(session.assessments.isEmpty)
        XCTAssertNil(transcript.consume(["type": "turn.done", "turn": ["id": "a", "role": "user", "transcript": "duplicate"]], elapsedMS: 50))
    }
    func testSeparateSpeakersAndUnknownEventsStayIsolated() {
        var transcript = SubscriptionTranscript()
        XCTAssertNil(transcript.consume(["type": "turn.delta", "turn_id": "missing", "delta": "bad"], elapsedMS: 0))
        XCTAssertNil(transcript.consume(["type": "turn.created", "turn": ["id": "tool", "role": "tool", "transcript": "bad"]], elapsedMS: 0))
        let a = transcript.consume(["type": "turn.created", "turn": ["id": "a", "role": "assistant", "transcript": "Hei"]], elapsedMS: 5)!
        let b = transcript.consume(["type": "turn.done", "turn": ["id": "b", "role": "user", "transcript": "Hallo"]], elapsedMS: 6)!
        XCTAssertNotEqual(a.id, b.id)
        XCTAssertNotEqual(a.speaker, b.speaker)
    }
    func testUnchangedFinalCaptionUpdatesTimeAndInvalidatesUnsupportedRecallEvidence() {
        var session = SessionRecord()
        let first = Fragment(id: "subscription:a", speaker: .user, text: "Hei", startMS: 10, endMS: 20)
        SubscriptionTranscript.apply(first, to: &session, meaningVisible: false)
        let passage = session.passages[0]
        session.assessments = [Assessment(passageID: passage.id, revisionKey: passage.revisionKey, outcome: .success,
                                          suggestedLevel: 1, nextGoal: "", capability: "", words: [])]
        var final = first; final.endMS = 40
        SubscriptionTranscript.apply(final, to: &session, meaningVisible: true)
        XCTAssertEqual(session.fragments.count, 1)
        XCTAssertEqual(session.fragments[0].endMS, 40)
        XCTAssertTrue(session.fragments[0].meaningVisible)
        XCTAssertNotEqual(session.passages[0].revisionKey, passage.revisionKey)
        XCTAssertTrue(session.assessments.isEmpty)
        XCTAssertTrue(session.fragments[0].previousTexts.isEmpty)
    }
}
