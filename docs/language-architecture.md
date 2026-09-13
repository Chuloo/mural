# How Mural keeps languages independent

A learner can be comfortable in Norwegian and new to Spanish. Mural therefore gives each conversation an immutable language ID and projects vocabulary, challenge level and capability observations from that language's evidence only. Identical word forms have different vocabulary keys across languages, so hiding or recalling a word in one language does not affect another.

Language-specific content lives in `Core/Languages/`. Each module defines its greeting, regional speech guidance, writing conventions, lemma rules, six teaching stages and cultural theme overrides. `LanguageRegistry` supplies the available choices to the UI.

| Storage ID | Learning target | Locale |
| --- | --- | --- |
| `nb` | Norwegian Bokmål, Eastern Norwegian speech | `nb-NO` |
| `es` | Spanish from Spain | `es-ES` |
| `en` | International English | `en-US` |
| `fr` | French from France | `fr-FR` |
| `de` | Standard German from Germany | `de-DE` |
| `it` | Italian from Italy | `it-IT` |
| `pt` | Brazilian Portuguese | `pt-BR` |
| `zh` | Standard Mandarin, Simplified Chinese | `zh-CN` |

These locales describe the initial teaching targets. Modules accept valid regional usage from learners. Regional pronunciation is a model instruction and still needs listening checks. Portuguese's stable `pt` storage ID currently belongs to the Brazilian module; a future independently selectable variety must not silently reinterpret existing progress.

`TeachingPolicy` combines a module with the shared teaching rules. Voice, assessment, typed replies, help, word lookup, subtitles and current-topic search all use that policy. The audio transport and provider connection remain shared. A module can override selected theme IDs while inheriting the common conversation catalog.

Switching is allowed between conversations. It clears the current screen context and invalidates pending language-dependent work. Previous messages and sourced topic briefs are selected only from the active language. Learner replies can use a support language; the meaning-subtitle language is a separate preference. Vocabulary senses remain in English to keep glossary identities stable.

Archive version 2 stores language IDs explicitly. Version 1 records migrate to Norwegian, and their hidden-word keys gain the same namespace as new evidence. The SwiftData record itself retains its original identity. Before persisting that migration, the app saves a protected copy of the original payload in its Application Support/Mural directory. The API key stays in Keychain. Backups with unknown language IDs or mixed-language topic attachments are rejected without replacing existing data.

Mandarin builds on [richardguerre's contribution in #4](https://github.com/Chuloo/mural/pull/4). Its module and pinyin approach were adapted after review. `MandarinPinyin` uses the system word tokenizer's Latin transcription, which distinguishes common readings such as 银行 (yínháng) and 旅行 (lǚxíng). It normalizes the dictionary's `v` notation to `ü` and preserves the source text, including punctuation, whitespace and mixed scripts. Unrecognized readings receive no annotation. This is a dictionary reading aid, not a pronunciation assessment or a complete treatment of tone sandhi.

Pinyin appears separately below selectable Chinese text, with a Show/Hide control. Word links use Chinese word boundaries. Lemmas stay in characters, observed forms and quotations stay unchanged, and generated pinyin never becomes learning evidence. Script identifiers such as `zh-Hans` and `zh-Hant` are accepted by the spoken-language check, so Chinese text does not trigger a false language redirect. Simplified Chinese is also available for meaning subtitles.

These are compiled modules. Adding one ships with an app update; there is no remote module download or extra service. Every new language needs a proficient-speaker teaching and pronunciation review. The Android client under `android/` reads the same modules through the generated `Languages.kt`; regenerate it with `python3 scripts/export_android_content.py` whenever a module changes.

See [how to add a language](add-language.md) for the implementation steps.

## Two native cores, one contract

The Android client is a separate Kotlin/Compose app, not a shared build. `scripts/export_android_content.py` generates Android's language content (`Languages.kt`) from the Swift modules under `Core/Languages/`, so a module registered in `LanguageRegistry.all` reaches both platforms without being written twice.

Everything else in the learning core is ported by hand, so `scripts/check_cross_platform.py` checks that the two ports stay in agreement: the teaching prompts sent to the model, a fixed table of shared numeric constants (recall spacing, evidence thresholds, session limits), and the required fields of the JSON backup archive. Golden fixtures under `Tests/Fixtures/cross-platform/` are read by both `swift test` and the Android unit tests, so a behavior change can be verified identically on both cores.

An export is semantically, not byte-for-byte, compatible with what it describes: Swift's `JSONEncoder` sorts keys when writing an archive, while Kotlin does not attempt to reproduce that ordering. Backups exchanged between platforms are compared by decoding and re-validating, never by comparing raw bytes.
