import SwiftUI
import MuralCore

/// Keeps Han text selectable and word links intact, with an optional pinyin or kana reading below it.
struct ReadingHelp: View {
    let text: String
    let languageID: String
    @State private var expanded = true

    var body: some View {
        if let reading = ReadingAid.reading(text, languageID: languageID) {
            let kana = languageID == "ja"
            VStack(spacing: 6) {
                Button { expanded.toggle() } label: {
                    Label(kana ? (expanded ? "Hide reading" : "Show reading") : (expanded ? "Hide pinyin" : "Show pinyin"),
                          systemImage: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption)
                }.buttonStyle(.plain).accessibilityIdentifier(kana ? "kana-toggle" : "pinyin-toggle")
                if expanded {
                    Text(reading).font(.callout).textSelection(.enabled)
                        .accessibilityIdentifier(kana ? "kana-reading" : "pinyin-reading")
                }
            }.foregroundStyle(MuralColor.secondary)
        }
    }
}
