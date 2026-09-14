# Configure Android accounts

Android uses the same account service and Google subject as iOS. An existing Google user signs into the same Mural account. Learning history remains on the device; the account stores identity and conversation-time records.

This branch implements native Google sign-in, secure session storage, account refresh, sign-out, deletion and minute-balance display. Automated tests use synthetic tokens and an isolated database. Real Android Google authorization and the production rollout still need verification. Hosted conversations and purchases remain disabled.

## Google configuration

1. Register an **Android** OAuth client for the app's package name and signing certificate SHA-1. Debug and Play-distributed builds need their respective certificates. For a Play build, use the Play App Signing certificate, not only the upload certificate.
2. Register a **Web application** OAuth client as the native account service's audience. This ID-token flow needs no browser redirect URI or client secret in the app or API.
3. Preserve the server's existing iOS `GOOGLE_CLIENT_ID`. Set `GOOGLE_ANDROID_SERVER_CLIENT_ID` to the web client ID and `GOOGLE_ANDROID_CLIENT_IDS` to the comma-separated Android client IDs accepted for this service.
4. Deploy the reviewed service and confirm `GET /v1/auth/providers` returns `googleAndroid: true`. A true flag reports configuration; it does not prove that a real Google authorization works.
5. Put these public configuration values in the ignored `apps/android/local.properties`, alongside any SDK path:

```properties
mural.apiOrigin=https://api.example.com
mural.googleServerClientID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

The origin must be HTTPS on port 443 with no path, query, fragment or embedded credentials. Empty or invalid configuration hides the optional account entry. The isolated `uiTest` build forces both values empty, even when the developer's local configuration enables them.

The app uses Credential Manager's explicit Google button flow. It requests a short-lived server challenge and passes its nonce to Google. The backend verifies signature, issuer, expiry, nonce, audience and the Android authorized party. Provider tokens stay in memory; only Mural's 24-hour session is saved with Android Keystore encryption, bound to the app and API origin. Exports and Android backup exclude it.

## Database permissions

Apply migrations 006 and 007 before deploying this branch, even when free trials remain disabled. Signup now captures a welcome offer, and deletion checks the minute journal. After the deployment's base runtime grants, apply [minute-runtime-grants.sql](../services/api/operations/minute-runtime-grants.sql) as the migration owner. This keeps policy edits and bulk grants out of the API runtime role's permissions. Keep trial-claim, guest, purchase and live-session routes gated until their respective release checks pass.

The account screen reads `/v1/minutes`; expose that authenticated GET route through the trusted proxy when enabling this client. It must not expose any proxy secret to the app. The existing iOS account flow remains available independently.

## Verify a configured build

- Complete and cancel Google sign-in, including rotation while the chooser is open. Repeated taps must not open another chooser.
- Sign into an existing iPhone Google account and verify the same account ID privately. A matching email alone must never merge accounts.
- Relaunch, refresh the profile and confirm the exact server balance. Network errors must not claim a zero balance or a successful deletion.
- Sign out, then verify revoked sessions are rejected. Current online sign-out ends all Mural sessions; the confirmation says so. Offline sign-out clears this device's session and explains that other sessions could not be ended.
- Delete a disposable account. Unresolved billing must leave it intact and provide a support path. Apple-linked deletion still requires the iPhone flow until Android's Apple authorization is implemented.
- Confirm learning archives and saved OpenAI keys survive sign-out and account deletion.

See [Google's implementation guide](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation) and the [account API reference](../services/api/docs/accounts-reference.md).
