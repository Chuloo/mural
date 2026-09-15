# Android version 6 preparation

Version 6 is being prepared to fix Google sign-in when guest conversation usage is still being settled. The change lets the Google flow open and durably links the earlier guest usage to the member account. Available gift or purchased balance can then be used while that usage is reconciled, without granting a second welcome allowance. Building and runtime verification remain pending.

The package remains `chat.mural.android`, version name `0.1`, minimum API 26 and target API 36. The default spec uses version code 6 with purchases disabled by default. [specs/direct-v6.json](specs/direct-v6.json) describes the explicitly configured direct Stripe build. The [v5 direct spec](specs/direct-v5.json) and [v4 Play submission](evidence/play-submission-2026-09-14.json) remain historical records.

## Required before building

- Finish and review the native sign-in recovery change and any required backend change.
- Deploy the corresponding backend with migration 023 before distributing v6. The durable guest-to-member binding is part of this fix; the native change alone is insufficient.
- Verify that unresolved guest usage does not prevent Google sign-in from opening, and that pending usage can still be reconciled without duplicate charges or lost guest state.
- Test cancellation, failed sign-in and a retry as well as a successful account exchange.
- Run the affected native and backend tests, then record the final candidate commit and the public build configuration.

## Build and upgrade requirements

The direct build requires `mural.minutePurchasesEnabled=true`, `mural.purchaseChannel=stripe` and `mural.minutePurchaseEnvironment=live`, with the intended API origin and Google client ID supplied through local configuration. A clean build's defaults do not enable this channel. Use the [build and verification procedure](build-and-verify.md) after the code is complete.

Sign the v6 APK with the same review certificate as the installed v5 direct APK. Use protected password files or a private signing prompt; keep passwords out of arguments and logs. Verify the certificate match, APK v2/v3 signatures, version code 6, release manifest flags, 16 KB ZIP alignment and the embedded-credential scan before distributing it.

Install v6 as an update over v5 and verify that learning history, settings and account/guest state remain available. Do not uninstall v5 or clear app data to work around a signature or recovery problem. Retain the v5 APK and its evidence; v6 needs a separate artifact, hash and verification record. A successful local upgrade does not establish Play approval or a Play-signed installation.

No v6 APK build, signing, installation or runtime result is established by this preparation document.

## Draft release notes

Fixes an issue that could stop Google sign-in after a guest conversation. Earlier guest usage stays linked to your account, so you can use an available gift or purchased balance while that usage is processed. Signing in keeps the existing one-time welcome allowance rule.

Publish these notes only after the backend prerequisite and the v6 verification above pass.
