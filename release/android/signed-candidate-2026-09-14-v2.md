# Android version 2 — 14 September 2026

Two separate files are prepared in the sibling `Hej/deliverables` directory. Both use `chat.mural.android`, version 0.1 (code 2), minimum API 26 and target API 36. Paid checkout remains disabled.

| File | Build | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `Mural-Android-preview-2026-09-14-v2.apk` | Installable debug preview | 63,458,443 | `52f5e9997dd565e39207f2d1662f264bfc0573e48c78d4f9e2279eafc280be86` |
| `Mural-Android-release-2026-09-14-v2.aab` | Upload-key-signed release bundle | 33,031,535 | `613cf25edc8db5bfe154f15d7cfd878197a0392e300c5eb671e5fd7240f70ce3` |

The preview keeps the existing personal debug certificate. All 607 release-bundle payload signatures verify against the approved upload certificate. Bundletool validation, all eight store assets and all five generated ARM64/device split signature and 16 KB alignment checks pass. The packaged ARM64 libraries match the earlier successful 16 KB runtime test byte for byte.

Known-credential scans found no matches in the preview's 605 entries or the signed bundle's 610 entries. Both include the required notices and public API/OAuth configuration. The preview is debuggable; the release bundle is neither debuggable nor test-only. Neither enables Android backup, cleartext traffic or paid checkout.

The version 2 build passed 246 JVM tests. Release lint reports zero errors and 44 warnings. This packaging audit did not touch an emulator or browser, upload to Play, or rerun live account, conversation, payment or physical-audio tests. Those journeys need their own evidence for the exact installed build.

[Signed candidate evidence](evidence/signed-candidate-2026-09-14-v2.json), [preview verification](evidence/debug-preview-2026-09-14-v2.json) and [release-file checks](evidence/signed-release-files-2026-09-14-v2.json) record the hashes and scope. The build came from an uncommitted working tree, so its recorded baseline commit alone does not reproduce it; the Android source fingerprint is retained. Version 1 files and evidence remain unchanged.
