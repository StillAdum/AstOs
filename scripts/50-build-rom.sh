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

# ---- build environment --------------------------------------------------------
export LC_ALL=C
export JACK_SERVER_VM_ARGUMENTS="-Dfile.encoding=UTF-8 -XX:+TieredCompilation -Xmx6g"
export ANDROID_JACK_VM_ARGS="$JACK_SERVER_VM_ARGUMENTS"
export USER_NAME="ast_os"
export USER_EMAIL="ast_os@users.noreply.github.com"
export BUILD_USERNAME="AstOs-CI"
export BUILD_HOSTNAME="github-actions"

cd "$ROMDIR"

echo "::group::lunch astos_TB3710F-userdebug"
source build/envsetup.sh
lunch astos_TB3710F-userdebug
echo "::endgroup::"

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
    cp "$z" "$REPO_ROOT/AstOs-1.0-$STAMP-TB3-710F.zip"
    break
done

ls -lh "$REPO_ROOT"/AstOs-*.zip
echo "ROM built OK"
