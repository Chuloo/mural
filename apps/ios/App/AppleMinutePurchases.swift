import Foundation
import Observation
import StoreKit
import MuralCore

@MainActor @Observable
final class AppleMinutePurchases {
    static let shared = AppleMinutePurchases()
    private(set) var offers: [MinuteOffer] = []
    private(set) var maximumQuantity = 1
    private(set) var busy = false
    private(set) var message: String?
    private(set) var pending = false
    private(set) var balanceRevision = 0
    private(set) var resumeSKU: String?
    private(set) var resumeQuantity = 1
    struct RecentPurchase: Identifiable {
        let id: UInt64
        let accountID: UUID
        let name: String
        let quantity: Int
        let date: Date
        let refunded: Bool
        let testPurchase: Bool
    }
    private(set) var recentPurchases: [RecentPurchase] = []
    private(set) var historyMessage: String?
    private(set) var loadingHistory = false
    @ObservationIgnored private var products: [String: Product] = [:]
    @ObservationIgnored private var listener: Task<Void, Never>?
    @ObservationIgnored private var delivering = Set<UInt64>()
    @ObservationIgnored private var checking = false
    @ObservationIgnored private let http = ManagedAccountHTTP()
    private let configuration = ManagedAccountConfiguration.load()
    private let configuredEnvironment = Bundle.main.object(forInfoDictionaryKey: "MuralApplePurchaseEnvironment") as? String ?? "live"
    private var resolvedEnvironment: String?
    private var preview: Bool {
        #if DEBUG && targetEnvironment(simulator)
        return ProcessInfo.processInfo.arguments.contains("--preview") && ProcessInfo.processInfo.arguments.contains("--preview-purchases")
        #else
        return false
        #endif
    }
    var enabled: Bool {
        if preview { return true }
        let value = Bundle.main.object(forInfoDictionaryKey: "MuralApplePurchasesEnabled")
        return value as? Bool == true || (value as? String)?.uppercased() == "YES"
    }
    var refundRequestsEnabled: Bool { enabled && !preview }
    var testPurchases: Bool { preview || resolvedEnvironment == "test" }
    private var storageKey: String { "mural.apple-purchases.v1." + (configuration?.storageScope ?? "disabled") + "." + (resolvedEnvironment ?? configuredEnvironment) }
    private func prepareEnvironment() async throws -> String {
        if let resolvedEnvironment { return resolvedEnvironment }
        let environment = configuredEnvironment == "auto"
            ? try await AppleStorePurchaseContext.shared.proof().environment : configuredEnvironment
        guard ["test", "live"].contains(environment) else { throw ManagedAccountError.invalidResponse }
        resolvedEnvironment = environment
        return environment
    }
    private func member() -> ManagedAccountSession? {
        guard let configuration else { return nil }
        return try? ManagedAccountKeychain(scope: configuration.storageScope).load()
    }
    private func current(_ session: ManagedAccountSession) -> Bool { member()?.accountID == session.accountID }
    private func attempts() throws -> [ApplePurchaseAttempt] {
        guard let data = UserDefaults.standard.data(forKey: storageKey) else { return [] }
        guard data.count <= 65_536 else { throw ManagedAccountError.invalidResponse }
        return try JSONDecoder().decode([ApplePurchaseAttempt].self, from: data)
    }
    private func save(_ attempt: ApplePurchaseAttempt) throws {
        var all = try attempts(); all.removeAll { $0.accountID == attempt.accountID }; all.append(attempt)
        UserDefaults.standard.set(try JSONEncoder().encode(all), forKey: storageKey)
    }
    private func remove(account: UUID, order: UUID? = nil) throws {
        var all = try attempts(); all.removeAll { $0.accountID == account && (order == nil || $0.orderID == order) }
        UserDefaults.standard.set(try JSONEncoder().encode(all), forKey: storageKey)
    }
    private func originalAccountRecoveryMessage(orderID: UUID?, owner: ManagedAccountSession) -> String? {
        guard let orderID, let saved = try? attempts(),
              saved.contains(where: { $0.orderID == orderID && $0.accountID != owner.accountID }) else { return nil }
        return "This purchase belongs to another Mural account. Sign in to that account and tap Check purchases to finish it."
    }
    func start() {
        guard enabled, listener == nil, !ProcessInfo.processInfo.arguments.contains("--preview") else { return }
        listener = Task { [weak self] in
            for await result in Transaction.updates {
                guard let self, let member = self.member() else { continue }
                await self.deliver(result, owner: member)
            }
        }
        Task {
            _ = try? await prepareEnvironment()
            await checkPurchases()
        }
    }
    func load() async {
        #if DEBUG && targetEnvironment(simulator)
        if preview {
            let catalog = ["small", "medium", "large"].enumerated().map { index, sku in
                ["sku": sku, "providerProduct": "chat.mural.ios.minutes.\(sku).v1", "currency": "usd", "currencyExponent": 2,
                 "totalMinor": [700, 1300, 2000][index], "estimatedMilliseconds": [2_214_000, 4_596_000, 6_966_000][index],
                 "estimateRateVersion": "preview", "scheduleVersion": "preview", "storefront": "USA", "environment": "test"] as [String: Any]
            }
            if let data = try? JSONSerialization.data(withJSONObject: catalog) { offers = (try? JSONDecoder().decode([MinuteOffer].self, from: data)) ?? [] }
            maximumQuantity = 10
            pending = ProcessInfo.processInfo.arguments.contains("--preview-purchase-pending")
            message = pending ? ApplePurchaseScope.pendingMessage(testPurchase: testPurchases) : nil
            return
        }
        #endif
        guard enabled, !busy else { return }
        busy = true; defer { busy = false }
        do {
            let expectedEnvironment = try await prepareEnvironment()
            if let owner = member() { await recoverRecordedOrders(owner: owner) }
            guard let storefront = await Storefront.current else { throw ManagedAccountError.unavailable }
            let catalog: MinuteCatalog = try await request("/v1/minutes/products", query: [URLQueryItem(name: "provider", value: "apple"),
                URLQueryItem(name: "storefront", value: storefront.countryCode)])
            try catalog.validate(storefront: storefront.countryCode)
            guard catalog.products.allSatisfy({ $0.environment == expectedEnvironment }) else { throw ManagedAccountError.invalidResponse }
            let loaded = try await Product.products(for: catalog.products.map(\.providerProduct))
            products = Dictionary(uniqueKeysWithValues: loaded.map { ($0.id, $0) })
            offers = catalog.products.filter { offer in
                guard let product = products[offer.providerProduct] else { return false }
                return matches(product, offer)
            }
            maximumQuantity = catalog.maximumQuantity
            let resumable = try member().flatMap { session in try attempts().first { $0.accountID == session.accountID && $0.canResumeCheckout } }
            resumeSKU = resumable?.offer.sku; resumeQuantity = resumable?.quantity ?? 1
            pending = try member().map { session in try attempts().contains { $0.accountID == session.accountID && !$0.canResumeCheckout } } ?? false
            message = offers.isEmpty ? "Minutes aren’t available to buy right now. Please try again later." :
                pending ? "Your purchase is still being checked. Tap Check purchases to try again." : nil
        } catch { offers = []; message = "Couldn’t load minutes. Please try again." }
    }
    func observeStorefrontChanges() async {
        guard enabled, !preview else { return }
        for await _ in Storefront.updates {
            guard !Task.isCancelled else { return }
            offers = []; products = [:]
            // A submitted order keeps its original terms; refresh offers after it finishes.
            while busy && !Task.isCancelled { try? await Task.sleep(for: .milliseconds(200)) }
            guard !Task.isCancelled else { return }
            await load()
        }
    }
    private func matches(_ product: Product, _ offer: MinuteOffer) -> Bool {
        product.type == .consumable && offer.matches(price: product.price, currency: product.priceFormatStyle.currencyCode)
    }
    func price(_ offer: MinuteOffer, quantity: Int) -> String {
        if preview { return (try? offer.price(quantity: quantity).formatted(.currency(code: offer.currency.uppercased()))) ?? "" }
        guard let product = products[offer.providerProduct], (1...maximumQuantity).contains(quantity) else { return "" }
        return (product.price * Decimal(quantity)).formatted(product.priceFormatStyle)
    }
    func buy(_ offer: MinuteOffer, quantity: Int, owner: ManagedAccountSession) async {
        if preview { message = "Purchase preview · no payment was made."; return }
        guard enabled, !busy, current(owner), (1...maximumQuantity).contains(quantity), let product = products[offer.providerProduct] else { return }
        busy = true; message = nil; defer { busy = false }
        do {
            // Finish previously credited consumables before asking StoreKit for a
            // new purchase of the same product.
            for await result in Transaction.unfinished {
                guard case .verified(let transaction) = result, transaction.productID == product.id else { continue }
                guard await deliver(result, owner: owner) else {
                    message = originalAccountRecoveryMessage(orderID: transaction.appAccountToken, owner: owner) ??
                        "Your earlier purchase needs another check. Tap Check purchases before trying again."; return
                }
            }
            guard await Storefront.current?.countryCode == offer.storefront, matches(product, offer) else { throw ManagedAccountError.invalidResponse }
            let previous = try attempts().first { $0.accountID == owner.accountID }
            // A lost create response can reuse the same immutable order. Once StoreKit was
            // invoked, recovery only checks transactions and never launches another payment.
            if let previous {
                guard previous.canResumeCheckout else { pending = true; message = "A purchase is still being checked. Tap Check purchases."; return }
            }
            var attempt = try previous?.preparingCheckout(for: offer, quantity: quantity) ??
                ApplePurchaseAttempt(accountID: owner.accountID, offer: offer, quantity: quantity)
            try save(attempt)
            struct Order: Decodable {
                struct Payment: Decodable { let orderID: UUID; let appAccountToken: UUID; let productID: String; let quantity: Int }
                let orderID: UUID; let quantity: Int; let totalMinor: Int; let currency: String; let estimatedMilliseconds: Int; let payment: Payment
            }
            let order: Order = try await request("/v1/minutes/orders", owner: owner,
                body: ["provider": "apple", "sku": offer.sku, "quantity": quantity, "storefront": offer.storefront, "scheduleVersion": offer.scheduleVersion], key: attempt.key)
            guard order.quantity == quantity, order.totalMinor == (try offer.total(quantity: quantity)), order.currency == offer.currency,
                  order.estimatedMilliseconds == offer.estimatedMilliseconds * quantity,
                  order.payment.orderID == order.orderID, order.payment.appAccountToken == order.orderID,
                  order.payment.productID == product.id, order.payment.quantity == quantity else { throw ManagedAccountError.invalidResponse }
            guard attempt.orderID == nil || attempt.orderID == order.orderID else { throw ManagedAccountError.invalidResponse }
            attempt.orderID = order.orderID; try save(attempt)
            guard current(owner), await Storefront.current?.countryCode == offer.storefront else { throw ManagedAccountError.unavailable }
            attempt.phase = .submitted; try save(attempt)
            let purchaseStarted = Date()
            let result = try await ApplePurchaseSubmission.perform(purchase: {
                try await product.purchase(options: [.quantity(quantity), .appAccountToken(order.payment.appAccountToken)])
            }, clearRejectedAttempt: {
                try self.remove(account: owner.accountID, order: order.orderID)
            })
            switch result {
            case .success(let result):
                let completed = await deliver(result, owner: owner)
                if completed, current(owner), case .verified(let returned) = result,
                   returned.productID == product.id, returned.appAccountToken != order.orderID,
                   returned.purchaseDate < purchaseStarted {
                    // StoreKit returned an earlier unfinished consumable instead of
                    // presenting a new payment. Preserve this uncharged order for retry.
                    attempt.phase = .preparing; try save(attempt)
                    pending = false
                    message = ApplePurchaseScope.completionMessage(testPurchase: testPurchases) + " Tap Continue when you’re ready to buy more minutes."
                }
            case .pending:
                attempt.phase = .awaitingApproval; try save(attempt)
                if current(owner) { pending = true; message = ApplePurchaseScope.pendingMessage(testPurchase: testPurchases) }
            case .userCancelled:
                try remove(account: owner.accountID, order: order.orderID)
                if current(owner) { pending = false; message = "Purchase canceled." }
            @unknown default: throw ManagedAccountError.unavailable
            }
        } catch {
            if current(owner) {
                pending = (try? attempts().contains { $0.accountID == owner.accountID && !$0.canResumeCheckout }) ?? true
                message = pending ? "Your purchase needs another check. Tap Check purchases before trying again." : "Couldn’t prepare your purchase. Tap Continue to try again."
            }
        }
    }
    func checkPurchases() async {
        guard enabled, !checking, !ProcessInfo.processInfo.arguments.contains("--preview"), let owner = member() else { return }
        checking = true; defer { checking = false }
        guard (try? await prepareEnvironment()) != nil else {
            message = "Couldn’t check purchases. Please try again."; return
        }
        message = nil
        var unfinished = Transaction.unfinished.makeAsyncIterator()
        guard await ApplePurchaseRecovery.check(
            recoverSavedOrders: { await self.recoverRecordedOrders(owner: owner) },
            nextUnfinished: { await unfinished.next() },
            isCurrent: { self.current(owner) },
            deliver: { _ = await self.deliver($0, owner: owner) }
        ) else { return }
        if current(owner) {
            pending = (try? attempts().contains { $0.accountID == owner.accountID && !$0.canResumeCheckout }) ?? true
            if message == nil { message = pending ? "Your purchase is still being checked. You can return later." : "Your purchases are up to date." }
            balanceRevision += 1
        }
    }
    func loadHistory() async {
        guard !loadingHistory else { return }
        recentPurchases = []; historyMessage = nil
        if preview {
            let owner = UUID(uuidString: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")!
            recentPurchases = [1, 2, 10].map { quantity in
                RecentPurchase(id: UInt64(quantity), accountID: owner, name: "Small pack", quantity: quantity,
                    date: Date(timeIntervalSince1970: 1_790_400_000), refunded: quantity == 1, testPurchase: true)
            }
            return
        }
        guard let owner = member(), enabled else { return }
        loadingHistory = true; defer { loadingHistory = false }
        guard (try? await prepareEnvironment()) != nil else {
            historyMessage = "Couldn’t check purchases. Please try again."; return
        }
        var checked = 0
        for await result in Transaction.all {
            guard current(owner), !Task.isCancelled else { recentPurchases = []; return }
            checked += 1
            if checked > 100 || recentPurchases.count >= 20 { break }
            guard case .verified(let transaction) = result, transaction.productType == .consumable,
                  transaction.productID.hasPrefix("chat.mural.ios.minutes."), let order = transaction.appAccountToken else { continue }
            do {
                let status: ApplePurchaseStatus = try await request("/v1/minutes/orders/" + order.uuidString.lowercased(), owner: owner)
                guard current(owner) else { recentPurchases = []; return }
                guard status.orderID == order, status.fulfillmentRecorded else { continue }
                let name = transaction.productID.contains(".small.") ? "Small pack" : transaction.productID.contains(".medium.") ? "Medium pack" : "Large pack"
                recentPurchases.append(RecentPurchase(id: transaction.id, accountID: owner.accountID, name: name,
                    quantity: transaction.purchasedQuantity, date: transaction.purchaseDate,
                    refunded: transaction.revocationDate != nil || (status.reversedNanoUSD.map { $0 != "0" } ?? false),
                    testPurchase: transaction.environment == .sandbox))
            } catch { historyMessage = "Some purchases couldn’t be checked. Pull down to retry." }
        }
    }
    private func recoverRecordedOrders(owner: ManagedAccountSession) async {
        guard current(owner), let saved = try? attempts() else { return }
        for attempt in saved where attempt.accountID == owner.accountID {
            guard let orderID = attempt.orderID else { continue }
            do {
                let status: ApplePurchaseStatus = try await request("/v1/minutes/orders/" + orderID.uuidString.lowercased(), owner: owner)
                guard current(owner), attempt.isFulfilled(by: status, for: owner.accountID) else { continue }
                // The server's durable grant is authoritative even if StoreKit's
                // callback was missed. Unfinished transactions can still be finished later.
                try remove(account: owner.accountID, order: orderID)
                pending = try attempts().contains { $0.accountID == owner.accountID && !$0.canResumeCheckout }
                message = ApplePurchaseScope.completionMessage(testPurchase: testPurchases); balanceRevision += 1
            } catch {
                // A failed lookup or pending order must retain its checkout lock.
            }
        }
    }
    @discardableResult
    private func deliver(_ result: VerificationResult<Transaction>, owner: ManagedAccountSession) async -> Bool {
        guard case .verified(let transaction) = result, transaction.productType == .consumable,
              transaction.productID.hasPrefix("chat.mural.ios.minutes."), let orderID = transaction.appAccountToken,
              !delivering.contains(transaction.id) else { return false }
        // StoreKit's local verification is followed by server verification and an owned durable grant.
        delivering.insert(transaction.id); defer { delivering.remove(transaction.id) }
        do {
            let environment = try await prepareEnvironment()
            guard try ApplePurchaseScope.environment(transaction.environment) == environment else { return false }
            // A notification may already have fulfilled this purchase. Confirm the
            // owned durable grant before resubmitting an older StoreKit receipt.
            let recorded: ApplePurchaseStatus? = try? await request("/v1/minutes/orders/" + orderID.uuidString.lowercased(), owner: owner)
            let status: ApplePurchaseStatus
            if let recorded, recorded.orderID == orderID, recorded.entitlementKind == "ai_value",
               recorded.state == "purchased", recorded.fulfillmentRecorded {
                status = recorded
            } else {
                status = try await request("/v1/minutes/apple/recover", owner: owner, body: ["transactionID": String(transaction.id)])
            }
            guard current(owner), status.orderID == orderID, status.entitlementKind == "ai_value",
                  status.state == "purchased", status.fulfillmentRecorded else { throw ManagedAccountError.invalidResponse }
            await transaction.finish()
            try remove(account: owner.accountID, order: orderID)
            if current(owner) {
                pending = try attempts().contains { $0.accountID == owner.accountID && !$0.canResumeCheckout }
                message = ApplePurchaseScope.completionMessage(testPurchase: testPurchases); balanceRevision += 1
            }
            return true
        } catch {
            if current(owner) {
                message = originalAccountRecoveryMessage(orderID: orderID, owner: owner) ??
                    "Your purchase is saved. We’ll check it again when Mural can connect."
            }
            return false
        }
    }
    private func request<T: Decodable>(_ path: String, query: [URLQueryItem]? = nil, owner: ManagedAccountSession? = nil,
                                      body: [String: Any]? = nil, key: UUID? = nil) async throws -> T {
        let statusPrefix = "/v1/minutes/orders/"
        let isStatus = path.hasPrefix(statusPrefix) && UUID(uuidString: String(path.dropFirst(statusPrefix.count))) != nil && body == nil
        guard let configuration, isStatus || ["/v1/minutes/products", "/v1/minutes/orders", "/v1/minutes/apple/recover"].contains(path),
              var parts = URLComponents(url: configuration.origin, resolvingAgainstBaseURL: false) else { throw ManagedAccountError.unavailable }
        parts.path = path; parts.queryItems = query
        guard let url = parts.url else { throw ManagedAccountError.unavailable }
        var request = URLRequest(url: url); request.httpMethod = body == nil ? "GET" : "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if let owner {
            guard owner.isUsable(scope: configuration.storageScope) else { throw ManagedAccountError.unavailable }
            request.setValue("Bearer " + owner.accessToken, forHTTPHeaderField: "Authorization")
        }
        if let key { request.setValue(key.uuidString, forHTTPHeaderField: "Idempotency-Key") }
        let (data, response) = try await http.send(request)
        guard (200...299).contains(response.statusCode) else { throw ManagedAccountError.unavailable }
        return try JSONDecoder().decode(T.self, from: data)
    }
}
