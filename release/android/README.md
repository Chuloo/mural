# Android release package

The default release specification tracks **version 6**, matching the Android build. Clean builds keep paid purchases disabled. The separate direct-distribution configuration enables Stripe purchases; it requires its own configuration and release checks.

The archived **version 4 guest preview for adults 18+** was submitted to Play production review on 14 September 2026, with paid checkout disabled. That [submission record](evidence/play-submission-2026-09-14.json) does not establish approval or publication. The retained Play listing copy, declarations and v4 test results describe that submitted preview. See [candidate scopes](candidate-scopes.md) before validating or distributing a build.

| File | Purpose |
| --- | --- |
| [candidate-scopes.md](candidate-scopes.md) | Current v6 configurations, historical v5 direct distribution and the v4 Play submission |
| [signed-candidate-2026-09-14-v4.md](signed-candidate-2026-09-14-v4.md) | Historical version 4 free-trial/BYOK APK/AAB, full UI results and packaging checks |
| [signed-candidate-2026-09-14-v3.md](signed-candidate-2026-09-14-v3.md) | Historical version 3 APK/AAB, certificate and packaging checks |
| [signed-candidate-2026-09-14-v2.md](signed-candidate-2026-09-14-v2.md) | Historical version 2 APK/AAB and packaging evidence |
| [signed-candidate-2026-09-14.md](signed-candidate-2026-09-14.md) | Historical version 1 signed bundle and verification scope |
| [preview-readiness-2026-09-13.md](preview-readiness-2026-09-13.md) | Earlier debug APK and its verification scope |
| [candidate-audit-8768c86-2026-09-13.md](candidate-audit-8768c86-2026-09-13.md) | Historical unsigned candidate after the account lifecycle fixes; rebuild after later native changes |
| [candidate-audit-2026-09-13.md](candidate-audit-2026-09-13.md) | Historical candidate before the account lifecycle fixes |
| [release-spec.json](release-spec.json) | Default current v6 identity and funded-preview scope; purchases disabled by default |
| [specs/direct-v6.json](specs/direct-v6.json) | Current v6 scope for configured direct Stripe distribution |
| [direct-v6-preparation.md](direct-v6-preparation.md) | Version 6 recovery scope and checks required before building and distribution |
| [specs/direct-v5.json](specs/direct-v5.json) | Historical v5 direct Stripe specification |
| [specs/play-v4.json](specs/play-v4.json) | Explicit historical v4 identity for rechecking the submitted Play bundle |
| [metadata/en-US](metadata/en-US) | Retained v4 Play listing text and preview release notes; revise before a paid Play submission |
| [declarations.md](declarations.md) | Data flows, permissions, Console declarations and unresolved answers |
| [build-and-verify.md](build-and-verify.md) | Build and evidence procedure for an approved candidate |
| [release-gates.md](release-gates.md) | Minimum internal-preview and public-release acceptance checks |
| [paid-listing-copy.md](paid-listing-copy.md) | Approved pricing wording to use only after actual-cost purchases pass release checks |

The owner-approved permanent package is `chat.mural.android`. Play registration and signing are separate release steps. `versionCode` starts at 1; each later upload must use a higher value. A debug installation is not a Play-signed release.

## Assets

The 512-pixel Play icon, native feature graphic and six 1080 × 1920 screenshots are prepared. [Asset provenance and refresh instructions](assets/README.md) identify the inspected capture build and synthetic learning fixtures. The screenshots show English controls, Spanish from Spain and English meanings. Compare them with the final uploaded candidate after source changes. The icon's encoding was normalized to opaque RGBA without changing any RGB pixel, and its sRGB setting matches the canonical iOS source.

The six screens cover greeting, a Spanish conversation with meanings, themes, vocabulary, language selection and settings. Payment offers and personal account details are absent. Use the isolated capture workflow to refresh them. The older [design captures](../../verification/android-design/README.md) remain review evidence; their 1080 × 2424 dimensions exceed Play's maximum screenshot ratio.

The store icon must be a 512 × 512, 32-bit sRGB PNG, at most 1024 KB. [Google’s icon specification](https://developer.android.com/distribute/google-play/resources/icon-design-specifications) The feature graphic must be a 1024 × 500, 24-bit PNG without alpha. This repository's validator also requires opaque 24-bit PNG screenshots, between 320 and 3840 pixels on each side, with the longer side no more than twice the shorter. Play accepts JPEG screenshots too; this workflow uses PNG to make review consistent. Keep the Mural artwork intact, with no price, rating or award claims. [Google's asset specification](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en-GB)

The iOS `AppIcon.appiconset/MuralIcon.png` is the canonical launcher artwork. Android must use the identical image bytes. The validator checks both images and follows the iOS catalog, Android manifest and adaptive foreground references so a resource change cannot silently select different artwork. It records the current iOS and Android design-source hashes for visual review. The current Android adaptive foreground has a 10% inset; identical source bytes alone do not prove the same visible scale after a launcher's mask is applied.

Derive the 512-pixel Play icon from the canonical image without redrawing, recoloring or substituting another orb. Use the same approved Mural mark in the feature graphic. Compare the installed launcher, in-app wordmark and final listing artwork with iOS before upload. Android's current wordmark uses Nunito; iOS uses its system rounded font, so their letterforms still need an explicit parity review. Store format checks do not establish brand parity.

The copy checker enforces Play's 30-character name, 80-character short description and 4,000-character full description limits. Release notes use a conservative 500-character limit. It also rejects unfinished placeholders. A passing check does not establish that the wording matches an untested feature. [Store metadata reference](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)
