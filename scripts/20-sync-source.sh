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

# ---- chromium-webview prebuilt: git-LFS pointer resolution -------------------
# LineageOS stores the webview prebuilts in git-LFS repos (see .gitattributes
# in android_external_chromium-webview_prebuilt_arm). `repo sync` does NOT
# resolve LFS pointers, so external/chromium-webview/prebuilt/arm/webview.apk
# ends up a 133-byte text pointer. The ROM build then dies at ~91% inside
# signapk with "java.util.zip.ZipException: error in opening zip file"
# (observed in run #14). Resolve the pointer through the LineageOS gerrit
# LFS batch API and verify the sha256 (the LFS oid) before continuing.
WV="$ROMDIR/external/chromium-webview/prebuilt/arm/webview.apk"
if [ -f "$WV" ] && head -1 "$WV" | grep -q "git-lfs"; then
    OID="$(sed -n 's/^oid sha256://p' "$WV")"
    SIZE="$(sed -n 's/^size //p' "$WV")"
    echo "webview.apk is an LFS pointer (oid=$OID size=$SIZE) — resolving via gerrit LFS"
    HREF="$(curl -s --retry 5 --retry-all-errors -X POST \
        "https://review.lineageos.org/LineageOS/android_external_chromium-webview_prebuilt_arm.git/info/lfs/objects/batch" \
        -H "Content-Type: application/vnd.git-lfs+json" \
        -H "Accept: application/vnd.git-lfs+json" \
        -d "{\"operation\":\"download\",\"objects\":[{\"oid\":\"$OID\",\"size\":$SIZE}]}" \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); o=d["objects"][0]; print(o.get("actions",{}).get("download",{}).get("href",""))')"
    test -n "$HREF" || { echo "FATAL: gerrit LFS batch API returned no download href"; exit 1; }
    curl -s --retry 5 --retry-all-errors -o "$WV.real" "$HREF"
    echo "$OID  $WV.real" | sha256sum -c - || { echo "FATAL: webview.apk sha256 mismatch (LFS download corrupt)"; exit 1; }
    mv "$WV.real" "$WV"
else
    echo "webview.apk already a real binary"
fi
head -c 2 "$WV" | grep -q "PK" || { echo "FATAL: webview.apk is not a zip archive (LFS resolution failed)"; exit 1; }
ls -lh "$WV"
echo "webview prebuilt OK"

# ---- sweep for any other unresolved LFS pointers (warn only) -----------------
BADW="$(find "$ROMDIR/external" "$ROMDIR/vendor" -maxdepth 6 -type f -size -300c \
        \( -name "*.apk" -o -name "*.zip" -o -name "*.jar" \) \
        -exec grep -l "oid sha256:" {} \; 2>/dev/null | head -5 || true)"
if [ -n "$BADW" ]; then
    echo "::warning::possible unresolved LFS pointers:"
    echo "$BADW"
fi

echo "::group::Disk after sync"
df -h /mnt || true
du -sh "$ROMDIR" || true
echo "::endgroup::"

echo "Source synced OK"
