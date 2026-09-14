import Foundation
import SwiftUI
import MuralCore

@main struct MuralApp: App {
    @AppStorage(InterfaceLanguage.preferenceKey) private var interfaceLanguage = InterfaceLanguage.current.rawValue
    @State private var store: LearningStore?
    @State private var startupError: String?
    init() {
        do { try SubscriptionStore.provisionFromInstallerEnvironment() }
        catch {
            _store = State(initialValue: nil)
            _startupError = State(initialValue: L10n.error(error))
            return
        }
        do { _store = State(initialValue: try LearningStore(inMemory: ProcessInfo.processInfo.arguments.contains("--preview") || AudioVerification.requested)) }
        catch { _startupError = State(initialValue: "Mural couldn’t open its learning record. Your existing data has not been replaced.") }
    }
    var body: some Scene {
        WindowGroup {
          Group {
            if let store { RootView(store: store) }
            else {
                ContentUnavailableView("Let’s try again", systemImage: "externaldrive.badge.exclamationmark", description: Text(LocalizedStringKey(startupError ?? "The learning record is unavailable.")))
            }
          }.environment(\.locale, (InterfaceLanguage(rawValue: interfaceLanguage) ?? .current).locale)
              .environment(\.muralPageBackground, PageBackground(rawValue: store?.preferences.pageBackgroundID ?? "original") ?? .original)
        }
    }
}
