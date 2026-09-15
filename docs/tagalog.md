# Tagalog (Filipino) from the Philippines

Mural's Tagalog option uses the stable language ID `tl` and locale `tl-PH`. Onboarding and Settings show **Tagalog (Filipino) · Philippines** so learners recognize either name. Filipino is a display alias for this learning option, not a second module or progress record. The [IANA registry](https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry) registers Tagalog (`tl`) and Filipino (`fil`) separately; [ISO 639-1](https://www.loc.gov/standards/iso639-2/php/langcodes_name.php?code_ID=444) assigns `tl` to Tagalog.

## Teaching choices

The module teaches everyday Tagalog through conversation, using contemporary Latin-script writing. It accepts support-language replies and established loanwords, bridges back to Tagalog, and uses polite address when the situation calls for it. Other Philippine languages are not treated as incorrect Tagalog dialects. The six challenge stages move from short exchanges through connected stories, aspect and voice/focus, reasons, register and nuanced discussion. They are provisional teaching guidance, not proficiency certification.

Vocabulary uses dictionary citation forms with meaningful affixes retained. Aspect variants such as `kumakain` and `kakain` can share `kumain`, while the object-focus family retains `kainin`. The module asks for consistent ordinary citation spelling and stable English senses; the learner's observed form and quotation stay intact. Storage tests verify the handling of these proposals, not the accuracy of a model's linguistic judgment.

Meaning subtitles keep their existing independent choices. Adding this learning target does not add Filipino subtitles or translate the app interface. The five cultural overrides reuse the shared café, market, transport, weekend and customs theme IDs; the other themes remain available.

## Research references

These references informed original prompt guidance and test fixtures. No dictionary dataset, lesson text, audio or external runtime dependency is bundled.

| Reference | How it informs the module |
| --- | --- |
| [Tagalog.com: kumusta](https://www.tagalog.com/dictionary/kumusta) | Greeting use and respectful address. The module uses ordinary `Kumusta!` spelling. |
| [Tagalog.com: kumain](https://www.tagalog.com/dictionary/kumain) | Actor-focus citation form and its aspect family. |
| [Tagalog.com: kainin](https://www.tagalog.com/dictionary/kainin) | Object-focus citation form; preserve its distinction from `kumain`. |
| [University of Hawai‘i: aspect](https://www.hawaii.edu/filipino/Grammar_Topics/Grammar_2-1.html) | Teach completion/progression and neutral forms in context rather than substituting English tense labels mechanically. |
| [University of Hawai‘i: focus](https://www.hawaii.edu/filipino/Grammar_Topics/Grammar_2-2.html) | Verb affixes indicate participant roles; do not strip every form down to a shared root. |

Tagalog.com was reviewed in a normal browser after automated page retrieval returned HTTP 403. Dictionary conjugation tables and editorial entries informed the implementation; community-submitted sentences were not treated as reviewed teaching material. These sources support the design choices, not a claim that the complete generated conversation has been assessed by a teacher.

## Speech-language detection

Apple's `NLLanguageRecognizer` can confidently misclassify Tagalog. On macOS with Xcode 26.6 / Swift 6.3.3 and on the iOS 26.5 simulator, two long Tagalog passages produced Indonesian (`id`) hypotheses at 0.99247 and 0.99974 confidence. Both would have triggered the existing language-redirect guard. The reproduction text and baseline are in [the implementation record](../TAGALOG-PLAN.md).

`TeachingPolicy.supportsSpeechLanguageDetection` therefore disables detector-driven redirection for `tl`. It does not treat Indonesian as Tagalog, force the recognizer to a desired answer, or change behavior for existing targets. Explicit Tagalog teaching instructions remain active. The tradeoff is that actual language drift also cannot be corrected through this detector; device and speaker review should check for English leakage.

The Debug language-flow report separates `flowPassed` from `passed`, which additionally requires reliable target-language detection. A Tagalog run can complete the mechanical flow while `passed` remains false and `languageQualityReview` remains `pending`. It records the detector's actual label/confidence rather than claiming Tagalog was recognized. Even for existing languages, automated detection does not certify pronunciation or correction quality.

## Data and service compatibility

Tagalog uses archive version 2 with its own vocabulary, topics and progress. Existing IDs and the Norwegian default are unchanged. Older app versions that do not contain `tl` reject Tagalog archives; upgrade them before importing. Retain the module in subsequent versions so existing learning records remain readable.

The BYOK iPhone flow uses the module's teaching instructions directly. Android's catalog is generated from the same Swift module; its regression tests cover the display alias, English support and isolated archive progress. The Apple detector exception applies only to iPhone; Android uses its own platform classifier and still needs live Tagalog review. The hosted service separately accepts `tl-PH` and rejects bare IDs and unconfigured aliases before reserving credit or conversation minutes. This addition does not enable hosted billing or require a deployment for native BYOK use.

See [build and test instructions](build-and-test.md) and [verification results](../verification/validation.md). Real-device conversation and proficient-speaker review remain separate from automated test completion.

## Lessons from other language contributions

Reviewed the related contributions on 15 September 2026:

- [Afrikaans, PR #21](https://github.com/Chuloo/mural/pull/21), adds language-specific apostrophe checks and updates Android store metadata. Applying those checks to Tagalog exposed a missing store-description entry, now corrected. Android now also tests the same contraction, hyphen, accent, aspect/focus, homograph and assisted-evidence cases as the existing Swift Tagalog suite. Its proposed Dutch detector alias is not adopted: a known wrong-language label cannot establish that Tagalog was spoken.
- [European Portuguese, PR #27](https://github.com/Chuloo/mural/pull/27), preserves Brazilian progress with a distinct regional ID and matches primary detector subtags. Tagalog already has one stable `tl` namespace with `fil` rejected as an evidence/storage alias. The regional matcher does not address Apple's Tagalog misclassification, so the existing safeguard stays in place. Translating the interface is separate from adding a learning target.
- [Mandarin, PR #4](https://github.com/Chuloo/mural/pull/4), its [four-language integration, PR #15](https://github.com/Chuloo/mural/pull/15), and [Android parity, PR #23](https://github.com/Chuloo/mural/pull/23), establish exact-text word links, independent meanings, shared content and archive tests. Their integrated implementation is already inherited through upstream; the new Android Tagalog regressions exercise those paths with Tagalog text.
- [Multilingual classrooms, PR #26](https://github.com/Chuloo/mural/pull/26), proposes a separate spoken explanation language and guided lessons. That remains a separate product change. This feature supports English meanings, contextual lookup and English/mixed learner replies while the tutor speaks Tagalog.
- [International English startup, PR #28](https://github.com/Chuloo/mural/pull/28), checks hosted admission against actual native registries, exposing the existing `en` versus `en-US` mismatch. Its registry check and English fix were subsequently merged upstream and are now inherited here, alongside Tagalog's `tl-PH` admission and credit/minute settlement tests. The provider test covers nine native locales plus the existing `en-US` compatibility alias.
