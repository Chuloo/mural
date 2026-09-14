# Historical unsigned Android candidate — source 8768c86

The candidate includes the tested account lifecycle fixes and passes bundle validation. It remains unsigned and unpublished. Purchases are disabled, and funding remains paused. This audit made no provider call or purchase.

Native development continued after this inspection. These results describe the recorded source and artifact; rebuild and verify later changes before signing or distribution.

| Item | Result |
| --- | --- |
| Source commit | `8768c863890e5583d76daf3e3a3fcdda542b785f` |
| Bundle | `release/private/android-candidate-8768c86-2026-09-13/mural-0.1-1-unsigned.aab` |
| SHA-256 | `aeb94501a7dfe15a66796d6f983b46e017e5b2cced8f278944e5843abea8ec42` |
| Size | 32,925,845 bytes |
| Identity | `chat.mural.android`, version code `1`, version name `0.1` |
| Android support | Minimum API 26; target API 36 |
| Build inputs | All 88 recorded production/build inputs match the committed Android checkout and stayed unchanged during the build |
| Build checks | `bundleRelease` and `lintRelease` passed; lint reported zero errors, 42 warnings and two hints |
| Separate verification build | 205 JVM tests and 31 isolated UI/device tests passed, with no failures, errors or skips |
| Public configuration | `https://api.mural.chat`, public Google OAuth client ID, purchases disabled, purchase environment `test` |
| Bundle validation | bundletool 1.18.0 passes; requested APK alignment is `PAGE_ALIGNMENT_16K` |
| Signing | No JAR signature entries; `jarsigner` confirms the AAB is unsigned |

The [bundle report](evidence/unsigned-candidate-8768c86-2026-09-13.json) records the manifest, packaged files, native libraries and artwork. The [build record](evidence/unsigned-candidate-build-8768c86-2026-09-13.json) records source/configuration hashes and test-report hashes. Android source and build files were clean; release documentation and evidence were still being written outside those build inputs.

The manifest is neither debuggable nor test-only. Automatic backup and cleartext traffic are disabled. All 606 packaged entries passed the known credential-file and secret-pattern checks; no matching credential was detected. This bounded scan does not establish that every possible secret format is absent. Generated public configuration is byte-identical to the earlier inspected configuration and contains no configured release identity.

## Native packaging and runtime limit

Four ARM64/API 35 split APKs generated from this exact bundle pass `zipalign -c -P 16 -v 4` and signature verification. [Split evidence](evidence/split-alignment-8768c86-2026-09-13.json) records each hash. These APKs use the standard local Android debug certificate solely for test installation; no production key was used, and the source AAB remains unsigned. They have not been installed.

The bundled ARM64 and x86_64 WebRTC and AndroidX graphics libraries are byte-identical to the earlier inspection. Their LOAD segments pass 16 KB alignment/congruence checks. Their unaligned RELRO ends do not overlap writable LOAD data when rounded to 16 KB pages. They have no static symbol table or debug sections. Runtime verification is still required. [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes)

The official API 35 ARM64 16 KB image is installed, but the fresh isolated emulator could not create its userdata partition. Startup required 7,372.80 MB and reported 3,199.89 MB available. The documented 1,536 MB partition override did not lower that requirement. No boot, observed 16 KB page size or native runtime result is claimed. The existing Mural emulator and its data were untouched. The SDK removed the compressed download after installation; the installed image remains available for a later run. [Attempt evidence](evidence/16kb-runtime-attempt-2026-09-13.json)

The 31 UI/device tests ran against the separate `.uitest` build on the existing 4 KB emulator. They do not establish Play-certificate login, a 16 KB runtime result or physical microphone, speaker and Bluetooth behavior.

## Remaining release work

- Complete upload-key custody, signing and Play App Signing configuration, then verify the signed artifact and Play-certificate login.
- Run the generated packages and native smoke test in a confirmed 16 KB environment; complete physical audio and interruption testing.
- Finish the feature graphic, six final screenshots and the iOS launcher/wordmark comparison. The 512-pixel Play icon preserves the approved source pixels and passes format checks.
- Resolve the funding decision before hosted activation. Complete the deployed guest, account, billing, cutoff and reconciliation tests required by the chosen release scope.
- Finish Play purchase tests, reviewer access, Data safety, deletion/privacy details, AI-report handling, audience/country declarations and rollout choice.

The [release gates](release-gates.md) remain the submission checklist. This artifact does not authorize signing, upload, paid activation or publication. The [earlier candidate](candidate-audit-2026-09-13.md) is retained as historical evidence.
