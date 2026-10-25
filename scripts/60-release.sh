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

TAG="v3.0-$(date +%Y%m%d-%H%M)"

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
AstOs 3.0 for the Lenovo Tab 3 Essential (TB3-710F)

v3.0 — THE MOCK-FIDELITY PIXEL RELEASE (99% the approved design):

* THE SHELL IS NOW THE MOCK: every screen rebuilt to match the approved
  HTML mock point-for-point — VT323 display face + IBM Plex Mono body
  (bundled), square cards with corner tags and 2dp ink borders + hard
  offset shadows, chunky transport buttons that slide into their shadows,
  dotted-paper backdrop with drifting motes, segmented progress + volume
  bars, EQ visualizer, pixel album art, and the accent mic with square
  listening rings. 100% native Views — no WebView anywhere.
* SIX THEMES: Dune (paper default), Forest, Rust, Mono, Ember and Pine —
  swatches in Settings, everything re-skins instantly (wizard included).
* HOME IS TWO CLEAN CARDS under a big VT323 clock: media (art, track,
  progress, transport, volume) + home control (pixel tiles with ON/OFF /
  LOCKED states, night/movie/all-off scenes, app drawer) — decluttered,
  everything in frame and dp-scaled.
* SETUP WIZARD IN THE MOCK LOOK: CONNECT -> SCAN -> SEND cards, the
  AstSetup_xxxx SSID set in VT323 accent, QR on its own cream plate
  (overlaps nothing), segmented progress. The phone page got the same
  pixel design and now also sets the theme + 24-hour clock.
* KEYBOARD: the system IME restyled to the pixel keys, and every in-shell
  text field (settings, app filter, song search) uses the built-in pixel
  keyboard — the system IME is never invoked there, so it can't vanish.
* POWER: new in-shell pixel power menu (restart / power off / cancel on
  an ink scrim) alongside the restyled hardware long-press dialog.
* KIOSK: persistent shade-killer service + immersive hold — the
  notification center cannot be opened.

Carried over from 2.4: phone-driven QR setup, Discord-bot-style music
("play <song>"), Groq AI, offline Vosk voice, Home Assistant control,
weather/news, voice-model on-device download, landscape boot animation,
kernel/boot fixes.

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
    --title "AstOs 3.0 — THE MOCK-FIDELITY PIXEL SHELL — $TAG" \
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
