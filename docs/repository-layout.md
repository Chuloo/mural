# Repository layout

Mural keeps both native apps and their API in one repository. Each has its own build, dependencies and release process. The website remains in [Chuloo/mural-website](https://github.com/Chuloo/mural-website).

| Location | Responsibility |
| --- | --- |
| `apps/ios/` | Xcode project, SwiftUI app, Swift package, signing configuration and iPhone tests |
| `apps/android/` | Gradle project, Compose app, Android resources and Android tests |
| `services/api/` | Account verification, minute ledger, operator tools, PostgreSQL migrations and server deployment |
| `shared/contracts/` | Public request and response formats |
| `shared/fixtures/` | Learning archives and expected outcomes checked by both native clients |
| `scripts/` | Content generation, project generation and compatibility checks |
| `release/` | Store submission material and release requirements |

Run repository scripts from the root. Run Gradle from `apps/android/` and server commands from `services/api/`. Swift commands use `--package-path apps/ios`. Open `apps/ios/Mural.xcodeproj` in Xcode. The [build guide](build-and-test.md) gives the commands.

Language definitions currently originate in `apps/ios/Core/Languages/`. The exporter generates Android's language content from those definitions. Teaching logic runs natively in Swift and Kotlin; the compatibility checker and shared fixtures detect differences in prompts, thresholds and archive fields. They do not prove equivalent speech quality or native behavior. A future web client should consume a versioned language-content package rather than parse either app's source at runtime.

Keep microphone handling, audio focus, permissions, secure credential storage and animation native. Keep accounts and purchased time authoritative on the API. Conversations, vocabulary and learning evidence belong on the device. A balance response is not permission for a client to mint time or report its own billable usage.

Folder moves do not change bundle IDs, Android application IDs, signing keys or stored data formats. Existing installations must keep their signing identities when updated. CI builds test and debug artifacts without production keys; signed store bundles require the separate release process. [Contribution guidance](../CONTRIBUTING.md) describes checks to run before submitting a change.
