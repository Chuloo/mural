#if DEBUG && targetEnvironment(simulator)
import Foundation
import MuralCore

/// Sample content for native simulator captures. Never loaded on a physical device.
@MainActor enum ScreenshotPreview {
    enum Screen: String { case greeting, conversation, themes, words }
    static var screen: Screen? {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains("--preview"),
              let argument = arguments.first(where: { $0.hasPrefix("--screenshot=") }) else { return nil }
        return Screen(rawValue: String(argument.dropFirst("--screenshot=".count)))
    }
    static var tab: Int { screen == .themes ? 1 : screen == .words ? 2 : 0 }

    static func seedWords(_ store: LearningStore) {
        let samples: [(lemma: String, meaning: String, form: String, quote: String, days: [Int])] = [
            ("me apetece", "I feel like", "me apetece", "Hoy me apetece tomar un café.", [9, 4, 0]),
            ("la sobremesa", "conversation after a meal", "sobremesa", "Me encanta la sobremesa con amigos.", [0]),
            ("quedar", "to meet up", "quedar", "Podemos quedar el sábado.", [3, 0]),
            ("pasear", "to go for a walk", "pasear", "Me gusta pasear por el barrio.", [9, 4, 0])
        ]
        for (index, sample) in samples.enumerated() {
            for (visit, day) in sample.days.enumerated() {
                let date = Date().addingTimeInterval(-Double(day) * 86400 - Double(index) * 60)
                let context = visit.isMultiple(of: 2) ? "coffee" : "weekend"
                var record = SessionRecord(languageID: "es", themeID: context)
                record.startedAt = date; record.endedAt = date.addingTimeInterval(60)
                record.append(Fragment(speaker: .user, text: sample.quote, startMS: 0, endMS: 3000, receivedAt: date))
                let passage = record.passages[0]
                record.assessments = [Assessment(passageID: passage.id, revisionKey: passage.revisionKey,
                    outcome: .success, suggestedLevel: 1, nextGoal: "Hablar de planes cotidianos.", capability: "",
                    words: [WordProposal(lemma: sample.lemma, meaning: sample.meaning, form: sample.form,
                        kind: .independent, confidence: 0.95, sourceIDs: passage.fragments.map(\.id), quote: sample.quote, language: "es")],
                    createdAt: date, context: context)]
                store.save(record)
            }
        }
    }
}
#endif
