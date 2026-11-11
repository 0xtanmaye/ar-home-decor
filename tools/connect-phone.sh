#!/usr/bin/env bash
# Connect adb to phones that have Wireless debugging turned on.
#
# adb's built-in discovery only listens on the default network interface, so it
# misses phones reachable through a second adapter (e.g. a VM's bridged adapter).
# This searches on every interface and runs `adb connect` for each phone found.
# The phone must have been paired once with `adb pair`.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"

mapfile -t local_ips < <(ip -4 -o addr show scope global | awk '{split($4, a, "/"); print a[1]}')
found="$(python3 -I "$here/find_adb_devices.py" "${local_ips[@]}")"

if [[ -z "$found" ]]; then
    echo "No phone found. Check that Wireless debugging is on and the phone is on the same Wi-Fi." >&2
    exit 1
fi

while read -r address; do
    "$adb" connect "$address"
done <<< "$found"

"$adb" devices -l
