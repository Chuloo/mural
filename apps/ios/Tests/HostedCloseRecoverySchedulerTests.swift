import XCTest
@testable import MuralCore

@MainActor final class HostedCloseRecoverySchedulerTests: XCTestCase {
    func testDelayedRetryRunsWithoutCancellingItsOwnTask() async {
        let scheduler = HostedCloseRecoveryScheduler()
        let retried = expectation(description: "Delayed recovery executes")
        scheduler.schedule(after: .milliseconds(10)) {
            XCTAssertFalse(Task.isCancelled)
            await scheduler.run {
                XCTAssertFalse(Task.isCancelled)
                retried.fulfill()
            }
            XCTAssertFalse(Task.isCancelled)
        }
        await fulfillment(of: [retried], timeout: 2)
    }

    func testNewCloseWhileARequestIsSuspendedIsRecoveredAfterward() async {
        let scheduler = HostedCloseRecoveryScheduler()
        let started = expectation(description: "First close request started")
        var release: CheckedContinuation<Void, Never>?
        var pending = Set(["first"])
        var passes = 0
        var concurrent = 0
        var maximumConcurrent = 0
        let first = Task {
            await scheduler.run {
                passes += 1; concurrent += 1; maximumConcurrent = max(maximumConcurrent, concurrent)
                let snapshot = pending
                await withCheckedContinuation { continuation in release = continuation; started.fulfill() }
                pending.subtract(snapshot)
                concurrent -= 1
            }
        }
        await fulfillment(of: [started], timeout: 2)
        pending.insert("second")
        // A second resume must not block on the already-running network request.
        await scheduler.run {
            passes += 1; concurrent += 1; maximumConcurrent = max(maximumConcurrent, concurrent)
            pending.removeAll()
            concurrent -= 1
        }
        XCTAssertEqual(passes, 1)
        release?.resume()
        await first.value
        XCTAssertTrue(pending.isEmpty)
        XCTAssertEqual(passes, 2)
        XCTAssertEqual(maximumConcurrent, 1)
    }

    func testBurstOfWakeupsCoalescesAndSurvivesCallerCancellation() async {
        let scheduler = HostedCloseRecoveryScheduler()
        let started = expectation(description: "First pass started")
        var release: CheckedContinuation<Void, Never>?
        var passes = 0
        let first = Task {
            await scheduler.run {
                passes += 1
                await withCheckedContinuation { continuation in release = continuation; started.fulfill() }
            }
        }
        await fulfillment(of: [started], timeout: 2)
        first.cancel()
        for _ in 0..<10 {
            await scheduler.run {
                XCTAssertFalse(Task.isCancelled)
                passes += 1
            }
        }
        release?.resume()
        await first.value
        XCTAssertEqual(passes, 2)
    }

    func testPauseCancelsOnlyTheScheduledRetry() async throws {
        let scheduler = HostedCloseRecoveryScheduler()
        var retryCount = 0
        scheduler.schedule(after: .milliseconds(10)) { retryCount += 1 }
        scheduler.pause()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(retryCount, 0)
        await scheduler.run { retryCount += 1 }
        XCTAssertEqual(retryCount, 1)
    }
}
