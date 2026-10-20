# Mural iOS 1.0 (3): App Store review record

**Submitted 25 September 2026 at 14:51 Oslo time.** App Store Connect shows **Waiting for Review** for submission `1ccffc90-6572-4777-b2a6-f261bca6298d`, iOS version 1.0, build 3, Apple ID `6816001011`, bundle ID `chat.mural.ios`. Release is set to **manual**, so approval alone will not make the app public.

## What changed from build 2

- Enabled Sign in with Apple alongside Google, with the `com.apple.developer.applesignin` entitlement. Build 3 also removes “TestFlight preview” from the public Settings footer.
- Added four native iPhone simulator screenshots: Mandarin, Italian, a word meaning sheet and Settings. Eight 1320 × 2868 PNGs are uploaded to the English (U.S.) 6.9-inch set. [Screenshot provenance and order](screenshots/en-US-2026-09-25/README.md).
- Published the [App Privacy inventory](app-privacy.md) as a nine-data-type, app-functionality, identity-linked, no-tracking label. Saved the Content Rights declaration after owner confirmation. The privacy and support URLs are live.
- Set the app price to free and availability to 175 storefronts. Disabled the untested Apple silicon Mac and Vision Pro compatibility listings. App Review contact, reviewer instructions, 18+ age rating and manual release are saved.

## Verification

- The Debug simulator build and signed Release archive compiled. The exported distribution IPA was inspected: bundle `chat.mural.ios`, build `3`, production signature (`get-task-allow=false`), Apple sign-in entitlement, camera and microphone purpose strings, export-compliance setting, and both app and WebRTC privacy manifests. Apple accepted the upload and processed build 3. WebRTC's dSYM was missing from the upload; this affects framework crash symbolication but did not block processing.
- Build 3 was installed directly on the connected iPhone 16 Pro. William reported that Apple sign-in and deletion of the new Apple-linked account both worked. The App Store signed IPA was inspected but the TestFlight-installed distribution build has not separately been exercised on the phone.
- The server's Apple revocation tests passed (4/4). After activation, `https://api.mural.chat/v1/auth/providers` reported `apple:true` and `google:true`; the API container was healthy, PostgreSQL was ready, and sanitized startup logs showed no Apple or service failure events after the iPhone test.
- App Store Connect accepted the metadata and build as **Ready for Review**, then changed the submission to **Waiting for Review**. Build 3 is available to the internal TestFlight group. Build 2 remains the external group's pending Beta App Review build; Apple does not allow a second build from version 1.0 into external beta review until that first build is approved.

## Production change and rollback

The server source stayed at revision `92f668424146d21039e2863c56fb6f78c6469545`; no API code or migration changed. The existing image `sha256:18606a281647222843ce9951b30270fa9f9a5d33fd28645f4a67dca8a4373d75` was reused. Before the configuration-only API restart, there were zero active voice sessions and an encrypted database backup was created at `/opt/mural/backups/mural-20260925T123342Z.dump.age`.

The server's prior Compose override is retained at `/opt/mural/deploy/compose.production.before-apple-20260925.yaml`. The Apple signing key is mounted read-only from `/opt/mural/deploy/apple-sign-in-20260925/` with restricted file permissions. The Mac's copy is git-ignored at `release/private/apple-sign-in-ATD6YQH372.p8` (mode 600). Do not print or commit either copy. To disable Apple sign-in, restore the prior override and recreate the API container from the retained image; do this only after checking active sessions.

## Review watch points

- Apple can still raise a policy or functional issue during review. The 10-minute guest trial gives reviewers access without an account; account sign-in and a personal OpenAI key are optional. The BYOK route may invite questions under Apple's digital-services purchase rules, although this build makes no in-app purchase or external checkout offer.
- China mainland availability can require an ICP filing for some apps. No ICP number is entered. Apple may mark that storefront unavailable independently of the global review result.
- The live privacy policy describes the hosted trial and optional accounts. Keep its preview-build wording and the App Privacy label aligned before a manual public release, especially if payments or remote learning sync are enabled later.
