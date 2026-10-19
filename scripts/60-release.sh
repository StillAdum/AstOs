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

TAG="v2.0-$(date +%Y%m%d-%H%M)"

NOTES="$(cat <<EOF
AstOs 2.0 — the ASCII custom OS for the Lenovo Tab 3 Essential (TB3-710F)

Base: LineageOS 14.1 core (Android 7.1.2)
NEW in 2.0:
* Full ASCII-art interface with animations: figlet clock, matrix rain,
  starfield, plasma, waves, scanlines, typewriter replies, glitch
  page transitions
* Customizable background: 7 animated FX modes + 8 theme palettes + font
  sizes + your own photo
* Discord-bot-style music: say "play <song>" or "hey spotify play <song>"
  and it searches YouTube Music on-device and streams it — free, no
  account, no Spotify Premium. Now Playing shows title, artist, album
  cover (bitmap or ASCII-art mode), progress bar, queue, controls
* Groq AI assistant: ask anything (get a free key at console.groq.com,
  paste it in setup)
* Alexa-style cards: weather, latest news headlines, date/time
* Clean custom setup wizard (AstSetup) replaces the CyanogenMod one:
  animated ASCII welcome, wifi, city, Groq key, look — no Android look
* FIXED: E3004 flash error — the zip now accepts TB3710F (what TWRP
  reports) as well as TB3-710F

Read FLASH.md and SETUP.md before installing.
Always take a TWRP backup first.
EOF
)"

gh release create "$TAG" \
    "$ZIP" \
    "docs/FLASH.md" \
    "docs/SETUP.md" \
    --title "AstOs 2.0 — $TAG" \
    --notes "$NOTES" \
    --target "${GITHUB_SHA:-HEAD}"

echo "Release created: $TAG"
