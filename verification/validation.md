# Build verification

11 September 2026

- iOS simulator build succeeded with Xcode 26.4.1.
- The signed device build succeeded and was installed on the connected iPhone 16 Pro running iOS 26.6.1. The app bundle passed strict code-signature verification.
- After the owner trusted the developer profile, Mural launched successfully on the iPhone at 22:00 CEST.
- 18 learning-policy tests passed: duplicate events and word proposals, late transcript fragments, overlapping speakers, exact transcript concatenation, supported and typed production, English input, evidence provenance, transcript corrections, recall spacing and decay, archive integrity, and source URL validation.
- 3 native UI tests passed on an iPhone 16 Pro simulator running iOS 26.4: greeting and subtitle toggle; secure key settings; theme persistence across Talk and Words.
- The Talk screen was inspected from a simulator capture. The initial clipped toolbar wordmark and crowded footer were corrected.
- WebRTC is pinned to 152.0.0 with the package checksum verified by Swift Package Manager. License notices are bundled in the app.

The owner entered an API key and confirmed live speech playback, but reported that speech through the iPhone was too quiet. Cellular connectivity, pronunciation and teaching quality still need the pilot below.

## Speaker-volume correction

Mural originally set the speaker preference directly on the active audio session. WebRTC then applied its own default configuration when its audio unit started, removing that preference. The fix sets `defaultToSpeaker` in `RTCAudioSessionConfiguration` before creating the connection. This follows [WebRTC's configuration mechanism](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/objc/components/audio/RTCAudioSession.mm) and [Apple's speaker routing option](https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/defaulttospeaker), which preserves connected headset routes.

The fix also balances app-owned audio activation and deactivation. Calling cleanup before the first connection or after an already-closed connection no longer decrements WebRTC's activation count.

- Signed device build passed and the update was installed.
- All 3 native UI regression tests passed.
- An explicit debug-only `--verify-audio` run tests two real Live connections using the device's saved key. It uses an in-memory learning store and writes only connection, route, volume and cleanup results to `Documents/audio-verification.json`. It incurs API usage and is never part of automatic offline tests.
- A real Live session received both speech and transcript. During playback, the output route was `Speaker`, the system volume was 100%, and the speaker preference remained set. Audio was released after closure. The owner confirmed: “Yes, the volume is good now”.
- The first repeat test muted input before the greeting was complete. Its second connection opened and closed correctly but had no caption during the 12-second observation window. The test now keeps input running, as required by [OpenAI's greeting flow](https://developers.openai.com/api/docs/guides/live-conversations#greet-before-the-caller-speaks).
- The corrected test build was installed. Its repeat run could not start because the phone had locked again. The speaker fix is confirmed by the first live playback and the owner's listening check; the corrected two-greeting test remains unverified.

## Personal pilot

Use the phone on cellular, with the Mac disconnected. Confirm:

1. Start requests microphone permission, connects and greets in Norwegian.
2. English replies produce Norwegian speech, with optional meaning subtitles.
3. A meaningful error receives a gentle correction without breaking the exchange.
4. Mute prevents microphone audio reaching the conversation; End releases the connection.
5. A theme persists when opening Words, and saved progress survives relaunch.
6. Current-topic lookup displays sources and declines to invent facts when unavailable.
7. Backgrounding, calls, a network drop and the session time limit close or recover clearly.
8. Speaker and AirPods audio are intelligible and yield promptly to interruption.

Record incorrect corrections, misheard speech, English leakage and unsupported claims. The recall thresholds and model judgments remain provisional until this pilot provides enough evidence to refine them.

## Spanish and language modules

Spanish targets the Spain variety. Language modules now supply the greeting, pronunciation and writing guidance, grammar focus, lemma rules and cultural themes. Shared prompts cover voice, subtitles, teaching, help, typed replies, lookup and sourced topics.

- 28 core tests passed, including version-1 migration, bilingual archive round trips, language-specific vocabulary and challenge levels, hidden cognates, source-language validation, topic-language consistency and Spanish prompt isolation.
- All 4 native UI tests passed. The language-switch test selects Spanish, checks its greeting and themes, opens Spanish Words, and switches back to Norwegian.
- The signed iPhone build passed and was installed over the existing app.
- A protected pre-migration learning payload is retained in Application Support/Mural/before-language-modules.json inside the app container. It contains no API key.
- Both Spanish live checks passed on the iPhone using its saved key. Each received speech and captions through the `Speaker` route at 100% system volume, retained the speaker preference and released audio after closing. Non-content results are in `spanish-live-verification.json`.
- Mural was relaunched normally with the persistent learning store after testing.

The first UI attempt ran a stale simulator test runner. Removing only that generated runner loaded the new tests; a later text assertion was corrected to account for uppercase section labels. The final suite passed with no failures.

## Conversation reset and Meaning

Ended conversations return to the ready screen after 15 seconds, or immediately through **New conversation**. This clears the current dialogue and theme while preserving preferences, saved conversations and learning progress. A transcript opened before the reset retains its content.

Meaning previously required an active session, so tapping it after ending could do nothing. Its label also sat outside the button's hit area. Streaming transcript fragments repeatedly cancelled translation requests, and cancelled requests could overwrite newer UI state. The new translation controller coalesces incoming fragments, validates request generations, retains a usable translation during updates, separates caches by subtitle language and exposes errors with an explicit retry.

- 35 core tests passed. Seven new tests cover continuous speech, coalescing, late cancelled responses, corrected transcripts, passage changes, retry behavior and subtitle-language changes.
- All 7 native UI tests passed. New checks tap the Meaning label after ending, verify immediate reset and retained history, wait for the actual 15-second reset, and keep a transcript open across that reset.
- The signed build succeeded and was installed over the existing iPhone app.
- A live Spanish session verified an English translation while active, immediate cached Meaning after ending, a new French translation requested after ending, automatic reset after 15 seconds, retained session history and released audio. Every check passed. The report is in `meaning-live-verification.json`; it contains no key, transcript or audio.
- The explicit debug invocation `--verify-audio --verify-meaning --verify-language=es` uses an in-memory learning store and the device's saved API key. It incurs API usage and is not run by the offline suite. Mural was reopened normally afterward with its persistent store.

The live test verifies that translations arrive and the controls respond. It does not establish translation accuracy across extended conversations or all supported subtitle languages.

## English, French, onboarding and AI consent

12 September 2026

The English module requests broadly intelligible pronunciation and accepts regional variants in its teaching policy. French targets France and accepts valid Francophone variants. Both modules have six teaching-focus levels, lemma guidance, cultural themes and a spoken fallback for unavailable lookups. Shared prompts now treat English as a possible learning language, and the speech-language check follows the selected target.

The welcome flow has two screens: learning language, then subtitle language. Greetings cycle across the installed languages, with a static alternative when Reduce Motion is enabled. The final screen identifies OpenAI as the processor, explains the audio and text transfer, links the privacy policy and requires **Agree and continue**.

Consent is recorded as a versioned preference. Older archives without a consent version keep their prior language choices and require agreement. Before the next live session, an existing user can agree in one consent sheet or choose **Not now** and continue reading saved material. Word lookup, current-topic search and meaning translation also check consent before making a provider request. New onboarding records the same consent version. The privacy-policy URL is `https://mural.chat/privacy/`; public availability depends on deploying the website.

- **41 core tests passed**, including separate progress across four languages, English as target-language evidence, French accents and elisions, language recovery, and old-archive consent decoding.
- **All 11 native UI tests passed** on the iPhone 16 Pro simulator running iOS 26.4. The suite covers target/subtitle selection, an explicit subtitle choice surviving Back, English and French settings, existing-user consent decline/accept/no-repeat, the Advanced API-key disclosure, and the prior conversation/Meaning regressions.
- The completed UI result is `Test-Mural-2026.09.12_13-37-03-+0200.xcresult`, ending at 13:40 CEST. The two failures in the earlier 13:06 run were accessibility-selector issues: the privacy link's element type and an identifier inherited from the key disclosure. Both were corrected and passed in this full run.
- The welcome screens were captured from native UI tests for visual review. After reducing the subtitle step's decorative header to leave room for the example and consent footer, its targeted onboarding test passed again at 13:45 CEST in `Test-Mural-2026.09.12_13-45-06-+0200.xcresult`. That targeted check also compiled the then-current combined app source; it did not exercise the account flows.
- UI fixtures use in-memory records. Preview starts do not connect to OpenAI. No API key was added, replaced or removed by these tests.

These results cover the language modules, onboarding, consent and existing conversation controls. They do not verify the separate account or billing work. English and French live pronunciation and teaching quality still need listening checks; the earlier Spanish speaker and Meaning results above remain historical evidence for their tested builds.

## Native account foundation

12 September 2026

The source now includes Google sign-in through the system authentication browser with OAuth authorization code and PKCE S256, native Sign in with Apple, a separate Keychain record for Mural sessions, and the server's challenge, exchange, wallet, sign-out and deletion contracts. The Settings destination is hidden unless explicit deployment and provider configuration passes the account gate. The default app remains BYOK and does not offer hosted trial minutes or purchases.

- **48 core tests passed** in the combined source, including seven new account tests. The new checks cover HTTPS configuration and provider capability gates, the RFC 7636 S256 test vector, raw nonce binding, callback origin/state/duplicate-parameter rejection, form encoding, expiry and backend scoping, exact wallet arithmetic and cancelled-operation invalidation.
- The combined iOS Simulator build passed at 13:45 CEST, including the account view and conditional Settings link.
- The unsigned **0.1.0 (1)** Release archive was refreshed successfully at 13:46 CEST with the final onboarding, consent and account source. It includes both privacy manifests and third-party notices. This is a build artifact, not a distribution-signed upload.
- The seven new account source, test and documentation files were scanned for secret-shaped keys, signing material, private local paths and device identifiers; no matches were found. Fixtures use synthetic values. Google's button artwork is the unmodified provider asset with its source and branding notice recorded.

No Google or Apple account was signed in, no provider credentials were configured, and no live Mural account or payment was created during this verification. Keychain persistence, configured provider callbacks and Apple authorization revocation still require device checks against the deployed server. See [managed-account setup](../docs/managed-accounts.md) for configuration and the remaining checks.

## Permanent release links

12 September 2026

Settings now has a **Help and privacy** section with `https://mural.chat/privacy/`, `https://mural.chat/terms/` and `https://mural.chat/support/`. Existing onboarding, consent and disabled-account links already used the intended privacy and terms URLs.

- A source check confirmed all seven native release-link occurrences use HTTPS, the intended domain and the canonical paths, without query strings or fragments.
- The combined simulator build and the existing Settings navigation test passed at 14:42 CEST. This checked the secure-key disclosure and return to Talk; it did not browse the release pages.
- The unsigned **0.1.0 (1)** archive was refreshed at 14:44 CEST with the Settings links. Compilation and archive creation are not Apple upload validation, TestFlight review or App Store approval.
- Custom-domain availability remained pending at this checkpoint. Confirm all three pages load without login after DNS propagation before submitting the app.


## 12 September 2026 — silent final assessment and access requests

The final native update passed 53 Core tests, including five final-assessment lifecycle tests, and five focused simulator UI checks. New-user language/subtitle onboarding, AI consent, existing-user consent, Settings, meanings after ending and reset remained available. Closing attempts the latest unassessed user passage silently for up to 15 seconds. Results apply to the original saved transcript; reset or a new conversation does not redirect them, and deletion or correction cancels stale work. App termination can interrupt this in-memory attempt. The signed personal update was installed and launched on the authorized iPhone without uninstalling or changing saved preferences. No new live AI conversation was used for this check.

The backend passed 57 tests in Node 22 with PostgreSQL 17 and no skipped tests. Access-request cases cover validation, exact origins, proxy identity, concurrency, duplicates, admission limits, retention, private export and deletion. A separate real Stripe sandbox Checkout test received signed payment and refund webhooks: one payment credited $10 once, duplicate delivery added no credit, and the full sandbox refund restored a zero balance with one reversal. A fresh session verified that Adaptive Pricing is disabled and the amount remains USD 12.16. No real money, production customer records or live payment credentials were used. These checks do not validate a native purchase flow or enable production payments.

## Optional Google signup and Settings cleanup

12 September 2026, 16:35 CEST checkpoint

Google and Apple configuration are now independent. The ignored local build configuration enables Mural's public Google iOS client for `no.william.mural` and `https://api.mural.chat`. Checked-in defaults remain disabled. Apple stays disabled while Hackmamba Inc.'s developer enrollment is processing; optional entitlement and compilation settings are prepared for an eligible profile.

The account screen shows a terms agreement and privacy acknowledgment before sign-in, states that learning history stays on the phone, and explains that BYOK does not require an account. After login it requests the account profile, validates its ID against the Mural session, and displays the provider and verified email when available. Wallet requests and balances are absent from this screen. Account creation does not enable hosted voice, trial minutes or purchases.

- **56 Core tests passed** at 16:25 CEST. Three new tests cover Google without Apple capability, Apple-only configuration and invalid Google starts, plus account-profile identity, provider, timestamp and email validation. The prior final-assessment, language and account security tests also passed.
- **Three focused native UI tests passed** at 16:26 CEST: account-free language/subtitle onboarding, secure API-key Settings and retained license notices after removing the WebRTC explanation and external license link. Result: `Test-Mural-2026.09.12_16-25-19-+0200.xcresult`.
- With Google configured, **two focused UI tests passed** at 16:34 CEST in `Test-Mural-2026.09.12_16-33-55-+0200.xcresult`. The Settings test entered Account, checked the Google button and agreement, then returned to secure API-key entry. The second test confirmed bundled notices remain accessible. Visual review confirmed Apple is absent and the screen fits without a storage error.
- The initial unsigned simulator account capture exposed a Keychain error in preview mode. Preview launches now skip account credential loading and block sign-in requests. Real signed builds retain secure storage and actionable error reporting. The final UI run includes this correction.
- The signed iPhone build passed with the existing personal team and bundle ID. Its processed Info.plist was checked for the intended Google client, callback, API origin and disabled Apple provider. `git diff --check` passed.

At this checkpoint, the configured Google build had not been installed and no real Google login, account creation, persistence, sign-out or deletion had been verified. The UI runs used preview mode and did not contact an identity provider. Apple authorization and revocation also remain unverified. These results establish native build and layout readiness, not a completed live account flow.

At 16:48:47 CEST, the signed Google-configured update was installed over the existing app and launched normally on the authorized iPhone. The personal signing identity and `no.william.mural` bundle were preserved; no uninstall, learning-data changes, onboarding reset or credential inspection was performed. The user was directed to **Settings → Account → Sign in with Google**. Actual Google authorization and the resulting account profile remain pending confirmation.

Later on 12 September, the owner confirmed that Google sign-in worked on that installed build. This is a user-confirmed live authorization result; no bearer token, Google credential or OpenAI key was inspected. Relaunch persistence, cancellation, sign-out, expiry and account deletion remain unverified on the physical phone. Apple sign-in and revocation remain pending.

## Native security review

12 September 2026

The review covered tracked native source and fixtures: Keychain services, OAuth PKCE/state/callback handling, session scope, fixed API destinations and redirects, ATS settings, source links, diagnostics, backup import/export and the pinned WebRTC package. It found and corrected these issues:

- The file importer read the entire selected file before enforcing the 30 MB archive limit. It now checks file size and uses a bounded read that also rejects growth beyond the limit.
- Imported durations, usage counters and revision numbers could exceed the range used by the interface. Extreme values passed the old decoder and could later trap during integer conversion or addition. Regression tests first reproduced acceptance, then passed after validation was added. Date values are now bounded too.
- Separately valid imports could produce a combined archive beyond the size or session limits accepted on relaunch. Import now validates the complete candidate before replacing local history. Duplicate sessions stay unchanged, local preferences and AI consent stay local, and invalid learning evidence is removed.
- Removing the OpenAI key ignored the Keychain result and always changed the UI to report removal. It now reports success only for a successful deletion or an already-absent item, and keeps the existing state when removal fails.

**60 Core tests passed** at 17:17 CEST, including four new backup-security tests and both merged byte/session limits. The synthetic numeric-import test failed with three acceptance errors before the fix. **Two focused native UI tests passed** at 17:14 CEST for Settings/account/API-key navigation and account-free language onboarding. The completed result is `Test-Mural-2026.09.12_17-13-31-+0200.xcresult`. No real account, API key or learning backup was used by these tests.

The tracked-file credential-pattern scan found no usable embedded key. Its only private-key marker was an intentionally invalid server test fixture. The reviewed network paths use HTTPS and reject redirects for credential-bearing requests; ATS has no transport exceptions. No app WebView or app code that logs credentials was found. Source links accept HTTPS without user-info, and the word-lookup link handler is confined to the caption view.

WebRTC remains pinned to 152.0.0 and package revision `1d04692697cb642bfebf6ad2dd99fe52649c3d6d`. The package's binary checksum matches the publisher's [M152 release metadata](https://github.com/stasel/WebRTC/releases/tag/152.0.0). This is dependency provenance verification, not a source or binary audit of WebRTC. The project describes separate [security tracking for standalone clients](https://webrtc.github.io/webrtc-org/bugs/security/).

This was a bounded source review with synthetic regression tests, not a penetration test, exhaustive fuzzing, traffic interception or device Keychain inspection. Physical-device Keychain failure handling, sign-out/deletion and the new backup paths still need device checks. The fixes had not been installed at this checkpoint. The review also flagged account email/user-ID privacy declarations for the separate release-preparation work.

The signed security update subsequently built successfully and was installed over the existing iPhone app at approximately 17:21 CEST, using the same personal team and `no.william.mural` bundle. No uninstall, learning-data change, credential inspection, sign-in action or AI request was performed. The normal launch attempt at 17:21:04 was denied by iOS because the phone was locked (`FBSOpenApplicationErrorDomain` code 7, `Locked`). Installation is verified; opening and checking this updated build on the phone remains pending unlock.

## German, Italian, Brazilian Portuguese and Mandarin

13 September 2026

The owner selected Brazilian Portuguese and Simplified Chinese with pinyin help. The new modules use stable IDs `de`, `it`, `pt` and `zh`, with locales `de-DE`, `it-IT`, `pt-BR` and `zh-CN`. They add greetings, six teaching stages, regional speech and writing guidance, lemma rules, cultural themes and target-language lookup fallbacks. The four existing learning languages remain registered.

Mandarin builds on Richard Guerre's [contribution in #4](https://github.com/Chuloo/mural/pull/4). Review found that per-character transliteration gives incorrect readings for common words such as 银行 and 音乐, and that replacing the caption/transcript with noninteractive ruby text removes lookup and copying. The adapted implementation uses system word readings and separate optional pinyin below the original text, preserving Chinese word links and selectable transcripts. It also accepts Chinese script IDs in speech-language detection and keeps generated pinyin out of vocabulary identities and evidence. The contribution's global simulator architecture exclusion was not needed; the existing project generator and documented arm64 build settings were retained.

- **70 core tests passed** at 16:19 CEST. The new coverage includes all eight languages in one archive, per-language selection after decoding, hidden cognates, native characters and accents, assisted/typed evidence, rejection of foreign-language evidence, prompt paths, Chinese script detection, polyphonic word readings, ü normalization and word boundaries. The first run exposed three pinyin expectations involving Apple's combining-mark `v` notation; normalization now operates on Unicode scalars and those regressions pass.
- **20 native UI tests passed** at 16:30 CEST on the iPhone 16 Pro simulator. The full suite covers the four new onboarding choices, all language settings, themes and vocabulary headings, returning to Norwegian, Simplified Chinese meanings, pinyin visibility, retained Mandarin transcripts, the largest accessibility text size, consent, secure-key Settings and existing reset/Meaning behavior. Result: `.build/FourLanguages-UI-Final.xcresult`. The initial run had three test-selector failures: two partially obscured onboarding rows and duplicate pinyin labels behind a presented transcript. Scrolling whole rows into view and scoping the text lookup resolved them; the complete rerun passed.
- **79 backend tests passed with no skips**, using Node 23.4.0 and an isolated temporary PostgreSQL 15 database. The database was stopped afterward. The added local HTTP test verifies all eight locale codes and regional provider prompts, and rejects unsupported codes before making a provider request. TypeScript compilation passed. The backend language additions are source changes; no hosted-service deployment was performed.
- The **signed Debug iPhone build and unsigned Release build passed**. The Release executable excludes the live language-verification helper. The signed app was installed over the existing personal installation with the same bundle ID and signing configuration.
- **All four live device checks passed** on the owner's iPhone 16 Pro running iOS 26.6.1. Each check opened one real voice session, received a greeting and speaker audio, sent a beginner request in English and a more complex typed request in the target language, detected target-language output, obtained English meanings and a word lookup, retained cached meaning after ending, released audio, decoded its exported temporary archive, and switched away and back with separate progress. Each produced supported evidence without independent recall credit. Mandarin also produced pinyin. Content-free reports are retained locally as `verification/language-live-de.json`, `language-live-it.json`, `language-live-pt.json` and `language-live-zh.json`.
- The live checks used `--verify-audio --verify-language-flow --verify-language=<ID>`, the key already saved inside the app and in-memory learning records. The microphone was muted after connection. No key or account credential was read out of the app, and no audio or transcript was exported. Mural was relaunched without test arguments at 16:28:54 CEST, and its normal process was confirmed running with the persistent store.

Visual review after the full UI run found that the fixed consent footer crowded the screen at the largest accessibility text size. Consent now scrolls with the content at those sizes, the Continue button stays at the bottom, the Back icon keeps a usable size, and changing onboarding steps resets the scroll position. Three focused tests passed at 16:35 CEST in `.build/FourLanguages-Accessibility-Fresh.xcresult`, including German selection, standard consent onboarding and the largest-text Mandarin flow. A fourth check at 16:36 CEST confirmed that Back preserves an explicit subtitle choice, in `.build/FourLanguages-Onboarding-Back.xcresult`. The corrected screenshot shows readable consent and an unobscured Continue button. A preceding targeted run used a stale unsigned simulator test runner; replacing only that runner and using a fresh build directory resolved the mismatch. The signed Debug and unsigned Release builds passed again after the layout correction. The final signed app was installed at 16:36 CEST; its immediate normal launch was blocked because the phone had locked, and the owner was asked to unlock it.

After the owner unlocked the phone, the final build launched normally at 16:37:51 CEST. Its running process was confirmed. This reopened the persistent learning store without verification arguments.

The device results verify the application/provider paths with synthetic typed input and real voice output. They do not verify recognition of a human speaker, pronunciation, tones, correction quality, unscripted interruptions, headphones or cellular operation. The proficient-speaker checks requested in issues #10–#13 remain open. Pinyin uses dictionary tones and may need correction for names, ambiguous words and connected-speech tone changes. The Android contribution is not integrated in this checkout, so there is no generated Android language catalog to update here.

## Tagalog (Filipino) from the Philippines

13 September 2026. Added the ninth learning target with stable ID `tl`, locale `tl-PH` and display label `Tagalog (Filipino) · Philippines`. It uses the existing voice, text, meaning, vocabulary and archive paths. [Teaching choices and sources](../docs/tagalog.md) document the Tagalog.com and University of Hawai‘i references; [the implementation record](../TAGALOG-PLAN.md) maps the feature and tests.

Automated checks on Xcode 26.6 / Swift 6.3.3:

- **90 core tests passed, zero failures.** The 20 added cases cover module identity and prompt paths, six-stage selection, Philippine themes, unreliable detector labels, unaided and assisted evidence, foreign/alias proposals, mixed passages, exact provenance, aspect/focus vocabulary identities, homographs, hidden words, punctuation, archive/import boundaries and late results after switching languages. The pre-implementation run of the 17 focused Tagalog tests produced 15 failures; the final full suite passed after implementation and integration with the existing tests.
- **80 backend tests passed, zero failures and zero skips**, with Node 26.8.1 and an isolated PostgreSQL 17 database. TypeScript type checking and build passed. The locale test failed on `tl-PH` before the provider map was updated, then passed for all nine locales. The added integration test proves invalid aliases create neither reservations nor provider calls, and a valid Tagalog session reserves and settles credit through the local fake HTTP/WebSocket provider.
- **Debug simulator test build passed**, including the updated live-verification helper. **Unsigned Release iPhone build passed**. Existing transport/dependency warnings remain; no dependency version, signing configuration, deployment or billing configuration changed.
- **25 native UI tests passed, zero failures**, on a dedicated iPhone 17 simulator running iOS 26.5. The full suite includes five new Tagalog tests and the existing Spanish, Portuguese, Norwegian and Mandarin paths. Tagalog coverage verifies onboarding, the full display alias at maximum accessibility size, subtitle choice after Back, Settings/themes/Words, a disabled language picker during a conversation, saved transcripts after reset and switching, and persistence across a normal relaunch. Result: `.build/Tagalog-UI.xcresult`, completed at 23:03 WEST. Visual inspection confirmed readable selection, captions and English meanings; [unedited screenshots](../marketing/screenshots/language-support/README.md) are included.

Xcode's initial WebRTC artifact download stalled. The same pinned release archive was downloaded separately, verified against the manifest SHA-256 `115cb9944248a3302c0c8af17462e2576a28ccc7adef9f6a1fe66ee75d9e1cc8`, and placed in SwiftPM's artifact cache. Xcode then built successfully. This was a local dependency-cache recovery, not a package/lockfile change.

The speech guard intentionally skips Tagalog because the local Apple recognizer misclassified valid Tagalog as Indonesian above 99% confidence on both macOS and the iOS 26.5 simulator. Other languages retain their previous detector behavior. The Debug report now separates mechanical `flowPassed` from reliable target detection and records the actual detector label/confidence. It does not turn unavailable language validation into a passing result.

No real-provider call or physical-device speech test was performed for this change. The owner chose automated verification with device review pending. Synthetic fixtures do not validate human speech recognition, generated pronunciation, translation accuracy, correction quality or model-generated lemmas. A proficient Tagalog speaker should review a real conversation before making those claims. Check a greeting, English support and mixed replies, café/market requests, polite register, a longer answer, meaningful corrections, English meanings, contextual lookup, a sourced topic and unavailable-lookup fallback; then interruption, mute, speaker/headphones and closure. Include unaided spoken evidence and switching/relaunch with the persistent store.


## 14 September 2026 — Tagalog integration with the native-app monorepo

Integrated upstream `main` at `1c175af64b863335bc5155588968f45f8d29d839`. The Tagalog module and tests now live under `apps/ios`, and the hosted locale and regression tests live under `services/api`. The deleted legacy `server/tests/hosted.test.ts` was not restored. Its Tagalog case was ported into the current suite and now verifies both credit-funded and minute-funded reservation/settlement, including rejection of aliases before any provider call or reservation.

Android's generated language catalog includes the same Tagalog module. A new Kotlin regression checks its label, locale, greeting, six stages, Philippine café theme, English support, single namespace and isolated archive progress. The Apple recognizer exception remains iPhone-specific; no Android classifier behavior was changed.

Checks on the integrated tree:

- **93 Swift core tests passed**, including the shared archive compatibility fixtures and all 17 focused Tagalog cases.
- **304 API tests passed with zero failures and zero skips**, using a dedicated PostgreSQL 17 test database. TypeScript check and build passed. The initial database launch used the default port while the test URL selected the dedicated port; correcting that local launch configuration allowed the full suite to run. The database was stopped afterward.
- **258 Android JVM tests passed with zero failures, errors or skips**. `:app:lintDebug` completed with zero errors (44 warnings and two hints), and `:app:assembleDebug` produced the APK. Java 17 and Android SDK 36 were used.
- **45 repository-tool tests passed**. Android generated-content, cross-platform contract and release-file checks passed.
- **Unsigned Release iPhone build passed** from `apps/ios/Mural.xcodeproj`.
- **25 native iOS UI tests passed with zero failures** on the iPhone 17 / iOS 26.5 simulator, including the complete Tagalog and existing-language flows. The Debug build and tests used the relocated project. Result: `.build/Tagalog-Merge-UI.xcresult`.

No physical-device or live-provider check was performed. The original pending Tagalog pronunciation, recognition, correction and proficient-speaker review remains pending on both platforms. Android verification here covers JVM behavior, lint and compilation; it is not an Android emulator or device conversation check.

### CodeRabbit review follow-up

The normal-relaunch UI test now restores Norwegian in an XCTest teardown block and verifies that restoration across a further normal launch. The maximum-accessibility test explicitly checks containment of the complete Tagalog card in the scroll viewport, containment of Continue in the screen and separation between them. The main README's remaining eight-language reference was corrected to nine.

Both affected UI tests ran twice: **four executions passed with zero failures** in `.build/Tagalog-Review-Fixes.xcresult` on the same iPhone 17 / iOS 26.5 simulator. Visual inspection of a fresh accessibility attachment confirmed the Tagalog text and Continue button are untruncated; the preceding list row is intentionally partly outside the viewport. Production app behavior was unchanged, so the earlier core, API, Android and full UI suite results remain recorded above. The generic 80% docstring-coverage warning was not treated as a repository requirement or a reason to add boilerplate documentation to self-describing test functions.


## 15 September 2026 — upstream Android parity and recovery integration

Merged upstream `main` at `926fd95` into the Tagalog branch. The Android README retains the new Mandarin word-link and platform-specific pinyin guidance while reporting all nine languages. The hosted-session suite retains every new upstream rejection/recovery test and the existing Tagalog credit/minute reservation and settlement test. Android content was regenerated with the updated exporter, preserving the upstream Swift-derived defaults and meaning-language catalog.

- **351 API tests passed with zero failures or skips**, using an isolated PostgreSQL 17 database, which was stopped afterward. TypeScript check and build passed.
- **315 Android JVM tests passed with zero failures, errors or skips**. Android Debug APK assembly and lint passed.
- **53 repository-tool tests passed**. Generated-content and cross-platform consistency checks passed, and the PR diff against upstream passed whitespace validation.
- **Swift core build passed** using the installed Command Line Tools. The Swift test rerun could not compile because those tools do not include XCTest; the selected Xcode installation now requires license acceptance. The earlier successful Swift/UI results remain historical, not a new test pass. No iOS application source changed in this merge; upstream added a shared redirect-fixture test. Native UI and physical-device/provider checks were not rerun.

### Review of other language PRs

Reviewed Afrikaans (#21), European Portuguese (#27), multilingual classrooms (#26), the Mandarin/four-language contributions (#4/#15), Android parity (#23) and the related English startup fix (#28). [Decisions and source links](../docs/tagalog.md#lessons-from-other-language-contributions) distinguish inherited improvements from separate product changes.

Added five Android Tagalog regression tests covering exact caption text and lookup links, aspect versus voice/focus vocabulary through archive export/import, homographs and language-scoped hidden words, rejection of foreign/display-alias evidence, and subtitle/typed support that cannot count as unaided recall. Corrected the Android store description to include Tagalog.

- **320 Android JVM tests passed, zero failures, errors or skips**, including all five added cases; Debug lint passed.
- **53 repository-tool tests passed**; generated-content and cross-platform checks passed.
- No production application or API code changed in this follow-up. Swift/UI/API suites were not rerun. These fixture tests validate storage and text handling, not generated linguistic judgments. Real-device/provider and proficient-speaker review remain pending.

### Integration of the International English startup fix

Merged upstream `3a12147` (PR #28). Resolved the append conflict in `services/api/tests/hosted.test.ts` by retaining both complete English and Tagalog integration cases. The provider test now expects ten requests: nine native locales plus the existing `en-US` compatibility alias. The upstream registry-to-API admission check is retained unchanged and includes Tagalog automatically.

- **357 API tests passed with zero failures or skips**, including both funded language flows and actual native-registry admission. TypeScript check/build passed; the isolated PostgreSQL test database was stopped afterward.
- **324 Android JVM tests passed with zero failures, errors or skips**; Debug assembly and lint passed.
- **53 repository-tool tests passed**; generated-content and cross-platform checks passed.
- No iOS source changed. Swift/UI and physical-device/provider checks were not rerun; the previously documented linguistic review remains pending.
