import Foundation

/// Serializes recovery passes and preserves a wakeup received during an awaited request.
@MainActor public final class HostedCloseRecoveryScheduler {
    public typealias Operation = @MainActor () async -> Void
    private var worker: Task<Void, Never>?
    private var requested: Operation?
    private var retry: (id: UUID, task: Task<Void, Never>)?

    public init() { }
    deinit { worker?.cancel(); retry?.task.cancel() }

    public func pause() {
        retry?.task.cancel(); retry = nil
    }

    public func run(_ operation: @escaping Operation) async {
        // Each operation rereads the durable queue, so a burst needs one additional pass.
        requested = operation
        pause()
        guard worker == nil else { return }
        let job = Task { [weak self] in
            guard let self else { return }
            while let operation = self.requested {
                self.requested = nil
                self.pause()
                await operation()
            }
            self.worker = nil
        }
        worker = job
        // The worker owns recovery independently of a view's task cancellation.
        await job.value
    }

    public func schedule(after delay: Duration, _ operation: @escaping Operation) {
        pause()
        let id = UUID()
        let task = Task { [weak self] in
            do { try await Task.sleep(for: delay) } catch { return }
            guard !Task.isCancelled, let self, self.retry?.id == id else { return }
            // Clear before entering run(), whose pause must not cancel this executing task.
            self.retry = nil
            await operation()
        }
        retry = (id, task)
    }
}
