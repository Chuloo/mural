# Install and use Mural on Android

## Personal installation

You need Android 8.0 or later and an OpenAI project key with access to the models Mural uses. A ChatGPT subscription does not include API credit.

Install the generated APK (`apps/android/app/build/outputs/apk/debug/app-debug.apk`) by opening it on the phone and allowing the installation, or from a computer:

1. Turn on **Developer options → USB debugging**. To show Developer options, tap **Build number** seven times in About phone.
2. Connect the phone with a data cable and accept the authorization prompt for this computer. On Android 11 or later you can use **Wireless debugging** instead: pair once with `adb pair <ip>:<port> <code>`, then `adb connect`.
3. Run:

   ```sh
   adb devices -l
   adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n chat.mural.android/chat.mural.MainActivity
   ```

`-r` updates the app and keeps its data. Updates need the same application ID (`chat.mural.android`) and the same signing key; do not uninstall the app if you want to keep your history. USB or wireless debugging is only needed to install and test from a computer, not for everyday use. The debug APK is for personal installation, not for Google Play.

If `apps/android/.signing/debug.keystore` exists (it is ignored by Git), the debug build signs with it, so a personal installation keeps its signing identity across SDK reinstalls. Keep a copy when moving development to another computer; losing it means exporting a backup and reinstalling with a new signature.

## First use

Mural speaks the interface language of your phone: Spanish on a Spanish phone, English otherwise. That is separate from the language you practise and the language you read meanings in, which you choose next.

Choose the language you practise and the language for meanings. When you practise Mandarin, captions link each word and show pinyin underneath on Android 10 or later; **Hide pinyin** keeps the characters only. Read the consent to send audio and text to OpenAI. You can decline and still browse your local data.

In **Settings**, save your own OpenAI key. Do not send it through chat or put it in repository files. It is encrypted with an Android Keystore key and is never included in learning backups.

On **Talk**, start a conversation and allow the microphone. You should hear a greeting in the language you chose. **Type instead** lets you practise without the microphone. You can mute, ask for a little help, show meanings, tap a word to look it up, and end the conversation. Any conversation ends when the app moves to the background. A voice conversation also ends when audio is interrupted, when it reaches the chosen duration, or after two minutes without activity; a written conversation stays open while you compose a reply.

Mural needs the internet to talk, translate and search. History and vocabulary are available offline. Requests are billed to your OpenAI project; **Settings** shows recorded voice time and a voice cost estimate. The app's time limit is not a billing cap.

## Move data from iPhone

On iPhone, export a learning backup from Settings and transfer the JSON file any way you like. On Android, choose **Settings → Import learning backup**. Mural v1 and v2 backups up to 30 MB are accepted. Conversations that are not already saved are added, and the Android settings are kept. The key is not transferred: enter it again on the phone.

Android exports use the same format, and iPhone can import them. There is no automatic sync or Mural account.

## Build

Install Java 17 and Android Studio or the official command-line tools. Install SDK Platform 36 and Build Tools 35.0.0. Set `ANDROID_HOME`, or create a private `apps/android/local.properties` with `sdk.dir=/path/to/sdk`.

```sh
cd apps/android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `gradlew.bat`. Gradle downloads dependencies on first use. The wrapper and its checksum are versioned, so a global Gradle installation is not needed.

For interface tests, use an emulator or a test device:

```sh
cd apps/android
./gradlew :app:connectedUiTestAndroidTest
```

The interface tests install as a separate app, `chat.mural.android.uitest`, so they never read or change the data of your Mural installation. They do not need an API key and do not call OpenAI.

## Troubleshooting

- **ADB shows `unauthorized`:** unlock the phone and accept the debugging authorization.
- **No device is listed:** check the cable, File transfer mode and USB debugging.
- **Microphone blocked:** Android Settings → Apps → Mural → Permissions → Microphone. You can also type.
- **Sound plays on the speaker with a Bluetooth headset:** connect the headset before starting the conversation. Mural chooses a connected headset on Android 12 or later; on Android 8–11 it uses the speaker.
- **Key rejected or usage limit reached:** check the key, model access and project limits in your OpenAI account. Mural never needs you to share your key with anyone.
- **Switching to another app:** Mural ends a voice conversation to release the microphone. Come back and start another; history is kept.
- **Backup error:** make sure it is a complete Mural file under 30 MB. The importer rejects a file before merging invalid records.
- **Update rejected because of the signature:** use the same signing key as the previous installation. Export a backup before any uninstall.

The architecture is described in [android/design.md](android/design.md); test results are recorded in [android-validation.md](../verification/android-validation.md).

## Optional Mural account

Google account setup is documented in [Android accounts](android-accounts.md). It uses a separate, encrypted session and keeps learning history local. Hosted conversations and minute purchases are still in development.
