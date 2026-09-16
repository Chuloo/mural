import XCTest
@testable import MuralCore

@MainActor final class VoiceConnectionRecoveryTests: XCTestCase {
    func testBriefHandoffRecoversAndLaterDisconnectFailsOnce() async throws {
        var failures = 0
        let recovery = VoiceConnectionRecovery(timeout: .milliseconds(40)) { failures += 1 }
        recovery.disconnected()
        recovery.connected()
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertEqual(failures, 0)
        recovery.disconnected()
        try await waitUntil { failures == 1 }
        XCTAssertEqual(failures, 1)
        recovery.disconnected()
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertEqual(failures, 1)
    }

    /// CI runners can run a main-actor timer well after its deadline, so a firing is awaited rather than given fixed slack.
    private func waitUntil(_ condition: () -> Bool, timeout: Duration = .seconds(2)) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        while !condition() && clock.now < deadline { try await Task.sleep(for: .milliseconds(10)) }
    }

    func testRepeatedDisconnectDoesNotExtendDeadline() async throws {
        var failures = 0
        let recovery = VoiceConnectionRecovery(timeout: .milliseconds(60)) { failures += 1 }
        recovery.disconnected()
        try await Task.sleep(for: .milliseconds(40))
        recovery.disconnected()
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertEqual(failures, 1)
    }

    func testClosingAndStartingAnotherCallCancelsOldTimer() async throws {
        var failures = 0
        let recovery = VoiceConnectionRecovery(timeout: .milliseconds(40)) { failures += 1 }
        recovery.disconnected()
        recovery.connected() // close / disconnect
        recovery.disconnected() // next call
        recovery.connected()
        try await Task.sleep(for: .milliseconds(70))
        XCTAssertEqual(failures, 0)
    }
}
