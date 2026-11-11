#!/usr/bin/env bash
# Compile Filament material sources (app/src/main/materials/*.mat) into the
# .filamat packages the app loads from assets. Run after editing any .mat file.
#
# Uses matc from the official Filament release that matches the Filament version
# in gradle/libs.versions.toml, verified against the release's published SHA-256.
set -euo pipefail

FILAMENT_VERSION="1.77.3"
# SHA-256 of filament-v$FILAMENT_VERSION-linux.tgz as published on the GitHub release.
FILAMENT_LINUX_SHA256="6d3391197dcbdec318dd8a7f4e8d6fd0766794231d53c756a1565fd92bba31b2"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

toml_version="$(sed -nE 's/^filament = "([^"]+)"/\1/p' "$root/gradle/libs.versions.toml")"
if [[ "$toml_version" != "$FILAMENT_VERSION" ]]; then
    echo "Filament version mismatch: libs.versions.toml has '$toml_version', this script pins '$FILAMENT_VERSION'." >&2
    echo "Materials must be compiled with the same version the app uses. Update FILAMENT_VERSION" >&2
    echo "and FILAMENT_LINUX_SHA256 (from the GitHub release page) together." >&2
    exit 1
fi

cache="${XDG_CACHE_HOME:-$HOME/.cache}/ar-home-decor/filament-$FILAMENT_VERSION"
matc="$cache/filament/bin/matc"
if [[ ! -x "$matc" ]]; then
    mkdir -p "$cache"
    archive="$cache/filament-v$FILAMENT_VERSION-linux.tgz"
    echo "Downloading matc (Filament $FILAMENT_VERSION)..."
    curl -fL --retry 3 -o "$archive" \
        "https://github.com/google/filament/releases/download/v$FILAMENT_VERSION/filament-v$FILAMENT_VERSION-linux.tgz"
    echo "$FILAMENT_LINUX_SHA256  $archive" | sha256sum -c -
    tar -xzf "$archive" -C "$cache" filament/bin/matc
    rm "$archive"
fi

src="$root/app/src/main/materials"
out="$root/app/src/main/assets/materials"
mkdir -p "$out"
for mat in "$src"/*.mat; do
    name="$(basename "$mat" .mat)"
    "$matc" --platform mobile --api opengl -o "$out/$name.filamat" "$mat"
    echo "compiled $name.filamat"
done
