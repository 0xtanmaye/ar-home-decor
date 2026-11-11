#!/usr/bin/env bash
# Copy AR session recordings from the phone to recordings/ (ignored by git: they are video of
# real rooms). Recordings live in the app's private storage, which adb can read only for debug
# builds. Files already copied are skipped.
#
# Usage: tools/pull-recordings.sh [output-dir, default recordings]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/adb-common.sh"

out="${1:-$(dirname "${BASH_SOURCE[0]}")/../recordings}"
mkdir -p "$out"
mapfile -t names < <("$adb" shell run-as "$app" ls files/recordings 2>/dev/null | tr -d '\r' | grep '\.mp4$' || true)
if [[ ${#names[@]} -eq 0 ]]; then
    echo "No recordings on the phone." >&2
    exit 0
fi
for name in "${names[@]}"; do
    if [[ -s "$out/$name" ]]; then
        echo "already copied: $name"
        continue
    fi
    "$adb" exec-out run-as "$app" cat "files/recordings/$name" > "$out/$name.part"
    mv "$out/$name.part" "$out/$name"
    echo "copied: $name ($(du -h "$out/$name" | cut -f1))"
done
