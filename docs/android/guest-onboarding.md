# Guest conversations on Android

A fresh installation starts with Mural-hosted conversations after language selection, subtitle selection, and AI consent. Google sign-in is optional. Existing installations with a saved personal API key keep that provider; an explicit provider preference always wins. Saving a key selects the personal-key provider, and the account sheet lets guests switch back to Mural minutes.

The consent screen includes an 18-or-older confirmation in English and Spanish. It does not collect a date of birth or add an onboarding step.

## Guest identity and minutes

`GuestMinuteController` owns guest access. It creates one 32-byte installation secret and stores it in `GuestInstallationStore`, encrypted with a separate Android Keystore key. Guest credentials never enter `AccountSessionStore`, member purchase verification, learning exports, URLs, or logs. Backup rules already exclude all application preferences and local files.

`POST /v1/guest/minutes` sends `{installationToken}` without a member bearer. The server chooses the allowance and enforces grant budgets. Android never assigns minutes locally.

- `available: true` returns the guest ID, bearer, expiry, remaining milliseconds, and whether this installation resumed an existing allowance.
- `available: false` with `temporarily_unavailable` shows **Free minutes currently unavailable**, followed by a short explanation of AI costs and the work to fund more free conversations.
- `available: false` with `sign_in_required` asks the learner to return to their account.
- Network errors, malformed responses, and admission limits show a retry state. They are not interpreted as a successful grant or an exhausted allowance.

A valid guest bearer checks its wallet directly. Expiry renews the same installation and guest identity; it does not reset the trial. A grant pause leaves previously issued remaining minutes usable. If encrypted identity cannot be read, the app fails closed instead of silently generating another identity.

The guest allowance is server configuration, with a ten-minute welcome default. Daily and total funding budgets are also server controls. The app contains no default payment price or funding cap.

## Sign-in and recovery

Before Google login replaces the active conversation owner, the app closes and reconciles any guest conversation. Hosted helpers and close requests remain bound to the exact owner who created the session. A guest ID is never submitted as Google's expected member ID.

After Google authentication, Android records a transfer ticket containing the target member ID and exact guest bearer before requesting `POST /v1/minutes/link-guest`. This uses the member bearer and sends only `{guestAccessToken}`. The server determines the transfer amount.

A lost response keeps the ticket for an idempotent retry. Another member cannot take over that ticket. Guest credentials are removed only after an explicit successful result:

- `outcome: transferred` moves the remaining allowance into the member wallet.
- `outcome: member_trial_already_claimed` retires the duplicate guest allowance without changing the member balance. The account sheet explains that the account already received its welcome allowance. Android does not treat a bare `409` as a completed transfer.

An expired guest token is renewed only after an explicit `invalid_guest_session` response during transfer. The original token is retried first so a transfer that already committed can return its stored result. Pending transfers block hosted member spending until resolved; personal-key conversation support stays separate.

The member purchase controller still reads only member credentials and validates the member profile. No guest bearer can authorize a purchase.

## Access screen and payments

Access problems open a warm sheet rather than adding scrolling status text to the conversation canvas. It offers Google sign-in, retry, and personal-key settings. Purchase entry appears only when the existing purchase capability is configured and enabled.

The fixed-minute pack implementation remains disabled. Its historical test prices and screenshots are synthetic fixtures, not approved offers. Paid pricing is being revised to actual AI cost plus processing costs and the 15% Mural fee, with estimated conversation minutes. Do not enable the existing fixed-pack catalog as the final paid product.

## Verification

Local controller tests cover guest access without a member, restart, paused grants, bearer renewal, identity mismatch, spent allowance, additive wallet transfer, uncertain response replay, member mismatch, unresolved reservations, corrupted identity, concurrent calls, expired-token recovery, and the duplicate-trial result.

HTTP tests use a local mock server. They check the anonymous grant body, strict response bounds, unavailable versus error states, member-authenticated transfer, exact outcomes, and redirect rejection. Device tests use only `chat.mural.android.uitest` to check separate encrypted storage, wrong-origin rejection, access-sheet actions, and the adult confirmation.

The current combined JVM run passes 227 tests with no failures, errors, or skips. Android lint reports zero errors. The complete isolated device suite passes all 40 tests; live public guest access and Google transfer require the matching server deployment before release verification.
