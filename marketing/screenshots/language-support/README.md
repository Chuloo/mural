# Language support screenshots

The original three unedited captures show Mural's native Talk screen on an iPhone 16 Pro simulator running iOS 26.4.1, taken on 13 September 2026 from the app at `7ea5a8168d35894323ce0717ec8b2caee0e26191`.

| File | Language | Visible behavior |
| --- | --- | --- |
| `german.png` | German | German caption and optional English meaning |
| `brazilian-portuguese.png` | Brazilian Portuguese | Portuguese caption, accents and optional English meaning |
| `mandarin-pinyin.png` | Mandarin | Simplified Chinese caption, expanded pinyin help and English meaning |

The captures use the Debug preview's sample conversation data, with the conversation ended and microphone off. They demonstrate the interface; separate live checks on the owner's iPhone are recorded in [validation.md](../../../verification/validation.md).

To reproduce, install a Debug simulator build and launch it with `--preview --ended-conversation --preview-language=<de|pt|zh> -UIPreferredContentSizeCategoryName UICTContentSizeCategoryL`. Capture within 15 seconds, before the ended conversation resets. These images were taken after three seconds with `xcrun simctl io <device-id> screenshot <absolute-output-path>` and a 9:41 status bar override.

## Tagalog

Three unedited captures from the Tagalog feature on 13 September 2026, using a dedicated iPhone 17 simulator running iOS 26.5:

| File | Visible behavior |
| --- | --- |
| `tagalog.png` | Philippine café theme, original Tagalog caption and cached English meaning, ended conversation and microphone off |
| `tagalog-onboarding.png` | Selected Tagalog (Filipino) option with Philippines variety |
| `tagalog-accessibility.png` | The full selection label and Continue button at the largest accessibility text size |

The accessibility capture is scrolled to Tagalog; preceding rows may be partially outside the viewport. The Tagalog regression explicitly checks that the entire selected card fits within the visible scroll area, that Continue fits within the screen, and that the card does not overlap the button. A fresh capture from the 14 September review-fix run was also visually checked for readable, untruncated Tagalog text.

The onboarding captures are attachments from the passing 25-test suite in `.build/Tagalog-UI.xcresult`. The Talk capture uses `--preview --ended-conversation --preview-language=tl -UIPreferredContentSizeCategoryName UICTContentSizeCategoryL`, captured after two seconds with `simctl io screenshot`. These are synthetic interface fixtures, with no live provider call or generated speech. Physical-device and proficient-speaker review remain pending.
