# Mural iOS 1.0 (3): App Review response

On 26 September 2026 at 19:30 Oslo time, submission `1ccffc90-6572-4777-b2a6-f261bca6298d` returned to **Waiting for Review** after an Information Needed rejection under Guideline 2.1. The submitted binary remains build 1.0 (3). **Manual release** is selected, so approval will not publish the app automatically.

## What Apple received

- A reply to the existing App Review message and matching App Review Notes. Both describe Mural's purpose and adult audience, onboarding and feature access, the temporary Google review login, optional bring-your-own OpenAI key, external services, regional behavior, private user content, and why no authorization documents apply. The login was entered only in App Store Connect, not in this repository. The owner confirmed it has no extra verification or trailing space in the password. Keep the account active through approval.
- The same 3 min 20 sec physical-iPhone recording attached to the reply and App Review Notes: `release/private/Mural-App-Review-iOS27-build3-redacted.mp4`. App Store Connect confirmed internal TestFlight build 1.0 (3) installed on the iPhone 16 Pro running iOS 27.0 immediately before recording.
- The recording begins on the Home Screen, launches Mural, shows a voice conversation and successful Meaning request, Themes, Words, Settings, Apple account creation and completed deletion, and Google review account sign-in. It has audio. Black rectangles cover the owner's personal Apple email, Apple account name and photo, and an unrelated Google passkey account identifier. The original `/Users/william/Downloads/mural video 2.mp4` remains untouched. The attached copy is H.264/AAC, 1206 × 2622, 30 fps, about 22 MB.

App Store Connect showed **Messages (2)** with the new reply and attachment. After **Update Review** and **Resubmit to App Review**, the submission and version both showed **Waiting for Review**. Reloading the version page confirmed the recording, review login, and manual release setting remained saved.

## Meaning issue found during preparation

The first recording was withheld because Meaning sometimes showed “Mural's free conversations are unavailable” while the live conversation continued. Sanitized production logs identified HTTP 429 `helper_session_limit` on helper calls. An early session's automatic helper requests could exceed the prior limit of 24 requests per earned conversation minute; iOS build 3 displayed a generic conversation error for that helper-specific failure.

With no active conversations, the production API's `HOSTED_HELPER_REQUESTS_PER_MINUTE` was raised from 24 to 48 in both `/opt/mural/deploy/backend.env` and the Compose override, then the API container was recreated. Backups are at `/opt/mural/deploy/backend.env.before-helper48-20260926` and `/opt/mural/deploy/compose.production.before-helper48-20260926.yaml`. The running container reports 48 and healthy database, hosted voice, and guest minutes. Existing helper spending limits were not changed. The second physical-device recording shows Meaning succeeding on a new session; it does not establish that every future request will succeed.

A separate local iOS task fixed helper pacing, error wording, and the vertical position of the Meaning, mic, and End controls. Those client changes are **not** in submitted build 3. Ship them in a later build if Apple reports the issue or for the next release; do not describe them as part of this submission.
