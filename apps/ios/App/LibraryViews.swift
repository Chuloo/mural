import SwiftUI
import UniformTypeIdentifiers
import MuralCore

struct ThemesView: View {
    let coordinator: ConversationCoordinator
    let choose: (ConversationTheme?) -> Void
    @State private var search = ""
    @State private var category = "All"
    @State private var current = false
    @Environment(\.dynamicTypeSize) private var typeSize
    private var themes: [ConversationTheme] {
        coordinator.language.themes.filter { (category == "All" || $0.category == category) && (search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.category.localizedCaseInsensitiveContains(search)) }
    }
    private var categories: [String] { coordinator.language.themes.map(\.category).reduce(into: ["All"]) { if !$0.contains($1) { $0.append($1) } } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                PageHeading(eyebrow: "A place to begin", title: "What’s on\nyour mind?", subtitle: "Same friend. Somewhere new.")
                Button { choose(nil) } label: {
                    HStack { Image(systemName: "waveform"); Text("Just talk"); Spacer(); Image(systemName: "arrow.up.right") }
                        .font(.headline).padding(22).background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 26))
                }
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(categories, id: \.self) { c in
                            Button(c) { category = c }.font(.caption).padding(.horizontal, 15).padding(.vertical, 11)
                                .background(category == c ? MuralColor.peach : .white.opacity(0.65), in: Capsule())
                                .accessibilityAddTraits(category == c ? .isSelected : [])
                        }
                    }
                }.scrollIndicators(.hidden)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 260 : 150), spacing: 12)], spacing: 12) {
                    ForEach(themes) { theme in
                        Button { if theme.id == "today" { current = true } else { choose(theme) } } label: {
                            VStack(alignment: .leading, spacing: 28) {
                                Image(systemName: theme.symbol).font(.system(size: 28, weight: .light)).foregroundStyle(MuralColor.secondary)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(theme.title).font(.system(.headline, design: .rounded))
                                    Text(theme.subtitle).font(.caption).foregroundStyle(MuralColor.secondary)
                                }
                            }.frame(maxWidth: .infinity, minHeight: 142, alignment: .leading).padding(19)
                                .background(MuralColor.panels[theme.colorIndex], in: RoundedRectangle(cornerRadius: 27))
                        }.buttonStyle(.plain)
                    }
                }
                if themes.isEmpty { ContentUnavailableView.search(text: search) }
            }.padding(24)
        }.foregroundStyle(MuralColor.ink)
            .searchable(text: $search, prompt: "Find a conversation")
            .sheet(isPresented: $current) { CurrentTopicView(coordinator: coordinator) { choose(coordinator.selectedTheme) } }
    }
}

struct CurrentTopicView: View {
    let coordinator: ConversationCoordinator
    let selected: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var brief: TopicBrief?
    @State private var loading = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    PageHeading(eyebrow: "The world today", title: "A fresh conversation.", subtitle: "What would you like to talk about?")
                    TextField(coordinator.language.topicPlaceholder, text: $query, axis: .vertical).padding(18).background(.white, in: RoundedRectangle(cornerRadius: 20))
                    Button { find() } label: {
                        HStack { Text(loading ? "Finding something interesting…" : "Find a topic"); Spacer(); if loading { ProgressView() } else { Image(systemName: "sparkle.magnifyingglass") } }.padding(18).background(MuralColor.peach, in: Capsule())
                    }.disabled(loading || query.trimmingCharacters(in: .whitespaces).isEmpty)
                    if let error { Text(error).font(.footnote).foregroundStyle(MuralColor.secondary) }
                    if let brief {
                        Text(.init(brief.text)).font(.body).textSelection(.enabled)
                        SourcesView(sources: brief.sources, date: brief.retrievedAt)
                        Button("Talk about this", systemImage: "waveform") { coordinator.discuss(brief); selected(); dismiss() }
                            .font(.headline).padding(18).frame(maxWidth: .infinity).background(MuralColor.orange, in: Capsule())
                    }
                    Text("Current topics use your API key outside a conversation. Sources stay attached to the topic.").font(.footnote).foregroundStyle(MuralColor.secondary)
                }.padding(26)
            }.background(MuralColor.cream).foregroundStyle(MuralColor.ink)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
    private func find() {
        loading = true; error = nil
        Task { do { brief = try await coordinator.currentTopic(query) } catch { self.error = error.localizedDescription }; loading = false }
    }
}

struct WordsView: View {
    let coordinator: ConversationCoordinator
    @State private var search = ""
    @State private var selected: WordState?
    @State private var sessions = false
    @State private var flashcards: FlashcardSession?
    private var learner: LearnerState { coordinator.store.learner }
    private var words: [WordState] { learner.words.filter { search.isEmpty || $0.lemma.localizedCaseInsensitiveContains(search) || $0.meaning.localizedCaseInsensitiveContains(search) } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Little by little · \(coordinator.language.name)".uppercased())
                        .font(.system(.caption, design: .rounded, weight: .medium)).tracking(1.5).foregroundStyle(MuralColor.secondary)
                    HStack(spacing: 16) {
                        Text("Your words.").font(.system(.largeTitle, design: .rounded, weight: .semibold)).tracking(-1)
                            .accessibilityAddTraits(.isHeader)
                        Spacer(minLength: 0)
                        Button {
                            flashcards = FlashcardSession(deck: FlashcardDeck(words: learner.words, languageID: coordinator.language.id))
                        } label: {
                            Image(systemName: "rectangle.on.rectangle").font(.system(size: 20)).frame(width: 48, height: 48).modifier(SoftGlass())
                        }.buttonStyle(.plain).accessibilityLabel("Practice flashcards").accessibilityIdentifier("open-flashcards")
                            .disabled(learner.words.isEmpty)
                    }
                    Text("Familiar words, ready for another conversation.").font(.subheadline).foregroundStyle(MuralColor.secondary)
                }
                if words.isEmpty {
                    VStack(alignment: .leading, spacing: 18) {
                        Image(systemName: "leaf").font(.system(size: 34, weight: .light))
                        Text(search.isEmpty ? "They’ll grow from here." : "No matching words yet.").font(.system(.title2, design: .rounded, weight: .medium))
                        Text(search.isEmpty ? "As we talk, useful words and phrases find a home here. Their strength grows when you recall them over time." : "Try another \(coordinator.language.name) word or English meaning.").font(.subheadline).foregroundStyle(MuralColor.secondary)
                    }.padding(26).frame(maxWidth: .infinity, alignment: .leading).background(MuralColor.sage, in: RoundedRectangle(cornerRadius: 28))
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(words) { word in
                            Button { selected = word } label: {
                                HStack(spacing: 18) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(word.lemma).font(.system(.title2, design: .rounded, weight: .medium))
                                        Text(word.meaning).font(.subheadline).foregroundStyle(MuralColor.secondary)
                                    }
                                    Spacer(minLength: 10)
                                    VStack(alignment: .trailing, spacing: 8) { RecallBars(count: word.bars); Text(word.label).font(.caption2).foregroundStyle(MuralColor.secondary) }
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
                        Text("Finding your voice").font(.system(.title3, design: .rounded, weight: .semibold))
                        ForEach(learner.capabilities, id: \.self) { Text($0).font(.subheadline) }
                        Text("Observed across conversations. These are provisional, not formal level certificates.").font(.footnote).foregroundStyle(MuralColor.secondary)
                    }.padding(22).background(MuralColor.butter, in: RoundedRectangle(cornerRadius: 24))
                }
                Button("Past conversations", systemImage: "clock.arrow.circlepath") { sessions = true }.font(.subheadline).padding(.vertical, 8)
            }.padding(26)
        }.foregroundStyle(MuralColor.ink).searchable(text: $search, prompt: "Find a word")
            .sheet(item: $selected) { word in WordDetailView(word: word, store: coordinator.store) }
            .sheet(isPresented: $sessions) { SessionHistoryView(store: coordinator.store) }
            .sheet(item: $flashcards) { session in FlashcardsView(deck: session.deck) }
    }
}

struct WordDetailView: View {
    let word: WordState
    let store: LearningStore
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 24) {
                Text(word.lemma).font(.system(.largeTitle, design: .rounded, weight: .medium))
                if store.language.id == "zh" { PinyinHelp(text: word.lemma) }
                Text(word.meaning).font(.title3).foregroundStyle(MuralColor.secondary)
                HStack { RecallBars(count: word.bars); Text(word.label).font(.subheadline) }
                Text(word.explanation).font(.body)
                Text("“\(word.example)”").font(.system(.title3, design: .rounded)).padding(20).frame(maxWidth: .infinity, alignment: .leading).background(MuralColor.peach, in: RoundedRectangle(cornerRadius: 22))
                Text("\(word.independentCount) independent uses · Last seen \(word.lastSeen.formatted(date: .abbreviated, time: .omitted))").font(.footnote).foregroundStyle(MuralColor.secondary)
                Button("Remove from my words", role: .destructive) { store.hideWord(word.id); dismiss() }.font(.footnote)
                Spacer()
            }.padding(28).frame(maxWidth: .infinity, alignment: .leading).background(MuralColor.cream).foregroundStyle(MuralColor.ink)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
}

struct SourcesView: View {
    var sources: [SourceLink]
    var date: Date
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Sources · \(date.formatted(date: .abbreviated, time: .omitted))").font(.caption).foregroundStyle(MuralColor.secondary)
            ForEach(sources) { source in if let url = source.safeURL { Link(destination: url) { Label(source.title, systemImage: "arrow.up.right").font(.subheadline) } } }
        }
    }
}

struct TranscriptView: View {
    let session: SessionRecord?
    var meaningLanguage = "English"
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let session {
                        ForEach(session.passages) { passage in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(passage.speaker == .assistant ? "MURAL" : "YOU").font(.caption).tracking(1).foregroundStyle(MuralColor.secondary)
                                Text(passage.text).font(.system(.title3, design: .rounded)).textSelection(.enabled)
                                    .accessibilityIdentifier(passage.speaker == .user ? "transcript-user-passage" : "transcript-assistant-passage")
                                if session.languageID == "zh" { PinyinHelp(text: passage.text) }
                                if let translation = session.translations[MeaningRequest.cacheKey(revisionKey: passage.revisionKey, language: meaningLanguage)] ?? session.translations[meaningLanguage + "::" + passage.revisionKey] ?? session.translations[passage.revisionKey] {
                                    Text(translation).font(.subheadline).foregroundStyle(MuralColor.secondary)
                                }
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                        ForEach(session.topics) { topic in Text(.init(topic.text)); SourcesView(sources: topic.sources, date: topic.retrievedAt) }
                        if session.fragments.isEmpty && session.topics.isEmpty { Text("Your conversation will appear here.").foregroundStyle(MuralColor.secondary) }
                    } else { Text("Start a conversation and your words will appear here.") }
                }.padding(26)
            }.background(MuralColor.cream).foregroundStyle(MuralColor.ink)
                .navigationTitle("Our conversation").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct SessionHistoryView: View {
    let store: LearningStore
    @State private var selected: SessionRecord?
    @State private var deleting: SessionRecord?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if store.learningSessions.isEmpty { Text("Your \(store.language.name) conversations will appear here.").foregroundStyle(MuralColor.secondary) }
                ForEach(store.learningSessions) { session in
                    Button { selected = session } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(session.title).font(.headline)
                            Text(session.startedAt.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(MuralColor.secondary)
                        }.padding(.vertical, 8)
                    }.swipeActions { Button("Delete", role: .destructive) { deleting = session }.disabled(session.endedAt == nil) }
                }
            }.scrollContentBackground(.hidden).background(MuralColor.cream)
                .navigationTitle("Past conversations").navigationBarTitleDisplayMode(.inline)
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
    let sessionID: UUID
    let store: LearningStore
    @Environment(\.dismiss) private var dismiss
    @State private var editingID: String?
    @State private var editedText = ""
    private var session: SessionRecord? { store.sessions.first { $0.id == sessionID } }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    ForEach(session?.passages ?? []) { passage in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(passage.speaker == .user ? "YOU" : "MURAL").font(.caption).tracking(1)
                                Spacer()
                                if passage.speaker == .user && session?.endedAt != nil {
                                    Button("Edit") { editedText = passage.text; editingID = passage.id }.font(.caption)
                                }
                            }.foregroundStyle(MuralColor.secondary)
                            Text(passage.text).font(.system(.title3, design: .rounded)).textSelection(.enabled)
                                    .accessibilityIdentifier(passage.speaker == .user ? "transcript-user-passage" : "transcript-assistant-passage")
                            if session?.languageID == "zh" { PinyinHelp(text: passage.text) }
                        }
                    }
                    ForEach(session?.topics ?? []) { topic in Text(.init(topic.text)); SourcesView(sources: topic.sources, date: topic.retrievedAt) }
                }.padding(26)
            }.background(MuralColor.cream).foregroundStyle(MuralColor.ink)
                .navigationTitle("Our conversation").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.sheet(isPresented: Binding(get: { editingID != nil }, set: { if !$0 { editingID = nil } })) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 20) {
                    TextField("What you said", text: $editedText, axis: .vertical).lineLimit(4...10).padding(18).background(.white, in: RoundedRectangle(cornerRadius: 20))
                    Text("Correct a misheard phrase. Learning evidence from the old wording will be removed; the original remains in your backup history.").font(.footnote).foregroundStyle(MuralColor.secondary)
                    Spacer()
                }.padding(24).background(MuralColor.cream).navigationTitle("What you said").navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { editingID = nil } }
                        ToolbarItem(placement: .confirmationAction) { Button("Save") { if let id = editingID { store.correctPassage(sessionID: sessionID, passageID: id, text: editedText) }; editingID = nil } }
                    }
            }.presentationDetents([.medium, .large])
        }
    }
}

struct SettingsView: View {
    let coordinator: ConversationCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var account = ManagedAccountStore()
    @State private var hasKey = Self.initialHasKey()
    @State private var showingKey = false
    @State private var saveAndUseKey = false
    @State private var confirmingPersonalKey = false
    @State private var showingHostedSwitch = false
    @State private var showingAccount = false
    @State private var showingAIProcessing = false
    private var store: LearningStore { coordinator.store }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
            Form {
                Section {
                    NavigationLink {
                        ManagedAccountView(coordinator: coordinator, store: account)
                    } label: {
                        HStack(spacing: 12) {
                            MuralOrb(active: false).frame(width: 38, height: 38).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Account")
                                Text(account.profile?.email ?? (account.session == nil ? "Sign in, if you’d like" : "Signed in"))
                                    .font(.footnote).foregroundStyle(MuralColor.secondary)
                                    .lineLimit(2)
                            }
                        }
                    }
                    .disabled(coordinator.isRunning)
                    .accessibilityIdentifier("managed-account-settings")
                }
                Section {
                    LearningLanguagePicker(coordinator: coordinator)
                    Toggle("Meaning subtitles", isOn: Binding(get: { store.preferences.meaningVisible }, set: { value in
                        if value != store.preferences.meaningVisible { coordinator.toggleMeaning() }
                    }))
                    Picker("Meaning language", selection: Binding(get: { store.preferences.meaningLanguage }, set: { coordinator.selectMeaningLanguage($0) })) {
                        ForEach(MeaningLanguages.all, id: \.self) { Text($0) }
                    }.pickerStyle(.menu).disabled(coordinator.isRunning)
                    NavigationLink("Interests") { InterestsSettingsView(coordinator: coordinator) }
                        .disabled(coordinator.isRunning)
                    Text("Corrections happen gently as you talk.")
                        .font(.footnote).foregroundStyle(MuralColor.secondary)
                } header: { Text("Your learning") } footer: {
                    Text(coordinator.isRunning ? "End this conversation to switch languages. Each language keeps its own words and progress." : "Each language keeps its own words and progress.")
                }
                Section {
                    Picker("Session limit", selection: Binding(get: { store.preferences.sessionMinutes }, set: { value in store.updatePreferences { $0.sessionMinutes = value } })) {
                        ForEach([5, 10, 15, 20, 30, 60], id: \.self) { Text("\($0) minutes").tag($0) }
                    }.pickerStyle(.menu).disabled(coordinator.isRunning)
                } header: { Text("Conversation") } footer: {
                    Text("Ends one conversation after the selected time. Your available Mural minutes are separate.")
                }
                Section("Your data") {
                    NavigationLink("Learning backup & data") { LearningBackupView(coordinator: coordinator) }
                }
                Section {
                    Picker("Conversation access", selection: Binding(get: { coordinator.conversationProvider }, set: chooseProvider)) {
                        Text("Mural minutes").tag(ConversationProvider.hosted)
                        Text("My API key").tag(ConversationProvider.personalKey)
                    }.pickerStyle(.menu).disabled(coordinator.isRunning)
                        .accessibilityIdentifier("settings-conversation-access")
                    if coordinator.conversationProvider == .personalKey {
                        Text("No Mural minute limit. OpenAI bills your account for usage.")
                            .font(.footnote).foregroundStyle(MuralColor.secondary)
                    }
                    if hasKey || coordinator.conversationProvider == .personalKey {
                        Button {
                            saveAndUseKey = coordinator.conversationProvider == .personalKey && !hasKey
                            showingKey = true
                        } label: {
                            LabeledContent("API key", value: hasKey ? "Saved on this iPhone" : "Key required")
                        }.accessibilityIdentifier("advanced-api-key")
                    }
                    if let failure = coordinator.personalKeyFailure {
                        Button(failure.kind.settingsTitle) { showingKey = true }
                            .foregroundStyle(MuralColor.secondary)
                            .accessibilityIdentifier("advanced-provider-issue")
                    }
                    if coordinator.conversationProvider == .personalKey {
                        LabeledContent("Recorded voice time", value: "\(Int(store.sessions.reduce(0) { $0 + $1.voiceSeconds }) / 60) min")
                        LabeledContent("Search calls recorded", value: "\(store.sessions.reduce(0) { $0 + $1.searchCalls })")
                        Text("Activity recorded on this iPhone; your OpenAI dashboard is authoritative for usage and charges.")
                            .font(.footnote).foregroundStyle(MuralColor.secondary)
                    }
                } header: { Text("Advanced") }.id("advanced-section")
                Section("Help & privacy") {
                    Button {
                        showingAIProcessing = true
                    } label: {
                        LabeledContent("AI processing", value: coordinator.hasAIConsent ? "Allowed" : "Off")
                    }.accessibilityIdentifier("settings-ai-processing")
                    Link("Contact support", destination: URL(string: "https://mural.chat/support/")!)
                        .accessibilityIdentifier("settings-support")
                    Link("Privacy policy", destination: URL(string: "https://mural.chat/privacy/")!)
                        .accessibilityIdentifier("settings-privacy-policy")
                    Link("Terms of use", destination: URL(string: "https://mural.chat/terms/")!)
                        .accessibilityIdentifier("settings-terms")
                    NavigationLink("About Mural") { AboutMuralView() }
                }
            }
            .scrollContentBackground(.hidden).background(MuralColor.cream).tint(MuralColor.ink)
            .navigationDestination(isPresented: $showingAccount) {
                ManagedAccountView(coordinator: coordinator, store: account)
            }
            .navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear {
                if coordinator.requestAccountFocus {
                    coordinator.requestAccountFocus = false
                    DispatchQueue.main.async { showingAccount = true }
                }
                if coordinator.requestAdvancedFocus {
                    coordinator.requestAdvancedFocus = false
                    DispatchQueue.main.async { withAnimation { proxy.scrollTo("advanced-section", anchor: .top) } }
                }
            }
            }
        }
        .sheet(isPresented: $showingKey, onDismiss: {
            if coordinator.requestHostedSwitch {
                coordinator.requestHostedSwitch = false
                showingHostedSwitch = true
            }
        }) {
            NavigationStack {
                OpenAIKeyView(coordinator: coordinator, hasKey: $hasKey, useAfterSave: saveAndUseKey)
            }
        }
        .sheet(isPresented: $showingHostedSwitch) { HostedAccessSwitchView(coordinator: coordinator) }
        .sheet(isPresented: $showingAIProcessing) {
            AIProcessingSettingsView(coordinator: coordinator)
        }
        .confirmationDialog("Use your API key?", isPresented: $confirmingPersonalKey, titleVisibility: .visible) {
            Button("Use my key") { coordinator.selectConversationProvider(.personalKey) }
            Button("Cancel", role: .cancel) {}
        } message: { Text("OpenAI bills your account for usage. Mural minutes stay available if you switch back later.") }
        .task { account.refresh() }
    }
    private func chooseProvider(_ provider: ConversationProvider) {
        guard !coordinator.isRunning, provider != coordinator.conversationProvider else { return }
        if provider == .hosted { showingHostedSwitch = true }
        else if hasKey { confirmingPersonalKey = true }
        else { saveAndUseKey = true; showingKey = true }
    }
    private static func initialHasKey() -> Bool {
        #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--preview") {
            return ProcessInfo.processInfo.arguments.contains("--preview-key")
        }
        #endif
        return CredentialStore.hasKey
    }
}

private extension ProviderFailureKind {
    var settingsTitle: String {
        switch self {
        case .creditExhausted: "Credits used up"
        case .spendLimit: "Spending limit reached"
        case .usageLimit: "Usage limit reached"
        case .quota: "Billing needs attention"
        case .authentication: "Key not accepted"
        default: "OpenAI needs attention"
        }
    }
}

private struct InterestsSettingsView: View {
    let coordinator: ConversationCoordinator
    var body: some View {
        Form {
            Section {
                TextField("A few things you enjoy", text: Binding(get: { coordinator.store.preferences.interests }, set: { value in
                    coordinator.store.updatePreferences { $0.interests = String(value.prefix(500)) }
                }), axis: .vertical)
                .lineLimit(4...10).disabled(coordinator.isRunning)
                .accessibilityIdentifier("settings-interests")
            } footer: { Text("Helps Mural suggest conversations that matter to you. Saved as you type on this iPhone.") }
        }.scrollContentBackground(.hidden).background(MuralColor.cream).navigationTitle("Interests")
    }
}

private struct OpenAIKeyView: View {
    let coordinator: ConversationCoordinator
    @Binding var hasKey: Bool
    let useAfterSave: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var message: String?
    @State private var removing = false
    var body: some View {
        Form {
            Section {
                LabeledContent("API key", value: hasKey ? "Saved on this iPhone" : "No key saved")
                SecureField(hasKey ? "Replacement key" : "New key", text: $key)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .privacySensitive().accessibilityIdentifier("api-key")
                Button(useAfterSave ? "Save & use my key" : hasKey ? "Save replacement key" : "Save key") {
                    do {
                        #if DEBUG && targetEnvironment(simulator)
                        if !ProcessInfo.processInfo.arguments.contains("--preview") {
                            try CredentialStore.save(key.trimmingCharacters(in: .whitespacesAndNewlines))
                        }
                        #else
                        try CredentialStore.save(key.trimmingCharacters(in: .whitespacesAndNewlines))
                        #endif
                        key = ""; hasKey = true; coordinator.clearPersonalKeyFailure()
                        if useAfterSave { coordinator.selectConversationProvider(.personalKey) }
                        message = "Key saved on this iPhone. It will be checked when you start a conversation."
                    } catch { message = error.localizedDescription }
                }.disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || coordinator.isRunning)
                if hasKey {
                    Button("Remove key", role: .destructive) { removing = true }
                        .disabled(coordinator.isRunning)
                }
            } footer: { Text("No Mural minute limit. OpenAI bills your account for usage. The key stays in this iPhone’s Keychain and goes only to OpenAI.") }
            if let failure = coordinator.personalKeyFailure {
                Section("OpenAI issue") {
                    Text(failure.localizedDescription)
                    if coordinator.conversationProvider == .personalKey {
                        Button("Use Mural minutes") { dismiss(); coordinator.requestHostedSwitch = true }
                    }
                }
            }
            if let message { Section { Text(message).foregroundStyle(MuralColor.secondary) } }
            Section {
                Link("Manage API keys", destination: URL(string: "https://platform.openai.com/api-keys")!)
                Link("Usage and billing", destination: URL(string: "https://platform.openai.com/usage")!)
            }
        }.scrollContentBackground(.hidden).background(MuralColor.cream)
            .navigationTitle("API key").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { key = ""; dismiss() } } }
            .confirmationDialog("Remove the saved key?", isPresented: $removing, titleVisibility: .visible) {
                Button("Remove key", role: .destructive) {
                    do {
                        #if DEBUG && targetEnvironment(simulator)
                        if !ProcessInfo.processInfo.arguments.contains("--preview") { try CredentialStore.delete() }
                        #else
                        try CredentialStore.delete()
                        #endif
                        hasKey = false; key = ""
                        coordinator.clearPersonalKeyFailure()
                        message = coordinator.conversationProvider == .personalKey
                            ? "Key removed. Add a key or choose Mural minutes before talking."
                            : "Key removed."
                    } catch { message = error.localizedDescription }
                }
            } message: { Text("If this key is selected for conversations, Mural will wait for a new key or your explicit choice to use Mural minutes.") }
    }
}

private struct HostedAccessSwitchView: View {
    let coordinator: ConversationCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var available: HostedBalance?
    @State private var checking = true
    @State private var failed = false
    @State private var switchTask: Task<Void, Never>?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if checking { ProgressView("Checking your minutes…") }
                    else if let available {
                        LabeledContent("Available", value: available.displayText)
                        if !available.canStart {
                            Text(available.paidReserved ? "Some minutes are in use. Check again shortly." :
                                 "Not enough minutes to start a conversation.")
                        }
                    }
                    else { Text("Couldn’t check your minutes. Try again.") }
                    if failed { Text("Your minutes changed. Check again before switching.").foregroundStyle(MuralColor.secondary) }
                    if !checking && available == nil { Button("Try again") { Task { await check() } } }
                } header: { Text("Mural minutes") } footer: { Text("Your saved OpenAI key will remain on this iPhone. Switching changes the next conversation only.") }
                if let available, available.canStart, !checking {
                    Section {
                        Button("Use Mural minutes") {
                            switchTask = Task {
                                checking = true
                                guard let latest = await coordinator.hostedBalanceForSwitch(), latest.canStart,
                                      !Task.isCancelled, coordinator.conversationProvider == .personalKey,
                                      !coordinator.isRunning else {
                                    self.available = nil; failed = true; checking = false; return
                                }
                                coordinator.selectConversationProvider(.hosted)
                                dismiss()
                            }
                        }.frame(maxWidth: .infinity).fontWeight(.semibold)
                    }
                }
            }.scrollContentBackground(.hidden).background(MuralColor.cream)
                .navigationTitle("Conversation access").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .task { await check() }
        .onDisappear { switchTask?.cancel() }
    }
    private func check() async {
        checking = true; failed = false
        available = await coordinator.hostedBalanceForSwitch()
        checking = false
    }
}

private struct LearningBackupView: View {
    let coordinator: ConversationCoordinator
    @State private var backup: BackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var deleting = false
    @State private var message: String?
    var body: some View {
        Form {
            Section {
                Button("Export learning backup", systemImage: "square.and.arrow.up") {
                    do { backup = BackupDocument(data: try coordinator.store.exportData()); exporting = true }
                    catch { message = error.localizedDescription }
                }
                Button("Import learning backup", systemImage: "square.and.arrow.down") { importing = true }
                    .disabled(coordinator.isRunning)
                Button("Delete all conversations and learning", role: .destructive) { deleting = true }
                    .disabled(coordinator.isRunning)
            } footer: { Text("Backups include transcripts and learning evidence, never your API key. Import adds conversations with new IDs. Learning stays on this iPhone unless you export it.") }
            if let message { Section { Text(message) } }
        }.scrollContentBackground(.hidden).background(MuralColor.cream).navigationTitle("Learning data")
            .fileExporter(isPresented: $exporting, document: backup, contentType: .json, defaultFilename: "Mural-learning-backup") { result in
                if case .failure(let error) = result { message = error.localizedDescription }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
                do {
                    let url = try result.get(); let granted = url.startAccessingSecurityScopedResource()
                    defer { if granted { url.stopAccessingSecurityScopedResource() } }
                    try coordinator.store.importData(Archive.readImportData(from: url)); message = "Your backup has been imported."
                } catch { message = error.localizedDescription }
            }
            .confirmationDialog("Delete all learning data on this phone?", isPresented: $deleting, titleVisibility: .visible) {
                Button("Delete all learning data", role: .destructive) { coordinator.deleteLearningData() }
            } message: { Text("This removes conversations, vocabulary and progress. Export a backup first if you want to keep them. Your key and account remain.") }
    }
}

private struct AboutMuralView: View {
    @State private var notices = false
    var body: some View {
        Form {
            Section {
                LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                Link("AI Data Controls", destination: URL(string: "https://developers.openai.com/api/docs/guides/your-data")!)
                Button("Open-source notices") { notices = true }
            }
            Section {
                Text("For Mural minutes, audio and selected text pass through Mural’s server to OpenAI. With your own key, they go directly to OpenAI. Raw audio is not saved by Mural.")
            }
        }.scrollContentBackground(.hidden).background(MuralColor.cream).navigationTitle("About Mural")
            .sheet(isPresented: $notices) {
                NavigationStack {
                    ScrollView {
                        Text(Bundle.main.url(forResource: "ThirdPartyNotices", withExtension: "txt")
                            .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "Notices unavailable.")
                            .font(.footnote).padding(24).textSelection(.enabled)
                    }.navigationTitle("Open-source notices").navigationBarTitleDisplayMode(.inline)
                }
            }
    }
}

struct LearningLanguagePicker: View {
    let coordinator: ConversationCoordinator
    var body: some View {
        Picker("Learning language", selection: Binding(get: { coordinator.language.id }, set: { coordinator.selectLanguage($0) })) {
            ForEach(LanguageRegistry.all) { language in Text(language.settingsTitle).tag(language.id) }
        }
        .pickerStyle(.menu)
        .disabled(coordinator.isRunning)
        .accessibilityIdentifier("learning-language-picker")
    }
}


private struct FlashcardSession: Identifiable {
    let id = UUID()
    let deck: FlashcardDeck
}

private struct FlashcardsView: View {
    let deck: FlashcardDeck
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var selection = 0
    @State private var dragOffset = 0.0
    @State private var dragAxis = 0
    @State private var advancing = false
    @State private var revealAllowedAfter = Date.distantPast
    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if deck.words.isEmpty {
                    ContentUnavailableView("No words yet", systemImage: "leaf", description: Text("Useful words from your conversations will appear here."))
                } else {
                    GeometryReader { geometry in
                        let direction = dragOffset > 0 ? -1 : 1
                        let destination = deck.destination(from: selection, direction: direction)
                        ZStack {
                            if destination != selection {
                                FlashcardView(word: deck.words[destination], selected: false, showContent: abs(dragOffset) > 6)
                                    .padding(.horizontal, 24).scaleEffect(0.94 + min(abs(dragOffset) / geometry.size.width, 1) * 0.06)
                                    .offset(y: 12).opacity(0.7).allowsHitTesting(false).accessibilityHidden(true)
                            }
                            FlashcardView(word: deck.words[selection], selected: true, canReveal: { !advancing && Date() > revealAllowedAfter }).id(deck.words[selection].id)
                                .padding(.horizontal, 24).offset(x: dragOffset)
                                .rotationEffect(.degrees(reduceMotion ? 0 : dragOffset / geometry.size.width * 12))
                                .simultaneousGesture(DragGesture(minimumDistance: 12)
                                    .onChanged { value in
                                        guard !advancing else { return }
                                        revealAllowedAfter = Date().addingTimeInterval(0.3)
                                        if dragAxis == 0 { dragAxis = abs(value.translation.width) > abs(value.translation.height) ? 1 : -1 }
                                        guard dragAxis == 1 else { return }
                                        let candidate = value.translation.width
                                        let target = deck.destination(from: selection, direction: candidate < 0 ? 1 : -1)
                                        dragOffset = target == selection ? candidate * 0.2 : candidate
                                    }
                                    .onEnded { value in
                                        revealAllowedAfter = Date().addingTimeInterval(0.3)
                                        let horizontal = dragAxis == 1
                                        dragAxis = 0
                                        guard !advancing else { return }
                                        let distance = value.translation.width
                                        if horizontal && (abs(distance) > geometry.size.width * 0.22 || abs(value.predictedEndTranslation.width) > geometry.size.width * 0.5) {
                                            move(distance < 0 ? 1 : -1, width: geometry.size.width)
                                        } else {
                                            withAnimation(reduceMotion ? nil : .spring(response: 0.34, dampingFraction: 0.82)) { dragOffset = 0 }
                                        }
                                    })
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }.accessibilityIdentifier("flashcard-pager")
                    HStack(spacing: 24) {
                        navigationButton("Previous word", symbol: "chevron.left", direction: -1)
                        Text("\(selection + 1) of \(deck.words.count)").font(.subheadline).monospacedDigit()
                            .foregroundStyle(MuralColor.secondary).accessibilityIdentifier("flashcard-progress")
                        navigationButton("Next word", symbol: "chevron.right", direction: 1)
                    }.padding(.bottom, 24)
                }
            }.padding(.top, 16).background(MuralColor.cream).foregroundStyle(MuralColor.ink)
                .navigationTitle("Your words").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.accessibilityIdentifier("close-flashcards") } }
        }.tint(MuralColor.ink).presentationDetents([.large]).presentationDragIndicator(.hidden).interactiveDismissDisabled()
    }
    private func navigationButton(_ label: String, symbol: String, direction: Int) -> some View {
        Button {
            move(direction, width: 500)
        } label: {
            Image(systemName: symbol).frame(width: 48, height: 48).modifier(SoftGlass())
        }.buttonStyle(.plain).accessibilityLabel(label)
            .disabled(deck.destination(from: selection, direction: direction) == selection)
    }
    private func move(_ direction: Int, width: Double) {
        guard !advancing else { return }
        let destination = deck.destination(from: selection, direction: direction)
        guard destination != selection else {
            withAnimation(reduceMotion ? nil : .spring(response: 0.34, dampingFraction: 0.82)) { dragOffset = 0 }
            return
        }
        if reduceMotion { selection = destination; dragOffset = 0; return }
        advancing = true
        withAnimation(.easeIn(duration: 0.2), completionCriteria: .logicallyComplete) {
            dragOffset = direction > 0 ? -width * 1.2 : width * 1.2
        } completion: {
            selection = destination
            dragOffset = 0
            advancing = false
        }
    }
}

private struct FlashcardView: View {
    let word: WordState
    let selected: Bool
    var canReveal: () -> Bool = { true }
    var showContent = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var revealed = false
    var body: some View {
        ZStack {
            face(back: false).modifier(FlashcardFaceRotation(angle: revealed && !reduceMotion ? 180 : 0, back: false))
                .opacity(reduceMotion && revealed ? 0 : 1).accessibilityHidden(revealed).allowsHitTesting(!revealed)
            face(back: true).modifier(FlashcardFaceRotation(angle: reduceMotion ? 180 : (revealed ? 180 : 0), back: true))
                .opacity(reduceMotion && !revealed ? 0 : 1).accessibilityHidden(!revealed).allowsHitTesting(revealed)
        }.onChange(of: selected) { _, _ in revealed = false }
    }
    private func face(back: Bool) -> some View {
        ScrollView {
            VStack(spacing: 28) {
                HStack(spacing: 10) { RecallBars(count: word.bars); Text(word.label).font(.subheadline).foregroundStyle(MuralColor.secondary) }
                    .accessibilityElement(children: .combine).accessibilityIdentifier("flashcard-proficiency")
                Text(word.lemma).font(.system(.largeTitle, design: .rounded, weight: .medium))
                    .multilineTextAlignment(.center).textSelection(.enabled).accessibilityIdentifier("flashcard-word")
                if back {
                    Text(word.meaning).font(.title3).foregroundStyle(MuralColor.secondary).multilineTextAlignment(.center)
                        .accessibilityIdentifier("flashcard-meaning")
                }
                Button(back ? "Hide meaning" : "Tap to reveal meaning") { reveal() }
                    .font(.subheadline).foregroundStyle(MuralColor.secondary).frame(minHeight: 44)
                    .accessibilityIdentifier("reveal-flashcard")
            }.opacity(showContent ? 1 : 0).padding(28).frame(maxWidth: .infinity, minHeight: 320)
                .background(.white, in: RoundedRectangle(cornerRadius: 32))
                .overlay(RoundedRectangle(cornerRadius: 32).strokeBorder(MuralColor.peach.opacity(0.65), lineWidth: 1))
                .contentShape(RoundedRectangle(cornerRadius: 32)).onTapGesture { reveal() }
                .accessibilityAction(named: back ? "Hide meaning" : "Reveal meaning") { reveal() }
                .padding(.vertical, 12)
        }.defaultScrollAnchor(.center, for: .alignment).scrollIndicators(.hidden)
    }
    private func reveal() {
        guard canReveal() else { return }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.48)) { revealed.toggle() }
    }
}

private struct FlashcardFaceRotation: AnimatableModifier {
    var angle: Double
    let back: Bool
    var animatableData: Double { get { angle } set { angle = newValue } }
    func body(content: Content) -> some View {
        content.opacity((angle >= 90) == back ? 1 : 0)
            .rotation3DEffect(.degrees(angle - (back ? 180 : 0)), axis: (x: 0, y: 1, z: 0), perspective: 0.55)
            .allowsHitTesting((angle >= 90) == back)
    }
}
