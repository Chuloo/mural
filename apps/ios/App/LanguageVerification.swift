#if DEBUG
import Foundation
import NaturalLanguage
import AVFoundation
import WebRTC
import MuralCore

extension AudioVerification {
    /// Explicit device check using synthetic typed turns and the existing in-memory verification store.
    /// The saved key stays inside the app; diagnostics contain no transcript or audio.
    @MainActor static func verifyLanguageFlow(_ coordinator: ConversationCoordinator) async {
        struct Report: Encodable {
            var languageID: String
            var status = "running"
            var connected = false
            var receivedGreeting = false
            var targetLanguageDetected = false
            var typedReplies = 0
            var translated = false
            var lookupReturned = false
            var pinyinAvailable = false
            var supportedEvidenceOnly = false
            var archiveRoundTrip = false
            var switchedAwayAndBack = false
            var cachedMeaningAfterEnd = false
            var closed = false
            var audioReleased = false
            var peakAudioLevel = 0.0
            var outputPorts: Set<String> = []
            var failure: String?
            var passed: Bool {
                connected && receivedGreeting && targetLanguageDetected && typedReplies == 2 && translated &&
                lookupReturned && (languageID != "zh" || pinyinAvailable) && supportedEvidenceOnly &&
                archiveRoundTrip && switchedAwayAndBack && cachedMeaningAfterEnd && closed && audioReleased &&
                peakAudioLevel > 0.001 && outputPorts.contains(AVAudioSession.Port.builtInSpeaker.rawValue) && failure == nil
            }
        }
        let id = coordinator.language.id
        var report = Report(languageID: id)
        let destination = URL.documentsDirectory.appendingPathComponent("language-verification-\(id).json")
        func write() {
            // Encode computed pass status explicitly alongside the report.
            struct Output: Encodable { let passed: Bool; let report: Report }
            if let data = try? JSONEncoder().encode(Output(passed: report.passed, report: report)) {
                try? data.write(to: destination, options: .atomic)
            }
        }
        func sampleAudio() {
            report.peakAudioLevel = max(report.peakAudioLevel, coordinator.outputLevel)
            if coordinator.outputLevel > 0.001 {
                report.outputPorts.formUnion(AVAudioSession.sharedInstance().currentRoute.outputs.map { $0.portType.rawValue })
            }
        }
        func waitFor(_ timeout: Double, condition: () -> Bool) async -> Bool {
            let deadline = Date().addingTimeInterval(timeout)
            while Date() < deadline && !Task.isCancelled {
                sampleAudio()
                if condition() { return true }
                if coordinator.error != nil || coordinator.showSettings || coordinator.state == .failed { return false }
                do { try await Task.sleep(for: .milliseconds(100)) } catch { return false }
            }
            return false
        }
        func settleCaption() async {
            var previous = coordinator.caption
            var stableSince = Date()
            _ = await waitFor(20) {
                if coordinator.caption != previous { previous = coordinator.caption; stableSince = .now }
                return Date().timeIntervalSince(stableSince) > 2 && coordinator.outputLevel < 0.001
            }
        }
        write()
        coordinator.selectMeaningLanguage("English")
        coordinator.store.updatePreferences { $0.meaningVisible = true }
        coordinator.chooseTheme(coordinator.language.themes.first { $0.id == "coffee" })
        coordinator.start()
        report.connected = await waitFor(45) { coordinator.state == .active }
        if report.connected {
            if !coordinator.isMuted { coordinator.toggleMute() }
            report.receivedGreeting = await waitFor(30) { coordinator.assistantPassage != nil }
            await settleCaption()
            // One support-language beginner request, then a target-language question with more complex syntax.
            let advanced = [
                "de": "Wenn du ein Café eröffnen würdest, wie würdest du regionale Zutaten und bezahlbare Preise miteinander vereinbaren?",
                "it": "Se aprissi un bar, come riusciresti a usare ingredienti locali mantenendo prezzi accessibili?",
                "pt": "Se você abrisse uma cafeteria, como conciliaria ingredientes locais com preços acessíveis?",
                "zh": "如果你开一家咖啡馆，你会怎样在使用本地食材和保持价格合理之间取得平衡？",
                "af": "As jy 'n koffiewinkel sou oopmaak, hoe sou jy plaaslike bestanddele met bekostigbare pryse versoen?"
            ]
            for reply in ["I am learning. How can I politely order a coffee?", advanced[id] ?? "Tell me more."] {
                let before = coordinator.session?.fragments.filter { $0.speaker == .assistant }.count ?? 0
                await coordinator.sendTyped(reply)
                if await waitFor(35, condition: { (coordinator.session?.fragments.filter { $0.speaker == .assistant }.count ?? 0) > before }) {
                    report.typedReplies += 1
                    await settleCaption()
                } else { break }
            }
            let recognizer = NLLanguageRecognizer()
            recognizer.processString(coordinator.caption)
            if let detected = recognizer.dominantLanguage?.rawValue {
                report.targetLanguageDetected = TeachingPolicy.detectedMatchesTarget(language: coordinator.language, detectedLanguageID: detected)
            }
            report.pinyinAvailable = MandarinPinyin.reading(coordinator.caption) != nil
            report.translated = await waitFor(20) { !coordinator.meaning.isEmpty && !coordinator.translating }
            let lookupWords = ["de": "Kaffee", "it": "caffè", "pt": "café", "zh": "咖啡", "af": "koffie"]
            do {
                let result = try await coordinator.lookup(word: lookupWords[id] ?? coordinator.language.greetingWord, sentence: coordinator.caption)
                report.lookupReturned = !result.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            } catch { report.failure = "Word lookup failed." }
        } else { report.failure = "Voice did not connect; check the device and API configuration." }
        coordinator.end(reason: "Language verification")
        report.closed = await waitFor(8) { !coordinator.isRunning }
        report.audioReleased = !RTCAudioSession.sharedInstance().isActive
        let meaning = coordinator.meaning
        coordinator.toggleMeaning(); coordinator.toggleMeaning()
        report.cachedMeaningAfterEnd = !meaning.isEmpty && coordinator.meaning == meaning && !coordinator.translating
        // Let the final assessment finish before checking actual saved evidence.
        _ = await waitFor(12) { coordinator.store.sessions.contains { !$0.assessments.isEmpty } }
        let saved = coordinator.store.sessions.filter { $0.languageID == id }
        let words = saved.flatMap { record in record.assessments.compactMap { LearningEngine.validate($0, session: record) }.flatMap(\.words) }
        report.supportedEvidenceOnly = !words.isEmpty && words.allSatisfy { $0.language == id && $0.kind != .independent }
        if let data = try? coordinator.store.exportData(), let restored = try? Archive.decode(data) {
            report.archiveRoundTrip = restored.sessions.count == saved.count && restored.sessions.allSatisfy { $0.languageID == id }
        }
        coordinator.selectLanguage("nb")
        let otherEmpty = coordinator.store.learner.words.isEmpty
        coordinator.selectLanguage(id)
        report.switchedAwayAndBack = otherEmpty && coordinator.language.id == id && coordinator.store.sessions.filter { $0.languageID == id }.count == saved.count
        if coordinator.error != nil { report.failure = "The app reported an error during the live check." }
        report.status = "complete"
        write()
    }
}
#endif
