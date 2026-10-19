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
test -f "$ROMDIR/vendor/astos/overlay/frameworks/base/core/res/res/drawable-nodpi/default_wallpaper.png"

# ---- sepolicy: union the CM global policy layer -------------------------------
# LineageOS's build/core/config.mk only does
#   BOARD_SEPOLICY_DIRS += vendor/cm/sepolicy
# inside `ifneq ($(CM_BUILD),)` — CM_BUILD is exported by breakfast/brunch.
# We build with plain `lunch astos_*`, so it never gets set and the union
# is silently skipped -> checkpolicy dies with
#   untrusted_app.te: ERROR 'unknown type exfat'
# because system/sepolicy references exfat/ntfs types that are declared in
# vendor/cm/sepolicy/file.te. Replicate the inclusion ourselves, keeping the
# LineageOS ordering (vendor/cm/sepolicy AFTER the device policy dirs).
CM_SEPOLICY="$ROMDIR/vendor/cm/sepolicy"
test -f "$CM_SEPOLICY/file.te" || { echo "FATAL: $CM_SEPOLICY/file.te missing — is vendor/cm synced?"; exit 1; }
grep -q "type exfat" "$CM_SEPOLICY/file.te" || { echo "FATAL: exfat type missing in vendor/cm/sepolicy/file.te"; exit 1; }

BC="$ROMDIR/device/lenovo/TB3710F/BoardConfig.mk"
if [ -f "$BC" ] && ! grep -q "vendor/cm/sepolicy" "$BC"; then
    cat >> "$BC" <<'EOF'

# AstOs build patch: union the CM global sepolicy into the policy.
# Normally included by build/core/config.mk when CM_BUILD is set (the
# breakfast/brunch flow); the plain `lunch astos_*` flow used by AstOs CI
# does not set it, and system/sepolicy references the exfat / ntfs types
# declared only in vendor/cm/sepolicy.
BOARD_SEPOLICY_DIRS += vendor/cm/sepolicy
EOF
    echo "BoardConfig.mk patched: BOARD_SEPOLICY_DIRS += vendor/cm/sepolicy"
fi
grep -n "BOARD_SEPOLICY_DIRS" "$BC" | tail -5

# ---- free the space the app build used --------------------------------------
sudo rm -rf /usr/local/lib/android/sdk "$HOME/.gradle" /tmp/gradle.zip /tmp/vosk-model-small-en-us-0.15.zip 2>/dev/null || true

echo "::group::Disk after embed"
df -h / /mnt || true
echo "::endgroup::"
echo "AstOs embedded into ROM tree OK"
