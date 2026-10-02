# Serbian, Greek and Tagalog

Both native clients use the same language content and stable storage IDs. Language choices also have matching hosted-server locales. The interface remains English or Spanish; choosing a learning language changes conversation content, meanings and progress.

| Language | Default teaching target | Storage ID | Hosted locale |
| --- | --- | --- | --- |
| Serbian | Standard Serbian, Latin script, Ekavian | `sr` | `sr-Latn-RS` |
| Greek | Modern Standard Greek as spoken in Greece | `el` | `el-GR` |
| Tagalog/Filipino | Conversational Tagalog as spoken in the Philippines | `tl` | `tl-PH` |

All three have six teaching stages, local cultural themes, greetings, lookup fallback text and meaning-language choices. Valid regional usage and support-language replies are accepted. Archives preserve each conversation's language and exact quoted text. Evidence from another language cannot advance the selected language's vocabulary.

## Serbian

The tutor produces Latin Ekavian text and accepts Cyrillic, Ijekavian and ordinary unaccented keyboard input. Input quotes remain unchanged. Guidance includes č/ć and dž/đ distinctions, syllabic r, stress, vowel quantity and pitch accent. These are pronunciation targets, not judgments inferred from a transcript. Serbian's standard accent system involves both pitch and length; see the [University of Novi Sad account of Serbian speech rhythm](https://digitalna.ff.uns.ac.rs/sites/default/files/db/books/Maja%20Bjelica%20-%20SPEECH%20RHYTHM.pdf).

Vocabulary guidance keeps verb aspect pairs and reflexive `se` distinct. Nouns use nominative singular and verbs use the infinitive. The text detector accepts closely related Serbian labels without redirecting valid Serbian output to another language.

## Greek

The tutor uses modern pronunciation and monotonic writing. Guidance covers lexical stress, intonation, modern consonants, digraphs, αυ/ευ voicing and polite forms of address. The [Centre for the Greek Language's terminology](https://www.greek-language.gr/linguisticterms/taxonomy/term/55) distinguishes stress, intonation and the monotonic writing system.

Captions, quotes and backups retain tonos, diaeresis, combined marks, final sigma and either Unicode normalization form. Uppercase accent omission and Greeklish input can be handled as support without rewriting the learner's quote. Word identities retain meaningful accent differences such as `πότε` and `ποτέ`.

Modern Greek verbs use the first-person singular present as their citation form, rather than an invented infinitive. Voice and aspect distinctions remain separate. Both `;` and the Unicode Greek question mark complete a Greek question for subtitle scheduling; a semicolon in another language keeps its ordinary behavior.

## Tagalog/Filipino

The tutor uses conversational Tagalog and accepts regional usage and Taglish as support. Guidance covers appropriate `po`/`opo`, respectful `kayo`, inclusive `tayo` versus exclusive `kami`, aspect, voice and participant markers. Natural stress, vowel length, glottal stops and intonation guide speech; spelling alone does not establish pronunciation quality.

Hyphens, apostrophes, contractions and optional stress/glottal marks remain in captions and quoted evidence. The [Komisyon sa Wikang Filipino's orthography discussion](https://kwf.gov.ph/wp-content/uploads/2015/12/Pagpaplanong-Wika-at-Filipino.pdf) describes the use of diacritics to distinguish pronunciation and meaning. Vocabulary guidance groups aspect forms within a voice but keeps actor- and object-focus verbs separate. Ambiguous English or mixed-language evidence is omitted from target-language vocabulary.

The system text detector can label Tagalog as Indonesian with high confidence. Mural therefore disables automatic language redirection for `tl`; voice, teaching and helper prompts still specify Tagalog. A successful transport check does not certify native-speaker quality.

## Phone requirements and background conversations

Voice continues only for a conversation the learner starts in the foreground after microphone consent. No additional OS speech-recognition or downloaded voice pack is required; the existing multilingual provider supplies voice.

iOS declares the audio background mode and uses its existing play-and-record voice session. This follows [Apple's background recording requirements](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/record). A bounded memory credential allows an existing personal-key conversation to authenticate helpers while locked. The saved key keeps its device-only, when-unlocked protection. A hosted call closes with the owner already pinned in its active lease and retains its pre-connection recovery record until secure storage is available.

Android starts a microphone and media-playback foreground service while the app is visible. This matches [Android's foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types) and [audio-focus rules](https://developer.android.com/media/optimize/audio-focus). The service shows a quiet notification with return and End controls, holds a bounded CPU wake lock and releases it on closure. It does not automatically restart a paid conversation after process death. Android 13 notification denial does not prevent a foreground voice service.

Returning to Mural retains the session ID, transcript and usage. Locking does not reset the existing silence timer or chosen session duration. Interruptions still end the call and release audio. Written conversations retain their existing background-close behavior.

Before publishing the next Android store build, declare the microphone and media-playback foreground-service use in Play Console and supply the required demonstration. [Google Play's policy](https://support.google.com/googleplay/android-developer/answer/13392821) requires this declaration for apps targeting Android 14 or later. Pull-request validation and installing a test build do not publish a store release.

## Verification limits

Shared fixtures test Unicode preservation, script variants, word selection, archive exchange, language isolation and redirect decisions on both platforms. Backend tests cover native locale admission, rejection before funding reservation, and credit/minute session closure. Native interface checks exercise selection, saved conversations and background service cleanup.

Real-phone checks must distinguish audio delivery, human speech recognition and linguistic quality. Synthetic typed turns with real voice output verify delivery and helper behavior; a competent speaker must still review regional pronunciation, accent, stress and corrections. Device results and deployment evidence belong in the corresponding verification report.

Serbian builds on [genes8's contribution in #134](https://github.com/Chuloo/mural/pull/134). Tagalog adapts [Pedro Gomes's contribution in #19](https://github.com/Chuloo/mural/pull/19) to the current native clients and hosted service.
