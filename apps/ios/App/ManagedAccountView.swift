import SwiftUI
import AuthenticationServices
import MuralCore

/// Ready for a Settings navigation destination when managed accounts are configured.
struct ManagedAccountView: View {
    @Environment(\.locale) private var interfaceLocale
    @State private var store = ManagedAccountStore()
    @State private var confirmDeletion = false
    var body: some View {
        let _ = interfaceLocale
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(spacing: 12) {
                    MuralOrb(active: !store.isBusy).frame(width: 112, height: 112)
                    Text(LocalizedStringKey(store.session == nil ? "Welcome to Mural" : "Your Mural account"))
                        .font(.title2.bold()).multilineTextAlignment(.center)
                    Text("Your conversations and learning history stay on this iPhone.")
                        .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }.frame(maxWidth: .infinity).padding(.vertical, 20)
                    .background(.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                if store.configuration == nil {
                    Text("Accounts aren’t available in this build yet. You can keep practising with your own OpenAI API key in Settings.")
                        .font(.body).multilineTextAlignment(.center)
                        .padding(20).background(.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .accessibilityIdentifier("managedAccountUnavailable")
                } else if let session = store.session {
                    VStack(spacing: 16) {
                        Label(LocalizedStringKey(session.provider == .apple ? "Signed in with Apple" : "Signed in with Google"), systemImage: "person.crop.circle")
                        if let email = store.profile?.email {
                            Text(email).font(.body).textSelection(.enabled)
                                .accessibilityIdentifier("managed-account-email")
                        }
                        Text("Purchases and hosted conversations aren’t available in this build.")
                            .font(.footnote).foregroundStyle(MuralColor.secondary)
                        Button("Refresh account", action: store.refresh).buttonStyle(.bordered)
                        Button("Sign out on all devices", action: store.signOut).buttonStyle(.bordered)
                        Button("Delete account", role: .destructive) { confirmDeletion = true }
                    }.padding(20).background(.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .disabled(store.isBusy)
                } else {
                    VStack(spacing: 14) {
                        Text("Mural stores your sign-in details and account sessions. You can practise with your own API key without an account.")
                            .font(.footnote).foregroundStyle(MuralColor.secondary).multilineTextAlignment(.center)
                        Text("By signing in, you agree to the [Terms of use](https://mural.chat/terms/) and acknowledge the [Privacy policy](https://mural.chat/privacy/).")
                            .font(.footnote).multilineTextAlignment(.center).tint(MuralColor.ink)
                            .accessibilityIdentifier("managed-sign-in-agreement")
                        if store.configuration?.providers.contains(.google) == true {
                            Button { store.signIn(.google) } label: {
                                Image("ManagedGoogleSignIn").resizable().scaledToFit().frame(height: 48)
                            }.buttonStyle(.plain).accessibilityLabel("Sign in with Google")
                                .accessibilityIdentifier("managed-google-sign-in")
                        }
                        if store.configuration?.providers.contains(.apple) == true {
                            ManagedAppleSignInButton { store.signIn(.apple) }.frame(width: 206, height: 48)
                                .accessibilityIdentifier("managed-apple-sign-in")
                        }
                    }.disabled(store.isBusy)
                }
                if store.isBusy { ProgressView().accessibilityLabel("Updating account") }
                if let message = store.message {
                    Text(LocalizedStringKey(message)).font(.callout).multilineTextAlignment(.center)
                        .accessibilityIdentifier("managedAccountMessage")
                }
                HStack(spacing: 24) {
                    Link("Privacy policy", destination: URL(string: "https://mural.chat/privacy/")!)
                    Link("Terms", destination: URL(string: "https://mural.chat/terms/")!)
                }.font(.footnote).tint(MuralColor.ink)
            }.padding(24).frame(maxWidth: 520).frame(maxWidth: .infinity)
        }
        .background(MuralBackdrop()).foregroundStyle(MuralColor.ink)
        .navigationTitle(L10n.text("Account")).navigationBarTitleDisplayMode(.inline)
        .task { store.refresh() }
        .onDisappear { store.cancelSignIn() }
        .confirmationDialog("Delete your Mural account?", isPresented: $confirmDeletion, titleVisibility: .visible) {
            Button("Delete account", role: .destructive, action: store.deleteAccount)
            Button("Keep account", role: .cancel) {}
        } message: {
            Text("This removes your sign-in details and account sessions and forfeits any unused free minutes. Learning history stays on this iPhone; you can remove it separately in Settings. A paid balance, pending payment or active conversation must be resolved before deletion.")
        }
    }
}

private struct ManagedAppleSignInButton: UIViewRepresentable {
    let action: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(action: action) }
    func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
        let button = ASAuthorizationAppleIDButton(type: .signIn, style: .whiteOutline)
        button.cornerRadius = 24
        button.addTarget(context.coordinator, action: #selector(Coordinator.signIn), for: .touchUpInside)
        return button
    }
    func updateUIView(_ uiView: ASAuthorizationAppleIDButton, context: Context) {
        context.coordinator.action = action; uiView.isEnabled = context.environment.isEnabled
    }
    final class Coordinator: NSObject {
        var action: () -> Void
        init(action: @escaping () -> Void) { self.action = action }
        @objc func signIn() { action() }
    }
}
