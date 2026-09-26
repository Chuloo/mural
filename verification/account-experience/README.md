# Settings and Account implementation review

26 September 2026 · Worktree `codex/ios-account-native` · Base revision `6a51ae7`

The redesign is implemented locally on iOS and Android. The final debug builds were installed in place on William’s iPhone 16 Pro and Galaxy S9. This report separates synthetic UI checks from live account and voice checks. No build from this worktree has been uploaded to TestFlight or an app store.

## What changed

- Settings now opens with Account, then Your learning, Conversation, Your data, Advanced, and Help & privacy. “Start talking” was removed. Conversation access and OpenAI key controls sit in Advanced.
- Account shows identity and account actions. It shows Mural minutes only for a signed-in member using Mural access. Sign out is plain text without an outline; Delete account uses semantic red. Guest Account has no minute card.
- Talk no longer shows a minute balance, “Microphone off” line or idle hint above the tab bar. The original orange microphone button remains on both platforms. Idle iPhone greeting and meaning captions now sit together; long responses keep their contained scrolling. Start still checks eligibility: Android presents its access sheet, while iPhone offers sign-in or OpenAI key setup from the native error alert.
- The iPhone control row retains its earlier clearance above the tab bar after the idle labels were removed.
- Switching from a personal key to Mural checks the current allowance and requires confirmation. The key remains saved. OpenAI credit, spending, usage, billing and rate failures have distinct recovery copy, with an explicit path to Mural access.
- iOS uses a full-height native Settings sheet, Form, NavigationStack and system menus. Android keeps Material bottom sheets, menus and dialogs. Both use the existing orb and cream/brown palette.

## Automated results

| Check | Result | Evidence |
| --- | --- | --- |
| Swift core package | 114 passed, 0 failed | `/private/tmp/mural-account-swift-tests-final-2.log` |
| Focused iOS Settings/Account UI | 4 passed | `/private/tmp/mural-account-focused-tests-3.xcresult` |
| Full iOS UI regression | 29 passed, 0 failed after home balance and status removal | `/private/tmp/mural-account-ios-quiet-home-2.xcresult` |
| Final iOS home, Mandarin large text and ended-session checks | 3 passed, 0 failed after caption spacing and mic restoration | `/private/tmp/mural-account-ios-caption-spacing.xcresult` |
| Final iOS home, Settings and hosted-access recovery checks | 4 passed, 0 failed after control clearance and direct sign-in/key actions | `/private/tmp/mural-account-ios-control-clearance.xcresult` |
| Android unit suite | 354 passed, 0 failed | `apps/android/app/build/test-results/testDebugUnitTest/` |
| Android lint, debug build and isolated test build | Passed with the configured local server and Google client | `/private/tmp/mural-account-android-original-mic-build.log` |
| Android related UI suite | 38 passed, 0 failed after removal of four obsolete home-meter tests | `/private/tmp/mural-account-android-quiet-home-ui.log` |
| Final Android idle and active mic check | 1 passed, 0 failed after restoring orange | `/private/tmp/mural-account-android-original-mic-ui.log` |
| Cross-platform consistency script | Passed | `python3 scripts/check_cross_platform.py` |

### Meaning failure found during live iPhone testing

The iPhone showed a meaning, then replaced it with “Mural’s free conversations are unavailable” while voice continued. The caption controller had sent a new meaning request for a later transcript fragment and displayed the generic hosted error when that request failed. The separate App Store review task reported a server `helper_session_limit` response during this session: 40 helper requests over 112 seconds. That task raised the production request limit from 24 to 48 per minute without changing the spending cap; new sessions use the new limit.

The iOS client now waits for a sentence or a quiet pause and spaces helper requests across successive passages. A failed meaning request shows guidance about meanings while voice stays active. A conversation-wide limit does not offer a retry that cannot succeed. The Meaning, microphone and End controls keep the same vertical position in idle, active and meaning-error states. The [simulator capture](ios-13-meaning-recovery.png) shows the revised error state. This revision passed 117 Swift core tests and four focused iOS UI tests, including measured control positions and large text. The revised client has not been installed on the physical iPhone while the separate App Store review task prepares a recording of the submitted build, so live retesting remains open.

The first full iOS run passed 23 of 28 tests. Three onboarding tests still expected the former radio list, one quota test expected the former error copy, and one sign-out assertion raced a native popover. Those tests were updated; the 29-case run then passed, including Mandarin at the largest accessibility text size and the new source-switch confirmation test. The first Android selection passed 39 of 40; its onboarding test still looked for AI permission on the old Settings page. That test was updated to navigate through About. A later 41-case run exposed an idle greeting clipped on a second viewport, which led to the compact Talk layout fix and a new regression test. The 42-case run passed after that fix.

Both cores now read [one shared account-access fixture](../../shared/fixtures/cross-platform/account-access-cases.json) for seven millisecond-boundary balances and eight provider failures. Account and source switching use the same rounded-second rule; Talk no longer displays a balance. The Android switch sheet previously used a purchase-balance formatter with decimal minutes; it now shows minutes and seconds like iOS.

## Device review

| Device | Completed | Remaining |
| --- | --- | --- |
| iPhone 16 Pro | Final signed debug build installed in place. Talk, meaning on/off, full-height Settings, guest Account and retained Norwegian preferences were inspected through iPhone Mirroring. The original meaning-off preference was restored. William completed a guest voice conversation and Google sign-in on the immediately preceding UI build; Account then showed about 6 min 22 sec, matching the Galaxy. Auth and billing code did not change in the final control-spacing build. | Exact balance accounting through the guest call, personal-key routing, screen reader and appearance checks |
| Galaxy S9, Android 10 | Final Talk build installed in place. The 38-case related UI suite passed on the emulator. William completed a guest voice conversation, then signed in with Google and saw about 6 min 22 sec remaining on the preceding UI revision; auth code was unchanged by the final home cleanup. “Hei!” was fully visible. The final home screen was captured on the phone. Only `chat.mural.android` remains installed there. | Exact before/after server balance, personal-key routing and TalkBack |
| iPhone 17 Pro simulator / Android 16 emulator | Synthetic member, guest, personal-key, onboarding, failure and enlarged-text tests | Simulators do not establish live billing or device audio behavior |

The Galaxy S9 screenshots show the [Settings overview](s9-01-overview.png), [Advanced placement](s9-03-advanced.png), [large-text Interests editor](s9-07-large-keyboard.png), [guest Account](s9-08-guest-account.png), [member with Mural minutes](s9-09-member-account.png), [member using a personal key](s9-10-personal-key-account.png), and [final home](s9-12-quiet-home.png). The [final iOS simulator home](ios-12-quiet-home.png) shows the paired greeting and meaning captions and the restored control position. The Android [idle](android-12-quiet-home-fixture.png) and [active voice](android-13-active-mic-fixture.png) fixture captures show the orange mic in both states. The Account captures use synthetic identities; the guest test build deliberately disables Google sign-in.

The design review found no outlined sign-out action, clipped email or key controls, duplicate access selector in Account, or Mural minute card in personal-key Account. The S9 uses a shorter bottom sheet in personal-key mode; the dimmed area above it belongs to the parent screen. The first S9 Talk capture showed “Hei!” clipped because the guest retry state crowded the caption area. The revised layout gives the idle greeting its natural height and scales the orb to available height with a lower bound. William found the trial pale mic out of place beside the orb, so the original orange treatment was restored. The final captures show both greeting lines, the mic and bottom navigation without overlap or a home meter. On iPhone, the idle meaning caption was too far from the greeting; grouping them fixed that gap without changing long-caption scrolling. The final iPhone simulator capture also restores space between the Talk controls and tab bar. iOS uses native Form and navigation chrome rather than a second custom glass layer.

The local Android checkout initially built without its ignored server/OAuth settings, so the first physical installation could neither verify guest minutes nor offer working account sign-in. The existing Mural server origin and Google web client were restored to the ignored `apps/android/local.properties`; the replacement debug build was installed without clearing app data. William’s subsequent guest conversation and Google sign-in succeeded. The checked-in test variant deliberately keeps those values empty for isolation.

### Physical acceptance cases

| Plan cases | Status on this revision |
| --- | --- |
| D01 | Settings and guest Account were inspected on the iPhone; Settings, guest, synthetic member and synthetic personal-key Account were captured on the S9. Full paired live-member comparison remains open. |
| D02–D04 | Large text, language selection and long captions passed simulator/emulator checks; the S9’s compact greeting was also checked physically. Screen readers, appearance changes, and paired live Italian/Mandarin caption and meaning checks remain open. |
| D05 | William reported a working guest voice conversation and Google sign-in on each phone. Both Accounts showed about 6 min 22 sec rather than a fresh 10-minute grant. Exact before/after server values are still needed to verify how the iPhone guest call was charged or linked. |
| D06–D07 | Google sign-in worked on both phones and the displayed balance agreed at about 6 min 22 sec. A measured cross-device refresh after a member call and a personal-key call with unchanged Mural balance remain open. |
| D08–D10 | Provider failures and source transitions have automated fixture coverage; injected faults during a physical conversation and network interruption remain open. |
| D11–D12 | Sign-out, deletion and backup actions have fixture coverage. Confirmed account deletion is reserved for a disposable account; physical cancellation, reauthentication and backup checks remain open. |
| D13 | Both builds were installed in place without clearing app data. Stored source, key and every history field have not yet been checked across the upgrade. |

## Server and release state

The clients use existing `/v1/account`, `/v1/minutes`, guest-link and auth contracts. The Account UI revision required no server code change or migration. The later meaning incident prompted the separate production helper-limit configuration change described above. On 26 September, `https://api.mural.chat/healthz` reported hosted voice and guest minutes enabled, and `/readyz` reported database, hosted voice and guest minutes ready. These health checks do not prove a live sign-in or billed voice session on the new builds.

The implementation is ready for the remaining paired live-device cases in the [test plan](../../docs/account-experience-test-plan.md): D05–D07 for guest time, sign-in transfer and personal-key routing; D08–D12 for injected failures and destructive actions with disposable accounts; D02–D04 for screen reader, appearance and long captions on both phones. TestFlight distribution and App Store submission remain separate from this local implementation.
