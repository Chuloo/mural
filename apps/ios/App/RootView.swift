import SwiftUI
import MuralCore

struct RootView: View {
    @State private var coordinator: ConversationCoordinator
    @State private var tab = 3
    @State private var onboarding = false
    @Environment(\.scenePhase) private var scenePhase
    init(store: LearningStore) {
        let coordinator = ConversationCoordinator(store: store)
        #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--preview"), ProcessInfo.processInfo.arguments.contains("--preview-existing-user") {
            store.updatePreferences { $0.hasOnboarded = true }
        }
        if let screen = ScreenshotPreview.screen { coordinator.prepareScreenshot(screen) }
        if ScreenshotPreview.screen != nil { _tab = State(initialValue: ScreenshotPreview.tab) }
        #endif
        _coordinator = State(initialValue: coordinator)
    }
    var body: some View {
        @Bindable var coordinator = coordinator
        TabView(selection: $tab) {
            Tab("Talk", systemImage: "waveform", value: 0) {
                shell {
                    if coordinator.isRunning && coordinator.session?.classroom != nil { classroomInProgress }
                    else { TalkView(coordinator: coordinator) }
                }
            }
            Tab("Themes", systemImage: "square.grid.2x2", value: 1) {
                shell {
                    if coordinator.isRunning && coordinator.session?.classroom != nil { classroomInProgress }
                    else { ThemesView(coordinator: coordinator) { theme in coordinator.chooseTheme(theme); tab = 0 } }
                }
            }
            Tab("Words", systemImage: "book", value: 2) { shell { WordsView(coordinator: coordinator) } }
            Tab(LocalizedStringKey("Classroom"), systemImage: "graduationcap", value: 3) { shell { BeginnerClassroomView(coordinator: coordinator) } }
        }
        .tint(MuralColor.accent)
        .sheet(isPresented: $coordinator.showSettings) { SettingsView(coordinator: coordinator) }
        .sheet(isPresented: $coordinator.showAIConsent, onDismiss: { coordinator.resumeAfterAIConsent() }) {
            AIConsentView(agree: { coordinator.acceptAIConsent() }, decline: { coordinator.declineAIConsent() })
        }
        .fullScreenCover(isPresented: $onboarding) { OnboardingView(coordinator: coordinator) { coordinator.store.updatePreferences { $0.hasOnboarded = true }; onboarding = false } }
        .alert("A little interruption", isPresented: Binding(get: { coordinator.error != nil || coordinator.store.error != nil }, set: { if !$0 { coordinator.error = nil; coordinator.store.error = nil } })) {
            Button("OK", role: .cancel) { coordinator.error = nil; coordinator.store.error = nil }
        } message: { Text(LocalizedStringKey(coordinator.error ?? coordinator.store.error ?? "")) }
        .onAppear {
            let arguments = ProcessInfo.processInfo.arguments
            #if DEBUG && targetEnvironment(simulator)
            if arguments.contains("--preview") && arguments.contains("--preview-onboarding") {
                onboarding = !coordinator.store.preferences.hasOnboarded
                return
            }
            #endif
            onboarding = !coordinator.store.preferences.hasOnboarded && !arguments.contains("--preview") && !AudioVerification.requested
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { coordinator.background() }
            else if phase == .active { coordinator.resume() }
        }
        #if DEBUG
        .task {
            if AudioVerification.requested { await AudioVerification.run(coordinator) }
            else if ProcessInfo.processInfo.arguments.contains("--ended-conversation") { coordinator.prepareEndedPreview() }
        }
        #endif
    }
    private var classroomInProgress: some View {
        ContentUnavailableView {
            Label("Classroom", systemImage: "graduationcap.fill")
        } description: {
            Text("A classroom is active.")
        } actions: {
            Button("Return to classroom", systemImage: "arrow.uturn.backward") { tab = 3 }
                .buttonStyle(.borderedProminent).tint(MuralColor.buttonFill)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(MuralBackdrop())
    }
    private func shell<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        NavigationStack {
            content()
                .background(MuralBackdrop())
                .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { coordinator.showSettings = true } label: { Image(systemName: "slider.horizontal.3") }
                        .accessibilityLabel("Settings")
                        .buttonStyle(.bordered)
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
    }
}

struct TalkView: View {
    @Environment(\.locale) private var interfaceLocale
    @Bindable var coordinator: ConversationCoordinator
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var typing = false
    @State private var transcript: SessionRecord?
    @State private var lookup: WordLookup?
    var body: some View {
        let _ = interfaceLocale
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 0) {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("Talk")
                                .font(.largeTitle.bold())
                            Text(coordinator.selectedTheme.map { $0.id == "current" ? $0.title : L10n.text($0.title) } ?? L10n.format("A little everyday %@", L10n.text(coordinator.language.name)))
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: coordinator.state == .active ? "waveform" : "waveform.slash")
                            .font(.title3.weight(.semibold)).foregroundStyle(MuralColor.accent)
                            .frame(width: 44, height: 44)
                            .background(.background, in: Circle())
                            .accessibilityHidden(true)
                    }
                    .padding(.top, 12).padding(.bottom, 4)
                    Spacer(minLength: 8)
                    MuralOrb(energy: max(max(coordinator.outputLevel, coordinator.offlinePronunciationLevel), coordinator.inputLevel * 0.45), listening: coordinator.state == .active && !coordinator.isMuted, active: coordinator.state != .closing, skin: OrbSkin(rawValue: coordinator.store.preferences.orbSkinID ?? "classic") ?? .classic, avatar: coordinator.store.preferences.avatar ?? AvatarSelection(),
                     speechEnergy: max(coordinator.outputLevel, coordinator.offlinePronunciationLevel),
                     listeningEnergy: coordinator.inputLevel)
                        .frame(width: typeSize.isAccessibilitySize ? 170 : 220, height: typeSize.isAccessibilitySize ? 180 : 222).padding(.vertical, 8)
                    Text(LocalizedStringKey(coordinator.status)).font(.system(.caption, design: .default)).foregroundStyle(MuralColor.secondary)
                        .contentTransition(.numericText()).padding(.top, 6).padding(.bottom, 16).accessibilityAddTraits(.updatesFrequently)
                        .accessibilityIdentifier("conversation-status")
                    captionArea
                    Spacer(minLength: 12)
                    controls
                    Text(LocalizedStringKey(coordinator.microphoneLabel)).font(.caption2).foregroundStyle(MuralColor.secondary).padding(.top, 10)
                        .accessibilityIdentifier("microphone-status")
                    HStack(spacing: 24) {
                        if coordinator.state == .active {
                            Button("Type instead", systemImage: "keyboard") { typing = true }
                            Button("A little help", systemImage: "sparkles") { coordinator.help() }
                        } else if coordinator.session == nil {
                            Text("Reply in whichever language comes to you.").foregroundStyle(MuralColor.secondary)
                        } else if !coordinator.isRunning {
                            Button("New conversation", systemImage: "arrow.counterclockwise") { coordinator.resetConversation() }
                                .accessibilityIdentifier("new-conversation")
                        }
                    }.font(.caption).padding(.top, 6).padding(.bottom, 12)
                    if let notice = coordinator.notice {
                        Text(LocalizedStringKey(notice)).font(.footnote).foregroundStyle(MuralColor.secondary).multilineTextAlignment(.center).padding(.bottom, 12)
                    }
                }.padding(.horizontal, 20).frame(maxWidth: .infinity).frame(minHeight: geometry.size.height)
            }.scrollIndicators(.hidden)
        }
        .sheet(isPresented: $typing) { TypedReplyView(coordinator: coordinator) }
        .animation(.smooth(duration: 0.35), value: coordinator.state)
        .sheet(item: $transcript) { session in
            TranscriptView(session: session, meaningLanguage: coordinator.store.preferences.meaningLanguage)
        }
        .sheet(item: $lookup) { item in LookupView(item: item, coordinator: coordinator) }
    }
    private var captionArea: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let user = coordinator.userPassage {
                HStack {
                    Spacer(minLength: 28)
                    Text(String(user.text.suffix(160))).font(.body)
                        .foregroundStyle(MuralColor.ink)
                        .padding(16)
                        .background(MuralColor.peach.opacity(0.48), in: RoundedRectangle(cornerRadius: 22))
                        .accessibilityLabel(Text(L10n.text("YOU") + ": " + String(user.text.suffix(160))))
                }
            }
            VStack(alignment: .leading, spacing: 12) {
            Text(linkedCaption).font(.system(coordinator.assistantPassage == nil ? .largeTitle : .title2, design: .default, weight: .medium))
                .tracking(-0.3).multilineTextAlignment(.leading).tint(MuralColor.ink)
                .environment(\.openURL, OpenURLAction { url in
                    guard url.scheme == "mural-word", let components = URLComponents(url: url, resolvingAgainstBaseURL: false), let word = components.queryItems?.first?.value else { return .discarded }
                    lookup = WordLookup(word: word, sentence: coordinator.caption); return .handled
                }).accessibilityIdentifier("target-caption")
            if coordinator.language.id == "zh" { PinyinHelp(text: coordinator.caption) }
            if coordinator.store.preferences.meaningVisible {
                Text(coordinator.assistantPassage == nil ? MeaningLanguages.greeting(in: coordinator.store.preferences.meaningLanguage) : !coordinator.meaning.isEmpty ? coordinator.meaning : coordinator.translating ? L10n.text("Finding the meaning…") : "")
                    .font(.subheadline).foregroundStyle(MuralColor.secondary).multilineTextAlignment(.leading)
                    .accessibilityIdentifier("meaning-caption")
                if let error = coordinator.meaningError {
                    VStack(spacing: 6) {
                        Text(LocalizedStringKey(error)).foregroundStyle(MuralColor.secondary)
                        Button("Try meaning again") { coordinator.retryMeaning() }
                    }.font(.caption).multilineTextAlignment(.center)
                }
            }
            if coordinator.working { ProgressView("Checking that for you…").font(.caption).tint(MuralColor.secondary) }
            if let sources = coordinator.session?.topics.last?.sources, !sources.isEmpty {
                Button("Sources", systemImage: "link") { transcript = coordinator.session }.font(.caption)
            }
            }
            .padding(20)
            .frame(minHeight: typeSize.isAccessibilitySize ? 100 : 105)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(MuralColor.elevatedSurface.opacity(0.94), in: RoundedRectangle(cornerRadius: 26))
            .overlay { RoundedRectangle(cornerRadius: 26).stroke(MuralColor.peach.opacity(0.45), lineWidth: 1) }
            .shadow(color: MuralColor.secondary.opacity(0.06), radius: 14, y: 5)
        }
    }
    private var linkedCaption: AttributedString {
        var result = AttributedString()
        for segment in CaptionWords.segments(coordinator.caption, languageID: coordinator.language.id) {
            var part = AttributedString(segment.text)
            if coordinator.assistantPassage != nil, let word = segment.lookup {
                var components = URLComponents(); components.scheme = "mural-word"; components.host = "lookup"
                components.queryItems = [URLQueryItem(name: "word", value: word)]
                part.link = components.url
            }
            part.foregroundColor = MuralColor.ink; result.append(part)
        }
        return result
    }
    @ViewBuilder private var controls: some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 20) { controlButtons }
        } else {
            controlButtons
        }
    }
    private var controlButtons: some View {
        HStack(alignment: .center, spacing: 20) {
            Button { coordinator.toggleMeaning() } label: {
                VStack(spacing: 6) {
                        Image(systemName: coordinator.store.preferences.meaningVisible ? "captions.bubble.fill" : "captions.bubble")
                        .frame(width: 48, height: 48).modifier(SoftGlass(tint: coordinator.store.preferences.meaningVisible ? MuralColor.accent.opacity(0.18) : .white.opacity(0.4)))
                    Text("Meaning").font(.caption2)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
                .accessibilityLabel(Text(LocalizedStringKey(coordinator.store.preferences.meaningVisible ? "Hide meaning subtitles" : "Show meaning subtitles")))
                .accessibilityValue(Text(LocalizedStringKey(coordinator.store.preferences.meaningVisible ? "On" : "Off")))
            Button {
                if coordinator.state == .active { coordinator.toggleMute() }
                else if !coordinator.isRunning { coordinator.start() }
            } label: {
                ZStack {
                    Circle().fill(LinearGradient(colors: [MuralColor.peach, MuralColor.orange], startPoint: .topLeading, endPoint: .bottomTrailing))
                    Circle().stroke(.white.opacity(0.85), lineWidth: 3).padding(3)
                    if coordinator.state == .connecting || coordinator.state == .closing { ProgressView().tint(Color(red: 0.212, green: 0.165, blue: 0.133)) }
                    else { Image(systemName: coordinator.isMuted && coordinator.state == .active ? "mic.slash" : "mic").font(.system(size: 28, weight: .regular)).contentTransition(.symbolEffect(.replace)) }
                }.foregroundStyle(Color(red: 0.212, green: 0.165, blue: 0.133))
                    .frame(width: 84, height: 84).padding(6)
                    .background(MuralColor.orange.opacity(0.13), in: Circle())
                    .shadow(color: MuralColor.orange.opacity(0.14), radius: 10, y: 6)
            }.buttonStyle(.plain).padding(.bottom, 18)
                .disabled(coordinator.state == .connecting || coordinator.state == .closing)
                .accessibilityLabel(Text(LocalizedStringKey(coordinator.state == .active ? (coordinator.isMuted ? "Unmute microphone" : "Mute microphone") : "Start conversation")))
                .accessibilityIdentifier("start-conversation")
            Button { if coordinator.isRunning { coordinator.end() } else { transcript = coordinator.session } } label: {
                VStack(spacing: 6) {
                    Image(systemName: coordinator.isRunning ? "phone.down" : "text.bubble").frame(width: 48, height: 48).modifier(SoftGlass())
                    Text(LocalizedStringKey(coordinator.isRunning ? "End" : "Transcript")).font(.caption2)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(Text(LocalizedStringKey(coordinator.isRunning ? "End conversation" : "Conversation transcript")))
                .disabled(coordinator.session == nil)
        }.foregroundStyle(MuralColor.ink)
    }
}

struct WordLookup: Identifiable { var id = UUID(); var word: String; var sentence: String }
struct LookupView: View {
    @Environment(\.locale) private var interfaceLocale
    let item: WordLookup
    let coordinator: ConversationCoordinator
    @State private var explanation: String?
    @State private var error: String?
    var body: some View {
        let _ = interfaceLocale
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Text(item.word).font(.system(.largeTitle, design: .default, weight: .medium))
                if coordinator.language.id == "zh" { PinyinHelp(text: item.word) }
                Text(item.sentence).font(.title3).foregroundStyle(MuralColor.secondary)
                if let explanation { Text(explanation).font(.body).textSelection(.enabled) }
                else if let error { Text(LocalizedStringKey(error)).foregroundStyle(MuralColor.secondary) }
                else { ProgressView("Finding the meaning…") }
                Spacer()
            }.padding(28).frame(maxWidth: .infinity, alignment: .leading).background(MuralBackdrop())
                .navigationTitle(L10n.text("A little meaning")).navigationBarTitleDisplayMode(.inline)
        }.presentationDetents([.medium, .large])
            .task { do { explanation = try await coordinator.lookup(word: item.word, sentence: item.sentence) } catch { self.error = L10n.error(error) } }
    }
}

struct TypedReplyView: View {
    @Environment(\.locale) private var interfaceLocale
    let coordinator: ConversationCoordinator
    @State private var text = ""
    @State private var sending = false
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool
    var body: some View {
        let _ = interfaceLocale
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Text("Say it your way.").font(.system(.title, design: .default, weight: .semibold))
                TextField(L10n.format("Reply in %@ or another language", L10n.text(coordinator.language.name)), text: $text, axis: .vertical).lineLimit(3...6).focused($focused).padding(18).background(MuralColor.surface, in: RoundedRectangle(cornerRadius: 14))
                Button { sending = true; Task { await coordinator.sendTyped(text); sending = false; dismiss() } } label: {
                    HStack { Text(LocalizedStringKey(sending ? "Sending…" : "Send reply")); Spacer(); Image(systemName: "arrow.up") }.padding(18).foregroundStyle(.white).background(MuralColor.buttonFill, in: Capsule())
                }.disabled(sending || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Spacer()
            }.padding(26).foregroundStyle(MuralColor.ink).background(MuralBackdrop())
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }.presentationDetents([.medium, .large]).onAppear { focused = true }
    }
}
