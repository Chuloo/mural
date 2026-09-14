# Initial source release audit

Review date: 12 September 2026. Target repository: [Chuloo/mural](https://github.com/Chuloo/mural). License: [MIT](../LICENSE).

The later [security audit](security-audit-2026-09-12.md) covers public Git history, deployed accounts and the resulting security fixes. The findings below describe the initial publication checkpoint.

## Findings

A pattern scan covered 71 non-build text files available at the time of review. Before the owner added a public support address, it found no credential-shaped secrets, non-example email addresses, local home-directory paths, hardware UDIDs or literal development-team IDs in the proposed public files. One earlier email match was a reserved example-domain fixture. These checks do not prove the absence of every possible secret; rerun against the exact staged publication set after parallel changes finish.

The final publication scan covered 125 staged files, including 113 UTF-8 text files. It found no private-key or provider-secret patterns, personal home paths, physical-device IDs or signing-team literals. Private deployment keys, local signing configuration, raw diagnostics, duplicate screenshot originals and the private website deployment manifest are excluded. The account and billing implementations remain explicitly gated pending their deployment and integration checks.

The local Apple signing team was moved from the generated project to ignored `Config/Local.xcconfig`. The checked-in `Config/Signing.xcconfig` includes it optionally. The generator preserves future Xcode-selected team changes in that local file; a fresh clone remains buildable in the simulator without it.

Three raw verification JSON files contain local test timestamps and device/audio diagnostics. They are not needed to build the app. Generated build trees include binaries, caches, logs, provisioning and machine-specific material and must stay out of the public repository. This audit did not inspect the owner’s Keychain or retrieve their API key.

The simulator build passed after the storage-cleanup and privacy-resource changes. Both the first-party privacy manifest and WebRTC’s manifest were present in the built app. A combined unsigned iOS Release archive also passed and contains both manifests and debug symbols. Distribution signing and Apple upload validation remain pending active membership. Language/onboarding test results are recorded separately.

## Initial publication scope

Publish from the directory containing `Package.swift`, keeping the following relative structure:

| Include | Notes |
| --- | --- |
| `App/`, `apps/ios/Core/`, `apps/ios/Tests/`, `apps/ios/UITests/` | Source, fixtures, app assets, privacy manifest and third-party notices |
| `Package.swift` | Pure Swift core package |
| `Mural.xcodeproj/project.pbxproj` | Generated project without personal signing values |
| `Mural.xcodeproj/xcshareddata/xcschemes/` | Shared scheme |
| `Mural.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` | Pinned dependency resolution |
| `Config/Signing.xcconfig`, `Config/Local.example.xcconfig` | Public signing configuration and example only |
| `scripts/` | Repeatable generators |
| `README.md`, `LICENSE`, `CONTRIBUTING.md`, `SECURITY.md`, `.gitignore` | Public project information |
| `docs/`, `release/` | Technical guides and clearly labeled submission drafts |
| `verification/validation.md` | Reviewed summary of completed tests and remaining limits |
| `marketing/screenshots/iphone-17-spanish/` | Four reviewed simulator PNGs with synthetic Spanish learning content and their README |
| `server/` | Tested account/billing foundation and deployment runbook; public funding remains disabled |
| `.github/workflows/` | Core and server checks on standard GitHub-hosted runners |

Exclude `.build/`, `DerivedData/`, `.swiftpm/` workspace state, Xcode user data, all local credentials and signing profiles, `Config/Local.xcconfig`, raw verification JSON/logs, compiled archives, exports of learning data, the duplicate marketing ZIP, and editor/system files. Keep the historical `plan/` drafts local for the first release; they describe earlier personal-build assumptions.

Website source and assets are published in the separate [Chuloo/mural-website repository](https://github.com/Chuloo/mural-website) and deployed at [mural.chat](https://mural.chat). They are outside the app repository’s publication scope.

## Publication checks

1. Stage only the reviewed scope. Inspect the staged file list and diff, not merely `.gitignore`.
2. Check that no secret, `.env`, `.p8`, `.p12`, provisioning profile, private signing configuration, learning export or generated build product is staged. Do not paste matched values into an issue or log.
3. Confirm the MIT license and upstream notices are included. Keep the license headers supplied by dependencies.
4. Enable GitHub private vulnerability reporting. Use the confirmed fallback security/support contact hi@hackmamba.io.
5. Confirm README feature claims against the final source and test results. Add the TestFlight CTA only after a real link is available.
6. Publish without rewriting unrelated repository history. Record the resulting commit and public URL outside this draft.

## Ignore coverage

`Mural/.gitignore` covers build output, local signing, credential file formats, provisioning profiles and private release material. The workspace-level ignore file covers the existing verification images and logs. Before publishing this directory as its own repository, retain the child ignore rules and explicitly exclude the historical/raw files listed above; ignore rules alone are not an allowlist.
