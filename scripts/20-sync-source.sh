#!/usr/bin/env bash
# 20-sync-source.sh — shallow-sync LineageOS 14.1 + TB3-710F device sources.
# Everything lives on /mnt (the big runner volume).
set -e

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
ROMDIR="${ROMDIR:-/mnt/rom}"

# repo tool
mkdir -p "$HOME/bin"
curl -s https://storage.googleapis.com/git-repo-downloads/repo > "$HOME/bin/repo"
chmod +x "$HOME/bin/repo"
export PATH="$HOME/bin:$PATH"

git config --global user.name "AstOs Builder"
git config --global user.email "ast_os@users.noreply.github.com"
git config --global color.ui false

sudo mkdir -p "$ROMDIR"
sudo chown -R "$(id -u):$(id -g)" "$ROMDIR"

cd "$ROMDIR"

echo "::group::repo init (cm-14.1)"
repo init \
    -u https://github.com/LineageOS/android.git \
    -b cm-14.1 \
    --depth=1
echo "::endgroup::"

mkdir -p .repo/local_manifests
cp "$REPO_ROOT/local_manifests/astos.xml" .repo/local_manifests/astos.xml

echo "::group::repo sync"
repo sync -c --no-tags --optimized-fetch -j8
echo "::endgroup::"

echo "::group::Disk after sync"
df -h /mnt || true
du -sh "$ROMDIR" || true
echo "::endgroup::"

echo "Source synced OK"
