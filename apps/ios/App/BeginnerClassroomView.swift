import SwiftUI
import UniformTypeIdentifiers
import MuralCore

private struct ClassroomChoiceStyle: ButtonStyle {
    let reduceMotion: Bool
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: configuration.isPressed)
    }
}

struct BeginnerClassroomView: View {
    @Environment(\.locale) private var interfaceLocale
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Bindable var coordinator: ConversationCoordinator
    @State private var settings: ClassroomSettings
    @State private var curriculum: OgdenCurriculum?
    @State private var libraryError: String?
    @State private var search = ""
    @State private var customLanguage = ""
    @State private var articleURL = ""
    @State private var showingArticleFile = false
    @State private var importingArticle = false
    @State private var settingsExpanded = true
    @State private var typing = false
    @State private var showingArticleText = false
    @State private var showingPractice = false
    @State private var sheet: ClassroomSheet?
    private enum ClassroomSheet: String, Identifiable {
        case words, source
        var id: String { rawValue }
    }

    init(coordinator: ConversationCoordinator) {
        self.coordinator = coordinator
        _settings = State(initialValue: coordinator.store.preferences.classroom ?? ClassroomSettings())
    }
    private var classroomRunning: Bool { coordinator.isRunning && coordinator.session?.classroom != nil }
    private var displaySettings: ClassroomSettings {
        classroomRunning ? (coordinator.pendingClassroomSettings ?? coordinator.session?.classroom ?? settings) : settings
    }
    private var currentWord: OgdenWord? {
        guard displaySettings.course == .english850 else { return nil }
        return curriculum?.word(id: displaySettings.wordID)
    }
    private var canChangeWord: Bool {
        !coordinator.pendingClassroomWord && (!coordinator.isRunning || (classroomRunning && coordinator.state == .active))
    }

    var body: some View {
        let _ = interfaceLocale
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                courseSelector
                classroomOrb
                settingsCard
                if settings.course == .articleReading && !classroomRunning {
                    articleImportCard.transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
                if displaySettings.course != .guided {
                    lessonCard.id(displaySettings.course.rawValue + (currentWord?.id ?? "article"))
                        .transition(reduceMotion ? .opacity : .scale(scale: 0.96).combined(with: .opacity))
                }
                transcriptCard
                if displaySettings.course == .english850 {
                    Button("Practice and review", systemImage: "checklist") { showingPractice = true }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .disabled(coordinator.isRunning || curriculum == nil)
                        .accessibilityIdentifier("ogden-practice-entry")
                    Button { sheet = .words } label: {
                        Label("Browse all 850 words", systemImage: "books.vertical")
                            .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                            .background(MuralColor.surface, in: RoundedRectangle(cornerRadius: 16))
                    }.buttonStyle(.plain).disabled(!canChangeWord || curriculum == nil)
                }
                if let libraryError { Text(LocalizedStringKey(libraryError)).foregroundStyle(.red) }
                if let error = coordinator.error { Text(LocalizedStringKey(error)).foregroundStyle(.red).multilineTextAlignment(.leading) }
                if let notice = coordinator.notice { Text(LocalizedStringKey(notice)).font(.footnote) }
                if coordinator.isRunning && !classroomRunning {
                    Text("A free conversation is active. End it before changing classroom settings.").font(.footnote)
                }
                if displaySettings.course == .english850 {
                    Button("Vocabulary source and license", systemImage: "info.circle") { sheet = .source }
                        .font(.footnote).frame(minHeight: 44).disabled(curriculum == nil)
                }
            }.padding(24)
        }
        .foregroundStyle(MuralColor.ink)
        .animation(reduceMotion ? nil : .smooth(duration: 0.32), value: settings.course)
        .animation(reduceMotion ? nil : .smooth(duration: 0.28), value: currentWord?.id)
        .safeAreaInset(edge: .bottom) { controls.padding(.horizontal, 20).padding(.vertical, 12).background(.regularMaterial) }
        .task {
            do {
                curriculum = try OgdenCurriculum.load()
                if let saved = coordinator.store.preferences.classroom { settings = saved }
                customLanguage = settings.customTargetLanguage ?? ""
                normalizeSelection()
            } catch { libraryError = L10n.error(error) }
        }
        .onChange(of: settings) { _, value in
            guard !coordinator.isRunning else { return }
            coordinator.store.updatePreferences { $0.classroom = value }
        }
        .onChange(of: settings.articleText) { _, _ in
            guard !importingArticle else { return }
            settings.articleParagraphIndex = 0
        }
        .onChange(of: coordinator.isRunning) { _, running in
            if running { settingsExpanded = false }
            if !running, let saved = coordinator.store.preferences.classroom { settings = saved }
        }
        .onChange(of: coordinator.store.preferences.classroom) { _, value in
            if !coordinator.isRunning, let value { settings = value }
        }
        .sheet(item: $sheet) { selected in
            NavigationStack {
                Group {
                    switch selected {
                    case .words: wordLibrary
                    case .source: sourceNotice
                    }
                }
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { sheet = nil } } }
            }
        }
        .fileImporter(isPresented: $showingArticleFile, allowedContentTypes: articleContentTypes) { result in
            guard case .success(let url) = result else { return }
            importArticleFile(url)
        }
        .sheet(isPresented: $typing) { TypedReplyView(coordinator: coordinator) }
        .sheet(isPresented: $showingPractice) { OgdenLearningView(coordinator: coordinator) }
    }

    private var courseSelector: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Course").font(.headline)
            HStack(spacing: 8) {
                courseButton(.english850, title: "English 850", icon: "text.book.closed")
                courseButton(.guided, title: "Guided learning", icon: "bubble.left.and.bubble.right")
                courseButton(.articleReading, title: "Article reading", icon: "doc.text")
            }
            .frame(maxWidth: .infinity)
        }
        .disabled(coordinator.isRunning || importingArticle)
        .accessibilityIdentifier("classroom-course-picker")
    }
    private func courseButton(_ course: ClassroomCourse, title: String, icon: String) -> some View {
        Button { settings.course = course; normalizeSelection() } label: {
            HStack(spacing: 6) {
                Image(systemName: icon).font(.caption)
                Text(LocalizedStringKey(title)).font(.caption.weight(.semibold)).lineLimit(1)
            }.frame(maxWidth: .infinity, minHeight: 42).padding(.horizontal, 10)
                .foregroundStyle(settings.course == course ? .white : MuralColor.ink)
                .background(settings.course == course ? MuralColor.buttonFill : MuralColor.surface, in: Capsule())
        }.buttonStyle(ClassroomChoiceStyle(reduceMotion: reduceMotion))
            .scaleEffect(settings.course == course && !reduceMotion ? 1.02 : 1)
            .animation(reduceMotion ? nil : .smooth(duration: 0.24), value: settings.course == course)
            .accessibilityAddTraits(settings.course == course ? .isSelected : [])
    }

    private var settingsCard: some View {
        DisclosureGroup("Learning settings", isExpanded: $settingsExpanded) {
            VStack(alignment: .leading, spacing: 16) {
                LabeledContent("Teaching language") {
                    Picker("Teaching language", selection: $settings.teachingLanguage) {
                        ForEach(TeachingLanguage.allCases) { Text($0.nativeName).tag($0) }
                    }.pickerStyle(.menu).labelsHidden()
                }.frame(minHeight: 44)
                if settings.course == .guided {
                    LabeledContent("Target language") {
                        Picker("Target language", selection: $settings.targetLanguageID) {
                            ForEach(LanguageRegistry.all) { Text($0.nativeName).tag($0.id) }
                            Text("Other language").tag("other")
                        }.pickerStyle(.menu).labelsHidden()
                    }.frame(minHeight: 44)
                    if settings.targetLanguageID == "other" {
                        TextField("Language name (optional)", text: $customLanguage)
                            .textInputAutocapitalization(.words)
                            .onChange(of: customLanguage) { _, value in settings.customTargetLanguage = value.isEmpty ? nil : value }
                        Text("After you start, tell the teacher which language you want to learn.")
                            .font(.footnote).foregroundStyle(MuralColor.secondary)
                    }
                }
                if settings.course == .articleReading {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Article mode").font(.subheadline)
                        Picker("Article mode", selection: $settings.articleMode) {
                            Text("Read, then check understanding").tag(ArticleReadingMode.readThenCheck)
                            Text("Explain each paragraph").tag(ArticleReadingMode.explainParagraph)
                        }.pickerStyle(.menu).labelsHidden()
                    }.frame(minHeight: 44, alignment: .leading)
                }
            }.padding(.top, 12)
        }
        .disabled(coordinator.isRunning || importingArticle)
        .padding(16).background(MuralColor.panels[0], in: RoundedRectangle(cornerRadius: 16))
    }

    private var articleImportCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Article web address", systemImage: "link").font(.headline)
            TextField("Paste article URL", text: $articleURL)
                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).frame(minHeight: 44)
                .background(MuralColor.background, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityIdentifier("article-url-input")
            Button(action: importURL) {
                HStack {
                    if importingArticle { ProgressView() }
                    Label("Import web page", systemImage: "arrow.down.circle")
                }.frame(maxWidth: .infinity, minHeight: 44)
            }.buttonStyle(.borderedProminent).tint(MuralColor.buttonFill).foregroundStyle(.white)
                .disabled(articleURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || importingArticle)
                .accessibilityIdentifier("article-url-import")
            Divider()
            Button("Import article file", systemImage: "doc.badge.plus") { showingArticleFile = true }
                .frame(minHeight: 44).accessibilityIdentifier("article-file-import")
            DisclosureGroup("Paste article text", isExpanded: $showingArticleText) {
                VStack(alignment: .leading, spacing: 12) {
                    TextField("Article title (optional)", text: Binding(get: { settings.articleTitle ?? "" }, set: { settings.articleTitle = $0.isEmpty ? nil : boundedArticleTitle($0) }))
                        .frame(minHeight: 44)
                    TextEditor(text: Binding(get: { settings.articleText ?? "" }, set: { value in
                        if value.utf16.count > 40_000 { libraryError = L10n.text(ArticleImporter.ImportError.articleTooLong.localizationKey) }
                        else { settings.articleText = value; libraryError = nil }
                    }))
                        .frame(minHeight: 160).scrollContentBackground(.hidden).padding(8)
                        .background(MuralColor.background, in: RoundedRectangle(cornerRadius: 10))
                        .accessibilityLabel("Article text")
                    Text("Paste the complete article here. Maximum 40,000 characters; nothing is silently shortened.")
                        .font(.footnote).foregroundStyle(MuralColor.secondary)
                }.padding(.top, 12)
            }
            Text("Supported formats: PDF, DOCX, EPUB, TXT, Markdown, RTF, and HTML.")
                .font(.footnote).foregroundStyle(MuralColor.secondary)
        }
        .disabled(importingArticle)
        .padding(16).background(MuralColor.surface, in: RoundedRectangle(cornerRadius: 16))
    }
    private var lessonCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            if displaySettings.course == .articleReading {
                articleCard
            } else if displaySettings.course == .english850 {
                HStack {
                    Text("Current word").font(.caption).foregroundStyle(MuralColor.secondary)
                    Spacer()
                    if let word = currentWord, let index = curriculum?.words.firstIndex(of: word) {
                        Text(verbatim: "\(index + 1) / 850").font(.caption).monospacedDigit()
                    }
                }
                if let word = currentWord {
                    Text(word.term).font(.system(.largeTitle, design: .default, weight: .semibold))
                        .accessibilityIdentifier("classroom-current-word")
                    Text(displaySettings.teachingLanguage == .chinese ? word.meaningZhCn : word.definitionEn)
                        .foregroundStyle(MuralColor.secondary)
                    Text(word.exampleEn).font(.title3)
                    if displaySettings.teachingLanguage == .chinese { Text(word.exampleZhCn).font(.subheadline) }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 8)], spacing: 8) {
                        actionButton("Play British pronunciation", icon: "speaker.wave.2", action: .pronounceBritish)
                        actionButton("Play American pronunciation", icon: "speaker.wave.2.fill", action: .pronounceAmerican)
                        actionButton("Play example", icon: "quote.bubble", action: .readExample)
                    }.font(.caption)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 8)], spacing: 8) {
                        actionButton("Explain grammar", icon: "text.book.closed", action: .grammar)
                        actionButton("Explain meaning", icon: "character.book.closed", action: .meaning)
                    }.font(.caption)
                    DisclosureGroup("Word details") {
                        VStack(alignment: .leading, spacing: 8) {
                            Button { coordinator.performClassroomAction(.pronounceBritish, settings: displaySettings) } label: { LabeledContent("British pronunciation", value: word.ipaUk) }
                                .disabled(coordinator.classroomActionPending || coordinator.state == .connecting || coordinator.state == .closing || (coordinator.isRunning && !classroomRunning))
                            Button { coordinator.performClassroomAction(.pronounceAmerican, settings: displaySettings) } label: { LabeledContent("American pronunciation", value: word.ipaUs) }
                                .disabled(coordinator.classroomActionPending || coordinator.state == .connecting || coordinator.state == .closing || (coordinator.isRunning && !classroomRunning))
                            if !word.synonyms.isEmpty {
                                Text("Related words").fontWeight(.medium)
                                Text(verbatim: word.synonyms.joined(separator: ", "))
                            }
                        }.font(.subheadline).padding(.top, 8)
                    }.font(.subheadline)
                    HStack(spacing: 20) {
                        Button("Repeat", systemImage: "repeat") { coordinator.repeatClassroomWord() }
                            .disabled(!classroomRunning || !canChangeWord)
                        Button("Next word", systemImage: "arrow.right") {
                            if let next = curriculum?.next(after: word.id) { select(next) }
                        }.disabled(!canChangeWord || curriculum?.next(after: word.id) == nil)
                            .accessibilityIdentifier("classroom-next-word")
                    }.font(.subheadline).padding(.top, 4)
                    if coordinator.pendingClassroomWord { ProgressView("Changing word…").font(.footnote) }
                } else if libraryError == nil { ProgressView("Loading the word list…") }
            }
        }
        .padding(20).frame(maxWidth: .infinity, alignment: .leading)
        .background(MuralColor.surface, in: RoundedRectangle(cornerRadius: 16))
    }

    private var articleCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title = displaySettings.articleTitle?.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty {
                Text(title).font(.title2.bold())
            } else {
                Text("Article reading").font(.title2.bold())
            }
            if let source = displaySettings.articleSource, !source.isEmpty {
                Text("Source: \(source)").font(.footnote).foregroundStyle(MuralColor.secondary).textSelection(.enabled)
            }
            if let paragraph = displaySettings.currentArticleParagraph, !paragraph.isEmpty {
                Text(L10n.format("Paragraph %lld / %lld", displaySettings.articleParagraphIndex + 1, displaySettings.articleParagraphs.count))
                    .font(.caption).foregroundStyle(MuralColor.secondary)
                Text(paragraph).font(.body).textSelection(.enabled)
                HStack {
                    Button("Previous paragraph", systemImage: "chevron.left") { coordinator.selectArticleParagraph(displaySettings.articleParagraphIndex - 1) }
                        .disabled(displaySettings.articleParagraphIndex <= 0)
                    Button("Next paragraph", systemImage: "chevron.right") { coordinator.selectArticleParagraph(displaySettings.articleParagraphIndex + 1) }
                        .disabled(displaySettings.articleParagraphIndex + 1 >= displaySettings.articleParagraphs.count)
                }.disabled(coordinator.pendingClassroomWord || coordinator.classroomActionPending || coordinator.state == .connecting || coordinator.state == .closing)
                HStack {
                    Button("Explain paragraph", systemImage: "text.book.closed") { coordinator.performArticleAction(.explain, settings: displaySettings) }
                    Button("Check understanding", systemImage: "questionmark.circle") { coordinator.performArticleAction(.comprehension, settings: displaySettings) }
                }.font(.caption).disabled(coordinator.state != .active || coordinator.classroomActionPending || coordinator.pendingClassroomWord)
            } else {
                Text("Paste an article above to begin reading together.").foregroundStyle(MuralColor.secondary)
            }
        }
    }

    private var classroomOrb: some View {
        VStack(spacing: 6) {
            MuralOrb(energy: max(max(coordinator.outputLevel, coordinator.offlinePronunciationLevel), coordinator.inputLevel * 0.45),
                     listening: coordinator.state == .active && !coordinator.isMuted,
                     active: coordinator.state != .closing,
                     skin: OrbSkin(rawValue: coordinator.store.preferences.orbSkinID ?? "classic") ?? .classic,
                     avatar: coordinator.store.preferences.avatar ?? AvatarSelection(),
                     speechEnergy: max(coordinator.outputLevel, coordinator.offlinePronunciationLevel),
                     listeningEnergy: coordinator.inputLevel)
                .frame(height: classroomRunning ? 180 : 130)
            Text(LocalizedStringKey(coordinator.status)).font(.caption).foregroundStyle(MuralColor.secondary)
        }.frame(maxWidth: .infinity).padding(.vertical, 2)
    }

    private func actionButton(_ title: String, icon: String, action: ClassroomAction) -> some View {
        Button { coordinator.performClassroomAction(action, settings: displaySettings) } label: {
            Label(LocalizedStringKey(title), systemImage: icon).frame(maxWidth: .infinity, minHeight: 44).padding(.horizontal, 10)
                .background(MuralColor.background, in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain)
            .disabled(coordinator.classroomActionPending || coordinator.state == .connecting || coordinator.state == .closing || (coordinator.isRunning && !classroomRunning))
    }

    private var transcriptCard: some View {
        Group {
            if coordinator.session?.classroom != nil {
                VStack(alignment: .leading, spacing: 12) {
                    Text(LocalizedStringKey(coordinator.status)).font(.caption).foregroundStyle(MuralColor.secondary)
                        .accessibilityIdentifier("classroom-status")
                    if let text = coordinator.assistantPassage?.text, !text.isEmpty {
                        Text("Teacher").font(.caption.bold())
                        Text(text).font(.body).textSelection(.enabled).accessibilityIdentifier("classroom-caption")
                    }
                    if let text = coordinator.userPassage?.text, !text.isEmpty {
                        Text("Your latest answer").font(.caption.bold())
                        Text(text).foregroundStyle(MuralColor.secondary).textSelection(.enabled)
                    }
                    if classroomRunning {
                        Button("Ask by typing", systemImage: "keyboard") { typing = true }
                        Button("Explain more simply", systemImage: "questionmark.circle") { coordinator.help() }
                            .disabled(coordinator.state != .active || coordinator.pendingClassroomWord)
                    }
                }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
                    .background(MuralColor.surface, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            Button {
                if classroomRunning { coordinator.toggleMute() }
                else { coordinator.startClassroom(settings: settings) }
            } label: {
                Label {
                    Text(LocalizedStringKey(classroomRunning ? (coordinator.isMuted ? "Unmute" : "Mute") : "Start lesson"))
                } icon: { Image(systemName: classroomRunning ? (coordinator.isMuted ? "mic.slash" : "mic") : "waveform") }
                .frame(maxWidth: .infinity).padding(15).foregroundStyle(.white).background(MuralColor.buttonFill, in: Capsule())
            }
            .disabled(coordinator.state == .connecting || coordinator.state == .closing || (coordinator.isRunning && !classroomRunning)
                      || (displaySettings.course == .english850 && currentWord == nil)
                      || (displaySettings.course == .articleReading && !displaySettings.canStartLesson)
                      || importingArticle)
            .accessibilityIdentifier("classroom-start-mute")
            if coordinator.isRunning {
                Button("End", systemImage: "phone.down") { coordinator.end() }
                    .padding(15).foregroundStyle(.red).background(MuralColor.surface, in: Capsule()).accessibilityIdentifier("classroom-end")
            }
        }.buttonStyle(.plain).font(.headline)
    }

    private var wordLibrary: some View {
        VStack(spacing: 0) {
            LibrarySearchField(prompt: "Search words", text: $search).padding(.horizontal, 20)
            List {
                let words = curriculum?.search(search) ?? []
                if words.isEmpty { Text("No matching words") }
                ForEach(words) { word in
                    Button { if select(word) { sheet = nil } } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(word.term).font(.headline)
                            Text(displaySettings.teachingLanguage == .chinese ? word.meaningZhCn : word.definitionEn)
                                .font(.subheadline).foregroundStyle(MuralColor.secondary).lineLimit(2)
                        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(!canChangeWord)
                }
            }.listStyle(.plain)
        }.navigationTitle(L10n.text("English 850 words")).navigationBarTitleDisplayMode(.inline)
    }
    private var sourceNotice: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("The 850-word library includes English and Chinese content. Vietnamese explanations are generated by your teacher during the lesson.")
                Text("This library uses Skivein's Chinese learner edition (longlong-skyligo/Ogden), adapted from ogden.munch.love.")
                Text("Some entries were revised for clearer meanings and more natural examples. Related words may have different meanings or uses.")
                Link("Original vocabulary source", destination: OgdenCurriculum.sourceURL)
                Link("MIT License", destination: OgdenCurriculum.licenseURL)
                DisclosureGroup("Full attribution and license (English)") {
                    Text(curriculum?.notice ?? "").font(.footnote).textSelection(.enabled)
                }.font(.footnote)
            }.padding(24)
        }.navigationTitle(L10n.text("Vocabulary source and license")).navigationBarTitleDisplayMode(.inline)
    }
    @discardableResult private func select(_ word: OgdenWord) -> Bool {
        guard canChangeWord else { return false }
        if classroomRunning { return coordinator.selectClassroomWord(word) }
        settings.wordID = word.id
        coordinator.store.updatePreferences { $0.classroom = settings }
        return true
    }
    private func normalizeSelection() {
        if settings.course == .english850, settings.wordID == nil {
            settings.wordID = curriculum?.words.first?.id
            coordinator.store.updatePreferences { $0.classroom = settings }
        } else if settings.course == .english850, curriculum?.word(id: settings.wordID) == nil {
            libraryError = "This word is unavailable. Choose another word from the library."
        }
    }

    private func boundedArticleTitle(_ title: String) -> String {
        var result = ""
        for character in title {
            guard result.utf16.count + String(character).utf16.count <= 200 else { break }
            result.append(character)
        }
        return result
    }

    private var articleContentTypes: [UTType] {
        [.pdf, .plainText, .rtf, .html, UTType(filenameExtension: "md")!, UTType(filenameExtension: "docx")!, UTType(filenameExtension: "epub")!]
    }

    private func importArticleFile(_ url: URL) {
        libraryError = nil
        importingArticle = true
        Task {
            defer { importingArticle = false }
            do {
                let imported = try await ArticleImporter.importFile(from: url)
                guard imported.text.utf16.count <= 40_000 else { throw ArticleImporter.ImportError.articleTooLong }
                settings.articleTitle = boundedArticleTitle(imported.title); settings.articleText = imported.text
                settings.articleSource = imported.source; settings.articleParagraphIndex = 0
                settingsExpanded = false; showingArticleText = false
            } catch { libraryError = importErrorText(error) }
        }
    }
    private func importURL() {
        let value = articleURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        libraryError = nil; importingArticle = true
        Task {
            defer { importingArticle = false }
            do {
                let imported = try await ArticleImporter.importURL(value)
                guard imported.text.utf16.count <= 40_000 else { throw ArticleImporter.ImportError.articleTooLong }
                settings.articleTitle = boundedArticleTitle(imported.title); settings.articleText = imported.text
                settings.articleSource = imported.source; settings.articleParagraphIndex = 0
                settingsExpanded = false; showingArticleText = false
            } catch { libraryError = importErrorText(error) }
        }
    }
    private func importErrorText(_ error: Error) -> String {
        if let error = error as? ArticleImporter.ImportError { return L10n.text(error.localizationKey) }
        return error.localizedDescription
    }
}
