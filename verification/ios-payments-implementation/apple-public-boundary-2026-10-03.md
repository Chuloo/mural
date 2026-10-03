# Apple payments on the public API

Prepared on October 3, 2026 from main `e21031e88bc5ba78b2489d7a0845ded979432560` for iOS 1.1 (25). This record covers local verification; deployment, Apple processing and physical-device acceptance must be recorded separately.

## Release behavior

The iPhone build enables StoreKit purchases at `https://api.mural.chat`. Apple's locally verified AppTransaction determines sandbox or production; the server independently verifies the signed proof. TestFlight and App Review receive test credit. App Store purchases receive paid credit. Orders, receipts, reservations, helpers, settlement and refunds retain their funding environment.

Failed app proof cannot fall back to spending paid funds. Proof is sent only to the configured HTTPS API origin, never Google OAuth. Existing Android and web requests retain production behavior. The existing hosted policy and lifetime cap of zero are preserved.

The Talk screen retains its minimal layout and native continuation sheet. Purchase actions remain orange. Sandbox purchases add one notice inside the purchase sheet and identify the account balance as test minutes. The release adds no Android splash stage or Android UI changes.

## Local checks

| Check | Result |
| --- | --- |
| API and PostgreSQL suite | 446 passed; zero failures or skips |
| Swift core | 175 passed |
| Repository contracts | 71 passed |
| Payment monitor | 13 passed |
| iPhone interface suite | 51 of 52 passed initially; the failed Tagalog menu gesture was corrected in test code, then Tagalog and Mandarin largest-text checks both passed |
| App compilation, signed archive and export | Passed |
| Archive and IPA configuration | 1.1 (25), `chat.mural.ios`, public API, purchases enabled, automatic Apple environment |
| Privacy manifests | App and WebRTC manifests present in the exported IPA |

The original UI failure artifact is retained. Its accessibility hierarchy showed that the test's swipe selected Greek rather than scrolling the native menu. The corrected test scrolls the menu container; app code and the signed archive were unchanged.

IPA SHA-256: `577653a43af55efa342d404219ae3bcc19012087c186e3d15d675263c7ad00dd`.

## Production steps

Apply migration 032, the updated actual-value runtime grants and the Apple purchase runtime grants. Preserve the live Stripe and Play catalog and protected credentials while adding Apple offers for USA and NOR in both environments. Configure production and sandbox V2 notifications to the public handler and monitor both Apple and Play history cursors.

After the first new Apple order, rollback must retain the dual-compatible API, catalog and receipt configuration. Disable new Apple intake if necessary; the old single-environment runtime cannot safely read these orders. See [funding scope](../../services/api/docs/apple-funding-scope.md).

TestFlight purchases are free sandbox transactions. Real customer charges require approval of the app version and all three first consumable products submitted with it. The approved launch prices are USD 7/13/20 and NOK 89/179/249. [Apple TestFlight testing](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testing-subscriptions-and-in-app-purchases-in-testflight/) and [first purchase submission](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/submit-an-in-app-purchase/).
