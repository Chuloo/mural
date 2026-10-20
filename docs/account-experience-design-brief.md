# Mural Settings and Account: implementation brief

Design revision 3 · Implementation brief revision 5 · 26 September 2026

**Status:** William approved implementation on 26 September 2026. The Settings and Account redesign is implemented on iOS and Android. Verification results and remaining device checks are recorded in [the implementation report](../verification/account-experience/README.md).

**Platforms:** iOS and Android, with platform-specific navigation, controls and sign-in options kept native.

**Verification:** [Test plan](account-experience-test-plan.md) and [recorded results](../verification/account-experience/README.md).

**Approved visual reference:** [Settings and Account preview, revision 3](/Users/william/.codex/visualizations/2026/09/25/01a0d7ad-db22-7470-9caa-e1ac257655e8/mural-settings-account-design.html). The HTML establishes hierarchy, copy and states; native controls govern the final platform appearance.

This revision supersedes the combined “Account & access” proposal. William’s direction is to keep Account focused on the Mural account and its usage. Conversation access and personal OpenAI keys belong in Advanced near the bottom of Settings. New installations default to Mural minutes. Remove “Start talking.”

## General Settings

Use the short navigation title “Settings,” with the existing Done action. Keep cream, warm brown text, and softly grouped rows. The list has this order:

1. **Account.** A small orb and the email or sign-in prompt. Open Account. Show identity here, without a source selector or repeated balance.
2. **Your learning.** Learning language, meaning subtitles, meaning language, and Interests. The Interests detail preserves the current 500-character limit and saves edits as the user types; Back returns without an extra save step. Language choices use single-choice menus. “Corrections happen gently as you talk” is supporting copy, since the existing correction behavior is not adjustable.
3. **Conversation.** Session limit: 5, 10, 15, 20, 30 or 60 minutes. Explain that this ends an individual session and is separate from available minutes. Do not present it as a spending cap.
4. **Your data.** One “Learning backup & data” destination for export, import, and local deletion, plus existing history management where available. Explain local storage and backup contents there. Keep account deletion and local learning-data deletion distinct.
5. **Advanced.** Conversation access defaults to “Mural minutes.” A single-choice menu offers “My OpenAI key.” Key management, provider usage/limits, and any credit issue appear here. This section is immediately before Help & privacy.
6. **Help & privacy.** Contact support, Privacy policy, Terms of use, and About Mural. About contains the version, AI data permission, provider data controls, open-source notices, and the data-routing explanation. Existing consent review/revocation remains reachable where supported.

Remove the rough voice-cost estimate. Recorded voice duration and search-call diagnostics move into Advanced and are labelled as activity recorded on this device; mixed hosted/personal activity must not be described as personal OpenAI spending. Provider usage/limits belong alongside key management. General Settings carries no OpenAI billing material.

## Account

The title is “Account.” Do not include source selection, key management, OpenAI billing, credit errors, or duplicate Help/Privacy groups.

### Signed in and using Mural minutes

- Compact identity row: 64 pt orb, “Your account,” optional email, and provider label. Allow relay addresses to wrap. Do not invent a name or avatar.
- One short note: “Your conversations and learning history stay on this iPhone.” Android uses “this phone.” Signing in must not suggest cloud backup.
- One quiet usage card headed “Mural minutes,” with the verified remaining amount, “conversation time remaining,” and “Updates after each conversation.” “Free” appears only when the balance is known to contain solely trial time. The preview’s 8 min 54 sec is sample data.
- “Sign out on all devices” as plain brown text. “Delete account…” below it in semantic red. Both have at least 44 pt targets, no persistent outline, and a subtle pressed state.

### Signed out

Show the reason to sign in, Apple and Google buttons, terms/privacy acknowledgement, and a quiet way to continue as a guest. Do not show a guest allowance inside Account: this screen is for the account. Guest practice still works through the existing Mural trial; this design does not introduce mandatory sign-in or promise a fresh allowance.

### Using a personal key

Account remains about identity. Hide its Mural allowance card while personal-key access is active. Do not replace it with an OpenAI card. The account screen can be shorter in this state; empty space does not need extra content. Advanced is the settings location for personal-key access and failures.

## Advanced

Most people see only “Conversation access · Mural minutes.” Use an ordinary settings row, without a promotional card, orange accent, balance, or explanation of API billing.

Selecting “My OpenAI key” reveals setup if no key exists. Keep the current source until “Save & use my key.” If a key is already stored, confirm switching with a short cost explanation. Saving a replacement while already using a personal key does not affect Mural minutes.

When a key is saved, show an “OpenAI key” row below the selector with storage status. Its detail screen contains “Key saved on this device,” a secure replacement field, removal, cost explanation, and provider usage/limits. The stored secret is never loaded into visible text or copied into the clipboard. Storage status does not establish that a key works or has credit. Do not show an OpenAI credit amount the app cannot verify.

Personal-key copy: “No Mural minute limit. OpenAI bills your account for usage.” Do not call this “free forever.” There is no Mural allowance meter in this state on Settings, Account, or Talk. Keep the separate session-length setting.

Disable source selection during an active conversation. After a failure, disconnect the failed session and preserve its transcript before switching. Removing an active key must never silently spend Mural minutes: retain a blocked personal-key state until the user adds a key or explicitly chooses Mural.

## Credit issues and recovery

Show the specific failure where the conversation stops. Its settings entry is under Advanced, with a quiet status matching the cause: “Credits used up,” “Spending limit reached,” “Usage limit reached,” “Billing needs attention,” or “Key not accepted.” Temporary rate/network failures use retry guidance. Account remains unchanged.

| Condition | Treatment |
| --- | --- |
| Confirmed prepaid credit exhaustion | “OpenAI credits used up.” Explain why the conversation stopped; offer an explicit switch to Mural. |
| Organization/project spending cap | Explain the spending limit and the relevant setting; adding credit alone may not help. |
| OpenAI-assigned usage cap | Explain that OpenAI’s usage limit was reached. |
| Ambiguous quota response | “OpenAI billing needs attention.” Avoid claiming credits definitely ran out. |
| Invalid/revoked key | Offer replacement under Advanced. |
| Rate limit or temporary connection failure | Retry appropriately; do not claim the user needs credits. |

These distinctions follow [OpenAI’s error guidance](https://developers.openai.com/api/docs/guides/error-codes), checked on 25 September 2026. Both current provider-failure allowlists omit the specific credit/spend/usage codes. The implementation contract includes `credit_balance_exhausted`, `organization_spend_limit_exceeded`, `project_spend_limit_exceeded`, `organization_usage_limit_exceeded`, and the rate-limit code `slow_down`. Status and code determine a safe category; `insufficient_quota` alone remains ambiguous. User-visible errors contain safe categories and validated support references, never provider response bodies or keys.

“Use Mural minutes” opens a confirmation and checks the authoritative balance. Only this switch flow shows available Mural time to a personal-key user. Confirmed zero blocks switching; an unavailable balance prompts retry. Confirmation changes the next conversation’s source, retains the saved key, and does not open the microphone. Cancelling changes nothing.

The approved scope uses a neutral credit message and explicit Mural fallback. It introduces no OpenAI purchase link. Any future top-up link needs a separate storefront review against [Apple’s payment rules](https://developer.apple.com/app-store/review/guidelines/#payments). Returning from a provider website does not prove the issue is resolved. A successful provider response clears the corresponding failure; replacing a key clears the old key’s failure and leaves the replacement unverified.

## Visual system

| Element | Treatment |
| --- | --- |
| Canvas | Existing cream `#FFF9EE` |
| Primary / supporting text | Warm ink `#362A22` / brown `#735B4A` |
| Group surface | Warm white `#FFFCF7`; 22 pt reference radius for custom content cards; native Form groups keep system geometry; no shadow or decorative outer stroke |
| Separators | Thin `#E8DED3`, inset to the text edge |
| Controls | Full-row targets; chevrons for navigation, menus for selection, a switch only for on/off |
| Main recovery / confirmation | One prominent action; native dialog roles, or a warm ink filled button in a custom recovery sheet |
| Sign-out / deletion | Borderless brown text for sign-out; native destructive red for deletion (`#B33B32` is the preview reference) |
| Type | System text and rounded semibold headings; semantic scaling, 13 pt supporting/legal text minimum at the default size |
| Spacing | Native form spacing; custom content starts from 20–24 pt page inset, 24–28 pt between groups and 40 pt before account exit actions |

Use native Apple and approved Google sign-in styling with equal prominence where both are supported. Their branding is the exception to Mural’s colors. No orb animation on Settings or Account. Full rows are tappable, with pressed feedback and accessible labels. Text, email addresses and error messages wrap vertically. Fixed heights never clip scaled text.

### iOS native behavior and Liquid Glass

The implementation uses `NavigationStack`, `Form`/`Section`, `NavigationLink`, menu-style `Picker`, `Toggle`, native sheets, alerts and `confirmationDialog`. Navigation and system controls retain Liquid Glass and scroll-edge behavior. Custom toolbar backgrounds or a second glass layer must not obscure these effects. Cream and warm-white content surfaces remain solid. This follows Apple’s guidance on [adopting Liquid Glass](https://developer.apple.com/documentation/TechnologyOverviews/adopting-liquid-glass?changes=_2_11) and [materials](https://developer.apple.com/design/human-interface-guidelines/materials).

- Settings opens as the existing native sheet. Detail screens push inside its navigation stack. Back returns to the previous scroll position; Done closes Settings. Swipe-back and sheet dismissal remain available.
- Language and access menus display one selection and a native checkmark. On/off controls remain switches. Interests and backup details use native keyboard and file presentation.
- System geometry takes precedence over exact mockup radii and toolbar padding. Existing app content colors remain consistent with Talk.
- Touch targets are at least 44 × 44 pt. VoiceOver announces label, value, enabled state and action. The decorative orb is excluded.
- Dynamic Type, Increase Contrast, Reduce Transparency and Reduce Motion preserve readability and operation. Native adaptation remains enabled. Custom elements need explicit verification.
- Light and dark system appearance are checked against the app’s supported appearance policy. This redesign does not introduce a separate dark theme.

### Android parity

Android shares section order, labels, state visibility, balance semantics and explicit source switching. Compose/Material menus, sheets, dialogs, back gestures, ripples, typography and file pickers remain native. It does not imitate Apple glass. Interactive targets are at least 48 × 48 dp, with TalkBack semantics and system font scaling, consistent with [Compose accessibility guidance](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

Apple sign-in is an iOS capability in this scope. Google is the common provider for cross-device account tests. Supported account actions and existing deletion-support paths remain reachable on Android. Existing purchase controls retain their feature/storefront gates; this redesign neither enables purchases nor changes prices. If enabled by the tested build, minute purchases remain account-related and visible only in the Mural-minute context.

## State and interaction contract

| Account / source | Settings account row | Account content | Advanced | Talk while idle |
| --- | --- | --- | --- | --- |
| Guest / Mural | Account · Sign in, optional | Sign-in benefits, available providers, legal text, continue as guest | Mural minutes | No persistent balance; Start checks eligibility |
| Member / Mural | Account · email or provider fallback | Identity, Mural allowance, account actions | Mural minutes | No persistent balance |
| Guest / personal key | Account · Sign in, optional | Optional sign-in; no allowance | Key status and management | No Mural allowance |
| Member / personal key | Account · email or provider fallback | Identity and account actions; no allowance | Key status and management | No Mural allowance |
| Personal key selected, key absent | Identity as above | Same source-specific visibility | Key required; Add key and explicit switch to Mural | Start opens key setup; no microphone or hosted lease |

Talk has no persistent minute balance for guests or members. Start checks the current allowance. Android uses its guest access sheet when time is exhausted or the check fails; iPhone shows a native interruption alert with direct actions for sign-in when required and OpenAI key setup. This keeps eligibility honest without adding a balance or retry line to the home screen. Source recovery opens Settings at Advanced.

Keep the original orange microphone button in both idle and active voice states. It remains the clearest call to action beside the orb. Its accessible label changes from “Start conversation” to “Mute microphone” or “Unmute microphone” as appropriate; the muted state uses the slashed icon. Do not add a separate “Microphone off” label below it.

On iPhone, keep the idle greeting and its meaning together in the caption area. Once a response arrives, each long caption keeps its own scroller and the orb retains its minimum size. Android follows the same visual grouping with its native layout.

### Balance states and concurrency

| State | Display and action |
| --- | --- |
| First load or explicit refresh | Checking…; never an assumed 10:00 |
| Verified positive balance | Remaining minutes and seconds, derived from authoritative available milliseconds |
| Confirmed zero | No Mural minutes remaining; no hosted start or source-switch confirmation |
| Failed lookup | Couldn’t check your minutes · Retry; never zero or a recycled account’s value |
| Previously known balance being refreshed | Last checked value may remain only for the same owner and is explicitly marked “Updating…”; it cannot authorize a switch |
| Active hosted conversation | Account usage says “Updates after this conversation”; source and account mutations are disabled |

Display uses whole seconds rounded up from positive milliseconds, then splits minutes and seconds. Exactly zero remains zero. Display rounding never determines eligibility. Both clients use the same fixture cases: 0, 1, 999, 1,000, 59,999, 60,000 and 534,000 ms.

Refreshes are bound to owner, source and request generation. Sign-in, sign-out, deletion and source changes invalidate obsolete results. An earlier guest or account response cannot overwrite current member state. After sign-in, identity success and trial-transfer success are distinct; a failed transfer is reported accurately with a retry. No new trial is promised.

Opening a source-switch sheet performs a balance check without opening a voice session. Cancel, back or swipe-dismiss leaves the source unchanged. A confirmed positive response enables one explicit “Use Mural minutes” action. Confirming persists the source, retains the key and returns to the idle app. The next Start operation still rechecks server eligibility. A sign-in change, background transition or intervening conversation invalidates the pending confirmation and requires a fresh check.

### Keys and account actions

- A fresh installation without a saved choice uses Mural minutes. An existing explicit personal-key choice survives update/relaunch. Existing key migrations retain their established source instead of silently changing who pays.
- Save & use my key commits only after secure storage succeeds. Failure leaves the previous source and key intact. Replacement is unavailable during a conversation.
- Key removal requires confirmation. Removing an active key leaves personal-key access selected and blocked; removing an inactive key leaves Mural selected. Backing out never removes a key.
- Sign-out requires confirmation explaining that this app’s sign-in sessions are revoked and local learning stays. Actual remote revocation failure is reported accurately, including any local-only outcome.
- Delete account uses a distinct destructive confirmation, existing identity reauthentication and the server’s actual eligibility result. Failed deletion never shows success. Account deletion does not delete local learning or reset trial eligibility.
- Account mutation controls are disabled during a conversation or another mutation. Repeated taps produce one operation. Preferences and history survive account actions.
- Pull to refresh updates profile and relevant Mural balance. Personal-key users receive no balance card or automatic source change.

## Implementation map and known gaps

These are the areas covered by the implementation. Paths are relative to the repository root; test evidence and open device cases are in the [implementation report](../verification/account-experience/README.md).

| Area | iOS | Android | Required outcome |
| --- | --- | --- | --- |
| Settings and detail navigation | `apps/ios/App/LibraryViews.swift` | `apps/android/app/src/main/java/chat/mural/ui/SettingsScreen.kt` | Approved order, Interests/data/About detail screens, Advanced access selector |
| Account | `apps/ios/App/ManagedAccountView.swift`, `ManagedAccountStore.swift` | `apps/android/app/src/main/java/chat/mural/ui/AccountSheet.kt`, `AccountViewModel.kt` | Shared identity state; conditional allowance; quiet actions; no access selector |
| Source and balance state | `apps/ios/App/ConversationCoordinator.swift`, `HostedAccess.swift` | `apps/android/app/src/main/java/chat/mural/MuralViewModel.kt`, `core/ConversationProviderStore.kt`, `core/AccountController.kt` | Owner-bound refreshes, deliberate transitions, no silent fallback |
| Failure mapping | `apps/ios/Core/ProviderFailure.swift`, `apps/ios/App/APIClient.swift`, `LiveTransport.swift` | `apps/android/app/src/main/java/chat/mural/core/ProviderFailure.kt`, `network/APIClient.kt` | Typed safe failures survive transport to UI; both HTTP and live-session paths covered |
| Talk entry and recovery | `apps/ios/App/RootView.swift` | `apps/android/app/src/main/java/chat/mural/ui/TalkScreen.kt` | No home balance or redundant idle labels; original orange mic, Start eligibility check and direct Advanced recovery |
| Theme and accessibility | `apps/ios/App/Design.swift` | Existing `MuralTheme` and `MuralColors` definitions | Shared content palette with platform-native chrome and accessible controls |

The original gaps in key removal, Account source placement, provider error classification, and iOS balance ownership were addressed in the local implementation. Live account, voice, accessibility, and appearance checks still determine release readiness.

The implementation lives in the isolated checkout at `/Users/william/Documents/ChatGPT/Hej/MuralAccountNative`, branch `codex/ios-account-native`. It retains the unrelated edits copied from the release checkout. The release checkout has not been changed for this redesign.

## Delivery stages and completion criteria

| Stage | Completion evidence |
| --- | --- |
| 0. Brief | Complete: approved hierarchy, native behavior, state rules and linked tests |
| 1. iOS implementation | Implemented locally; automated and physical results in the report |
| 2. Android alignment | Implemented locally; automated and physical results in the report |
| 3. Parity review | Synthetic states compared; remaining live and accessibility cases listed in the report |
| 4. Release readiness | Pending remaining device checks and a release build; distribution follows separate direction |

“Matched on both platforms” requires Stage 3. An iOS-only implementation may be ready for iPhone review while Android alignment remains explicitly outstanding. Neither HTML previews nor passing mocked UI tests establish real sign-in, microphone behavior or Liquid Glass quality.

A server change is not assumed. Compatibility review covers balance ownership, guest transfer, session revocation, deletion and provider error fields. Any required server work follows `AGENTS.md`, including deployment and live compatibility verification as part of an authorized release. No server, signing, distribution, pricing or public-link changes are part of completing this brief.

**Current status:** Local implementation and verification are underway. Use the [implementation report](../verification/account-experience/README.md) for actual results and remaining cases.
