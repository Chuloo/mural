import Foundation
import Observation

public struct MeaningRequest: Equatable, Sendable {
    public let sessionID: UUID
    public let passageID: String
    public let revisionKey: String
    public let text: String
    public let learningLanguageID: String
    public let meaningLanguage: String
    public init(sessionID: UUID, passage: Passage, learningLanguageID: String, meaningLanguage: String) {
        self.sessionID = sessionID; passageID = passage.id; revisionKey = passage.revisionKey
        text = passage.text; self.learningLanguageID = learningLanguageID; self.meaningLanguage = meaningLanguage
    }
    public var cacheKey: String { Self.cacheKey(revisionKey: revisionKey, language: meaningLanguage) }
    public static func cacheKey(revisionKey: String, language: String) -> String { "caption2/" + language + "::" + revisionKey }
    /// Caption text sent to the translation helper. Must match what the learner sees for this revision.
    public var translationInput: String { Self.translationInput(for: text) }
    public static func translationInput(for text: String) -> String { text }
    func sharesContext(with other: Self) -> Bool {
        sessionID == other.sessionID && passageID == other.passageID &&
        learningLanguageID == other.learningLanguageID && meaningLanguage == other.meaningLanguage
    }
}

public struct MeaningResult: Sendable {
    public let text: String
    public let inputTokens: Int
    public let outputTokens: Int
    public init(text: String, inputTokens: Int = 0, outputTokens: Int = 0) {
        self.text = text; self.inputTokens = inputTokens; self.outputTokens = outputTokens
    }
}

/// Keeps one translation in flight while coalescing growing transcript fragments.
@MainActor @Observable public final class MeaningController {
    public private(set) var text = ""
    public private(set) var isLoading = false
    public private(set) var error: String?
    @ObservationIgnored public var onResult: ((MeaningRequest, MeaningResult) -> Void)?
    @ObservationIgnored private let translate: @MainActor (MeaningRequest, @escaping @MainActor (String) -> Void) async throws -> MeaningResult
    @ObservationIgnored private let delay: Duration
    @ObservationIgnored private var desired: MeaningRequest?
    @ObservationIgnored private var rendered: MeaningRequest?
    @ObservationIgnored private var displayed: MeaningRequest?
    @ObservationIgnored private var lastDispatchedAt: ContinuousClock.Instant?
    @ObservationIgnored private var translationID: UUID?
    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()

    public init(delay: Duration = .milliseconds(450), translate: @escaping @MainActor (MeaningRequest) async throws -> MeaningResult) {
        self.delay = delay; self.translate = { request, _ in try await translate(request) }
    }
    public init(delay: Duration = .milliseconds(450), streaming: @escaping @MainActor (MeaningRequest, @escaping @MainActor (String) -> Void) async throws -> MeaningResult) {
        self.delay = delay; self.translate = streaming
    }
    deinit { worker?.cancel() }

    public func update(_ request: MeaningRequest, cached: String? = nil) {
        let changedContext = desired.map { !$0.sharesContext(with: request) } ?? true
        if changedContext { reset() }
        desired = request
        if let cached, !cached.isEmpty {
            cancelWorker(); text = cached; rendered = request; displayed = request; error = nil; return
        }
        if rendered == request { return }
        // Do not display a translation of text that was subsequently corrected.
        if let displayed, !request.text.hasPrefix(displayed.text) { text = ""; self.rendered = nil; self.displayed = nil }
        if worker == nil && error == nil { begin() }
    }
    public func reset() {
        cancelWorker(); desired = nil; rendered = nil; displayed = nil; lastDispatchedAt = nil; text = ""; error = nil
    }
    public func retry() {
        guard desired != nil else { return }
        cancelWorker(); error = nil; begin()
    }
    private func cancelWorker() {
        generation = UUID(); translationID = nil; worker?.cancel(); worker = nil; isLoading = false
    }
    private func begin() {
        guard desired != nil, worker == nil else { return }
        isLoading = true
        let token = generation
        worker = Task { [weak self] in
            guard let self else { return }
            do {
                let elapsed = self.lastDispatchedAt.map { $0.duration(to: .now) } ?? .zero
                try await Task.sleep(for: max(.zero, self.delay - elapsed))
                guard token == self.generation, !Task.isCancelled, let request = self.desired else { return }
                self.lastDispatchedAt = .now
                let translationID = UUID(); self.translationID = translationID
                // Retain the readable prefix until the next stream has caught up.
                let minimumPartialLength = self.text.count
                let result = try await self.translate(request) { [weak self] partial in
                    guard let self, token == self.generation, self.translationID == translationID, !Task.isCancelled,
                          let latest = self.desired, latest.sharesContext(with: request),
                          latest.text.hasPrefix(request.text), !partial.isEmpty, partial.count >= minimumPartialLength else { return }
                    self.text = partial; self.displayed = request
                }
                guard token == self.generation, !Task.isCancelled, let latest = self.desired else { return }
                self.translationID = nil
                guard !result.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw MeaningError.empty }
                self.onResult?(request, result)
                if latest.sharesContext(with: request), latest.text.hasPrefix(request.text) {
                    self.text = result.text; self.rendered = request; self.displayed = request
                }
                self.worker = nil; self.isLoading = false
                if latest != request { self.begin() }
            } catch {
                guard token == self.generation, !Task.isCancelled else { return }
                self.translationID = nil; self.worker = nil; self.isLoading = false
                if self.rendered != self.desired { self.text = ""; self.displayed = nil }
                self.error = error.localizedDescription
            }
        }
    }
    private enum MeaningError: LocalizedError {
        case empty
        var errorDescription: String? { "The translation came back empty. Please try again." }
    }
}
