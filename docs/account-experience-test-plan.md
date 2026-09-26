# How to verify Settings and Account on iOS and Android

26 September 2026 · Verification plan and acceptance cases

The acceptance contract is the [Settings and Account brief](account-experience-design-brief.md). Keep automated, simulated and real-device results separate in [the implementation report](../verification/account-experience/README.md). A prior build’s passing results do not verify this redesign.

## 1. Record the baseline

1. Record the Git revision and uncommitted patch, app version/build, backend revision/environment, enabled account/commerce flags and test time.
2. Identify William’s connected iPhone and Android phone by model and OS version. Device identifiers stay in local tooling, outside published reports. Do not infer the Android model or supported features before inspection.
3. Capture current Settings, member Account, guest Account, Advanced and key failure on both platforms using synthetic identities. Label these captures **before**.
4. Record which scenarios the Android baseline already passes. Its current layout is comparison evidence, not the acceptance specification.
5. Keep local learning data and credentials intact. Use the existing isolated Android UI-test app and an isolated iOS fixture environment. Export a learning backup before a real installation if a reinstall/signature change is needed. Do not uninstall the user's app to obtain a fresh trial.

## 2. Build deterministic test fixtures

Create one shared fixture definition under `shared/fixtures/cross-platform/` for the state contract. Both clients must consume or validate the same values. Keep these fixtures out of normal release flows. Extend the existing iOS `--preview` test harness; Android instrumentation uses the `uiTest` build type, whose application ID is `chat.mural.android.uitest`.

Inject account, balance, provider, clock and secure-storage fakes. Fixture tests must make zero production account, balance, OpenAI or payment requests and must not read/write real credentials. An unexpected network request fails the test. Existing preview flags alone do not establish this isolation; assert it.

| Fixture | Initial state and controllable response |
| --- | --- |
| `guest-mural` | Guest; Mural selected; verified 534,000 ms |
| `member-mural` | Synthetic Google member; Mural selected; 534,000 ms |
| `member-apple` | Synthetic Apple member with long relay address; Mural selected |
| `guest-key` / `member-key` | Key selected and saved in fake storage; no OpenAI balance |
| `key-missing` | Personal key selected, storage empty |
| `balance-loading` / `balance-offline` / `balance-zero` | Delayed response, lookup failure, or confirmed 0 ms |
| `balance-race` | Delayed guest/account-A response arrives after member/account-B response |
| `key-credit` / `key-spend` / `key-usage` / `key-quota` | Specific credit exhaustion, spend cap, usage cap, or ambiguous quota |
| `key-auth` / `key-rate` / `key-network` | Invalid key, temporary rate limit or offline transport |
| `active` / `mutation-busy` | Connected voice session or account operation underway |
| `storage-failure` | Key save, replacement or removal fails |
| `sign-in-transfer-failure` | Identity succeeds; guest-minute transfer fails |

Use short and long email fixtures, absent email, English/Spanish interface copy, Norwegian/Italian/Mandarin learning languages and subtitles on/off. Capture key screens only with synthetic input. Never attach a real API key, token or private account payload to test output.

## 3. Add focused automated coverage

Use stable accessibility identifiers/test tags for control intent. Keep existing useful identifiers when relocating controls. Assert user-visible state and side effects; a screenshot or label assertion alone cannot establish correct charging behavior.

### Unit and integration cases

| ID | Action | Required result on both clients |
| --- | --- | --- |
| A01 | Fresh install; relaunch; upgrade with explicit key selection | Fresh default is Mural. Explicit saved source survives. Migration does not silently change the payer. |
| A02 | Decode every account/source fixture | Balance visibility follows the brief; personal-key mode has no Mural allowance in Settings, Account or Talk. |
| A03 | Resolve delayed guest/account-A balance after account-B sign-in | Late response is ignored; no previous identity or balance leaks into current UI. Source changes and view cancellation also invalidate pending updates. |
| A04 | Load, refresh, fail and return zero balance | Loading, stale, unavailable and zero stay distinct. No default 600,000 ms is inserted. Rounding matches the brief's boundary values. |
| A05 | Select personal key; cancel; save; fail secure save | Source stays unchanged until Save & use succeeds. Cancel and save failure preserve prior credentials and source; no microphone/hosted lease is opened. |
| A06 | Replace or remove active/inactive key; cancel removal | Replacement is atomic. Active removal blocks key use without switching to Mural. Inactive removal preserves Mural. Cancel preserves the key. |
| A07 | Request Mural switch with positive, zero, offline or stale balance | Only fresh positive state enables explicit confirmation. Confirm changes source once and keeps the saved key. Cancel/back/dismiss changes nothing. Balance check creates no voice lease. Start revalidates eligibility. |
| A08 | Tap source/account actions during voice or account mutation | Unsafe transitions are disabled; repeated input starts at most one operation. A finishing call cannot commit a stale switch. |
| A09 | Map HTTP and live-session errors, including unknown/malformed codes | Specific credit, spend, usage, auth and rate outcomes remain distinct. Test every listed provider code, incorrect status combinations, oversized payloads and malicious reference text. Raw messages/secrets never reach UI or logs. |
| A10 | Receive fatal key failure during a conversation | Transport/microphone stop, transcript is retained once, source remains personal key, and no hosted fallback occurs. Temporary failures follow retry policy without fabricated credit advice. |
| A11 | Return from provider website; replace key; obtain successful response | Website return leaves failure unresolved. Replacement removes old-key failure but remains unverified. Actual provider success clears the matching failure. |
| A12 | Cancel/sign in/expire; transfer minutes fails or succeeds | Cancellation is quiet. Profile and allowance refresh after successful transfer. Partial success is truthful, retryable and cannot duplicate the trial. |
| A13 | Sign out or delete; revoke/delete fails or is blocked | Confirmation precedes mutation. Local learning remains. Failures never claim full success. Actual local-only sign-out and deletion-support states are preserved. |
| A14 | Export/import backup; delete only local learning | Round-trip supported data; reject malformed/oversized archive safely. Local deletion keeps preferences/key/account as specified. No key or account token enters backup. |

Extend `apps/ios/Tests/ProviderFailureTests.swift`, `ManagedAccountTests.swift` and the applicable cross-platform fixtures. Put newly extracted source/balance state logic under focused Swift tests. App-bound behavior that cannot run in the core package needs an app test harness with injected dependencies; do not claim coverage from an unrelated core test.

On Android, extend `ProviderFailureTest`, `ConversationProvidersTest`, `GuestMinuteControllerTest`, `AccountControllerTest`, `AccountMutationCoordinatorTest` and related existing model tests where the behavior is owned. Keep purchase isolation/regression tests intact. Test unavailable commerce with no purchase action and preserve existing gated commerce behavior without real purchases.

### Native UI cases

Add a focused iOS `SettingsAccountUITests` class to the existing `MuralUITests` target, or equivalent focused methods. Extend Android `SettingsParityTest`, `SettingsDetailsTest` and `AccountSheetTest`; remove obsolete assertions that require source selection inside Account and replace them with relocation checks.

| ID | Script | Pass condition |
| --- | --- | --- |
| U01 | Open Settings; inspect every group | Account first; Your learning → Conversation → Your data → Advanced → Help & privacy. No Start talking group; key, billing and access controls only in Advanced. |
| U02 | Open guest/member Account under both sources | Exact state visibility matches fixtures. No duplicated access selector or Help group. Long/absent email is handled. Sign-out has no bold outline. |
| U03 | Select language, meaning language, subtitles, interests and session limit; close/reopen | Single-choice menus, correct persistence, keyboard-safe Interests editor, existing duration options. Session limit never reads as a credit balance. Existing consent review/revocation remains reachable and retains its behavior. |
| U04 | Save/replace/remove key; trigger secure-storage failure | Secure input, truthful saved/unverified status, correct source behavior, no exposed stored secret. |
| U05 | Trigger each credit/billing/auth/rate failure; open recovery | Safe explanation at failure and under Advanced; Account unchanged. Mural fallback has check → explicit confirm → idle, plus cancel/zero/offline paths. |
| U06 | Open idle Talk with verified, zero and unavailable guest allowances; sign in after a short call | No persistent balance, “Microphone off” line or stray idle hint appears on Talk. The original orange mic stays tappable; its accessible label changes with voice and mute state. Start checks eligibility: Android shows its access sheet; iPhone offers sign-in or key setup from the native alert. Guest Account has no allowance; signed-in Account reflects transferred time rather than a reset trial. |
| U07 | Sign-out/delete dialogs: cancel and confirm; simulate failure | Correct action performed once; accurate outcome; learning retained. No destructive mutation on initial tap. |
| U08 | Back, Done, swipe-dismiss, refresh, rotation/background and reopen | Native navigation, preserved relevant state, no stale confirmation, duplicate sheets or unexpected keyboard/microphone. Android system Back dismisses keyboard first, then returns through destinations. |
| U09 | Default/largest text, narrow viewport, long messages, VoiceOver/TalkBack | Every action remains reachable; no horizontal clipping or overlapping targets; reading order and control values are correct. |
| U10 | Compare idle greeting and meaning, then use long Italian/Mandarin captions; open meaning and Settings, then return | Idle greeting and meaning stay close on both phones. Long captions scroll within their areas, the orb retains its existing minimum in normal portrait, and Talk controls remain usable. At extreme accessibility/short landscape sizes the existing whole-page fallback remains reachable. |

## 4. Run the automated checks

Run from the repository root unless shown otherwise. These commands are the existing project entry points; the new test coverage above must be added before their results count for this redesign. Save command exit status, selected test count and reports. A zero-test run is a failure of verification.

```sh
swift test --package-path apps/ios
python3 scripts/check_cross_platform.py
```

Build and run native iOS tests on an installed simulator. The following uses the documented `iPhone 17` destination; choose an available equivalent if its name differs. Use a new result-bundle path on each run.

```sh
xcodebuild -project apps/ios/Mural.xcodeproj -scheme Mural \
  -destination 'platform=iOS Simulator,name=iPhone 17,arch=arm64' \
  -derivedDataPath .build/AccountDerivedData \
  -resultBundlePath .build/account-ios-tests.xcresult \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  -parallel-testing-enabled NO test
```

After the focused class exists, `-only-testing:MuralUITests/SettingsAccountUITests` can narrow a rerun to a remaining Settings/Account issue. Run related language/caption/backup regressions before completion. Regenerate the iOS project with `python3 scripts/generate_project.py` when adding app/test files as required by the generator; inspect the result to preserve signing configuration and existing changes.

From `apps/android`, run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedUiTestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=chat.mural.SettingsParityTest,chat.mural.SettingsDetailsTest,chat.mural.AccountSheetTest
```

Run the related existing `TestIsolationTest`, `AccountRotationTest`, `GuestMinutesSheetTest`, `CaptionParityTest`, `MuralOnboardingTest` and any newly introduced test class when its area changes. Gradle reports live under `apps/android/app/build/reports/`; retain the applicable report and test-count summary. Follow [build and test](build-and-test.md) for full regression commands if core contracts change.

Re-run U09 on a compact simulator/emulator and at the largest supported accessibility text size. Restore system accessibility/font settings after the run. Screenshot review supplements semantic assertions; avoid brittle pixel equality for native glass, shadows or OS-specific controls.

## 5. Compare the physical devices side by side

Use William’s iPhone and Android phone for this stage when he resumes work. Run the same scenario in the same order on each. For visual comparisons, use identical synthetic identity, language, balance, preferences and error state. Pair captures by scenario rather than comparing different accounts or elapsed call durations.

For live tests, use a designated test identity and the common Google sign-in path. Test Apple sign-in separately on iPhone. Do not assume Apple and Google identities with the same email map to one Mural account. Use disposable test accounts for confirmed deletion, never William’s primary account.

| ID | Device script | Evidence and expected result |
| --- | --- | --- |
| D01 | Fresh fixture; Settings → every detail → back | Paired screenshots of general Settings top/bottom, guest Account, member Account, Advanced and key detail. Same hierarchy; iOS glass/nav and Android native controls each behave correctly. |
| D02 | Increase text; enable screen reader; repeat main paths | No clipped identity, key explanation, legal text or actions. Read selected menu values, disabled states and balance updates. Keyboard never covers Save. |
| D03 | On iPhone, change available glass/transparency/contrast/motion settings; scroll and dismiss Settings | Short recordings show native toolbar/menu/sheet transitions. Static screenshots alone do not pass the Liquid Glass check. No double blur, opaque overlay masking native chrome, flashing or unreadable content. |
| D04 | On both phones, switch languages in onboarding and Settings; run long-caption fixture and meaning modal | Single-choice language selection, matching content behavior, contained caption scrolling and stable orb/control layout. Include Italian and Mandarin with meanings on/off. |
| D05 | Guest on Mural: record authoritative balance through the test harness, make a short voice call, end, then sign in | Talk has no persistent meter. The server balance falls after the call and does not reset to 10:00 on sign-in. Account shows the transferred member balance. Record before/after milliseconds and server outcome without tokens. |
| D06 | Google sign-in on both; pull to refresh; use the same member sequentially | Identity and authoritative balance agree after refresh. A call on one device is reflected on the other after refresh. Local transcripts remain device-local. Calls run sequentially to avoid ambiguous concurrent reservations. |
| D07 | Choose saved personal key, cancel once, confirm; make a short voice call | All Mural meters disappear. Successful call uses the personal provider path and leaves authoritative Mural balance unchanged. Merely inspecting that balance for test evidence does not open a hosted call. |
| D08 | Inject provider failures in an isolated debug harness on each phone; exercise Mural fallback | Correct error category, retained transcript, stopped microphone, no automatic fallback, cancellation preserved, positive/zero/offline checks correct. No deliberate spending down of real provider credits. |
| D09 | Remove active key, dismiss/relaunch, try Start; explicitly switch to Mural | Blocked key state persists; Start opens setup. Only explicit Mural confirmation changes source. Opening recovery never activates the microphone. |
| D10 | Start a short call; inspect disabled actions; interrupt network and restore | No source/account mutation during call; accurate connection error, transcript preserved, no duplicate session and no “credits exhausted” claim for network loss. |
| D11 | Cancel/complete Google sign-out; verify the other device; test Apple separately | Confirmation works. Server-side revocation is observed on refresh/request; local learning remains. Offline revoke failure gives accurate local-only status. Apple sign-in cancellation/re-authentication works on iPhone. |
| D12 | Delete a disposable account; separately cancel and perform fixture local-data deletion; export/import sample backup | Account and learning deletion have distinct effects. Server blocks are truthful. Backup round-trip succeeds without secrets. Main user data remains intact. |
| D13 | Upgrade from current build with saved source, preferences, history and key | Data and explicit provider choice survive the update; no onboarding loop or renewed-trial claim. Record version/signature compatibility first. |

Use short live calls only for the routing/audio/balance checks; they consume the selected source’s credit. Prefer injected faults for destructive, quota and outage cases. Mark such evidence **device + simulated service**, separately from **device + live service**. A simulator/emulator pass cannot substitute for D05–D07 or native on-phone sign-in checks.

If Android is not yet aligned, complete the baseline column and record each mismatch. Repeat the paired cases after Android implementation; do not mark eventual parity complete from the first comparison.

## 6. Record results and decide readiness

Create an implementation-specific report under `verification/` with this summary, case-level links and paired captures:

| Field | Required record |
| --- | --- |
| Builds | iOS/Android version, build number, source revision and relevant feature flags |
| Environment | Backend revision, device models/OS, simulator/emulator versions and test time |
| Automated cases | A/U IDs, actual test names/counts, PASS/FAIL/BLOCKED/NOT RUN, report paths |
| Physical cases | D IDs; Android baseline, revised iOS and revised Android outcome; live versus simulated service; paired evidence |
| Visual review | Default/large text, sign-out border, glass transitions, keyboard, compact width and screen readers |
| Differences | Expected platform difference, Android work remaining, or defect requiring a fix |
| Backend | Existing contracts sufficient, or exact required server changes and verification/deployment status |
| Release | Local readiness, installed device build and distribution status separately |

An acceptable difference is a native checkmark/menu shape, navigation gesture, Android ripple or iOS-only Apple sign-in. A different payer transition, allowance visibility, inaccessible action, misleading error, stale identity or lost data is a defect.

Block readiness for accidental source switching/charging, credential leakage, stale-owner balance, broken sign-in/deletion, data loss, missing actions at large text, or failed native navigation. A missing physical device or live account check stays **BLOCKED**, with the exact remaining case named. “Both experiences match” requires the paired Android and iPhone checks to pass.

Current record: implementation and verification began after William approved them on 26 September 2026. See the implementation report for completed tests, physical-device evidence and remaining checks.
