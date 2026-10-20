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

TAG="v2.1-$(date +%Y%m%d-%H%M)"

SUM="$(sha256sum "$ZIP" | cut -d' ' -f1)"

# Standalone kernel rescue asset (built by 50-build-rom.sh). If the flash
# succeeds but the tablet freezes at the Lenovo logo, this boot.img is the
# 2-minute fix — see FLASH.md Troubleshooting.
BOOT="$(ls AstOs-*-boot.img 2>/dev/null | head -1)"
BOOT_SUM=""
if [ -n "$BOOT" ]; then
    BOOT_SUM="$(sha256sum "$BOOT" | cut -d' ' -f1)"
    echo "Boot rescue asset: $BOOT (sha256 $BOOT_SUM)"
fi

NOTES="$(cat <<EOF
AstOs 2.1 for the Lenovo Tab 3 Essential (TB3-710F)

FIXED: the 2.0 zips' updater-script had unbalanced parentheses in the
device-assert line -> the updater exited with code 6 and TWRP showed
"Updater process ended with ERROR: 6". Nothing was ever flashed by a
broken zip (parse failure happens before any write); nothing on your
device changed if you hit it. 2.1 generates the assert natively in the
build (signed afterwards, no post-processing) and a hard parse gate in
CI makes ERROR 6 unshippable in the future.

Everything else is 2.0:
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
* Device assert accepts both TB3710F (what TWRP reports) and TB3-710F

sha256: ${SUM}
Verify yours matches after downloading (see FLASH.md Troubleshooting).

Read FLASH.md and SETUP.md before installing.
Always take a TWRP backup first.
EOF
)"

if [ -n "$BOOT" ]; then
    NOTES="${NOTES}

Standalone kernel: ${BOOT}
boot.img sha256: ${BOOT_SUM}
If the tablet freezes at the Lenovo logo after flashing, flash this file
via TWRP Install Image -> Boot partition (FLASH.md Troubleshooting)."
fi

ASSETS=("$ZIP" "docs/FLASH.md" "docs/SETUP.md")
if [ -n "$BOOT" ]; then
    ASSETS=("$BOOT" "${ASSETS[@]}")
fi

gh release create "$TAG" \
    "${ASSETS[@]}" \
    --title "AstOs 2.1 — $TAG" \
    --notes "$NOTES" \
    --target "${GITHUB_SHA:-HEAD}"

echo "Release created: $TAG"
