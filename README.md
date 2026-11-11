# AR Home Decor

Android app for previewing furniture and wall paint/patterns in a real room through the phone camera, in real time.

## Requirements

- JDK 21
- Android SDK (platform 37, build-tools 37.0.0, platform-tools), with `ANDROID_HOME` set
- An Android phone running Android 7.0+ with USB or wireless debugging enabled. Live AR needs an [ARCore-supported device](https://developers.google.com/ar/devices); other devices get photo mode.

## Connect a phone over Wi-Fi

1. On the phone: Developer options → Wireless debugging → on.
2. Pair once: `adb pair <ip>:<port>` using the code from "Pair device with pairing code".
3. Each session: `./tools/connect-phone.sh` finds the phone on the network and connects adb.

`./tools/screenshot.sh <file.png>` and `./tools/record-screen.sh <seconds> <dir>` capture the phone
screen for review (the recorder also produces timestamped contact sheets with ffmpeg). Both only
capture while this app is in the foreground.

## Recording and replaying AR sessions

Debug builds show a **Record session** button in the AR view. It uses ARCore's Recording & Playback
API to save the camera images, motion sensor data and the user's taps to an MP4 in the app's private
storage. Replaying a recording runs ARCore on that data instead of the live camera, so a problem
seen once can be reproduced, and a fix compared with the behaviour before it, without walking the
room again. ARCore recomputes tracking, planes and depth on each replay, so runs are close but not
identical.

- `./tools/pull-recordings.sh` copies recordings to `recordings/` (ignored by git: they show real rooms).
- `./tools/play-recording.sh [--depth-debug] <recording.mp4> [seconds dir [fps]]` restarts the app,
  replays the recording and optionally records the screen while it plays. The phone must be unlocked.
  `--depth-debug` colours planes by the depth check instead of drawing grids: green = the real
  surface is the plane, red = something in front of it, blue = behind it, yellow = at the plane's
  distance but facing another way, grey = beyond ARCore's outline and not yet confirmed by
  measured depth, magenta = repeatedly seen through. A white line marks ARCore's own outline.

## Build and run

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew testDebugUnitTest      # run unit tests
./gradlew installDebug           # install on the connected phone
adb shell am start -n com.arhomedecor.app/.MainActivity
```

## Materials and models

- Filament materials are written in `app/src/main/materials/*.mat` and compiled into
  `app/src/main/assets/materials/*.filamat` by `./tools/compile-materials.sh`. The script downloads
  `matc` from the official Filament release matching the app's Filament version and verifies its
  checksum. Re-run it after editing a material or changing the Filament version.
- The 50 cm reference cube used for scale checks is generated with
  `python3 tools/make_reference_cube.py 0.5 app/src/main/assets/models/reference_cube_50cm.glb`.

## Project layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose, ARCore, Filament) |
| `app/src/main/materials/` | Filament material sources |
| `gradle/libs.versions.toml` | Dependency versions |
| `tools/` | Development helper scripts |
| `docs/testing-guide.md` | How to scan a room, read the debug panel and run accuracy checks |
