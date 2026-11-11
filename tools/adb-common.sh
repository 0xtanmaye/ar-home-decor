# Shared helpers for the adb scripts in this folder. Source it, don't run it.

adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
app="com.arhomedecor.app"

# Package of the app currently in the foreground on the phone.
foreground_package() {
    "$adb" shell dumpsys activity activities 2>/dev/null \
        | grep -oE 'topResumedActivity=ActivityRecord\{[^ ]+ [^ ]+ [^/ ]+' \
        | awk '{print $3}' | head -1
}

# Exits unless this app is in the foreground, so other apps on the phone are never captured.
require_app_in_foreground() {
    local current
    current="$(foreground_package)"
    if [[ "$current" != "$app" ]]; then
        echo "Not capturing: the foreground app is '${current:-unknown}', not $app." >&2
        exit 1
    fi
}
