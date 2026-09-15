# Android version 4 — 14 September 2026

This is the historical v4 packaging record. The later [Play submission record](evidence/play-submission-2026-09-14.json) records v4 in review; the current source and default release spec have moved to later versions. Recheck this bundle with [specs/play-v4.json](specs/play-v4.json) using the [explicit-spec procedure](build-and-verify.md#3-validate-the-exact-bundle-and-assets). The original evidence below remains unchanged.

Version 4 adds an account-deletion support dialog with email, copy-address and web options. It retains the free-trial and personal-key experience. Paid checkout is disabled, with its environment set to `test` in both packaged builds.

The candidate was built from clean commit `cd75bdb23ee1ed5d4318ff084d7400b35306120a`. Both files use `chat.mural.android`, version 0.1 (code 4), minimum API 26 and target API 36. The API origin is `https://api.mural.chat`; the Google client ID is public configuration. No provider key or account credential is bundled.

| File in `Hej/deliverables` | Build | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `Mural-Android-preview-2026-09-14-v4.apk` | Installable debug preview | 63,496,043 | `4f0b5eb731c6bb9508a853d3d46976916ba0bf244830161facb68bbcb6d18373` |
| `Mural-Android-release-2026-09-14-v4.aab` | Upload-key-signed release bundle | 33,050,159 | `8e408404ac2c9cf397eeceac0e0d71b245b8d24884169df2b424729bc62a8946` |

The final build passed **257 JVM tests**, **49 isolated UI/device tests** and **21 release-validator tests**, with no failures or skips. Release lint reports zero errors and 44 warnings. The interface suite ran in temporary emulator user 13; the original user and display settings were restored, preserving personal app data.

All 607 bundle payload signatures verify against the approved upload certificate. The preview retains the existing personal debug certificate. Bundletool validation, all eight store assets and all five generated ARM64 split signatures and 16 KB alignment checks pass. Direct inspection of the bundle-derived APK confirms version 4, release debugging disabled, and payments disabled. Known-credential pattern scans found zero matches in the APK and AAB. These scans do not prove the absence of every possible credential format.

The packaged ARM64 libraries remain byte-identical to the earlier successful 16 KB native runtime test. That earlier test covers graphics/WebRTC loading and offline peer setup; this audit did not install the exact version 4 splits in that runtime. No physical microphone, speaker, Bluetooth or API 26 runtime test is claimed.

[Candidate evidence](evidence/signed-candidate-2026-09-14-v4.json), [preview checks](evidence/debug-preview-2026-09-14-v4.json), [full interface results](evidence/isolated-ui-2026-09-14-v4.json) and [bundle/assets report](evidence/signed-release-files-2026-09-14-v4.json) retain the source and artifact hashes. Version 3 files and evidence remain unchanged.

This packaging audit did not upload or submit the app, perform live provider or payment calls, or verify a Play-signed installation. Play certificate-bound login, reviewer access, candidate-specific declarations and the pre-launch report remain separate submission checks. Paid features require their own live-channel verification before activation.
