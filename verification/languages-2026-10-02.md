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
| Isolated Android live-verification APK and test APK | Built and run on the Samsung; all three language flows passed after the mute correction |
| Signed iPhone build and update installation | Passed; existing installation retained |
| iPhone Release build | Passed without signing |
| iPhone simulator interface tests | 52 passed in the final combined run; no failures, completed at 18:44 UTC |

Shared fixtures cover Cyrillic and Latin Serbian, Greek tonos/diaeresis and question marks, Tagalog contractions and optional marks, both Unicode normalization forms, quoted evidence, word selection and archives. Hosted funding tests create and close sessions in each new locale using both credits and minutes. Credential tests cover locked helper access, expiration and saved-key replacement/deletion.

The first CI run passed Checks, Contracts, Secret scan and Android build/release jobs. Its Android 16 emulator ran 88 tests and failed one background-service test waiting for a notification; the setup lacked notification permission. The test setup now grants Android 13+ notification permission. A new case also checks service restart ownership and stale notification End actions. All four workflows subsequently passed on `0825675`. The [PR's Checks tab](https://github.com/Chuloo/mural/pull/140/checks) records results for the latest head, including the final Android mute correction. Required CI must pass before this draft is marked ready.

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
| iPhone final spoken/locked/return/interruption check | Passed with the revised diagnostics: non-typed speech and its audible response in the background, 60.075 seconds with the same call and protected storage locked, background helper, return to the same active call, then a real Siri interruption and audio release. The user confirmed Siri responded |
| Samsung interface suite | 83 passed, no failures, one expected skip: per-app Spanish locale setup requires Android 13; this phone runs Android 10 |
| Samsung hosted live setup | Stopped before connecting: the API was reachable, but the remaining daily welcome-minute funding capacity could not cover a new allowance. The spending limit was left unchanged |
| Samsung personal-key live checks | Serbian, Greek and Tagalog passed with a fresh spoken response to each typed turn, meanings, lookup, background helper, retained session, archive round-trip and notification End cleanup |
| Samsung screen-off speech and return | Passed: non-typed speech and a new audible reply while the display was off, two further replies and a helper over 30 seconds, same active session after waking, then audio-focus interruption released audio, notification and service |

The first iPhone spoken attempt recorded speech and a response but failed a combined check because protected storage was still available when speech arrived. The revised diagnostics separate background state from storage timing and limit the audio sample to the spoken response. The final physical run passes these revised checks, with 11 assessments and seven supported learning words. Protected storage was available at the instant speech arrived and became locked during the minute-long background check. Human-action windows use bounded synthetic turns. These changes affect explicit verification builds only.

The Android runner uses the owner-entered personal key without reading credentials from the Play app or accepting keys in test arguments. Its first checks verified greeting audio and helpers but did not require a fresh spoken response after typed input. Stronger checks exposed an Android mute bug in all three languages: after the mute acknowledgment, later thinking/commentary updates remained unacknowledged and produced no new audio or captions. Muting now replaces microphone samples with silence through the audio device module while keeping the media track enabled. All three stronger language checks pass after that change, as do 392 unit tests and lint. The screen-off spoken/return/interruption check also passes.

The change follows the [Live context-timeline behavior](https://developers.openai.com/api/docs/guides/live-conversations#understand-when-context-reaches-the-model) and WebRTC's [microphone-silencing implementation](https://webrtc.googlesource.com/src/+/c267248921182b9d432bc97c030b705020d1927d/sdk/android/src/java/org/webrtc/audio/WebRtcAudioRecord.java). The runner records protocol event kinds and counts, without their payloads. Missed lock cues and failed mirror controls are recorded as failed attempts, not product passes. A two-minute human-action window uses bounded synthetic turns; it does not alter the product timeout.

Both phones have completed the live speech, screen-off playback, return and controlled interruption checks. CI for the final Android mute correction must pass before the PR is marked ready. These results verify delivery and lifecycle behavior; they do not establish native-speaker pronunciation quality in all three languages. Competent-speaker review of accent, stress, pronunciation and correction quality remains recommended. The user chose to retain the existing silence cutoff on October 3. Screen locking alone does not end a call; silence, the selected session duration and funding limits still can.

The next Android store publication also needs the foreground-service declaration and demonstration in Play Console. This PR has not been merged and no phone store release has been published.
