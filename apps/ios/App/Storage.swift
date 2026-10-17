import Foundation
import SwiftData
import Security
import Observation
import MuralCore

@Model final class StoredArchive {
    @Attribute(.unique) var key: String
    var payload: Data
    init(payload: Data) { key = "mural-v1"; self.payload = payload }
}

@MainActor @Observable final class LearningStore {
    private(set) var archive = Archive()
    var error: String?
    @ObservationIgnored var onSessionInvalidation: ((UUID) -> Void)?
    let container: ModelContainer
    private var document: StoredArchive
    private let migrationBackupURL: URL?
    init(inMemory: Bool = false) throws {
        migrationBackupURL = inMemory ? nil : URL.applicationSupportDirectory
            .appendingPathComponent("Mural", isDirectory: true)
            .appendingPathComponent("before-language-modules.json")
        container = try ModelContainer(for: StoredArchive.self, configurations: ModelConfiguration(isStoredInMemoryOnly: inMemory, cloudKitDatabase: .none))
        let context = container.mainContext
        if let existing = try context.fetch(FetchDescriptor<StoredArchive>()).first {
            document = existing
            archive = try Archive.decode(existing.payload)
            if let backup = migrationBackupURL,
               let root = try JSONSerialization.jsonObject(with: existing.payload) as? [String: Any], root["schemaVersion"] as? Int == 1 {
                let directory = backup.deletingLastPathComponent()
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                if !FileManager.default.fileExists(atPath: backup.path) {
                    try existing.payload.write(to: backup, options: [.atomic, .completeFileProtection])
                }
            }
            for i in archive.sessions.indices where archive.sessions[i].endedAt == nil {
                archive.sessions[i].endedAt = .now; archive.sessions[i].endReason = "App closed before finalization"
            }
        } else {
            document = StoredArchive(payload: try Archive().encoded()); context.insert(document)
        }
        persist()
    }
    var preferences: Preferences { archive.preferences }
    var language: LanguageModule { LanguageRegistry.module(for: preferences.learningLanguageID)! }
    var sessions: [SessionRecord] { archive.sessions.sorted { $0.startedAt > $1.startedAt } }
    var learningSessions: [SessionRecord] { sessions.filter { $0.languageID == language.id } }
    var learner: LearnerState { LearningEngine.project(archive.sessions, languageID: language.id, hiddenWords: archive.preferences.hiddenWords) }
    func selectLanguage(_ id: String) {
        guard LanguageRegistry.module(for: id) != nil else { return }
        archive.preferences.learningLanguageID = id; persist()
    }
    func updatePreferences(_ change: (inout Preferences) -> Void) { change(&archive.preferences); persist() }
    func save(_ session: SessionRecord) {
        if let i = archive.sessions.firstIndex(where: { $0.id == session.id }) { archive.sessions[i] = session }
        else { archive.sessions.append(session) }
        persist()
    }
    func deleteSession(_ id: UUID) { onSessionInvalidation?(id); archive.sessions.removeAll { $0.id == id }; persist() }
    func hideWord(_ id: String) { archive.preferences.hiddenWords.append(id); persist() }
    func correctPassage(sessionID: UUID, passageID: String, text: String) {
        guard let index = archive.sessions.firstIndex(where: { $0.id == sessionID }),
              let passage = archive.sessions[index].passages.first(where: { $0.id == passageID && $0.speaker == .user }) else { return }
        for (offset, fragment) in passage.fragments.enumerated() {
            archive.sessions[index].correctFragment(id: fragment.id, text: offset == 0 ? String(text.prefix(10_000)) : "")
        }
        onSessionInvalidation?(sessionID)
        persist()
    }
    func deleteAll() {
        if let migrationBackupURL {
            do { try FileManager.default.removeItem(at: migrationBackupURL) }
            catch CocoaError.fileNoSuchFile { /* Most installations have no migration backup. */ }
            catch {
                self.error = "Mural couldn’t delete the older learning backup. Your conversations are still here. Please try again."
                return
            }
        }
        archive.sessions.forEach { onSessionInvalidation?($0.id) }
        archive.sessions = []; archive.preferences.hiddenWords = []; persist()
    }
    func exportData() throws -> Data { try archive.encoded() }
    func importData(_ data: Data) throws {
        let imported = try Archive.decode(data)
        archive = try archive.merging(imported)
        persist()
    }
    private func persist() {
        do { document.payload = try archive.encoded(); try container.mainContext.save(); error = nil }
        catch { self.error = "Mural couldn’t save your progress. Please export a backup and try again." }
    }
}

enum CredentialStore {
    static let openAI = "no.william.mural.openai"
    static let customEndpoint = "no.william.mural.custom-endpoint"
    private static func query(_ service: String) -> [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "owner", kSecAttrSynchronizable as String: false] }
    static func read(service: String = openAI) -> String? {
        var q = query(service); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static var hasKey: Bool { read() != nil }
    static func save(_ key: String, service: String = openAI) throws {
        let value = key.trimmingCharacters(in: .whitespacesAndNewlines)
        // A custom server may use any token format.
        let valid = service == openAI ? value.hasPrefix("sk-") && value.count >= 20 : !value.isEmpty && value.count <= 500
        guard valid, !value.contains(where: \.isWhitespace) else { throw KeyError.invalid }
        let data = Data(value.utf8)
        let status = SecItemUpdate(query(service) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var q = query(service); q[kSecValueData as String] = data
            q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(q as CFDictionary, nil) == errSecSuccess else { throw KeyError.save }
        } else if status != errSecSuccess { throw KeyError.save }
    }
    static func delete(service: String = openAI) throws {
        let status = SecItemDelete(query(service) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeyError.remove }
    }
    enum KeyError: LocalizedError {
        case invalid, save, remove
        var errorDescription: String? {
            switch self {
            case .invalid: "Enter a valid OpenAI API key."
            case .save: "The key couldn’t be saved to this device’s Keychain."
            case .remove: "The key couldn’t be removed. Unlock this iPhone and try again."
            }
        }
    }
}
