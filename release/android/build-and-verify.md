# How to prepare and verify an Android release candidate

Use this procedure after the release scope, package and upload-key custody are approved. Work in a clean checkout of the candidate commit, with JDK 17 and the Android SDK configured as described in [Run on Android](../../docs/run-on-android.md). Keep signing keys, passwords, provider keys and reviewer credentials outside Git.

## 1. Freeze the candidate

1. Set the Android `versionCode` and `versionName`, then update [release-spec.json](release-spec.json) to match. Record the Git commit and all release configuration names; record public client IDs if useful, never secrets.
2. Confirm whether the candidate is a personal-key preview, a funded guest preview or a paid release. Update listing copy and [declarations](declarations.md) for the features actually enabled. Select final assets from the same candidate.
3. Run the existing cross-platform, Android, backend and security checks appropriate to the changes. Review failures before producing the candidate. Use the [gates](release-gates.md) to identify the required real-device and live-service evidence.

## 2. Build the bundle

From `apps/android`, build a local unsigned release bundle for inspection:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintRelease :app:bundleRelease
```

The output is `apps/android/app/build/outputs/bundle/release/app-release.aab`. The checked-in build does not include a release signing identity. Produce the signed candidate through Android Studio's **Generate Signed App Bundle / APK → Android App Bundle** flow using the approved upload key, or a separately reviewed CI signing setup. Supply passwords through the IDE, protected environment or permission-restricted password files; never place them in command arguments, workflow YAML or `local.properties` committed to Git.

Inspect the signed bundle with `jarsigner -verify -verbose -certs`, confirm that every payload entry is signed, and compare the certificate SHA-256 fingerprint with the intended upload certificate. An Android upload certificate can be self-signed; distinguish that trust warning from a broken signature or unsigned payload. Save the certificate fingerprint and bundle SHA-256, not the private key, in release evidence. Play App Signing uses its own app-signing certificate for delivered installs; register that fingerprint with native OAuth and other certificate-bound services. [Android signing guidance](https://developer.android.com/studio/publish/app-signing)

## 3. Validate the exact bundle and assets

Obtain a pinned release of Google's [bundletool](https://github.com/google/bundletool/releases), verify its provenance/checksum and keep it outside the repository. From the repository root, substitute the actual signed bundle and tool paths:

```sh
python3 scripts/check_android_release.py \
  --aab /absolute/path/mural-release.aab \
  --bundletool-jar /absolute/path/bundletool-all.jar \
  --require-bundle --require-assets \
  --output /absolute/path/candidate-evidence/release-files.json
```

For a local unsigned inspection candidate, add `--require-unsigned`. If only Gradle’s cached bundletool library is available, use `--bundletool-classpath-file /absolute/path/classpath.json` instead of `--bundletool-jar`. This file must contain a JSON array of trusted local JAR paths for bundletool and its dependencies. The evidence records each dependency hash; no download or Gradle change is required.

The default specification is the current v7 candidate. To recheck the archived v4 Play bundle, select its historical spec explicitly:

```sh
python3 scripts/check_android_release.py \
  --spec release/android/specs/play-v4.json \
  --aab /absolute/path/Mural-Android-release-2026-09-14-v4.aab \
  --bundletool-jar /absolute/path/bundletool-all.jar \
  --require-bundle --require-assets \
  --output /absolute/path/candidate-evidence/v4-recheck.json
```

For a configured v7 direct-distribution bundle, use `--spec release/android/specs/direct-v7.json`. Historical direct specs remain at `release/android/specs/direct-v6.json` and `release/android/specs/direct-v5.json`. `--spec` paths are relative to the working directory. `--release-dir` still sets the root for metadata and assets; selecting a spec does not move that root. With no `--spec`, the checker reads `release-spec.json` in that root. A missing or invalid explicit spec fails, and a bundle whose version differs from the selected spec fails. Keep the default version aligned with the current build rather than changing it to make an older bundle pass.

The report records the selected spec filename, hash and candidate identity. Historical rechecks use the currently available shared listing/assets and branding source; compare their hashes with the original [v4 evidence](evidence/signed-release-files-2026-09-14-v4.json) and preserve that original report. A spec's scope labels the intended release; it does not verify purchase flags, payment behavior or Play approval. [Candidate scopes](candidate-scopes.md) identifies which copy and evidence belong to each version.

The check records artifact and asset hashes, listing lengths, required license files, every packaged 64-bit library's LOAD/RELRO layout, package/version/SDK identity and the bundle's request for 16 KB APK alignment. It verifies that the Android launcher image is byte-identical to the iOS source and that the source resource references still select it. It also reports JAR signature entries and checks packaged files for credential filenames and known OpenAI, Stripe, Google OAuth, GitHub and private-key patterns. Matches fail without including the credential value in the report. This pattern scan does not prove that every possible secret format is absent; inspect the release configuration separately. Missing final assets fail when `--require-assets` is used. The report explicitly lists runtime, signing, visual brand parity and product checks it does not perform.

An unaligned RELRO end is reported for review. The validator fails if rounding RELRO protection to 16 KB pages overlaps writable LOAD data outside the declared RELRO regions. A gap after RELRO can avoid that overlap, so an end-address remainder alone is not proof of a crash. Preserve any warnings and resolve them with runtime evidence. [Android's page-size guide](https://developer.android.com/guide/practices/page-sizes) and [Bionic's protection logic](https://android.googlesource.com/platform/bionic/+/main/linker/linker_phdr.cpp) describe the relevant checks and behavior.

## 4. Test APKs generated from the bundle

1. Use `bundletool build-apks` with the exact candidate AAB and an approved local test signing identity. Pass passwords by protected file or prompt. Generate device-specific split APKs for the test device/emulator to avoid unnecessary disk use.
2. Extract the generated APK set to a temporary directory. Run SDK Build Tools `zipalign -c -P 16 -v 4` on each APK; retain the output and APK hashes.
3. Install those split APKs on an isolated test installation with `bundletool install-apks`. Run onboarding, live speech, helper actions, local learning and account journeys. Do not replace or uninstall a learner's differently signed installation without an export and explicit migration plan.
4. On a 16 KB emulator/device, confirm `adb -s SERIAL shell getconf PAGE_SIZE` returns `16384`, then exercise WebRTC and graphics. Record OS/build, ABI, page size, native library warnings and results. Reuse the instrumented native smoke test where applicable; it is not a substitute for a live audio route test.
5. Upload the same candidate to internal testing. Verify the resulting Play-signed install, including Google login using the Play certificate. Local upload-key/debug-signed APKs cannot prove this certificate-bound flow. [Bundle testing](https://developer.android.com/tools/bundletool)

## 5. Retain evidence and submit

Keep the AAB, its SHA-256, signing-certificate fingerprint, public build configuration, generated APK hashes, test reports, screenshots and the final declaration record together. Save R8 mapping files if shrinking is enabled and native debug symbols where the upstream artifact supplies them; do not claim absent symbol files were generated. Keep private reviewer access and any account identifiers in a restricted companion record.

Resolve every gate for the intended track, then submit through Play Console. Promote the tested version between tracks where possible. After publication, install from the public listing and recheck login, trial and purchases before enabling the website's Play CTA.

## Continuous integration

The Android workflow checks listing files and tests the release validator. Its Android job builds an **unsigned** release AAB, scans bundled notices/native layout and retains the bundle plus JSON report for 14 days. It does not sign, upload, run bundletool, grant trial minutes or exercise paid/live services. A candidate requires the signed-bundle and runtime procedure above.
