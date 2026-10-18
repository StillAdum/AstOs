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

# ncurses5 / tinfo5 compat symlinks for old host binaries (old clang and
# friends link against libncurses.so.5 / libtinfo.so.5, both 64-bit and
# 32-bit variants)
for d in /usr/lib/x86_64-linux-gnu /usr/lib32 /lib32 /usr/lib/i386-linux-gnu; do
    if [ -d "$d" ]; then
        [ -e "$d/libtinfo.so.5" ] || sudo ln -sf libtinfo.so.6 "$d/libtinfo.so.5" 2>/dev/null || true
        [ -e "$d/libncurses.so.5" ] || sudo ln -sf libncurses.so.6 "$d/libncurses.so.5" 2>/dev/null || true
        [ -e "$d/libncursesw.so.5" ] || sudo ln -sf libncursesw.so.6 "$d/libncursesw.so.5" 2>/dev/null || true
    fi
done
ldconfig 2>/dev/null || sudo ldconfig 2>/dev/null || true

# ---- schedtool (mka requires it; not in Ubuntu 24.04) ----------------------
if ! command -v schedtool >/dev/null 2>&1; then
    echo "Building schedtool from source…"
    rm -rf /tmp/st.tar.gz /tmp/schedtool*
    curl -sL -o /tmp/st.tar.gz "https://deb.debian.org/debian/pool/main/s/schedtool/schedtool_1.3.0.orig.tar.gz"
    tar xf /tmp/st.tar.gz -C /tmp
    cd /tmp/schedtool*/
    make -j"$(nproc)" >/dev/null
    sudo install -m 0755 schedtool /usr/local/bin/schedtool
    cd -
fi
command -v schedtool && schedtool -v || true

echo "Host dependencies ready"
