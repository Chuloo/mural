# Unsigned Android candidate audit — 13 September 2026

This historical inspection candidate builds and passes bundle validation. Account lifecycle fixes made afterward require a fresh candidate before signing. It is not signed, uploaded or approved for publication. Minute purchases are disabled in this build, and this audit made no live provider call, account request or purchase.

## Candidate and evidence

| Item | Result |
| --- | --- |
| Bundle | `release/private/android-candidate-2026-09-13/mural-0.1-1-unsigned.aab` |
| SHA-256 | `699dc931914a27d7264c88953e18fb3df0c06ed688a331f89d8bc65bc6f05812` |
| Size | 32,920,631 bytes |
| Identity | `chat.mural.android`, version code `1`, version name `0.1` |
| Android support | Minimum API 26; target API 36 |
| Build | `bundleRelease` and `lintRelease` succeeded; lint had zero errors, 42 warnings and two hints |
| Source | Base commit `b0fbc88dcef231af9391268f162aba7fb87829a6` plus uncommitted work; all 83 recorded production source/configuration files stayed unchanged during the build |
| Manifest | Not debuggable or test-only; automatic backup and cleartext traffic disabled |
| Public configuration | `https://api.mural.chat` and a Google public OAuth client ID; purchases disabled; purchase environment `test` |
| Signing | No JAR signature entries; `jarsigner` confirms the bundle is unsigned |
| Bundle tooling | Cached bundletool 1.18.0 validates the bundle and reports `PAGE_ALIGNMENT_16K` |

The [bundle report](evidence/unsigned-candidate-2026-09-13.json) records metadata, artwork, manifest, library and tool hashes. The [build record](evidence/unsigned-candidate-build-2026-09-13.json) includes the source snapshot, public configuration checks and native debug-section inventory. The candidate uses a dirty checkout, so the final release must be rebuilt and verified from a recorded candidate commit.

All 606 packaged files passed the checker’s known credential-file and secret-pattern scan. No OpenAI/Stripe secret key, Google OAuth client secret, GitHub token, private-key material or credential file was detected. This is a bounded pattern scan, not proof that every possible credential format is absent. Generated release configuration contains the expected public fields and disabled commerce flag. Its API origin is also present in the compiled DEX.

The manifest includes microphone, Internet, audio/network state, biometric, legacy fingerprint, billing and a signature-protected receiver permission. Its exported components are the launcher, Google’s permission-protected revocation service, and AndroidX’s DUMP-protected profile installer receiver. The [declaration inventory](declarations.md) now records the packaged Billing SDK and conditional hosted data flows.

## Native libraries and artwork

Both arm64 and x86_64 variants of WebRTC and AndroidX graphics pass 16 KB LOAD alignment and offset-congruence checks. All four have unaligned RELRO ends, but page rounding does not overlap writable LOAD data outside RELRO. This remains a runtime check, not a demonstrated failure. Four ARM64/API 35 split APKs generated from the exact bundle pass `zipalign -c -P 16 -v 4`. They use the standard local Android debug certificate only for test installation; the source AAB remains unsigned. [Split evidence](evidence/split-alignment-2026-09-13.json) records each package and native-library hash. An actual 16 KB runtime test is still required. [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes)

Gradle could not strip those library names and packaged them as received. The inspected 64-bit variants contain no static symbol table or debug sections; this audit did not produce native debug-symbol files.

The Play icon now meets the 512 × 512 RGBA requirement. Its alpha is uniformly opaque and its sRGB setting matches the canonical source. Every RGB pixel is unchanged from the existing 512-pixel image; [conversion evidence](evidence/icon-normalization.json) records the before/after hashes. The Android launcher image remains byte-identical to the iOS source. [Google icon specification](https://developer.android.com/distribute/google-play/resources/icon-design-specifications)

The feature graphic and all six final store screenshots are missing. Existing tall design captures are review evidence. Installed launcher masks/scale, the in-app Mural wordmark and final store artwork still need visual comparison against iOS. The prepared listing copy passes length checks and describes a BYOK preview; it must not be used to advertise hosted purchases before they work in the submitted candidate.

## Remaining release gates

1. Freeze a release commit, provide the approved upload signing setup, then rebuild and verify the signed bundle and its certificate. Confirm Play App Signing and release-certificate Google login.
2. Generate APKs from that exact bundle, verify APK 16 KB alignment and installation, and exercise WebRTC/graphics in a confirmed 16 KB runtime. Check real microphone, speaker, Bluetooth and interruption behavior on a physical Android phone.
3. Complete the current candidate’s voice, meanings-after-end, learning persistence, export/import, account renewal/deletion and accessibility tests. The unsigned artifact audit does not establish those runtime outcomes.
4. Verify guest eligibility/merge, funded voice/helper settlement, the 15-second minimum, restart economics and the approved free-trial budget against deployed services before public hosted activation.
5. Finish Play product setup and Play-signed purchase, pending, cancellation, restoration and refund tests; complete any authorized controlled live payment check. Keep sales disabled until those gates pass.
6. Produce the feature graphic and six final screenshots; finish the iOS brand comparison and revise listing copy for the enabled release scope.
7. Complete reviewer access, Data safety, privacy/deletion links, AI-report handling, content rating, audience and region declarations. Resolve pre-launch report findings and select the rollout scope.
8. Add the website’s Play CTA only when the public listing and its install work.

The release checker’s 21 automated tests pass. No release signature, upload, physical audio result or live-service result is claimed here.
