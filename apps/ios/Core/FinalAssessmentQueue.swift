import Foundation

public struct FinalAssessmentResult: Sendable {
    public let sessionID: UUID
    public let languageID: String
    public let assessment: Assessment
    public let inputTokens: Int
    public let outputTokens: Int
    public let searchCalls: Int

    public init(sessionID: UUID, languageID: String, assessment: Assessment, inputTokens: Int = 0, outputTokens: Int = 0, searchCalls: Int = 0) {
        self.sessionID = sessionID; self.languageID = languageID; self.assessment = assessment
        self.inputTokens = inputTokens; self.outputTokens = outputTokens; self.searchCalls = searchCalls
    }

    /// Apply only to the original saved transcript, which may have changed or been deleted.
    public func applying(to current: SessionRecord?) -> SessionRecord? {
        guard var current, current.id == sessionID, current.languageID == languageID, current.endedAt != nil,
              let validated = LearningEngine.validate(assessment, session: current),
              !current.assessments.contains(where: { $0.passageID == assessment.passageID && $0.revisionKey == assessment.revisionKey }) else { return nil }
        current.assessments.removeAll { $0.passageID == validated.passageID }
        current.assessments.append(validated)
        current.inputTokens += inputTokens; current.outputTokens += outputTokens; current.searchCalls += searchCalls
        return current
    }
}

/// Finishes the latest unassessed user passage without owning the visible conversation.
@MainActor public final class FinalAssessmentQueue {
    public var onResult: ((FinalAssessmentResult) -> Void)?
    private let assess: (SessionRecord, Passage) async throws -> FinalAssessmentResult
    private let timeout: TimeInterval
    private struct Job: Sendable {
        let token: UUID
        let deadline: Date
        let request: Task<Void, Never>
        let timer: Task<Void, Never>
    }
    private var jobs: [UUID: Job] = [:]

    public init(timeout: TimeInterval = 15, assess: @escaping (SessionRecord, Passage) async throws -> FinalAssessmentResult) {
        self.timeout = min(15, max(0.001, timeout)); self.assess = assess
    }
    deinit { for job in jobs.values { job.request.cancel(); job.timer.cancel() } }

    @discardableResult public func submit(_ session: SessionRecord) -> Bool {
        guard session.endedAt != nil, jobs[session.id] == nil,
              let passage = session.passages.last(where: { $0.speaker == .user }), passage.text.count >= 3,
              !session.assessments.contains(where: { $0.passageID == passage.id && $0.revisionKey == passage.revisionKey }) else { return false }
        let token = UUID(), deadline = Date().addingTimeInterval(timeout), assess = self.assess
        let request = Task { [weak self] in
            do {
                let result = try await assess(session, passage)
                guard !Task.isCancelled, let self, let job = self.jobs[session.id], job.token == token,
                      Date() <= job.deadline, result.sessionID == session.id, result.languageID == session.languageID else { return }
                self.jobs.removeValue(forKey: session.id)?.timer.cancel()
                self.onResult?(result)
            } catch {
                guard let self, self.jobs[session.id]?.token == token else { return }
                self.jobs.removeValue(forKey: session.id)?.timer.cancel()
            }
        }
        let timer = Task { [weak self, timeout] in
            do { try await Task.sleep(for: .seconds(timeout)) } catch { return }
            guard self?.jobs[session.id]?.token == token else { return }
            self?.cancel(session.id)
        }
        jobs[session.id] = Job(token: token, deadline: deadline, request: request, timer: timer)
        return true
    }
    public func isPending(_ id: UUID) -> Bool { jobs[id] != nil }
    public func cancel(_ id: UUID) {
        guard let job = jobs.removeValue(forKey: id) else { return }
        job.request.cancel(); job.timer.cancel()
    }
}
