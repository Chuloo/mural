import SwiftUI
import UniformTypeIdentifiers
import MuralCore

struct ThemesView: View {
    @Environment(\.locale) private var interfaceLocale
    let coordinator: ConversationCoordinator
    let choose: (ConversationTheme?) -> Void
    @State private var search = ""
    @State private var category = "All"
    @State private var current = false
    @Environment(\.dynamicTypeSize) private var typeSize
    private var themes: [ConversationTheme] {
        coordinator.language.themes.filter { (category == "All" || $0.category == category) && (search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.category.localizedCaseInsensitiveContains(search) || L10n.text($0.title).localizedCaseInsensitiveContains(search) || L10n.text($0.category).localizedCaseInsensitiveContains(search)) }
    }
    private var categories: [String] { coordinator.language.themes.map(\.category).reduce(into: ["All"]) { if !$0.contains($1) { $0.append($1) } } }
    var body: some View {
        let _ = interfaceLocale
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                LibrarySearchField(prompt: "Find a conversation", text: $search)
                VStack(alignment: .leading, spacing: 5) {
                    Text("Themes").font(.largeTitle.bold())
                    Text("Choose a conversation to begin.").font(.subheadline).foregroundStyle(.secondary)
                }
                Button { choose(nil) } label: {
                    HStack { Image(systemName: "waveform"); Text("Just talk"); Spacer(); Image(systemName: "arrow.up.right") }
                        .font(.headline).padding(18).background(.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(categories, id: \.self) { c in
                            Button(LocalizedStringKey(c)) { category = c }.font(.caption).padding(.horizontal, 15).padding(.vertical, 11)
                                .background(category == c ? MuralColor.accent.opacity(0.16) : MuralColor.surface, in: Capsule())
                                .accessibilityAddTraits(category == c ? .isSelected : [])
                        }
                    }
                }.scrollIndicators(.hidden)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 260 : 150), spacing: 12)], spacing: 12) {
                    ForEach(Array(themes.enumerated()), id: \.element.id) { index, theme in
                        Button { if theme.id == "today" { current = true } else { choose(theme) } } label: {
                            VStack(alignment: .leading, spacing: 28) {
                                Image(systemName: theme.symbol).font(.system(size: 28, weight: .light)).foregroundStyle(MuralColor.secondary)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(LocalizedStringKey(theme.title)).font(.system(.headline, design: .default))
                                    Text(LocalizedStringKey(theme.subtitle)).font(.caption).foregroundStyle(MuralColor.secondary)
                                }
                            }.frame(maxWidth: .infinity, minHeight: 142, alignment: .leading).padding(19)
                                .background(MuralColor.panels[index % MuralColor.panels.count], in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                                .overlay { RoundedRectangle(cornerRadius: 16).stroke(.quaternary, lineWidth: 1) }
                        }.buttonStyle(.plain)
                    }
                }
                if themes.isEmpty { ContentUnavailableView("No matching themes", systemImage: "magnifyingglass", description: Text("Try another topic or category.")) }
            }.padding(24)
        }.foregroundStyle(MuralColor.ink)
            .scrollDismissesKeyboard(.interactively)
            .sheet(isPresented: $current) { CurrentTopicView(coordinator: coordinator) { choose(coordinator.selectedTheme) } }
    }
}

struct CurrentTopicView: View {
    @Environment(\.locale) private var interfaceLocale
    let coordinator: ConversationCoordinator
    let selected: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var brief: TopicBrief?
    @State private var loading = false
    @State private var error: String?
    var body: some View {
        let _ = interfaceLocale
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Current topic").font(.largeTitle.bold())
                        Text("Search for a subject to explore together.").font(.subheadline).foregroundStyle(.secondary)
                    }
                    TextField(LocalizedStringKey(coordinator.language.topicPlaceholder), text: $query, axis: .vertical).padding(16).background(.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    Button { find() } label: {
                        HStack { Text(LocalizedStringKey(loading ? "Finding something interesting…" : "Find a topic")); Spacer(); if loading { ProgressView() } else { Image(systemName: "sparkle.magnifyingglass") } }.padding(16).background(MuralColor.buttonFill, in: Capsule()).foregroundStyle(.white)
                    }.disabled(loading || query.trimmingCharacters(in: .whitespaces).isEmpty)
                    if let error { Text(LocalizedStringKey(error)).font(.footnote).foregroundStyle(MuralColor.secondary) }
                    if let brief {
                        Text(.init(brief.text)).font(.body).textSelection(.enabled)
                        SourcesView(sources: brief.sources, date: brief.retrievedAt)
                        Button("Talk about this", systemImage: "waveform") { coordinator.discuss(brief); selected(); dismiss() }
                            .font(.headline).padding(16).frame(maxWidth: .infinity).background(MuralColor.buttonFill, in: Capsule()).foregroundStyle(.white)
                    }
                    Text("Search uses your saved connection. Sources stay attached to the topic.").font(.footnote).foregroundStyle(MuralColor.secondary)
                }.padding(26)
            }.background(MuralBackdrop()).foregroundStyle(MuralColor.ink)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
    private func find() {
        loading = true; error = nil
        Task { do { brief = try await coordinator.currentTopic(query) } catch { self.error = L10n.error(error) }; loading = false }
    }
}

struct WordsView: View {
    @Environment(\.locale) private var interfaceLocale
    let coordinator: ConversationCoordinator
    @State private var search = ""
    @State private var selected: WordState?
    @State private var sessions = false
    private var learner: LearnerState { coordinator.store.learner }
    private var words: [WordState] { learner.words.filter { search.isEmpty || $0.lemma.localizedCaseInsensitiveContains(search) || $0.meaning.localizedCaseInsensitiveContains(search) } }
    var body: some View {
        let _ = interfaceLocale
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                LibrarySearchField(prompt: "Find a word", text: $search)
                VStack(alignment: .leading, spacing: 5) {
                    Text("Words").font(.largeTitle.bold())
                    Text(L10n.format("Words you are practising in %@.", L10n.text(coordinator.language.name))).font(.subheadline).foregroundStyle(.secondary)
                }
                if words.isEmpty {
                    VStack(alignment: .leading, spacing: 18) {
                        Image(systemName: "leaf").font(.system(size: 34, weight: .light))
                        Text(LocalizedStringKey(search.isEmpty ? "They’ll grow from here." : "No matching words yet.")).font(.system(.title2, design: .default, weight: .medium))
                        Text(LocalizedStringKey(search.isEmpty ? "As we talk, useful words and phrases find a home here. Their strength grows when you recall them over time." : L10n.format("Try another %@ word or English meaning.", L10n.text(coordinator.language.name)))).font(.subheadline).foregroundStyle(MuralColor.secondary)
                    }.padding(20).frame(maxWidth: .infinity, alignment: .leading).background(.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(words) { word in
                            Button { selected = word } label: {
                                HStack(spacing: 18) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(word.lemma).font(.system(.title2, design: .default, weight: .medium))
                                        Text(word.meaning).font(.subheadline).foregroundStyle(MuralColor.secondary)
                                    }
                                    Spacer(minLength: 10)
                                    VStack(alignment: .trailing, spacing: 8) { RecallBars(count: word.bars); Text(LocalizedStringKey(word.label)).font(.caption2).foregroundStyle(MuralColor.secondary) }
                                }.padding(.vertical, 20)
                            }.buttonStyle(.plain)
                            Divider().overlay(MuralColor.peach)
                        }
                    }
                }
                HStack { Text("1 · Fragile"); Spacer(); Text("2 · Growing"); Spacer(); Text("3 · Steady") }.font(.caption).foregroundStyle(MuralColor.secondary)
                Text("The bars estimate spoken recall, not permanent mastery. Using a word with visible meanings counts as supported practice.").font(.footnote).foregroundStyle(MuralColor.secondary)
                if !learner.capabilities.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Finding your voice").font(.system(.title3, design: .default, weight: .semibold))
                        ForEach(learner.capabilities, id: \.self) { Text($0).font(.subheadline) }
                        Text("Observed across conversations. These are provisional, not formal level certificates.").font(.footnote).foregroundStyle(MuralColor.secondary)
                    }.padding(20).background(.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                Button("Past conversations", systemImage: "clock.arrow.circlepath") { sessions = true }.font(.subheadline).padding(.vertical, 8)
            }.padding(26)
        }.foregroundStyle(MuralColor.ink).scrollDismissesKeyboard(.interactively)
            .sheet(item: $selected) { word in WordDetailView(word: word, store: coordinator.store) }
            .sheet(isPresented: $sessions) { SessionHistoryView(store: coordinator.store) }
    }
}

struct WordDetailView: View {
    @Environment(\.locale) private var interfaceLocale
    let word: WordState
    let store: LearningStore
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let _ = interfaceLocale
        NavigationStack {
            VStack(alignment: .leading, spacing: 24) {
                Text(word.lemma).font(.system(.largeTitle, design: .default, weight: .medium))
                if store.language.id == "zh" { PinyinHelp(text: word.lemma) }
                Text(word.meaning).font(.title3).foregroundStyle(MuralColor.secondary)
                HStack { RecallBars(count: word.bars); Text(LocalizedStringKey(word.label)).font(.subheadline) }
                Text(LocalizedStringKey(word.explanation)).font(.body)
                Text("“\(word.example)”").font(.title3).padding(18).frame(maxWidth: .infinity, alignment: .leading).background(.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                Text(L10n.format("%lld independent uses · Last seen %@", word.independentCount, L10n.date(word.lastSeen))).font(.footnote).foregroundStyle(MuralColor.secondary)
                Button("Remove from my words", role: .destructive) { store.hideWord(word.id); dismiss() }.font(.footnote)
                Spacer()
            }.padding(24).frame(maxWidth: .infinity, alignment: .leading).background(MuralBackdrop()).foregroundStyle(MuralColor.ink)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
}

struct SourcesView: View {
    @Environment(\.locale) private var interfaceLocale
    var sources: [SourceLink]
    var date: Date
    var body: some View {
        let _ = interfaceLocale
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.format("Sources · %@", L10n.date(date))).font(.caption).foregroundStyle(MuralColor.secondary)
            ForEach(sources) { source in if let url = source.safeURL { Link(destination: url) { Label(source.title == "Source" ? L10n.text("Source") : source.title, systemImage: "arrow.up.right").font(.subheadline) } } }
        }
    }
}

struct TranscriptView: View {
    @Environment(\.locale) private var interfaceLocale
    let session: SessionRecord?
    var meaningLanguage = "English"
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let _ = interfaceLocale
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let session {
                        ForEach(session.passages) { passage in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(LocalizedStringKey(passage.speaker == .assistant ? "MURAL" : "YOU")).font(.caption).tracking(1).foregroundStyle(MuralColor.secondary)
                                Text(passage.text).font(.system(.title3, design: .default)).textSelection(.enabled)
                                if session.languageID == "zh" { PinyinHelp(text: passage.text) }
                                if let translation = session.translations[MeaningRequest.cacheKey(revisionKey: passage.revisionKey, language: meaningLanguage)] ?? session.translations[passage.revisionKey] {
                                    Text(translation).font(.subheadline).foregroundStyle(MuralColor.secondary)
                                }
                            }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                                .background(.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        ForEach(session.topics) { topic in Text(.init(topic.text)); SourcesView(sources: topic.sources, date: topic.retrievedAt) }
                        if session.fragments.isEmpty && session.topics.isEmpty { Text("Your conversation will appear here.").foregroundStyle(MuralColor.secondary) }
                    } else { Text("Start a conversation and your words will appear here.") }
                }.padding(26)
            }.background(MuralBackdrop()).foregroundStyle(MuralColor.ink)
                .navigationTitle(L10n.text("Our conversation")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct SessionHistoryView: View {
    @Environment(\.locale) private var interfaceLocale
    private func displayTitle(for session: SessionRecord) -> String {
        guard let language = LanguageRegistry.module(for: session.languageID) else { return session.title }
        if session.title == language.defaultTitle { return L10n.format("A little %@", L10n.text(language.name)) }
        if let theme = language.themes.first(where: { $0.id == session.themeID }), session.title == theme.title { return L10n.text(theme.title) }
        return session.title
    }
    let store: LearningStore
    @State private var selected: SessionRecord?
    @State private var deleting: SessionRecord?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let _ = interfaceLocale
        NavigationStack {
            List {
                if store.learningSessions.isEmpty { Text(L10n.format("Your %@ conversations will appear here.", L10n.text(store.language.name))).foregroundStyle(MuralColor.secondary) }
                ForEach(store.learningSessions) { session in
                    Button { selected = session } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(displayTitle(for: session)).font(.headline)
                            Text(L10n.date(session.startedAt, includeTime: true)).font(.caption).foregroundStyle(MuralColor.secondary)
                        }.padding(.vertical, 8)
                    }.swipeActions { Button("Delete", role: .destructive) { deleting = session }.disabled(session.endedAt == nil) }
                }
            }.scrollContentBackground(.hidden).background(MuralBackdrop())
                .navigationTitle(L10n.text("Past conversations")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.sheet(item: $selected) { session in EditableTranscriptView(sessionID: session.id, store: store) }
            .confirmationDialog("Delete this conversation and its learning evidence?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button("Delete conversation", role: .destructive) { if let deleting { store.deleteSession(deleting.id) }; deleting = nil }
            }
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct EditableTranscriptView: View {
    @Environment(\.locale) private var interfaceLocale
    let sessionID: UUID
    let store: LearningStore
    @Environment(\.dismiss) private var dismiss
    @State private var editingID: String?
    @State private var editedText = ""
    private var session: SessionRecord? { store.sessions.first { $0.id == sessionID } }
    var body: some View {
        let _ = interfaceLocale
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    ForEach(session?.passages ?? []) { passage in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(LocalizedStringKey(passage.speaker == .user ? "YOU" : "MURAL")).font(.caption).tracking(1)
                                Spacer()
                                if passage.speaker == .user && session?.endedAt != nil {
                                    Button("Edit") { editedText = passage.text; editingID = passage.id }.font(.caption)
                                }
                            }.foregroundStyle(MuralColor.secondary)
                            Text(passage.text).font(.system(.title3, design: .default)).textSelection(.enabled)
                            if session?.languageID == "zh" { PinyinHelp(text: passage.text) }
                        }
                    }
                    ForEach(session?.topics ?? []) { topic in Text(.init(topic.text)); SourcesView(sources: topic.sources, date: topic.retrievedAt) }
                }.padding(26)
            }.background(MuralBackdrop()).foregroundStyle(MuralColor.ink)
                .navigationTitle(L10n.text("Our conversation")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.sheet(isPresented: Binding(get: { editingID != nil }, set: { if !$0 { editingID = nil } })) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 20) {
                    TextField("What you said", text: $editedText, axis: .vertical).lineLimit(4...10).padding(16).background(.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    Text("Correct a misheard phrase. Learning evidence from the old wording will be removed; the original remains in your backup history.").font(.footnote).foregroundStyle(MuralColor.secondary)
                    Spacer()
                }.padding(24).background(MuralBackdrop()).navigationTitle(L10n.text("What you said")).navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { editingID = nil } }
                        ToolbarItem(placement: .confirmationAction) { Button("Save") { if let id = editingID { store.correctPassage(sessionID: sessionID, passageID: id, text: editedText) }; editingID = nil } }
                    }
            }.presentationDetents([.medium, .large])
        }
    }
}

struct SettingsView: View {
    @Environment(\.locale) private var interfaceLocale
    let coordinator: ConversationCoordinator
    @AppStorage(InterfaceLanguage.preferenceKey) private var interfaceLanguage = InterfaceLanguage.current.rawValue
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var hasKey = CredentialStore.hasKey
    @State private var message: String?
    @State private var exporting = false
    @State private var importing = false
    @State private var backup: BackupDocument?
    @State private var deleting = false
    @State private var notices = false
    @State private var showingAPIKey = false
    @State private var showingInterfaceLanguage = false
    @State private var hasSubscription = SubscriptionStore.isConfigured
    @State private var showingAppearance = false
    private var store: LearningStore { coordinator.store }
    private var totalVoiceSeconds: Double { store.sessions.reduce(0) { $0 + $1.voiceSeconds } }
    var body: some View {
        let _ = interfaceLocale
        NavigationStack {
            Form {
                Section {
                    Button { showingInterfaceLanguage = true } label: {
                        HStack { LabeledContent("App language", value: (InterfaceLanguage(rawValue: interfaceLanguage) ?? .current).nativeName); Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary) }
                    }.accessibilityIdentifier("interface-language-settings")
                }
                Section {
                    LearningLanguagePicker(coordinator: coordinator)
                    Toggle("Meaning subtitles", isOn: Binding(get: { store.preferences.meaningVisible }, set: { value in
                        if value != store.preferences.meaningVisible { coordinator.toggleMeaning() }
                    }))
                    Picker("Meaning language", selection: Binding(get: { store.preferences.meaningLanguage }, set: { coordinator.selectMeaningLanguage($0) })) {
                        ForEach(MeaningLanguages.all, id: \.self) { Text(LocalizedStringKey($0)) }
                    }
                    ConversationTeachingLanguagePicker(coordinator: coordinator)
                    LabeledContent("Corrections") { Text("Gently, as we talk") }
                    TextField("A few things you enjoy", text: Binding(get: { store.preferences.interests }, set: { value in store.updatePreferences { $0.interests = String(value.prefix(500)) } }), axis: .vertical)
                } header: { Text("Just your pace") } footer: { Text(LocalizedStringKey(coordinator.isRunning ? "End this conversation to switch languages. Each language keeps its own words and progress." : "Each language keeps its own words and progress. Mural finds your pace through conversation.")) }
                Section {
                    Button { showingAppearance = true } label: {
                        HStack { Label("Appearance", systemImage: "paintpalette"); Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary) }
                    }
                }
                if ManagedAccountConfiguration.load() != nil {
                    Section {
                        NavigationLink { ManagedAccountView() } label: {
                            Label("Account", systemImage: "person.crop.circle")
                        }.disabled(coordinator.isRunning).accessibilityIdentifier("managed-account-settings")
                    }
                }
                SubscriptionSettingsSection(isRunning: coordinator.isRunning) { hasSubscription = SubscriptionStore.isConfigured }
                Section {
                    DisclosureGroup(isExpanded: $showingAPIKey) {
                        if hasKey { Label("Your key is saved on this iPhone", systemImage: "checkmark.shield") }
                        SecureField(LocalizedStringKey(hasKey ? "Replace OpenAI key" : "OpenAI API key"), text: $key)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().privacySensitive().accessibilityIdentifier("api-key")
                        Button(LocalizedStringKey(hasKey ? "Save replacement key" : "Save key")) {
                            do { try CredentialStore.save(key); key = ""; hasKey = true; message = "Saved securely. Start a conversation to connect." }
                            catch { message = L10n.error(error) }
                        }.disabled(key.isEmpty || coordinator.isRunning)
                        Link("Open OpenAI API keys", destination: URL(string: "https://platform.openai.com/api-keys")!)
                        if hasKey {
                            Button("Remove key", role: .destructive) {
                                do { try CredentialStore.delete(); hasKey = false; message = "Your key has been removed." }
                                catch { message = L10n.error(error) }
                            }.disabled(coordinator.isRunning)
                        }
                        Text("Your OpenAI account pays for usage. The key stays in this iPhone’s Keychain and is sent only to OpenAI.")
                            .font(.footnote).foregroundStyle(MuralColor.secondary)
                    } label: { Label("Use your own API key", systemImage: "key").accessibilityIdentifier("advanced-api-key") }
                    if let message { Text(LocalizedStringKey(message)).font(.footnote).foregroundStyle(MuralColor.secondary) }
                } header: { Text("Advanced") } footer: {
                    if hasSubscription { Text("Your saved subscription connection is used. The API key is not used as a fallback.") }
                    else if !hasKey { Text("Connect a subscription above, or use your own OpenAI API key.") }
                }
                Section {
                    Picker("Conversation limit", selection: Binding(get: { store.preferences.sessionMinutes }, set: { value in store.updatePreferences { $0.sessionMinutes = value } })) {
                        ForEach([5, 10, 15, 20, 30, 60], id: \.self) { Text(L10n.format("%lld minutes", $0)).tag($0) }
                    }
                    LabeledContent("Recorded voice time", value: L10n.format("%lld min %lld sec", Int(totalVoiceSeconds / 60), Int(totalVoiceSeconds) % 60))
                    LabeledContent("Search calls recorded", value: "\(store.sessions.reduce(0) { $0 + $1.searchCalls })")
                    if !hasSubscription { Link("OpenAI usage and billing", destination: URL(string: "https://platform.openai.com/usage")!) }
                } header: { Text("Keep it comfortable") } footer: {
                    if hasSubscription { Text("Subscription limits apply. Recorded time and available token counts are not a billing statement. The conversation limit is local.") }
                    else { Text("API voice, translation, teaching and search are billed by OpenAI. Recorded totals may include subscription conversations and are not a billing statement. Your OpenAI dashboard is authoritative. The time limit is local, not a billing cap.") }
                }
                Section {
                    Button("Export learning backup", systemImage: "square.and.arrow.up") {
                        do { backup = BackupDocument(data: try store.exportData()); exporting = true } catch { message = L10n.error(error) }
                    }
                    Button("Import learning backup", systemImage: "square.and.arrow.down") { importing = true }.disabled(coordinator.isRunning)
                    Button("Delete all conversations and learning", role: .destructive) { deleting = true }.disabled(coordinator.isRunning)
                } header: { Text("Your words belong to you") } footer: {
                    Text("Backups include transcripts and learning evidence, never your API key or subscription pairing code. Import adds conversations with new IDs. Existing conversations stay unchanged. There is no cloud sync.")
                    Text("Custom avatar images are stored only on this device and are not included in learning backups.")
                }
                Section {
                    Link("Privacy policy", destination: URL(string: "https://mural.chat/privacy/")!)
                        .accessibilityIdentifier("settings-privacy-policy")
                    Link("Terms of use", destination: URL(string: "https://mural.chat/terms/")!)
                        .accessibilityIdentifier("settings-terms")
                    Link("Contact support", destination: URL(string: "https://mural.chat/support/")!)
                        .accessibilityIdentifier("settings-support")
                } header: { Text("Help and privacy") }
                Section {
                    Text("Mural 0.1 · Personal build").font(.footnote)
                    Text("Voice: GPT-Live-1 · Teacher: GPT-5.6 Luna").font(.footnote)
                    Link("OpenAI data controls", destination: URL(string: "https://developers.openai.com/api/docs/guides/your-data")!)
                    Text("Audio and selected text go to OpenAI while you practise. In subscription mode, selected text and connection details also pass through your own service. Provider retention rules apply. Raw audio is not saved by Mural.").font(.footnote)
                    Button("Open-source notices") { notices = true }
                }
            }.scrollContentBackground(.hidden).background(MuralBackdrop()).tint(MuralColor.accent)
                .navigationTitle(L10n.text("Settings")).navigationBarTitleDisplayMode(.inline)
                .navigationDestination(isPresented: $showingInterfaceLanguage) { InterfaceLanguageView() }
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { key = ""; dismiss() } } }
        }
        .fileExporter(isPresented: $exporting, document: backup, contentType: .json, defaultFilename: "Mural-learning-backup") { result in if case .failure(let error) = result { message = L10n.error(error) } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            do {
                let url = try result.get(); let granted = url.startAccessingSecurityScopedResource(); defer { if granted { url.stopAccessingSecurityScopedResource() } }
                try store.importData(Archive.readImportData(from: url)); message = "Your backup has been imported."
            } catch { message = L10n.error(error) }
        }
        .confirmationDialog("Delete all learning data on this phone?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Delete all learning data", role: .destructive) { coordinator.deleteLearningData() }
        } message: { Text("This removes conversations, vocabulary and progress. Export a backup first if you want to keep them. Your API key and preferences remain.") }
        .sheet(isPresented: $notices) {
            NavigationStack {
                ScrollView { VStack(alignment: .leading, spacing: 16) { Text("License texts are shown in their original wording."); Text(Bundle.main.url(forResource: "ThirdPartyNotices", withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? L10n.text("Notices unavailable.")).textSelection(.enabled) }.font(.footnote).padding(24) }
                    .navigationTitle(L10n.text("Open-source notices")).navigationBarTitleDisplayMode(.inline)
            }
        }
        .sheet(isPresented: $showingAppearance) {
            NavigationStack {
                AppearancePicker(
                    skin: Binding(
                        get: { currentOrbSkin },
                        set: { skin in coordinator.store.updatePreferences { $0.orbSkinID = skin.rawValue } }
                    ),
                    avatar: Binding(
                        get: { store.preferences.avatar ?? AvatarSelection() },
                        set: { value in coordinator.store.updatePreferences { $0.avatar = value } }
                    ),
                    background: Binding(
                        get: { PageBackground(rawValue: store.preferences.pageBackgroundID ?? "original") ?? .original },
                        set: { value in coordinator.store.updatePreferences { $0.pageBackgroundID = value.rawValue } }
                    )
                )
                .navigationTitle("Appearance")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showingAppearance = false } } }
            }
        }
    }
    private var currentOrbSkin: OrbSkin { OrbSkin(rawValue: store.preferences.orbSkinID ?? "classic") ?? .classic }
}

private struct ConversationTeachingLanguagePicker: View {
    let coordinator: ConversationCoordinator
    @State private var customName = ""
    private var stored: String? { coordinator.store.preferences.conversationTeachingLanguage }
    private var selection: String {
        guard let stored, ["zh-Hans", "vi", "en"].contains(stored) else { return "other" }
        return stored
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Conversation explanation language", selection: Binding(get: { selection }, set: { value in
                coordinator.store.updatePreferences { $0.conversationTeachingLanguage = value == "other" ? (customName.isEmpty ? nil : customName) : value }
            })) {
                Text("简体中文").tag("zh-Hans")
                Text("Tiếng Việt").tag("vi")
                Text("English").tag("en")
                Text("Other language").tag("other")
            }
            if selection == "other" {
                TextField("Language name (optional)", text: $customName)
                    .textInputAutocapitalization(.words)
                    .onChange(of: customName) { _, value in
                        coordinator.store.updatePreferences { $0.conversationTeachingLanguage = value.isEmpty ? nil : value }
                    }
            }
            Text(LocalizedStringKey(coordinator.isRunning ? "This takes effect in your next conversation." : "Ask for explanations in this language during free conversation."))
                .font(.footnote).foregroundStyle(MuralColor.secondary)
        }
        .onAppear { customName = selection == "other" ? (stored ?? "") : "" }
        .disabled(coordinator.isRunning)
    }
}

struct LearningLanguagePicker: View {
    @Environment(\.locale) private var interfaceLocale
    let coordinator: ConversationCoordinator
    var body: some View {
        let _ = interfaceLocale
        Picker("Learning language", selection: Binding(get: { coordinator.language.id }, set: { coordinator.selectLanguage($0) })) {
            ForEach(LanguageRegistry.all) { language in Text(L10n.format("%@ · %@", L10n.text(language.name), L10n.text(language.variety))).tag(language.id) }
        }
        .pickerStyle(.menu)
        .id(interfaceLocale.identifier)
        .disabled(coordinator.isRunning)
        .accessibilityIdentifier("learning-language-picker")
    }
}
