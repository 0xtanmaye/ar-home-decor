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
