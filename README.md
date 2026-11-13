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
4. [Or: build the app yourself without Android Studio](#4-or-build-the-app-yourself-without-android-studio)
5. [Using the app](#5-using-the-app)
6. [Reporting a problem](#6-reporting-a-problem)
7. [Troubleshooting](#7-troubleshooting)
8. [For developers](#8-for-developers)

---

## 1. What you need

- **An Android phone that supports Google Play Services for AR (ARCore).** Check yours in
  Google's [list of supported devices](https://developers.google.com/ar/devices). It needs
  Android 7.0 or newer. Most recent mid-range and flagship phones are on the list.
- **Only if you want to build the app yourself (sections 3 and 4):** a Windows, macOS or Linux
  computer with free disk space (about 15 GB with Android Studio, about 3 GB without), and a USB
  cable that carries data (not a charge-only cable) or a Wi-Fi network shared with the phone.

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

## 4. Or: build the app yourself without Android Studio

This is how the app is developed day to day: a terminal, Google's free Android command-line
tools, and the phone connected by USB cable or Wi-Fi. It needs about 3 GB of disk space.
Commands are typed (or pasted) into a terminal: **PowerShell** on Windows, **Terminal** on macOS
and Linux.

**One-time setup**

Open the steps for your computer and follow them in order. The Linux steps are the ones used
for this project; the Windows and macOS steps follow the same pattern with those systems'
usual folders.

<details>
<summary><b>Windows</b></summary>

1. **Java:** from [adoptium.net](https://adoptium.net/temurin/releases/?version=21), download the
   **JDK 21** `.msi` installer for Windows x64 and run it. On the *Custom Setup* page, set
   **Set JAVA_HOME variable** to *Will be installed on local hard drive*.
2. **Android command-line tools:** on
   [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only),
   scroll down to **Command line tools only** and download the **Windows** zip into your
   Downloads folder.
3. Open **PowerShell** and paste these lines. They unzip the tools to where Android Studio would
   put them, and tell Windows where they are:
   ```powershell
   $sdk = "$env:LOCALAPPDATA\Android\Sdk"
   New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
   $zip = Get-ChildItem "$HOME\Downloads\commandlinetools-win-*_latest.zip" | Select-Object -First 1
   Expand-Archive $zip.FullName "$sdk\cmdline-tools"
   Rename-Item "$sdk\cmdline-tools\cmdline-tools" latest
   [Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User")
   $path = [Environment]::GetEnvironmentVariable("Path", "User")
   [Environment]::SetEnvironmentVariable("Path", "$path;$sdk\cmdline-tools\latest\bin;$sdk\platform-tools", "User")
   ```
4. **Close PowerShell and open a new one** (only new windows see the changes), then install the
   parts of the Android SDK the app needs and accept Google's licences. Type `y` and press Enter
   whenever it asks:
   ```powershell
   sdkmanager "platform-tools" "platforms;android-37.0"
   sdkmanager --licenses
   ```
5. Check: `java -version` should mention version 21, and `adb version` should print a version.

</details>

<details>
<summary><b>macOS</b></summary>

1. **Java:** from [adoptium.net](https://adoptium.net/temurin/releases/?version=21), download the
   **JDK 21** `.pkg` installer for macOS and run it: *aarch64* for Macs with Apple silicon
   (M1 and later), *x64* for Intel Macs.
2. **Android command-line tools:** on
   [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only),
   scroll down to **Command line tools only** and download the **Mac** zip (*ARM* for Apple
   silicon, *Intel* otherwise) into your Downloads folder.
3. Open **Terminal** and paste these lines. They unzip the tools to where Android Studio would
   put them, and tell the terminal where they are:
   ```bash
   sdk="$HOME/Library/Android/sdk"
   mkdir -p "$sdk/cmdline-tools"
   unzip -q ~/Downloads/commandlinetools-mac*_latest.zip -d "$sdk/cmdline-tools"
   mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
   echo 'export ANDROID_HOME="$HOME/Library/Android/sdk"' >> ~/.zshrc
   echo 'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"' >> ~/.zshrc
   ```
   If Safari already unzipped the download into a `cmdline-tools` folder, replace the `unzip`
   and first `mv` lines with `mv ~/Downloads/cmdline-tools "$sdk/cmdline-tools/latest"`.
4. **Close Terminal and open it again** (only new windows see the changes), then install the
   parts of the Android SDK the app needs and accept Google's licences. Type `y` and press Enter
   whenever it asks:
   ```bash
   sdkmanager "platform-tools" "platforms;android-37.0"
   sdkmanager --licenses
   ```
5. Check: `java -version` should mention version 21, and `adb version` should print a version.

</details>

<details>
<summary><b>Linux (Debian, Ubuntu)</b></summary>

1. **Java:** `sudo apt install openjdk-21-jdk-headless unzip` (it asks for your password). If
   your system has no `openjdk-21` package, use the JDK 21 from
   [adoptium.net](https://adoptium.net/temurin/releases/?version=21) instead.
2. **Android command-line tools:** on
   [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only),
   scroll down to **Command line tools only** and download the **Linux** zip into your Downloads
   folder.
3. Paste these lines into a terminal. They unzip the tools into `~/Android/Sdk`, and tell the
   terminal where they are:
   ```bash
   sdk="$HOME/Android/Sdk"
   mkdir -p "$sdk/cmdline-tools"
   unzip -q ~/Downloads/commandlinetools-linux-*_latest.zip -d "$sdk/cmdline-tools"
   mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
   echo 'export ANDROID_HOME="$HOME/Android/Sdk"' >> ~/.bashrc
   echo 'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"' >> ~/.bashrc
   ```
4. **Close the terminal and open a new one** (only new windows see the changes), then install
   the parts of the Android SDK the app needs and accept Google's licences. Type `y` and press
   Enter whenever it asks:
   ```bash
   sdkmanager "platform-tools" "platforms;android-37.0"
   sdkmanager --licenses
   ```
5. Check: `java -version` should mention version 21, and `adb version` should print a version.

</details>

Then, on any system:

6. Get the project as in [section 3](#3-or-build-the-app-yourself-with-android-studio), step 2
   (**Download ZIP** and unzip it, or `git clone`).
7. Turn on **USB debugging** on the phone as in section 3, step 5.

**Installing on your phone (each time)**

1. Connect the phone with the USB cable. When it asks **"Allow USB debugging?"**, tick **Always
   allow from this computer** and tap **Allow**.
2. Open a terminal in the project folder (the one that contains `gradlew`):
   - Windows: open the folder in File Explorer, right-click an empty area and choose **Open in
     Terminal**.
   - macOS: type `cd ` (with a space) in Terminal, drag the folder onto the window, press Enter.
   - Linux: right-click inside the folder and choose **Open in Terminal**, or use `cd`.
3. Check that the phone is connected: `adb devices` should list it with the word `device` after
   it. `unauthorized` means the prompt on the phone hasn't been accepted yet.
4. Build the app and install it on the phone:
   - Windows: `.\gradlew.bat installDebug`
   - macOS and Linux: `./gradlew installDebug`

   The first time takes 5–15 minutes, because it downloads the build tools; after that it takes
   a minute or two. It ends with **BUILD SUCCESSFUL**.
5. Open **AR Home Decor** on the phone. You can unplug it now: the app stays installed.

To get newer changes later, download the ZIP again (or `git pull`) and repeat step 4.

**Connecting over Wi-Fi instead of a cable** (Android 11 and newer; the phone and computer must
be on the same Wi-Fi):

1. On the phone: **Settings → Developer options → Wireless debugging** → turn it on.
2. Once only: tap **Pair device with pairing code** and run `adb pair <address>` with the IP
   address and port shown in that pop-up, then type the code it shows.
3. On the **Wireless debugging** screen itself (not the pop-up), note the **IP address & Port**
   and run `adb connect <address>` with it. The port changes when the phone restarts or Wi-Fi
   reconnects; repeat this step then.
4. `adb devices` now lists the phone: continue with step 4 above.

## 5. Using the app

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

## 6. Reporting a problem

Note down **what you did, where** (room and lighting) and **your phone model**, and if you can,
make one of these:

- **A screen recording.** This is the easiest: use your phone's own screen recorder (in the quick
  settings panel), reproduce the problem, and stop.
- **An AR session recording.** These can be replayed exactly on a development phone, which makes
  problems much easier to fix. Test builds record every AR session automatically, from the moment
  the AR screen opens; the panel's **Session** line shows the time.
  1. When something looks wrong, keep it in view for a few seconds and tap **Mark problem** at
     the bottom of the screen. The session's recording is then kept, and the moment is marked in
     it.
  2. Carry on, or leave the AR screen. The recording is saved when you leave the AR screen (or
     after 5 minutes) as `session-<date>-<time>-marked.mp4`.

     Sessions without a marked problem are deleted after a while: only the last 3 are kept.
     Recordings take about 1 MB of storage per second.
  3. To copy the recording off the phone:
     - **With Android Studio** (section 3): open **View → Tool Windows → Device Explorer**, go to
       `data/data/com.arhomedecor.app/files/recordings`, right-click the file and choose
       **Save As**.
     - **Without Android Studio** (section 4): with the phone connected, list the recordings,
       then copy one into the phone's **Download** folder, using its name from the list in
       place of `session-20261112-101500-marked.mp4` (twice):
       ```
       adb shell run-as com.arhomedecor.app ls files/recordings
       adb shell "run-as com.arhomedecor.app cat files/recordings/session-20261112-101500-marked.mp4 | cat > /sdcard/Download/session-20261112-101500-marked.mp4"
       ```
       It then appears in the phone's **Files** app under **Downloads**, and can be shared from
       there. Delete it from Downloads once it has been sent.

Then open a **GitHub issue** in this repository (the **Issues** tab → **New issue**) describing
the problem.

> **Issues are public.** Screen and session recordings show the inside of your home, so never
> attach them to an issue. Send them to the developer privately instead (for example in a private
> chat, or a cloud-storage link shared only with them) and mention in the issue that you did.

## 7. Troubleshooting

| Problem | What to try |
|---|---|
| "App not installed" when installing a new `.apk` | Uninstall *AR Home Decor* first, then install again. |
| Start screen says **photo mode** | The phone doesn't support live AR (see section 1). Use another phone. |
| The camera view stays black | Close other apps that use the camera, then leave the AR screen and open it again. |
| Grids don't appear | Use more light, move the phone sideways more slowly, and point at a floor with some texture or pattern. |
| Things placed earlier disappear | ARCore lost its place. Point back at an area you scanned before and hold still for a few seconds (see the testing guide). |
| Android Studio or `adb devices` doesn't list the phone | Check that the "Allow USB debugging?" prompt was accepted, try another cable or port, and on Windows install your phone maker's USB driver (e.g. *Samsung Android USB Driver*). |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the "Allow USB debugging?" prompt. If it doesn't appear, unplug and plug in again. |
| Gradle sync failed | Check your internet connection, then **File → Sync Project with Gradle Files**. If it mentions a newer Android Gradle Plugin, update Android Studio. |
| `java`, `sdkmanager` or `adb` is "not recognized" or "not found" | Open a new terminal window: settings from the one-time setup only apply to windows opened afterwards. If it persists, repeat setup step 3 (or reinstall Java for `java`). |
| "SDK location not found" when building | The terminal doesn't know where the Android SDK is: open a new terminal window, or repeat setup step 3. |
| `Permission denied` for `./gradlew` (macOS, Linux) | Run `chmod +x gradlew` once, then try again. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app was built on another computer. Run `adb uninstall com.arhomedecor.app`, then install again. |

---

## 8. For developers

Everything above works without these steps. This section covers the project's development tools
and conventions.

### Command-line requirements

- JDK 21 and the Android SDK (`platform-tools`, `platforms;android-37.0`), with `ANDROID_HOME`
  pointing at the SDK and its licences accepted: see
  [section 4](#4-or-build-the-app-yourself-without-android-studio). The Android Gradle Plugin
  downloads the build-tools version it needs on the first build.
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

Debug builds record every live AR session from its start, using ARCore's Recording & Playback API:
the camera images, motion sensor data and the user's actions go into an MP4 in the app's private
storage (`files/recordings`). Replaying a recording runs ARCore on that data instead of the live
camera, so a problem seen once can be reproduced, and a fix compared with the behaviour before
it, without walking the room again. ARCore recomputes tracking, planes and depth on each replay,
so runs are close but not identical.

Recording from the start matters: a replay begins with an empty map, so a recording started
after a problem appeared cannot show it. **Mark problem** marks the moment (replays show a notice
there and log `problem marked`) and keeps the session as `session-…-marked.mp4`. Unmarked
sessions rotate (the last 3 are kept), and each recording stops after 5 minutes. Recordings made
before this (`rec-….mp4`) are left alone.

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
(per-plane classification changes, with the plane each floor or raised surface was measured
against and how much of it measured depth has confirmed), `ArSurfaces` (once a second per floor
and raised surface: its height, confirmed area and extent; how much of its grid lies inside
ARCore's outline or in the margin beyond it, how much measured depth confirmed or refuted, and
how much of what is on screen is drawn although this frame's depth sees something lower there),
`ArRecording`.

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
