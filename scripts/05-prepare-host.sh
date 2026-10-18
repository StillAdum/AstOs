#!/usr/bin/env bash
# 05-prepare-host.sh — install the classic LineageOS 14.1 build dependencies
# that Ubuntu 24.04 runners no longer ship (incl. building schedtool from
# source — it was dropped from Ubuntu after 18.04 but mka needs it).
set -e

export DEBIAN_FRONTEND=noninteractive

sudo apt-get update -q

# 64-bit build tools
sudo apt-get install -y --no-install-recommends \
    build-essential gcc-multilib g++-multilib \
    flex bison gperf bc rsync lzop \
    libxml2-utils xsltproc zip unzip curl file \
    libncurses5-dev libtinfo5 2>/dev/null || \
sudo apt-get install -y --no-install-recommends \
    build-essential gcc-multilib g++-multilib \
    flex bison gperf bc rsync lzop \
    libxml2-utils xsltproc zip unzip curl file || true

# 32-bit host runtime libs (old arm-linux-androideabi-4.9 toolchain is a
# 32-bit host binary on 14.1)
sudo dpkg --add-architecture i386 || true
sudo apt-get update -q
sudo apt-get install -y --no-install-recommends \
    lib32z1 lib32ncurses6 lib32stdc++6 lib32gcc-s1 2>/dev/null || \
sudo apt-get install -y --no-install-recommends \
    lib32z1 lib32tinfo6 lib32stdc++6 lib32gcc-s1 2>/dev/null || true

# ncurses5 / tinfo5 compat symlinks for old host binaries
for d in /usr/lib32 /lib32 /usr/lib/i386-linux-gnu; do
    if [ -d "$d" ]; then
        [ -e "$d/libtinfo.so.5" ] || sudo ln -sf libtinfo.so.6 "$d/libtinfo.so.5" 2>/dev/null || true
        [ -e "$d/libncurses.so.5" ] || sudo ln -sf libncurses.so.6 "$d/libncurses.so.5" 2>/dev/null || true
        [ -e "$d/libz.so.1" ] || true
    fi
done

# ---- schedtool (mka requires it; not in Ubuntu 24.04) ----------------------
if ! command -v schedtool >/dev/null 2>&1; then
    echo "Building schedtool from source…"
    cd /tmp
    curl -sL -o st.tar.gz "https://deb.debian.org/debian/pool/main/s/schedtool/schedtool_1.3.0.orig.tar.gz"
    tar xf st.tar.gz
    cd schedtool-1.3.0
    make -j"$(nproc)" >/dev/null
    sudo install -m 0755 schedtool /usr/local/bin/schedtool
fi
command -v schedtool && schedtool -v || true

echo "Host dependencies ready"
