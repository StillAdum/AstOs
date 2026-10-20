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

# ---- OTA device assert (E3004 flash failure) ----------------------------------
# TWRP for this tablet reports ro.product.device=TB3710F (tree codename),
# while the stock OTA assert checks TB3-710F (marketing name) — either alone
# aborts the other with E3004. Fix it at the SOURCE: patch the tree's OTA
# script generator so `mka bacon` natively emits a balanced multi-name assert
# (signed afterwards as normal). NEVER rewrite the signed zip after bacon —
# a hand-written assert once shipped unbalanced parens -> updater exit 6 ->
# TWRP "Updater process ended with ERROR: 6" (nothing flashed, but confusing).
# scripts/check_updater_script.py gates the finished zip in 50-build-rom.sh.
EG="$ROMDIR/build/tools/releasetools/edify_generator.py"
test -f "$EG" || { echo "FATAL: $EG missing — is the tree synced?"; exit 1; }
python3 - "$EG" <<'PYEOF'
import re, sys
path = sys.argv[1]
src = open(path).read()
new_fn = '''  def AssertDevice(self, device):
    """AstOs: balanced multi-name device assert. TWRP reports TB3710F,
    the device tree ships TB3-710F - accept both. Syntax here is
    load-bearing: an unbalanced assert makes the updater exit 6 (parse
    errors) and TWRP refuses to flash. The finished zip is verified by
    scripts/check_updater_script.py in 50-build-rom.sh."""
    cmd = ('assert('
           'getprop("ro.product.device") == "TB3710F" || '
           'getprop("ro.build.product") == "TB3710F" || '
           'getprop("ro.product.device") == "TB3-710F" || '
           'getprop("ro.build.product") == "TB3-710F" || '
           'abort("E3004: This package is for device: TB3710F / TB3-710F; '
           'this device is " + getprop("ro.product.device") + ".");'
           ');')
    self.script.append(cmd)
'''
pat = re.compile(r'  def AssertDevice\(self, device\):.*?(?=\n  def AssertSomeBootloader)', re.S)
if not pat.search(src):
    sys.exit("FATAL: AssertDevice method not found in edify_generator.py")
if 'AstOs: balanced multi-name device assert' in src:
    print("edify_generator.py: AssertDevice already patched")
else:
    open(path, "w").write(src[:pat.search(src).start()] + new_fn + src[pat.search(src).end():])
    print("edify_generator.py AssertDevice patched (multi-name, balanced)")
PYEOF
python3 -c "import ast,sys; ast.parse(open(sys.argv[1]).read())" "$EG" \
    || { echo "FATAL: patched edify_generator.py is not valid python" >&2; exit 1; }
# The OTA packaging step (ota_from_target_files) runs under python2, which
# rejects any non-ASCII byte without an encoding declaration - run #21 died
# exactly there (SyntaxError: Non-ASCII character '\xe2') because the patched
# docstring contained an em-dash. Gate on pure ASCII so this can never recur.
# NOTE: the -c snippet must import sys itself (run #22 died here with NameError:
# sys is not defined - sys.argv is NOT pre-imported in `python3 -c`).
python3 -c "import sys; open(sys.argv[1], encoding='ascii').read()" "$EG" \
    || { echo "FATAL: patched edify_generator.py is not pure ASCII - the OTA packaging parses it with python2" >&2; exit 1; }
grep -c 'TB3710F' "$EG" >/dev/null
echo "AssertDevice patch OK"

# ---- cmsdk ordering patch ------------------------------------------------------
# Run #13 died at 43%: "Building with Jack: org.cyanogenmod.platform.internal"
# failed with "Property 'jack.source': ... org.cyanogenmod.platform-res ...
# R.java does not exist". The jack compiles of the CMSDK modules consume
# R.java / Manifest.java generated by the org.cyanogenmod.platform-res AAPT
# step (its src/R.stamp rule). Upstream orders this implicitly via the
# apicheck task (droidcore: checkapi-cm -> cm-api-stubs -> platform ->
# R.stamp); AstOs builds with WITHOUT_CHECK_API=true (a dev-time API-drift
# check that is irrelevant for a ROM and broken in this device tree), so
# that ordering path is absent and ninja raced the two. Also, the upstream
# Android.mk's " $(full_target): ..." ordering lines sit BEFORE their
# include $(BUILD_*) (leftover-variable quirk) and never attach to the
# .internal module. Attach explicit, correct edges.
CMSDK_MK="$ROMDIR/vendor/cmsdk/Android.mk"
if [ -f "$CMSDK_MK" ] && ! grep -q "cm_astos_platres_stamp" "$CMSDK_MK"; then
    cat >> "$CMSDK_MK" <<'EOF'

# AstOs CI build fix: deterministic ordering for the CMSDK jack modules.
# The jack compiles of org.cyanogenmod.platform(.sdk/.internal) consume
# R.java / Manifest.java generated by the org.cyanogenmod.platform-res
# AAPT step (src/R.stamp). Upstream relies on the apicheck task chain
# (droidcore: checkapi-cm) to order these; this build disables that
# dev-time check (WITHOUT_CHECK_API=true), so add the edges directly.
cm_astos_platres_stamp := $(call intermediates-dir-for,APPS,org.cyanogenmod.platform-res,,COMMON)/src/R.stamp
$(call intermediates-dir-for,JAVA_LIBRARIES,org.cyanogenmod.platform,,COMMON)/classes.jack: $(cm_astos_platres_stamp)
$(call intermediates-dir-for,JAVA_LIBRARIES,org.cyanogenmod.platform.sdk,,COMMON)/classes.jack: $(cm_astos_platres_stamp)
$(call intermediates-dir-for,JAVA_LIBRARIES,org.cyanogenmod.platform.internal,,COMMON)/classes.jack: $(cm_astos_platres_stamp)
EOF
    echo "vendor/cmsdk/Android.mk patched with R.stamp ordering edges"
fi
grep -n "cm_astos_platres_stamp" "$CMSDK_MK" | head -5

# ---- wallpaper: replace the AOSP base resource directly ----------------------
# aapt's `-c hdpi-v4` flavor filter (PRODUCT_AAPT_PREF_CONFIG=hdpi) strips
# density-flavored overlay files (x*hdpi) and the plain-nodpi overlay file
# never replaces the base one in the final framework-res.apk — only the
# hdpi/sw600dp/sw720dp flavors survive from the overlay. On TB3-710F
# (ro.sf.lcd_density=160, 1024x600 => sw600dp) the runtime resolves
# sw600dp-nodpi => our warm cream already. This direct base-file override
# makes EVERY remaining flavor ours as well, so the warm look is guaranteed
# under any config resolution.
FWRES_RES="$ROMDIR/frameworks/base/core/res/res"
for f in drawable-nodpi drawable-sw600dp-nodpi drawable-sw720dp-nodpi; do
    SRC="$REPO_ROOT/vendor/astos/overlay/frameworks/base/core/res/res/$f/default_wallpaper.png"
    DST="$FWRES_RES/$f/default_wallpaper.png"
    if [ -f "$DST" ]; then
        cp "$SRC" "$DST"
        echo "base wallpaper replaced: $f"
    fi
done

# ---- debrand: the AstOs first boot, not the Lineage/Android one ---------------
# vendor/cm/config/common.mk ships LineageSetupWizard (the stock first-boot
# wizard that makes the tablet look like "just another Android") and Trebuchet
# (a second home app, which triggers the "complete action using" chooser).
# AstOs replaces both: AstShell is the only HOME and its own SetupActivity
# (inside AstShell) runs the first-boot setup instead. Both entries are single
# words inside backslash-continued PRODUCT_PACKAGES lists, so blanking just
# the word keeps the make syntax valid and drops the module from the image.
CM_COMMON_MK="$ROMDIR/vendor/cm/config/common.mk"
test -f "$CM_COMMON_MK" || { echo "FATAL: $CM_COMMON_MK missing — is vendor/cm synced?"; exit 1; }
sed -i 's/\bLineageSetupWizard\b//g; s/\bTrebuchet\b//g' "$CM_COMMON_MK"
if grep -qE "\bLineageSetupWizard\b|\bTrebuchet\b" "$CM_COMMON_MK"; then
    echo "FATAL: debrand strip failed — first-boot would show the Lineage wizard" >&2
    exit 1
fi
echo "Debranded first boot: LineageSetupWizard + Trebuchet stripped from CM common.mk"

# ---- prebuilt kernel: the proven zImage dumped from a real TB3-710F -----------
# andrew264's kernel source compiles, but the resulting zImage does NOT boot
# on real MT8127 hardware — the tablet freezes at the static Lenovo logo
# (the kernel dies before userspace ever starts). The working LineageOS 14.1
# build for this device (aptivi-mobile, 2023-03-23) ships a kernel DUMPED
# FROM A REAL TB3-710F instead (commit 7b762eb91 "Added zImage dumped from
# TB3-710F"). We pin the exact same binary. The kernel repo is not synced
# (local_manifests), so vendor/cm/build/tasks/kernel.mk takes its
# TARGET_PREBUILT_KERNEL path and copies this file to $(PRODUCT_OUT)/kernel,
# which device/lenovo/TB3710F/mkbootimg.mk then MTK-wraps for boot.img and
# recovery.img. Byte identity is enforced by scripts/check_bootimg_kernel.py.
DEVDIR="$ROMDIR/device/lenovo/TB3710F"
test -f "$DEVDIR/BoardConfig.mk" || { echo "FATAL: device tree missing at $DEVDIR"; exit 1; }

ZIMG_URL="https://raw.githubusercontent.com/aptivi-mobile/device_lenovo_TB3710F/7b762eb91f053a119977057fc3b5c5ac73092c9b/zImage"
ZIMG_SHA="10773efaa0bd51b9de663ca99253524815a193df0c127a46c61975b78897ccb3"
RAW_SHA="f862c0417d0aa984b74b1794b56f95c304a12a747097d74e7f44ea726a6c0450"
RAW_SIZE=3773648

curl -fsSL --retry 5 --retry-all-errors "$ZIMG_URL" -o "$DEVDIR/zImage.dumped" \
    || { echo "FATAL: could not download the proven zImage"; exit 1; }
echo "$ZIMG_SHA  $DEVDIR/zImage.dumped" | sha256sum -c - \
    || { echo "FATAL: dumped zImage sha256 mismatch — upstream artifact changed!"; exit 1; }
# The upstream file carries the 512-byte MTK KERNEL header; our mkbootimg.mk
# (make_header) adds exactly one such header itself, so strip it and feed the
# build the RAW zImage.
od -A n -t x1 -N 4 "$DEVDIR/zImage.dumped" | tr -d ' \n' | grep -q '^88168858$' \
    || { echo "FATAL: dumped zImage lacks the MTK KERNEL magic"; exit 1; }
dd if="$DEVDIR/zImage.dumped" of="$DEVDIR/zImage.raw" bs=512 skip=1 status=none
[ "$(stat -c %s "$DEVDIR/zImage.raw")" -eq "$RAW_SIZE" ] \
    || { echo "FATAL: raw kernel size mismatch"; exit 1; }
echo "$RAW_SHA  $DEVDIR/zImage.raw" | sha256sum -c - \
    || { echo "FATAL: raw kernel sha256 mismatch"; exit 1; }
# ARM zImage magic 0x016f2818 (little-endian: 18 28 6f 01) at offset 0x24
od -A n -t x1 -j 36 -N 4 "$DEVDIR/zImage.raw" | tr -d ' \n' | grep -q '^18286f01$' \
    || { echo "FATAL: stripped file is not an ARM zImage"; exit 1; }
mv "$DEVDIR/zImage.raw" "$DEVDIR/zImage"
rm -f "$DEVDIR/zImage.dumped"
ls -l "$DEVDIR/zImage"

# Wire it: TARGET_PREBUILT_KERNEL (honored by kernel.mk because the kernel
# source tree is intentionally absent).
if ! grep -q "TARGET_PREBUILT_KERNEL" "$BC"; then
    cat >> "$BC" <<'EOF'

# AstOs fix: use the kernel dumped from a real TB3-710F. The kernel compiled
# from andrew264's source freezes the tablet at the Lenovo logo on real
# hardware; this prebuilt is byte-identical to the one shipped by the
# working LineageOS 14.1 build (aptivi-mobile 7b762eb91). The kernel repo
# is intentionally not synced, so kernel.mk takes the TARGET_PREBUILT_KERNEL
# path instead of compiling the broken source.
TARGET_PREBUILT_KERNEL := $(DEVICE_DIR)/zImage
EOF
    echo "BoardConfig.mk patched: TARGET_PREBUILT_KERNEL wired"
fi
grep -n "TARGET_PREBUILT_KERNEL" "$BC"

# ---- sdcardfs: the proven kernel has no sdcardfs -------------------------------
# Aptivi c33d4578d: "Don't enable sdcardfs! It won't work and will cause apps
# to fail to write data to the internal storage, causing some of them to FC!
# It's not compatible with the TB3-710F."
DM="$DEVDIR/device.mk"
if grep -q "ro.sys.sdcardfs=true" "$DM"; then
    sed -i 's/ro\.sys\.sdcardfs=true/ro.sys.sdcardfs=false/' "$DM"
    echo "device.mk patched: ro.sys.sdcardfs=false"
fi
grep -n "ro.sys.sdcardfs" "$DM" || echo "::warning::no sdcardfs prop found (device tree changed?)"

# ---- free the space the app build used --------------------------------------
sudo rm -rf /usr/local/lib/android/sdk "$HOME/.gradle" /tmp/gradle.zip /tmp/vosk-model-small-en-us-0.15.zip 2>/dev/null || true

echo "::group::Disk after embed"
df -h / /mnt || true
echo "::endgroup::"
echo "AstOs embedded into ROM tree OK"

# ---- AstSetup: embed the first-boot wizard ------------------------------------
APK2="$REPO_ROOT/astsetup/app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$APK2" ]; then
    echo "FATAL: AstSetup APK missing — run 30-build-astshell.sh first" >&2
    exit 1
fi
mkdir -p "$ROMDIR/vendor/astos/AstSetup"
cp "$APK2" "$ROMDIR/vendor/astos/AstSetup/AstSetup.apk"
ls -lh "$ROMDIR/vendor/astos/AstSetup/AstSetup.apk"

# ---- replace the CM setup wizard with AstOs's own ------------------------------
# The user-facing first boot must be the clean ASCII AstOs wizard, not the
# CyanogenMod one. AstSetup (platform-signed, privileged) marks the device
# provisioned itself on completion (device_provisioned + user_setup_complete
# + astos_setup_done), so removing CMSetupWizard from the build is safe.
for MK in "$ROMDIR"/vendor/cm/config/*.mk "$ROMDIR"/device/lenovo/TB3710F/*.mk; do
    [ -f "$MK" ] || continue
    if grep -q "CMSetupWizard" "$MK"; then
        sed -i '/CMSetupWizard/d' "$MK"
        echo "CMSetupWizard removed: $MK"
    fi
done
LEFT=$(grep -rln "CMSetupWizard" "$ROMDIR/vendor/cm/config/" "$ROMDIR/device/lenovo/TB3710F/" 2>/dev/null | head -3 || true)
if [ -n "$LEFT" ]; then
    echo "WARNING: CMSetupWizard still referenced in: $LEFT"
fi
