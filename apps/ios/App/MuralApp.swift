import SwiftUI

@main struct MuralApp: App {
    @State private var store: LearningStore?
    @State private var startupError: String?
    init() {
        do { _store = State(initialValue: try LearningStore(inMemory: ProcessInfo.processInfo.arguments.contains("--preview") || AudioVerification.requested)) }
        catch { _startupError = State(initialValue: "Mural couldn’t open its learning record. Your existing data has not been replaced.") }
    }
    var body: some Scene {
        WindowGroup {
            if let store { RootView(store: store).preferredColorScheme(.light) }
            else {
                ContentUnavailableView("Let’s try again", systemImage: "externaldrive.badge.exclamationmark", description: Text(startupError ?? "The learning record is unavailable."))
                    .preferredColorScheme(.light)
            }
        }
    }
}
