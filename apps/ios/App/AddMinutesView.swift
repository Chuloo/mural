import SwiftUI
import MuralCore
import StoreKit

struct AddMinutesView: View {
    let account: ManagedAccountStore
    @State private var purchases = AppleMinutePurchases.shared
    @State private var selected: String?
    @State private var quantity = 1
    @Environment(\.dynamicTypeSize) private var typeSize
    private var offer: MinuteOffer? { purchases.offers.first { $0.sku == selected } ?? purchases.offers.first }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Text("Choose a one-time pack. Conversation time is approximate and varies with use.")
                    .font(.subheadline).foregroundStyle(MuralColor.secondary)
                if purchases.testPurchases {
                    Text("Test purchase. You won’t be charged. Test purchases don’t add usable minutes.")
                        .font(.footnote).foregroundStyle(MuralColor.secondary)
                        .accessibilityIdentifier("minute-sandbox-note")
                }
                VStack(spacing: 10) {
                    ForEach(purchases.offers) { item in
                        Button { selected = item.sku } label: {
                            HStack(spacing: 14) {
                                Image(systemName: item.sku == offer?.sku ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 24)).accessibilityHidden(true)
                                if typeSize.isAccessibilitySize {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text("About \((try? item.minutes(quantity: 1)) ?? 0) min").font(.headline)
                                        Text(purchases.price(item, quantity: 1)).font(.subheadline)
                                    }.frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
                                } else {
                                    Text("About \((try? item.minutes(quantity: 1)) ?? 0) min").font(.headline)
                                    Spacer(minLength: 8)
                                    Text(purchases.price(item, quantity: 1)).font(.subheadline)
                                }
                            }.padding(18).frame(maxWidth: .infinity, minHeight: 58)
                                .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 20))
                        }.buttonStyle(.plain).accessibilityAddTraits(item.sku == offer?.sku ? .isSelected : [])
                            .accessibilityIdentifier("minute-offer-\(item.sku)")
                    }
                }.disabled(purchases.busy || purchases.pending)
                if purchases.busy { ProgressView("Checking minutes…") }
                if let message = purchases.message { Text(message).font(.callout).foregroundStyle(MuralColor.secondary) }
                Text("One-time purchase. No subscription. Unused purchased minutes don’t expire. Each conversation has a 15-second minimum charge.")
                    .font(.footnote).foregroundStyle(MuralColor.secondary)
                Button("Check purchases") { Task { await purchases.checkPurchases(); await account.refreshAndWait() } }
                    .disabled(purchases.busy || account.session == nil).frame(minHeight: 44)
                    .accessibilityIdentifier("minute-check-purchases")
                Text("Purchases are handled by Apple. [Terms of use](https://mural.chat/terms/) · [Privacy policy](https://mural.chat/privacy/)")
                    .font(.footnote).foregroundStyle(MuralColor.secondary)
            }.padding(24).frame(maxWidth: 520).frame(maxWidth: .infinity)
        }.background(MuralColor.cream).foregroundStyle(MuralColor.ink)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(alignment: .leading, spacing: 12) {
                if let offer {
                    Stepper(value: $quantity, in: 1...purchases.maximumQuantity) { Text("Quantity · \(quantity)") }
                        .disabled(purchases.busy || purchases.pending).accessibilityIdentifier("minute-quantity")
                    VStack(alignment: .leading, spacing: 6) {
                        Text("About \((try? offer.minutes(quantity: quantity)) ?? 0) min").font(.headline)
                    }.accessibilityElement(children: .combine).accessibilityIdentifier("minute-total")
                    Button {
                        guard let owner = account.session else { return }
                        Task { await purchases.buy(offer, quantity: quantity, owner: owner); await account.refreshAndWait() }
                    } label: {
                        Text("Continue · \(purchases.price(offer, quantity: quantity))")
                            .font(.headline).foregroundStyle(MuralColor.ink).frame(maxWidth: .infinity, minHeight: 52)
                    }.buttonStyle(.borderedProminent).tint(MuralColor.orange).clipShape(Capsule())
                        .disabled(purchases.busy || purchases.pending || account.session == nil)
                        .accessibilityIdentifier("minute-continue")
                }
                }.padding(.horizontal, 24).padding(.vertical, 12)
                    .frame(maxWidth: 568).frame(maxWidth: .infinity).background(MuralColor.cream).foregroundStyle(MuralColor.ink)
            }
            .navigationTitle("Add Mural minutes").navigationBarTitleDisplayMode(.inline)
            .task {
                await purchases.load()
                if let resume = purchases.resumeSKU { selected = resume; quantity = min(purchases.resumeQuantity, purchases.maximumQuantity) }
            }
            .onChange(of: purchases.balanceRevision) { _, _ in account.refresh() }
            .task { await purchases.observeStorefrontChanges() }
    }
}

struct ApplePurchaseHistoryView: View {
    let account: ManagedAccountStore
    @State private var purchases = AppleMinutePurchases.shared
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Text("Recent Apple purchases").font(.title2.weight(.semibold))
                Text("Purchases belong to the Mural account used at checkout. Apple reviews refund requests; minutes update after a refund is confirmed.")
                    .font(.subheadline).foregroundStyle(MuralColor.secondary)
                if purchases.loadingHistory { ProgressView("Checking purchases…") }
                ForEach(purchases.recentPurchases.filter { $0.accountID == account.session?.accountID }) { purchase in
                    ApplePurchaseHistoryRow(purchase: purchase, account: account)
                }
                if !purchases.loadingHistory && purchases.recentPurchases.isEmpty && purchases.historyMessage == nil {
                    Text("No recent Apple purchases found for this account.").foregroundStyle(MuralColor.secondary)
                }
                if let message = purchases.historyMessage { Text(message).font(.callout).foregroundStyle(MuralColor.secondary) }
                Button("Check purchases") { Task { await purchases.checkPurchases(); await purchases.loadHistory(); await account.refreshAndWait() } }
                    .frame(minHeight: 44).disabled(purchases.loadingHistory)
                Link("Purchase support", destination: URL(string: "https://reportaproblem.apple.com/")!).frame(minHeight: 44)
            }.padding(24).frame(maxWidth: 520).frame(maxWidth: .infinity)
        }.background(MuralColor.cream).foregroundStyle(MuralColor.ink)
            .navigationTitle("Purchase history").navigationBarTitleDisplayMode(.inline)
            .task { await purchases.loadHistory() }
            .refreshable { await purchases.loadHistory(); await account.refreshAndWait() }
    }
}

private struct ApplePurchaseHistoryRow: View {
    let purchase: AppleMinutePurchases.RecentPurchase
    let account: ManagedAccountStore
    @State private var refundPresented = false
    @State private var message: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(purchase.name).font(.headline)
            Text("Quantity · \(purchase.quantity)").font(.subheadline)
            Text(purchase.date, style: .date).font(.footnote).foregroundStyle(MuralColor.secondary)
            Text(ApplePurchaseScope.historyStatus(testPurchase: purchase.testPurchase, refunded: purchase.refunded)).font(.subheadline)
            if !purchase.refunded {
                Button("Request a refund") { refundPresented = true }.frame(minHeight: 44)
                    .disabled(account.session?.accountID != purchase.accountID || !AppleMinutePurchases.shared.refundRequestsEnabled)
            }
            if let message { Text(message).font(.footnote).foregroundStyle(MuralColor.secondary) }
        }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 20))
            .refundRequestSheet(for: purchase.id, isPresented: $refundPresented) { result in
                guard account.session?.accountID == purchase.accountID else { return }
                switch result {
                case .success(.success):
                    message = purchase.testPurchase ? "Your test refund request was sent to Apple. It won’t change your usable minutes."
                        : "Your refund request was sent to Apple. Minutes update after Apple confirms a refund."
                case .success(.userCancelled): message = nil
                case .failure: message = "Couldn’t confirm the refund status. Check Purchase support before trying again."
                @unknown default: message = "Check Purchase support for the status of your request."
                }
                Task { await account.refreshAndWait() }
            }
    }
}
