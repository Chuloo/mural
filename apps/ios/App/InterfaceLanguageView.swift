import SwiftUI
import MuralCore

struct InterfaceLanguageView: View {
    @Environment(\.locale) private var interfaceLocale
    @Environment(\.dismiss) private var dismiss
    @AppStorage(InterfaceLanguage.preferenceKey) private var selected = InterfaceLanguage.current.rawValue

    var body: some View {
        let _ = interfaceLocale
        List {
            Section {
                ForEach(InterfaceLanguage.allCases) { language in
                    Button {
                        selected = language.rawValue
                    } label: {
                        HStack(spacing: 14) {
                            Text(verbatim: language.nativeName)
                                .font(.body.weight(.medium))
                            Spacer()
                            if selected == language.rawValue { Image(systemName: "checkmark.circle.fill").foregroundStyle(MuralColor.accent) }
                        }.foregroundStyle(MuralColor.ink)
                    }
                    .accessibilityIdentifier("interface-language-\(language.rawValue)")
                    .accessibilityAddTraits(selected == language.rawValue ? .isSelected : [])
                }
            } footer: {
                Text("Changes apply immediately and are saved on this iPhone. Your learning language, meaning subtitles and voice stay the same.")
            }
        }
        .scrollContentBackground(.hidden).background(MuralBackdrop())
        .navigationTitle(L10n.text("App language")).navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden()
        .toolbar { ToolbarItem(placement: .topBarLeading) { Button { dismiss() } label: { Image(systemName: "chevron.left").foregroundStyle(MuralColor.ink) }.accessibilityLabel("Back") } }
        .accessibilityIdentifier("interface-language-page")
    }
}
