# Android candidate scopes

The current Android source uses version code **6**. The Play submission evidence belongs to **version 4**. Those versions share the package `chat.mural.android` and version name `0.1`; their configuration and test evidence are separate.

| Specification | Intended build | Release status and evidence |
| --- | --- | --- |
| [release-spec.json](release-spec.json) | Current v6 funded-preview baseline. Clean Gradle builds default purchases off, channel `play`, environment `test` | Default validation target; it must match the version in `app/build.gradle.kts` |
| [specs/direct-v6.json](specs/direct-v6.json) | Current v6 direct distribution, with `mural.minutePurchasesEnabled=true`, `mural.purchaseChannel=stripe`, `mural.minutePurchaseEnvironment=live` supplied explicitly | Separate from the submitted Play candidate. The scope label does not establish a successful purchase, signature, installation or store approval |
| [specs/direct-v5.json](specs/direct-v5.json) | Historical v5 direct Stripe distribution | Retained specification; its artifact checks do not cover the v6 recovery fix |
| [specs/play-v4.json](specs/play-v4.json) | Historical v4 funded guest/personal-key preview, purchases disabled | [Submitted to Play production review on 14 September 2026](evidence/play-submission-2026-09-14.json). Approval and publication are not established by that record |

The historical v4 bundle has SHA-256 `8e408404ac2c9cf397eeceac0e0d71b245b8d24884169df2b424729bc62a8946`. Its [packaging and test record](signed-candidate-2026-09-14-v4.md) applies to that bundle and its identified preview APK. It does not cover v5 or later source changes.

The [Play listing copy](metadata/en-US), [declarations](declarations.md) and store screenshots remain the v4 submission material. All four specs reference those shared files for structural checks. Text-length, image-format and hash checks cannot establish that the copy describes a different release's enabled features. A paid Play submission needs updated copy, declarations, screenshots where affected, and channel-specific purchase evidence.

The checker records the selected spec's filename, SHA-256 and version. It enforces the same package, version, SDK, manifest, native-layout and credential checks for an explicit historical spec as for the default. [Validation instructions](build-and-verify.md#3-validate-the-exact-bundle-and-assets) show how to select a spec without changing the current candidate version.
