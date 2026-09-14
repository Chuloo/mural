# Signed Android candidate — 14 September 2026

`Mural-Android-release-2026-09-14.aab` is ready for internal Play testing in the sibling `Hej/deliverables` directory. This audit did not upload it. The package is `chat.mural.android`, version 0.1 (code 1), with minimum API 26 and target API 36. Paid checkout remains disabled.

- **SHA-256:** `cb327af6edbecbf3b2212cdfc12d978c763a6517a5505bccddb35a5f82d50d20`
- **Size:** 33,029,850 bytes.
- **Upload certificate SHA-256:** `E4:5E:9C:F5:ED:9D:61:64:BA:58:EA:5A:D7:DF:C2:67:CB:82:2C:7A:EB:8C:46:A3:5D:39:A3:C8:DB:20:C6:5C`.

All 607 payload signatures verify against the approved upload certificate. Bundletool validates the bundle; all five generated ARM64/device splits pass 16 KB ZIP alignment and signature checks. The packaged ARM64 libraries are identical to those that passed two offline tests on a confirmed 16 KB runtime. No known credential pattern was found in the 610-entry signed archive. The eight store assets and listing lengths pass validation.

The final build passed 235 JVM tests and release lint with zero errors and 44 warnings. The full isolated interface suite passed 44 tests before a final paid-catalog estimate-limit correction. All five purchase-interface tests passed again on the replacement build, including the estimated-time and fee disclosure. Personal emulator data and device settings were preserved.

This build came from an uncommitted working tree. Its baseline commit and Android source fingerprint are recorded in [candidate evidence](evidence/signed-candidate-2026-09-14.json); the baseline alone does not reproduce the build. [File validation](evidence/signed-release-files-2026-09-14.json) and [full UI evidence](evidence/isolated-ui-2026-09-14.json) retain the artifact hashes and scope.

Next, install the exact bundle through internal Play testing and verify Google login with the Play app-signing certificate. API 26, physical audio routes, final live account/trial journeys and the relevant [release gates](release-gates.md) remain open. Local upload-key signatures do not establish that the Play-signed install works. Paid checkout stays unavailable until its separate payment and settlement checks pass.
