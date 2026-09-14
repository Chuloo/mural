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
    public static func cacheKey(revisionKey: String, language: String) -> String { language + "::" + revisionKey }
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
    @ObservationIgnored private let translate: @MainActor (MeaningRequest) async throws -> MeaningResult
    @ObservationIgnored private let delay: Duration
    @ObservationIgnored private var desired: MeaningRequest?
    @ObservationIgnored private var rendered: MeaningRequest?
    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()

    public init(delay: Duration = .milliseconds(450), translate: @escaping @MainActor (MeaningRequest) async throws -> MeaningResult) {
        self.delay = delay; self.translate = translate
    }
    deinit { worker?.cancel() }

    public func update(_ request: MeaningRequest, cached: String? = nil) {
        let changedContext = desired.map { !$0.sharesContext(with: request) } ?? true
        if changedContext { reset() }
        desired = request
        if let cached, !cached.isEmpty {
            cancelWorker(); text = cached; rendered = request; error = nil; return
        }
        if rendered == request { return }
        // Do not display a translation of text that was subsequently corrected.
        if let rendered, !request.text.hasPrefix(rendered.text) { text = ""; self.rendered = nil }
        if worker == nil && error == nil { begin() }
    }
    public func reset() {
        cancelWorker(); desired = nil; rendered = nil; text = ""; error = nil
    }
    public func retry() {
        guard desired != nil else { return }
        cancelWorker(); error = nil; begin()
    }
    private func cancelWorker() {
        generation = UUID(); worker?.cancel(); worker = nil; isLoading = false
    }
    private func begin() {
        guard desired != nil, worker == nil else { return }
        isLoading = true
        let token = generation
        worker = Task { [weak self] in
            guard let self else { return }
            do {
                try await Task.sleep(for: self.delay)
                guard token == self.generation, !Task.isCancelled, let request = self.desired else { return }
                let result = try await self.translate(request)
                guard token == self.generation, !Task.isCancelled, let latest = self.desired else { return }
                guard !result.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw MeaningError.empty }
                self.onResult?(request, result)
                if latest.sharesContext(with: request), latest.text.hasPrefix(request.text) {
                    self.text = result.text; self.rendered = request
                }
                self.worker = nil; self.isLoading = false
                if latest != request { self.begin() }
            } catch {
                guard token == self.generation, !Task.isCancelled else { return }
                self.worker = nil; self.isLoading = false
                self.error = error.localizedDescription
            }
        }
    }
    private enum MeaningError: LocalizedError {
        case empty
        var errorDescription: String? { "The translation came back empty. Please try again." }
    }
}
