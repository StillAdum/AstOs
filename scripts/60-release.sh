#!/usr/bin/env bash
# 60-release.sh — publish the ROM as a GitHub Release with the docs.
set -e

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO_ROOT"

# v3.1: single-sourced version (scripts/VERSION) — same file the boot
# animation, the ROM zip name and the release title read.
ASTOS_VERSION="$(cat scripts/VERSION 2>/dev/null | tr -d '[:space:]')"
[ -n "$ASTOS_VERSION" ] || ASTOS_VERSION="3.1"

ZIP="$(ls AstOs-*.zip 2>/dev/null | head -1)"
if [ -z "$ZIP" ]; then
    echo "FATAL: no ROM zip found" >&2
    exit 1
fi
echo "Releasing: $ZIP ($(du -h "$ZIP" | cut -f1))"

TAG="v${ASTOS_VERSION}-$(date +%Y%m%d-%H%M)"

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
AstOs ${ASTOS_VERSION} for the Lenovo Tab 3 Essential (TB3-710F)

v3.4 — cable-free pairing, pixel UI and real media feedback

* Direct encrypted LAN discovery/pairing/reconnection; optional USB bootstrap.
* Native Windows Bluetooth RFCOMM, independent of virtual COM ports.
* Cross-app Wi-Fi configuration permission and scan/association/error-state corrections.
* Close/minimize Companion to tray; background-safe Spotify long polling.
* PC-first song requests, actual cover art, optional real PC audio levels and local audio-session capture.
* Theme-aware pixel keyboard/dialogs, improved text contrast, keyboard-icon prompt and expandable timed response line.
* Persistent custom backdrop builder and disabled window entrance animations.
* ROM navigation boot-property conflict fixed, with AstOS pixel Back/Home icons.

Tests/builds do not certify physical tablet radios, audio or live Spotify behavior.
Read FOLLOWUP-3.4.md for required matching versions, setup and validation limits.
Back up in TWRP before flashing.

sha256: ${SUM}
Verify yours matches after downloading (see FLASH.md Troubleshooting).

Read FLASH.md, SETUP.md, COMPANION.md and PLUGINS.md before installing.
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

ASSETS=("$ZIP" "docs/FLASH.md" "docs/SETUP.md" "docs/COMPANION.md" "docs/PLUGINS.md" "docs/PC-PLAYBACK.md" "docs/FIX-VALIDATION.md" "docs/AI-PROVIDERS.md" "docs/VALIDATION-3.3.md" "docs/FOLLOWUP-3.4.md" "spicetify/astos.js")
if [ -n "$BOOT" ]; then
    ASSETS=("$BOOT" "${ASSETS[@]}")
fi

# v3.1: the PC companion installer built by the windows CI job and
# downloaded into this dir (any .exe files in it are attached).
COMPANION_DIR="${COMPANION_DIR:-}"
if [ -n "$COMPANION_DIR" ] && [ -d "$COMPANION_DIR" ]; then
    for f in "$COMPANION_DIR"/*.exe; do
        [ -f "$f" ] || continue
        ASSETS=("${ASSETS[@]}" "$f")
        echo "Attaching companion installer: $f"
    done
else
    echo "NOTE: no companion dir provided — release without the PC app" >&2
fi

# v3.1: bundle the example .astpl mods as one release asset
MODZIP="AstOs-example-mods-${ASTOS_VERSION}.zip"
if ls examples/plugins/*.astpl >/dev/null 2>&1; then
    rm -f "$MODZIP"
    ( cd examples/plugins && zip -q -j "../../$MODZIP" *.astpl )
    ASSETS=("${ASSETS[@]}" "$MODZIP")
    echo "Attaching example mods: $MODZIP"
fi

gh release create "$TAG" \
    "${ASSETS[@]}" \
    --title "AstOs ${ASTOS_VERSION} — setup + providers + media — $TAG" \
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
