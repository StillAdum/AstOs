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

TAG="v2.3-$(date +%Y%m%d-%H%M)"

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
AstOs 2.3 for the Lenovo Tab 3 Essential (TB3-710F)

v2.3 — THE KIOSK + SETUP RELEASE (all 13 reported issues fixed):

* Landscape locked everywhere (tablet feel, no more portrait boot)
* First-boot setup now ALWAYS runs on a fresh flash (the old gate
  defaulted to "done" — that's why you saw no setup)
* Setup gains a PAIR step: scan the QR code (or open the http://
  tablet-address:8420 page on your phone) and push Home Assistant,
  Groq key, city and theme straight from the phone
* The voice model is no longer bundled and NEVER downloads before
  setup: after WiFi + pairing the wizard says "Setup is almost
  finished, just downloading a few more things..." and fetches the
  ~40 MB offline model on-device (retries + skip supported)
* No lock screen at all, no notification shade, no nav bar, no edge
  swipe surprises — power-on goes straight to the ASCII dashboard
* Power menu redesigned: just SHUT DOWN / REBOOT, chunky terminal rows
* Volume panel redesigned: warm espresso panel, terracotta sliders,
  bigger rows and cream speaker glyphs
* Custom AstOs keyboard (IME) — chunky monospace keys, digits row,
  [DEL] [HIDE] [GO], hold-DEL repeat; selected automatically
* Big touch targets across the UI (2-row tap areas, wider arrows)
  and a larger default font; SMALL/MED/LARGE now 16/20/24 px
* No more APPS button (all-ASCII home, voice still opens apps)
* 4 new theme palettes — 12 total, incl. Phosphor, Ember Gold,
  Ice Cavern, Vaporwave (everything else from 2.2 stays)
* Build caching: ccache + Gradle/JDK toolchain persisted between
  CI runs — rebuilds get much faster after the first

Still v2.2 at heart: Every earlier AstOs build froze forever at the static Lenovo
logo. Root cause (proven by full forensics against the known-good community
LineageOS 14.1 build, which boots on this tablet): the TB3710F device tree
ships a set of pre-build patches (patches/patch.sh) that our CI never
applied. Without them:

* surfaceflinger kept the CAP_SYS_NICE file capability -> at exec it is
  AT_SECURE -> the linker SANITIZES LD_PRELOAD -> but the MTK mali blobs
  need libxlog.so/libmtk_symbols.so from LD_PRELOAD -> surfaceflinger died
  instantly -> nobody ever drew a frame -> frozen logo. (The good ROM ships
  surfaceflinger with capabilities 0x0; ours shipped 0x800000.)
* system/lib/libstlport.so was missing — the MTK display/camera blobs
  (libdpframework, lib3a, libcam*) are linked against it and the display
  pipeline dies without it (the community build documents this exact
  failure: "Fixed Android failing to boot because of missing libstlport.so").

v2.2 fixes:
* Device patches now applied in CI (system/core: CAP_SYS_NICE removal,
  MTK libnetutils bits, init ENV[64]; frameworks/av: MediaTek color
  formats, camera, mic; wpa_supplicant: NVRAM warning SSID filter)
* libstlport.so shipped — byte-identical to the known-good ROM's build,
  sha256-pinned
* sdcardfs disabled (ro.sys.sdcardfs=false) — matches the working ROM;
  leaving it on broke app writes to internal storage
* CI boot gates: the build now hard-fails unless the OTA ships
  surfaceflinger with capabilities 0x0 and the pinned libstlport.so —
  these two freeze bugs can never ship again

Everything else is 2.1/2.0:
* Full ASCII-art interface with animations: figlet clock, matrix rain,
  starfield, plasma, waves, scanlines, typewriter replies, glitch
  page transitions
* Customizable background: 6+ animated FX modes + 12 theme palettes + font
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
* Balanced multi-name device assert, generated natively + hard parse gate
  (the ERROR 6 story can never repeat)

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
    --title "AstOs 2.2 — THE BOOT FIX — $TAG" \
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
