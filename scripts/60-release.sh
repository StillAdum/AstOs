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

TAG="v2.4-$(date +%Y%m%d-%H%M)"

SUM="$(sha256sum "$ZIP" | cut -d' ' -f1)"

# Standalone kernel rescue asset (built by 50-build-rom.sh). Since v2.2 it
# carries the proven dumped kernel — flash it alone to test-boot the fix
# before committing to a full ROM flash (see FLASH.md Troubleshooting).
BOOT="$(ls AstOs-*-boot.img 2>/dev/null | head -1)"
BOOT_SUM=""
if [ -n "$BOOT" ]; then
    BOOT_SUM="$(sha256sum "$BOOT" | cut -d' ' -f1)"
    echo "Boot rescue asset: $BOOT (sha256 $BOOT_SUM)"
fi

NOTES="$(cat <<EOF
AstOs 2.4 for the Lenovo Tab 3 Essential (TB3-710F)

v2.4 — THE PIXEL SHELL + PHONE-SETUP RELEASE (all 9 reported issues fixed):

* BOOT ANIMATION IS LANDSCAPE: the TB3-710F panel is a portrait-scanned
  600x1024 LCD behind ro.sf.hwrotation=270 — the old 1024x600 frames were
  center-cropped. Boot frames are now authored in landscape and shipped
  pre-rotated to the panel's native 600x1024 (desc "600 1024 20"), so the
  animation reads upright on the glass.
* THE UI IS NO LONGER ASCII: AstShell 2.4 is a clean pixel-canvas shell —
  big blocky pixel clock, warm cards, pixel-art icons, smooth sine-wave
  animations (pulses, slides, equalizer bars, spinner arcs). ASCII art
  now lives ONLY in the boot animation, where it belongs.
* DECLUTTERED HOME: four clear pages (HOME / MUSIC / ASSIST / SETUP) with
  a big readable clock, one now-playing card, one mic orb — generous
  spacing, everything laid out in dp from the measured screen so nothing
  is ever out of frame.
* KEYBOARD NO LONGER DISAPPEARS: the IME view is built exactly once and
  every shift/layer change relabels keys in place (the old full rebuild
  on every toggle is what made it vanish mid-typing).
* SETUP IS FULLY PHONE-DRIVEN: first boot opens the tablet's own hotspot
  "AstSetup_xxxx" (xxxx = the device's unique id). Connect your phone to
  it, scan the QR (or open the printed http address) and push the WHOLE
  configuration from the phone: home wifi credentials (picked from the
  tablet's live scan), city, Groq key, Home Assistant, theme. The tablet
  then joins your wifi and finishes on its own.
* THE QR CODE NO LONGER OVERLAPS ANYTHING: the pair screen is a clean
  three-card layout — CONNECT (the AstSetup_xxxx name) -> SCAN (the QR on
  its own cream plate) -> SEND — with the status line below.
* THE NOTIFICATION SHADE CAN NO LONGER BE OPENED: a persistent foreground
  KioskService now holds the StatusBarManager disable flags with a 20 s
  heartbeat (the old flags silently died with the calling binder — that's
  why the shade + its black border could still be pulled down).
* POWER MENU FIXED: warm espresso dialog (was a white Material dialog
  with cream text = unreadable). Long-press power now shows SHUT DOWN /
  REBOOT on a warm rounded card that matches the whole OS.
* WARM DIALOGS EVERYWHERE: AstShell's own dialogs (background picker,
  setup prompts) use the same espresso styling — no white boxes left.

v2.3 highlights (still in):
* Landscape locked everywhere; no lock screen, no nav bar
* Custom AstOs keyboard (IME): chunky monospace keys, digits row,
  [DEL] [HIDE] [GO], hold-DEL repeat
* Volume panel redesigned warm
* The voice model downloads on-device at the end of setup (~40 MB,
  retries + skip supported; never blocks first boot)
* Boot-fix lineage from 2.2: surfaceflinger CAP_SYS_NICE removed,
  libstlport.so shipped (sha256-pinned), sdcardfs off — the frozen-logo
  class of bugs cannot come back (CI gates it)

Discord-bot-style music ("play <song>" — free, no account), Groq AI,
weather/news cards, 12 warm palettes, animated background FX — all still
in, now on the pixel canvas.

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
Same boot.img as inside the ROM zip. Flash via TWRP Install Image ->
Boot partition for quick testing or rescue (FLASH.md Troubleshooting)."
fi

ASSETS=("$ZIP" "docs/FLASH.md" "docs/SETUP.md")
if [ -n "$BOOT" ]; then
    ASSETS=("$BOOT" "${ASSETS[@]}")
fi

gh release create "$TAG" \
    "${ASSETS[@]}" \
    --title "AstOs 2.4 — THE PIXEL SHELL — $TAG" \
    --notes "$NOTES" \
    --target "${GITHUB_SHA:-HEAD}"

echo "Release created: $TAG"

# ---- voice model mirror (one-time, ~40 MB) -----------------------------------
# The ROM's ModelDownloader tries alphacephei first, then this mirror:
#   https://github.com/StillAdum/AstOs/releases/download/vosk-model/vosk-model-small-en-us-0.15.zip
MODEL_ZIP="vosk-model-small-en-us-0.15.zip"
if ! gh release view vosk-model >/dev/null 2>&1; then
    echo "Creating the vosk-model mirror release..."
    curl -sL "https://alphacephei.com/vosk/models/$MODEL_ZIP" -o "/tmp/$MODEL_ZIP"
    if [ "$(stat -c%s "/tmp/$MODEL_ZIP" 2>/dev/null || echo 0)" -lt 30000000 ]; then
        echo "WARNING: model download looked truncated, skipping mirror" >&2
        exit 0
    fi
    gh release create vosk-model "/tmp/$MODEL_ZIP" \
        --title "AstOs voice model (vosk-model-small-en-us-0.15)" \
        --notes "Self-hosted mirror of the Vosk small en-US model. Downloaded by the ROM at the end of first-boot setup when alphacephei.com is unreachable." \
        --target "${GITHUB_SHA:-HEAD}"
    echo "Voice model mirror created."
else
    echo "Voice model mirror already present."
fi
