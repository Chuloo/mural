# Zero server telemetry: egress inventory and outcome (ticket 18)

28 November 2026 — branch `ticket/18-zero-server-telemetry`, based on `feature/on-device-model` @ `9d0c962`.

Mural asks a server only when the learner's own action requires that server. This record inventories
every outbound path in the Android app, states which ones were automatic, and records what now
enforces the guarantee. The iPhone app is untouched: it has no AI-report path and no On-Device Model
provider, and nothing in this change alters its archive format (a saved report rides the Android
archive as an optional key that Swift's decoder ignores).

## Outcome

- **A report stays on the phone.** Tapping **Save report** in the report sheet writes the report ID,
  learning language, reason, the bounded and credential-checked excerpt, the consent version and the
  date into the learner's archive (`Archive.reports`). The sheet confirms "Your report is saved on
  this phone", the disclosure says nothing is sent, and the report travels with the existing backup
  export and import in **Settings → Data**. `ReportClient`, its capability probe and the server's
  `POST /v1/feedback/ai` and `GET /v1/feedback/capabilities` routes are gone.
- **No automatic probe runs for a provider that does not need it.** Hosted readiness (capabilities,
  minute balance, guest grant) now runs only while **Mural Minutes** is the chosen Conversation
  Provider; the account profile/balance probe and the minute-store probe never run while
  **On-Device Model** is the chosen provider. Launch, foreground return, preference changes and
  account-sheet dismissal therefore reach no server on their own for an On-Device Model learner, even
  with a key saved, an account signed in and minutes available.
- **Nothing about a failure leaves the phone.** Error references are received from the server that
  already handled the request (`X-Mural-Error-Reference`, OpenAI's `x-request-id`) and shown in the
  error dialog; the app never sends one. Helper diagnostics are a DEBUG-only `Log.d` line with a
  fixed purpose, HTTP status and recognized error code — no body, credential, URL or account ID.

**Handover note: no server deployment is required for this ticket's app change.** The app no longer
calls the report endpoint, so it works against the production image as it stands. The route removal
in `services/api` ships with the next deployment a human authorizes; until then the endpoint stays
live but unused, and its retention task keeps expiring rows stored before the removal (see
`services/api/docs/ai-reporting.md` for the operator steps, including dropping the tables only after
they are empty). Nothing here activates a feature, changes pricing or destroys data.

## Egress inventory

Every outbound path in `apps/android`. "Automatic" means the app could start it with no learner
action in that moment.

### User-initiated functional traffic (kept)

| Path | Endpoints | Trigger |
| --- | --- | --- |
| `APIClient` (Personal Key) | `POST https://api.openai.com/v1/responses`, `POST /v1/live/sessions` | The learner starts a conversation, types a reply, or asks for a meaning, assessment, lookup, delegation, topic or help Helper while **Personal Key** is the chosen provider |
| `LiveTransport` (Personal Key voice) | WebRTC media to the OpenAI Realtime session the SDP answer names | The learner starts a voice conversation on **Personal Key** |
| `HostedAPIClient` (Mural Minutes) | `GET v1/live/capabilities`, `GET v1/live/sessions/current`, `POST v1/live/sessions`, `GET v1/live/sessions/{id}`, `POST v1/live/sessions/{id}/close`, `POST v1/live/sessions/{id}/helpers` | The learner starts, continues or ends a conversation, or asks a Helper, while **Mural Minutes** is the chosen provider |
| Hosted voice media | WebRTC to the hosted session's transport address | The learner starts a voice conversation on **Mural Minutes** |
| `ManagedAccountClient` | `GET v1/auth/providers`, `POST v1/auth/challenge`, `POST v1/auth/exchange`, `GET v1/account`, `GET v1/minutes`, `POST v1/auth/sign-out`, `DELETE v1/account` | The learner opens the account sheet, signs in, signs out, deletes the account, or taps **Refresh** |
| `GuestMinuteClient` | `POST v1/guest/minutes`, `GET v1/minutes`, `POST v1/minutes/link-guest` | The learner chooses **Mural Minutes** and needs a guest wallet, checks a balance on demand, or links a guest wallet while signing in |
| `MinuteCommerceClient`, `PlayBillingAdapter`, `StripeMinutePurchases` | `GET v1/minutes/products`, `POST v1/minutes/orders`, `GET v1/minutes/orders/{id}`, `GET v1/minutes/orders/by-key/{key}`, `POST v1/minutes/orders/{id}/play`, `POST v1/minutes/play/recover`, `GET v1/minutes`; Play Billing IPC | The learner opens the minute store, buys, or the app recovers their own unfinished purchase |
| `OnDeviceModelDownloader` | The curated catalogue's HTTPS model URL, or a link the learner pastes | The learner taps **Download** or imports a direct link; **Cancel** stops it |
| Credential Manager / Google Sign-In | Google Play services IPC | The learner taps sign-in |

### Automatic traffic, removed

| Path | Was | Now |
| --- | --- | --- |
| `ReportClient` | `GET v1/feedback/capabilities` when the report sheet opened, `POST v1/feedback/ai` on submit | Deleted with its server route. A report is stored in `Archive.reports` |

### Automatic traffic, gated

| Path | Was | Gate now |
| --- | --- | --- |
| Hosted readiness: `GET v1/live/capabilities` + `GET v1/minutes`, and `POST v1/guest/minutes` for a guest wallet | Every launch, every foreground return (`MainActivity.onStart`), every preference change, account-sheet dismissal, and a member session on any provider | `MuralViewModel.refreshHostedReadiness()` returns without a request unless **Mural Minutes** is the chosen provider, and clears a readiness that is no longer that provider's business. Explicit minutes actions — starting a minutes conversation, the minutes sheet's **Retry**, choosing **Mural Minutes**, a purchase — still refresh |
| Account probes: `GET v1/auth/providers`, `GET v1/account`, `GET v1/minutes` | `AccountViewModel` init and every `MainActivity.onStart`, for any provider | `AccountController.restore()` is local-only (it publishes the stored session and requests nothing). `AccountViewModel.refreshAutomatically()` consults the durable `ConversationProviderStore` selection and skips the probe entirely while **On-Device Model** is chosen; the account sheet's **Refresh**, sign-in, sign-out, deletion and a purchase still call the ungated `refresh()` |
| Minute-store probes: `GET v1/minutes/products`, Play Billing connect, `GET v1/minutes` | `MinutePurchaseViewModel.onForeground` on every foreground return when purchases are enabled | Skipped while **On-Device Model** is the durable choice; opening the minute store or a completed purchase still refreshes |
| Recovered final assessments: `POST https://api.openai.com/v1/responses` with the learner's own key | Up to `MAX_RECOVERED_PER_LAUNCH` owed assessments from interrupted Personal Key conversations, retried at every launch, key save and preference change | `MuralViewModel.recoverFinalAssessments()` runs only while **Personal Key** is the chosen provider, and choosing it resumes the queue. On-Device Model and Mural Minutes sessions were already excluded from recovery tickets, so an on-device learner's launch spends nothing |
| Hosted session settlement: `GET v1/live/sessions/current`, close, `GET v1/minutes` | `reconcileHostedSessions()` retried for up to 10 minutes whenever a pending hosted owner marker existed | Unchanged, and deliberately so: it only runs when the learner's own interrupted **Mural Minutes** conversation left reserved time on the server, and it is what releases that reservation. It cannot start for an On-Device Model learner, whose sessions never create a marker |

### Paths that never leave the phone

| Path | Where it goes |
| --- | --- |
| On-Device Model conversation and Helpers (`OnDeviceLiveSession`, `OnDeviceTeachingClient`) | The Model Runtime on the phone (LiteRT-LM or AICore). A Helper that needs the web degrades to the Language Module's offline reply instead of searching |
| Model files, learning archive, saved reports, continuation checkpoints, provider selection | App-private storage; `allowBackup="false"` and `data_extraction_rules.xml` exclude every domain from cloud backup and device transfer, so the learner's own export is the only copy that leaves |
| Credentials (`CredentialStore`, `AccountSessionStore`, `GuestInstallationStore`, `StripePurchaseAttemptStore`) | Encrypted app-private storage; read only to authorize a request the learner asked for |
| Error references (`RequestErrorReference`, `MuralViewModel.resolveMessage`) | Received from the server that handled the request, shown in the error dialog. Never sent anywhere |
| Helper diagnostics (`HostedHelperDiagnostics`) | DEBUG-only `Log.d` with a fixed purpose, status and recognized code; absent from release builds |
| Language detection, pinyin, Han readings, sentence chunking, speech turn detection | Platform `TextClassificationManager`, ICU and on-device models |

### Platform traffic Mural does not control

Android's own components may reach a network independently of Mural: Google Play services (Credential
Manager, Play Billing), Play's app-update and store traffic, platform text-to-speech fetching a voice
data package, AICore/ML Kit GenAI feature checks for the system model, and the Google DataTransport
(CCT) registration that ML Kit and MediaPipe ship with — see the audit below. Mural makes no request
through them on its own, and none of them carries conversation text, transcripts, audio, credentials
or reports. Airplane Mode disables them all, which is why the On-Device Model device check runs with
it enabled.

## Dependency and merged-manifest audit

Run in `mural-android-builder` (see `apps/android/on-device-verification.md` §2.A for the invocation):

```sh
./gradlew --no-daemon --no-watch-fs :app:dependencies --configuration debugRuntimeClasspath
./gradlew --no-daemon --no-watch-fs :app:processDebugMainManifest
```

**Dependencies.** `debugRuntimeClasspath` resolves to 164 distinct `group:artifact` coordinates, 23 of
them declared directly in `app/build.gradle.kts`: AndroidX (Compose, lifecycle, credentials,
biometric, startup), Kotlin and kotlinx (coroutines, serialization), OkHttp/Okio, Play Billing,
Google identity (`googleid`), LiteRT-LM, ML Kit GenAI, MediaPipe Tasks Audio and the WebRTC SDK. No
analytics, crash-reporting or attribution SDK appears at any depth: no `firebase-analytics`, no
`firebase-crashlytics`, no `play-services-analytics` or `play-services-measurement`, and no Sentry,
Bugsnag, Amplitude, Mixpanel, Segment, Adjust, AppsFlyer, Branch, Flurry, New Relic, Datadog, PostHog
or Countly. The build applies no `com.google.gms.google-services` plugin and ships no
`google-services.json`, so no Firebase application is ever initialized.

**Finding: Google's own transport ships inside the on-device AI libraries.**
`com.google.mlkit:common:18.11.0` (through `genai-prompt`/`genai-common`, the AICore Model Runtime)
and `com.google.mediapipe:tasks-core:1.2.0` (through `tasks-audio`, the speech recognition
alternate and the open-weight voices) each pull
`com.google.android.datatransport:transport-api:3.0.0`, `transport-runtime:3.1.8` and
`transport-backend-cct:3.1.8`, plus `com.google.firebase:firebase-{components,annotations,encoders,
encoders-json,encoders-proto}` — the latter a dependency-injection and serialization library, not
Firebase Analytics. No code in `apps/android` references DataTransport, `TransportRuntime`, CCT or
any Firebase API: Mural enqueues nothing on that transport. Whether ML Kit or MediaPipe send their
own usage events is those libraries' behavior, which this repository cannot decide; §2.10's
transmitted-byte measurement on a device is the check that would show it.

**Merged manifest.** `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`
declares `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`,
`FOREGROUND_SERVICE{,_MICROPHONE,_MEDIA_PLAYBACK}`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, and from
libraries `USE_BIOMETRIC`, `USE_FINGERPRINT`, `com.android.vending.BILLING` and
`com.google.android.apps.aicore.service.BIND_SERVICE`. `android:usesCleartextTraffic="false"` and
`allowBackup="false"` with `data_extraction_rules.xml` excluding every domain. Mural's own
components are only `chat.mural.MainActivity` and `chat.mural.VoiceConversationService`; the rest
come from AndroidX (`InitializationProvider`, `ProfileInstallReceiver`, Compose tooling), Credential
Manager and Play services sign-in, Play Billing's proxy activities, ML Kit
(`MlKitInitProvider`, `MlKitComponentDiscoveryService`) and the DataTransport registration above
(`TransportBackendDiscovery`, `JobInfoSchedulerService`,
`AlarmManagerSchedulerBroadcastReceiver`, and the
`backend:com.google.android.datatransport.cct.CctBackendFactory` meta-data).

`TelemetryFreeDependenciesTest` keeps the declaration side true: it fails the unit suite if a known
analytics, crash-reporting or telemetry coordinate appears in `app/build.gradle.kts` or a component
appears in `app/src/main/AndroidManifest.xml`. The transitive graph above is the audit of record; the
test is the guard against a new declaration.

**Follow-up, needs a device.** If William wants Google's transport gone rather than unused, remove
its registration with the manifest merger and re-run the AICore and Parakeet checks on a Pixel 9,
because ML Kit's component discovery is what registers it:

```xml
<service android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService" tools:node="remove" />
<receiver android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver" tools:node="remove" />
<meta-data android:name="backend:com.google.android.datatransport.cct.CctBackendFactory" tools:node="remove" />
```

This ticket does not do that: it cannot be verified offline, and a broken AICore runtime would cost
the learner the zero-download system model.

## Tests that pin the guarantee

Offline (`:app:testDebugUnitTest`):

- `ondevice/OnDeviceZeroEgressTest` — a scripted On-Device Model conversation from start to close:
  greeting, a recorded learner turn, meaning, assessment, lookup and a web-topic Helper, plus a filed
  report. Every server seam (cloud conversation and Helper client, live-session provider, account
  service, guest minutes) is present, configured and fail-loud, and the test asserts each seam's call
  count is zero. A second test proves the fakes really fail loud, so an empty ledger cannot be a
  no-op pass.
- `core/AccountControllerTest.restoringAStoredSessionPublishesItAndRequestsNothing` — a stored
  session is published with no profile or balance request.
- `core/HostedReadinessControllerTest` — one readiness result per account and login session, and a
  superseded read is never published.
- `network/HostedHelperDiagnosticsTest` — the local helper diagnostic line carries a fixed purpose,
  status and recognized code only, never response text, a credential or an account identifier.
- `core/ReportControllerTest` — a report is saved once, a duplicate tap cannot save twice, a retry
  keeps its report ID, and a rejected consent, language or malformed excerpt never reaches storage.
- `ui/ReportSubmissionTest` — a saved report round-trips through a backup, an import merges reports
  by ID, and a report this phone would not save makes a backup invalid.
- `core/CrossPlatformFixtureTest` — the shared iPhone fixture has no report key and re-encodes
  without one, so the Android archive change stays cross-platform.
- `TelemetryFreeDependenciesTest` — no telemetry SDK is declared or registered.

On a device (`:app:assembleUiTestAndroidTest`, then the instrumented suite):

- `ondevice/OnDeviceZeroNetworkTest` — with On-Device Model chosen, a conversation turn and a filed
  report transmit nothing: bytes are measured for the app's own UID before and after, the turn runs
  on the Model Runtime seam, the report is saved and exported, and no readiness probe produces a
  result.
- `ReportHistoryTest` — the report sheet on a saved reply, saved on this phone and present in the
  export.
- `ReportConversationSheetTest` — saving needs a reason and explicit consent, a credential-shaped
  excerpt cannot be saved, and the disclosure and Spanish receipt say the report stays here.

The human counterpart, with a real service origin and Airplane Mode, is §2 of
`apps/android/on-device-verification.md`.

## Limits and follow-ups

- **Google's DataTransport (CCT) ships inside ML Kit GenAI and MediaPipe Tasks Audio** and registers
  its backend in the merged manifest. Mural enqueues nothing on it and declares no analytics or
  crash-reporting SDK, but this repository cannot decide what those two libraries do with their own
  usage events. The audit above records it, §2.10's transmitted-byte measurement is the empirical
  check on a device, and the manifest-merger removal is the follow-up if William wants it gone.
- A saved report has no learner-visible list and no delete action: it rides the backup, and deleting
  a conversation does not delete a report filed from it. The log is bounded at
  `MAXIMUM_SAVED_REPORTS` (1,000) and a full log drops its oldest entry. Add a Settings list if
  reports ever become visible.
- The instrumented byte check measures the app's UID, not per host, and the interface build's
  configured origin is blank; the Airplane Mode run on a release build is the stronger evidence.
- `release/android/declarations.md` still describes the server-side report admission counters this
  ticket removed, and the published privacy text and Play Data safety declaration still describe
  reported text reaching Mural. Both need the human's release pass.
- A report on a Language Module whose ID is not two or three lowercase letters (Roman Konkani,
  `kok-Latn`) is still refused by the submission validation the server route once required. Nothing
  server-side needs that shape now; relaxing it is a separate decision.
