import Foundation

enum AudioVerification {
    static var requested: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("--verify-audio")
        #else
        false
        #endif
    }
}

#if DEBUG
import AVFoundation
import UIKit
import WebRTC
import MuralCore

extension AudioVerification {
    private struct Playback: Codable {
        var connected = false
        var receivedCaption = false
        var peakAudioLevel = 0.0
        var outputPorts: Set<String> = []
        var outputVolumes: Set<Float> = []
        var speakerPreferenceRetained = true
        var closed = false
        var audioReleased = false
        var error: String?
        var passed: Bool {
            connected && receivedCaption && peakAudioLevel > 0.001 &&
            outputPorts == [AVAudioSession.Port.builtInSpeaker.rawValue] &&
            speakerPreferenceRetained && closed && audioReleased && error == nil
        }
    }

    // Explicit debug launch only. Uses an in-memory learning store and records
    // routing facts, never credentials, audio, or conversation text.
    @MainActor static func run(_ coordinator: ConversationCoordinator) async {
        let wasIdleTimerDisabled = UIApplication.shared.isIdleTimerDisabled
        UIApplication.shared.isIdleTimerDisabled = true
        defer { UIApplication.shared.isIdleTimerDisabled = wasIdleTimerDisabled }
        if ProcessInfo.processInfo.arguments.contains("--record-spanish-demo") {
            await recordSpanishDemo(coordinator)
            return
        }
        if let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("--verify-language=") }) {
            let id = String(argument.dropFirst("--verify-language=".count))
            guard LanguageRegistry.module(for: id) != nil else { return }
            coordinator.selectLanguage(id)
        }
        if ProcessInfo.processInfo.arguments.contains("--verify-language-flow") {
            await verifyLanguageFlow(coordinator)
            return
        }
        if ProcessInfo.processInfo.arguments.contains("--verify-meaning") {
            await verifyMeaning(coordinator)
            return
        }
        let destination = URL.documentsDirectory.appendingPathComponent("audio-verification.json")
        func write(status: String, results: [Playback]) {
            struct Report: Encodable {
                var date: Date
                var status: String
                var languageID: String
                var passed: Bool
                var playback: [Playback]
            }
            let report = Report(date: .now, status: status, languageID: coordinator.language.id, passed: results.count == 2 && results.allSatisfy(\.passed), playback: results)
            if let data = try? JSONEncoder().encode(report) { try? data.write(to: destination, options: .atomic) }
        }
        coordinator.store.updatePreferences { $0.meaningVisible = false }
        var results: [Playback] = []
        write(status: "running", results: results)
        for _ in 0..<2 {
            var result = Playback()
            coordinator.start()
            let deadline = Date().addingTimeInterval(60)
            var connectedAt: Date?
            while Date() < deadline && !Task.isCancelled {
                if coordinator.state == .active {
                    if connectedAt == nil {
                        connectedAt = .now
                    }
                    result.connected = true
                    result.receivedCaption = result.receivedCaption || coordinator.assistantPassage != nil
                    result.peakAudioLevel = max(result.peakAudioLevel, coordinator.outputLevel)
                    if coordinator.outputLevel > 0.001 {
                        let audio = AVAudioSession.sharedInstance()
                        result.outputPorts.formUnion(audio.currentRoute.outputs.map { $0.portType.rawValue })
                        result.outputVolumes.insert(audio.outputVolume)
                        result.speakerPreferenceRetained = result.speakerPreferenceRetained && audio.categoryOptions.contains(.defaultToSpeaker)
                    }
                    if let connectedAt, Date().timeIntervalSince(connectedAt) >= 12 { break }
                }
                if coordinator.state == .failed || coordinator.showSettings { break }
                try? await Task.sleep(for: .milliseconds(100))
            }
            result.error = coordinator.error
            coordinator.end(reason: "Audio verification")
            let closingDeadline = Date().addingTimeInterval(7)
            while coordinator.isRunning && Date() < closingDeadline {
                try? await Task.sleep(for: .milliseconds(100))
            }
            result.closed = !coordinator.isRunning
            result.audioReleased = !RTCAudioSession.sharedInstance().isActive
            results.append(result)
            write(status: results.count == 2 ? "complete" : "running", results: results)
            if !result.connected || !result.closed || Task.isCancelled { break }
        }
        write(status: "complete", results: results)
    }

    // Explicit recording helper: real provider responses to two scripted typed
    // turns. It never changes the owner's learning record or exports their key.
    @MainActor private static func recordSpanishDemo(_ coordinator: ConversationCoordinator) async {
        coordinator.selectLanguage("es")
        coordinator.selectMeaningLanguage("English")
        coordinator.chooseTheme(ConversationTheme.shared.first { $0.id == "coffee" })
        coordinator.store.updatePreferences { $0.meaningVisible = true }
        let destination = URL.documentsDirectory.appendingPathComponent("demo-verification.json")
        func report(_ status: String, replies: Int = 0) {
            let data = try? JSONEncoder().encode(["status": status, "replies": String(replies), "input": "scripted typed turns", "output": "live provider audio"])
            try? data?.write(to: destination, options: .atomic)
        }
        report("ready")
        do { try await Task.sleep(for: .seconds(30)) } catch { return }
        coordinator.start()
        defer { coordinator.end(reason: "Spanish recording demo") }
        let connectionDeadline = Date().addingTimeInterval(45)
        while coordinator.state == .connecting && Date() < connectionDeadline {
            do { try await Task.sleep(for: .milliseconds(100)) } catch { coordinator.end(reason: "Demo cancelled"); return }
        }
        guard coordinator.state == .active else { report("connection failed"); return }
        coordinator.toggleMute()
        let absoluteDeadline = Date().addingTimeInterval(100)
        func waitForReply(after previous: String) async -> Bool {
            var lastChange = Date(), previousText = previous
            while coordinator.state == .active && Date() < absoluteDeadline && !Task.isCancelled {
                let current = coordinator.caption
                if current != previousText || coordinator.outputLevel > 0.01 { lastChange = .now; previousText = current }
                if current != previous, coordinator.assistantPassage != nil, !coordinator.working,
                   Date().timeIntervalSince(lastChange) > 3 { return true }
                do { try await Task.sleep(for: .milliseconds(100)) } catch { return false }
            }
            return false
        }
        var replies = 0
        if await waitForReply(after: coordinator.language.greeting) {
            for line in ["Hola. Me gustaría tomar un café con leche.", "Yo quiere una tostada también, por favor."] {
                let previous = coordinator.caption
                await coordinator.sendTyped(line)
                guard await waitForReply(after: previous) else { break }
                replies += 1
            }
        }
        report(replies == 2 ? "complete" : "incomplete", replies: replies)
    }

    @MainActor private static func verifyMeaning(_ coordinator: ConversationCoordinator) async {
        struct Report: Encodable {
            var date = Date()
            var status = "running"
            var languageID: String
            var translatedWhileActive = false
            var cachedMeaningAfterEnd = false
            var newTranslationAfterEnd = false
            var resetAfter15Seconds = false
            var savedConversationRetained = false
            var audioReleased = false
            var error: String?
            var passed: Bool {
                translatedWhileActive && cachedMeaningAfterEnd && newTranslationAfterEnd &&
                resetAfter15Seconds && savedConversationRetained && audioReleased && error == nil
            }
            // Include the computed outcome in the written report.
            enum CodingKeys: String, CodingKey {
                case date, status, languageID, translatedWhileActive, cachedMeaningAfterEnd,
                     newTranslationAfterEnd, resetAfter15Seconds, savedConversationRetained, audioReleased, error, passed
            }
            func encode(to encoder: Encoder) throws {
                var c = encoder.container(keyedBy: CodingKeys.self)
                try c.encode(date, forKey: .date); try c.encode(status, forKey: .status)
                try c.encode(languageID, forKey: .languageID)
                try c.encode(translatedWhileActive, forKey: .translatedWhileActive)
                try c.encode(cachedMeaningAfterEnd, forKey: .cachedMeaningAfterEnd)
                try c.encode(newTranslationAfterEnd, forKey: .newTranslationAfterEnd)
                try c.encode(resetAfter15Seconds, forKey: .resetAfter15Seconds)
                try c.encode(savedConversationRetained, forKey: .savedConversationRetained)
                try c.encode(audioReleased, forKey: .audioReleased)
                try c.encodeIfPresent(error, forKey: .error); try c.encode(passed, forKey: .passed)
            }
        }
        var report = Report(languageID: coordinator.language.id)
        let destination = URL.documentsDirectory.appendingPathComponent("meaning-verification.json")
        func write() {
            if let data = try? JSONEncoder().encode(report) { try? data.write(to: destination, options: .atomic) }
        }
        write()
        coordinator.store.updatePreferences { $0.meaningVisible = true; $0.meaningLanguage = "English" }
        coordinator.start()
        let deadline = Date().addingTimeInterval(45)
        while Date() < deadline && !Task.isCancelled {
            if coordinator.state == .active, !coordinator.meaning.isEmpty, !coordinator.translating {
                report.translatedWhileActive = true; break
            }
            if coordinator.meaningError != nil || coordinator.error != nil || coordinator.showSettings { break }
            try? await Task.sleep(for: .milliseconds(100))
        }
        report.error = coordinator.meaningError ?? coordinator.error
        let sessionID = coordinator.session?.id
        coordinator.end(reason: "Meaning verification")
        let closingDeadline = Date().addingTimeInterval(7)
        while coordinator.isRunning && Date() < closingDeadline { try? await Task.sleep(for: .milliseconds(100)) }
        let endedAt = coordinator.session?.endedAt
        report.audioReleased = !RTCAudioSession.sharedInstance().isActive
        let english = coordinator.meaning
        coordinator.toggleMeaning()
        coordinator.toggleMeaning()
        report.cachedMeaningAfterEnd = coordinator.state == .ended && !english.isEmpty && coordinator.meaning == english && !coordinator.translating
        coordinator.selectMeaningLanguage("French")
        let translationDeadline = Date().addingTimeInterval(13)
        while Date() < translationDeadline && coordinator.state == .ended && !Task.isCancelled {
            if !coordinator.meaning.isEmpty && !coordinator.translating {
                report.newTranslationAfterEnd = true; break
            }
            if coordinator.meaningError != nil { report.error = coordinator.meaningError; break }
            try? await Task.sleep(for: .milliseconds(100))
        }
        write()
        let resetDeadline = (endedAt ?? .now).addingTimeInterval(18)
        while coordinator.state == .ended && Date() < resetDeadline { try? await Task.sleep(for: .milliseconds(100)) }
        report.resetAfter15Seconds = coordinator.state == .idle && coordinator.session == nil && coordinator.selectedTheme == nil &&
            coordinator.caption == coordinator.language.greeting && endedAt.map { Date().timeIntervalSince($0) >= 14.5 } == true
        report.savedConversationRetained = coordinator.store.sessions.contains { $0.id == sessionID && $0.endedAt != nil && !$0.fragments.isEmpty }
        report.status = "complete"; write()
    }
}
#endif
