# Serbian, Greek, Tagalog and background voice verification

PR: [#140](https://github.com/Chuloo/mural/pull/140). Includes [#139](https://github.com/Chuloo/mural/issues/139). The agreed targets are Serbian Latin/Ekavian, Modern Standard Greek in Greece and conversational Tagalog/Filipino in the Philippines. See [language and platform requirements](../docs/serbian-greek-tagalog.md).

## Automated checks

| Check | Result on October 2, 2026 |
| --- | --- |
| Swift core | 171 passed; no failures or skips |
| Android unit tests | 392 passed; no failures or skips |
| Android 16 emulator | 89 passed; no failures or skips on `1fdd95d` |
| API tests with disposable PostgreSQL | 438 passed; no failures or skips |
| Repository contract tests | 70 passed |
| Generated Android content and cross-platform contracts | Passed |
| Android lint, debug/interface APKs and release bundle | Passed |
| Android release package validation | Passed for version code 15; no store release published |
| Isolated Android live-verification APK and test APK | Built successfully; device run pending |
| Signed iPhone build and update installation | Passed; existing installation retained |
| iPhone Release build | Passed without signing |
| iPhone simulator interface tests | 52 passed in the final combined run; no failures, completed at 18:44 UTC |

Shared fixtures cover Cyrillic and Latin Serbian, Greek tonos/diaeresis and question marks, Tagalog contractions and optional marks, both Unicode normalization forms, quoted evidence, word selection and archives. Hosted funding tests create and close sessions in each new locale using both credits and minutes. Credential tests cover locked helper access, expiration and saved-key replacement/deletion.

The first CI run passed Checks, Contracts, Secret scan and Android build/release jobs. Its Android 16 emulator ran 88 tests and failed one background-service test waiting for a notification; the setup lacked notification permission. The test setup now grants Android 13+ notification permission. A new case also checks service restart ownership and stale notification End actions. The [PR's Checks tab](https://github.com/Chuloo/mural/pull/140/checks) records results for the latest head. Required CI must pass before this draft is marked ready.

## Production server

The matching API was deployed on October 2 at 18:08 UTC, before live phone testing. No migrations, pricing, feature activation or runtime grants were needed. Active calls and helper requests were zero at the deployment gate; temporary admission guards were removed afterward.

| Evidence | Value |
| --- | --- |
| Deployed source revision | `50a036d00585a792bac99c2b52797fa30d0466d0` |
| Running image | `sha256:8c2b66ccdc90c870494bd196b40ca625b7bdf3edabf2d8376727bb16c81e18af` |
| Compiled live-provider module SHA-256 | `fd8a5579df0eb3f57e26e44b44982d0a7e08c697b7d3d97fdebed39d7b5252d5` |
| Encrypted database backup | `/opt/mural/backups/mural-20261002T180817Z.dump.age` |
| Backup size and SHA-256 | 4,098,159 bytes; `32e8e92f2f79b0c6afc4117dd7fc512a50cd96d26c1587dc34410f7249a47017` |
| Previous configuration and rollback files | `/opt/mural/deploy/before-languages-20261002T180806Z` |
| Retained previous image tag | `mural-api-rollback:20261002T180806Z` |

The deployed module matches the locally tested compiled module. Public health and readiness returned 200; database readiness, hosted voice, guest minutes and live payments remained enabled. The deployed provider accepted all 11 canonical native locales and rejected six invalid aliases. Private configuration and mounted feature files retained their hashes. No sanitized application errors were found in the deployment verification window. Later native-app and test changes do not alter the deployed API source.

## Connected phones: October 3 retry

Both phones reconnected. The iPhone 16 Pro runs iOS 27.0; the Samsung Galaxy S9 runs Android 10. Existing learning data and the Android Play installation were preserved. iPhone Mirroring remained closed for live checks because [Apple disables microphone access while mirroring](https://support.apple.com/en-us/120421).

| Physical check | Observed result |
| --- | --- |
| iPhone Serbian | Live flow passed: speaker audio, two typed replies, meanings, lookup, supported learning evidence, archive/language switching and audio release |
| iPhone Tagalog | Same live flow passed; Apple's detector returned Indonesian, so the separate detector-dependent `passed` flag was false as expected |
| iPhone Greek | Live flow passed on retry after fixing the harness to wait for the latest reply's assessment; its earlier condition could stop at an English-support assessment with no target words |
| iPhone locked-screen Greek | Passed: protected storage locked, two new spoken replies and a lookup during 30 seconds in the background, same session retained, then closed and released audio in the background |
| iPhone spoken reply while locked | The user confirmed locking the phone and speaking. The report recorded non-typed speech and a new assistant reply, 35 seconds in the background, the same session and audio release. Its strict combined result was false because protected storage was still available at the instant speech arrived; it became locked later |
| Samsung interface suite | 83 passed, no failures, one expected skip: per-app Spanish locale setup requires Android 13; this phone runs Android 10 |
| Samsung hosted live setup | Stopped before connecting: the API was reachable, but the remaining daily welcome-minute funding capacity could not cover a new allowance. The spending limit was left unchanged |
| Samsung personal-key live checks | Setup reached the key-entry timeout for all three cases; no voice call began. Awaiting the user's existing key in the separate Mural Verify app |

The iPhone spoken-check diagnostics now distinguish background state at speech input and response from protected-storage timing. Its audio sample is limited to the spoken-response window, before further scripted replies. The revised harness compiles; the earlier observation is not relabelled as a passing result under the revised fields. A missed 15-second lock window also led to a longer human-action window with bounded scripted turns. These changes affect explicit verification builds only.

The Android runner supports the owner-entered personal key without reading credentials from the Play app or accepting keys in test arguments. Its waiting loop advances the Compose test clock so Settings remains usable. Opt-in screen-off and controlled audio-focus interruption checks are prepared; completed results remain required.

The PR remains a draft. Android live voice, screen-off microphone/playback, return after unlocking and interruption checks remain outstanding, as do iPhone interruption and a longer auto-lock/return check. The iPhone observation verifies a brief spoken reply while the user confirmed the screen was locked; it does not establish native-speaker pronunciation quality in all three languages. A competent speaker must still assess accent, stress, pronunciation and correction quality. The existing silence timeout remains unchanged pending the user's choice.

The next Android store publication also needs the foreground-service declaration and demonstration in Play Console. This PR has not been merged and no phone store release has been published.
