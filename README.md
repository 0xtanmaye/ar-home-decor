# AR Home Decor

An Android app that shows, through the phone camera and in real time, how furniture and wall
paint would look in a real room. Accuracy is the main goal.

**What works in the current test builds**
- Live camera view with surfaces detected and drawn as see-through grids:
  - **cyan**: floor
  - **amber**: tables, beds and other raised surfaces
  - **pink**: walls
  - **grey**: ceilings
- Plain walls can be marked by hand with two taps.
- A 50 cm reference cube can be placed to check that sizes look right.
- An on-screen panel shows measurements such as camera height and how well the grids fit the
  real surfaces.

Furniture placement and wall paint come in later versions.

**Contents**
1. [What you need](#1-what-you-need)
2. [Install the app on your phone](#2-install-the-app-on-your-phone)
3. [Or: build the app yourself with Android Studio](#3-or-build-the-app-yourself-with-android-studio)
4. [Using the app](#4-using-the-app)
5. [Reporting a problem](#5-reporting-a-problem)
6. [Troubleshooting](#6-troubleshooting)
7. [For developers](#7-for-developers)

---

## 1. What you need

- **An Android phone that supports Google Play Services for AR (ARCore).** Check yours in
  Google's [list of supported devices](https://developers.google.com/ar/devices). It needs
  Android 7.0 or newer. Most recent mid-range and flagship phones are on the list.
- **Only if you want to build the app yourself (section 3):** a Windows, macOS or Linux computer
  with about 15 GB of free disk space, and a USB cable that carries data (not a charge-only
  cable).

## 2. Install the app on your phone

This is the easiest way and needs no computer.

1. On your phone, open the repository's
   **[Releases page](https://github.com/0xtanmaye/ar-home-decor/releases)** in a browser. No
   GitHub account is needed.
2. Under the newest release, open **Assets** and tap the file ending in **`.apk`** to download it.
3. Open the downloaded file, for example from the browser's downloads or the notification.
   - If the phone asks whether your browser may **install unknown apps**, tap **Settings**, turn
     the switch on, then go back.
   - If **Google Play Protect** warns about an unknown app, tap **More details → Install
     anyway**. Test builds are not from the Play Store, so this warning is expected.
4. Tap **Install**, then **Open**.
5. Allow **camera** access when asked. The start screen then checks your phone:

   | "Mode" on the start screen | What to do |
   |---|---|
   | live AR available | Tap **Start AR**. |
   | install or update Google Play Services for AR | Tap the button. The Play Store opens: install or update, then come back to the app. |
   | photo mode (live AR not supported on this device) | This phone can't run live AR, so it can't be used for testing yet. |

   The **Depth API** line shows whether your phone can measure depth. The app works either way,
   but grids fit real surfaces more precisely when depth is supported.

**Updating to a newer test build:** install the new `.apk` the same way; it replaces the old one.
If the phone says **"App not installed"**, uninstall *AR Home Decor* first (long-press its icon →
Uninstall), then install again. This happens when the two builds came from different computers.

## 3. Or: build the app yourself with Android Studio

Use this if there is no ready-made `.apk` for the version you need, or if you want the very latest
changes. Android Studio is Google's free tool for Android apps and includes everything needed.

**One-time setup**

1. Download and install the latest stable **Android Studio** from
   [developer.android.com/studio](https://developer.android.com/studio). Accept the default
   options in its setup wizard.
2. Get the project:
   - On this repository's GitHub page, click **Code → Download ZIP** and unzip it, for example
     into your Documents folder.
   - Or, if you use Git: `git clone https://github.com/0xtanmaye/ar-home-decor.git`
3. Open Android Studio → **Open** → choose the unzipped folder (the one that contains
   `settings.gradle.kts`) → **Trust Project**.
4. Wait for the first **Gradle sync** to finish. The progress bar is at the bottom of the window.
   The first time takes 5–15 minutes because it downloads the build tools.
   - If a message offers to **install missing SDK packages** or **accept licences**, click it
     and accept.
   - If it says the project needs a newer Android Studio, use **Help → Check for Updates**.
5. Turn on **USB debugging** on your phone:
   1. Open **Settings → About phone** (on Samsung: **Settings → About phone → Software
      information**) and tap **Build number** seven times. The phone confirms that developer
      options are on; you may need to enter your PIN.
   2. Go back to **Settings → Developer options** (on Samsung it is at the bottom of Settings)
      and turn on **USB debugging**.

**Installing on your phone (each time)**

1. Connect the phone to the computer with the USB cable.
2. On the phone, a prompt asks **"Allow USB debugging?"**: tick **Always allow from this
   computer** and tap **Allow**.
3. In Android Studio's top toolbar, choose your phone in the device menu. The configuration next
   to it should say **app**.
4. Click the green **Run ▶** button. The app is built, installed and opened on the phone. This
   takes a few minutes the first time and is much quicker after that.
5. You can then unplug the phone: the app stays installed.

To get newer changes later, download the ZIP again (or `git pull`), open it the same way and
click **Run ▶** again.

## 4. Using the app

1. Tap **Start AR** and hold the phone at chest height, pointing at the floor 1–2 m ahead.
2. **Move the phone sideways slowly** for 5–10 seconds, for example by taking a step or sweeping
   your arm. Turning on the spot is not enough: the app judges distance from how the view shifts
   as the camera moves.
3. Grids appear on the surfaces it finds (colours as listed at the top). Grid edges fill in over a
   second or two as you look around.
4. **Place cube** (default): tap the floor or a table to place the 50 cm reference cube.
5. **Mark wall**: plain walls with no texture are not detected automatically. Tap the floor
   exactly where it meets the wall, then tap a second point further along the same wall.
6. The bottom of the screen always tells you what to do next, for example *"Move the phone more
   slowly"* or *"Too dark: turn on more lights"*.

**[docs/testing-guide.md](docs/testing-guide.md)** covers the details: preparing a session, what
each line of the debug panel means, how to check accuracy with a tape measure, and known
limitations. Please read it before a proper test session.

## 5. Reporting a problem

Note down **what you did, where** (room and lighting) and **your phone model**, and if you can,
make one of these:

- **A screen recording.** This is the easiest: use your phone's own screen recorder (in the quick
  settings panel), reproduce the problem, and stop.
- **An AR session recording.** These can be replayed exactly on a development phone, which makes
  problems much easier to fix.
  1. Tap **Record session** at the bottom of the AR screen **before** scanning; the panel's
     **Session** line counts the time.
  2. Point at the floor for a few seconds, then reproduce the problem and keep it in view for a
     few seconds.
  3. Tap **Stop recording**. Aim for **30–60 seconds**: a replay needs the first few seconds just
     to start tracking.
  4. To copy the recording off the phone, connect it to Android Studio (section 3), open
     **View → Tool Windows → Device Explorer**, go to
     `data/data/com.arhomedecor.app/files/recordings`, right-click the file and choose
     **Save As**.

Then open a **GitHub issue** in this repository (the **Issues** tab → **New issue**) describing
the problem.

> **Issues are public.** Screen and session recordings show the inside of your home, so never
> attach them to an issue. Send them to the developer privately instead (for example in a private
> chat, or a cloud-storage link shared only with them) and mention in the issue that you did.

## 6. Troubleshooting

| Problem | What to try |
|---|---|
| "App not installed" when installing a new `.apk` | Uninstall *AR Home Decor* first, then install again. |
| Start screen says **photo mode** | The phone doesn't support live AR (see section 1). Use another phone. |
| The camera view stays black | Close other apps that use the camera, then leave the AR screen and open it again. |
| Grids don't appear | Use more light, move the phone sideways more slowly, and point at a floor with some texture or pattern. |
| Things placed earlier disappear | ARCore lost its place. Point back at an area you scanned before and hold still for a few seconds (see the testing guide). |
| Android Studio doesn't list the phone | Check that the "Allow USB debugging?" prompt was accepted, try another cable or port, and on Windows install your phone maker's USB driver (e.g. *Samsung Android USB Driver*). |
| Gradle sync failed | Check your internet connection, then **File → Sync Project with Gradle Files**. If it mentions a newer Android Gradle Plugin, update Android Studio. |

---

## 7. For developers

Everything above works without these steps. This section covers developing from the command line,
without Android Studio.

### Command-line requirements

- JDK 21
- Android SDK command-line tools with platform 37, build-tools 37.0.0 and platform-tools, and
  `ANDROID_HOME` pointing at the SDK
- `ffmpeg` (only for the screen-capture contact sheets)

Gradle itself is fetched by the wrapper (`./gradlew`), pinned by version and checksum.

### Build and run

```bash
./gradlew assembleDebug          # build the debug APK (app/build/outputs/apk/debug/)
./gradlew testDebugUnitTest      # run unit tests
./gradlew installDebug           # install on the connected phone
adb shell am start -n com.arhomedecor.app/.MainActivity
```

### Connect a phone over Wi-Fi

1. On the phone: Developer options → Wireless debugging → on.
2. Pair once: `adb pair <ip>:<port>` using the code from "Pair device with pairing code".
3. Each session: `./tools/connect-phone.sh` finds the phone on the network and connects adb.
   adb's own discovery only listens on the default network interface, which misses phones
   reachable through a second adapter (such as a VM's bridged adapter).

### Screen capture

`./tools/screenshot.sh <file.png>` and `./tools/record-screen.sh <seconds> <dir>` capture the phone
screen for review; the recorder also produces timestamped contact sheets with ffmpeg. Both only
capture while this app is in the foreground and stop if another app comes to the front.

### Recording and replaying AR sessions

Debug builds show **Record session** in the AR view. It uses ARCore's Recording & Playback API to
save the camera images, motion sensor data and the user's taps to an MP4 in the app's private
storage. Replaying a recording runs ARCore on that data instead of the live camera, so a problem
seen once can be reproduced, and a fix compared with the behaviour before it, without walking the
room again. ARCore recomputes tracking, planes and depth on each replay, so runs are close but not
identical.

- `./tools/pull-recordings.sh` copies recordings to `recordings/`. That folder is ignored by git
  because the recordings show real rooms.
- `./tools/play-recording.sh [--depth-debug] <recording.mp4> [seconds dir [fps]]` restarts the
  app, replays the recording and optionally records the screen while it plays. The phone must be
  unlocked.
- `--depth-debug` colours planes by the depth check instead of drawing grids:

  | Colour | Meaning |
  |---|---|
  | green | the real surface is the plane |
  | red | something is in front of the plane |
  | blue | the real surface is behind the plane |
  | yellow | at the plane's distance but facing another way |
  | grey | beyond ARCore's outline and not yet confirmed by measured depth |
  | magenta | repeatedly seen through |

  A white line marks ARCore's own outline.

Logcat tags: `ArStats` (once a second), `ArEvents` (tracking and map events), `ArPlanes`
(per-plane classification changes), `ArRecording`.

### Publishing a test build for the team

Each release on GitHub carries a ready-made APK for section 2:

```bash
./gradlew testDebugUnitTest assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /tmp/ar-home-decor-<version>-debug.apk
gh release create <tag> /tmp/ar-home-decor-<version>-debug.apk --prerelease \
    --title "Test build <version>" --notes "What changed and what to test"
```

The APK is signed with the building machine's debug key, so testers switching between builds
from different machines must uninstall first. Raise `versionCode`/`versionName` in
`app/build.gradle.kts` for each new test build.

### Materials and models

- Filament materials are written in `app/src/main/materials/*.mat` and compiled into
  `app/src/main/assets/materials/*.filamat` by `./tools/compile-materials.sh`. The script downloads
  `matc` from the official Filament release matching the app's Filament version and verifies its
  checksum. Re-run it after editing a material or changing the Filament version. The compiled
  files are committed, so building the app doesn't need `matc`.
- The 50 cm reference cube used for scale checks is generated with
  `python3 tools/make_reference_cube.py 0.5 app/src/main/assets/models/reference_cube_50cm.glb`.

### Project layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose, ARCore, Filament) |
| `app/src/main/materials/` | Filament material sources |
| `gradle/libs.versions.toml` | Dependency versions |
| `tools/` | Development helper scripts |
| `docs/testing-guide.md` | How to scan a room, read the debug panel and run accuracy checks |
