import Foundation
import Security
import SwiftUI
import MuralCore

enum SubscriptionStore {
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "no.william.mural.subscription",
         kSecAttrAccount as String: "connection", kSecAttrSynchronizable as String: false]
    }
    static func load() -> SubscriptionConnection? {
        try? connectionForRequest()
    }
    static var isConfigured: Bool {
        var itemQuery = query; itemQuery[kSecMatchLimit as String] = kSecMatchLimitOne
        // An inaccessible saved connection must not silently select a paid API key.
        return SecItemCopyMatching(itemQuery as CFDictionary, nil) != errSecItemNotFound
    }
    static func connectionForRequest() throws -> SubscriptionConnection? {
        var itemQuery = query; itemQuery[kSecReturnData as String] = true; itemQuery[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(itemQuery as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data,
              let connection = try? JSONDecoder().decode(SubscriptionConnection.self, from: data) else {
            throw SubscriptionConnection.ConnectionError.storage
        }
        return connection
    }
    static func save(_ connection: SubscriptionConnection) throws {
        let data = try JSONEncoder().encode(connection)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var itemQuery = query; itemQuery[kSecValueData as String] = data
            itemQuery[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(itemQuery as CFDictionary, nil) == errSecSuccess else { throw SubscriptionConnection.ConnectionError.storage }
        } else if status != errSecSuccess { throw SubscriptionConnection.ConnectionError.storage }
    }
    static func remove() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw SubscriptionConnection.ConnectionError.storage }
    }

    static var installerProvisioningEnabled: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "MuralInstallerProvisioningEnabled") as? String)?.uppercased() == "YES"
    }

    static func provisionFromInstallerEnvironment() throws {
        guard installerProvisioningEnabled else { return }
        // A normal relaunch has no installer environment and must leave Keychain untouched.
        guard let rawToken = ProcessInfo.processInfo.environment["MURAL_INSTALLATION_PAIRING_CODE"] else { return }
        guard !rawToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ProvisioningError.missingPairingCode }
        let address = Bundle.main.object(forInfoDictionaryKey: "MuralSubscriptionServiceURL") as? String
        let existing = try connectionForRequest()
        guard let candidate = try SubscriptionConnection.installerProvisioningConnection(serviceAddress: address, pairingCode: rawToken, existing: existing) else { return }
        if existing == nil { try save(candidate) }
    }

    enum ProvisioningError: LocalizedError {
        case missingPairingCode
        var errorDescription: String? {
            switch self {
            case .missingPairingCode: "This personal build was not provisioned with its private subscription connection."
            }
        }
    }
}

struct SubscriptionSettingsSection: View {
    @Environment(\.locale) private var interfaceLocale
    let isRunning: Bool
    let changed: () -> Void
    @State private var address = SubscriptionStore.load()?.origin.absoluteString ?? ""
    @State private var token = ""
    @State private var connected = SubscriptionStore.isConfigured
    @State private var checking = false
    @State private var message: String?
    @State private var voices: [String] = []
    @State private var availableVoices: [String] = []
    @State private var selectedVoice = SubscriptionStore.load()?.voice ?? ""
    @State private var savedVoice = SubscriptionStore.load()?.voice ?? ""
    @State private var voiceListLoaded = false
    @State private var requestGeneration = 0

    private var installerProvisioning: Bool { SubscriptionStore.installerProvisioningEnabled }

    var body: some View {
        let _ = interfaceLocale
        Section {
            if connected { Label("Subscription connection saved", systemImage: "checkmark.shield.fill").foregroundStyle(.green) }
            if !installerProvisioning {
                DisclosureGroup("Advanced setup") {
                    TextField("Your service’s HTTPS address", text: $address)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("subscription-address")
                    SecureField(LocalizedStringKey(connected ? "Replacement pairing code (optional)" : "Pairing code"), text: $token)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().privacySensitive()
                        .accessibilityIdentifier("subscription-pairing-code")
                }
            } else if !connected {
                Text("This personal build uses the subscription connection provisioned during installation.")
                    .font(.footnote)
            }
            if !voices.isEmpty {
                Picker("Voice for new conversations", selection: $selectedVoice) {
                    ForEach(voices, id: \.self) { voice in
                        Text(availableVoices.contains(voice) ? voice : L10n.format("%@ (unavailable)", voice)).tag(voice)
                    }
                }
                .accessibilityIdentifier("subscription-voice")
                Text("Changes apply to the next conversation.").font(.footnote)
                if !savedVoice.isEmpty && !availableVoices.contains(savedVoice) {
                    Text(L10n.format("The saved voice %@ is no longer available. Choose a current voice to update it.", savedVoice))
                        .font(.footnote).foregroundStyle(.orange)
                }
            } else if connected {
                Text("Voice list unavailable until the subscription service is reachable.").font(.footnote)
            }
            Button(LocalizedStringKey(checking ? "Checking connection…" : (installerProvisioning ? "Verify subscription connection" : "Connect subscription"))) {
                requestGeneration += 1
                let generation = requestGeneration
                checking = true; message = nil
                Task { @MainActor in
                    defer { checking = false }
                    do {
                        let previous = SubscriptionStore.load()
                        let requestedVoice = previous == nil && selectedVoice == "cove" ? "" : selectedVoice
                        let candidate = try SubscriptionConnection(address: address, token: token.isEmpty ? previous?.token ?? "" : token,
                                                                    voice: requestedVoice.isEmpty ? "cove" : requestedVoice)
                        // A saved credential must never be silently forwarded to a different host.
                        guard !token.isEmpty || previous?.origin == candidate.origin else { throw SubscriptionConnection.ConnectionError.token }
                        let account = try await APIClient().subscriptionRequest(candidate, path: "account", method: "GET")
                        guard account["type"] as? String == "chatgpt" else { throw APIClient.APIError.subscription(401) }
                        guard !Task.isCancelled, requestGeneration == generation else { return }
                        let catalog = try SubscriptionConnection.VoiceCatalog(account: account)
                        let voice = selectedVoice.isEmpty ? (previous?.voice ?? catalog.defaultVoice) : selectedVoice
                        guard catalog.voices.contains(voice) else { throw SubscriptionConnection.ConnectionError.unsupportedVoice }
                        let saved = try SubscriptionConnection(address: address, token: token.isEmpty ? previous?.token ?? "" : token, voice: voice)
                        try SubscriptionStore.save(saved)
                        availableVoices = catalog.voices
                        voices = catalog.voices
                        if !voices.contains(voice) { voices.append(voice) }
                        selectedVoice = voice; savedVoice = voice; voiceListLoaded = true
                        token = ""; connected = true; changed()
                        message = "Connected. You can start a conversation."
                    } catch { message = L10n.error(error) }
                }
            }.buttonStyle(.borderedProminent).tint(MuralColor.buttonFill).disabled(checking || address.isEmpty || isRunning || (installerProvisioning && !connected)).accessibilityIdentifier("subscription-connect")
            if connected && !installerProvisioning {
                Button("Disconnect subscription", role: .destructive) {
                    requestGeneration += 1
                    do { try SubscriptionStore.remove(); connected = false; token = ""; voices = []; availableVoices = []; voiceListLoaded = false; message = "Connection removed. Learning records are kept."; changed() }
                    catch { message = L10n.error(error) }
                }.buttonStyle(.bordered).disabled(checking || isRunning)
            }
            if let message { Text(LocalizedStringKey(message)).font(.footnote) }
        } header: { Text("ChatGPT subscription") } footer: {
            Text("Connect to your own service, where you are signed in to ChatGPT. This phone saves only its pairing code and selected voice. Your service must remain available. Conversations and selected text are processed by OpenAI through that service.")
        }
        .task {
            guard connected, let connection = SubscriptionStore.load() else { return }
            let generation = requestGeneration + 1
            requestGeneration = generation
            do {
                let account = try await APIClient().subscriptionRequest(connection, path: "account", method: "GET")
                guard !Task.isCancelled, connected, requestGeneration == generation,
                      let current = try? SubscriptionStore.connectionForRequest(), current == connection else { return }
                guard account["type"] as? String == "chatgpt" else { throw APIClient.APIError.subscription(401) }
                let catalog = try SubscriptionConnection.VoiceCatalog(account: account)
                availableVoices = catalog.voices
                voices = catalog.voices
                if !voices.contains(connection.voice) { voices.append(connection.voice) }
                selectedVoice = connection.voice; savedVoice = connection.voice; voiceListLoaded = true
            } catch {
                guard !Task.isCancelled, connected, requestGeneration == generation,
                      let current = try? SubscriptionStore.connectionForRequest(), current == connection else { return }
                message = L10n.error(error)
            }
        }
        .onChange(of: selectedVoice) { _, newVoice in
            guard connected, voiceListLoaded, !checking, !isRunning, newVoice != savedVoice else { return }
            guard availableVoices.contains(newVoice) else { selectedVoice = savedVoice; return }
            let previous = savedVoice
            do {
                guard let existing = try SubscriptionStore.connectionForRequest() else { throw SubscriptionConnection.ConnectionError.storage }
                let updated = try SubscriptionConnection(address: existing.origin.absoluteString, token: existing.token, voice: newVoice)
                try SubscriptionStore.save(updated)
                savedVoice = newVoice
                message = "Voice saved for the next conversation."
                changed()
            } catch {
                selectedVoice = previous
                message = L10n.error(error)
            }
        }
        .disabled(isRunning || checking)
    }
}
