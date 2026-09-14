# Android release gates

These gates apply to the candidate being uploaded. Historical tests and debug screenshots do not certify a later bundle. Record the commit, configuration, artifact hashes, device/OS, test outcome and any remaining failure for each candidate.

## Internal guest preview

- [ ] Owner-approved package registered in Play Console; app signing configured with backed-up upload-key custody.
- [ ] Release AAB builds from a recorded commit. Its own manifest matches the package, version and SDK specification; it is neither debuggable nor test-only.
- [ ] Android's launcher image is byte-identical to the canonical iOS AppIcon, and resource references select that image. Installed launcher masks/scale and the in-app Mural mark match the iOS reference. Play icon and feature artwork derive from the same approved mark, without redrawing or recoloring it.
- [ ] Bundled native libraries pass 16 KB LOAD checks and RELRO layout review, generated split APKs pass `zipalign`, and WebRTC loads and runs in a confirmed 16 KB environment.
- [ ] Reviewer/tester access works without a personal provider key. Guest availability, trial restrictions and optional BYOK costs match the listing and consent; purchases remain unavailable until the paid release gates pass.
- [ ] Main conversations, meanings after end, 15-second reset, cancellation, offline errors, local storage, export/import and deletion pass on the candidate. Onboarding, large text, keyboard, TalkBack and reduced motion remain usable.
- [ ] All eight supported language variants pass content checks. A short live session and transcription/meaning test run for each; proficient speakers assess the quality needed for public claims.
- [ ] Security scan covers working tree and public history; account credentials and provider keys are absent from the AAB's resources and repository.
- [ ] Required third-party notices remain accessible. Microphone and backup behavior match privacy copy.
- [ ] Internal Play install uses the same uploaded bundle and succeeds on a tester account. If Google sign-in is exposed, test the Play-signed certificate, restoration, cancellation, sign-out and deletion.

An internal preview is an early test milestone. It does not complete the approved commercial launch.

## Public hosted/minute release

- [ ] All internal checks pass, with real physical microphone, speaker, wired/Bluetooth route, interruptions, backgrounding and reconnect behavior verified. The Mac emulator cannot establish these audio results.
- [ ] Guest trial is funded once per accepted installation identity, using the owner-approved capped per-install beta policy. Guest-to-account transfer preserves the same remainder. Test repeated claims, reinstalls, merged identities and budget exhaustion. Device Recall approval is not a prerequisite for this beta; do not claim that a reinstall can be reliably linked to the same physical device.
- [ ] Owner controls for allowance changes and selected/all-user grants are tested with idempotency and audit records. New-trial commitments respect the latest approved $200 per UTC day and $2,000 total funding limits, with $1.50 reserved per ten-minute grant; existing grants remain usable. Recheck the live policy before rollout because these settings can change independently of the app.
- [ ] Provider-backed voice and helper calls have authoritative cutoffs, reservation settlement and reconciliation. Crash, network-loss and uncertain-creation paths do not grant unbounded time or double-debit minutes. The approved 15-second minimum, capped by the reservation, appears before a hosted conversation; final charges follow the server’s session policy. Test short calls, residual balances, legacy sessions and repeated restarts against the aggregate voice/helper allowance.
- [ ] Prepaid AI-value top-ups use actual provider usage plus the owner-approved 15% Mural fee and separately quoted processing costs/buffer. Paid minutes are estimates. Final channel prices, taxes, immutable quote snapshots and exact voice/helper settlement are verified. Play and Stripe sandbox purchase/refund/replay/pending/cancel/recovery cases pass. Authorized controlled live payment/refund evidence is recorded before broad activation.
- [ ] Account management covers expired/revoked sessions, local-data behavior, account recovery and deletion with a remaining balance or unresolved payment. Apple-only account access has an explicit supported path or a disclosed owner-approved limitation.
- [ ] In-app AI-output reporting works; users review an excerpt and consent before sending. Capability and retry states, trusted-proxy admission, restricted runtime grants, 30-day expiry/cleanup, backup handling and reviewer access pass in the deployed environment. Privacy and Data safety declarations include this optional content. A support owner reviews reports and acts on relevant findings.
- [ ] Encrypted off-server backups and a timed restore drill pass. Alerts cover API failures, payment reconciliation, budget exhaustion and capacity without logging conversation content.
- [ ] Final store copy, six screenshots, icon and feature graphic match the candidate. Data safety, audience/rating, ads, permissions, reviewer access, privacy, deletion URL and region declarations are complete.
- [ ] Closed beta and Play pre-launch report issues are resolved. First production rollout scope is explicitly selected and public install, login, trial and purchases are rechecked after publication.
- [ ] Website receives the real Google Play link only after that listing and install are public. iOS access remains accurately labeled.

## Decisions and private setup still needed

| Item | Minimum decision or evidence |
| --- | --- |
| Upload signing | Owner-approved custody and recovery location; credentials supplied through protected local/CI configuration |
| Review access | A restricted, funded access path; no shared provider secret in source or store metadata |
| Launch audience / countries | Adults 18+ approved; initial markets still need selection and verified billing coverage |
| Commerce | Actual-cost top-ups with a configurable 15% fee; verified channel fees, estimated-minute display and a controlled live purchase/refund test |
| Trial eligibility | Test the approved capped per-install policy and guest-to-member merge; document reinstall limitations. Device Recall is a later strengthening option |
| Physical testing | Android phone or authorized external tester for speaker, microphone and Bluetooth evidence |
| Apple accounts | Available Apple configuration and the intended cross-platform account behavior |
| Support operations | Named person/team responsible for reports, closeouts, security alerts and privacy requests |

The build targets API 36 and declares Android 8 (API 26) as its minimum. Verify current Play requirements at submission and retain that minimum only after the oldest supported runtime is tested. [Target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)

On 14 September, the release operator verified the owner's requested Documents backup of the upload key, password and certificate with restricted file permissions. This satisfies the selected backup arrangement. It is a second copy on the same Mac; an off-device recovery copy is still advisable, but is not an additional release approval requirement. Play enrollment and final bundle signing remain separate checks.

## Paid account deletion follow-up

Before enabling sales, resolve deletion after an abandoned Play checkout. Creating an order before opening or cancelling the billing sheet can leave no purchase token for reconciliation. The current account-deletion check treats this as unresolved billing and blocks deletion. A production fix must allow account deletion while retaining only the billing records needed to handle a late provider result; it must not infer that missing client confirmation means no charge occurred.
