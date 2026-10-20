import Foundation

/// Finished history belongs to the history screen, never the delivery retry queue.
public enum ApplePurchaseRecovery {
    @MainActor public static func check<Purchase>(
        recoverSavedOrders: @MainActor () async -> Void,
        nextUnfinished: @MainActor () async -> Purchase?,
        isCurrent: @MainActor () -> Bool,
        deliver: @MainActor (Purchase) async -> Void
    ) async -> Bool {
        guard isCurrent(), !Task.isCancelled else { return false }
        await recoverSavedOrders()
        while isCurrent(), !Task.isCancelled {
            guard let purchase = await nextUnfinished() else {
                return isCurrent() && !Task.isCancelled
            }
            guard isCurrent(), !Task.isCancelled else { return false }
            await deliver(purchase)
        }
        return false
    }
}
