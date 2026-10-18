#!/usr/bin/env bash
# 10-free-disk.sh — reclaim runner disk before the ROM build.
# NOTE: the Android SDK is NOT removed here (AstShell Gradle build needs it);
# it is deleted later in 40-embed-astos.sh.
set -e

echo "::group::Disk usage before cleanup"
df -h / /mnt || true
echo "::endgroup::"

sudo rm -rf \
    /usr/share/dotnet \
    /usr/local/lib/node_modules \
    /opt/ghc \
    /usr/local/.ghcup \
    /opt/hostedtoolcache/CodeQL \
    /usr/local/share/boost \
    /usr/local/share/chromium \
    /usr/local/bin/phantomjs \
    /usr/share/swift 2>/dev/null || true

sudo docker image prune -af >/dev/null 2>&1 || true
sudo apt-get clean >/dev/null 2>&1 || true

echo "::group::Disk usage after cleanup"
df -h / /mnt || true
echo "::endgroup::"
