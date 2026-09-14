# Android preview readiness — 13 September 2026

The current deliverable is an installable **debug guest preview for adults 18+**. It has not been uploaded to Google Play. Paid checkout and AI-output reporting remain disabled. The listing copy now describes the eligible ten-minute guest trial and optional personal OpenAI key.

Follow-up on 14 September: the six store screenshots and feature graphic are now prepared from an isolated build containing later layout corrections. The named APK below predates those corrections. Its audit remains specific to that file; build a fresh distributable after the final source freeze.

## Inspected artifact

| Field | Recorded value |
| --- | --- |
| File, relative to the repository | `../deliverables/Mural-Android-preview-2026-09-13.apk` |
| Size | 65,921,525 bytes |
| SHA-256 | `8aca837413d40f15e70cbe74a14083f03bedc5d3906e0ddbe7f3ada7164604a5` |
| Package and version | `chat.mural.android`, code 1, version 0.1 |
| Android support declared | Minimum API 26; target API 36 |
| Build | Debug; debuggable; not test-only |
| Signing | APK v2 signature verifies; existing Android Debug identity retained |
| Certificate SHA-256 | `16cc94553e43e0d9dfbc0ac72f162eb163e7d26d330bcae455e212c0a790c022` |
| Packaged configuration | `https://api.mural.chat`, public Google client ID, purchases disabled, purchase environment `test` |
| Backup and cleartext traffic | Both disabled in the packaged manifest |
| Credentials and notices | Known-pattern scan: zero findings across 605 entries. All six required notice files present |

The identity, signature and configuration were read from this APK. The associated source baseline is `334ad89`, with later working-tree changes; this audit does not claim that the APK is a reproducible build of that clean commit. Freeze and record the final source and configuration when building the release AAB. Full machine-readable results are in [preview evidence](evidence/debug-preview-2026-09-13.json).

## Verified evidence

| Check | Result and boundary |
| --- | --- |
| Automated Android tests | Existing reports contain 227 passing JVM tests and 40 passing isolated UI tests, with no failures or skips. This audit hashes those reports; it does not rerun them |
| Startup | Three UI tests passed for the orb-only screen, pulse frames, compact sizing and restoration. [Rest](../../verification/android-startup/splash-orb-rest.png) and [pulse](../../verification/android-startup/splash-orb-pulse.png) images are component review evidence |
| 16 KB native runtime | Android 15 / API 35 / ARM64 reported 16,384-byte pages and passed two tests: native loading and an offline WebRTC audio/data offer. Both native libraries match this APK exactly. [Runtime evidence](evidence/16kb-runtime-verified-2026-09-13.json) |
| Live guest conversation | The recorded Spain Spanish test received ten minutes, produced a Spanish greeting and English meaning, accepted a synthetic typed reply, closed at 125 seconds and retained 475 seconds. No host microphone or speaker was used. [Live test record](../../verification/android-release-progress.md#live-guest-verification) |
| Branding files | Android's canonical launcher PNG is byte-identical to the iOS AppIcon. The 512-pixel Play icon passes its format check. Launcher masks, in-app wordmark and final listing composition still need visual sign-off |

The earlier disk-blocked 16 KB attempt is superseded by the successful runtime evidence for these native library hashes. It remains historical evidence. The old unsigned AABs also remain historical; neither contains the current guest startup flow.

## Store package completeness

| Item | Status | Next step |
| --- | --- | --- |
| App name, short description, full description, release notes | Prepared in `metadata/en-US`; current guest preview wording, adults 18+, no paid availability claim | Recheck against the uploaded candidate and enabled trial policy |
| Icon | Present: `assets/icon.png`, 512 × 512 RGBA, 62,428 bytes | Compare final launcher and listing appearance with iOS |
| Feature graphic | Prepared: `assets/feature-graphic.png`, 1024 × 500 opaque PNG | Native Mural brand and orb; compare with the final candidate |
| Six phone screenshots | All six paths prepared at 1080 × 1920 | English controls with Spanish learning and English meanings; actual UI with synthetic conversation/vocabulary fixtures |
| Graphic alt text | Prepared in `assets/README.md` | Enter it with the corresponding images in Console |
| Other listing locales | Not prepared | Choose initial markets before committing to localized listings |
| Reviewer access | Not complete | Provide private, reliable funded access covering speech without requiring a reviewer-owned provider key |
| Declarations | Draft inventory only | Finish Data safety, content rating, adults-only audience, ads, deletion resource, permissions and country availability in Console |

Google requires an icon, feature graphic and at least two screenshots; this project targets six screenshots to show its core experience. The existing 1080 × 2424 review captures exceed the allowed 2:1 screenshot ratio. Capture the app in the final layout rather than cropping or stretching those images. [Google's preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en-GB)

## Remaining release checks, in order

1. **Complete account continuity.** Run a live guest-to-Google transfer and verify the same allowance after login, token renewal and restart. Cover an account that already received its trial, cancellation, sign-out and account deletion. Local integration tests are recorded; the fresh live guest profile has not completed this journey.
2. **Complete safety and privacy operations.** Enable and verify in-app AI-output reports before Play release, including excerpt consent, delivery, restricted operator access and 30-day cleanup. Verify the external account-deletion path and finalize the declarations against the deployed services.
3. **Finish the actual-cost paid flow before enabling sales.** The approved model uses actual AI cost, a default 15% Mural fee and separately quoted payment costs/buffer. Purchased minutes are estimates. Wallet fulfillment, voice/helper settlement, refunds, Play-signed purchase recovery, taxes and launch prices still require verification. [Prepared pricing wording](paid-listing-copy.md) stays outside the current listing.
4. **Test the supported devices.** Exercise API 26 before promising that minimum. On a physical Android phone, verify microphone, speaker, wired/Bluetooth routes, interruptions and reconnects. Repeat a short conversation and meaning test for every advertised language; the recorded live guest test covers Spanish from Spain only.
5. **Review the listing artwork and finish access.** The feature graphic and six screenshots are prepared with [capture evidence](evidence/play-assets-2026-09-14.json). Compare them with the final candidate and iOS branding, then finish private reviewer instructions, support handling, audience/rating and launch markets.
6. **Freeze and package the Play candidate.** The owner-requested Documents signing backup is verified by the release operator. It is on the same Mac; an off-device copy remains advisable. Build the current release AAB from a recorded source/configuration snapshot and check its own manifest, secret scan, notices, native alignment and generated split APKs. Sign only with the approved upload identity.
7. **Verify the delivered build.** Install from Play internal testing, register and test the Play signing certificate for Google login, rerun relevant flows on that installed artifact and resolve the pre-launch report. The current debug signature does not certify Play login or billing. Add the website's Play CTA only after public installation works.

The trial policy currently recorded by operations is $200 per UTC day and $2,000 total, with $1.50 committed per ten-minute grant. Recheck those configurable values before rollout. Paid processing remains disabled, and pausing new free grants must preserve existing balances.
