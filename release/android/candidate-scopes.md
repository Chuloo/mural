# Android candidate scopes

The default Android build uses version code **15**. Version **14** is reserved for the regional Play Billing license test. The current Play production release is **version 13**. All use the package `chat.mural.android` and version name `0.1`; their configuration and test evidence are separate.

| Specification | Intended build | Release status and evidence |
| --- | --- | --- |
| [release-spec.json](release-spec.json) | Planned v15 production identity and assets. Clean Gradle builds default purchases off, channel `play`, environment `test` | Default validation target; paid release requires explicit live flags |
| [specs/play-v15.json](specs/play-v15.json) | Regional Play production candidate with live service and purchases enabled | Requires regional backend deployment and Play-installed test evidence |
| [specs/play-v14.json](specs/play-v14.json) | Regional internal test candidate using the isolated sandbox | Never promote to production |
| [specs/play-v13.json](specs/play-v13.json) | Signed v13 paid Play production candidate with purchases enabled, channel `play`, environment `live` and the production API origin | Published on 29 September after the Play license purchase test and production backend activation |
| [specs/play-v12.json](specs/play-v12.json) | Internal-only Play sandbox candidate with purchases enabled, channel `play`, environment `test` and the sandbox API origin | Built and validated from `5772ec6`, then published to internal testers on 29 September. License-test receipts belong only to the isolated test backend; never promote this bundle to production |
| [specs/play-v11.json](specs/play-v11.json) | Historical v11 Play candidate with purchases enabled and `live` environment | Signed AAB was uploaded to internal testing before the latest fixes; its evidence does not cover v12 |
| [specs/direct-v10.json](specs/direct-v10.json) | Historical v10 direct distribution, with Stripe configured explicitly | Separate candidate; it has not been published as the website's primary Android download |
| [specs/direct-v9.json](specs/direct-v9.json) | Historical v9 direct Stripe distribution | Retained for upgrade and regression checks |
| [specs/direct-v8.json](specs/direct-v8.json) | Historical v8 direct Stripe distribution | Retained for upgrade and regression checks |
| [specs/direct-v7.json](specs/direct-v7.json) | Historical v7 direct Stripe distribution | Retained for upgrade and regression checks |
| [specs/direct-v6.json](specs/direct-v6.json) | Historical v6 direct Stripe distribution | Retained for upgrade and regression checks |
| [specs/direct-v5.json](specs/direct-v5.json) | Historical v5 direct Stripe distribution | Retained specification; its artifact checks do not cover the v6 recovery fix |
| [specs/play-v4.json](specs/play-v4.json) | Historical v4 funded guest/personal-key preview, purchases disabled | Published to Play production on 26 September 2026, as verified in Play Console on 29 September |

The historical v4 bundle has SHA-256 `8e408404ac2c9cf397eeceac0e0d71b245b8d24884169df2b424729bc62a8946`. Its [packaging and test record](signed-candidate-2026-09-14-v4.md) applies to that bundle and its identified preview APK. It does not cover v5 or later source changes.

The [Play listing copy](metadata/en-US) and [eight screenshots](assets/README.md) are prepared for the paid release. Compare them with the final v15 bundle before upload. The Console listing, [declarations](declarations.md) and real purchase behavior still need verification before production. Text-length, image-format and hash checks cannot establish that the copy describes the live app correctly.

The checker records the selected spec's filename, SHA-256 and version. It enforces the same package, version, SDK, manifest, native-layout and credential checks for an explicit historical spec as for the default. [Validation instructions](build-and-verify.md#3-validate-the-exact-bundle-and-assets) show how to select a spec without changing the current candidate version.
