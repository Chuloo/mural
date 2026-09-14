import SwiftUI
import MuralCore

struct OgdenLearningView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var tabSelection
    @Bindable var coordinator: ConversationCoordinator
    @State private var curriculum: OgdenCurriculum?
    @State private var tab: OgdenTab = .home
    @State private var loadError: String?
    @State private var modal: OgdenModal?

    private var learning: OgdenLearningState { coordinator.store.preferences.ogdenLearning ?? OgdenLearningState() }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if curriculum != nil {
                    OgdenTeacherPresence(coordinator: coordinator, active: modal == nil)
                        .frame(height: tab == .home ? 140 : 100)
                        .padding(.top, 8)
                }
                Group {
                if let curriculum { content(curriculum) }
                else if let loadError { ContentUnavailableView(L10n.text("ogden.error.title"), systemImage: "books.vertical", description: Text(loadError)) }
                else { ProgressView(L10n.text("ogden.loading")) }
                }
                .id(tab)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            }
            .animation(reduceMotion ? nil : .smooth(duration: 0.3), value: tab)
            .background(MuralBackdrop())
            .navigationTitle(L10n.text("English 850"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(LocalizedStringKey("ogden.close")) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(LocalizedStringKey("ogden.settings")) { if let curriculum { modal = .settings(curriculum) } }
                        .accessibilityLabel(Text(LocalizedStringKey("ogden.settings")))
                }
            }
            .safeAreaInset(edge: .bottom) { tabBar }
        }
        .tint(MuralColor.accent)
        .task {
            do {
                let loaded = try OgdenCurriculum.load()
                guard learning.isValid(c: loaded) else { loadError = L10n.text("ogden.progress.invalid"); return }
                curriculum = loaded
                coordinator.prewarmOgdenAudio(wordID: coordinator.classroomSettings?.wordID ?? loaded.words.first?.id)
            } catch { loadError = L10n.error(error) }
        }
        .sheet(item: $modal) { item in
            switch item {
            case .settings(let c): OgdenSettingsView(coordinator: coordinator, curriculum: c)
            case .practice(let request): OgdenPracticeView(coordinator: coordinator, request: request)
            }
        }
        .overlay(alignment: .top) {
            if let message = coordinator.notice, modal == nil {
                Text(L10n.text(message)).font(.footnote).padding(12)
                    .background(MuralColor.surface, in: RoundedRectangle(cornerRadius: 12)).padding()
                    .onTapGesture { coordinator.notice = nil }
            }
        }
        .onDisappear { coordinator.stopOfflinePronunciation() }
    }

    @ViewBuilder private func content(_ c: OgdenCurriculum) -> some View {
        switch tab {
        case .home:
            OgdenHomeView(state: learning, onContinue: { continueLevel(c) }, onPractice: { practice(c.words.map(\.id), curriculum: c) }, onTab: { tab = $0 })
        case .levels:
            let levels = OgdenPractice.levels(curriculum: c)
            List {
                ForEach(OgdenCategory.allCases) { category in
                    Section(L10n.text("ogden.category.\(category.rawValue)")) {
                        ForEach(levels.filter { $0.category == category }) { level in
                            Button { modal = .practice(.init(curriculum: c, wordIDs: level.wordIDs, level: level)) } label: {
                                HStack {
                                    Image(systemName: learning.completedLevelIDs.contains(level.id) ? "checkmark.circle.fill" : learning.isUnlocked(level, levels: levels) ? "play.circle" : "lock")
                                    Text(L10n.format("ogden.level", level.index + 1))
                                    Spacer()
                                    Text(L10n.format("ogden.level.words", level.wordIDs.count)).foregroundStyle(MuralColor.secondary)
                                }.frame(minHeight: 44)
                            }.disabled(!learning.isUnlocked(level, levels: levels))
                        }
                    }
                }
            }.scrollContentBackground(.hidden)
        case .library:
            OgdenLibraryView(curriculum: c, state: learning, onPlay: play, onStudy: study, onFavorite: favorite)
        case .review:
            List {
                reviewSection("ogden.wrong", empty: "ogden.empty.wrong", ids: learning.wrongWordIDs, curriculum: c)
                reviewSection("ogden.favorites", empty: "ogden.empty.favorites", ids: learning.favorites, curriculum: c)
            }.scrollContentBackground(.hidden)
        }
    }

    private func reviewSection(_ title: String, empty: String, ids: Set<String>, curriculum c: OgdenCurriculum) -> some View {
        let words = c.words.filter { ids.contains($0.id) }
        return Section(LocalizedStringKey(title)) {
            if words.isEmpty { Text(LocalizedStringKey(empty)).foregroundStyle(MuralColor.secondary) }
            else {
                Button(LocalizedStringKey("ogden.start.review"), systemImage: "play.fill") { practice(words.map(\.id), curriculum: c) }.frame(minHeight: 44)
                ForEach(words) { word in row(word) }
            }
        }
    }
    private func row(_ word: OgdenWord) -> some View {
        OgdenWordRow(word: word, state: learning, onPlay: { play(word) }, onStudy: { study(word) }, onFavorite: { favorite(word) })
    }
    private var tabBar: some View {
        HStack(spacing: 0) {
            ForEach(OgdenTab.allCases) { item in
                Button { coordinator.stopOfflinePronunciation(); tab = item } label: {
                    VStack(spacing: 4) { Image(systemName: item.icon); Text(LocalizedStringKey(item.titleKey)).font(.caption) }
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .foregroundStyle(tab == item ? MuralColor.accent : MuralColor.secondary)
                        .background {
                            if tab == item {
                                RoundedRectangle(cornerRadius: 14).fill(MuralColor.accent.opacity(0.13))
                                    .matchedGeometryEffect(id: "ogden-selected-tab", in: tabSelection)
                            }
                        }
                }.buttonStyle(.plain).accessibilityAddTraits(tab == item ? .isSelected : [])
            }
        }.padding(8).background(.bar)
            .animation(reduceMotion ? nil : .smooth(duration: 0.3), value: tab)
    }
    private func play(_ word: OgdenWord) { coordinator.playOfflinePronunciation(word, accent: learning.accent) }
    private func favorite(_ word: OgdenWord) {
        var next = learning; next.toggleFavorite(wordID: word.id)
        coordinator.store.updatePreferences { $0.ogdenLearning = next }
    }
    private func study(_ word: OgdenWord) {
        if coordinator.studyOgdenWord(word) { coordinator.stopOfflinePronunciation(); dismiss() }
    }
    private func practice(_ ids: [String], curriculum: OgdenCurriculum) { modal = .practice(.init(curriculum: curriculum, wordIDs: ids, level: nil)) }
    private func continueLevel(_ c: OgdenCurriculum) {
        let levels = OgdenPractice.levels(curriculum: c)
        if let level = levels.first(where: { learning.isUnlocked($0, levels: levels) && !learning.completedLevelIDs.contains($0.id) }) {
            modal = .practice(.init(curriculum: c, wordIDs: level.wordIDs, level: level))
        } else { tab = .review }
    }
}

private enum OgdenTab: String, CaseIterable, Identifiable {
    case home, levels, library, review
    var id: String { rawValue }
    var titleKey: String { "ogden.tab.\(rawValue)" }
    var icon: String { switch self { case .home: "house"; case .levels: "flag.checkered"; case .library: "books.vertical"; case .review: "arrow.clockwise" } }
}
private enum OgdenModal: Identifiable {
    case settings(OgdenCurriculum), practice(OgdenPracticeRequest)
    var id: String { switch self { case .settings: "settings"; case .practice(let request): request.id.uuidString } }
}
private struct OgdenPracticeRequest: Identifiable {
    let id = UUID()
    let curriculum: OgdenCurriculum
    let wordIDs: [String]
    let level: OgdenLevel?
}

private struct OgdenHomeView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let state: OgdenLearningState
    let onContinue: () -> Void
    let onPractice: () -> Void
    let onTab: (OgdenTab) -> Void
    @State private var flipped = false
    // Original source's authored short sayings, retained with its Chinese translations.
    private let sayings = [
        ("Small words can carry a great light.", "微小的词，也能承载辽阔的光。"),
        ("A clear word opens a quiet door.", "一个清楚的词，能推开一扇安静的门。"),
        ("Learn the simple things, and the hard things grow kind.", "先学会简单的事，艰深的事也会变得温和。"),
        ("One word today is one step tomorrow.", "今日一词，明日一步。"),
        ("The child who listens well speaks with courage.", "善于聆听的孩子，也会勇敢表达。"),
        ("A good sentence is a small bridge between minds.", "一句好句子，是心灵之间的小桥。"),
        ("Slow study makes deep roots.", "缓慢的学习，会长出深深的根。"),
        ("Words are seeds; practice is rain.", "词语是种子，练习是雨水。"),
        ("To know a word is to find a new window.", "认识一个词，就是发现一扇新窗。"),
        ("Little by little, the voice becomes clear.", "一点一点，声音终会清晰。")
    ]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    stat("ogden.mastered", state.stats.values.filter { $0.mastery >= 3 }.count)
                    stat("ogden.favorites", state.favorites.count)
                    stat("ogden.streak", state.streak())
                }
                HStack(spacing: 12) {
                    Image(systemName: "chart.bar.fill").font(.title3).foregroundStyle(MuralColor.orange)
                    Text(LocalizedStringKey("ogden.studied"))
                    Spacer()
                    Text(state.studiedCount, format: .number).font(.title2.weight(.semibold)).contentTransition(.numericText())
                }
                .padding(16)
                .background(MuralColor.peach, in: RoundedRectangle(cornerRadius: 16))
                .animation(reduceMotion ? nil : .smooth(duration: 0.25), value: state.studiedCount)
                Button(action: onContinue) {
                    Label(LocalizedStringKey("ogden.continue"), systemImage: "play.fill").frame(maxWidth: .infinity, alignment: .leading)
                }.buttonStyle(OgdenCardButtonStyle(prominent: true))
                Button(action: onPractice) {
                    Label(LocalizedStringKey("ogden.practice.all"), systemImage: "square.grid.2x2").frame(maxWidth: .infinity, alignment: .leading)
                }.buttonStyle(OgdenCardButtonStyle())
                let day = Calendar.current.ordinality(of: .day, in: .era, for: .now) ?? 0
                let saying = sayings[day % sayings.count]
                Button { withAnimation(reduceMotion ? nil : .smooth(duration: 0.45)) { flipped.toggle() } } label: {
                    ZStack {
                        sayingFace(saying.0, hint: "ogden.tap.translation")
                            .opacity(flipped ? 0 : 1).accessibilityHidden(flipped)
                        sayingFace(ogdenChinese(saying.1, traditional: state.traditionalChinese), hint: "ogden.tap.english")
                            .rotation3DEffect(.degrees(reduceMotion ? 0 : 180), axis: (x: 0, y: 1, z: 0))
                            .opacity(flipped ? 1 : 0).accessibilityHidden(!flipped)
                    }
                    .rotation3DEffect(.degrees(reduceMotion || !flipped ? 0 : 180), axis: (x: 0, y: 1, z: 0))
                }.buttonStyle(OgdenCardButtonStyle())
                ForEach([OgdenTab.levels, .library, .review]) { tab in
                    Button { onTab(tab) } label: {
                        HStack { Label(LocalizedStringKey(tab.titleKey), systemImage: tab.icon); Spacer(); Image(systemName: "chevron.right") }
                    }.buttonStyle(OgdenCardButtonStyle())
                }
            }.padding(20)
        }
    }
    private func sayingFace(_ text: String, hint: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(text).font(.title3).multilineTextAlignment(.leading)
            Text(LocalizedStringKey(hint)).font(.footnote).foregroundStyle(MuralColor.secondary)
        }.frame(maxWidth: .infinity, minHeight: 120, alignment: .leading)
    }
    private func stat(_ key: String, _ value: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value, format: .number).font(.title2.bold()).contentTransition(.numericText())
            Text(LocalizedStringKey(key)).font(.caption).foregroundStyle(MuralColor.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(12)
            .background(MuralColor.surface, in: RoundedRectangle(cornerRadius: 14))
            .animation(reduceMotion ? nil : .smooth(duration: 0.25), value: value)
    }
}

private struct OgdenLibraryView: View {
    let curriculum: OgdenCurriculum
    let state: OgdenLearningState
    let onPlay: (OgdenWord) -> Void
    let onStudy: (OgdenWord) -> Void
    let onFavorite: (OgdenWord) -> Void
    @State private var query = ""
    @State private var category: OgdenCategory?
    private var words: [OgdenWord] {
        let hans = query.applyingTransform(.init("Hant-Hans"), reverse: false) ?? query
        return curriculum.search(hans).filter { category == nil || OgdenCategory(rawValue: $0.category) == category }
    }
    var body: some View {
        VStack(spacing: 12) {
            TextField(LocalizedStringKey("ogden.search"), text: $query).textFieldStyle(.roundedBorder).padding(.horizontal)
            LabeledContent(LocalizedStringKey("ogden.category")) {
                Picker(LocalizedStringKey("ogden.category"), selection: $category) {
                    Text(LocalizedStringKey("ogden.all")).tag(Optional<OgdenCategory>.none)
                    ForEach(OgdenCategory.allCases) { Text(L10n.text("ogden.category.\($0.rawValue)")).tag(Optional($0)) }
                }.pickerStyle(.menu).labelsHidden()
            }.padding(.horizontal).frame(minHeight: 44)
            List {
                ForEach(words) { word in
                    OgdenWordRow(word: word, state: state, onPlay: { onPlay(word) }, onStudy: { onStudy(word) }, onFavorite: { onFavorite(word) })
                }
            }.listStyle(.plain).scrollContentBackground(.hidden)
                .overlay { if words.isEmpty { ContentUnavailableView.search(text: query) } }
        }.padding(.top, 8)
    }
}

private struct OgdenWordRow: View {
    let word: OgdenWord
    let state: OgdenLearningState
    let onPlay: () -> Void
    let onStudy: () -> Void
    let onFavorite: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(word.term).font(.title3.bold())
                Text(state.accent == "uk" ? word.ipaUk : word.ipaUs).font(.subheadline).foregroundStyle(MuralColor.secondary)
                Spacer(minLength: 0)
            }
            Text(ogdenChinese(word.meaningZhCn, traditional: state.traditionalChinese))
            DisclosureGroup(LocalizedStringKey("ogden.details")) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(word.definitionEn)
                    Text(word.exampleEn)
                    Text(ogdenChinese(word.exampleZhCn, traditional: state.traditionalChinese)).foregroundStyle(MuralColor.secondary)
                    if let stats = state.stats[word.id] { Text(L10n.format("ogden.word.progress", stats.mastery, stats.correct, stats.attempts)).font(.footnote) }
                }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 8).textSelection(.enabled)
            }
            HStack {
                action("ogden.accessibility.play", "speaker.wave.2", onPlay)
                action("ogden.accessibility.study", "graduationcap", onStudy)
                Spacer()
                action(state.favorites.contains(word.id) ? "ogden.accessibility.unfavorite" : "ogden.accessibility.favorite", state.favorites.contains(word.id) ? "star.fill" : "star", onFavorite)
            }
        }.padding(.vertical, 8)
    }
    private func action(_ title: String, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(minWidth: 44, minHeight: 44) }
            .buttonStyle(.borderless).accessibilityLabel(Text(LocalizedStringKey(title)))
    }
}

private struct OgdenPracticeView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Bindable var coordinator: ConversationCoordinator
    let request: OgdenPracticeRequest
    @State private var type: OgdenPracticeType?
    @State private var run: OgdenPracticeSession?
    @State private var selected = ""
    @State private var spelling = ""
    private var learning: OgdenLearningState { coordinator.store.preferences.ogdenLearning ?? OgdenLearningState() }
    private var title: String { request.level.map { L10n.format("ogden.level", $0.index + 1) } ?? L10n.text("ogden.tab.review") }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    OgdenTeacherPresence(coordinator: coordinator)
                        .frame(height: run == nil ? 140 : 110)
                    if let run {
                        if run.finished { completion(run) }
                        else if let q = run.current, let word = request.curriculum.word(id: q.wordID) {
                            VStack(alignment: .leading, spacing: 20) { question(q, word: word, run: run) }
                                .id(q.id)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
                        }
                    } else { setup }
                    if let message = coordinator.notice { Text(L10n.text(message)).font(.footnote).foregroundStyle(MuralColor.secondary) }
                    if let message = coordinator.store.error { Text(L10n.text(message)).foregroundStyle(.red) }
                }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
                    .animation(reduceMotion ? nil : .smooth(duration: 0.3), value: run?.position)
                    .animation(reduceMotion ? nil : .smooth(duration: 0.25), value: run?.currentResult)
            }
            .background(MuralBackdrop()).navigationTitle(title)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(LocalizedStringKey("ogden.close")) { dismiss() } } }
        }.tint(MuralColor.accent).onDisappear { coordinator.stopOfflinePronunciation() }
    }
    private var setup: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(LocalizedStringKey("ogden.practice.type")).font(.headline)
            Picker(LocalizedStringKey("ogden.practice.type"), selection: $type) {
                Text(LocalizedStringKey("ogden.practice.mixed")).tag(Optional<OgdenPracticeType>.none)
                ForEach(OgdenPracticeType.allCases) { Text(L10n.text("ogden.practice.\($0.rawValue)")).tag(Optional($0)) }
            }.pickerStyle(.menu).labelsHidden()
            Text(LocalizedStringKey("ogden.practice.setup")).foregroundStyle(MuralColor.secondary)
            Button(LocalizedStringKey("ogden.practice.start"), systemImage: "play.fill") {
                coordinator.notice = nil
                let ids = request.level == nil ? request.wordIDs.shuffled() : request.wordIDs
                let all = OgdenPracticeSession.make(wordIDs: ids, type: type, curriculum: request.curriculum, maxQuestions: request.level == nil ? 10 : nil)
                guard !all.questions.isEmpty else { coordinator.notice = "ogden.practice.noQuestion"; return }
                let questions = all.questions
                run = OgdenPracticeSession(questions: questions)
                if let id = questions.first?.wordID { coordinator.prewarmOgdenAudio(wordID: id) }
            }.buttonStyle(.borderedProminent).tint(MuralColor.buttonFill).controlSize(.large)
        }
    }
    @ViewBuilder private func question(_ q: OgdenQuestion, word: OgdenWord, run: OgdenPracticeSession) -> some View {
        ProgressView(value: Double(run.position + 1), total: Double(run.questions.count))
            .tint(MuralColor.accent)
        HStack {
            Text(L10n.format("ogden.question.progress", run.position + 1, run.questions.count))
            Spacer()
            Text(L10n.format("ogden.question.score", run.score))
        }.font(.footnote).foregroundStyle(MuralColor.secondary)
        Text(L10n.text("ogden.practice.\(q.type.rawValue)")).font(.headline)
        if q.type == .listen {
            Button { coordinator.playOfflinePronunciation(word, accent: learning.accent) } label: {
                Label(LocalizedStringKey("ogden.listen"), systemImage: "speaker.wave.2.fill").frame(minHeight: 44)
            }.buttonStyle(.bordered)
        } else if q.type == .synonym {
            Text(word.term).font(.title2.bold())
            Text(ogdenChinese(word.meaningZhCn, traditional: learning.traditionalChinese)).foregroundStyle(MuralColor.secondary)
        } else {
            Text(ogdenChinese(q.prompt, traditional: learning.traditionalChinese)).font(.title3).textSelection(.enabled)
            if q.type == .cloze { Text(ogdenChinese(word.meaningZhCn, traditional: learning.traditionalChinese)).foregroundStyle(MuralColor.secondary) }
        }
        if q.type == .spelling {
            TextField(LocalizedStringKey("ogden.spelling.placeholder"), text: $spelling)
                .textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled()
                .disabled(run.currentResult != nil).onSubmit { submit(spelling) }
            Button(LocalizedStringKey("ogden.submit")) { submit(spelling) }
                .buttonStyle(.borderedProminent).tint(MuralColor.buttonFill).disabled(spelling.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || run.currentResult != nil)
        } else {
            ForEach(q.options, id: \.self) { option in
                Button { submit(option) } label: {
                    HStack {
                        Text(option)
                        Spacer()
                        if run.currentResult != nil, q.matches(option) { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                        else if run.currentResult != nil, selected == option { Image(systemName: "xmark.circle.fill").foregroundStyle(.red) }
                    }.frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                }.buttonStyle(OgdenCardButtonStyle()).disabled(run.currentResult != nil)
            }
        }
        if let correct = run.currentResult {
            Label(LocalizedStringKey(correct ? "ogden.correct" : "ogden.incorrect"), systemImage: correct ? "checkmark.circle.fill" : "arrow.clockwise.circle")
                .font(.headline).foregroundStyle(correct ? .green : .red)
                .transition(reduceMotion ? .opacity : .scale(scale: 0.92).combined(with: .opacity))
            if !correct { Text(L10n.format("ogden.answer", q.answer)) }
            Text(word.exampleEn)
            Text(ogdenChinese(word.exampleZhCn, traditional: learning.traditionalChinese)).foregroundStyle(MuralColor.secondary)
            Button(LocalizedStringKey(run.position + 1 == run.questions.count ? "ogden.finish" : "ogden.next")) { advance() }
                .buttonStyle(.borderedProminent).tint(MuralColor.buttonFill).controlSize(.large)
        }
    }
    private func submit(_ text: String) {
        guard var active = run, let wordID = active.current?.wordID, let correct = active.submit(text) else { return }
        selected = text; run = active
        var next = learning; next.recordAnswer(wordID: wordID, correct: correct)
        coordinator.store.updatePreferences { $0.ogdenLearning = next }
    }
    private func advance() {
        guard var active = run else { return }
        active.advance(); run = active; selected = ""; spelling = ""
        coordinator.stopOfflinePronunciation()
        if let id = active.current?.wordID { coordinator.prewarmOgdenAudio(wordID: id) }
        if let level = request.level, active.covers(level) {
            var next = learning; next.completeLevel(level, correct: active.score, total: active.questions.count)
            coordinator.store.updatePreferences { $0.ogdenLearning = next }
        }
    }
    private func completion(_ run: OgdenPracticeSession) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Label(LocalizedStringKey("ogden.practice.complete"), systemImage: "checkmark.circle").font(.title2)
            Text(L10n.format("ogden.practice.result", run.score, run.questions.count))
            if let level = request.level, !run.covers(level) { Text(LocalizedStringKey("ogden.practice.partial")).foregroundStyle(MuralColor.secondary) }
            Button(LocalizedStringKey("ogden.done")) { dismiss() }.buttonStyle(.borderedProminent).tint(MuralColor.buttonFill).controlSize(.large)
        }
    }
}

private struct OgdenSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: ConversationCoordinator
    let curriculum: OgdenCurriculum
    var body: some View {
        NavigationStack {
            Form {
                Section(LocalizedStringKey("ogden.settings.pronunciation")) {
                    Picker(LocalizedStringKey("ogden.accent"), selection: Binding(get: { learning.accent }, set: { value in update { $0.accent = value } })) {
                        Text(LocalizedStringKey("ogden.accent.us")).tag("us")
                        Text(LocalizedStringKey("ogden.accent.uk")).tag("uk")
                    }
                }
                Section(LocalizedStringKey("ogden.settings.chinese")) {
                    Toggle(LocalizedStringKey("ogden.traditional"), isOn: Binding(get: { learning.traditionalChinese }, set: { value in update { $0.traditionalChinese = value } }))
                }
                Section {
                    Text(L10n.format("ogden.source.words", curriculum.words.count))
                    DisclosureGroup(LocalizedStringKey("ogden.source.notice")) { Text(curriculum.notice).font(.footnote).textSelection(.enabled) }
                }
                if let error = coordinator.store.error { Text(L10n.text(error)).foregroundStyle(.red) }
            }
            .scrollContentBackground(.hidden).background(MuralBackdrop())
            .navigationTitle(LocalizedStringKey("ogden.settings"))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(LocalizedStringKey("ogden.done")) { dismiss() } } }
        }
    }
    private var learning: OgdenLearningState { coordinator.store.preferences.ogdenLearning ?? OgdenLearningState() }
    private func update(_ change: (inout OgdenLearningState) -> Void) {
        coordinator.stopOfflinePronunciation()
        var next = learning; change(&next)
        coordinator.store.updatePreferences { $0.ogdenLearning = next }
    }
}

/// Shares the classroom's selected appearance and actual realtime audio levels.
/// This is presentation only; opening a practice page never starts a voice session.
private struct OgdenTeacherPresence: View {
    @Bindable var coordinator: ConversationCoordinator
    var active = true
    @State private var visible = false

    var body: some View {
        MuralOrb(energy: max(max(coordinator.outputLevel, coordinator.offlinePronunciationLevel), coordinator.inputLevel * 0.45),
                 listening: coordinator.state == .active && !coordinator.isMuted,
                 active: active && visible && coordinator.state != .closing,
                 skin: OrbSkin(rawValue: coordinator.store.preferences.orbSkinID ?? "classic") ?? .classic,
                 avatar: coordinator.store.preferences.avatar ?? AvatarSelection(),
                     speechEnergy: max(coordinator.outputLevel, coordinator.offlinePronunciationLevel),
                     listeningEnergy: coordinator.inputLevel)
            .frame(maxWidth: .infinity)
            .onAppear { visible = true }
            .onDisappear { visible = false }
            .accessibilityIdentifier("ogden-teacher-presence")
    }
}

private struct OgdenCardButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var prominent = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(14).frame(minHeight: 48)
            .foregroundStyle(prominent ? .white : MuralColor.ink)
            .background(prominent ? MuralColor.buttonFill : MuralColor.surface, in: RoundedRectangle(cornerRadius: 16))
            .contentShape(RoundedRectangle(cornerRadius: 16))
            .scaleEffect(reduceMotion || !configuration.isPressed ? 1 : 0.97)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: configuration.isPressed)
    }
}

private func ogdenChinese(_ text: String, traditional: Bool) -> String {
    guard traditional else { return text }
    return text.applyingTransform(.init("Hans-Hant"), reverse: false) ?? text
}
