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

v3.1 - THE COMPANION + MODS RELEASE:

SETUP BUGS FIXED (all reported ones):
* Every setup button actually taps now - the hit-rect engine treated
  (x, y, width, height) as (left, top, right, bottom), so every button
  after the welcome screen ("no phone? set up wifi on the tablet", the
  wifi list, RESCAN, SKIP, RETRY, CONTINUE, ENTER ASTOS) was an inverted
  dead rectangle. Fixed + a 140ms tap-flash so taps feel acknowledged.
* The hotspot SSID no longer broadcasts with quote characters: the AP
  config now carries the SSID unquoted (Android 7.1 passes the stored
  value to hostapd as-is) and the display always strips quotes.
* The setup URL is now plain http://<tablet-ip> (port 80, with 8420 as
  fallback) and the OS connectivity probes (Android /generate_204,
  iOS /hotspot-detect.html, Windows /ncsi.txt) now return the setup page
  itself - so the phone usually opens the setup form BY ITSELF the
  moment it joins the hotspot ("sign in to network"), no URL typing.
* The QR self-heals: if the tablet's softap address was slow to settle,
  the code + QR now refresh to the real address instead of freezing on
  the 192.168.43.1 guess.
* The phone page now tells you WHY a send fails (turn off mobile data -
  the tablet has no internet) with a retry button.

NEW - AstOS COMPANION (the Windows PC app):
* Pair over Bluetooth or USB from the PC and run the WHOLE setup there:
  wifi credentials, city, Groq key, Home Assistant, theme.
* Manage the tablet afterwards: battery/storage/wifi dashboard, toggle
  kiosk/screen/voice, reboot/recovery/power-off, wake the screen.
* ROM updates from the app: pick the AstOs .zip (the TWRP-flashable
  one), the app transfers it, stages the TWRP auto-flash script and
  reboots the tablet into recovery to apply it. USB for speed, Bluetooth
  with resume when no cable is around.
* Built from the same dune/pixel design language as the OS, with the
  same chunky buttons, dotted paper and animations.

NEW - .astpl PLUGINS (mod the whole system):
* Drop .astpl files in /sdcard/AstOs/plugins and scan in Settings ->
  Mods, or install them from the PC companion.
* JavaScript mods with a permission system (ui / settings / ha / media /
  voice / network / files / system). The "system" grant gives REAL
  whole-OS access: shell commands, system properties, secure settings
  and input injection - gated behind a master switch + per-plugin opt-in.
* Plugins add cards to the home dashboard, react to voice, tick on a
  schedule, call Home Assistant, and store their own data. Sandboxed:
  no Java access, watchdogged, crash-isolated from the shell.
* Example mods ship in the release (uptime card, battery guard, and a
  system-grant demo that shows the whole-OS access working).

BOOT SCREEN: the animation caption is now stamped from scripts/VERSION
on every ROM build - the boot screen can never show a stale version
again (this release boots saying "AstOs 3.1 - booting").

Carried over from 3.0: the mock-fidelity pixel shell (native Views, no
WebView), six themes, two-card home, pixel keyboard + in-shell IME,
pixel power menu, kiosk shade-killer, phone-driven QR setup, Discord-bot
music, Groq AI, offline Vosk voice, Home Assistant, weather/news.

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

ASSETS=("$ZIP" "docs/FLASH.md" "docs/SETUP.md" "docs/COMPANION.md" "docs/PLUGINS.md")
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
    --title "AstOs ${ASTOS_VERSION} — companion app + mods + setup fixes — $TAG" \
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
