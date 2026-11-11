#!/usr/bin/env bash
# Save a screenshot of the phone, only while this app is in the foreground.
# Usage: tools/screenshot.sh <output.png>
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/adb-common.sh"

out="${1:?usage: screenshot.sh <output.png>}"
require_app_in_foreground
"$adb" exec-out screencap -p > "$out"
echo "$out"
