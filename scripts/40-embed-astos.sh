#!/usr/bin/env bash
# 40-embed-astos.sh — copy AstOs vendor bits + AstShell APK into the ROM tree,
# then reclaim the disk used by the SDK/Gradle.
set -e

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
ROMDIR="${ROMDIR:-/mnt/rom}"

# vendor/astos -> $ROMDIR/vendor/astos  (products, bootanim, wallpaper, rc, mk)
mkdir -p "$ROMDIR/vendor/astos"
rsync -a --delete "$REPO_ROOT/vendor/astos/" "$ROMDIR/vendor/astos/"

# AstShell APK as prebuilt
APK="$REPO_ROOT/astshell/app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$APK" ]; then
    echo "FATAL: AstShell APK missing — run 30-build-astshell.sh first" >&2
    exit 1
fi
mkdir -p "$ROMDIR/vendor/astos/AstShell"
cp "$APK" "$ROMDIR/vendor/astos/AstShell/AstShell.apk"
ls -lh "$ROMDIR/vendor/astos/AstShell/AstShell.apk"

# sanity: boot animation + wallpaper present
test -f "$ROMDIR/vendor/astos/bootanimation/bootanimation.zip"
test -f "$ROMDIR/vendor/astos/overlay/frameworks/base/core/res/res/drawable-nodpi/default_wallpaper.jpg"

# ---- free the space the app build used --------------------------------------
sudo rm -rf /usr/local/lib/android/sdk "$HOME/.gradle" /tmp/gradle.zip /tmp/vosk-model-small-en-us-0.15.zip 2>/dev/null || true

echo "::group::Disk after embed"
df -h / /mnt || true
echo "::endgroup::"
echo "AstOs embedded into ROM tree OK"
