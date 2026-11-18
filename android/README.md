# TV Remote for Android (LG webOS)

A phone remote for LG TVs, designed around talking, swiping and searching instead of rows of buttons. It connects straight to the TV over Wi-Fi; there's no bridge, no account and no cloud.

## What it does

- **Voice (mic button):** say what you want, e.g. "open Netflix", "volume 20", "louder", "pause", "switch to HDMI 2", "channel 7", "find cooking videos on YouTube", "turn off the TV". Commands are understood on the phone (`voice/CommandParser.kt`); only the speech-to-text uses the phone's speech recognizer.
- **One "ask or search" box:** type the same things as you'd say. Anything that isn't a command becomes a search.
- **Touchpad:**
  - *Swipe* mode: swipe to move through menus (keep sliding to keep moving), tap to select.
  - *Pointer* mode: drag to move the TV's Magic Remote cursor, tap to click, two fingers to scroll.
- **Typing onto the TV:** when a text box is open on the TV, the search box turns blue and whatever you type or say goes straight into it.
- **Your TV's apps** in a strip, in the same order as the TV's home bar.
- **The phone's volume buttons** control the TV's volume while the app is open.
- Power off, and power on via Wake-on-LAN. The TV's MAC address is learned automatically the first time you connect.

## Install

1. Copy `TV-Remote.apk` to the phone and open it. Allow "Install unknown apps" for whichever app you opened it from (Files, Chrome, …).
2. Turn the TV on, open TV Remote, tap your TV, and press **Accept** on the TV's prompt with the LG remote. You only do this once.
3. To turn the TV on from the phone, enable *Settings › General › Devices › TV Management (or External Devices) › Turn on via Wi-Fi* on the TV. The menu location varies by year.

## Build

```bash
cd android
./gradlew testDebugUnitTest      # parser + LG protocol tests (simulated TV)
./gradlew recordPaparazziDebug   # re-render screenshots in app/src/test/snapshots
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17+ and the Android SDK (platform 35). Android Studio sets both up.

## Known limits

- **Search inside apps:** YouTube searches are sent as a deep link. Other apps (Netflix, Prime, …) can't be searched from outside, so the app opens them; once you open that app's search box, voice and typing go straight in.
- The release APK is signed with a debug key. Rebuilding on another machine changes the key, so you'll need to uninstall before installing the new build.
