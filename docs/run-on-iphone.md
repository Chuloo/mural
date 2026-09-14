# How to run Mural on your iPhone

Use this guide to install a personal build from source. After installation, the phone connects directly to OpenAI and works away from your Mac.

## Before you start

- A Mac with Xcode 26 or later, downloaded from Apple.
- An iPhone running iOS 26.1 or later and a USB cable for initial setup.
- An Apple Account added to **Xcode → Settings → Accounts**.
- An OpenAI project with API billing enabled and access to the models configured in `apps/ios/App/APIClient.swift` and `apps/ios/App/LiveTransport.swift`.

OpenAI API usage is paid separately from ChatGPT. Use your own key for this personal build. Never add a key to source code, an Xcode build setting, a screenshot or a GitHub issue.

## Update an earlier checkout

The iPhone project and its configuration now live in `apps/ios/`. Git does not move your ignored personal settings. If your earlier checkout has `Config/Local.xcconfig`, copy it to `apps/ios/Config/Local.xcconfig` only when that destination does not already exist. From the repository root:

```sh
python3 - <<'PY'
from pathlib import Path

source = Path("Config/Local.xcconfig")
destination = Path("apps/ios/Config/Local.xcconfig")
if source.is_file():
    try:
        with destination.open("xb") as output:
            output.write(source.read_bytes())
        print("Copied existing local settings without changing the original.")
    except FileExistsError:
        print("The new local settings file already exists; no files changed.")
else:
    print("No earlier local settings file found.")
PY
```

Both paths remain ignored by Git. If both files exist, compare them privately and keep the intended team and account settings in the new file; do not publish their contents. Keep the development team and bundle identifier used by your installed app. If you previously changed the identifier directly in Xcode, confirm that change is present in the relocated project before running it. Open `apps/ios/Mural.xcodeproj` for future builds. Moving the project does not move or erase the learning data or Keychain entries already on your iPhone.

## Install

1. Download the [repository ZIP](https://github.com/Chuloo/mural/archive/refs/heads/main.zip) and extract it, or clone [Chuloo/mural](https://github.com/Chuloo/mural). Open `apps/ios/Mural.xcodeproj`. Allow Xcode to resolve the pinned WebRTC package.
2. Select the blue **Mural** project in the navigator. Under **Targets**, choose **Mural**, then open **Signing & Capabilities**.
3. Enable **Automatically manage signing** and choose your Apple team. For a fork, set a unique bundle identifier, such as `com.yourname.mural`. Do not change an existing installation’s identifier when refreshing it.
4. Connect the iPhone, unlock it, and accept **Trust This Computer** if shown. In Xcode’s **Window → Devices and Simulators**, wait for the phone to finish preparing.
5. On the iPhone, enable **Settings → Privacy & Security → Developer Mode**. Restart and confirm **Turn On** when prompted.
6. In Xcode’s toolbar, select the **Mural** scheme and your iPhone as the destination. Click **Run** or press **⌘R**. If macOS requests access to the signing key, allow Xcode to use it.
7. If iOS blocks the first launch because the developer is untrusted, open **Settings → General → VPN & Device Management**, select your developer profile, and tap **Trust**. Return to Xcode and run again.
8. Choose your learning and subtitle languages in the welcome screens. In **Settings → Advanced → Use your own API key**, save your OpenAI project key. Tap the main conversation button and allow microphone access.

Mural should greet you aloud. Disconnect the cable and confirm a short conversation over Wi-Fi or cellular.

## Refresh a free installation

A free Personal Team provisioning profile expires after seven days. Reconnect your phone and run the same project with the same team and bundle identifier. Keep the app installed while refreshing it. [Apple’s account and membership guidance](https://developer.apple.com/support/compare-memberships/)

Use **Settings → Export learning backup** before changing your bundle identifier, signing team or device. On a new installation, choose **Import learning backup** and enter your API key again. The key is never included in the backup.

## Fix setup problems

| Problem | Action |
| --- | --- |
| Xcode cannot register the bundle identifier | Choose a unique identifier for your fork and reselect your team. |
| The phone does not appear as a destination | Unlock it, check the cable, and open Devices and Simulators to finish pairing. |
| Developer Mode is missing | Pair with Xcode first, then check Privacy & Security again. |
| The app will not launch after a week | Refresh the build through Xcode without uninstalling. |
| Key rejected or model unavailable | Check the key’s project, API billing, permissions and model access in OpenAI. |
| No microphone input | Enable Mural under iPhone Settings → Privacy & Security → Microphone. |
| Sound uses the wrong output | Check iOS’s current audio destination; disconnect an unwanted Bluetooth route. |
| A connection fails on cellular | Confirm Mural is allowed to use cellular data and retry with a stable connection. |

For simulator previews and automated checks, use [the build guide](build-and-test.md).
