# Android visual review

These captures show the actual Android interface on a Pixel 9 emulator running API 36. Images 01–12 show the full display; images 13–16 show the purchase sheet itself. They use the separate `chat.mural.android.uitest` installation, no account or API credentials, and disabled continuous motion. They are design-review evidence, not final Play Store listing assets.

- [Learning language](01-onboarding.png)
- [Subtitle language](02-meaning.png)
- [Talk](03-talk.png)
- [Themes](04-themes.png)
- [Words](05-words.png)
- [Settings](06-settings.png)
- [Settings language menu](07-settings-language-menu.png)
- [Advanced settings](08-settings-advanced.png)
- [Data controls](09-settings-data.png)
- [Settings at 160% text size](10-settings-large-text.png)
- [Advanced settings at 160%](11-settings-large-advanced.png)
- [Interests above the keyboard at 160%](12-settings-large-keyboard.png)
- [Minute packs, synthetic quote](13-minute-packs-synthetic.png)
- [Pending purchase, synthetic state](14-minute-pending-synthetic.png)
- [Minute packs in Spanish, synthetic quote](15-minute-packs-spanish-synthetic.png)
- [Spanish minute packs at 160%, synthetic quote](16-minute-packs-spanish-large-synthetic.png)

The Settings captures are 1080 × 2424 pixels at 420 dpi, from `SettingsParityTest`. The large-text review temporarily used the emulator's actual `font_scale=1.6`, with regular keyboard input, then restored the original settings and returned to the personal app. A Compose-local density override does not reach the separate window used by the Settings sheet. The regular preference controls, expanded Advanced section, Done button and interests field remained reachable; the input stayed above the keyboard.

The purchase captures come from `MinutePurchaseSheetTest`. The displayed 30-minute pack and €5.99 price are test fixtures, not approved launch pricing or a live product. The pending state is simulated; no purchase, account or provider request was made. The sheet shows the minimum charge before the purchase control, rejects taps while pending or signed out, and keeps its controls reachable when scrolled at 160% text size. The Spanish test temporarily changes only the isolated app's locale and restores it. The large-text run also restores the original emulator font setting. After these captures, all 29 isolated interface tests passed with the normal font and keyboard settings.

To refresh the original interface captures, start an emulator, configure Java 17 and `ANDROID_HOME`, then run from the repository root:

```sh
python3 scripts/capture_android_design.py --serial emulator-5554
```

The script builds and retains the isolated test installation. It refuses physical-device identifiers, does not modify a personal Mural installation, and does not call an AI or account provider. The test checks both dropdowns, the consent gate, primary action visibility and the floating navigation geometry before saving captures.
