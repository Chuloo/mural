# Contributing to Mural

Mural helps people practise a language through conversation. Changes should preserve the simple voice experience, keep learning records on the device, and make teaching claims no stronger than the evidence supports.

For a bug, describe the expected result, what happened, and how to reproduce it. Include the app version, iOS version, learning language and audio route when relevant. Remove personal conversation content from logs and screenshots. Never post an API key, authentication token or learning export in an issue.

Before a substantial feature, open an issue describing the problem and proposed behavior. Small fixes can go straight to a pull request.

1. Follow [the iPhone setup guide](docs/run-on-iphone.md) or use a simulator.
2. Make a focused change. Use [the language-module guide](docs/add-language.md) for new languages.
3. Run `swift test` and the relevant native checks in [the build guide](docs/build-and-test.md). Changes to voice behavior need a real-device check; report when that check was unavailable.
4. If your change touches `Core/`, make the matching change under `android/app/src/main/java/chat/mural/core/` and run `python3 scripts/check_cross_platform.py`. State in your pull request if you could not build or run the Android app to verify it.
5. Describe the user-visible change, how you verified it, and any remaining limitations in your pull request.

Keep credentials and machine-specific signing settings out of commits. Update the project generator when adding app resources or project settings. Preserve third-party notices when modifying dependencies.

Report security vulnerabilities through the [private process](SECURITY.md). By contributing code you have the right to share, you agree to make it available under the project’s [MIT License](LICENSE).
