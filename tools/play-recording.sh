#!/usr/bin/env bash
# Play an AR session recording back on the phone instead of the live camera, and optionally
# record the screen while it plays (see record-screen.sh). The app restarts first, so the
# playback starts from a fresh session. Needs a debug build and an unlocked phone.
#
# Usage: tools/play-recording.sh [--depth-debug] <recording.mp4> [screen-seconds output-dir [frames-per-second]]
#   --depth-debug  colour planes by the depth check (green on the plane, red in front, blue behind)
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
source "$here/adb-common.sh"

extras=()
if [[ "${1:-}" == "--depth-debug" ]]; then
    extras+=(--ez depthDebug true)
    shift
fi

name="$(basename "${1:?usage: play-recording.sh [--depth-debug] <recording.mp4> [screen-seconds output-dir [fps]]}")"
if ! "$adb" shell run-as "$app" ls "files/recordings/$name" >/dev/null 2>&1; then
    if [[ -f "$1" ]]; then
        # Not on the phone (e.g. a fresh install): copy it back from this machine.
        "$adb" shell run-as "$app" mkdir -p files/recordings
        "$adb" exec-in run-as "$app" sh -c "cat > files/recordings/$name" < "$1"
    else
        echo "No recording named $name on the phone." >&2
        exit 1
    fi
fi

"$adb" logcat -c
"$adb" shell am start -S -W -n "$app/.MainActivity" --es playback "$name" "${extras[@]}" >/dev/null
if [[ $# -ge 3 ]]; then
    # Give the activity a moment to reach the foreground before the capture guard checks it.
    sleep 2
    "$here/record-screen.sh" "$2" "$3" "${4:-2}"
fi
