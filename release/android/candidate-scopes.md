# Android candidate scopes

The current Android source uses version code **11**. The existing Play production release is **version 4**. Both use the package `chat.mural.android` and version name `0.1`; their configuration and test evidence are separate.

| Specification | Intended build | Release status and evidence |
| --- | --- | --- |
| [release-spec.json](release-spec.json) | Current v11 identity and assets. Clean Gradle builds default purchases off, channel `play`, environment `test` | Default validation target; it must match the version in `app/build.gradle.kts` |
| [specs/play-v11.json](specs/play-v11.json) | Current v11 Play candidate, built with purchases enabled and `live` environment explicitly | Signed AAB passed local tests and packaging checks; Play-signed install and live purchase still require testing |
| [specs/direct-v10.json](specs/direct-v10.json) | Historical v10 direct distribution, with Stripe configured explicitly | Separate candidate; it has not been published as the website's primary Android download |
| [specs/direct-v9.json](specs/direct-v9.json) | Historical v9 direct Stripe distribution | Retained for upgrade and regression checks |
| [specs/direct-v8.json](specs/direct-v8.json) | Historical v8 direct Stripe distribution | Retained for upgrade and regression checks |
| [specs/direct-v7.json](specs/direct-v7.json) | Historical v7 direct Stripe distribution | Retained for upgrade and regression checks |
| [specs/direct-v6.json](specs/direct-v6.json) | Historical v6 direct Stripe distribution | Retained for upgrade and regression checks |
| [specs/direct-v5.json](specs/direct-v5.json) | Historical v5 direct Stripe distribution | Retained specification; its artifact checks do not cover the v6 recovery fix |
| [specs/play-v4.json](specs/play-v4.json) | Historical v4 funded guest/personal-key preview, purchases disabled | Published to Play production on 26 September 2026, as verified in Play Console on 29 September |

The historical v4 bundle has SHA-256 `8e408404ac2c9cf397eeceac0e0d71b245b8d24884169df2b424729bc62a8946`. Its [packaging and test record](signed-candidate-2026-09-14-v4.md) applies to that bundle and its identified preview APK. It does not cover v5 or later source changes.

The [Play listing copy](metadata/en-US) and [eight screenshots](assets/README.md) have been refreshed for v11. The Console listing, [declarations](declarations.md) and real purchase behavior still need to be verified before the paid production release. Text-length, image-format and hash checks cannot establish that the copy describes the live app correctly.

The checker records the selected spec's filename, SHA-256 and version. It enforces the same package, version, SDK, manifest, native-layout and credential checks for an explicit historical spec as for the default. [Validation instructions](build-and-verify.md#3-validate-the-exact-bundle-and-assets) show how to select a spec without changing the current candidate version.
