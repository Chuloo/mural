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

public protocol MeaningRetryGuidance: Error {
    var retryMeaningAllowed: Bool { get }
}

/// Waits for a sentence or quiet transcript and keeps one translation in flight.
@MainActor @Observable public final class MeaningController {
    public private(set) var text = ""
    public private(set) var isLoading = false
    public private(set) var error: String?
    public private(set) var canRetry = true
    @ObservationIgnored public var onResult: ((MeaningRequest, MeaningResult) -> Void)?
    @ObservationIgnored private let translate: @MainActor (MeaningRequest, @escaping @MainActor (String) -> Void) async throws -> MeaningResult
    @ObservationIgnored private let delay: Duration
    @ObservationIgnored private let incompleteDelay: Duration
    @ObservationIgnored private let minimumSpacing: Duration
    @ObservationIgnored private var desired: MeaningRequest?
    @ObservationIgnored private var rendered: MeaningRequest?
    @ObservationIgnored private var displayed: MeaningRequest?
    @ObservationIgnored private var lastDispatchedAt: ContinuousClock.Instant?
    @ObservationIgnored private var desiredUpdatedAt: ContinuousClock.Instant?
    @ObservationIgnored private var finalRequested = false
    @ObservationIgnored private var translationID: UUID?
    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private var translating = false
    @ObservationIgnored private var generation = UUID()

    public init(delay: Duration = .milliseconds(450), incompleteDelay: Duration = .milliseconds(1800),
                minimumSpacing: Duration = .milliseconds(2500),
                translate: @escaping @MainActor (MeaningRequest) async throws -> MeaningResult) {
        self.delay = delay; self.incompleteDelay = incompleteDelay; self.minimumSpacing = minimumSpacing
        self.translate = { request, _ in try await translate(request) }
    }
    public init(delay: Duration = .milliseconds(450), incompleteDelay: Duration = .milliseconds(1800),
                minimumSpacing: Duration = .milliseconds(2500),
                streaming: @escaping @MainActor (MeaningRequest, @escaping @MainActor (String) -> Void) async throws -> MeaningResult) {
        self.delay = delay; self.incompleteDelay = incompleteDelay; self.minimumSpacing = minimumSpacing
        self.translate = streaming
    }
    deinit { worker?.cancel() }

    public func update(_ request: MeaningRequest, cached: String? = nil, conversationEnded: Bool = false) {
        let changedContext = desired.map { !$0.sharesContext(with: request) } ?? true
        if changedContext {
            let sameConversation = desired.map {
                $0.sessionID == request.sessionID && $0.learningLanguageID == request.learningLanguageID &&
                $0.meaningLanguage == request.meaningLanguage
            } ?? false
            if translating && sameConversation {
                // Let an admitted request finish before asking for the next passage.
                rendered = nil; displayed = nil; text = ""; error = nil; canRetry = true; finalRequested = false
            } else {
                let priorDispatch = sameConversation ? lastDispatchedAt : nil
                reset()
                lastDispatchedAt = priorDispatch
            }
        }
        if desired != request { desiredUpdatedAt = .now; finalRequested = false }
        desired = request
        if conversationEnded && !finalRequested {
            finalRequested = true
            // A waiting quiet window must wake when the conversation ends.
            // An admitted request still finishes before another is sent.
            if !translating { cancelWorker() }
        }
        if let cached, !cached.isEmpty {
            if !translating { cancelWorker() }
            text = cached; rendered = request; displayed = request; error = nil; canRetry = true
            isLoading = false; return
        }
        if rendered == request { return }
        // Do not display a translation of text that was subsequently corrected.
        if let displayed, !request.text.hasPrefix(displayed.text) { text = ""; self.rendered = nil; self.displayed = nil }
        if worker == nil && error == nil { begin() }
    }
    public func reset() {
        cancelWorker(); desired = nil; rendered = nil; displayed = nil; lastDispatchedAt = nil; desiredUpdatedAt = nil
        finalRequested = false; text = ""; error = nil; canRetry = true
    }
    public func retry() {
        guard desired != nil, !translating else { return }
        guard canRetry else { return }
        cancelWorker(); error = nil; finalRequested = true; begin()
    }
    #if DEBUG
    public func preparePreviewFailure(_ message: String, canRetry: Bool) {
        cancelWorker(); text = ""; error = message; self.canRetry = canRetry
    }
    #endif
    private func cancelWorker() {
        generation = UUID(); translationID = nil; worker?.cancel(); worker = nil; isLoading = false; translating = false
    }
    private func begin() {
        guard desired != nil, rendered != desired, worker == nil else { return }
        isLoading = true
        let token = generation
        worker = Task { [weak self] in
            guard let self else { return }
            var dispatched: MeaningRequest?
            do {
                while true {
                    guard token == self.generation, !Task.isCancelled, let pending = self.desired else { return }
                    let now = ContinuousClock.now
                    let quietUntil = (self.desiredUpdatedAt ?? now) + (self.finalRequested ? .zero : Self.endsSentence(pending.text, languageID: pending.learningLanguageID) ? self.delay : self.incompleteDelay)
                    let pacedUntil = self.lastDispatchedAt.map { $0 + self.minimumSpacing } ?? now
                    let wait = max(max(.zero, now.duration(to: quietUntil)), max(.zero, now.duration(to: pacedUntil)))
                    if wait == .zero { break }
                    try await Task.sleep(for: wait)
                }
                guard token == self.generation, !Task.isCancelled, let request = self.desired else { return }
                self.lastDispatchedAt = .now
                let translationID = UUID(); self.translationID = translationID
                self.translating = true; dispatched = request
                // Retain the readable prefix until the next stream has caught up.
                let minimumPartialLength = self.text.count
                let result = try await self.translate(request) { [weak self] partial in
                    guard let self, token == self.generation, self.translationID == translationID, !Task.isCancelled,
                          let latest = self.desired, self.rendered != latest, latest.sharesContext(with: request),
                          latest.text.hasPrefix(request.text), !partial.isEmpty, partial.count >= minimumPartialLength else { return }
                    self.text = partial; self.displayed = request
                }
                guard token == self.generation, !Task.isCancelled, let latest = self.desired else { return }
                self.translationID = nil
                guard !result.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw MeaningError.empty }
                self.onResult?(request, result)
                if self.rendered != latest, latest.sharesContext(with: request), latest.text.hasPrefix(request.text) {
                    self.text = result.text; self.rendered = request; self.displayed = request
                }
                self.worker = nil; self.isLoading = false; self.translating = false
                self.canRetry = true
                if latest != request { self.begin() }
            } catch {
                guard token == self.generation, !Task.isCancelled else { return }
                self.translationID = nil; self.worker = nil; self.isLoading = false; self.translating = false
                if self.rendered == self.desired { self.error = nil; return }
                if let dispatched, let latest = self.desired, !latest.sharesContext(with: dispatched) {
                    self.begin(); return
                }
                if self.rendered != self.desired { self.text = ""; self.displayed = nil }
                self.error = error.localizedDescription
                self.canRetry = (error as? MeaningRetryGuidance)?.retryMeaningAllowed ?? true
            }
        }
    }
    private static func endsSentence(_ text: String, languageID: String) -> Bool {
        let ending = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "\"'”’»)"))
        return ending.last.map { ".!?。！？…".contains($0) || (languageID == "el" && ";;".contains($0)) } ?? false
    }
    private enum MeaningError: LocalizedError {
        case empty
        var errorDescription: String? { "The translation came back empty. Please try again." }
    }
}
