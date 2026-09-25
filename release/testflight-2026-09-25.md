# Mural iOS 1.0 (2): internal TestFlight record

**Status on September 25, 2026:** Apple accepted build 2 for internal testing. App Store Connect lists it in the Mural Internal group with two invited testers. The App Store version remains a draft at **Prepare for Submission**; no App Review submission has been made.

## What build 2 contains

- Bundle ID `chat.mural.ios`, signed by Hackmamba Inc. The old personal-team ID `no.william.mural` was unavailable to this team, so the TestFlight app installs separately from the personal build.
- Hosted voice with an eligible-device allowance of up to 10 minutes, plus a personal OpenAI key option. Google sign-in uses the hosted Mural account server. The iOS Google OAuth client and the server's accepted audience were configured for the new bundle ID.
- A fresh hosted-minute lookup when Settings opens, to avoid showing the pre-conversation guest balance. The value is withheld while a conversation runs and refreshed afterward.
- A single language menu in onboarding. On normal iPhone screen sizes, long captions occupy their own scroll areas while the orb stays at a minimum size and the conversation controls remain visible. Accessibility text sizes and very short screens still allow page scrolling.
- Apple's required camera purpose string. The included voice framework references camera APIs; Mural's current conversations do not capture photos or video.

## Checks and limits

- Apple rejected build 1 because `NSCameraUsageDescription` was missing. The corrected Release archive and IPA for build 2 compiled, exported, uploaded, and processed successfully.
- On the directly installed phone build, a voice conversation and Google sign-in worked. After sign-in, Settings showed 8 min 54 sec remaining. Before sign-in, it had still shown 10 min 0 sec after the conversation; build 2 contains the display fix, which needs a TestFlight phone retest.
- The new onboarding menu and a long example passage were inspected in Simulator. The long passage stayed inside its caption area with the orb and controls visible. Manual scrolling inside that area still needs confirmation on the phone.
- The focused Xcode UI test could not start because Xcode compiled `MuralCore.swiftmodule` for an incompatible target. The simulator and device Release builds succeeded; the automated UI-test failure remains open.
- Personal-key use, account restoration and deletion, microphone denial, offline recovery, and all eight language qualities remain to be checked on the TestFlight build. Paid minutes and external TestFlight access are outside this internal milestone.

## App Store draft

The draft uses the existing pastel orb app icon from build 2 and four 6.9-inch iPhone screenshots in `screenshots/en-US-2026-09-25/`: conversation, themes, words, and greeting. The listing, review notes, contact information, and manual-release choice are saved. The App Privacy answers remain a draft. Do not add the version for review until the TestFlight device checks and final privacy review are complete.
