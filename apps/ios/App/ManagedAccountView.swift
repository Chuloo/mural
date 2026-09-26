import SwiftUI
import AuthenticationServices
import MuralCore

/// Account contains identity and Mural usage only. Access and keys live in Settings → Advanced.
struct ManagedAccountView: View {
    let coordinator: ConversationCoordinator
    let store: ManagedAccountStore
    @Environment(\.dismiss) private var dismiss
    @State private var confirmSignOut = false
    @State private var confirmDeletion = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .center, spacing: 16) {
                    MuralOrb(active: false).frame(width: 64, height: 64).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(store.session == nil ? "Welcome to Mural" : "Your account")
                            .font(.system(.title2, design: .rounded, weight: .semibold))
                        if let email = store.profile?.email {
                            Text(email).font(.subheadline).textSelection(.enabled)
                                .accessibilityIdentifier("managed-account-email")
                        }
                        if let provider = store.session?.provider {
                            Text(provider == .apple ? "Signed in with Apple" : "Signed in with Google")
                                .font(.footnote).foregroundStyle(MuralColor.secondary)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                Text("Your conversations and learning history stay on this iPhone.")
                    .font(.subheadline).foregroundStyle(MuralColor.secondary)
                if store.configuration == nil && store.session == nil {
                    Text("Account sign-in isn’t available in this build. You can continue as a guest.")
                        .padding(20).frame(maxWidth: .infinity, alignment: .leading)
                        .background(.white.opacity(0.72), in: RoundedRectangle(cornerRadius: 22))
                        .accessibilityIdentifier("managedAccountUnavailable")
                } else if store.session != nil {
                    if coordinator.conversationProvider == .hosted {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Mural minutes").font(.headline)
                            if let balance = store.hostedBalance {
                                Text(balance.displayText)
                                    .font(.system(.largeTitle, design: .rounded, weight: .semibold))
                                    .accessibilityIdentifier("managed-account-minutes")
                                Text(balance.paidReserved && !balance.canStart ? "Some minutes are in use" :
                                     balance.hasPaidRemainder ? "estimated conversation time remaining" : "conversation time remaining")
                                    .font(.subheadline)
                            } else {
                                Text(store.isBusy ? "Checking your minutes…" : "Couldn’t check your minutes. Pull down to retry.")
                                    .font(.subheadline)
                            }
                            Text("Updates after each conversation.").font(.footnote)
                                .foregroundStyle(MuralColor.secondary)
                        }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
                            .background(.white.opacity(0.78), in: RoundedRectangle(cornerRadius: 22))
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Button("Sign out on all devices") { confirmSignOut = true }
                            .foregroundStyle(MuralColor.secondary)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .contentShape(Rectangle())
                            .accessibilityIdentifier("managed-account-sign-out")
                        Button("Delete account…", role: .destructive) { confirmDeletion = true }
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .controlSize(.large)
                    .padding(.top, 16)
                    .disabled(store.isBusy || coordinator.isRunning)
                } else {
                    VStack(alignment: .leading, spacing: 18) {
                        Text("Sign in to keep eligible Mural minutes with your account.")
                            .foregroundStyle(MuralColor.secondary)
                        if store.configuration?.providers.contains(.google) == true {
                            Button { store.signIn(.google) } label: {
                                Image("ManagedGoogleSignIn").resizable().scaledToFit().frame(height: 48)
                            }.buttonStyle(.plain).accessibilityLabel("Sign in with Google")
                                .accessibilityIdentifier("managed-google-sign-in")
                        }
                        if store.configuration?.providers.contains(.apple) == true {
                            ManagedAppleSignInButton { store.signIn(.apple) }
                                .frame(width: 206, height: 48)
                                .accessibilityIdentifier("managed-apple-sign-in")
                        }
                        Text("By signing in, you agree to the [Terms of use](https://mural.chat/terms/) and acknowledge the [Privacy policy](https://mural.chat/privacy/).")
                            .font(.footnote).tint(MuralColor.ink)
                            .accessibilityIdentifier("managed-sign-in-agreement")
                        Button("Continue as guest") { dismiss() }
                            .font(.subheadline).foregroundStyle(MuralColor.secondary)
                            .frame(minHeight: 44).padding(.top, 6)
                    }.disabled(store.isBusy)
                }
                if store.isBusy { ProgressView("Updating account") }
                if let message = store.message {
                    Text(message).font(.callout).foregroundStyle(MuralColor.secondary)
                        .accessibilityIdentifier("managedAccountMessage")
                }
            }.padding(24).frame(maxWidth: 520).frame(maxWidth: .infinity)
        }
        .background(MuralColor.cream).foregroundStyle(MuralColor.ink)
        .navigationTitle("Account").navigationBarTitleDisplayMode(.inline)
        .refreshable { await store.refreshAndWait() }
        .task { store.refresh() }
        .onDisappear { store.cancelSignIn() }
        .confirmationDialog("Sign out on all devices?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out") { store.signOut() }
            Button("Stay signed in", role: .cancel) {}
        } message: { Text("Your learning history stays on this iPhone. If Mural can’t reach the server, sign-out on other devices may take up to 24 hours.") }
        .confirmationDialog("Delete your Mural account?", isPresented: $confirmDeletion, titleVisibility: .visible) {
            Button("Delete account", role: .destructive, action: store.deleteAccount)
            Button("Keep account", role: .cancel) {}
        } message: {
            Text("This removes your sign-in details and account sessions and forfeits unused free minutes. Learning history stays on this iPhone. A paid balance, pending payment or active conversation must be resolved before deletion.")
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
