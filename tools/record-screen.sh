#!/usr/bin/env bash
# Record the phone screen and turn the video into contact sheets (grids of timestamped
# frames) so behaviour over time, such as drift or flicker, can be reviewed as images.
#
# Only records this app: it refuses to start unless the app is in the foreground, and stops
# early if another app comes to the foreground during the recording.
#
# Usage: tools/record-screen.sh <seconds (max 180)> <output-dir> [frames-per-second, default 2]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/adb-common.sh"

seconds="${1:?usage: record-screen.sh <seconds> <output-dir> [fps]}"
out="${2:?usage: record-screen.sh <seconds> <output-dir> [fps]}"
fps="${3:-2}"
font="/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
remote="/sdcard/ar-home-decor-recording.mp4"

require_app_in_foreground
mkdir -p "$out"
"$adb" shell screenrecord --time-limit "$seconds" --bit-rate 8000000 "$remote" &
recorder=$!

# Stop as soon as the app leaves the foreground, so nothing else on the phone is captured.
stopped_early=""
while kill -0 "$recorder" 2>/dev/null; do
    sleep 1
    if [[ "$(foreground_package)" != "$app" ]]; then
        "$adb" shell pkill -INT screenrecord || true
        stopped_early="yes"
        break
    fi
done
wait "$recorder" || true

"$adb" pull -q "$remote" "$out/recording.mp4"
"$adb" shell rm -f "$remote"
if [[ -n "$stopped_early" ]]; then
    echo "Stopped early: $app left the foreground." >&2
fi

timestamp=""
if [[ -f "$font" ]]; then
    timestamp=",drawtext=fontfile=$font:text='%{pts\:hms}':x=10:y=10:fontsize=30:fontcolor=yellow:box=1:boxcolor=black@0.6"
fi
# 12 frames per sheet (4 columns x 3 rows), each scaled to 360 px wide.
ffmpeg -loglevel error -y -i "$out/recording.mp4" \
    -vf "fps=$fps,scale=360:-2$timestamp,tile=4x3:padding=4:color=white" \
    "$out/sheet-%03d.png"
ls "$out"
