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

## Project layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose, ARCore) |
| `gradle/libs.versions.toml` | Dependency versions |
| `tools/` | Development helper scripts |
