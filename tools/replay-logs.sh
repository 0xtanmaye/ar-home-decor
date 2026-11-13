#!/usr/bin/env bash
# Replay AR session recordings one after another and save each replay's log, to compare a change
# with the build before it (see compare-replays.py). Nothing is captured from the screen.
#
# Replays are not identical run to run (ARCore recomputes tracking, planes and depth), so replay
# each recording a few times, with the same recordings for both builds.
#
# Needs a debug build, an unlocked phone that is free to use, and the recordings on the phone or
# on this machine (they are copied over if missing). Stops if the app leaves the foreground.
#
# Usage: tools/replay-logs.sh [--times N] <output-dir> <recording.mp4>...
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
source "$here/adb-common.sh"

times=1
if [[ "${1:-}" == "--times" ]]; then
    times="${2:?usage: replay-logs.sh [--times N] <output-dir> <recording.mp4>...}"
    shift 2
fi
out="${1:?usage: replay-logs.sh [--times N] <output-dir> <recording.mp4>...}"
shift
[[ $# -gt 0 ]] || { echo "usage: replay-logs.sh [--times N] <output-dir> <recording.mp4>..." >&2; exit 1; }
mkdir -p "$out"

tags=(ArStats:I ArEvents:I ArPlanes:I ArSurfaces:I ArRecording:I ArActivity:I ArRenderer:I AndroidRuntime:E)
max_wait_seconds=600

for recording in "$@"; do
    name="$(basename "$recording" .mp4)"
    for run in $(seq 1 "$times"); do
        log="$out/$name-$run.log"
        # Only lines from now on: the phone's clock, quoted as one command for the phone's shell.
        since="$("$adb" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
        "$adb" logcat -v time -T "$since" -s "${tags[@]}" > "$log" &
        logger=$!
        "$here/play-recording.sh" "$recording"
        sleep 3 # let the app reach the foreground
        result="timed out"
        for _ in $(seq "$max_wait_seconds"); do
            if grep -q "playback finished" "$log"; then
                result="done"
                break
            fi
            if [[ "$(foreground_package)" != "$app" ]]; then
                result="stopped: $app left the foreground"
                break
            fi
            sleep 1
        done
        sleep 2
        kill "$logger" 2>/dev/null || true
        echo "$name run $run: $result ($(wc -l < "$log") log lines)"
        [[ "$result" == done ]] || exit 1
    done
done
