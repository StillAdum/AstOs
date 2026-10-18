#!/usr/bin/env bash
# 60-release.sh — publish the ROM as a GitHub Release with the docs.
set -e

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO_ROOT"

ZIP="$(ls AstOs-*.zip 2>/dev/null | head -1)"
if [ -z "$ZIP" ]; then
    echo "FATAL: no ROM zip found" >&2
    exit 1
fi
echo "Releasing: $ZIP ($(du -h "$ZIP" | cut -f1))"

TAG="v1.0-$(date +%Y%m%d-%H%M)"

NOTES="$(cat <<EOF
AstOs 1.0 — home assistant ROM for the Lenovo Tab 3 Essential (TB3-710F)

Base: LineageOS 14.1 (Android 7.1.2)
* AstShell home dashboard (warm, minimal, no blue)
* Offline voice control (Vosk) — no Google account needed
* Home Assistant REST control (lights / switches / scenes)
* Media transport control — Spotify free works
* Built-in local music player for your SD card music
* zram swap enabled for the 1 GB RAM device
* Fully offline: no Google apps bundled

Read FLASH.md and SETUP.md before installing.
Always take a TWRP backup first.
EOF
)"

gh release create "$TAG" \
    "$ZIP" \
    "docs/FLASH.md" \
    "docs/SETUP.md" \
    --title "AstOs 1.0 — $TAG" \
    --notes "$NOTES" \
    --target "${GITHUB_SHA:-HEAD}"

echo "Release created: $TAG"
