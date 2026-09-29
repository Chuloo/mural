# Android Play v11 release status — 29 September 2026

Version 11 (`chat.mural.android`, version name `0.1`) is a signed internal-testing release. The public Play release remains the free version 4 preview. Play purchases are not available in production.

## Build and Play Console

- The signed AAB is at `deliverables/mural-play-v11-candidate-2026-09-29/Mural-Android-Play-v11.aab` in the workspace, SHA-256 `564b3d9dcb22ac3a15c1e0cf40ef6675d1c8564df5f42596ddbb85f9e7a85887`. It uses the established upload certificate. Android unit tests, release lint, signed bundle creation and release bundle/assets checks passed locally.
- Play internal testing shows `0.1 (11) — Play minute packs` available to internal testers. A Play-installed purchase has not yet been completed.
- Eight 1080 × 1920 native screenshots and the refreshed feature graphic were submitted for Google review. The paid-release description and release notes are prepared in the repository; the public listing still describes the free preview.
- Three one-time products are saved as inactive drafts. US prices are $7, $13 and $20; Norway prices are NOK 89, 179 and 249. Other regions are unavailable. The corresponding six-row [catalog](play-catalog-draft.json) is a planning draft, not a production catalog.

## Backend deployment

The API test suite passed 409 tests and its type check before deployment. Production runs the compatible backend source at `92b4081`. The deployment used the active-call gate, encrypted database backup and retained prior image. The protected deployment record contains the rollback details.

Private configuration and migrations were unchanged. Public `/healthz` and `/readyz` passed; pricing still reports a 15% Mural service fee and usage-based AI value. Google and Apple sign-in remain available. `/v1/minutes/products?provider=play` returns `available: false` and `maximumQuantity: 1`, as expected while Play is unconfigured. The protected deployment record contains the operational review.

## Activation gates

The Google Play Developer API is enabled for the Mural Cloud project. A dedicated service account exists with no Cloud IAM role or key, and its Play Console access is restricted to Mural with app-level financial-data and order-management permissions. Play Console also assigns its baseline read-only app permissions. The production environment still has no Play service-account file or purchase-binding key.

Before enabling sales, securely provision the service-account credential and protected binding key, verify the merchant payout and service-fee setup, and approve the six-row catalog against actual Play prices and tax treatment. Then use a Play-installed build and license tester to verify purchase, server credit, consumption, restart recovery, refund and account deletion. Only after those checks should the products and v11 production track be activated and the public listing copy updated.
