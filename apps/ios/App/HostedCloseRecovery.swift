import Foundation
import Security
import CryptoKit
import Observation
import MuralCore

/// Only closure identifiers and the pinned owner's bearer are retained, in device-only Keychain storage.
@MainActor @Observable final class HostedCloseRecovery {
    static let shared = HostedCloseRecovery()
    private struct Record: Codable { var sessionID: UUID?; var requestID: UUID?; var owner: HostedOwner }
    private(set) var needsSignIn = false
    @ObservationIgnored private var active = Set<UUID>()
    @ObservationIgnored private var running = false
    @ObservationIgnored private var activeRequests = Set<UUID>()
    @ObservationIgnored private var retry: Task<Void, Never>?
    private(set) var revision = 0
    private var query: [String: Any]? {
        guard let client = HostedClient.shared else { return nil }
        let scope = Data(SHA256.hash(data: Data(client.origin.absoluteString.utf8))).base64EncodedString()
        return [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "chat.mural.hosted-close",
                kSecAttrAccount as String: scope, kSecAttrSynchronizable as String: false]
    }
    private func records() throws -> [Record] {
        guard var q = query else { return [] }
        q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &value)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let data = value as? Data, data.count <= 65_536 else { throw HostedError.secureStorage }
        let records = try JSONDecoder().decode([Record].self, from: data)
        guard records.count <= 32, records.allSatisfy({ $0.sessionID != nil || $0.requestID != nil }) else { throw HostedError.secureStorage }
        return records
    }
    private func save(_ records: [Record]) throws {
        guard let query, records.count <= 32 else { throw HostedError.secureStorage }
        let attrs: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(records),
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(query.merging(attrs) { _, new in new } as CFDictionary, nil) == errSecSuccess else { throw HostedError.secureStorage }
        } else if status != errSecSuccess { throw HostedError.secureStorage }
    }
    func begin(requestID: UUID, owner: HostedOwner) throws {
        var all = try records()
        guard !all.contains(where: { $0.requestID == requestID }) else { throw HostedError.invalidResponse }
        all.append(Record(requestID: requestID, owner: owner)); try save(all)
        activeRequests.insert(requestID)
    }
    func track(_ lease: HostedLease, requestID: UUID) throws {
        var all = try records()
        guard let index = all.firstIndex(where: { $0.requestID == requestID && $0.owner.accountID == lease.owner.accountID }) else { throw HostedError.secureStorage }
        all[index].sessionID = lease.sessionID; try save(all)
        active.insert(lease.sessionID); activeRequests.remove(requestID)
    }
    func failed(requestID: UUID) {
        activeRequests.remove(requestID)
        Task { await resume() }
    }
    func track(sessionID: UUID, owner: HostedOwner, active isActive: Bool) throws {
        var all = try records()
        if let existing = all.first(where: { $0.sessionID == sessionID }) {
            guard existing.owner.accountID == owner.accountID else { throw HostedError.invalidResponse }
        } else { all.append(Record(sessionID: sessionID, owner: owner)); try save(all) }
        if isActive { active.insert(sessionID) }
    }
    func close(_ lease: HostedLease) {
        active.remove(lease.sessionID)
        Task { await resume() }
    }
    func pause() { retry?.cancel(); retry = nil }
    func resume(round: Int = 0) async {
        guard !running, !ProcessInfo.processInfo.arguments.contains("--preview"), let client = HostedClient.shared else { return }
        retry?.cancel(); retry = nil
        running = true; defer { running = false }
        needsSignIn = false
        for pass in 0..<3 {
            if pass > 0 { try? await Task.sleep(for: .seconds(3)) }
            guard !Task.isCancelled, let saved = try? records() else { return }
            let pending = saved.filter { record in
                !(record.sessionID.map { active.contains($0) } ?? false) && !(record.requestID.map { activeRequests.contains($0) } ?? false)
            }
            if pending.isEmpty { return }
            for var record in pending {
                if let config = ManagedAccountConfiguration.load(),
                   let member = try? ManagedAccountKeychain(scope: config.storageScope).load(),
                   member.accountID == record.owner.accountID, member.isUsable(scope: config.storageScope) {
                    record.owner = HostedOwner(accountID: member.accountID, accessToken: member.accessToken, expiresAt: member.expiresAt)
                }
                guard record.owner.usable else { needsSignIn = true; continue }
                do {
                    let final: Bool
                    if let id = record.sessionID { final = try await client.close(sessionID: id, owner: record.owner) }
                    else if let key = record.requestID { final = try await client.close(requestID: key, owner: record.owner) }
                    else { throw HostedError.invalidResponse }
                    if final {
                        var all = try records()
                        all.removeAll { $0.sessionID == record.sessionID && $0.requestID == record.requestID }; try save(all)
                        revision += 1
                    }
                } catch let error as HostedError { if error.needsSignInRecovery { needsSignIn = true } }
                catch { /* Keep the pinned record for the next foreground or close attempt. */ }
            }
        }
        if !needsSignIn, round < 40 {
            retry = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(15)) } catch { return }
                await self?.resume(round: round + 1)
            }
        }
    }
}
