#if DEBUG
import Foundation
import NaturalLanguage
import AVFoundation
import WebRTC
import MuralCore
import UIKit

extension AudioVerification {
    /// Explicit device check using synthetic typed turns and the existing in-memory verification store.
    /// The saved key stays inside the app; diagnostics contain no transcript or audio.
    @MainActor static func verifyLanguageFlow(_ coordinator: ConversationCoordinator) async {
        struct Report: Encodable {
            var languageID: String
            var status = "running"
            var connected = false
            var readyToStart = false
            var foregroundAtStart = false
            var protectedDataAvailableAtStart = false
            var connectionState = "idle"
            var connectionErrorPresent = false
            var provider = ""
            var receivedGreeting = false
            var targetLanguageDetected = false
            var languageDetectionReliable: Bool
            var detectedLanguageID: String?
            var detectedLanguageConfidence: Double?
            var languageQualityReview = "pending"
            var typedReplies = 0
            var translated = false
            var lookupReturned = false
            var pinyinAvailable = false
            var supportedEvidenceOnly = false
            var assessedLastReply = false
            var assessmentCount = 0
            var acceptedWordCount = 0
            var archiveRoundTrip = false
            var switchedAwayAndBack = false
            var cachedMeaningAfterEnd = false
            var closed = false
            var audioReleased = false
            var backgroundRequested = false
            var backgroundObserved = false
            var protectedStorageLocked = false
            var backgroundHelperReturned = false
            var backgroundReplies = 0
            var backgroundAudioPeak = 0.0
            var backgroundSeconds = 0.0
            var spokenCheckRequested = false
            var spokenInputReceived = false
            var spokenInputInBackground = false
            var protectedStorageLockedAtSpeech = false
            var spokenReplyReceived = false
            var spokenReplyInBackground = false
            var spokenReplyAudioPeak = 0.0
            var sameSessionInBackground = false
            var endedInBackground = false
            var peakAudioLevel = 0.0
            var outputPorts: Set<String> = []
            var failure: String?
            var flowPassed: Bool {
                connected && receivedGreeting && typedReplies == 2 && translated &&
                lookupReturned && (languageID != "zh" || pinyinAvailable) && assessedLastReply && supportedEvidenceOnly &&
                archiveRoundTrip && switchedAwayAndBack && cachedMeaningAfterEnd && closed && audioReleased &&
                peakAudioLevel > 0.001 && outputPorts.contains(AVAudioSession.Port.builtInSpeaker.rawValue) && failure == nil &&
                (!spokenCheckRequested || (spokenInputReceived && spokenInputInBackground && spokenReplyReceived && spokenReplyInBackground && spokenReplyAudioPeak > 0.001)) &&
                (!backgroundRequested || (backgroundObserved && backgroundHelperReturned && sameSessionInBackground && endedInBackground &&
                    backgroundReplies == 2 && backgroundAudioPeak > 0.001 && backgroundSeconds >= 30))
            }
            var passed: Bool { flowPassed && languageDetectionReliable && targetLanguageDetected }
        }
        let id = coordinator.language.id
        var report = Report(languageID: id, languageDetectionReliable: TeachingPolicy.supportsSpeechLanguageDetection(language: coordinator.language))
        report.backgroundRequested = ProcessInfo.processInfo.arguments.contains("--verify-background")
        report.spokenCheckRequested = report.backgroundRequested && ProcessInfo.processInfo.arguments.contains("--verify-spoken-background")
        let destination = URL.documentsDirectory.appendingPathComponent("language-verification-\(id).json")
        var samplingSpokenReply = false
        func write() {
            // Encode computed pass status explicitly alongside the report.
            struct Output: Encodable { let passed: Bool; let flowPassed: Bool; let report: Report }
            if let data = try? JSONEncoder().encode(Output(passed: report.passed, flowPassed: report.flowPassed, report: report)) {
                try? data.write(to: destination, options: .atomic)
            }
        }
        func sampleAudio() {
            report.peakAudioLevel = max(report.peakAudioLevel, coordinator.outputLevel)
            if report.backgroundObserved && UIApplication.shared.applicationState == .background {
                report.backgroundAudioPeak = max(report.backgroundAudioPeak, coordinator.outputLevel)
                report.protectedStorageLocked = report.protectedStorageLocked || !UIApplication.shared.isProtectedDataAvailable
                if samplingSpokenReply { report.spokenReplyAudioPeak = max(report.spokenReplyAudioPeak, coordinator.outputLevel) }
            }
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
        // SwiftUI's launch task can run before the first active scene callback.
        report.readyToStart = await waitFor(20) {
            UIApplication.shared.applicationState == .active && UIApplication.shared.isProtectedDataAvailable
        }
        report.foregroundAtStart = UIApplication.shared.applicationState == .active
        report.protectedDataAvailableAtStart = UIApplication.shared.isProtectedDataAvailable
        report.provider = coordinator.conversationProvider.rawValue
        if report.readyToStart {
            coordinator.start()
            report.connected = await waitFor(45) { coordinator.state == .active }
        }
        report.connectionState = String(describing: coordinator.state)
        report.connectionErrorPresent = coordinator.error != nil
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
                "sr": "Kad bi otvorio kafić, kako bi pomirio domaće namirnice sa pristupačnim cenama?",
                "el": "Αν άνοιγες μια καφετέρια, πώς θα κρατούσες προσιτές τις τιμές χρησιμοποιώντας τοπικά υλικά;",
                "tl": "Kung magbubukas ka ng kapihan, paano mo mapapanatiling abot-kaya ang mga presyo habang gumagamit ng mga lokal na sangkap?"
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
            if let detected = recognizer.languageHypotheses(withMaximum: 2).max(by: { $0.value < $1.value }) {
                report.detectedLanguageID = detected.key.rawValue
                report.detectedLanguageConfidence = detected.value
                report.targetLanguageDetected = TeachingPolicy.detectedLanguageMatches(language: coordinator.language, detectedLanguageID: detected.key.rawValue)
            }
            report.pinyinAvailable = MandarinPinyin.reading(coordinator.caption) != nil
            report.translated = await waitFor(20) { !coordinator.meaning.isEmpty && !coordinator.translating }
            let lookupWords = ["de": "Kaffee", "it": "caffè", "pt": "café", "zh": "咖啡", "sr": "kafa", "el": "καφές", "tl": "kape"]
            do {
                let result = try await coordinator.lookup(word: lookupWords[id] ?? coordinator.language.greetingWord, sentence: coordinator.caption)
                report.lookupReturned = !result.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            } catch { report.failure = "Word lookup failed." }
            if report.backgroundRequested {
                let sessionID = coordinator.session?.id
                report.status = "ready-for-background"; write()
                // Give a person time to react to the cue. Short scripted turns keep
                // this test active without changing the product's silence policy.
                for attempt in 0..<4 {
                    report.backgroundObserved = await waitFor(12) { UIApplication.shared.applicationState == .background }
                    if report.backgroundObserved || coordinator.state != .active { break }
                    if attempt < 3 {
                        await coordinator.sendTyped("Give me one more short example of a polite coffee order.")
                        await settleCaption()
                    }
                }
                if report.backgroundObserved {
                    let backgroundStarted = Date()
                    report.protectedStorageLocked = !UIApplication.shared.isProtectedDataAvailable
                    if report.spokenCheckRequested {
                        let spokenBefore = coordinator.session?.fragments.filter { $0.speaker == .user && !$0.typed }.count ?? 0
                        let repliesBefore = coordinator.session?.fragments.filter { $0.speaker == .assistant }.count ?? 0
                        if coordinator.isMuted { coordinator.toggleMute() }
                        report.status = "ready-for-speech"; write()
                        report.spokenInputReceived = await waitFor(25) {
                            (coordinator.session?.fragments.filter { $0.speaker == .user && !$0.typed }.count ?? 0) > spokenBefore
                        }
                        report.spokenInputInBackground = report.spokenInputReceived && UIApplication.shared.applicationState == .background
                        // Secure-storage availability can lag a physical screen lock.
                        // Record it separately from the lifecycle state at speech input.
                        report.protectedStorageLockedAtSpeech = report.spokenInputReceived && !UIApplication.shared.isProtectedDataAvailable
                        if report.spokenInputReceived {
                            samplingSpokenReply = true
                            report.spokenReplyReceived = await waitFor(25) {
                                (coordinator.session?.fragments.filter { $0.speaker == .assistant }.count ?? 0) > repliesBefore
                            }
                            report.spokenReplyInBackground = report.spokenReplyReceived && UIApplication.shared.applicationState == .background
                            await settleCaption()
                            samplingSpokenReply = false
                        }
                        if !coordinator.isMuted { coordinator.toggleMute() }
                    }
                    // New replies after background entry verify ongoing voice delivery.
                    // Typed input deliberately keeps human microphone recognition a separate check.
                    for reply in ["Ask me one short question about coffee.", "Give me one short example of a polite order."] {
                        let before = coordinator.session?.fragments.filter { $0.speaker == .assistant }.count ?? 0
                        await coordinator.sendTyped(reply)
                        if await waitFor(25, condition: { (coordinator.session?.fragments.filter { $0.speaker == .assistant }.count ?? 0) > before }) {
                            report.backgroundReplies += 1
                            await settleCaption()
                        }
                        let pauseUntil = Date().addingTimeInterval(5)
                        _ = await waitFor(6) { Date() >= pauseUntil }
                    }
                    let minimumEnd = backgroundStarted.addingTimeInterval(30)
                    _ = await waitFor(max(0, minimumEnd.timeIntervalSinceNow) + 1) { Date() >= minimumEnd }
                    report.backgroundSeconds = Date().timeIntervalSince(backgroundStarted)
                    do {
                        let result = try await coordinator.lookup(word: coordinator.language.greetingWord, sentence: coordinator.caption)
                        report.backgroundHelperReturned = !result.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    } catch { report.failure = "The background helper failed." }
                    report.sameSessionInBackground = coordinator.state == .active && coordinator.session?.id == sessionID && RTCAudioSession.sharedInstance().isActive
                } else { report.failure = "The device did not enter the background during the check." }
            }
        } else {
            report.failure = report.readyToStart ? "Voice did not connect; check the device and API configuration." :
                "The app did not become active with protected storage available."
        }
        report.endedInBackground = UIApplication.shared.applicationState == .background
        coordinator.end(reason: "Language verification")
        report.closed = await waitFor(8) { !coordinator.isRunning }
        report.audioReleased = !RTCAudioSession.sharedInstance().isActive
        let meaning = coordinator.meaning
        coordinator.toggleMeaning(); coordinator.toggleMeaning()
        report.cachedMeaningAfterEnd = !meaning.isEmpty && coordinator.meaning == meaning && !coordinator.translating
        // An earlier support-language assessment can contain no target words.
        // Wait for the last reply, including the final queue's full 15-second deadline.
        report.assessedLastReply = await waitFor(18) {
            guard let current = coordinator.session,
                  let last = current.passages.last(where: { $0.speaker == .user }),
                  let saved = coordinator.store.sessions.first(where: { $0.id == current.id }) else { return false }
            return saved.assessments.contains { $0.passageID == last.id && $0.revisionKey == last.revisionKey }
        }
        let saved = coordinator.store.sessions.filter { $0.languageID == id }
        let words = saved.flatMap { record in record.assessments.compactMap { LearningEngine.validate($0, session: record) }.flatMap(\.words) }
        report.assessmentCount = saved.reduce(0) { $0 + $1.assessments.count }
        report.acceptedWordCount = words.count
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
