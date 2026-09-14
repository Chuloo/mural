import SwiftUI
import MuralCore

struct OnboardingView: View {
    let coordinator: ConversationCoordinator
    let done: () -> Void
    @State private var step = 0
    @State private var targetID: String
    @State private var meaningLanguage: String
    @State private var hasChosenMeaning: Bool
    @State private var greetingIndex = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.scenePhase) private var scenePhase

    init(coordinator: ConversationCoordinator, done: @escaping () -> Void) {
        self.coordinator = coordinator
        self.done = done
        _targetID = State(initialValue: coordinator.language.id)
        _meaningLanguage = State(initialValue: coordinator.store.preferences.meaningLanguage)
        _hasChosenMeaning = State(initialValue: coordinator.store.preferences.meaningLanguage != Preferences().meaningLanguage)
    }

    private var target: LanguageModule { LanguageRegistry.module(for: targetID) ?? .norwegian }
    private var greeting: String { reduceMotion ? target.greeting : LanguageRegistry.all[greetingIndex].greeting }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                if step == 1 {
                    Button { move(to: 0) } label: {
                        Image(systemName: "chevron.left").font(.system(size: 20, weight: .medium)).frame(width: 44, height: 44)
                            .modifier(SoftGlass())
                    }.accessibilityLabel("Back to learning language").accessibilityIdentifier("onboarding-back")
                } else { Brand() }
                Spacer()
                HStack(spacing: 6) {
                    ForEach(0..<2) { index in
                        Capsule().fill(index == step ? MuralColor.orange : MuralColor.peach)
                            .frame(width: index == step ? 24 : 8, height: 6)
                    }
                }.accessibilityElement(children: .ignore).accessibilityLabel("Step \(step + 1) of 2")
            }.padding(.horizontal, 26).padding(.top, 8).frame(height: 54)

            ScrollView {
                VStack(spacing: step == 0 ? 22 : 18) {
                    VStack(spacing: 4) {
                        MuralOrb().frame(height: typeSize.isAccessibilitySize ? 80 : step == 0 ? 134 : 74)
                        Text(greeting)
                            .font(.system(size: typeSize.isAccessibilitySize ? 46 : step == 0 ? 60 : 48, weight: .medium, design: .rounded))
                            .tracking(-2).id(greeting)
                            .transition(.opacity)
                            .frame(height: step == 0 ? 76 : 60)
                            .accessibilityIdentifier("onboarding-greeting")
                    }.padding(.top, step == 0 ? 8 : 0).accessibilityElement(children: .ignore).accessibilityLabel("Welcome to Mural")

                    Group {
                        if step == 0 { languageStep }
                        else { meaningStep }
                    }.id(step).transition(reduceMotion ? .identity : .opacity.combined(with: .offset(y: 14)))
                    if step == 1 && typeSize.isAccessibilitySize { consentDetails }
                }.padding(.horizontal, 26).padding(.bottom, 22)
            }.scrollIndicators(.hidden).id(step)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 12) {
                if step == 1 && !typeSize.isAccessibilitySize { consentDetails }
                Button(step == 0 ? "Continue" : "Agree and continue") { advance() }
                    .font(.system(.headline, design: .rounded)).multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity).padding(.vertical, 19)
                    .background(MuralColor.orange, in: Capsule())
                    .accessibilityIdentifier("onboarding-continue")
                if !typeSize.isAccessibilitySize {
                    Text(step == 0 ? "We’ll find your pace through conversation." : "You can change both languages in Settings.")
                        .font(.caption).foregroundStyle(MuralColor.secondary).multilineTextAlignment(.center)
                }
            }.padding(.horizontal, 26).padding(.top, 16).padding(.bottom, 16)
                .background(MuralColor.cream)
        }
        .background(OnboardingBackground())
        .foregroundStyle(MuralColor.ink).tint(MuralColor.ink)
        .interactiveDismissDisabled()
        .sensoryFeedback(.selection, trigger: targetID)
        .task(id: reduceMotion) {
            guard !reduceMotion else { return }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(3.8)) } catch { return }
                guard !Task.isCancelled else { return }
                if scenePhase == .active {
                    withAnimation(.easeInOut(duration: 0.6)) { greetingIndex = (greetingIndex + 1) % LanguageRegistry.all.count }
                }
            }
        }
    }

    private var consentDetails: some View {
        VStack(spacing: 12) {
            Text(AIProcessingConsent.summary)
                .font(.footnote).foregroundStyle(MuralColor.secondary).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("onboarding-ai-consent")
            Link("Privacy policy", destination: URL(string: "https://mural.chat/privacy/")!)
                .font(.footnote).underline().accessibilityIdentifier("onboarding-privacy-policy")
        }
    }

    private var languageStep: some View {
        VStack(spacing: 18) {
            Text("What would you\nlike to speak?")
                .font(.system(.title2, design: .rounded, weight: .semibold)).tracking(-0.5)
                .multilineTextAlignment(.center).accessibilityIdentifier("onboarding-language-title")
            VStack(spacing: 10) {
                ForEach(LanguageRegistry.all) { language in
                    Button { targetID = language.id } label: {
                        HStack(spacing: 14) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(language.nativeName).font(.system(.headline, design: .rounded))
                                Text(language.settingsTitle).font(.caption).foregroundStyle(MuralColor.secondary)
                            }
                            Spacer()
                            Image(systemName: targetID == language.id ? "checkmark.circle.fill" : "circle")
                                .font(.title3).foregroundStyle(targetID == language.id ? MuralColor.orange : MuralColor.secondary.opacity(0.4))
                        }.padding(.horizontal, 18).padding(.vertical, 13).frame(maxWidth: .infinity)
                            .background(targetID == language.id ? .white.opacity(0.92) : .white.opacity(0.52), in: RoundedRectangle(cornerRadius: 22))
                            .overlay { RoundedRectangle(cornerRadius: 22).strokeBorder(targetID == language.id ? MuralColor.orange.opacity(0.55) : .clear, lineWidth: 1.5) }
                    }.buttonStyle(.plain)
                        .accessibilityLabel(language.settingsTitle)
                        .accessibilityAddTraits(targetID == language.id ? .isSelected : [])
                        .accessibilityIdentifier("onboarding-language-\(language.id)")
                }
            }
        }
    }

    private var meaningStep: some View {
        VStack(spacing: 22) {
            VStack(spacing: 10) {
                Text("A little help,\nin your language.")
                    .font(.system(.title2, design: .rounded, weight: .semibold)).tracking(-0.5)
                Text("Mural speaks \(target.name). Choose the language you read most easily for meanings.")
                    .font(.subheadline).foregroundStyle(MuralColor.secondary)
            }.multilineTextAlignment(.center).accessibilityIdentifier("onboarding-meaning-title")
            Picker("Subtitle language", selection: Binding(get: { meaningLanguage }, set: { meaningLanguage = $0; hasChosenMeaning = true })) {
                ForEach(MeaningLanguages.all, id: \.self) { Text($0).tag($0) }
            }.pickerStyle(.menu).font(.system(.headline, design: .rounded))
                .padding(20).frame(maxWidth: .infinity)
                .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 22))
                .accessibilityIdentifier("onboarding-meaning-picker")
            VStack(spacing: 8) {
                Text(target.greeting).font(.system(.title2, design: .rounded, weight: .medium))
                if target.id == "zh", let reading = MandarinPinyin.reading(target.greeting) {
                    Text(reading).font(.callout).foregroundStyle(MuralColor.secondary)
                }
                Text(MeaningLanguages.greeting(in: meaningLanguage)).font(.body).foregroundStyle(MuralColor.secondary)
                    .accessibilityIdentifier("onboarding-meaning-example")
                Text("Turn meanings on whenever you need a hand.").font(.caption).foregroundStyle(MuralColor.secondary).padding(.top, 8)
            }.multilineTextAlignment(.center).padding(.vertical, 12)
        }
    }

    private func advance() {
        if step == 0 {
            if !hasChosenMeaning && meaningLanguage == target.name {
                let preferredNames = Locale.preferredLanguages.map { identifier in
                    let code = Locale(identifier: identifier).language.languageCode?.identifier ?? identifier
                    if code == "zh" { return "Chinese (Simplified)" }
                    return LanguageRegistry.module(for: code)?.name ?? Locale(identifier: "en").localizedString(forLanguageCode: code)?.capitalized ?? ""
                }
                meaningLanguage = preferredNames.first { MeaningLanguages.all.contains($0) && $0 != target.name }
                    ?? MeaningLanguages.all.first { $0 != target.name } ?? "English"
            }
            move(to: 1)
        } else {
            coordinator.selectLanguage(targetID)
            coordinator.selectMeaningLanguage(meaningLanguage)
            coordinator.store.updatePreferences { $0.meaningVisible = true; $0.aiConsentVersion = AIProcessingConsent.version }
            done()
        }
    }

    private func move(to value: Int) {
        withAnimation(reduceMotion ? nil : .smooth(duration: 0.4)) { step = value }
    }
}

enum AIProcessingConsent {
    static let version = 1
    static let summary = "With your permission, Mural sends audio and selected text to OpenAI to provide conversations and meanings. Provider retention rules apply."
    enum ConsentError: LocalizedError {
        case required
        var errorDescription: String? { "Before using AI features, open Talk and tap the microphone to review how OpenAI processes your audio and text." }
    }
}

struct AIConsentView: View {
    let agree: () -> Void
    let decline: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Image(systemName: "waveform.bubble").font(.system(size: 32, weight: .light)).foregroundStyle(MuralColor.orange)
            Text("Before we talk.").font(.system(.title, design: .rounded, weight: .semibold))
                .accessibilityIdentifier("ai-consent-title")
            Text(AIProcessingConsent.summary).font(.body)
            Text("Your learning record is stored on this iPhone. Mural does not save raw audio. You can keep browsing your saved words and conversations without agreeing.")
                .font(.subheadline).foregroundStyle(MuralColor.secondary)
            Link("Privacy policy", destination: URL(string: "https://mural.chat/privacy/")!).font(.subheadline).underline()
            Button("Agree and continue", action: agree).font(.headline).frame(maxWidth: .infinity).padding(18)
                .background(MuralColor.orange, in: Capsule()).accessibilityIdentifier("ai-consent-agree")
            Button("Not now", action: decline).font(.subheadline).frame(maxWidth: .infinity)
                .accessibilityIdentifier("ai-consent-decline")
        }.padding(28).foregroundStyle(MuralColor.ink).tint(MuralColor.ink)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).background(MuralColor.cream)
            .presentationDetents([.large]).interactiveDismissDisabled()
    }
}

private struct OnboardingBackground: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 20, paused: reduceMotion || scenePhase != .active)) { timeline in
            let phase = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate * 0.14
            MeshGradient(width: 3, height: 3, points: [
                [0, 0], [0.5, 0], [1, 0],
                [0, 0.5], [Float(0.5 + sin(phase) * 0.12), Float(0.45 + cos(phase) * 0.1)], [1, 0.5],
                [0, 1], [0.5, 1], [1, 1]
            ], colors: [MuralColor.cream, MuralColor.butter.opacity(0.7), MuralColor.cream,
                        MuralColor.cream, MuralColor.peach.opacity(0.75), MuralColor.lilac.opacity(0.45),
                        MuralColor.cream, MuralColor.cream, MuralColor.cream])
        }.background(MuralColor.cream).ignoresSafeArea().accessibilityHidden(true)
    }
}
