# Serbian, Greek, Tagalog and background voice verification

PR: [#140](https://github.com/Chuloo/mural/pull/140). Includes [#139](https://github.com/Chuloo/mural/issues/139). The agreed targets are Serbian Latin/Ekavian, Modern Standard Greek in Greece and conversational Tagalog/Filipino in the Philippines. See [language and platform requirements](../docs/serbian-greek-tagalog.md).

## Automated checks

| Check | Result on October 2, 2026 |
| --- | --- |
| Swift core | 171 passed; no failures or skips |
| Android unit tests | 392 passed; no failures or skips |
| API tests with disposable PostgreSQL | 438 passed; no failures or skips |
| Repository contract tests | 70 passed |
| Generated Android content and cross-platform contracts | Passed |
| Android lint, debug/interface APKs and release bundle | Passed |
| Android release package validation | Passed for version code 15; no store release published |
| Isolated Android live-verification APK and test APK | Built successfully; device run pending |
| Signed iPhone build and update installation | Passed; existing installation retained |
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

## Connected phones and remaining checks

The signed test build is installed on the connected iPhone 16 Pro running iOS 27.0. Its saved configuration and learning data were preserved. iOS rejected the live test launch because the phone was locked. The connected Samsung Galaxy S9 runs Android 10; an interface-test attempt could not find the app hierarchy while the phone was asleep. Neither attempt counts as a successful device check. The Mac is also locked, preventing both mirrors from being controlled.

The PR remains a draft pending the authorized live checks after device access is restored. The device flows described in [build and test](../docs/build-and-test.md) use temporary or separate learning storage. They must verify received voice, meanings, lookup, the same session on return and release of audio and service resources on End. Actual screen dim/lock and interruption checks remain required; synthetic typed turns do not verify recognition of human speech. A competent speaker must still assess accent, stress, pronunciation and correction quality. Tagalog text detection is recorded as unreliable and does not redirect valid Tagalog output.

The next Android store publication also needs the foreground-service declaration and demonstration in Play Console. This PR has not been merged and no phone store release has been published.
