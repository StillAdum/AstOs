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

TAG="v2.2-$(date +%Y%m%d-%H%M)"

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
AstOs 2.2 for the Lenovo Tab 3 Essential (TB3-710F)

THE BOOT FIX. Every earlier AstOs build compiled its kernel from source,
and that kernel never boots on this hardware — the tablet froze forever at
the static Lenovo logo (the kernel died before Android ever started). The
A/B proof: the community LineageOS 14.1 build for this tablet boots fine,
and it doesn't compile a kernel at all — it ships one DUMPED FROM A REAL
TB3-710F. AstOs 2.2 now ships that exact proven kernel:

* Kernel: byte-identical to the kernel of the known-good LineageOS 14.1
  build (dumped from a real TB3-710F, pinned + double sha256-verified in CI)
* Storage: sdcardfs disabled — the proven kernel has no sdcardfs, and
  leaving it on made apps crash when writing to internal storage
* CI gate: the build now hard-fails unless boot.img carries the proven
  kernel, so a dead-kernel ROM can never ship again

Everything else is 2.1/2.0:
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
Carries the SAME proven kernel as the full ROM. Flash it alone via TWRP
Install Image -> Boot partition to test-boot the fix first — or to rescue
a logo-frozen tablet (FLASH.md Troubleshooting)."
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
