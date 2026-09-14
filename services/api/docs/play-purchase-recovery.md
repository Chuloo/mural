# Play purchase recovery reference

Play recovery resolves an existing server order when a device has an unconsumed purchase token but has lost its local order ID. It never creates a new order or uses client purchase state to grant minutes.

## Server interface

```ts
await purchases.reconcile('play', {
  kind: 'recovery',
  accountID: authenticatedMemberID,
  purchaseToken,
});
```

`accountID` is the authenticated member from the server session. `purchaseToken` contains 1–4096 printable ASCII characters without spaces. No order ID, SKU, quantity, price, currency or state is accepted in this recovery input. Guest, deleted and missing accounts fail before a provider request.

`PlayMinuteProvider.recover(accountID, purchaseToken)` returns `VerifiedMinutePurchase` for the accounting service. Calling that method alone does not grant minutes. The normal `MinutePurchases.reconcile` entry point performs immutable event recording, idempotency, wallet locking, refunds and fulfillment.

The provider retrieves Google's purchase, resolves its `obfuscatedExternalProfileId` against the saved order binding, checks the accompanying account binding, and requires that order to belong to the signed-in member. Product, quantity, environment, original currency, amount and successful refunds use the same verification as a normal purchase. The encrypted receipt and retry job commit before accounting. A server crash between these steps leaves recoverable work.

Concurrent recovery requests and later normal purchase notifications settle the same transaction once. Pending purchases grant no time and are not consumed. The delivery worker refetches them, grants after verified completion, and consumes after the wallet transaction commits. Recovery remains available when new sales are disabled.

## HTTP wiring contract

The app integration uses authenticated `POST /v1/minutes/play/recover`, with a JSON body containing only `purchaseToken` and an 8192-byte body limit. This provider module does not register the route. The route applies member authentication, strict request validation and the existing account request limits before calling the accounting service.

A successful response is `MinutePurchaseStatus`: resolved `orderID`, `state`, `grantedMilliseconds`, `reversedMilliseconds`, `reversalOutstandingMilliseconds`, and `fulfillmentRecorded`. It contains no purchase token, provider order number or account identity. Verification failures at the accounting boundary return the existing `purchase_verification_failed` error; raw provider responses are never reflected.

## Android lifecycle

The client can submit newly observed or restored unconsumed tokens without recovering an old local UUID. After success, the resolved order ID supports ordinary status requests. Consumed purchases are restored through the signed-in server wallet; they do not need another grant.

Google documents `queryPurchasesAsync()` on connection/foreground as recovery for missed callbacks, pending payments and device changes. Client callbacks are discovery signals; provider verification remains authoritative. [Play Billing integration](https://developer.android.com/google/play/billing/integrate), [purchase verification](https://developer.android.com/google/play/billing/security).

Related: [minute provider integration](minute-provider-integration.md), [commerce configuration](minute-commerce-configuration.md).
