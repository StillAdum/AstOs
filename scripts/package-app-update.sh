#!/usr/bin/env bash
# Build already-compiled AstShell into a signed, data-preserving app repair mod.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; OUT="${1:-$ROOT/app-update}";mkdir -p "$OUT"
bash "$ROOT/mods/playback-tools-preview/sign.sh" "$ROOT/astshell/app/build/outputs/apk/release/app-release-unsigned.apk" "$OUT/AstShell-3.5.apk"
bash "$ROOT/mods/playback-tools-preview/sign.sh" "$ROOT/astsetup/app/build/outputs/apk/release/app-release-unsigned.apk" "$OUT/AstSetup-3.5.apk"
bash "$ROOT/mods/playback-tools-preview/build-installer.sh" "$OUT/installer.jar"
python3 - "$ROOT" "$OUT" <<'PY'
import sys,pathlib,json,zipfile
root,out=map(pathlib.Path,sys.argv[1:]);ident='astos35_update'
manifest={'id':ident,'name':'AstOS 3.5 app repair','version':'1.0.0','author':'AstOS','main':'plugin.js','permissions':['ui','system'],'description':'Installs the signed AstShell 3.5 app update after your confirmation, preserving data. Full ROM is still needed for AstSetup and system changes.'}
code='var MOD_ID='+json.dumps(ident)+';var MOD_TITLE="Install AstShell 3.5";\n'+(root/'mods/playback-tools-preview/installer.js').read_text()
with zipfile.ZipFile(out/'AstOS-3.5-App-Repair.astpl','w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('manifest.json',json.dumps(manifest));z.writestr('plugin.js',code);z.write(out/'installer.jar','installer.jar');z.write(out/'AstShell-3.5.apk','AstShell.apk')
PY
cp "$ROOT/spicetify/astos.js" "$ROOT/docs/RELEASE-3.5.md" "$OUT/"
(cd "$OUT" && sha256sum *.apk *.astpl astos.js > SHA256SUMS.txt)
