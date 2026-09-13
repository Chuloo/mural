# Tagalog (Filipino) — Philippines implementation record

This completes the repository plan prepared on 13 September 2026 against `4aef373d85d5d64aee5d6adbf43092dae83c1f55`. Implementation and automated verification are the delivery scope. Real-device conversation and proficient-speaker review remain explicitly pending.

## Feature contract

| Field | Value |
| --- | --- |
| Module | `LanguageModule.tagalog` in `Core/Languages/Tagalog.swift` |
| Stable learning/storage ID | `tl` |
| Display name and native name | `Tagalog (Filipino)` |
| Variety / picker label | `Philippines` / `Tagalog (Filipino) · Philippines` |
| Locale | `tl-PH` |
| Greeting / lookup word | `Kumusta!` / `kumusta` |
| Writing system | Contemporary Latin-script Tagalog |

Filipino is a learner-facing alias, with a single Tagalog progress namespace. The target uses the same onboarding, Settings, voice, typed reply, help, meaning, lookup, theme, topic, vocabulary and backup paths as existing languages. It does not add a second Filipino module or new subtitle/interface localization. The original eight IDs and Norwegian default stay unchanged.

## Implementation map

| Planned work | Implementation |
| --- | --- |
| Complete compiled language module | `Core/Languages/Tagalog.swift`: regional speech, writing, lemma rules, six challenge stages, topic placeholder and target-language lookup fallback. |
| Register the ninth target | `Core/Languages/LanguageModule.swift`: append `.tagalog`; both pickers derive their options and labels automatically. |
| Philippine themes | Override the existing `coffee`, `groceries`, `travel`, `cabin` and `traditions` IDs; inherit the other 19 themes. |
| All prompt paths | Existing `TeachingPolicy` functions interpolate the module, including its display alias; language-specific guidance keeps the target explicit. |
| Prevent false speech redirects | `TeachingPolicy.supportsSpeechLanguageDetection` declines automatic detection for `tl`; the coordinator avoids that unreliable check. Other targets retain their previous behavior. |
| Honest live-check diagnostics | `App/LanguageVerification.swift`: Tagalog reply/lookup fixtures, actual detector label/confidence, separate mechanical `flowPassed`, detection-dependent `passed` and pending language-quality review. |
| Native previews | `ConversationCoordinator.prepareConversationPreview`: matched Tagalog caption/meaning; Debug active-state fixture for the language-switch lock. |
| Experimental hosted locale | `server/src/live-provider.ts`: `tl-PH` regional target; negative aliases remain unsupported before any reservation/provider call. |
| Core regressions | `Tests/TagalogTests.swift`, expanded catalog fixtures, cross-language meaning and final-assessment cases. |
| Native regressions | `UITests/MuralUITests.swift`: onboarding, largest accessibility text, subtitle choice, Settings/themes/Words, transcript retention, switching lock and normal relaunch persistence. |
| Hosted regressions | Locale/provider request test plus a PostgreSQL-backed Tagalog reservation, settlement and invalid-alias test. |
| Documentation | README, architecture, add-language/build guides, server contract, store metadata, [Tagalog references](docs/tagalog.md) and [verification record](verification/validation.md). |

## Research and teaching decisions

[Tagalog teaching choices and references](docs/tagalog.md) records the sources, including Tagalog.com dictionary entries and the University of Hawai‘i's aspect/focus grammar notes. The code contains original guidance, not a scraped dictionary or lesson corpus.

- Use everyday Tagalog and context-appropriate polite address; accept valid regional usage and learner support languages.
- Teach aspect in context, with voice/focus and participant markers, rather than mechanically copying English tense categories.
- Group aspect variants under a consistent citation form while retaining meaningful affixes and distinct senses. For example, `kumain` and `kainin` stay separate.
- Preserve source spelling, diacritics, apostrophes, hyphens and exact quotations. Generated pronunciation annotations do not become learning evidence.
- Keep Philippine settings specific without treating all communities as culturally or linguistically identical.

## Detector finding and chosen tradeoff

The local `NLLanguageRecognizer` probe (Xcode 26.6 / Swift 6.3.3) returned the following results on both macOS and an iOS 26.5 simulator:

| Input | Leading result |
| --- | --- |
| `Kumusta! Gusto kong matutong magsalita ng Tagalog. Maaari ba tayong mag-usap tungkol sa pagkain at paglalakbay sa Pilipinas?` | Indonesian (`id`), 0.99247 confidence |
| `Kung magbubukas ka ng kapihan, paano mo mapapanatiling abot-kaya ang mga presyo habang gumagamit ng mga lokal na sangkap?` | Indonesian (`id`), 0.99974 confidence |
| `Gusto ko ng kape.` | Croatian (`hr`), 0.38728 confidence |

The long samples exceed the coordinator's 70-character threshold and the policy's 0.88-confidence threshold. A catalog-only addition would redirect valid speech. The fix disables this detector-based guard for Tagalog while retaining explicit target-language prompts. It does not alias Indonesian to Tagalog or force a positive detector result. Actual English drift also cannot be corrected through this detector, so live review must check for it.

The verification helper records this limit: `flowPassed` can succeed independently, while `passed` requires reliable target-language detection. Neither outcome certifies pronunciation, recognition of a human learner, correction accuracy or teaching effectiveness.

## Test coverage and acceptance

- [x] One stable ID, correct locale and display alias; original language identities retained.
- [x] Every prompt path, all six stages and stable cultural-theme IDs covered.
- [x] Unaided recall, typed/subtitle-assisted practice, imitation, foreign/mixed/alias proposals and invalid provenance covered.
- [x] Aspect-family storage, separate focus/sense keys, hidden cognates, punctuation and exact archive text covered.
- [x] Archive v2 selection/topics/import and rejection of unsupported IDs tested; existing v1 migration retained.
- [x] Late meaning results/errors and final assessments cannot cross into another target.
- [x] Detector regressions cover high-confidence wrong labels, unsupported observations and unchanged behavior for previous targets.
- [x] Native onboarding, Settings, theme and vocabulary labels, large text, transcript retention, active switching lock and normal relaunch tests implemented.
- [x] Server accepts `tl-PH`, rejects unsupported aliases without credit reservation, and settles the fake-provider session correctly.

See [the verification record](verification/validation.md) for executed checks, totals and build results. Simulator previews and the test database contain temporary synthetic data; they make no real provider calls.

## Compatibility and limits

No archive-version change, SwiftData migration, new package dependency or project regeneration is required. Older app versions without the module reject `tl` archives; upgrade before importing. Retain `tl` in future releases so saved records remain readable. The server addition does not enable hosted accounts/billing or require server deployment for the current native BYOK path.

`LearningEngine.validate` checks proposal labels and transcript provenance, not the actual linguistic identity of the text. Tests using supplied lemmas validate storage and evidence behavior; they cannot establish that a model always produces correct lemmas or assessments. No inference of independent learning quality is made from synthetic typed fixtures.

Remaining release review:

- Real iPhone voice conversation with the saved API key, speaker/headphones, interruption, mute and closure.
- Human speech recognition, generated translations, sourced-topic behavior and correction/lemma quality in actual provider output.
- Proficient-speaker review of pronunciation, register, naturalness and cultural examples.

Run the Debug helper with `--verify-audio --verify-language-flow --verify-language=tl` when real-device testing is available. It uses temporary learning records and incurs API usage. The full manual checklist is in [the build guide](docs/build-and-test.md) and [verification record](verification/validation.md).
