# Android version 3 — 14 September 2026

Version 3 keeps meanings visible during long replies. Each language has its own bounded scroll area with soft edges; longer replies reduce the orb and spacing to make room for reading. The greeting and short-conversation layout retain the original large orb and floating navigation.

The files below are in the sibling `Hej/deliverables` directory. Both use `chat.mural.android`, version 0.1 (code 3), minimum API 26 and target API 36. Paid checkout remains disabled.

| File | Build | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `Mural-Android-preview-2026-09-14-v3.apk` | Installable debug preview | 63,474,827 | `938107cf13969422bf389252c88836c97701a93f6accc8f96d5266ed55ca0c2f` |
| `Mural-Android-release-2026-09-14-v3.aab` | Upload-key-signed release bundle | 33,038,028 | `0bd2d55f0138da6697236480c1752489b622d1143fc8928ec623564d7573e2bc` |

The final build passed 257 JVM tests and all 45 isolated UI/device tests. The full interface suite ran on emulator user 11, preserving the personal guest installation and device settings. It includes the long-reply regression, fee disclosure, account lifecycle, secure storage, onboarding, settings and offline native checks. Release lint reports zero errors and 44 warnings.

The preview keeps the existing personal debug certificate. All 607 release-bundle payload signatures verify against the approved upload certificate. Bundletool validation, all eight store assets and all five generated ARM64/device split signature and 16 KB alignment checks pass. The packaged ARM64 libraries match the earlier successful 16 KB runtime test byte for byte. Known-credential scans found no matches in the preview's 605 entries or the signed bundle's 610 entries.

[Candidate evidence](evidence/signed-candidate-2026-09-14-v3.json), [preview verification](evidence/debug-preview-2026-09-14-v3.json), [full UI results](evidence/isolated-ui-2026-09-14-v3.json) and [release-file checks](evidence/signed-release-files-2026-09-14-v3.json) retain the hashes and scope. [Layout review images](evidence/long-reply-layout-2026-09-14-v3/manifest.json) use synthetic Spanish conversations in the actual app UI.

This build came from an uncommitted working tree; its recorded baseline commit alone does not reproduce it. The Android source fingerprint is retained. Earlier version 1 and version 2 files and evidence remain unchanged.

This audit did not upload to Play, install the personal APK, or perform live provider, payment, API 26 or physical-audio tests. Play-signed login and those journeys require separate evidence for the installed release and deployed service configuration.
