# Learning extension

This document describes the optional learning features added to Mural for learners who need explanations in a familiar language before practising in the target language.

## What was added

- **Interface languages:** Simplified Chinese and Vietnamese are available alongside the existing interface language. Language selection is persisted locally and is separate from the language being learned.
- **Native-language teaching context:** the learner chooses a target language and a meaning or explanation language. The realtime teacher uses the selected explanation language for instructions, corrections, pronunciation notes, and grammar explanations. Free conversation remains open to any language the learner explicitly requests.
- **Ogden Basic:** a native Swift classroom built around 850 English words from the pinned [longlong-skyligo/Ogden revision](https://github.com/longlong-skyligo/Ogden/tree/9d924597f047342b7cd78ba7ac928d940d3190ed). It keeps local progress, favourites, levels, US/UK spelling, IPA, bundled word audio, and bounded practice types (listening, meaning, cloze, spelling, and reviewed synonym pairs).
- **Article reading:** a reading entry accepts PDF, URL, TXT, Markdown, RTF, HTML, DOCX, and EPUB. Imported text is normalised locally and passed to the reading teacher. The intended flow is: read first, then let the teacher ask questions to check understanding.
- **Visual personalisation:** 22 added orb concepts join the four existing choices, for 26 skins. The avatar selector offers the animated orb or a user-selected photo. Motion is implemented by the native renderer and can respond to conversation activity; still artwork is only the visual source. Legacy avatar values remain readable, but this contribution does not add a cartoon or animal picker.
- **Optional subscription connection:** the app can use a separately deployed subscription bridge to connect the user's existing subscription session to the realtime experience. This is an experimental integration point and is deliberately kept separate from the core app.

## Why these changes

Mural's original voice flow is a useful base for conversation, but an early learner can be excluded when the teacher explains only in the target language. The extension makes the explanation language explicit, adds a gradual vocabulary route, and lets a learner bring a complete article into the same reading conversation. Local progress and local import handling keep the everyday learning state on the device while preserving the original realtime voice path.

## Scope and current verification

The implementation is native Swift and reuses Mural's existing realtime transport, account/session handling, voice selection, and local conversation records. The public change should be reviewed as an optional extension rather than as a replacement for the original conversation flow.

The public candidate passes 130 Swift core tests, 23 offline bridge tests, 49 project-script tests, the generated-content and cross-platform checks, nine article URL validation cases, and an arm64 iOS Simulator build. A previous personal development build was installed and launched on an iPhone; it is not the public candidate. Every skin's audio-reactive motion, reduced-motion/background behaviour, complete persistence coverage, realtime subtitle latency, and the full classroom flow across all supported import formats still require verification on the exact target device and build. These checks do not establish pronunciation or teaching quality.

Android preserves the optional classroom and appearance fields when importing and exporting supported-language archives, and its teaching prompts accept the matching explanation-language context. A shared synthetic archive exercises those fields on both platforms. The new classroom and appearance UI is iOS-only. Custom target-language archives remain unsupported on Android. Android Gradle tests and a native build were not run for this contribution because the validation host has no JDK; source parity checks do not substitute for those checks.

The subscription bridge is not an iPhone OAuth implementation. It is an opt-in adapter for a user-controlled deployment; private hostnames, ports, launch agents, pairing records, tokens, and local configuration must remain outside this repository.
