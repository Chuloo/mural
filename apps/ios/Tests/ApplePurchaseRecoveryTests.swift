import Foundation
import XCTest
@testable import MuralCore

final class ApplePurchaseRecoveryTests: XCTestCase, @unchecked Sendable {
    private struct Purchase {
        let orderID: UUID
    }
    @MainActor private final class Store {
        var unfinished: [Purchase] = []
        var finished: [Purchase] = []
        var unfinishedReads = 0
        var finishedReads = 0
        func nextUnfinished() -> Purchase? {
            unfinishedReads += 1
            return unfinished.isEmpty ? nil : unfinished.removeFirst()
        }
        func nextFinished() -> Purchase? {
            finishedReads += 1
            return finished.isEmpty ? nil : finished.removeFirst()
        }
    }
    @MainActor private final class Account {
        let owner = UUID()
        var current = true
        var saved: [ApplePurchaseAttempt] = []
        var statuses: [UUID: ApplePurchaseStatus] = [:]
        var statusRequests: [UUID] = []
        var recoveryRequests: [UUID] = []
        var finishedOrders: [UUID] = []
        var message: String?
        func status(_ order: UUID) -> ApplePurchaseStatus? {
            statusRequests.append(order)
            return statuses[order]
        }
        func recoverSaved() {
            for attempt in saved {
                guard let order = attempt.orderID, let recorded = status(order),
                      attempt.isFulfilled(by: recorded, for: owner) else { continue }
                saved.removeAll { $0.orderID == order }
                message = ApplePurchaseScope.completionMessage(testPurchase: true)
            }
        }
        func deliver(_ purchase: Purchase) {
            guard let recorded = status(purchase.orderID), recorded.fulfillmentRecorded else {
                recoveryRequests.append(purchase.orderID)
                message = "Your purchase is saved. We’ll check it again when Mural can connect."
                return
            }
            finishedOrders.append(purchase.orderID)
            saved.removeAll { $0.orderID == purchase.orderID }
            message = ApplePurchaseScope.completionMessage(testPurchase: true)
        }
    }
    private func offer() throws -> MinuteOffer {
        let fields: [String: Any] = ["sku": "small", "providerProduct": "chat.mural.ios.minutes.small.v1",
            "currency": "usd", "currencyExponent": 2, "totalMinor": 700, "estimatedMilliseconds": 2_214_000,
            "estimateRateVersion": "test-rate", "scheduleVersion": "test-schedule", "storefront": "USA", "environment": "test"]
        return try JSONDecoder().decode(MinuteOffer.self, from: JSONSerialization.data(withJSONObject: fields))
    }
    private func status(_ order: UUID, fulfilled: Bool) throws -> ApplePurchaseStatus {
        try JSONDecoder().decode(ApplePurchaseStatus.self, from: JSONSerialization.data(withJSONObject:
            ["orderID": order.uuidString, "entitlementKind": "ai_value", "state": fulfilled ? "purchased" : "pending",
             "fulfillmentRecorded": fulfilled]))
    }
    @MainActor private func attempt(owner: UUID, order: UUID) throws -> ApplePurchaseAttempt {
        var attempt = try ApplePurchaseAttempt(accountID: owner, offer: offer(), quantity: 1)
        attempt.orderID = order
        attempt.phase = .submitted
        return attempt
    }
    @MainActor func testOwnedRecoveryIsNotMaskedByForeignFinishedHistoryOrReplayedToServer() async throws {
        let account = Account(), store = Store(), ownedOrder = UUID()
        account.saved = [try attempt(owner: account.owner, order: ownedOrder)]
        account.statuses[ownedOrder] = try status(ownedOrder, fulfilled: true)
        let foreignHistory = (0..<3).map { _ in Purchase(orderID: UUID()) }
        store.finished = [Purchase(orderID: ownedOrder)] + foreignHistory

        let completed = await ApplePurchaseRecovery.check(recoverSavedOrders: { account.recoverSaved() },
            nextUnfinished: { store.nextUnfinished() }, isCurrent: { account.current }, deliver: { account.deliver($0) })

        XCTAssertTrue(completed)
        XCTAssertEqual(account.statusRequests, [ownedOrder])
        XCTAssertTrue(account.recoveryRequests.isEmpty, "Finished history must never invoke Apple recovery.")
        XCTAssertEqual(store.finishedReads, 0)
        XCTAssertEqual(store.finished.count, 4)
        XCTAssertTrue(account.saved.isEmpty)
        XCTAssertEqual(account.message, ApplePurchaseScope.completionMessage(testPurchase: true))
    }
    @MainActor func testOwnedUnfinishedPurchaseUsesRecordedGrantAndFinishesOnce() async throws {
        let account = Account(), store = Store(), ownedOrder = UUID()
        account.saved = [try attempt(owner: account.owner, order: ownedOrder)]
        account.statuses[ownedOrder] = try status(ownedOrder, fulfilled: true)
        store.unfinished = [Purchase(orderID: ownedOrder)]
        store.finished = [Purchase(orderID: UUID())]
        let completed = await ApplePurchaseRecovery.check(recoverSavedOrders: { account.recoverSaved() },
            nextUnfinished: { store.nextUnfinished() }, isCurrent: { account.current }, deliver: { account.deliver($0) })
        XCTAssertTrue(completed)
        XCTAssertEqual(account.statusRequests, [ownedOrder, ownedOrder])
        XCTAssertEqual(account.finishedOrders, [ownedOrder])
        XCTAssertTrue(account.recoveryRequests.isEmpty)
        XCTAssertEqual(store.finishedReads, 0)
        XCTAssertTrue(account.saved.isEmpty)
    }
    @MainActor func testPendingAndFailedUnfinishedPurchasesRetainSavedRecovery() async throws {
        let account = Account(), store = Store(), pendingOrder = UUID(), unavailableOrder = UUID()
        let pending = try attempt(owner: account.owner, order: pendingOrder)
        let unavailable = try attempt(owner: account.owner, order: unavailableOrder)
        account.saved = [pending, unavailable]
        account.statuses[pendingOrder] = try status(pendingOrder, fulfilled: false)
        store.unfinished = [Purchase(orderID: unavailableOrder)]
        let completed = await ApplePurchaseRecovery.check(recoverSavedOrders: { account.recoverSaved() },
            nextUnfinished: { store.nextUnfinished() }, isCurrent: { account.current }, deliver: { account.deliver($0) })
        XCTAssertTrue(completed)
        XCTAssertEqual(account.saved, [pending, unavailable])
        XCTAssertEqual(account.recoveryRequests, [unavailableOrder])
        XCTAssertTrue(account.finishedOrders.isEmpty)
        XCTAssertEqual(account.message, "Your purchase is saved. We’ll check it again when Mural can connect.")
    }
    @MainActor func testAccountSwitchAfterSavedCheckStopsUnfinishedDelivery() async {
        let account = Account(), store = Store()
        store.unfinished = [Purchase(orderID: UUID())]
        let completed = await ApplePurchaseRecovery.check(recoverSavedOrders: { account.current = false },
            nextUnfinished: { store.nextUnfinished() }, isCurrent: { account.current }, deliver: { account.deliver($0) })
        XCTAssertFalse(completed)
        XCTAssertEqual(store.unfinishedReads, 0)
        XCTAssertTrue(account.statusRequests.isEmpty)
        XCTAssertTrue(account.recoveryRequests.isEmpty)
    }
    @MainActor func testAccountSwitchWhileReadingUnfinishedStopsDelivery() async {
        let account = Account()
        let completed = await ApplePurchaseRecovery.check(recoverSavedOrders: {}, nextUnfinished: {
            account.current = false
            return Purchase(orderID: UUID())
        }, isCurrent: { account.current }, deliver: { account.deliver($0) })
        XCTAssertFalse(completed)
        XCTAssertTrue(account.statusRequests.isEmpty)
        XCTAssertTrue(account.recoveryRequests.isEmpty)
    }
    @MainActor func testCancellationCannotStartRecoveryRequests() async {
        let account = Account(), store = Store()
        let task = Task { @MainActor in
            withUnsafeCurrentTask { $0?.cancel() }
            return await ApplePurchaseRecovery.check(recoverSavedOrders: { account.recoverSaved() },
                nextUnfinished: { store.nextUnfinished() }, isCurrent: { account.current }, deliver: { account.deliver($0) })
        }
        let completed = await task.value
        XCTAssertFalse(completed)
        XCTAssertEqual(store.unfinishedReads, 0)
        XCTAssertTrue(account.statusRequests.isEmpty)
        XCTAssertTrue(account.recoveryRequests.isEmpty)
    }
}
