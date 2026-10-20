#!/usr/bin/env bash
# 50-build-rom.sh — build the AstOs ROM (LineageOS 14.1 base, TB3-710F).
# Java 8 is required by the 14.1 build system (Jack toolchain).
set -e

ROMDIR="${ROMDIR:-/mnt/rom}"

# ---- JDK 8 -------------------------------------------------------------------
if [ ! -d "$HOME/jdk8" ]; then
    echo "Downloading Temurin 8…"
    mkdir -p "$HOME/jdk8tmp"
    curl -sL "https://api.adoptium.net/v3/binary/latest/8/ga/linux/x64/jdk/hotspot/normal/eclipse" \
        | tar -xz -C "$HOME/jdk8tmp"
    mv "$(echo $HOME/jdk8tmp/jdk8*)" "$HOME/jdk8"
fi
export JAVA_HOME="$HOME/jdk8"
export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1

# ---- Jack server TLS fix ------------------------------------------------------
# Newer JDK 8 builds disable TLSv1/TLSv1.1 (needed by the 2017 jack server):
# "SSL error when connecting to the Jack server". Re-enable them.
JS="$JAVA_HOME/jre/lib/security/java.security"
[ -f "$JS" ] || JS="$JAVA_HOME/lib/security/java.security"
if [ -f "$JS" ]; then
    sed -i 's/SSLv3, TLSv1, TLSv1.1, //g; s/TLSv1, TLSv1.1, //g; s/, TLSv1.1//g; s/, TLSv1//g' "$JS"
    echo "java.security patched for Jack TLS compatibility"
fi

# ---- build environment --------------------------------------------------------
export LC_ALL=C
export JACK_SERVER_VM_ARGUMENTS="-Dfile.encoding=UTF-8 -XX:+TieredCompilation -Xmx6g"
export ANDROID_JACK_VM_ARGS="$JACK_SERVER_VM_ARGUMENTS"
export USER_NAME="ast_os"
export USER_EMAIL="ast_os@users.noreply.github.com"
export BUILD_USERNAME="AstOs-CI"
export BUILD_HOSTNAME="github-actions"
# The TB3710F tree doesn't define CM_SRC_API_DIR, so the CM SDK api-drift
# check resolves to a bogus /7.txt dependency. Skip the check (dev-only).
export WITHOUT_CHECK_API=true

cd "$ROMDIR"

echo "::group::lunch astos_TB3710F-userdebug"
source build/envsetup.sh
lunch astos_TB3710F-userdebug
echo "::endgroup::"

# Some 14.1 trees define mka (which needs schedtool, installed by
# 05-prepare-host.sh); older/newer envsetups dropped it — provide a fallback.
if ! type mka >/dev/null 2>&1; then
    mka() { mk_timer $(get_make_command) "$@"; }
fi

echo "::group::mka bacon"
mka bacon -j6
echo "::endgroup::"

OUT="$ROMDIR/out/target/product/TB3710F"
echo "Build outputs:"
ls -lh "$OUT"/*.zip || true

# Copy the ROM zip(s) out with a clean AstOs name
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
STAMP="$(date +%Y%m%d)"
for z in "$OUT"/lineage-14.1*.zip "$OUT"/astos*.zip; do
    [ -f "$z" ] || continue
    cp "$z" "$REPO_ROOT/AstOs-2.1-$STAMP-TB3-710F.zip"
    break
done

# ---- updater-script parse gate (TWRP "ERROR: 6" can never ship again) --------
# Exit code 6 from the updater means the edify script failed to PARSE. The
# released 2.0 zips had a hand-rewritten assert with unbalanced parens (the
# old fix_ota_assert.py post-processor — now deleted). The assert is now born
# correct (40-embed-astos.sh patches the tree's edify_generator.py), and this
# gate parses the finished zip's updater-script with the REAL edify grammar
# to guarantee it before release.
python3 "$REPO_ROOT/scripts/check_updater_script.py" "$REPO_ROOT/AstOs-2.1-$STAMP-TB3-710F.zip" || {
    echo "FATAL: updater-script failed the edify parse gate — zip must NOT ship" >&2
    exit 1
}

# ---- kernel as a standalone rescue asset --------------------------------------
# A frozen-at-logo boot almost always means the boot partition never got the
# new kernel (the OTA writes it via /dev/bootimg, which not every TWRP build
# maps). Shipping boot.img separately makes the rescue a 2-minute TWRP
# "Install Image -> Boot" job (see FLASH.md Troubleshooting). Same bytes as
# the boot.img inside the zip — verify against the release-listed sha256.
if [ -f "$OUT/boot.img" ]; then
    cp "$OUT/boot.img" "$REPO_ROOT/AstOs-2.1-$STAMP-TB3-710F-boot.img"
    ls -lh "$REPO_ROOT"/AstOs-*-boot.img
else
    echo "WARNING: $OUT/boot.img missing — boot rescue asset skipped (zip still contains it)" >&2
fi

ls -lh "$REPO_ROOT"/AstOs-*.zip
echo "ROM built OK"
